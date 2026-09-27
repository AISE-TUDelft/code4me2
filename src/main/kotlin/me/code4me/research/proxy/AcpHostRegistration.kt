package me.code4me.research.proxy

import me.code4me.research.runtime.ContentHasher
import me.code4me.research.telemetry.canonicalJson
import me.code4me.services.agent.AcpRegistryWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
 *
 * A gateway-bound (Goose) agent receives the study's inference credential the
 * same way the proxy receives its IPC capability: only the **path** of the
 * plugin-owned, owner-only credential file travels (`--inference-credential-file`
 * plus the entry env [INFERENCE_CREDENTIAL_FILE_ENV_VAR]); the proxy reads the
 * file and injects the credential into the agent child's environment. The
 * credential value itself is never part of the argv or the entry env.
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

    /** Where this registration writes; the IDE layer refreshes VFS around writes. */
    val registryLocation: Path
        get() = registryPath

    /**
     * Register (or refresh) the research proxy entry.
     *
     * @param resolved the verified packaged proxy runtime.
     * @param agentArgv the installed agent argv array; it is the `--agent-cmd`
     * REMAINDER and is always the last option on the command line. It is empty
     * only for an explicitly enabled development (source) runtime, which carries
     * no packaged agent; the entry then pins no agent contract.
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
     * @param agentRunId the native agent run id minted for this activation, or
     * `null` when the caller has none. It is emitted as `--agent-run-id` and
     * carried in the entry `env` as [RUN_ID_ENV_VAR] so the proxy stamps
     * `agent_run_id` on every canonical event it produces.
     * @param policyFile the frozen telemetry policy JSON the proxy enforces; it
     * is emitted as `--telemetry-policy` and is never inlined on the command
     * line.
     * @param policyDigest the digest the policy document declares; it is emitted
     * as `--telemetry-policy-digest` and makes a tampered/stale policy fail
     * closed at launch.
     * @param statusFile the plugin-owned, per-context delivery status document
     * the proxy writes (drop counters). It is emitted as `--status-file` and
     * carried in the entry `env` as [STATUS_FILE_ENV_VAR] so the participant
     * status surface can read the proxy's local telemetry loss. Content-free:
     * the path travels, never any payload.
     * @param agentEnv release-declared BYOA configuration overrides for the
     * agent child, emitted as repeated `--agent-env KEY=VALUE`. Blank when the
     * release declares none. Never carries a credential.
     * @param inferenceCredentialFile the plugin-owned, owner-only file holding
     * the study's inference credential for a gateway-bound agent, or `null`
     * when the arm does not use the gateway. Only its path is emitted
     * (`--inference-credential-file`, before `--agent-cmd`) and carried in the
     * entry `env` as [INFERENCE_CREDENTIAL_FILE_ENV_VAR].
     */
    fun register(
        resolved: ResolvedProxyRuntime,
        agentArgv: List<String>,
        spoolEndpoint: String?,
        capabilityFile: Path?,
        env: Map<String, String> = emptyMap(),
        agentDigest: String? = null,
        digestFallbackToAgentArgv: Boolean = true,
        capabilityValue: String? = null,
        adapterId: String? = null,
        adapterVersion: String? = null,
        agentRunId: String? = null,
        policyFile: Path? = null,
        policyDigest: String? = null,
        statusFile: Path? = null,
        agentEnv: Map<String, String> = emptyMap(),
        inferenceCredentialFile: Path? = null,
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
                    agentRunId?.takeIf { it.isNotBlank() }?.let { put(RUN_ID_ENV_VAR, it) }
                    statusFile?.let { put(STATUS_FILE_ENV_VAR, it.toAbsolutePath().normalize().toString()) }
                    inferenceCredentialFile?.let {
                        put(INFERENCE_CREDENTIAL_FILE_ENV_VAR, it.toAbsolutePath().normalize().toString())
                    }
                    if (capability != null) put(CAPABILITY_ENV_VAR, capability)
                }

            // The proxy CLI treats `--agent-cmd` as an argparse REMAINDER: it
            // consumes every following token as the agent argv. It must therefore
            // be the LAST option on the command line, so the digest, spool,
            // policy, and adapter flags are emitted before it.
            val args =
                buildList {
                    addAll(resolved.proxyArgv.drop(1))
                    // The bundled proxy is digest-pinned to this plugin, so the
                    // compatibility flag is part of the pinned contract and is
                    // always emitted (see COMPAT_IDEMPOTENT_INITIALIZE_FLAG).
                    add(COMPAT_IDEMPOTENT_INITIALIZE_FLAG)
                    if (digestFallbackToAgentArgv) {
                        add(AGENT_DIGEST_FLAG)
                        add(agentDigest ?: agentDigestOf(agentArgv.first()))
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
                    statusFile?.let {
                        add(STATUS_FILE_FLAG)
                        add(it.toAbsolutePath().normalize().toString())
                    }
                    adapterId?.takeIf { it.isNotBlank() }?.let {
                        add(ADAPTER_FLAG)
                        add(it)
                    }
                    agentRunId?.takeIf { it.isNotBlank() }?.let {
                        add(AGENT_RUN_ID_FLAG)
                        add(it)
                    }
                    agentEnv.toSortedMap().forEach { (key, value) ->
                        add(AGENT_ENV_FLAG)
                        add("$key=$value")
                    }
                    // Only the path: the proxy reads the owner-only file itself.
                    inferenceCredentialFile?.let {
                        add(INFERENCE_CREDENTIAL_FILE_FLAG)
                        add(it.toAbsolutePath().normalize().toString())
                    }
                    if (agentArgv.isNotEmpty()) {
                        add(AGENT_CMD_FLAG)
                        addAll(agentArgv)
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

    /**
     * Remove exactly this component's ACP entry (idempotent, never throws).
     *
     * Removal is verified rather than trusted: the writer's reported success is
     * not enough, because a concurrent writer can leave the entry in place. A
     * still-present entry is removed once more and, if it survives that retry,
     * the caller receives an [IllegalStateException] naming the entry instead of
     * a silent success — an unremoved entry would otherwise outlive its
     * capability and be read as a pre-existing entry on the next login.
     */
    fun unregister(): Result<Unit> =
        runCatching {
            val writer = registryWriterFactory(registryPath)
            writer.removeEntry(entryName).getOrThrow()
            if (writer.hasEntry(entryName)) {
                writer.removeEntry(entryName).getOrThrow()
                if (writer.hasEntry(entryName)) {
                    throw IllegalStateException(
                        "ACP registry entry '$entryName' is still present after removal",
                    )
                }
            }
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

        /**
         * Opt-in proxy compatibility mode: answer a repeated `initialize` on the
         * same connection from the cached handshake result instead of forwarding
         * it to the agent.
         *
         * JetBrains AI Assistant resubmits a failed prompt by creating a new
         * session on an already-initialized proxy process; the duplicate
         * `initialize` reaches a strict ACP agent, which rejects it with JSON-RPC
         * `-32603` ("Already initialized") and wedges the chat. The bundled proxy
         * is digest-pinned to this plugin, so the flag is always emitted; the
         * proxy's default (no flag) remains byte-preserving.
         */
        const val COMPAT_IDEMPOTENT_INITIALIZE_FLAG: String = "--compat-idempotent-initialize"

        /** Path to the frozen study privacy policy JSON the proxy enforces. */
        const val TELEMETRY_POLICY_FLAG: String = "--telemetry-policy"

        /** Expected digest of the frozen policy; a mismatch exits with a usage error. */
        const val TELEMETRY_POLICY_DIGEST_FLAG: String = "--telemetry-policy-digest"

        /**
         * Content-free delivery status document the proxy writes (drop
         * counters). It is the only channel that makes local telemetry loss
         * visible to the participant status surface.
         */
        const val STATUS_FILE_FLAG: String = "--status-file"

        /** Allowlisted adapter id the proxy resolves for enrichment. */
        const val ADAPTER_FLAG: String = "--adapter"

        /**
         * Native agent run id minted for this activation. The proxy stamps it as
         * `agent_run_id` on every canonical event it produces.
         */
        const val AGENT_RUN_ID_FLAG: String = "--agent-run-id"

        /** Release-declared BYOA configuration override for the agent child. */
        const val AGENT_ENV_FLAG: String = "--agent-env"

        /**
         * Path of the plugin-owned inference credential file for a gateway-bound
         * agent. The proxy reads it (never deletes it) and injects the credential
         * into the agent child's environment under the file's `credential_env_key`.
         */
        const val INFERENCE_CREDENTIAL_FILE_FLAG: String = "--inference-credential-file"

        /**
         * The ACP entry env key carrying the inference credential file path; the
         * proxy's fallback when `--inference-credential-file` is absent. Only
         * the path, never the credential.
         */
        const val INFERENCE_CREDENTIAL_FILE_ENV_VAR: String = "CODE4ME_RESEARCH_INFERENCE_CREDENTIAL_FILE"

        /** The credential file schema the proxy accepts. */
        const val INFERENCE_CREDENTIAL_SCHEMA_VERSION: String = "1"

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

        /**
         * The ACP entry env key carrying the native agent run id. Durable like
         * the capability, it is the proxy's fallback when `--agent-run-id` is
         * absent, so a launch never loses run attribution.
         */
        const val RUN_ID_ENV_VAR: String = "CODE4ME_RESEARCH_RUN_ID"

        /**
         * The ACP entry env key carrying the delivery status document path. The
         * proxy's fallback when `--status-file` is absent.
         */
        const val STATUS_FILE_ENV_VAR: String = "CODE4ME_RESEARCH_STATUS_FILE"

        /** The default JetBrains ACP registry the AI Assistant reads. */
        fun defaultRegistryPath(): Path = Path.of(System.getProperty("user.home"), ".jetbrains", "acp.json")

        /** Delete a leftover capability file; best effort, never throws. */
        fun cleanupCapabilityFile(path: Path?) {
            if (path == null) return
            runCatching { Files.deleteIfExists(path) }
        }

        /** Delete the plugin-owned inference credential file; best effort, never throws. */
        fun cleanupInferenceCredentialFile(path: Path?) = cleanupCapabilityFile(path)
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

private val ENV_KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * Write [value] to [path] owner-only **and atomically**: the content is staged
 * in a uniquely named temporary file in the same directory (created 0600 before
 * any byte is written) and moved into place with `ATOMIC_MOVE`, so a reader
 * never observes a partial or group-readable document. Used for the inference
 * credential file, which is rewritten on every manifest refresh while a
 * launch may be reading it (the proxy retries a briefly unreadable file).
 */
internal fun writeOwnerOnlyFileAtomically(
    path: Path,
    value: String,
) {
    val absolute = path.toAbsolutePath().normalize()
    val directory = absolute.parent ?: throw IllegalArgumentException("the file path has no parent directory")
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, ".${absolute.fileName}.", ".tmp")
    try {
        try {
            Files.setPosixFilePermissions(
                temporary,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows and non-POSIX filesystems: the ACL keeps the file user-scoped.
        }
        Files.writeString(temporary, value, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/**
 * The inference credential document the proxy reads:
 * `{"schema_version":"1","credential_env_key":<env var>,"credential":<bearer>}`
 * (canonical JSON; key order is irrelevant to the proxy's JSON parser).
 */
internal fun inferenceCredentialDocument(
    credentialEnvKey: String,
    credential: String,
): String =
    canonicalJson(
        linkedMapOf(
            "schema_version" to AcpHostRegistration.INFERENCE_CREDENTIAL_SCHEMA_VERSION,
            "credential_env_key" to credentialEnvKey,
            "credential" to credential,
        ),
    )

/**
 * Write the study's inference credential for a gateway-bound agent to [path]:
 * owner-only, atomically replaced, and read back verbatim so a launch can never
 * pick up a document the proxy would reject. Fails (never throws) on an env key
 * that is not a valid variable name or a credential that is blank or carries
 * control characters — exactly the documents the proxy refuses. No failure
 * message ever includes the credential.
 */
internal fun writeInferenceCredentialFile(
    path: Path,
    credentialEnvKey: String,
    credential: String,
): Result<Unit> =
    runCatching {
        require(ENV_KEY_PATTERN.matches(credentialEnvKey)) {
            "the inference credential env key is not a valid environment variable name"
        }
        require(credential.isNotBlank()) { "the inference credential is blank" }
        require(credential.none { it < ' ' || it == '\u007f' }) {
            "the inference credential contains control characters"
        }
        val document = inferenceCredentialDocument(credentialEnvKey, credential)
        writeOwnerOnlyFileAtomically(path, document)
        val reloaded = Files.readString(path.toAbsolutePath().normalize(), StandardCharsets.UTF_8)
        require(reloaded == document) { "the inference credential file could not be read back verbatim" }
    }
