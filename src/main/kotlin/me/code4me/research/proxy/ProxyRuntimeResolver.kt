package me.code4me.research.proxy

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.extensions.PluginId
import me.code4me.research.telemetry.parseCanonicalJsonObject
import me.code4me.research.telemetry.sha256Hex
import me.code4me.research.proxy.ProxyRuntimeErrorCode.ARTIFACT_MISSING
import me.code4me.research.proxy.ProxyRuntimeErrorCode.DIGEST_MISMATCH
import me.code4me.research.proxy.ProxyRuntimeErrorCode.EXTRACTION_FAILED
import me.code4me.research.proxy.ProxyRuntimeErrorCode.MALFORMED_MANIFEST
import me.code4me.research.proxy.ProxyRuntimeErrorCode.NOT_SELF_CONTAINED
import me.code4me.research.proxy.ProxyRuntimeErrorCode.PATH_ESCAPE
import me.code4me.research.proxy.ProxyRuntimeErrorCode.UNSUPPORTED_PLATFORM
import me.code4me.research.runtime.ContentHasher
import me.code4me.research.runtime.PathResolution
import me.code4me.research.runtime.PlatformTriple
import me.code4me.research.runtime.ResolutionErrorCode
import me.code4me.research.runtime.RuntimeArtifactResolver
import me.code4me.research.runtime.RuntimeComponent
import me.code4me.research.runtime.VerificationResult
import me.code4me.research.runtime.isLowercaseSha256
import me.code4me.research.runtime.pathSafety
import me.code4me.research.runtime.stringKeyedMap
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Resolution of the **packaged** research proxy runtime (Issue 11 / Issue 08).
 *
 * Two layouts are supported, in order:
 *
 * 1. **Exploded** — `<pluginPath>/research-runtime/proxy-manifest.json` exists
 *    on disk (dev sandbox / unpacked distribution).
 * 2. **Packaged** — `/research-runtime/proxy-manifest.json` is read from the
 *    plugin classpath (the plugin jar). Every declared file is then streamed out
 *    of the same classpath into a content-addressed cache directory
 *    (`<system>/code4me/research-runtime/<manifest-sha256>/`), verifying size and
 *    SHA-256 while writing, before being used as an argv.
 *
 * Resolution is fail-closed:
 *
 * - the runtime is only ever the plugin's own bundled artifact, never a source
 *   checkout, `PATH`, `npm`, or a globally installed proxy;
 * - only the exact `(os, arch)` triple is selected, never another platform;
 * - every declared file must stay inside the runtime root (no absolute paths,
 *   `..`, or symlink escapes) and match its size/SHA-256;
 * - cache extraction is idempotent (a verified cache is reused) and atomic
 *   (extract to a temp dir, then move into place);
 * - a non-self-contained platform is refused with `NOT_SELF_CONTAINED` unless an
 *   explicit development interpreter is configured *and* dev mode is enabled.
 *
 * Resolution is proxy-only: the real agent is a separate, single artifact
 * identity installed by [PackagedAgentInstaller] from the shipped recipe. Every
 * failure is a typed [ProxyRuntimeResolution.Failed]; no method returns a
 * fallback executable.
 */

/** Stable machine-readable reason a packaged proxy runtime could not be used. */
enum class ProxyRuntimeErrorCode {
    /** The manifest itself could not be found in either layout. */
    ARTIFACT_MISSING,

    /** The manifest declares no platform matching the current host. */
    UNSUPPORTED_PLATFORM,

    /** A declared file's size or SHA-256 does not match the manifest. */
    DIGEST_MISMATCH,

    /** A declared path is absolute, non-normalized, or escapes the runtime root. */
    PATH_ESCAPE,

    /** The manifest JSON is missing a required field or is not the supported schema. */
    MALFORMED_MANIFEST,

    /** The platform is declared but not self-contained and dev mode is not enabled. */
    NOT_SELF_CONTAINED,

    /** A bundled file could not be materialized into the runtime cache. */
    EXTRACTION_FAILED,
}

/** One typed resolution failure. */
data class ProxyRuntimeError(
    val code: ProxyRuntimeErrorCode,
    val message: String,
    val path: String? = null,
)

/** Result of resolving the packaged proxy runtime. */
sealed interface ProxyRuntimeResolution {
    /** The exact platform was resolved and fully verified. */
    data class Resolved(val runtime: ResolvedProxyRuntime) : ProxyRuntimeResolution

