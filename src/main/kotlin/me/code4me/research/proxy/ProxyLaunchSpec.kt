package me.code4me.research.proxy

import me.code4me.research.telemetry.sha256Hex
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/** Raised when a proxy launch spec cannot be built safely. */
open class ProxyLaunchException(message: String) : IllegalArgumentException(message)

/** Raised when a resolved path escapes the runtime root or contains `..`. */
class UnsafeProxyPathException(message: String) : ProxyLaunchException(message)

/** Raised when the resolved artifact digest does not match the pinned digest. */
class ProxyArtifactMismatchException(
    val expectedDigest: String,
    val actualDigest: String,
) : ProxyLaunchException("Proxy artifact digest mismatch: expected '$expectedDigest' but resolved '$actualDigest'")

/** Raised when a required launch input is malformed. */
class ProxyLaunchInputException(message: String) : ProxyLaunchException(message)

/**
 * A one-time, process-local IPC capability.
 *
 * The value is generated once per launch and must be consumed exactly once
 * (typically by the ACP host handshake). Re-use throws instead of silently
 * replaying a capability.
 */
class OneTimeIpcCapability(value: String) {
    val value: String = value

    private val consumed = AtomicBoolean(false)

    init {
        require(value.isNotBlank()) { "IPC capability must not be blank" }
    }

    val isConsumed: Boolean
        get() = consumed.get()

    /** Return the capability value exactly once; a second call throws. */
    fun consume(): String {
        if (!consumed.compareAndSet(false, true)) {
            throw IllegalStateException("IPC capability has already been consumed")
        }
        return value
    }

    override fun equals(other: Any?): Boolean = other is OneTimeIpcCapability && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "OneTimeIpcCapability(value=<redacted>, consumed=$isConsumed)"
}

/**
 * The descriptor written for an ACP host to launch the proxy.
 *
 * [command] is always a resolved absolute executable path and [args] is always
 * an argument array: the contract never exposes a shell string, and there is
 * deliberately no fallback to `PATH`, `npm`, or a globally installed agent.
 *
 * This is the launch *descriptor* only (a value). Persisting it into the host
 * registry is [AcpHostRegistration]'s job.
 */
data class ProxyHostRegistration(
    val command: String,
    val args: List<String>,
    val env: Map<String, String> = emptyMap(),
) {
    init {
        require(command.isNotBlank()) { "command must not be blank" }
    }

    /** Registration map shape the host writes (for example into `acp.json`). */
    fun toRegistrationMap(): Map<String, Any?> =
        linkedMapOf(
            "command" to command,
            "args" to args,
            "env" to env,
        )
}

/** Every input needed to resolve and launch the pinned proxy artifact. */
data class ProxyLaunchRequest(
    val runtimeRoot: Path,
    val executableRelativePath: String,
    val expectedArtifactDigest: String,
    val actualArtifactDigest: String,
    val researchSessionId: String,
    val telemetryPolicyDigest: String,
    val workspace: Path,
    val ipcCapability: String,
    /** Non-secret adapter identity resolved from the bootstrap manifest. */
    val adapterId: String? = null,
    val adapterVersion: String? = null,
)

/**
 * A fully resolved, safe proxy launch (Issue 10 / Issue 11).
 *
 * @property executable resolved absolute path inside the runtime root.
 * @property arguments argument array, never a shell-quoted string.
 * @property workingDirectory process working directory (the runtime root).
 * @property environment non-secret environment markers.
 * @property ipcCapability one-time local IPC capability.
 * @property hostRegistration descriptor an ACP host writes to launch the proxy.
 */
class ProxyLaunchSpec(
    val executable: Path,
    val arguments: List<String>,
    val workingDirectory: Path,
    val environment: Map<String, String>,
    val ipcCapability: OneTimeIpcCapability,
    val telemetryPolicyDigest: String,
    val researchSessionId: String,
    val workspace: Path,
    val hostRegistration: ProxyHostRegistration,
) {
    /** The exact argv (executable first) with no shell interpolation. */
    fun toCommandList(): List<String> = listOf(executable.toString()) + arguments

    /** A [ProcessBuilder] carrying the argv array directly. */
    fun toProcessBuilder(): ProcessBuilder {
        val builder = ProcessBuilder(toCommandList())
        builder.directory(workingDirectory.toFile())
        builder.environment().putAll(environment)
        return builder
    }

    /** Consume the one-time IPC capability for the host handshake. */
    fun consumeIpcCapability(): String = ipcCapability.consume()
}

/**
 * Builds [ProxyLaunchSpec] from a [ProxyLaunchRequest].
 *
 * Resolution is fail-closed:
 * - the executable must be a relative path without `..` segments;
 * - the normalized executable must stay inside the resolved runtime root;
 * - the expected and resolved digests must both be 64 lowercase hex chars and
 *   must be equal.
 *
 * On any failure it throws a typed [ProxyLaunchException]. It never returns a
 * fallback executable from `PATH`, `npm`, or a global agent.
 */
