package me.code4me.research.runtime

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Runtime artifact resolution and verification (Issue 11).
 *
 * Resolution is fail-closed. There is deliberately **no fallback** to `PATH`,
 * `npm`/`npx`, a source checkout, or a globally installed agent: every failure
 * is a terminal, typed [ResolutionError]. A failure never yields a path, so a
 * caller can never accidentally launch an unaudited executable.
 *
 * All logic here is pure JVM and injectable; no real process is started by the
 * tested code.
 */

/** Stable machine-readable reason a runtime artifact could not be used. */
enum class ResolutionErrorCode {
    /** No component is declared for the requested `(os, arch)`. */
    UNSUPPORTED_PLATFORM,

    /** The platform is supported but the component/file is missing. */
    ARTIFACT_MISSING,

    /** Size or SHA-256 of the resolved bytes does not match the manifest. */
    DIGEST_MISMATCH,

    /** A declared path is absolute, non-normalized, escaping, NUL, or has shell metacharacters. */
    PATH_UNSAFE,

    /** The component declares no runnable executable. */
    NOT_EXECUTABLE,

    /** A signature is claimed but no verifier could confirm it. */
    SIGNATURE_MISSING,

    /** The claimed signature was verified and rejected. */
    SIGNATURE_INVALID,

    /** The staged component failed its declared self-check. */
    SELF_CHECK_FAILED,
}

/** One typed resolution/verification failure. */
data class ResolutionError(
    val code: ResolutionErrorCode,
    val message: String,
    val componentId: String? = null,
)

/** Result of selecting a component for a platform. */
sealed interface Resolution {
    data class Resolved(
        val manifest: RuntimeManifestV2,
        val component: RuntimeComponent,
        val arguments: List<String>,
    ) : Resolution

    data class Failed(val error: ResolutionError) : Resolution
}

/** Result of resolving a component path under a trusted root. */
sealed interface PathResolution {
    data class Contained(val path: Path) : PathResolution

    data class Rejected(val error: ResolutionError) : PathResolution
}

/** Result of an integrity or self-check verification. */
sealed interface VerificationResult {
    object Passed : VerificationResult

    data class Failed(val error: ResolutionError) : VerificationResult
}

/** Outcome of a detached-signature verification attempt. */
sealed interface SignatureVerification {
    object Valid : SignatureVerification

    data class Invalid(val reason: String) : SignatureVerification

    data class Unsupported(val reason: String) : SignatureVerification
}

/**
 * Pluggable signature verification.
 *
 * The default [REJECTING] verifier accepts nothing: a component that claims a
 * signature fails with `SIGNATURE_MISSING` unless a real verifier is supplied.
 * This keeps the pipeline fail-closed until signing keys are configured.
 */
fun interface SignatureVerifier {
    fun verify(
        component: RuntimeComponent,
        manifest: RuntimeManifestV2?,
    ): SignatureVerification

    companion object {
        val REJECTING: SignatureVerifier =
            SignatureVerifier { _, _ -> SignatureVerification.Unsupported("no signature verifier configured") }
    }
}

/** Streaming content hasher; injectable so tests never touch the filesystem. */
fun interface ContentHasher {
    fun sha256(path: Path): String

    companion object {
        val STREAMING: ContentHasher = ContentHasher { path -> streamingSha256(path) }
    }
}

/** The environment a self-check command runs in. */
data class SelfCheckContext(val executablePath: Path, val workingDirectory: Path?)

/** Result of executing a self-check command. */
data class SelfCheckOutcome(val exitCode: Int, val output: String = "")

/** Injectable self-check executor; the real implementation spawns a process. */
fun interface SelfCheckExecutor {
    fun run(
        spec: SelfCheckSpec,
        context: SelfCheckContext,
    ): SelfCheckOutcome
}

/**
 * Selects and verifies exact runtime components.
 *
 * @see Resolution
 */
