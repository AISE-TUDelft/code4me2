package me.code4me.services.agent

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.code4me.services.state.DEFAULT_AGENT_LAUNCH_MODEL
import me.code4me.services.state.getPrefState
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Locates the Goose binary and builds the environment it is launched with.
 *
 * Goose is an OpenAI-compatible client, so pointing it at [LocalProxyServer] is enough to route
 * every inference call it makes through Code4Me without patching Goose itself.
 */
object GooseRuntime {
    private val LOG = thisLogger()

    fun detect(): String? {
        val prefs = getPrefState()

        val stored = prefs.agentPath
        if (!stored.isNullOrBlank() && File(stored).exists()) {
            LOG.info("[GooseRuntime] Goose path from cache: $stored")
            ensureVersionCached(stored)
            return stored
        }

        // Try system PATH first.
        val fromPath = detectViaPath()
        if (fromPath != null) {
            prefs.agentPath = fromPath
            ensureVersionCached(fromPath)
            return fromPath
        }

        // Scan the JetBrains ACP registry install directory directly — works even before any
        // acp.json has been written (e.g. fresh install of Goose via JetBrains AI Assistant).
        val fromRegistry = detectViaAcpRegistry()
        if (fromRegistry != null) {
            prefs.agentPath = fromRegistry
            ensureVersionCached(fromRegistry)
            return fromRegistry
        }

        // Last resort: read the command field from an existing acp.json on disk.
        val fromAcp = detectViaAcpJson()
        if (fromAcp != null) {
            prefs.agentPath = fromAcp
            ensureVersionCached(fromAcp)
            return fromAcp
        }

        LOG.warn("[GooseRuntime] Goose binary not found via PATH, ACP registry, or acp.json")
        return null
    }

    /**
     * Queries `<goosePath> --version` once and caches the result so the proxy can tag
     * telemetry with `framework_version` without shelling out on every request.
     */
    private fun ensureVersionCached(goosePath: String) {
        val prefs = getPrefState()
        if (!prefs.agentVersion.isNullOrBlank()) return

        val version =
            try {
                val process = ProcessBuilder(goosePath, "--version").redirectErrorStream(true).start()
                val finished = process.waitFor(5, TimeUnit.SECONDS)
                if (!finished) {
                    LOG.warn("[GooseRuntime] '$goosePath --version' timed out after 5s")
                    process.destroyForcibly()
                    null
                } else {
                    val output = process.inputStream.bufferedReader().readText().trim()
                    output.lines().firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {
                LOG.warn("[GooseRuntime] Failed to detect Goose version", e)
                null
            }

        if (version != null) {
            prefs.agentVersion = version
            LOG.info("[GooseRuntime] Goose version detected and cached: $version")
        }
    }

    private fun detectViaPath(): String? =
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val cmd = if (isWindows) listOf("where", "goose") else listOf("which", "goose")
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val result = process.inputStream.bufferedReader().readLine()?.trim()
            process.waitFor()
            if (!result.isNullOrBlank() && File(result).exists()) {
                LOG.info("[GooseRuntime] Goose detected via system PATH at: $result")
                result
            } else {
                null
            }
        } catch (e: Exception) {
            LOG.debug("[GooseRuntime] PATH lookup for Goose failed", e)
            null
        }

    private fun detectViaAcpRegistry(): String? {
        return try {
            val osName = System.getProperty("os.name").lowercase()
            val isWindows = osName.contains("win")
            val isMac = osName.contains("mac")
            val home = System.getProperty("user.home")

            // Platform-specific root where JetBrains products store per-IDE data.
            val jetbrainsRoot =
                when {
                    isWindows -> File(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "JetBrains")
                    isMac -> File(home, "Library/Application Support/JetBrains")
                    else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "JetBrains")
                }

            if (!jetbrainsRoot.isDirectory) return null

            val binaryName = if (isWindows) "goose.exe" else "goose"

            // Walk: jetbrainsRoot/<IDE>/<acp-agents>/goose/<VERSION>/goose-package/<binary>
            // Pick the version directory with the latest last-modified time when multiple exist.
            jetbrainsRoot.listFiles()
                ?.filter { it.isDirectory }
                ?.flatMap { ideDir ->
                    ideDir.resolve("acp-agents/goose")
                        .listFiles()
                        ?.filter { it.isDirectory }
                        ?.mapNotNull { versionDir ->
                            val binary = versionDir.resolve("goose-package/$binaryName")
                            if (binary.exists()) Pair(versionDir.lastModified(), binary) else null
                        } ?: emptyList()
                }
                ?.maxByOrNull { it.first }
                ?.second
                ?.absolutePath
                ?.also { LOG.info("[GooseRuntime] Goose detected via ACP registry at: $it") }
        } catch (e: Exception) {
            LOG.warn("[GooseRuntime] Failed to scan ACP registry directory", e)
            null
        }
    }

    private fun detectViaAcpJson(): String? {
        return try {
            if (!AcpManager.acpFile.exists()) return null
            val root = Json.parseToJsonElement(AcpManager.acpFile.readText()).jsonObject
            val servers = root["agent_servers"]?.jsonObject ?: return null
            for ((_, entry) in servers) {
                val cmd = entry.jsonObject["command"]?.jsonPrimitive?.content ?: continue
                if (File(cmd).exists()) {
                    LOG.info("[GooseRuntime] Goose detected via acp.json at: $cmd")
                    return cmd
                }
            }
            null
        } catch (e: Exception) {
            LOG.warn("[GooseRuntime] Failed to read Goose path from acp.json", e)
            null
        }
    }

    /**
     * Builds the environment Goose is launched with.
     *
     * The proxy origin is derived from [localProxyBaseUrl] and the model from plugin settings, so
     * neither the port nor the model name appears as a literal here — changing
     * `PrefSettings.localProxyPort` or `PrefSettings.agentLaunchModel` is enough to move both.
     *
     * `task_id`/`session_id` are injected per-request by [LocalProxyServer], not here.
     *
     * - `GOOSE_PROVIDER__BASE_URL` — Goose config-layer override (no `/v1`)
     * - `OPENAI_BASE_URL` — standard env var read by the OpenAI SDK inside Goose; must include
     *   `/v1` because the SDK appends `/chat/completions`
     * - `OPENAI_API_KEY` — required by the OpenAI SDK to send an Authorization header at all.
     *   The proxy ignores the value; the real credential never leaves the server.
     */
    fun buildEnvBundle(localProxyBaseUrl: String): Map<String, String> {
        val origin = localProxyBaseUrl.trimEnd('/')
        val model = getPrefState().agentLaunchModel?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT_LAUNCH_MODEL
        return mapOf(
            "GOOSE_PROVIDER__BASE_URL" to origin,
            "GOOSE_PROVIDER" to "openai",
            "GOOSE_MODEL" to model,
            "GOOSE_PROVIDER__TYPE" to "openai",
            "GOOSE_PROVIDER__HOST" to origin,
            "GOOSE_PROVIDER__API_KEY" to PROXY_API_KEY_PLACEHOLDER,
            "OPENAI_HOST" to origin,
            "OPENAI_BASE_PATH" to "v1/chat/completions",
            "OPENAI_BASE_URL" to "$origin/v1",
            "OPENAI_API_KEY" to PROXY_API_KEY_PLACEHOLDER,
        )
    }

    /**
     * Placeholder credential handed to agent runtimes. OpenAI-compatible SDKs refuse to send a
     * request without an API key, but [LocalProxyServer] discards it and the backend authenticates
     * the relay with the user's own session cookie instead.
     */
    const val PROXY_API_KEY_PLACEHOLDER = "code4me-local-proxy"
}
