package me.code4me.services.agent

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

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
    fun registerManagedAgent(
        executable: String,
        bridgeDirectory: String,
    ): Result<Unit> {
        val path = acpFile.toPath()
        AcpRegistryVfs.beforeWrite(path)
        return AcpRegistryWriter(path).registerManagedAgent(executable, bridgeDirectory).also {
            AcpRegistryVfs.afterWrite(path)
        }
    }

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

            AcpRegistryVfs.beforeWrite(file.toPath())
            val tmp = File(file.parent, "acp.json.tmp")
            tmp.writeText(json.encodeToString(JsonObject.serializer(), updatedRoot))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            AcpRegistryVfs.afterWrite(file.toPath())

            LOG.info("[AcpManager] acp.json written to ${file.absolutePath}")
        } catch (e: Exception) {
            LOG.warn("[AcpManager] Failed to write acp.json", e)
        }
    }

    fun removeDeveloperGooseEntry() {
        try {
            val file = acpFile
            if (!file.exists()) return
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val servers = (root["agent_servers"]?.jsonObject ?: return).toMutableMap()
            if (servers.remove(GOOSE_ENTRY_NAME) == null) return
            val updated = JsonObject(root.toMutableMap().also { it["agent_servers"] = JsonObject(servers) })
            val tmp = File(file.parent, "acp.json.tmp")
            tmp.writeText(json.encodeToString(JsonObject.serializer(), updated))
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            LOG.info("[AcpManager] removed '$GOOSE_ENTRY_NAME' because it is not the assigned runtime")
        } catch (e: Exception) {
            LOG.warn("[AcpManager] Failed to remove stale '$GOOSE_ENTRY_NAME' entry", e)
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

    fun registerManagedAgent(
        executable: String,
        bridgeDirectory: String,
    ): Result<Unit> =
        runCatching {
            Files.createDirectories(registryPath.parent)
            val lockPath = registryPath.resolveSibling("${registryPath.fileName}.lock")
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { updateRegistry(executable, bridgeDirectory) }
            }
        }

    private fun updateRegistry(
        executable: String,
        bridgeDirectory: String,
    ) {
        val root =
            if (Files.exists(registryPath)) {
                try {
                    json.parseToJsonElement(Files.readString(registryPath)).jsonObject
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "The JetBrains ACP registry is invalid. Code4Me left it unchanged; repair ~/.jetbrains/acp.json and retry.",
                        e,
                    )
                }
            } else {
                JsonObject(emptyMap())
            }
        val servers = (root["agent_servers"]?.jsonObject ?: JsonObject(emptyMap())).toMutableMap()
        val managed =
            JsonObject(
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
        if (existing == managed) {
            // Keep the owner-only invariant on an idempotent refresh too: the
            // registry can carry the research entry's capability secret.
            restrictToOwner(registryPath)
            return
        }
        servers[MANAGED_ENTRY_NAME] = managed

        val updated = JsonObject(root.toMutableMap().also { it["agent_servers"] = JsonObject(servers) })
        val temp = Files.createTempFile(registryPath.parent, "acp", ".tmp")
        try {
            Files.writeString(temp, json.encodeToString(JsonObject.serializer(), updated))
            restrictToOwner(temp)
            try {
                Files.move(temp, registryPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (
                _: Exception,
            ) {
                Files.move(temp, registryPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        log.info("Code4Me managed ACP agent registered in $registryPath")
    }

    private fun isLegacyLocalDev(entry: JsonElement?): Boolean {
        val text = entry?.toString()?.lowercase() ?: return false
        return text.contains("local-dev") || text.contains(".venv/bin/python") || text.contains("configure-agent")
    }

    /**
     * Write (or refresh) the generic `agent_servers[name]` entry atomically,
     * preserving every unrelated entry. Idempotent: an already-correct entry is
     * left untouched. The registry is never rewritten when it cannot be parsed.
     */
    fun registerProxyEntry(
        name: String,
        command: String,
        args: List<String>,
        env: Map<String, String>,
    ): Result<Unit> =
        runCatching {
            require(name.isNotBlank()) { "ACP entry name must not be blank" }
            require(command.isNotBlank()) { "ACP entry command must not be blank" }
            Files.createDirectories(registryPath.parent)
            withRegistryLock {
                val root = readRootOrThrow()
                val servers = (root["agent_servers"]?.jsonObject ?: JsonObject(emptyMap())).toMutableMap()
                val entry =
                    JsonObject(
                        mapOf(
                            "command" to JsonPrimitive(command),
                            "args" to JsonArray(args.map { JsonPrimitive(it) }),
                            "env" to JsonObject(env.mapValues { JsonPrimitive(it.value) }),
                        ),
                    )
                if (servers[name] == entry) {
                    // An already-correct entry still gets the owner-only fix-up:
                    // a registry written by an older plugin version may be
                    // group/world readable while carrying the capability secret.
                    restrictToOwner(registryPath)
                    return@withRegistryLock
                }
                servers[name] = entry
                writeAtomic(JsonObject(root.toMutableMap().also { it["agent_servers"] = JsonObject(servers) }))
            }
        }

    /** Remove exactly `agent_servers[name]`, preserving every unrelated entry. */
    fun removeEntry(name: String): Result<Unit> =
        runCatching {
            require(name.isNotBlank()) { "ACP entry name must not be blank" }
            if (!Files.exists(registryPath)) return@runCatching
            withRegistryLock {
                val root = readRootOrThrow()
                val servers = (root["agent_servers"]?.jsonObject ?: return@withRegistryLock).toMutableMap()
                if (servers.remove(name) == null) return@withRegistryLock
                writeAtomic(JsonObject(root.toMutableMap().also { it["agent_servers"] = JsonObject(servers) }))
            }
        }

    /** Whether `agent_servers[name]` exists. Never throws; an unreadable file is `false`. */
    fun hasEntry(name: String): Boolean =
        try {
            if (!Files.exists(registryPath)) {
                false
            } else {
                val root = json.parseToJsonElement(Files.readString(registryPath)).jsonObject
                (root["agent_servers"]?.jsonObject ?: JsonObject(emptyMap())).containsKey(name)
            }
        } catch (_: Exception) {
            false
        }

    private fun readRootOrThrow(): JsonObject =
        if (Files.exists(registryPath)) {
            try {
                json.parseToJsonElement(Files.readString(registryPath)).jsonObject
            } catch (e: Exception) {
                throw IllegalStateException(
                    "The JetBrains ACP registry is invalid. Code4Me left it unchanged; repair ~/.jetbrains/acp.json and retry.",
                    e,
                )
            }
        } else {
            JsonObject(emptyMap())
        }

    private fun <T> withRegistryLock(action: () -> T): T {
        // The OS lock serializes processes; a JVM-wide monitor serializes the
        // threads of this IDE, which would otherwise hit
        // OverlappingFileLockException on the same channel.
        synchronized(REGISTRY_MONITOR) {
            val lockPath = registryPath.resolveSibling("${registryPath.fileName}.lock")
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                return channel.lock().use { action() }
            }
        }
    }

    private fun writeAtomic(root: JsonObject) {
        val temp = Files.createTempFile(registryPath.parent, "acp", ".tmp")
        try {
            Files.writeString(temp, json.encodeToString(JsonObject.serializer(), root))
            restrictToOwner(temp)
            try {
                Files.move(temp, registryPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temp, registryPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Restrict the registry to its owner before it is moved into place.
     *
     * The research proxy entry carries the one-time local IPC capability in its
     * `env`, so `~/.jetbrains/acp.json` must never be group/world readable.
     * POSIX filesystems get `0600`; Windows and non-POSIX filesystems rely on the
     * per-user ACL (setting POSIX permissions throws there and is ignored).
     */
    private fun restrictToOwner(path: java.nio.file.Path) {
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows and non-POSIX filesystems: the user ACL keeps it user-scoped.
        }
    }

    companion object {
        /** One monitor for every registry writer in this JVM (see [withRegistryLock]). */
        private val REGISTRY_MONITOR = Any()

        const val MANAGED_ENTRY_NAME = "Code4Me Agent"
    }
}