object RuntimeArtifactResolver {
    /**
     * Select the exact `(os, arch)` component, or fail with a typed error.
     *
     * A missing signature verifier rejects a claimed signature rather than
     * accepting it. No fallback artifact is ever consulted.
     */
    fun resolve(
        manifest: RuntimeManifestV2,
        os: String,
        arch: String,
        verifier: SignatureVerifier = SignatureVerifier.REJECTING,
    ): Resolution {
        if (os.isBlank() || arch.isBlank()) {
            return failed(
                ResolutionErrorCode.UNSUPPORTED_PLATFORM,
                "requested platform must declare a non-blank os and arch",
            )
        }
        if (manifest.platforms.none { it.os == os && it.arch == arch }) {
            return failed(
                ResolutionErrorCode.UNSUPPORTED_PLATFORM,
                "manifest does not declare platform '$os-$arch'",
            )
        }
        val component =
            manifest.componentFor(os, arch)
                ?: return failed(
                    ResolutionErrorCode.ARTIFACT_MISSING,
                    "manifest has no component for '$os-$arch'",
                )

        pathSafety(component.path)?.let {
            return failed(ResolutionErrorCode.PATH_UNSAFE, "component path is unsafe: $it", component.componentId)
        }
        if (component.executable.isBlank()) {
            return failed(
                ResolutionErrorCode.NOT_EXECUTABLE,
                "component '${component.componentId}' declares no executable",
                component.componentId,
            )
        }
        pathSafety(component.executable)?.let {
            return failed(
                ResolutionErrorCode.PATH_UNSAFE,
                "component executable path is unsafe: $it",
                component.componentId,
            )
        }
        if (!isLowercaseSha256(component.sha256)) {
            return failed(
                ResolutionErrorCode.DIGEST_MISMATCH,
                "component '${component.componentId}' has a malformed sha256",
                component.componentId,
            )
        }
        if (component.size < 0L) {
            return failed(
                ResolutionErrorCode.ARTIFACT_MISSING,
                "declared size must not be negative",
                component.componentId,
            )
        }
        val signatureFailure = verifySignature(manifest, component, verifier)
        if (signatureFailure != null) return Resolution.Failed(signatureFailure)

        return Resolution.Resolved(
            manifest = manifest,
            component = component,
            arguments = component.argsTemplate,
        )
    }

    /**
     * Resolve a component's executable under [root], guaranteeing the result
     * stays inside [root]. Symlink escapes are detected with normalized real
     * paths when the path actually exists.
     */
    fun resolveUnderRoot(
        root: Path,
        component: RuntimeComponent,
    ): PathResolution {
        val normalizedRoot =
            try {
                root.toAbsolutePath().normalize()
            } catch (exception: Exception) {
                return PathResolution.Rejected(
                    ResolutionError(
                        ResolutionErrorCode.PATH_UNSAFE,
                        "runtime root is not resolvable: ${exception.message}",
                        component.componentId,
                    ),
                )
            }
        pathSafety(component.executable)?.let {
            return PathResolution.Rejected(
                ResolutionError(
                    ResolutionErrorCode.PATH_UNSAFE,
                    "component executable path is unsafe: $it",
                    component.componentId,
                ),
            )
        }
        val resolved = normalizedRoot.resolve(component.executable).normalize()
        if (!resolved.startsWith(normalizedRoot)) {
            return PathResolution.Rejected(
                ResolutionError(
                    ResolutionErrorCode.PATH_UNSAFE,
                    "component executable escapes the runtime root",
                    component.componentId,
                ),
            )
        }
        if (Files.exists(resolved)) {
            val realRoot = if (Files.exists(normalizedRoot)) normalizedRoot.toRealPath() else normalizedRoot
            val realExecutable = resolved.toRealPath()
            if (!realExecutable.startsWith(realRoot)) {
                return PathResolution.Rejected(
                    ResolutionError(
                        ResolutionErrorCode.PATH_UNSAFE,
                        "component executable escapes the runtime root via a link",
                        component.componentId,
                    ),
                )
            }
        }
        return PathResolution.Contained(resolved)
    }