object ProxyLaunchSpecBuilder {
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun build(request: ProxyLaunchRequest): ProxyLaunchSpec {
        val root = resolveRuntimeRoot(request.runtimeRoot)
        val executable = resolveExecutable(root, request.executableRelativePath)
        verifyArtifactDigest(request.expectedArtifactDigest, request.actualArtifactDigest)
        if (request.researchSessionId.isBlank()) {
            throw ProxyLaunchInputException("researchSessionId must not be blank")
        }
        if (request.telemetryPolicyDigest.isBlank()) {
            throw ProxyLaunchInputException("telemetryPolicyDigest must not be blank")
        }
        val capability = OneTimeIpcCapability(request.ipcCapability)

        val arguments =
            buildList {
                add("--runtime-root")
                add(root.toString())
                add("--artifact-digest")
                add(request.expectedArtifactDigest)
                add("--session")
                add(request.researchSessionId)
                add("--telemetry-policy-digest")
                add(request.telemetryPolicyDigest)
                add("--workspace")
                add(request.workspace.toString())
                add("--ipc-capability")
                add(capability.value)
                // Adapter identity travels via the environment below, never
                // argv: the proxy CLI contract accepts only `--adapter`, and the
                // production ACP registration path is env-only too.
            }

        val environment =
            linkedMapOf(
                "CODE4ME_PROXY_RUNTIME_ROOT" to root.toString(),
                "CODE4ME_RESEARCH_SESSION" to request.researchSessionId,
            ).apply {
                request.adapterId?.takeIf { it.isNotBlank() }?.let {
                    put(AcpHostRegistration.ADAPTER_ID_ENV_VAR, it)
                }
                request.adapterVersion?.takeIf { it.isNotBlank() }?.let {
                    put(AcpHostRegistration.ADAPTER_VERSION_ENV_VAR, it)
                }
            }

        return ProxyLaunchSpec(
            executable = executable,
            arguments = arguments,
            workingDirectory = root,
            environment = environment,
            ipcCapability = capability,
            telemetryPolicyDigest = request.telemetryPolicyDigest,
            researchSessionId = request.researchSessionId,
            workspace = request.workspace,
            hostRegistration =
                ProxyHostRegistration(
                    command = executable.toString(),
                    args = arguments,
                    env = environment,
                ),
        )
    }

    /** Resolve the runtime root to an absolute, normalized path. */
    fun resolveRuntimeRoot(runtimeRoot: Path): Path {
        val root = runtimeRoot.toAbsolutePath().normalize()
        require(root.toString().isNotEmpty()) { "runtimeRoot must not be blank" }
        return root
    }

    /**
     * Resolve [relativePath] inside [root], rejecting absolute paths and any `..`
     * segment, and confirming the result stays inside [root].
     */
    fun resolveExecutable(
        root: Path,
        relativePath: String,
    ): Path {
        if (relativePath.isBlank()) {
            throw UnsafeProxyPathException("Executable path must not be blank")
        }
        val relative =
            try {
                Paths.get(relativePath)
            } catch (exception: InvalidPathException) {
                throw UnsafeProxyPathException("Executable path is not a valid path: ${exception.message}")
            }
        if (relative.isAbsolute) {
            throw UnsafeProxyPathException("Absolute executable path is not allowed: '$relativePath'")
        }
        for (segment in relative) {
            if (segment.toString() == "..") {
                throw UnsafeProxyPathException("Executable path must not contain '..': '$relativePath'")
            }
        }
        val resolved = root.resolve(relative).normalize()
        if (!resolved.startsWith(root)) {
            throw UnsafeProxyPathException("Executable path escapes the runtime root: '$relativePath'")
        }
        // When the executable actually exists, its real path (following symlinks)
        // must also stay inside the resolved runtime root.
        if (Files.exists(resolved)) {
            val realRoot = if (Files.exists(root)) root.toRealPath() else root
            val realExecutable = resolved.toRealPath()
            if (!realExecutable.startsWith(realRoot)) {
                throw UnsafeProxyPathException("Executable path escapes the runtime root via a link: '$relativePath'")
            }
        }
        return resolved
    }

    /** Verify a 64-lowercase-hex digest pair and that the two are equal. */
    fun verifyArtifactDigest(
        expected: String,
        actual: String,
    ) {
        if (!HEX64.matches(expected)) {
            throw ProxyArtifactMismatchException(expected, actual)
        }
        if (!HEX64.matches(actual)) {
            throw ProxyArtifactMismatchException(expected, actual)
        }
        if (expected != actual) {
            throw ProxyArtifactMismatchException(expected, actual)
        }
    }

    /** Convenience check: the digest is a 64 lowercase hex string. */
    fun isArtifactDigest(value: String?): Boolean = value != null && HEX64.matches(value)

    /** Convenience digest helper used by resolvers to pin a local artifact. */
    fun digestOf(bytes: ByteArray): String = sha256Hex(bytes)
}
