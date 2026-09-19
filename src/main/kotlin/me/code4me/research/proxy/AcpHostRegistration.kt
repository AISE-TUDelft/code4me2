package me.code4me.research.proxy

import me.code4me.research.runtime.ContentHasher
import me.code4me.services.agent.AcpRegistryWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * Registers the packaged research proxy as an ACP host entry (Issue 08/10/11).
 *
 * The entry command is the resolved proxy argv and the args pin the real agent,
 * the one-time spool capability file, and the spool endpoint. Registration is
 * additive and idempotent: every unrelated `agent_servers` entry (Goose, Codex,
 * third-party) is preserved, and an already-correct entry is left untouched.
 * [unregister] removes exactly this component's entry.
 *
 * The one-time capability is the LOCAL IPC capability of the live spool server.
 * It is carried **in the ACP entry's `env`** as `CODE4ME_RESEARCH_CAPABILITY`
 * (the entry env persists with the entry, so a launch never depends on a file
 * surviving teardown) and, as a fallback, written to [capabilityFile] with
 * owner-only permissions where the filesystem supports them. The file is
 * plugin-owned: it is rewritten on every activation (the ACP entry is
 * persistent, so the proxy may be launched repeatedly) and the proxy only reads
 * it. Env is authoritative when the file cannot be read.
 *
 * The study privacy policy and the selected adapter are passed as CLI flags
 * (`--telemetry-policy`, `--telemetry-policy-digest`, `--adapter`), so the proxy
 * enforces the frozen policy and resolves the allowlisted adapter from the same
 * contract the manifest declares.
 */
