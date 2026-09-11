package me.code4me.services.agent

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Registers Code4Me-managed agent runtimes in `~/.jetbrains/acp.json`, the registry JetBrains'
 * native AI Assistant reads to populate its agent picker.
 *
 * This is the additive half of the "native as primary" merge decision: writing this file makes
 * Goose and Codex appear in the AI Assistant tool window without the plugin having to host or
 * replace any chat UI of its own. The custom [me.code4me.chatWindow.components.ChatPanel] keeps
 * serving the non-agentic chat feature untouched.
 */
object AcpManager {
    private val LOG = thisLogger()
    private val json = Json { prettyPrint = true }

    internal val acpFile: File
        get() = File(System.getProperty("user.home"), ".jetbrains/acp.json")

    /** Registers the participant runtime while preserving every unrelated ACP entry. */
    fun registerManagedAgent(executable: String, bridgeDirectory: String): Result<Unit> =
        AcpRegistryWriter(acpFile.toPath()).registerManagedAgent(executable, bridgeDirectory)

    fun writeOrUpdate(
        goosePath: String?,
        envBundle: Map<String, String>,
        localProxyBaseUrl: String,
        codexSourceDir: String? = null,
    ) {
        LOG.info("[AcpManager] writeOrUpdate called with goosePath=${goosePath ?: "<null>"}, codexSourceDir=${codexSourceDir ?: "<null>"}")
        try {
            val file = acpFile
            file.parentFile.mkdirs()

            val existing: JsonObject =
                if (file.exists()) {
                    try {
                        json.parseToJsonElement(file.readText()).jsonObject
                    } catch (e: Exception) {
                        LOG.warn("[AcpManager] Could not parse existing acp.json — starting fresh", e)
                        JsonObject(emptyMap())
                    }
                } else {
                    JsonObject(emptyMap())
                }

            // Preserve any pre-existing servers. For our own entries we're idempotent: keep a
            // correctly-formatted entry as-is, and only (re)write it when it's missing or its
            // shape/values don't match what's needed. We never touch third-party entries.
            val existingServers = existing["agent_servers"]?.jsonObject ?: JsonObject(emptyMap())
            val servers = existingServers.toMutableMap()
            var changed = false

            if (goosePath != null) {
                val gooseEntry =
                    JsonObject(
                        mapOf(
                            "command" to JsonPrimitive(goosePath),
                            "args" to JsonArray(listOf(JsonPrimitive("acp"))),
                            "env" to JsonObject(envBundle.mapValues { JsonPrimitive(it.value) }),
                        ),
                    )
                if (servers.putIfCorrect(GOOSE_ENTRY_NAME, gooseEntry)) changed = true
            } else {
                LOG.info("[AcpManager] Goose not detected — leaving any existing '$GOOSE_ENTRY_NAME' entry untouched")
            }

            if (!codexSourceDir.isNullOrBlank()) {
                val codexEntry =
                    JsonObject(
                        mapOf(
                            "command" to JsonPrimitive("npx"),
                            "args" to
                                JsonArray(
                                    listOf(
                                        JsonPrimitive("npm"),
                                        JsonPrimitive("run"),
                                        JsonPrimitive("start"),
                                        JsonPrimitive("--prefix"),
                                        JsonPrimitive(codexSourceDir),
                                    ),
                                ),
                            // The vendored codex-acp patch routes every Codex model call at
                            // CODEX_PROXY_URL, so OPENAI_API_KEY is never used against OpenAI
                            // directly — it only exists because the Codex CLI refuses to start
                            // without one.
                            "env" to
                                JsonObject(
                                    mapOf(
                                        "CODEX_PROXY_URL" to JsonPrimitive("${localProxyBaseUrl.trimEnd('/')}/v1"),
                                        "OPENAI_API_KEY" to JsonPrimitive(GooseRuntime.PROXY_API_KEY_PLACEHOLDER),
                                    ),
                                ),
                        ),
                    )
                if (servers.putIfCorrect(CODEX_ENTRY_NAME, codexEntry)) changed = true
            } else {
                LOG.info("[AcpManager] codexSourceDir not set — leaving any existing '$CODEX_ENTRY_NAME' entry untouched")
            }

            if (!changed) {
                LOG.info("[AcpManager] acp.json already up-to-date — skipping write")
                return
            }

            val updatedRoot = JsonObject(existing.toMutableMap().also { it["agent_servers"] = JsonObject(servers) })

            val tmp = File(file.parent, "acp.json.tmp")
            tmp.writeText(json.encodeToString(JsonObject.serializer(), updatedRoot))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

            LOG.info("[AcpManager] acp.json written to ${file.absolutePath}")
        } catch (e: Exception) {
            LOG.warn("[AcpManager] Failed to write acp.json", e)
        }
    }