    /**
     * Verify [file] against [component] by streaming: size first, then SHA-256.
     *
     * When [manifest] and a claimed signature are present, the [verifier] is
     * consulted as well. A component with no [RuntimeComponent.signature] needs
     * no verifier.
     */
    fun verifyFile(
        component: RuntimeComponent,
        file: Path,
        hasher: ContentHasher = ContentHasher.STREAMING,
        verifier: SignatureVerifier = SignatureVerifier.REJECTING,
        manifest: RuntimeManifestV2? = null,
    ): VerificationResult {
        if (!Files.exists(file)) {
            return VerificationResult.Failed(
                ResolutionError(
                    ResolutionErrorCode.ARTIFACT_MISSING,
                    "component file does not exist: $file",
                    component.componentId,
                ),
            )
        }
        val actualSize = Files.size(file)
        if (actualSize != component.size) {
            return VerificationResult.Failed(
                ResolutionError(
                    ResolutionErrorCode.DIGEST_MISMATCH,
                    "component file size $actualSize does not match declared ${component.size}",
                    component.componentId,
                ),
            )
        }
        val actualSha256 =
            try {
                hasher.sha256(file)
            } catch (exception: Exception) {
                return VerificationResult.Failed(
                    ResolutionError(
                        ResolutionErrorCode.ARTIFACT_MISSING,
                        "component file could not be read: ${exception.message}",
                        component.componentId,
                    ),
                )
            }
        if (!actualSha256.equals(component.sha256, ignoreCase = false)) {
            return VerificationResult.Failed(
                ResolutionError(
                    ResolutionErrorCode.DIGEST_MISMATCH,
                    "component file digest does not match declared sha256",
                    component.componentId,
                ),
            )
        }
        val signatureFailure = verifySignature(manifest, component, verifier)
        if (signatureFailure != null) return VerificationResult.Failed(signatureFailure)
        return VerificationResult.Passed
    }

    /**
     * Run the component's declared self-check (if any) and compare the exit code.
     * A component with no self-check passes vacuously.
     */
    fun verifySelfCheck(
        component: RuntimeComponent,
        executablePath: Path,
        executor: SelfCheckExecutor,
        workingDirectory: Path? = null,
    ): VerificationResult {
        val spec = component.selfCheck ?: return VerificationResult.Passed
        val outcome =
            try {
                executor.run(spec, SelfCheckContext(executablePath, workingDirectory))
            } catch (exception: Exception) {
                return VerificationResult.Failed(
                    ResolutionError(
                        ResolutionErrorCode.SELF_CHECK_FAILED,
                        "self-check threw: ${exception.message}",
                        component.componentId,
                    ),
                )
            }
        if (outcome.exitCode != spec.expectedExitCode) {
            return VerificationResult.Failed(
                ResolutionError(
                    ResolutionErrorCode.SELF_CHECK_FAILED,
                    "self-check exited ${outcome.exitCode}, expected ${spec.expectedExitCode}",
                    component.componentId,
                ),
            )
        }
        return VerificationResult.Passed
    }

    /**
     * Build the argv for a resolved component: the executable first, then the
     * declared argument template. Arguments are always an array, never a shell
     * string, so paths with spaces or non-ASCII characters are safe.
     */
    fun buildCommand(
        executablePath: Path,
        component: RuntimeComponent,
    ): List<String> {
        val substitutions =
            mapOf(
                "{executable}" to executablePath.toString(),
                "{component_path}" to component.path,
                "{component_dir}" to (executablePath.parent?.toString() ?: ""),
            )
        val arguments = component.argsTemplate.map { argument ->
            substitutions.entries.fold(argument) { result, (token, value) -> result.replace(token, value) }
        }
        return listOf(executablePath.toString()) + arguments
    }

    private fun verifySignature(
        manifest: RuntimeManifestV2?,
        component: RuntimeComponent,
        verifier: SignatureVerifier,
    ): ResolutionError? {
        if (component.signature.isNullOrBlank()) return null
        return when (val verification = verifier.verify(component, manifest)) {
            is SignatureVerification.Valid -> null
            is SignatureVerification.Invalid ->
                ResolutionError(
                    ResolutionErrorCode.SIGNATURE_INVALID,
                    "component signature rejected: ${verification.reason}",
                    component.componentId,
                )
            is SignatureVerification.Unsupported ->
                ResolutionError(
                    ResolutionErrorCode.SIGNATURE_MISSING,
                    "component signature cannot be verified: ${verification.reason}",
                    component.componentId,
                )
        }
    }

    private fun failed(
        code: ResolutionErrorCode,
        message: String,
        componentId: String? = null,
    ): Resolution = Resolution.Failed(ResolutionError(code, message, componentId))
}

internal fun streamingSha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(Files.newInputStream(path).buffered(), digest).use {
        it.transferTo(OutputStream.nullOutputStream())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