    /** No fallback is attempted; the caller must block the launch. */
    data class Failed(val error: ProxyRuntimeError) : ProxyRuntimeResolution
}

/**
 * A verified proxy runtime.
 *
 * @property runtimeRoot resolved runtime directory (all argv entries stay inside).
 * @property proxyArgv the proxy argv array; the first element is the resolved
 * absolute executable (or the development interpreter, in dev mode).
 * @property proxyDigest 64-lowercase-hex SHA-256 of the proxy executable.
 * @property selfContained true only when no host interpreter/runtime is needed.
 * @property development true only for an explicitly-enabled dev (source) runtime.
 */
data class ResolvedProxyRuntime(
    val runtimeRoot: Path,
    val proxyArgv: List<String>,
    val proxyDigest: String,
    val selfContained: Boolean = true,
    val development: Boolean = false,
)

/** Explicit, opt-in development-runtime configuration (never a production fallback). */
data class DevelopmentRuntime(
    val allowed: Boolean,
    val interpreter: String?,
) {
    companion object {
        val DISABLED: DevelopmentRuntime = DevelopmentRuntime(allowed = false, interpreter = null)
    }
}

/** Opens a bundled plugin resource by its absolute resource path. */
fun interface RuntimeResourceSource {
    fun open(resourcePath: String): InputStream?
}

/** The default resource source: the plugin's own classloader. */
object ClasspathRuntimeResources : RuntimeResourceSource {
    override fun open(resourcePath: String): InputStream? = PackagedProxyRuntimeResolver::class.java.getResourceAsStream(resourcePath)
}

/**
 * Injectable runtime resolver so activation can fail closed without a process.
 * It resolves the proxy argv/digest only; the real agent is a separate artifact
 * installed by [PackagedAgentInstaller].
 */
fun interface ProxyRuntimeResolver {
    fun resolve(): ProxyRuntimeResolution
}

/** Host `(os, arch)` detection in the manifest vocabulary. */
object HostPlatform {
    fun os(): String {
        val name = System.getProperty("os.name").orEmpty()
        return when {
            name.startsWith("Windows", ignoreCase = true) -> "windows"
            name.startsWith("Mac", ignoreCase = true) || name.startsWith("Darwin", ignoreCase = true) -> "macos"
            name.startsWith("Linux", ignoreCase = true) -> "linux"
            else -> name.lowercase().replace(' ', '-')
        }
    }

    fun arch(): String =
        when (System.getProperty("os.arch").orEmpty().lowercase()) {
            "aarch64", "arm64" -> "aarch64"
            "amd64", "x86_64", "x64" -> "x64"
            else -> System.getProperty("os.arch").orEmpty().lowercase()
        }

    fun triple(): PlatformTriple = PlatformTriple(os(), arch())
}

/** Locates the `research-runtime/` directory shipped in the plugin distribution. */
object ResearchRuntimeLocation {
    const val RUNTIME_DIRECTORY: String = "research-runtime"
    const val MANIFEST_FILE: String = "proxy-manifest.json"
    const val PLUGIN_ID: String = "me.code4me"

    /** The plugin's exploded runtime directory, or `null` when it is not exploded. */
    fun locate(plugin: PluginDescriptor? = pluginDescriptor()): Path? {
        val base = plugin?.pluginPath ?: return null
        val candidates =
            listOf(
                base.resolve(RUNTIME_DIRECTORY),
                base.resolve("lib").resolve(RUNTIME_DIRECTORY),
                base.resolve("classes").resolve(RUNTIME_DIRECTORY),
            )
        return candidates.firstOrNull { Files.isDirectory(it) }
    }

