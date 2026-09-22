package me.code4me.services.agent

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class RuntimeArtifact(
    val runtimeId: String,
    val version: String,
    val platform: String,
    val architecture: String,
    val archive: String,
    val sha256: String,
    val executable: String,
    val managedProtocol: String,
    /** Optional `adapter.digest` the recipe declares; `null` when it declares none. */
    val adapterDigest: String? = null,
)

sealed interface RuntimeInstallResult {
    data class Ready(val executable: Path, val artifact: RuntimeArtifact) : RuntimeInstallResult
    data class Unavailable(val message: String) : RuntimeInstallResult
    data class Failed(val message: String) : RuntimeInstallResult
}

/** Installs the platform runtime bundled in the plugin without executing it. */
class ManagedRuntimeInstaller(
    private val installRoot: Path = Path.of(PathManager.getSystemPath(), "code4me", "runtimes"),
    private val resourceLoader: (String) -> InputStream? = { ManagedRuntimeInstaller::class.java.getResourceAsStream(it) },
) {
    private val log = thisLogger()
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun ensureInstalled(repair: Boolean = false): RuntimeInstallResult {
        val artifact = try {
            selectArtifact()
        } catch (e: Exception) {
            log.warn("Managed runtime manifest is invalid", e)
            return RuntimeInstallResult.Failed("The bundled Code4Me runtime manifest is invalid. Reinstall the plugin.")
        } ?: return RuntimeInstallResult.Unavailable(
            "No Code4Me runtime is published for ${platformId()}/${architectureId()}.",
        )
        val archiveResource = "/${artifact.archive}"
        val actualChecksum = resourceLoader(archiveResource)?.use(::sha256)
            ?: return RuntimeInstallResult.Unavailable(
                "The ${artifact.platform}/${artifact.architecture} runtime is not included in this development build.",
            )
        if (!artifact.sha256.matches(Regex("[0-9a-fA-F]{64}")) || actualChecksum != artifact.sha256.lowercase()) {
            return RuntimeInstallResult.Failed("The bundled Code4Me runtime failed checksum verification. Use Repair agent.")
        }

        // Content-addressed installs avoid replacing a directory that an ACP process is
        // currently executing from (notably impossible on Windows). Older versions remain
        // usable until JetBrains releases their processes and can be cleaned up later.
        val artifactRoot = installRoot.resolve(artifact.runtimeId)
        val baseDestination = artifactRoot.resolve("${artifact.version}-${actualChecksum.take(12)}")
        val destination = when {
            !Files.exists(baseDestination) -> baseDestination
            !repair && isValidInstall(baseDestination, artifact, actualChecksum) -> baseDestination
            else -> artifactRoot.resolve("${artifact.version}-${actualChecksum.take(12)}-repair-${UUID.randomUUID()}")
        }
        val executable = destination.resolve(artifact.executable).normalize()
        if (!executable.startsWith(destination)) return RuntimeInstallResult.Failed("The runtime manifest contains an unsafe executable path.")
        if (!repair && isValidInstall(destination, artifact, actualChecksum)) {
            return RuntimeInstallResult.Ready(executable, artifact)
        }

        return try {
            Files.createDirectories(destination.parent)
            val staging = Files.createTempDirectory(destination.parent, ".${artifact.version}-install-")
            try {
                val extraction = resourceLoader(archiveResource)?.use {
                    extractZip(it, staging, artifact.executable)
                } ?: error("The bundled runtime disappeared while it was being installed.")
                var stagedExecutable = staging.resolve(artifact.executable).normalize()
                if (!stagedExecutable.startsWith(staging) || !Files.isRegularFile(stagedExecutable)) {
                    // Tolerate non-flat archives (bundle dir as top-level entry,
                    // possibly alongside flat entries): relocate the directory
                    // holding the executable to the staging root so the installed
                    // layout stays flat (executable beside _internal/).
                    stagedExecutable = relocateExecutableRoot(staging, artifact.executable)
                }
                if (!stagedExecutable.startsWith(staging) || !Files.isRegularFile(stagedExecutable)) {
                    val detail =
                        describeStaging(
                            staging,
                            artifact.executable,
                            extraction.entryCount,
                            extraction.extractedBytes,
                            extraction.sawExecutableEntry,
                        )
                    log.warn("Managed runtime archive layout unexpected: $detail")
                    require(false) {
                        "Runtime archive does not contain ${artifact.executable} $detail. " +
                            "Full detail is in idea.log; use Repair agent after updating the plugin."
                    }
                }
                require(stagedExecutable.toFile().setExecutable(true, true) || Files.isExecutable(stagedExecutable)) {
                    "The runtime executable could not be marked executable."
                }
                Files.writeString(staging.resolve(INSTALL_MARKER), actualChecksum)
                try { Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: Exception) { Files.move(staging, destination) }
                RuntimeInstallResult.Ready(executable, artifact)
            } finally {
                deleteTreeIfPresent(staging)
            }
        } catch (e: Exception) {
            log.warn("Managed runtime installation failed", e)
            RuntimeInstallResult.Failed(e.message ?: "The bundled Code4Me runtime could not be installed.")
        }
    }

    internal fun selectArtifact(): RuntimeArtifact? {
        val manifest = resourceLoader(MANIFEST_RESOURCE)?.bufferedReader()?.use { it.readText() } ?: return null
        val root = json.parseToJsonElement(manifest).jsonObject
        require(root.getValue("manifest_version").jsonPrimitive.content == SUPPORTED_MANIFEST_VERSION) {
            "Unsupported runtime manifest version."
        }
        require(root.getValue("managed_protocol_version").jsonPrimitive.content == SUPPORTED_MANAGED_PROTOCOL) {
            "Unsupported managed protocol version."
        }
        return root["artifacts"]?.jsonArray?.map { element ->
            val item = element.jsonObject
            RuntimeArtifact(
                runtimeId = item.getValue("runtime_id").jsonPrimitive.content,
                version = item.getValue("version").jsonPrimitive.content,
                platform = item.getValue("platform").jsonPrimitive.content,
                architecture = item.getValue("architecture").jsonPrimitive.content,
                archive = item.getValue("archive").jsonPrimitive.content,
                sha256 = item.getValue("sha256").jsonPrimitive.content,
                executable = item.getValue("executable").jsonPrimitive.content,
                managedProtocol = item.getValue("managed_protocol").jsonPrimitive.content,
                adapterDigest = item["adapter"]?.jsonObject?.get("digest")?.jsonPrimitive?.content,
            )
        }?.firstOrNull { it.platform == platformId() && it.architecture == architectureId() }
            ?.also { artifact ->
                require(artifact.runtimeId == SUPPORTED_RUNTIME_ID) { "Unsupported managed runtime ID." }
                require(artifact.managedProtocol == SUPPORTED_MANAGED_PROTOCOL) {
                    "Runtime artifact uses an unsupported managed protocol."
                }
            }
    }

    private fun isValidInstall(destination: Path, artifact: RuntimeArtifact, checksum: String): Boolean {
        val executable = destination.resolve(artifact.executable).normalize()
        if (!executable.startsWith(destination) || !Files.isRegularFile(executable)) return false
        return runCatching { Files.readString(destination.resolve(INSTALL_MARKER)).trim() == checksum }.getOrDefault(false)
    }

    private data class Extraction(
        val entryCount: Int,
        val extractedBytes: Long,
        val sawExecutableEntry: Boolean,
    )

    private fun extractZip(input: InputStream, destination: Path, executable: String): Extraction {
        var count = 0
        var extractedBytes = 0L
        var sawExecutableEntry = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= MAX_ARCHIVE_ENTRIES) { "Runtime archive contains too many entries." }
                // Normalize the entry name the same way the lookup does, so a
                // "./" prefix or stray separators can't hide the executable.
                val cleanName = entry.name.trim().trimStart('.', '/')
                if (cleanName == executable) sawExecutableEntry = true
                val output = destination.resolve(entry.name).normalize()
                require(output.startsWith(destination)) { "Runtime archive contains an unsafe path: ${entry.name}" }
                if (entry.isDirectory) Files.createDirectories(output)
                else {
                    Files.createDirectories(output.parent)
                    Files.newOutputStream(output).use { target ->
                        extractedBytes = copyBounded(zip, target, extractedBytes)
                    }
                }
                zip.closeEntry()
            }
        }
        return Extraction(count, extractedBytes, sawExecutableEntry)
    }

    private fun copyBounded(input: InputStream, output: OutputStream, alreadyWritten: Long): Long {
        var total = alreadyWritten
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_EXTRACTED_BYTES) { "Runtime archive expands beyond the installation limit." }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun relocateExecutableRoot(staging: Path, executable: String): Path {
        val matches = findExecutable(staging, executable, maxDepth = 3)
        if (matches.size != 1) return staging.resolve(executable).normalize()
        val holder = matches.single().parent
        if (holder == staging) return staging.resolve(executable).normalize()
        Files.list(holder).use { stream ->
            stream.forEach { child ->
                Files.move(
                    child,
                    staging.resolve(child.fileName.toString()),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
        deleteTreeIfPresent(holder)
        return staging.resolve(executable).normalize()
    }

    private fun findExecutable(staging: Path, executable: String, maxDepth: Int): List<Path> =
        runCatching {
            Files.walk(staging, maxDepth).use { stream ->
                stream.filter { it.fileName.toString() == executable && Files.isRegularFile(it) }.toList()
            }
        }.getOrDefault(emptyList())

    private fun describeStaging(
        staging: Path,
        executable: String,
        entryCount: Int,
        byteSize: Long,
        sawExecutableEntry: Boolean,
    ): String {
        // Type markers (no-follow for links): "/" directory, "@" symlink,
        // "*" regular file, "?" anything else (fifo/socket/device).
        val top =
            runCatching {
                Files.list(staging).use { stream ->
                    stream.map { child ->
                        val name = child.fileName.toString()
                        val marker =
                            when {
                                Files.isDirectory(child) -> "/"
                                Files.isSymbolicLink(child) -> "@"
                                Files.isRegularFile(child) -> "*"
                                else -> "?"
                            }
                        name + marker
                    }.sorted().toList()
                }
            }.getOrDefault(emptyList()).take(8).joinToString(",")
        val walkError = findExecutableError(staging, executable)
        val found = findExecutable(staging, executable, maxDepth = 4).size
        val self = probeInfo(staging.resolve(executable))
        return "(entries: $entryCount, bytes: $byteSize, top: $top, found: $found, " +
            "sawEntry: $sawExecutableEntry, self: $self, walkError: $walkError)"
    }

    private fun findExecutableError(staging: Path, executable: String): String =
        try {
            Files.walk(staging, 4).use { stream ->
                stream.filter { it.fileName.toString() == executable }.forEach { _ -> }
            }
            "none"
        } catch (e: Exception) {
            "${e::class.simpleName}: ${e.message?.take(120)}"
        }

    private fun probeInfo(path: Path): String =
        runCatching {
            val attrs =
                Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
            "exists=${Files.exists(path)} dir=${attrs.isDirectory} reg=${attrs.isRegularFile} " +
                "link=${attrs.isSymbolicLink} other=${attrs.isOther} size=${attrs.size()} " +
                "readable=${Files.isReadable(path)} writable=${Files.isWritable(path)} " +
                "target=${runCatching { Files.readSymbolicLink(path)?.toString() }.getOrNull()}"
        }.getOrElse { "unreadable: ${it::class.simpleName}: ${it.message?.take(120)}" }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteTreeIfPresent(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    companion object {
        private const val SUPPORTED_MANIFEST_VERSION = "1"
        private const val SUPPORTED_MANAGED_PROTOCOL = "1"
        private const val SUPPORTED_RUNTIME_ID = "code4me-agent"
        const val MANIFEST_RESOURCE = "/code4me-runtime/manifest.json"

        internal fun platformId(): String = when {
            System.getProperty("os.name").startsWith("Windows", true) -> "windows"
            System.getProperty("os.name").startsWith("Mac", true) -> "macos"
            System.getProperty("os.name").startsWith("Linux", true) -> "linux"
            else -> "unsupported"
        }

        internal fun architectureId(): String = when (System.getProperty("os.arch").lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "amd64", "x86_64", "x64" -> "x64"
            else -> "unsupported"
        }

        private const val INSTALL_MARKER = ".code4me-runtime.sha256"
        private const val MAX_ARCHIVE_ENTRIES = 50_000
        private const val MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024
    }
}
