package me.code4me.services.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.code4me.api.wrapper.CookieAwareApiClient
import me.code4me.services.app.getAppService
import me.code4me.services.state.getPrefState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Local OpenAI-compatible HTTP relay hosted inside the plugin so third-party agent runtimes
 * (Goose, Codex) can talk to `http://127.0.0.1:<port>/v1/chat/completions` without needing
 * direct access to the Code4Me server or any credential of their own. Each request is enriched
 * with the current pending `task_id` plus IDE context and forwarded to the authenticated server
 * endpoint `POST /api/agent/inference`.
 *
 * This is the observation point for agents whose internals we do not control: it sees every
 * inference call they make, before it leaves the machine. Agent runtimes we *do* control
 * (`code4me2-agent`) self-report to the backend instead and never go through here.
 *
 * Streaming responses (SSE) are copied byte-for-byte with frequent flushes so chunks
 * propagate to the agent as they arrive.
 *
 * Lifecycle: started from PluginStartupActivity, stopped via a JVM shutdown hook.
 * `start()` is idempotent.
 */
object LocalProxyServer {
    private val LOG = thisLogger()

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var boundPort: Int = -1

    @Volatile
    private var activeProject: Project? = null

    private val shutdownHookRegistered = AtomicBoolean(false)

    private val idleScheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "LocalProxyServer-idle-close").apply { isDaemon = true }
        }
    private val pendingIdleClose = AtomicReference<ScheduledFuture<*>?>()

    private const val IDLE_CLOSE_DELAY_SEC = 60L

    @Synchronized
    fun start(project: Project? = null) {
        LOG.info("[LocalProxyServer] start() called (project=${project?.name})")
        if (project != null) activeProject = project
        if (ApplicationManager.getApplication().isUnitTestMode) {
            LOG.info("[LocalProxyServer] unit test mode — not starting local proxy server")
            return
        }
        val targetPort = getPrefState().localProxyPort
        if (server != null && boundPort == targetPort) {
            LOG.info("[LocalProxyServer] already listening on 127.0.0.1:$boundPort")
            return
        }
        if (server != null) {
            stop()
        }

        try {
            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", targetPort), 0)
            httpServer.executor = Executors.newFixedThreadPool(4)
            // Both Chat Completions (Goose) and Responses API (Codex) are relayed through
            // the same handle() function — the body is forwarded opaquely to /api/agent/inference
            // and the server routes to the correct upstream endpoint based on body shape.
            // Goose may call /v1/chat/completions (OPENAI_BASE_URL) or /chat/completions
            // (GOOSE_PROVIDER__BASE_URL, no /v1 suffix) depending on which provider path it
            // takes internally. Register both so neither falls through to the server directly.
            httpServer.createContext("/v1/chat/completions") { exchange -> handle(exchange) }
            httpServer.createContext("/chat/completions") { exchange -> handle(exchange) }
            httpServer.createContext("/v1/responses") { exchange -> handle(exchange) }
            httpServer.start()
            server = httpServer
            boundPort = targetPort
            LOG.info("[LocalProxyServer] listening on 127.0.0.1:$targetPort")

            if (shutdownHookRegistered.compareAndSet(false, true)) {
                Runtime.getRuntime().addShutdownHook(Thread({ stop() }, "LocalProxyServer-shutdown"))
            }
        } catch (e: Exception) {
            LOG.warn("[LocalProxyServer] failed to start on 127.0.0.1:$targetPort", e)
        }
    }

    @Synchronized
    fun stop() {
        val current = server ?: return
        try {
            current.stop(0)
            LOG.info("[LocalProxyServer] stopped (was on 127.0.0.1:$boundPort)")
        } catch (e: Exception) {
            LOG.warn("[LocalProxyServer] error stopping server", e)
        } finally {
            server = null
            boundPort = -1
            pendingIdleClose.getAndSet(null)?.cancel(false)
        }
    }

    /** Base URL agent runtimes should be pointed at. */
    fun baseUrl(): String = "http://127.0.0.1:${getPrefState().localProxyPort}"

    private fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.path
        LOG.info("[LocalProxyServer] → $method $path")
        try {
            if (method != "POST") {
                LOG.warn("[LocalProxyServer] rejected $method $path — only POST allowed")
                respondPlain(exchange, 405, "method not allowed")
                return
            }

            val incomingBytes = exchange.requestBody.use { it.readBytes() }
            LOG.info("[LocalProxyServer] request body: ${incomingBytes.size} bytes")
            val incomingJson =
                try {
                    Json.parseToJsonElement(incomingBytes.toString(Charsets.UTF_8)).jsonObject
                } catch (e: Exception) {
                    LOG.warn("[LocalProxyServer] invalid JSON body", e)
                    respondPlain(exchange, 400, "invalid JSON body")
                    return
                }

            val taskId = getOrCreateTaskId()
            if (taskId.isNullOrBlank()) {
                LOG.warn("[LocalProxyServer] pendingTaskId is blank — agent task not provisioned yet")
                respondPlain(exchange, 503, "agent task not yet provisioned")
                return
            }
            LOG.info("[LocalProxyServer] enriching request with taskId=$taskId")

            // /v1/responses is Codex's Responses API; everything else (chat completions) is Goose.
            val frameworkVersion =
                if (path == "/v1/responses") {
                    "codex"
                } else {
                    getPrefState().agentVersion?.takeIf { it.isNotBlank() }?.let { "goose $it" }
                }

            // Content storage consent is enforced server-side, but mirroring it here means the
            // editor selection (source code) is never even transmitted when the user opted out.
            val contentIncluded = getPrefState().storeAgentContent

            val enrichmentFields =
                mutableMapOf<String, JsonElement>(
                    "content_included" to JsonPrimitive(contentIncluded),
                )
            if (frameworkVersion != null) {
                enrichmentFields["framework_version"] = JsonPrimitive(frameworkVersion)
            }

            // Capture the IDE editor context: the active file path is structural metadata
            // (sent unconditionally), while the selection is source code and only sent when
            // the user has opted into content storage.
            activeProject?.takeUnless { it.isDisposed }?.let { proj ->
                val fileCtx = AgentContextProvider().activeFileContext(proj)
                fileCtx.path?.let { enrichmentFields["active_file"] = JsonPrimitive(it) }
                if (contentIncluded) {
                    fileCtx.selectedText?.let {
                        enrichmentFields["selected_text"] = JsonPrimitive(it)
                    }
                }
            }
            val enrichment = JsonObject(enrichmentFields)
            val wrapped =
                JsonObject(
                    mapOf(
                        "task_id" to JsonPrimitive(taskId),
                        "request" to incomingJson,
                        "enrichment" to enrichment,
                    ),
                ).toString()

            val baseUrl = getAppService().getApiBaseUrl()
            if (baseUrl.isBlank()) {
                LOG.warn("[LocalProxyServer] server base URL is not configured")
                respondPlain(exchange, 503, "server base URL not configured")
                return
            }

            val upstreamUrl = "$baseUrl/api/agent/inference"
            LOG.info("[LocalProxyServer] forwarding to $upstreamUrl")

            val request =
                Request.Builder()
                    .url(upstreamUrl)
                    .post(wrapped.toRequestBody("application/json".toMediaType()))
                    .build()

            CookieAwareApiClient.sharedOkHttpClient.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type") ?: "application/json"
                val isStream = contentType.startsWith("text/event-stream")
                LOG.info("[LocalProxyServer] upstream responded ${response.code} content-type=$contentType stream=$isStream")
                exchange.responseHeaders.set("Content-Type", contentType)

                if (isStream) {
                    exchange.sendResponseHeaders(response.code, 0)
                    response.body?.byteStream()?.let { input ->
                        var totalBytes = 0
                        exchange.responseBody.use { output ->
                            val buffer = ByteArray(1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                output.flush()
                                totalBytes += read
                            }
                        }
                        LOG.info("[LocalProxyServer] SSE relay complete — $totalBytes bytes streamed")
                    } ?: exchange.responseBody.close()
                } else {
                    val bodyBytes = response.body?.bytes() ?: ByteArray(0)
                    LOG.info("[LocalProxyServer] JSON response — ${bodyBytes.size} bytes")
                    exchange.sendResponseHeaders(response.code, bodyBytes.size.toLong())
                    exchange.responseBody.use { it.write(bodyBytes) }
                }
            }
            resetIdleTimer(taskId)
        } catch (e: Exception) {
            LOG.warn("[LocalProxyServer] error handling request", e)
            try {
                respondPlain(exchange, 502, "upstream error: ${e.message}")
            } catch (_: Exception) {
                // headers already sent — nothing we can do
            }
        } finally {
            try {
                exchange.close()
            } catch (_: Exception) {
                // best effort
            }
        }
    }

    private fun resetIdleTimer(taskId: String) {
        pendingIdleClose.getAndSet(null)?.cancel(false)
        pendingIdleClose.set(
            idleScheduler.schedule({
                val prefs = getPrefState()
                if (prefs.pendingTaskId != taskId) return@schedule
                try {
                    runBlocking { getAppService().closeAgentTask(UUID.fromString(taskId)) }
                    prefs.pendingTaskId = null
                    LOG.info("[LocalProxyServer] idle close — task $taskId marked done after ${IDLE_CLOSE_DELAY_SEC}s")
                } catch (e: Exception) {
                    LOG.warn("[LocalProxyServer] idle close failed for task $taskId", e)
                }
            }, IDLE_CLOSE_DELAY_SEC, TimeUnit.SECONDS),
        )
    }

    private fun getOrCreateTaskId(): String? {
        val existing = getPrefState().pendingTaskId
        if (!existing.isNullOrBlank()) return existing
        val project = activeProject
        if (project == null || project.isDisposed) return null
        return try {
            runBlocking { AgentStartupManager.ensureActiveTask(project) }
            getPrefState().pendingTaskId
        } catch (e: Exception) {
            LOG.warn("[LocalProxyServer] lazy task provisioning failed", e)
            null
        }
    }

    private fun respondPlain(
        exchange: HttpExchange,
        status: Int,
        message: String,
    ) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
