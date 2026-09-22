package me.code4me.research.spool

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import me.code4me.research.telemetry.CanonicalEvent
import me.code4me.research.telemetry.EventSource
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.parseCanonicalJson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A loopback-only IPC endpoint that accepts already-filtered canonical events
 * from the research proxy and appends them to a [DurableSpool].
 *
 * Implementations must expose the endpoint and the per-session capability and
 * must release the socket in [close] (idempotent).
 */
interface SpoolIpcServer {
    /** The authenticated loopback URL the proxy POSTs to. */
    val endpointUrl: String

    /** The fresh per-session capability the proxy must present. */
    val capability: String

    /** Stop accepting deliveries; safe to call more than once. */
    fun close()
}

/**
 * Authority of one authenticated IPC endpoint, supplied by the active IDE session.
 *
 * [agentRunId] is the native run minted for this activation. It is stamped only
 * on ACP-source events: IDE activity belongs to the session, not to a run, and
 * keeps `agent_run_id = null` (TA-04).
 */
data class SpoolEventContext(
    val studyId: String,
    val enrollmentId: String,
    val researchSessionId: String,
    val agentRunId: String? = null,
) {
    fun bind(event: CanonicalEvent): CanonicalEvent {
        require(event.studyId == null || event.studyId == studyId) { "Study context mismatch" }
        require(event.enrollmentId == null || event.enrollmentId == enrollmentId) { "Enrollment context mismatch" }
        require(event.researchSessionId == null || event.researchSessionId == researchSessionId) { "Session context mismatch" }
        // A run id on the event is authoritative, but it must agree with this
        // activation when both are present: a different run is another window's
        // event and is rejected, never relabelled.
        require(agentRunId == null || event.agentRunId == null || event.agentRunId == agentRunId) {
            "Agent run context mismatch"
        }
        val boundRunId =
            event.agentRunId
                ?: agentRunId?.takeIf { event.source == EventSource.ACP }
        return event.copy(
            studyId = studyId,
            enrollmentId = enrollmentId,
            researchSessionId = researchSessionId,
            agentRunId = boundRunId,
        )
    }
}

/**
 * Local, authenticated spool IPC server (Gap 2).
 *
 * Contract:
 * - binds only [InetAddress.getLoopbackAddress] on an ephemeral port;
 * - every request must carry `X-Research-Capability` equal (constant-time) to
 *   the fresh per-session [capability], otherwise `401` and no append;
 * - `POST /spool` accepts `{schema_version, proxy_digest?, emitter_id?,
 *   events:[<canonical event>...]}` and appends each event durably (the
 *   [DurableSpool] `fsync`s) **before** responding;
 * - an `event_id` already present in the spool is answered as `duplicate` and
 *   is never appended twice;
 * - malformed JSON is `400`, an oversized body is `413`, and a bad schema is
 *   `400`;
 * - it never writes protocol frames to stdout.
 *
 * @property spool the durable local spool that remains the delivery authority.
 * @property maxBodyBytes hard limit on an accepted request body.
 * @property maxEventsPerRequest hard limit on events in one request.
 */