    private fun pluginDescriptor(): PluginDescriptor? =
        try {
            PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
}

/**
 * Reads and verifies the packaged `research-runtime/proxy-manifest.json` from an
 * exploded root, the plugin classpath, or both.
 *
 * A platform entry declares the proxy's self-contained payload only; every
 * declared file is contained, sized and digest-verified. The real agent is not
 * part of the proxy manifest: it is installed by [PackagedAgentInstaller].
 *
 * @param explodedRoot an explicit on-disk runtime root (tests), or `null`.
 * @param cacheRoot destination root for classpath extraction (`null` disables it).
 * @param devRuntime explicit opt-in development-runtime configuration.
 * @param resources classpath resource source (injectable for tests).
 */
class PackagedProxyRuntimeResolver(
    private val explodedRoot: Path?,
    private val os: String = HostPlatform.os(),
    private val arch: String = HostPlatform.arch(),
    private val hasher: ContentHasher = ContentHasher.STREAMING,
    private val cacheRoot: Path? = defaultCacheRoot(),
    private val devRuntime: DevelopmentRuntime = DevelopmentRuntime.DISABLED,
    private val resources: RuntimeResourceSource = ClasspathRuntimeResources,
) : ProxyRuntimeResolver {
    override fun resolve(): ProxyRuntimeResolution {
        val exploded = explodedRoot?.toAbsolutePath()?.normalize()
        if (exploded != null) {
            val manifestPath = exploded.resolve(ResearchRuntimeLocation.MANIFEST_FILE)
            if (Files.isRegularFile(manifestPath)) {
                val bytes =
                    try {
                        Files.readAllBytes(manifestPath)
                    } catch (exception: Exception) {
                        return fail(MALFORMED_MANIFEST, "the exploded proxy manifest could not be read: ${exception.message}")
                    }
                return resolveFrom(bytes, exploded)
            }
        }
        val bytes =
            try {
                resources.open(MANIFEST_RESOURCE)?.use { it.readBytes() }
            } catch (exception: Exception) {
                return fail(MALFORMED_MANIFEST, "the packaged proxy manifest could not be read: ${exception.message}")
            } ?: return fail(
                ARTIFACT_MISSING,
                "the packaged proxy manifest was not found on disk or on the plugin classpath",
            )
        return resolveFrom(bytes, null)
    }

    private fun resolveFrom(
        manifestBytes: ByteArray,
        explodedRuntimeRoot: Path?,
    ): ProxyRuntimeResolution {
        val document =
            try {
                parseCanonicalJsonObject(String(manifestBytes, Charsets.UTF_8))
            } catch (exception: Exception) {
                return fail(MALFORMED_MANIFEST, "the packaged proxy manifest is not valid JSON: ${exception.message}")
            }
        val platform =
            try {
                selectPlatform(document)
            } catch (exception: ManifestParseException) {
                return fail(MALFORMED_MANIFEST, exception.message ?: "malformed proxy manifest")
            } ?: return fail(UNSUPPORTED_PLATFORM, "the packaged proxy does not support platform '$os-$arch'")
        val bundle =
            try {
                parseBundle(platform)
            } catch (exception: ManifestParseException) {
                return fail(MALFORMED_MANIFEST, exception.message ?: "malformed proxy manifest")
            }

        val root: Path =
            if (explodedRuntimeRoot != null) {
                explodedRuntimeRoot
            } else {
                when (val materialized = materialize(manifestBytes, bundle)) {
                    is Materialized.Ready -> materialized.root
                    is Materialized.Failed -> return ProxyRuntimeResolution.Failed(materialized.error)
                }
            }

        val effective = bundle

        verifyBundle(root, effective)?.let { return ProxyRuntimeResolution.Failed(it) }

        val development = !effective.selfContained
        val interpreter = devRuntime.interpreter?.takeIf { it.isNotBlank() }
        if (development && (!devRuntime.allowed || interpreter == null)) {
            return fail(
                NOT_SELF_CONTAINED,
                "the packaged proxy for '$os-$arch' is a development source bundle; " +
                    "enable the development runtime and configure an interpreter to use it",
            )
        }

        val proxyEntry =
            effective.files.firstOrNull { it.path == effective.entrypoint.first() }
                ?: return fail(
                    MALFORMED_MANIFEST,
                    "entrypoint '${effective.entrypoint.first()}' is not declared in files",
                )
        val proxyPath =
            containedPath(root, proxyEntry.path)
                ?: return fail(PATH_ESCAPE, "proxy entrypoint escapes the runtime root", proxyEntry.path)
        val proxyArgv =
            if (development) {
                listOf(interpreter!!, proxyPath.toString()) + effective.entrypoint.drop(1)
            } else {
                listOf(proxyPath.toString()) + effective.entrypoint.drop(1)
            }

        return ProxyRuntimeResolution.Resolved(
            ResolvedProxyRuntime(
                runtimeRoot = root,
                proxyArgv = proxyArgv,
                proxyDigest = proxyEntry.sha256,
                selfContained = effective.selfContained,
                development = development,
            ),
        )
    }

    private fun selectPlatform(document: Map<String, Any?>): Map<String, Any?>? {
        if (document["schema_version"] != SUPPORTED_SCHEMA_VERSION) {
            throw ManifestParseException("unsupported proxy manifest schema_version")
        }
        val platforms = document["platforms"] as? List<*> ?: throw ManifestParseException("proxy manifest 'platforms' must be an array")
        if (platforms.isEmpty()) throw ManifestParseException("proxy manifest declares no platforms")
        return platforms
            .mapIndexed { index, item ->
                val map = item as? Map<*, *> ?: throw ManifestParseException("platforms[$index] must be an object")
                stringKeyedMap(map)
            }.firstOrNull { it["os"] == os && it["arch"] == arch }
    }

    private fun parseBundle(platform: Map<String, Any?>): BundleSpec {
        val selfContained = platform["self_contained"] as? Boolean ?: false
        val entrypoint = stringList(platform["entrypoint"], "entrypoint").filter { it.isNotEmpty() }
        if (entrypoint.isEmpty()) throw ManifestParseException("platform '$os-$arch' declares no entrypoint")
        val files = parseFiles(platform["files"], "files")
        return BundleSpec(selfContained = selfContained, entrypoint = entrypoint, files = files)
    }

    private fun parseFiles(
        value: Any?,
        field: String,
    ): List<FileSpec> {
        val list = value as? List<*> ?: throw ManifestParseException("$field must be an array")
        if (list.isEmpty()) throw ManifestParseException("$field must not be empty")
        return list.mapIndexed { index, item ->
            val map = item as? Map<*, *> ?: throw ManifestParseException("$field[$index] must be an object")
            val entry = stringKeyedMap(map)
            FileSpec(
                path = entry["path"] as? String ?: throw ManifestParseException("$field[$index].path is required"),
                sha256 = entry["sha256"] as? String ?: throw ManifestParseException("$field[$index].sha256 is required"),
                size = (entry["size"] as? Number)?.toLong() ?: throw ManifestParseException("$field[$index].size is required"),
                executable = entry["executable"] as? Boolean ?: false,
            )
        }
    }

    // ------------------------------------------------------------------
    // Verification (exploded + extracted)
    // ------------------------------------------------------------------

    private fun verifyBundle(
        root: Path,
        bundle: BundleSpec,
    ): ProxyRuntimeError? {
        for (file in allFiles(bundle)) {
            val contained =
                containedPath(root, file.path)
                    ?: return ProxyRuntimeError(PATH_ESCAPE, "declared path escapes the runtime root", file.path)
            if (!Files.isRegularFile(contained)) {
                return ProxyRuntimeError(ARTIFACT_MISSING, "declared file is missing", file.path)
            }
            if (!isLowercaseSha256(file.sha256)) {
                return ProxyRuntimeError(MALFORMED_MANIFEST, "declared sha256 is not 64 lowercase hex", file.path)
            }
            if (file.size < 0L) {
                return ProxyRuntimeError(MALFORMED_MANIFEST, "declared size must not be negative", file.path)
            }
            when (val verification = verifyFile(file, contained)) {
                is FileVerification.Failed -> return ProxyRuntimeError(verification.code, verification.message, file.path)
                FileVerification.Passed -> Unit
            }
        }
        return null
    }

    private fun verifyFile(
        file: FileSpec,
        resolved: Path,
    ): FileVerification {
        val component =
            RuntimeComponent(
                componentId = file.path,
                path = file.path,
                sha256 = file.sha256,
                size = file.size,
                os = os,
                arch = arch,
                executable = file.path,
            )
        return when (val verification = RuntimeArtifactResolver.verifyFile(component, resolved, hasher)) {
            is VerificationResult.Passed -> FileVerification.Passed
            is VerificationResult.Failed ->
                when (verification.error.code) {
                    ResolutionErrorCode.ARTIFACT_MISSING ->
                        FileVerification.Failed(ARTIFACT_MISSING, verification.error.message)
                    else -> FileVerification.Failed(DIGEST_MISMATCH, verification.error.message)
                }
        }
    }

    // ------------------------------------------------------------------
    // Classpath extraction
    // ------------------------------------------------------------------

    private fun materialize(
        manifestBytes: ByteArray,
        bundle: BundleSpec,
    ): Materialized {
        val root =
            cacheRoot ?: return Materialized.Failed(
                ProxyRuntimeError(EXTRACTION_FAILED, "no runtime cache root is configured for the packaged proxy"),
            )
        val normalizedRoot =
            try {
                root.toAbsolutePath().normalize()
            } catch (exception: Exception) {
                return Materialized.Failed(
                    ProxyRuntimeError(EXTRACTION_FAILED, "the runtime cache root is not resolvable: ${exception.message}"),
                )
            }
        val manifestDigest = sha256Hex(manifestBytes)
        val cacheDir = normalizedRoot.resolve(manifestDigest).normalize()
        if (!cacheDir.startsWith(normalizedRoot)) {
            return Materialized.Failed(ProxyRuntimeError(PATH_ESCAPE, "the runtime cache directory escapes the cache root"))
        }
        if (isCacheVerified(cacheDir, manifestDigest, bundle)) return Materialized.Ready(cacheDir)

        val temp =
            try {
                Files.createDirectories(normalizedRoot)
                Files.createTempDirectory(normalizedRoot, ".extract-")
            } catch (exception: Exception) {
                return Materialized.Failed(
                    ProxyRuntimeError(EXTRACTION_FAILED, "the runtime cache directory could not be created: ${exception.message}"),
                )
            }
        try {
            for (file in allFiles(bundle)) {
                val destination =
                    containedPath(temp, file.path)
                        ?: return Materialized.Failed(ProxyRuntimeError(PATH_ESCAPE, "declared path escapes the runtime root", file.path))
                destination.parent?.let {
                    try {
                        Files.createDirectories(it)
                    } catch (exception: Exception) {
                        return Materialized.Failed(
                            ProxyRuntimeError(EXTRACTION_FAILED, "could not create '${file.path}': ${exception.message}", file.path),
                        )
                    }
                }
                val source =
                    try {
                        resources.open(resourcePath(file.path))
                    } catch (exception: Exception) {
                        return Materialized.Failed(
                            ProxyRuntimeError(EXTRACTION_FAILED, "bundled resource could not be opened: ${exception.message}", file.path),
                        )
                    } ?: return Materialized.Failed(
                        ProxyRuntimeError(EXTRACTION_FAILED, "bundled resource is missing", file.path),
                    )
                val failure =
                    source.use { input ->
                        try {
                            Files.newOutputStream(
                                destination,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE,
                            ).use { sink -> copyAndVerify(input, sink, file) }
                        } catch (exception: Exception) {
                            ProxyRuntimeError(EXTRACTION_FAILED, "could not extract '${file.path}': ${exception.message}", file.path)
                        }
                    }
                if (failure != null) return Materialized.Failed(failure)
                if (file.executable) makeExecutable(destination)
            }
            try {
                Files.writeString(temp.resolve(MARKER_FILE), manifestDigest)
            } catch (exception: Exception) {
                return Materialized.Failed(ProxyRuntimeError(EXTRACTION_FAILED, "could not write the cache marker: ${exception.message}"))
            }
            try {
                Files.move(temp, cacheDir, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                if (Files.exists(cacheDir) && isCacheVerified(cacheDir, manifestDigest, bundle)) {
                    // Another process won the race with a verified cache; reuse it.
                } else {
                    try {
                        Files.move(temp, cacheDir)
                    } catch (exception: Exception) {
                        return Materialized.Failed(
                            ProxyRuntimeError(EXTRACTION_FAILED, "the runtime cache could not be committed: ${exception.message}"),
                        )
                    }
                }
            }
        } finally {
            if (Files.exists(temp)) runCatching { deleteRecursively(temp) }
        }
        return if (isCacheVerified(cacheDir, manifestDigest, bundle)) {
            Materialized.Ready(cacheDir)
        } else {
            Materialized.Failed(ProxyRuntimeError(EXTRACTION_FAILED, "the extracted runtime cache could not be verified"))
        }
    }

    private fun isCacheVerified(
        cacheDir: Path,
        manifestDigest: String,
        bundle: BundleSpec,
    ): Boolean {
        val marker = cacheDir.resolve(MARKER_FILE)
        if (!Files.isRegularFile(marker)) return false
        val recorded =
            try {
                Files.readString(marker).trim()
            } catch (_: Exception) {
                return false
            }
        if (recorded != manifestDigest) return false
        for (file in allFiles(bundle)) {
            val path = containedPath(cacheDir, file.path) ?: return false
            if (!Files.isRegularFile(path)) return false
            if (Files.size(path) != file.size) return false
        }
        return true
    }

    private fun copyAndVerify(
        source: InputStream,
        sink: OutputStream,
        file: FileSpec,
    ): ProxyRuntimeError? {
        val digest = MessageDigest.getInstance("SHA-256")
        val count = DigestInputStream(source, digest).transferTo(sink)
        if (count != file.size) {
            return ProxyRuntimeError(
                DIGEST_MISMATCH,
                "bundled resource size $count does not match declared ${file.size}",
                file.path,
            )
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != file.sha256) {
            return ProxyRuntimeError(DIGEST_MISMATCH, "bundled resource sha256 does not match the manifest", file.path)
        }
        return null
    }

    private fun makeExecutable(path: Path) {
        try {
            val permissions = Files.getPosixFilePermissions(path).toMutableSet()
            permissions += PosixFilePermission.OWNER_EXECUTE
            permissions += PosixFilePermission.GROUP_EXECUTE
            permissions += PosixFilePermission.OTHERS_EXECUTE
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            path.toFile().setExecutable(true, false)
        } catch (_: Exception) {
            path.toFile().setExecutable(true, false)
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Resolve [relative] under [root] with containment; `null` when it escapes. */
    private fun containedPath(
        root: Path,
        relative: String,
    ): Path? {
        if (pathSafety(relative) != null) return null
        val component =
            RuntimeComponent(
                componentId = relative,
                path = relative,
                sha256 = PLACEHOLDER_DIGEST,
                size = 1L,
                os = os,
                arch = arch,
                executable = relative,
            )
        return when (val resolution = RuntimeArtifactResolver.resolveUnderRoot(root, component)) {
            is PathResolution.Contained -> resolution.path
            is PathResolution.Rejected -> null
        }
    }

    private fun stringList(
        value: Any?,
        field: String,
    ): List<String> =
        (value as? List<*>)?.map { it as? String ?: throw ManifestParseException("$field must contain only strings") }
            ?: throw ManifestParseException("'$field' must be an array")

    private fun resourcePath(relative: String): String = "$RESOURCE_BASE/$relative"

    private fun allFiles(bundle: BundleSpec): List<FileSpec> = bundle.files.distinct()

    private fun fail(
        code: ProxyRuntimeErrorCode,
        message: String,
        path: String? = null,
    ): ProxyRuntimeResolution = ProxyRuntimeResolution.Failed(ProxyRuntimeError(code, message, path))

    private class ManifestParseException(message: String) : IllegalArgumentException(message)

    private data class FileSpec(
        val path: String,
        val sha256: String,
        val size: Long,
        val executable: Boolean,
    )

    private data class BundleSpec(
        val selfContained: Boolean,
        val entrypoint: List<String>,
        val files: List<FileSpec>,
    )

    private sealed interface FileVerification {
        object Passed : FileVerification

        data class Failed(val code: ProxyRuntimeErrorCode, val message: String) : FileVerification
    }

    private sealed interface Materialized {
        data class Ready(val root: Path) : Materialized

        data class Failed(val error: ProxyRuntimeError) : Materialized
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION: String = "1"

        /** Classpath prefix of the bundled runtime, including the leading slash. */
        const val RESOURCE_BASE: String = "/research-runtime"

        /** Absolute classpath path of the bundled manifest. */
        const val MANIFEST_RESOURCE: String = "$RESOURCE_BASE/${ResearchRuntimeLocation.MANIFEST_FILE}"

        private const val MARKER_FILE = ".code4me-verified"
        private const val PLACEHOLDER_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"

        /** Default extraction cache: `<system>/code4me/research-runtime`. */
        fun defaultCacheRoot(): Path? =
            try {
                Path.of(PathManager.getSystemPath(), "code4me", "research-runtime")
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }

        /** A resolver for the plugin's own staged/classpath runtime (or a fail-closed one). */
        fun forPlugin(
            allowDevelopmentRuntime: Boolean = false,
            developmentInterpreter: String? = null,
        ): PackagedProxyRuntimeResolver =
            PackagedProxyRuntimeResolver(
                explodedRoot = ResearchRuntimeLocation.locate(),
                devRuntime = DevelopmentRuntime(allowDevelopmentRuntime, developmentInterpreter),
            )
    }
}
