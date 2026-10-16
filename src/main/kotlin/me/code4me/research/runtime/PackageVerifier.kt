package me.code4me.research.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Locale

/**
 * Package-level verification and bootstrap-digest resolution (Issue 11).
 *
 * [PackageVerifier.verify] is default-deny: it enforces relative safe paths under
 * a trusted root, size/SHA-256 integrity, executability, no undeclared
 * executables, no secret files, the manifest's own structural validation, and an
 * optional signature. [PackageVerifier.resolveForBootstrap] selects an exact
 * `(os, arch)` component **only** when its digest equals the bootstrap-declared
 * artifact digest; there is no fallback to `PATH`, `npm`/`npx`, a source
 * checkout, or another platform/version.
 *
 * Pure JVM: no IntelliJ platform APIs, no network. All process-free.
 */

/** Stable machine-readable reason a package/component was blocked. */
enum class PackageVerificationCode {
    /** The manifest itself failed structural validation. */
    INVALID_MANIFEST,

    /** A declared path is absolute, escaping, or non-normalized. */
    PATH_ESCAPE,

    /** A declared path is blank, NUL-containing, or shell-shaped. */
    PATH_UNSAFE,

    /** The platform is supported but the component/file is missing. */
    ARTIFACT_MISSING,

    /** Size or SHA-256 does not match the manifest. */
    DIGEST_MISMATCH,

    /** The file size does not match the declared size. */
    SIZE_MISMATCH,

    /** A component declared as executable is not executable. */
    NOT_EXECUTABLE,

    /** A signature is claimed but cannot be verified. */
    SIGNATURE_MISSING,

    /** The claimed signature was verified and rejected. */
    SIGNATURE_INVALID,

    /** An executable-looking file is present that no component declares. */
    UNDECLARED_EXECUTABLE,

    /** A credential-looking file is present in the package. */
    SECRET_FILE_PRESENT,

    /** No component is declared for the requested platform. */
    UNSUPPORTED_PLATFORM,

    /** The component digest does not match the bootstrap-declared digest. */
    BOOTSTRAP_DIGEST_MISMATCH,

    /** A declared self-check failed or could not be confirmed. */
    SELF_CHECK_FAILED,
}

/** One typed package-verification issue. */
data class PackageVerificationIssue(
    val code: PackageVerificationCode,
    val message: String,
    val componentId: String? = null,
    val path: String? = null,
)

/** Result of verifying a package directory against a manifest. */
sealed interface PackageVerification {
    data class Verified(val manifest: RuntimeManifestV2, val verifiedComponents: Int) : PackageVerification

    data class Rejected(val issues: List<PackageVerificationIssue>) : PackageVerification
}

/** A component selected for one exact platform and bootstrap digest. */
data class BootstrapComponent(
    val component: RuntimeComponent,
    val arguments: List<String>,
    val digest: String,
)

/** Result of resolving a component for a bootstrap manifest. */
sealed interface BootstrapResolution {
    data class Resolved(val resolved: BootstrapComponent) : BootstrapResolution

    data class Failed(val issues: List<PackageVerificationIssue>) : BootstrapResolution
}

object PackageVerifier {
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

    private val EXECUTABLE_SUFFIXES =
        setOf(".exe", ".bat", ".cmd", ".com", ".sh", ".ps1", ".command")

    private val SECRET_NAME_PATTERNS =
        listOf(
            Regex("(^|[._-])\\.env(\\.[a-z0-9]+)?$"),
            Regex("(^|[._-])id_(rsa|dsa|ecdsa|ed25519)($|[._-])"),
            Regex("(^|[._-])(credentials|credential|secret|secrets|token|tokens)($|[._-])"),
            Regex("(^|[._-])\\.(npmrc|netrc|pypirc|git-credentials)$"),
            Regex("\\.(pem|key|p12|pfx|jks|keystore)$"),
        )

    private val SECRET_CONTENT_PATTERNS =
        listOf(
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Regex("\\bsk-[A-Za-z0-9]{16,}"),
            Regex("\\bghp_[A-Za-z0-9]{20,}"),
            Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
            Regex("AWS_SECRET_ACCESS_KEY\\s*="),
        )