class AcpHostRegistration internal constructor(
    private val registryPath: Path,
    private val entryName: String,
    private val registryWriterFactory: (Path) -> AcpRegistryWriter,
    private val capabilityWriter: (Path, String) -> Unit,
    private val capabilityFactory: () -> String,
    private val digestFactory: (Path) -> String,
) {
    /** Production constructor: the default registry, capability, and hashing. */
    constructor(
        registryPath: Path,
        entryName: String = DEFAULT_ENTRY_NAME,
    ) : this(
        registryPath = registryPath,
        entryName = entryName,
        registryWriterFactory = { AcpRegistryWriter(it) },
        capabilityWriter = ::writeOwnerOnlyFile,
        capabilityFactory = { UUID.randomUUID().toString().replace("-", "") },
        digestFactory = { ContentHasher.STREAMING.sha256(it) },
    )

    init {
        require(entryName.isNotBlank()) { "ACP entry name must not be blank" }
    }

    /**
     * Register (or refresh) the research proxy entry.
     *
     * @param resolved the verified packaged proxy runtime.
     * @param agentArgv the real agent argv array, or `null` to use
     * [ResolvedProxyRuntime.agentArgv] when the manifest declared one.
     * @param spoolEndpoint the local spool endpoint, or `null` to omit the spool.
     * @param capabilityFile the fallback file the one-time capability is written to;
     * the proxy reads it without deleting it. Required when [spoolEndpoint] is set.
     * The capability is also carried in the entry `env`, which is authoritative.
     * @param env environment markers for the host entry.
     * @param capabilityValue an explicit one-time capability to write (for example
     * the value the local spool IPC server is already authenticating with). When
     * `null`, a fresh capability is minted.
     * @param adapterId non-secret adapter identity from the bootstrap manifest, or
     * `null` when the release declares none. It is passed to the proxy as
     * `--adapter` (the proxy's validated allowlist) and also carried in the
     * entry `env` as a durable, non-secret marker.
     * @param adapterVersion non-secret adapter version paired with [adapterId].
     * @param policyFile the frozen telemetry policy JSON the proxy enforces; it
     * is emitted as `--telemetry-policy` and is never inlined on the command
     * line.
     * @param policyDigest the digest the policy document declares; it is emitted
     * as `--telemetry-policy-digest` and makes a tampered/stale policy fail
     * closed at launch.
     * @param agentEnv release-declared BYOA configuration overrides for the
     * agent child, emitted as repeated `--agent-env KEY=VALUE`. Blank when the
     * release declares none.
     */
    fun register(
        resolved: ResolvedProxyRuntime,
        agentArgv: List<String>?,
        spoolEndpoint: String?,
        capabilityFile: Path?,
        env: Map<String, String> = emptyMap(),
        agentDigest: String? = null,
        capabilityValue: String? = null,
        adapterId: String? = null,
        adapterVersion: String? = null,
        policyFile: Path? = null,
        policyDigest: String? = null,
        agentEnv: Map<String, String> = emptyMap(),
    ): Result<Unit> =
        runCatching {
            require(resolved.proxyArgv.isNotEmpty()) { "resolved proxy argv must not be empty" }
            if (spoolEndpoint != null) {
                require(capabilityFile != null) { "a spool endpoint requires a one-time capability file" }
            }
            val capability =
                if (spoolEndpoint != null && capabilityFile != null) {
                    val value = capabilityValue?.takeIf { it.isNotBlank() } ?: capabilityFactory()
                    capabilityWriter(capabilityFile, value)
                    value
                } else {
                    null
                }
            // The capability travels with the entry: the proxy reads it from the
            // env when its fallback file is gone, so an entry can never dangle.
            // The adapter identity travels the same way as a durable, non-secret
            // marker; the effective selection is the `--adapter` argv flag.
            val entryEnv =
                buildMap {
                    putAll(env)
                    adapterId?.takeIf { it.isNotBlank() }?.let { put(ADAPTER_ID_ENV_VAR, it) }
                    adapterVersion?.takeIf { it.isNotBlank() }?.let { put(ADAPTER_VERSION_ENV_VAR, it) }
                    if (capability != null) put(CAPABILITY_ENV_VAR, capability)
                }

            // The proxy CLI treats `--agent-cmd` as an argparse REMAINDER: it
            // consumes every following token as the agent argv. It must therefore
            // be the LAST option on the command line, so the digest, spool,
            // policy, and adapter flags are emitted before it.
            val args =
                buildList {
                    addAll(resolved.proxyArgv.drop(1))
                    val effectiveAgent = agentArgv ?: resolved.agentArgv
                    val effectiveDigest = agentDigest ?: resolved.agentDigest
                    if (!effectiveAgent.isNullOrEmpty()) {
                        add(AGENT_DIGEST_FLAG)
                        add(effectiveDigest ?: agentDigestOf(effectiveAgent.first()))
                    }
                    if (spoolEndpoint != null && capability != null && capabilityFile != null) {
                        add(SPOOL_ENDPOINT_FLAG)
                        add(spoolEndpoint)
                        add(CAPABILITY_FILE_FLAG)
                        add(capabilityFile.toAbsolutePath().normalize().toString())
                    }
                    if (policyFile != null) {
                        add(TELEMETRY_POLICY_FLAG)
                        add(policyFile.toAbsolutePath().normalize().toString())
                        policyDigest?.takeIf { it.isNotBlank() }?.let {
                            add(TELEMETRY_POLICY_DIGEST_FLAG)
                            add(it)
                        }
                    }
                    adapterId?.takeIf { it.isNotBlank() }?.let {
                        add(ADAPTER_FLAG)
                        add(it)
                    }
                    agentEnv.toSortedMap().forEach { (key, value) ->
                        add(AGENT_ENV_FLAG)
                        add("$key=$value")
                    }
                    if (!effectiveAgent.isNullOrEmpty()) {
                        add(AGENT_CMD_FLAG)
                        addAll(effectiveAgent)
                    }
                }

            registryWriterFactory(registryPath)
                .registerProxyEntry(
                    name = entryName,
                    command = resolved.proxyArgv.first(),
                    args = args,
                    env = entryEnv,
                ).getOrThrow()
        }

    /** Remove exactly this component's ACP entry (idempotent, never throws). */
    fun unregister(): Result<Unit> =
        runCatching {
            registryWriterFactory(registryPath).removeEntry(entryName).getOrThrow()
        }

    /** Whether this component currently has an ACP entry. */
    fun hasEntry(): Boolean = registryWriterFactory(registryPath).hasEntry(entryName)

    private fun agentDigestOf(executable: String): String {
        val path =
            try {
                Path.of(executable)
            } catch (exception: Exception) {
                throw IllegalArgumentException("agent executable is not a valid path", exception)
            }
        require(Files.isRegularFile(path)) { "agent executable is not a readable file" }
        return digestFactory(path)
    }

    companion object {
        /** Dedicated ACP registry entry name; never a Goose/Codex entry. */
        const val DEFAULT_ENTRY_NAME: String = "Code4Me Research Proxy"

        /**
         * Context-scoped ACP entry name. Each project/window owns its own entry so
         * closing one window removes only its own registration; the opaque context
         * id is appended (never a filesystem path).
         */
        fun contextEntryName(contextId: String): String =
            if (contextId.isBlank()) {
                DEFAULT_ENTRY_NAME
            } else {
                "$DEFAULT_ENTRY_NAME · ${contextId.take(16)}"
            }

        const val AGENT_CMD_FLAG: String = "--agent-cmd"
        const val AGENT_DIGEST_FLAG: String = "--agent-digest"
        const val SPOOL_ENDPOINT_FLAG: String = "--spool-endpoint"
        const val CAPABILITY_FILE_FLAG: String = "--capability-file"

        /** Path to the frozen study privacy policy JSON the proxy enforces. */
        const val TELEMETRY_POLICY_FLAG: String = "--telemetry-policy"

        /** Expected digest of the frozen policy; a mismatch exits with a usage error. */
        const val TELEMETRY_POLICY_DIGEST_FLAG: String = "--telemetry-policy-digest"

        /** Allowlisted adapter id the proxy resolves for enrichment. */
        const val ADAPTER_FLAG: String = "--adapter"

        /** Release-declared BYOA configuration override for the agent child. */
        const val AGENT_ENV_FLAG: String = "--agent-env"

        /**
         * The ACP entry env key carrying the one-time LOCAL IPC capability. It
         * persists with the entry, so the proxy can authenticate even after the
         * fallback capability file has been removed.
         */
        const val CAPABILITY_ENV_VAR: String = "CODE4ME_RESEARCH_CAPABILITY"

        /**
         * The ACP entry env key carrying the allowlisted adapter id. Non-secret
         * and advisory: an unknown/absent id falls back to generic normalization.
         */
        const val ADAPTER_ID_ENV_VAR: String = "CODE4ME_AGENT_ADAPTER_ID"

        /** The ACP entry env key carrying the adapter version paired with the id. */
        const val ADAPTER_VERSION_ENV_VAR: String = "CODE4ME_AGENT_ADAPTER_VERSION"

        /** The default JetBrains ACP registry the AI Assistant reads. */
        fun defaultRegistryPath(): Path = Path.of(System.getProperty("user.home"), ".jetbrains", "acp.json")

        /** Delete a leftover capability file; best effort, never throws. */
        fun cleanupCapabilityFile(path: Path?) {
            if (path == null) return
            runCatching { Files.deleteIfExists(path) }
        }
    }
}

/**
 * Write [value] to [path] with owner-only permissions where the filesystem
 * supports POSIX permissions (Windows relies on the user ACL).
 *
 * Shared by the one-time capability and the frozen telemetry policy: both are
 * plugin-owned, rewritten on every activation, and read but never deleted by
 * the proxy.
 */
internal fun writeOwnerOnlyFile(
    path: Path,
    value: String,
) {
    val absolute = path.toAbsolutePath().normalize()
    absolute.parent?.let { Files.createDirectories(it) }
    Files.writeString(
        absolute,
        value,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
    try {
        Files.setPosixFilePermissions(
            absolute,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    } catch (_: UnsupportedOperationException) {
        // Windows and non-POSIX filesystems: the ACL keeps the file user-scoped.
    }
}