class ResearchSpoolIpcServer(
    private val spool: DurableSpool,
    capability: String? = null,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    private val maxEventsPerRequest: Int = DEFAULT_MAX_EVENTS_PER_REQUEST,
    private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    private val onEventAppended: (CanonicalEvent) -> Unit = {},
    private val eventContext: SpoolEventContext? = null,
) : SpoolIpcServer {
    init {
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive" }
        require(maxEventsPerRequest > 0) { "maxEventsPerRequest must be positive" }
    }

    override val capability: String =
        capability?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().replace("-", "")

    private val server: HttpServer = HttpServer.create(InetSocketAddress(bindAddress, 0), 0)

    // Daemon request threads: a leaked (never closed) server must never keep a
    // host JVM alive, and teardown must be able to release the socket promptly.
    private val executor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }

    @Volatile private var closed = false

    override val endpointUrl: String
        get() = "http://${hostFor(server.address.address)}:${server.address.port}$SPOOL_PATH"

    init {
        server.executor = executor
        server.createContext(SPOOL_PATH) { exchange -> handleSafely(exchange) }
        server.start()
    }

    override fun close() {
        if (closed) return
        closed = true
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handleSafely(exchange: HttpExchange) {
        try {
            handle(exchange)
        } catch (_: Exception) {
            runCatching { respond(exchange, HTTP_INTERNAL_ERROR, mapOf("error" to "spool unavailable")) }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            respond(exchange, HTTP_METHOD_NOT_ALLOWED, mapOf("error" to "method not allowed"))
            return
        }
        if (!authorized(exchange)) {
            respond(exchange, HTTP_UNAUTHORIZED, mapOf("error" to "invalid capability"))
            return
        }
        val declaredLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declaredLength != null && declaredLength > maxBodyBytes) {
            respond(exchange, HTTP_PAYLOAD_TOO_LARGE, mapOf("error" to "body too large"))
            return
        }
        val body = readBounded(exchange.requestBody)
        if (body == null) {
            respond(exchange, HTTP_PAYLOAD_TOO_LARGE, mapOf("error" to "body too large"))
            return
        }
        val parsed =
            try {
                parseCanonicalJson(body)
            } catch (_: Exception) {
                respond(exchange, HTTP_BAD_REQUEST, mapOf("error" to "malformed json"))
                return
            }
        if (parsed !is Map<*, *>) {
            respond(exchange, HTTP_BAD_REQUEST, mapOf("error" to "body must be a json object"))
            return
        }
        val schemaVersion = parsed["schema_version"] as? String
        if (schemaVersion != SCHEMA_VERSION) {
            respond(exchange, HTTP_BAD_REQUEST, mapOf("error" to "unsupported schema_version"))
            return
        }
        val rawEvents = parsed["events"]
        if (rawEvents !is List<*>) {
            respond(exchange, HTTP_BAD_REQUEST, mapOf("error" to "events must be a list"))
            return
        }
        if (rawEvents.size > maxEventsPerRequest) {
            respond(exchange, HTTP_PAYLOAD_TOO_LARGE, mapOf("error" to "too many events"))
            return
        }
        val events = ArrayList<CanonicalEvent>(rawEvents.size)
        val invalid = ArrayList<Map<String, Any?>>()
        for (raw in rawEvents) {
            val parsed = parseEvent(raw)
            val bound = parsed?.let { event -> runCatching { eventContext?.bind(event) ?: event }.getOrNull() }
            when {
                bound != null -> events.add(bound)
                else -> {
                    val id = (raw as? Map<*, *>)?.get("event_id") as? String
                    // Distinguish a well-formed event for another session from a
                    // malformed one: operators need to tell them apart.
                    val reason = if (parsed != null) "context_mismatch" else "invalid_event"
                    invalid.add(linkedMapOf("event_id" to id, "reason" to reason))
                }
            }
        }
        val outcome = appendAll(events)
        respond(
            exchange,
            HTTP_OK,
            linkedMapOf(
                "accepted" to outcome.accepted,
                "duplicate" to outcome.duplicate,
                "rejected" to (invalid + outcome.rejected),
            ),
        )
    }

    private data class AppendOutcome(
        val accepted: List<String>,
        val duplicate: List<String>,
        val rejected: List<Map<String, Any?>>,
    )

    private fun appendAll(events: List<CanonicalEvent>): AppendOutcome {
        val existing = spool.existingEventIds(events.map { it.eventId })
        val accepted = ArrayList<String>()
        val duplicate = ArrayList<String>()
        val rejected = ArrayList<Map<String, Any?>>()
        val appended = HashSet<String>()
        for (event in events) {
            val id = event.eventId
            if (id in existing || !appended.add(id)) {
                duplicate.add(id)
                notifyAppended(event)
                continue
            }
            try {
                spool.append(event)
                accepted.add(id)
                notifyAppended(event)
            } catch (_: Exception) {
                appended.remove(id)
                rejected.add(linkedMapOf("event_id" to id, "reason" to "append_failed"))
            }
        }
        return AppendOutcome(accepted, duplicate, rejected)
    }

    /**
     * Notify the optional observer that [event] is known to the spool. Never
     * throws: an observer failure must not change the delivery response.
     */
    private fun notifyAppended(event: CanonicalEvent) {
        runCatching { onEventAppended(event) }
    }

    private fun parseEvent(raw: Any?): CanonicalEvent? {
        val map = raw as? Map<*, *> ?: return null
        @Suppress("UNCHECKED_CAST")
        val stringKeyed = map as? Map<String, Any?>
        if (stringKeyed == null) {
            val converted = LinkedHashMap<String, Any?>()
            for ((key, value) in map) {
                converted[key?.toString() ?: return null] = value
            }
            return runCatching { CanonicalEvent.fromCanonicalMap(converted) }.getOrNull()
        }
        return runCatching { CanonicalEvent.fromCanonicalMap(stringKeyed) }.getOrNull()
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        val provided = exchange.requestHeaders.getFirst(CAPABILITY_HEADER) ?: return false
        return MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.UTF_8),
            capability.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun readBounded(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBodyBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toString(StandardCharsets.UTF_8)
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        payload: Map<String, Any?>,
    ) {
        val bytes = canonicalJson(payload).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun hostFor(address: InetAddress): String =
        if (address.address.size == IPV6_BYTES) "[${address.hostAddress}]" else address.hostAddress

    companion object {
        const val CAPABILITY_HEADER: String = "X-Research-Capability"
        const val SPOOL_PATH: String = "/spool"
        const val SCHEMA_VERSION: String = "1"
        const val DEFAULT_MAX_BODY_BYTES: Int = 2_000_000
        const val DEFAULT_MAX_EVENTS_PER_REQUEST: Int = 500

        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_INTERNAL_ERROR = 500
        private const val READ_BUFFER_BYTES = 8_192
        private const val IPV6_BYTES = 16
        private const val THREAD_NAME = "code4me-research-spool-ipc"
    }
}