    // Writes [expected] under [name] only if the current entry is missing or doesn't already
    // match it exactly — a correctly-formatted entry is left as-is. Keeps acp.json stable
    // across runs while still healing stale/malformed entries.
    // Returns true if the map was modified (entry was missing or stale), false if already correct.
    private fun MutableMap<String, JsonElement>.putIfCorrect(
        name: String,
        expected: JsonObject,
    ): Boolean {
        return when {
            this[name] == expected -> {
                LOG.info("[AcpManager] '$name' already present and correct — keeping")
                false
            }

            containsKey(name) -> {
                LOG.info("[AcpManager] '$name' present but outdated/malformed — rewriting")
                this[name] = expected
                true
            }

            else -> {
                LOG.info("[AcpManager] '$name' missing — writing")
                this[name] = expected
                true
            }
        }
    }

    private const val GOOSE_ENTRY_NAME = "Goose (Code4Me)"
    private const val CODEX_ENTRY_NAME = "Codex (Code4Me)"
}

internal class AcpRegistryWriter(private val registryPath: java.nio.file.Path) {
    private val log = thisLogger()
    private val json = Json { prettyPrint = true }

    fun registerManagedAgent(executable: String, bridgeDirectory: String): Result<Unit> = runCatching {
        Files.createDirectories(registryPath.parent)
        val lockPath = registryPath.resolveSibling("${registryPath.fileName}.lock")
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { updateRegistry(executable, bridgeDirectory) }
        }
    }

    private fun updateRegistry(executable: String, bridgeDirectory: String) {
        val root = if (Files.exists(registryPath)) {
            try { json.parseToJsonElement(Files.readString(registryPath)).jsonObject }
            catch (e: Exception) {
                throw IllegalStateException(
                    "The JetBrains ACP registry is invalid. Code4Me left it unchanged; repair ~/.jetbrains/acp.json and retry.",
                    e,
                )
            }
        } else JsonObject(emptyMap())
        val servers = (root["agent_servers"]?.jsonObject ?: JsonObject(emptyMap())).toMutableMap()
        val managed = JsonObject(
            mapOf(
                "command" to JsonPrimitive(executable),
                "args" to JsonArray(listOf(JsonPrimitive("--managed"))),
                "env" to JsonObject(mapOf("CODE4ME_BRIDGE_DIR" to JsonPrimitive(bridgeDirectory))),
            ),
        )

        val existing = servers[MANAGED_ENTRY_NAME]
        if (isLegacyLocalDev(existing)) {
            val backupName = "$MANAGED_ENTRY_NAME (local-dev backup)"
            if (!servers.containsKey(backupName)) servers[backupName] = existing!!
        }
        if (existing == managed) return
        servers[MANAGED_ENTRY_NAME] = managed

        val updated = JsonObject(root.toMutableMap().also { it["agent_servers"] = JsonObject(servers) })
        val temp = Files.createTempFile(registryPath.parent, "acp", ".tmp")
        try {
            Files.writeString(temp, json.encodeToString(JsonObject.serializer(), updated))
            try { Files.move(temp, registryPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: Exception) { Files.move(temp, registryPath, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
        log.info("Code4Me managed ACP agent registered in $registryPath")
    }

    private fun isLegacyLocalDev(entry: JsonElement?): Boolean {
        val text = entry?.toString()?.lowercase() ?: return false
        return text.contains("local-dev") || text.contains(".venv/bin/python") || text.contains("configure-agent")
    }

    companion object { const val MANAGED_ENTRY_NAME = "Code4Me Agent" }
}