    private val TEXT_SUFFIXES =
        setOf(
            ".json",
            ".yaml",
            ".yml",
            ".toml",
            ".ini",
            ".cfg",
            ".conf",
            ".txt",
            ".md",
            ".env",
            ".sh",
        )

    private const val MAX_CONTENT_SCAN_BYTES = 256L * 1024L

    /** Bare lowercase hex of a `sha256:<hex>` (or bare hex) digest, or `null`. */
    fun normalizeDigest(value: String?): String? {
        val text = value?.trim()?.lowercase(Locale.ROOT) ?: return null
        val stripped = if (text.startsWith("sha256:")) text.substring("sha256:".length) else text
        return if (SHA256_PATTERN.matches(stripped)) stripped else null
    }

    /**
     * Verify [root] against [manifest]. Every failure is typed and collected;
     * a package with any issue is [PackageVerification.Rejected].
     */
    fun verify(
        root: Path,
        manifest: RuntimeManifestV2,
        verifier: SignatureVerifier = SignatureVerifier.REJECTING,
        hasher: ContentHasher = ContentHasher.STREAMING,
    ): PackageVerification {
        val issues = mutableListOf<PackageVerificationIssue>()
        manifest.validate().issues.forEach { issues += it.toPackageIssue() }

        if (!Files.isDirectory(root)) {
            issues +=
                PackageVerificationIssue(
                    PackageVerificationCode.ARTIFACT_MISSING,
                    "package root '$root' is not a directory",
                )
            return PackageVerification.Rejected(issues)
        }

        var verified = 0
        val declared = mutableSetOf<String>()
        for (component in manifest.components) {
            declared += normalizeRelative(component.path)
            declared += normalizeRelative(component.executable)

            val pathIssue =
                classifyPath(component.path, component.componentId)
                    ?: classifyPath(component.executable, component.componentId)
            if (pathIssue != null) {
                issues += pathIssue
                continue
            }

            val payload = resolvePayload(root, component)
            if (payload is PathResolution.Rejected) {
                issues += payload.error.toPackageIssue(component.componentId)
                continue
            }
            val payloadPath = (payload as PathResolution.Contained).path
            if (!Files.isRegularFile(payloadPath)) {
                issues +=
                    PackageVerificationIssue(
                        PackageVerificationCode.ARTIFACT_MISSING,
                        "component '${component.componentId}' payload is missing",
                        component.componentId,
                        component.path,
                    )
                continue
            }
            val actualSize = Files.size(payloadPath)
            if (actualSize != component.size) {
                issues +=
                    PackageVerificationIssue(
                        PackageVerificationCode.SIZE_MISMATCH,
                        "component '${component.componentId}' size $actualSize does not match declared ${component.size}",
                        component.componentId,
                        component.path,
                    )
                continue
            }
            when (
                val verification =
                    RuntimeArtifactResolver.verifyFile(component, payloadPath, hasher, verifier, manifest)
            ) {
                is VerificationResult.Failed -> {
                    issues += verification.error.toPackageIssue(component.componentId)
                    continue
                }
                VerificationResult.Passed -> Unit
            }

            if (component.executable.isNotBlank()) {
                val executable = RuntimeArtifactResolver.resolveUnderRoot(root, component)
                if (executable is PathResolution.Rejected) {
                    issues += executable.error.toPackageIssue(component.componentId)
                    continue
                }
                val executablePath = (executable as PathResolution.Contained).path
                if (!Files.isRegularFile(executablePath)) {
                    issues +=
                        PackageVerificationIssue(
                            PackageVerificationCode.ARTIFACT_MISSING,
                            "component '${component.componentId}' executable is missing",
                            component.componentId,
                            component.executable,
                        )
                    continue
                }
                if (!isExecutable(executablePath)) {
                    issues +=
                        PackageVerificationIssue(
                            PackageVerificationCode.NOT_EXECUTABLE,
                            "component '${component.componentId}' executable is not executable",
                            component.componentId,
                            component.executable,
                        )
                    continue
                }
            }
            verified += 1
        }

        for (relative in undeclaredExecutables(root, declared)) {
            issues +=
                PackageVerificationIssue(
                    PackageVerificationCode.UNDECLARED_EXECUTABLE,
                    "executable '$relative' is not declared by any component",
                    path = relative,
                )
        }
        for (relative in secretFiles(root)) {
            issues +=
                PackageVerificationIssue(
                    PackageVerificationCode.SECRET_FILE_PRESENT,
                    "secret-shaped file '$relative' must not be packaged",
                    path = relative,
                )
        }

        return if (issues.isEmpty()) {
            PackageVerification.Verified(manifest, verified)
        } else {
            PackageVerification.Rejected(issues)
        }
    }

    /**
     * Select the exact `(os, arch)` component for a bootstrap manifest, requiring
     * its digest to equal [bootstrapArtifactDigest]. Never falls back.
     */
    fun resolveForBootstrap(
        manifest: RuntimeManifestV2,
        os: String,
        arch: String,
        bootstrapArtifactDigest: String,
    ): BootstrapResolution {
        val validation = manifest.validate()
        if (!validation.isValid) {
            return BootstrapResolution.Failed(validation.issues.map { it.toPackageIssue() })
        }
        if (os.isBlank() || arch.isBlank() || manifest.platforms.none { it.os == os && it.arch == arch }) {
            return BootstrapResolution.Failed(
                listOf(
                    PackageVerificationIssue(
                        PackageVerificationCode.UNSUPPORTED_PLATFORM,
                        "manifest does not declare platform '$os-$arch'",
                    ),
                ),
            )
        }
        val component =
            manifest.componentFor(os, arch)
                ?: return BootstrapResolution.Failed(
                    listOf(
                        PackageVerificationIssue(
                            PackageVerificationCode.ARTIFACT_MISSING,
                            "manifest has no component for '$os-$arch'",
                        ),
                    ),
                )
        val declared = normalizeDigest(component.sha256)
        val expected = normalizeDigest(bootstrapArtifactDigest)
        if (declared == null || expected == null || declared != expected) {
            return BootstrapResolution.Failed(
                listOf(
                    PackageVerificationIssue(
                        PackageVerificationCode.BOOTSTRAP_DIGEST_MISMATCH,
                        "component '${component.componentId}' digest does not match the bootstrap artifact digest",
                        component.componentId,
                    ),
                ),
            )
        }
        return BootstrapResolution.Resolved(
            BootstrapComponent(component, component.argsTemplate, declared),
        )
    }

    private fun resolvePayload(
        root: Path,
        component: RuntimeComponent,
    ): PathResolution {
        val payloadComponent =
            if (component.path == component.executable) {
                component
            } else {
                component.copy(executable = component.path)
            }
        return RuntimeArtifactResolver.resolveUnderRoot(root, payloadComponent)
    }

    /**
     * Classify a declared path before touching the filesystem: blank/NUL is
     * [PackageVerificationCode.PATH_UNSAFE]; absolute, home-relative, or `..`
     * traversal is [PackageVerificationCode.PATH_ESCAPE].
     */
    private fun classifyPath(
        value: String,
        componentId: String?,
    ): PackageVerificationIssue? {
        if (value.isBlank()) {
            return PackageVerificationIssue(
                PackageVerificationCode.PATH_UNSAFE,
                "declared path must not be blank",
                componentId,
                value,
            )
        }
        if (value.indexOf('\u0000') >= 0) {
            return PackageVerificationIssue(
                PackageVerificationCode.PATH_UNSAFE,
                "declared path must not contain NUL",
                componentId,
                value,
            )
        }
        if (value.startsWith("/") || value.startsWith("\\") || value.startsWith("~")) {
            return PackageVerificationIssue(
                PackageVerificationCode.PATH_ESCAPE,
                "declared path '$value' must be relative to the package root",
                componentId,
                value,
            )
        }
        if (value.length >= 2 && value[0].isLetter() && value[1] == ':') {
            return PackageVerificationIssue(
                PackageVerificationCode.PATH_ESCAPE,
                "declared path '$value' must be relative to the package root",
                componentId,
                value,
            )
        }
        if (value.replace('\\', '/').split('/').any { it == ".." }) {
            return PackageVerificationIssue(
                PackageVerificationCode.PATH_ESCAPE,
                "declared path '$value' must not contain '..'",
                componentId,
                value,
            )
        }
        return null
    }

    private fun undeclaredExecutables(
        root: Path,
        declared: Set<String>,
    ): List<String> {
        val normalized = declared.map { normalizeRelative(it) }.toSet()
        val findings = mutableListOf<String>()
        forEachRegularFile(root) { path, relative ->
            if (isExecutable(path) && normalizeRelative(relative) !in normalized) {
                findings += relative
            }
        }
        return findings
    }

    private fun secretFiles(root: Path): List<String> {
        val findings = mutableListOf<String>()
        forEachRegularFile(root) { path, relative ->
            val segments = relative.split('/')
            if (segments.any { it.startsWith(".aws") || it.startsWith(".ssh") || it.startsWith(".gnupg") }) {
                findings += relative
                return@forEachRegularFile
            }
            val name = path.fileName.toString().lowercase(Locale.ROOT)
            if (SECRET_NAME_PATTERNS.any { it.containsMatchIn(name) }) {
                findings += relative
                return@forEachRegularFile
            }
            val suffix = name.substringAfterLast('.', "")
            if (".$suffix" in TEXT_SUFFIXES && Files.size(path) <= MAX_CONTENT_SCAN_BYTES) {
                val content =
                    try {
                        Files.readString(path)
                    } catch (_: Exception) {
                        return@forEachRegularFile
                    }
                if (SECRET_CONTENT_PATTERNS.any { it.containsMatchIn(content) }) {
                    findings += relative
                }
            }
        }
        return findings
    }

    private fun forEachRegularFile(
        root: Path,
        action: (Path, String) -> Unit,
    ) {
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .sorted()
                .forEach { path ->
                    val relative = root.relativize(path).toString().replace('\\', '/')
                    action(path, relative)
                }
        }
    }

    private fun normalizeRelative(value: String): String = value.replace('\\', '/').removePrefix("./")

    private fun isExecutable(path: Path): Boolean {
        val name = path.fileName.toString().lowercase(Locale.ROOT)
        if (EXECUTABLE_SUFFIXES.any { name.endsWith(it) }) return true
        return try {
            val permissions = Files.getPosixFilePermissions(path)
            PosixFilePermission.OWNER_EXECUTE in permissions ||
                PosixFilePermission.GROUP_EXECUTE in permissions ||
                PosixFilePermission.OTHERS_EXECUTE in permissions
        } catch (_: UnsupportedOperationException) {
            false
        }
    }
}

internal fun RuntimeManifestIssue.toPackageIssue(): PackageVerificationIssue =
    PackageVerificationIssue(
        code =
            when (code) {
                RuntimeManifestErrorCode.UNSAFE_PATH -> PackageVerificationCode.PATH_UNSAFE
                RuntimeManifestErrorCode.UNDECLARED_EXECUTABLE -> PackageVerificationCode.UNDECLARED_EXECUTABLE
                RuntimeManifestErrorCode.SECRET_DETECTED -> PackageVerificationCode.SECRET_FILE_PRESENT
                else -> PackageVerificationCode.INVALID_MANIFEST
            },
        message = message,
        path = field,
    )

internal fun ResolutionError.toPackageIssue(fallbackComponentId: String? = null): PackageVerificationIssue =
    PackageVerificationIssue(
        code =
            when (code) {
                ResolutionErrorCode.PATH_UNSAFE -> PackageVerificationCode.PATH_UNSAFE
                ResolutionErrorCode.UNSUPPORTED_PLATFORM -> PackageVerificationCode.UNSUPPORTED_PLATFORM
                ResolutionErrorCode.ARTIFACT_MISSING -> PackageVerificationCode.ARTIFACT_MISSING
                ResolutionErrorCode.DIGEST_MISMATCH -> PackageVerificationCode.DIGEST_MISMATCH
                ResolutionErrorCode.NOT_EXECUTABLE -> PackageVerificationCode.NOT_EXECUTABLE
                ResolutionErrorCode.SIGNATURE_MISSING -> PackageVerificationCode.SIGNATURE_MISSING
                ResolutionErrorCode.SIGNATURE_INVALID -> PackageVerificationCode.SIGNATURE_INVALID
                ResolutionErrorCode.SELF_CHECK_FAILED -> PackageVerificationCode.SELF_CHECK_FAILED
            },
        message = message,
        componentId = componentId ?: fallbackComponentId,
    )
