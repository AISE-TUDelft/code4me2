package me.code4me.services.agent

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.FileNotFoundException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

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
    val downloadUrl: String? = null,
    val size: Long = 0,
)

sealed interface RuntimeInstallResult {
    data class Ready(val executable: Path, val artifact: RuntimeArtifact) : RuntimeInstallResult
    data class Unavailable(val message: String) : RuntimeInstallResult
    data class Failed(val message: String, val retryable: Boolean = false) : RuntimeInstallResult
}

/** Verifies and installs the exact platform archive without executing it. */
class ManagedRuntimeInstaller(
    private val installRoot: Path = Path.of(PathManager.getSystemPath(), "code4me", "runtimes"),
    private val downloadClient: Call.Factory = DOWNLOAD_CLIENT,
    private val resourceLoader: (String) -> InputStream? = { ManagedRuntimeInstaller::class.java.getResourceAsStream(it) },
) {
    private val log = thisLogger()
    private val json = Json { ignoreUnknownKeys = true }

    fun ensureInstalled(repair: Boolean = false): RuntimeInstallResult {
        val artifact = try {
            selectArtifact()
        } catch (e: Exception) {
            return RuntimeInstallResult.Failed("The bundled Code4Me runtime manifest is invalid: ${e.message}")
        } ?: return RuntimeInstallResult.Unavailable("No runtime is bundled for ${platformId()}/${architectureId()}.")
        return ensureInstalled(artifact, repair)
    }

    /**
     * With [requirePublicRelease] (the study path) a verified cache entry is used as is, and a
     * cache miss is filled only from the artifact's exact canonical GitHub Release URL, never
     * from an archive bundled in the plugin.
     */
    fun ensureInstalled(
        artifact: RuntimeArtifact,
        repair: Boolean = false,
        progress: (Long, Long) -> Unit = { _, _ -> },
        isCurrent: () -> Boolean = { true },
        requirePublicRelease: Boolean = false,
    ): RuntimeInstallResult {
        if (artifact.runtimeId != SUPPORTED_RUNTIME_ID || artifact.managedProtocol != SUPPORTED_MANAGED_PROTOCOL) {
            return RuntimeInstallResult.Failed("This study's agent requires a newer Code4Me plugin.")
        }
        if (artifact.platform != platformId() || artifact.architecture != architectureId()) {
            return RuntimeInstallResult.Unavailable("This study has no agent for ${platformId()}/${architectureId()}.")
        }
        if (!artifact.sha256.matches(Regex("[0-9a-fA-F]{64}")) || !artifact.version.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,127}")) || artifact.size < 0 || artifact.executable.isBlank() || Path.of(artifact.executable).isAbsolute) {
            return RuntimeInstallResult.Failed("The study's runtime identity is invalid.")
        }
        val lock = INSTALL_LOCKS.computeIfAbsent(installRoot.toAbsolutePath().normalize().toString() + artifact.sha256.lowercase()) { ReentrantLock() }
        var locked = false
        return try {
            while (!locked) {
                if (!isCurrent()) throw CancellationException("Agent preparation cancelled.")
                locked = lock.tryLock(100, TimeUnit.MILLISECONDS)
            }
            val archive = prepareArchive(artifact, progress, isCurrent, requirePublicRelease)
            val actualChecksum = artifact.sha256.lowercase()
            val artifactRoot = installRoot.resolve(artifact.runtimeId)
            val baseDestination = artifactRoot.resolve("${artifact.version}-${actualChecksum.take(12)}")
            val valid = !repair && isValidInstall(baseDestination, artifact, actualChecksum, archive)
            if (valid) return RuntimeInstallResult.Ready(baseDestination.resolve(artifact.executable), artifact)
            if (!repair && Files.isDirectory(artifactRoot)) {
                val repaired = Files.list(artifactRoot).use { paths ->
                    paths.filter { it.fileName.toString().startsWith("${baseDestination.fileName}-repair-") &&
                        isValidInstall(it, artifact, actualChecksum, archive) }.findFirst().orElse(null)
                }
                if (repaired != null) return RuntimeInstallResult.Ready(repaired.resolve(artifact.executable), artifact)
            }
            val destination = if (!Files.exists(baseDestination)) baseDestination
                else artifactRoot.resolve("${artifact.version}-${actualChecksum.take(12)}-repair-${UUID.randomUUID()}")
            val executable = destination.resolve(artifact.executable).normalize()
            require(executable.startsWith(destination) && !Path.of(artifact.executable).isAbsolute) { "Unsafe runtime executable path." }
            Files.createDirectories(destination.parent)
            val staging = Files.createTempDirectory(destination.parent, ".${artifact.version}-install-")
            try {
                val extraction = Files.newInputStream(archive).use {
                    extractZip(it, staging, artifact.executable, isCurrent)
                }
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
                check(isCurrent()) { "Agent preparation cancelled." }
                Files.writeString(staging.resolve(INSTALL_MARKER), actualChecksum)
                try { Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(staging, destination) }
                RuntimeInstallResult.Ready(executable, artifact)
            } finally {
                deleteTreeIfPresent(staging)
            }
        } catch (e: Exception) {
            if (e !is CancellationException) log.warn("Managed runtime installation failed", e)
            if (e is FileNotFoundException) RuntimeInstallResult.Unavailable(e.message.orEmpty())
            else RuntimeInstallResult.Failed(e.message ?: "The study's agent could not be prepared.",
                retryable = e is java.io.IOException && e !is java.util.zip.ZipException)
        } finally {
            if (locked) lock.unlock()
        }
    }

    private fun prepareArchive(
        artifact: RuntimeArtifact,
        progress: (Long, Long) -> Unit,
        isCurrent: () -> Boolean,
        requirePublicRelease: Boolean,
    ): Path {
        val archives = installRoot.resolve(artifact.runtimeId).resolve("archives")
        Files.createDirectories(archives)
        val archive = archives.resolve("${artifact.sha256.lowercase()}.zip")
        if (Files.isRegularFile(archive) && (artifact.size == 0L || Files.size(archive) == artifact.size) &&
            Files.newInputStream(archive).use(::sha256) == artifact.sha256.lowercase()) return archive
        val temporary = Files.createTempFile(archives, ".download-", ".part")
        try {
            val bundled = if (requirePublicRelease) null else
                runCatching { selectArtifact() }.getOrNull()?.takeIf { it.sha256.equals(artifact.sha256, ignoreCase = true) }
            val input = bundled?.let { resourceLoader("/${it.archive}") }
            if (input != null) input.use { copyArchive(it, temporary, artifact, progress, isCurrent) }
            else downloadArchive(artifact, temporary, progress, isCurrent)
            require(artifact.size == 0L || Files.size(temporary) == artifact.size) { "The agent archive size does not match the study release." }
            require(Files.newInputStream(temporary).use(::sha256) == artifact.sha256.lowercase()) { "The agent archive failed checksum verification." }
            Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return archive
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun publicReleaseUrl(artifact: RuntimeArtifact): okhttp3.HttpUrl {
        val url = artifact.downloadUrl?.toHttpUrl()
            ?: throw FileNotFoundException("The study's agent has no public GitHub Release URL. Contact the research team.")
        require(url.scheme == "https" && url.host == "github.com" && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null && url.pathSegments.size == 6 &&
            url.pathSegments[0].equals(RELEASE_OWNER, ignoreCase = true) && url.pathSegments[1].equals(RELEASE_REPOSITORY, ignoreCase = true) &&
            url.pathSegments.subList(2, 4) == listOf("releases", "download") && url.pathSegments[4] != "latest" &&
            url.pathSegments.all { it.isNotEmpty() && it != "." && it != ".." && '/' !in it } && url.pathSegments.last() == Path.of(artifact.archive).fileName.toString()) {
            "The study's agent must come from an exact public GitHub release asset of $RELEASE_OWNER/$RELEASE_REPOSITORY."
        }
        return url
    }

    private fun downloadArchive(artifact: RuntimeArtifact, destination: Path, progress: (Long, Long) -> Unit, isCurrent: () -> Boolean) {
        var url = publicReleaseUrl(artifact)
        repeat(6) {
            if (!isCurrent()) throw CancellationException("Agent preparation cancelled.")
            require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.host in DOWNLOAD_HOSTS) { "Untrusted agent download redirect." }
            downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isRedirect) {
                    url = url.resolve(response.header("Location") ?: error("Agent download redirect has no location."))
                        ?: error("Agent download redirect is invalid.")
                } else {
                    if (response.code == 429 || response.code >= 500) throw java.io.IOException("Agent download failed (HTTP ${response.code}). Please retry.")
                    require(response.isSuccessful) { "Agent download failed (HTTP ${response.code}). Choose Retry to try again." }
                    val body = response.body ?: error("The agent download is empty.")
                    body.byteStream().use { copyArchive(it, destination, artifact, progress, isCurrent) }
                    return
                }
            }
        }
        error("Too many agent download redirects.")
    }

    private fun copyArchive(input: InputStream, destination: Path, artifact: RuntimeArtifact, progress: (Long, Long) -> Unit, isCurrent: () -> Boolean) {
        Files.newOutputStream(destination).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var written = 0L
            while (true) {
                if (!isCurrent()) throw CancellationException("Agent preparation cancelled.")
                val read = input.read(buffer)
                if (read < 0) break
                written += read
                require(written <= MAX_ARCHIVE_BYTES && (artifact.size == 0L || written <= artifact.size)) { "The agent archive exceeds its declared size." }
                output.write(buffer, 0, read)
                progress(written, artifact.size)
            }
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
        // The server recipe declares the adapter once at the top level; the
        // per-artifact block (when present) wins.
        val recipeAdapterDigest = root["adapter"]?.jsonObject?.get("digest")?.jsonPrimitive?.content
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
                size = item["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                downloadUrl = item["download_url"]?.jsonPrimitive?.content,
                adapterDigest = item["adapter"]?.jsonObject?.get("digest")?.jsonPrimitive?.content
                    ?: recipeAdapterDigest,
            )
        }?.firstOrNull { it.platform == platformId() && it.architecture == architectureId() }
            ?.also { artifact ->
                require(artifact.runtimeId == SUPPORTED_RUNTIME_ID) { "Unsupported managed runtime ID." }
                require(artifact.managedProtocol == SUPPORTED_MANAGED_PROTOCOL) {
                    "Runtime artifact uses an unsupported managed protocol."
                }
            }
    }

    private fun isValidInstall(destination: Path, artifact: RuntimeArtifact, checksum: String, archive: Path): Boolean = runCatching {
        if (!Files.isDirectory(destination) || Files.readString(destination.resolve(INSTALL_MARKER)).trim() != checksum) return false
        if (!Files.isExecutable(destination.resolve(artifact.executable))) return false
        if (Files.walk(destination).use { paths -> paths.anyMatch(Files::isSymbolicLink) }) return false
        ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            val executableEntry = entries.firstOrNull { it.name == artifact.executable }
                ?: entries.filter { it.name.endsWith("/${artifact.executable}") && Path.of(it.name).nameCount <= 3 }.singleOrNull()
                ?: return false
            val prefix = executableEntry.name.removeSuffix(artifact.executable)
            val expected = entries.map { entry ->
                val relative = if (prefix.isNotEmpty() && entry.name.startsWith(prefix)) entry.name.removePrefix(prefix) else entry.name
                val file = destination.resolve(relative).normalize()
                if (!file.startsWith(destination) || Files.isSymbolicLink(file) || !Files.isRegularFile(file) ||
                    zip.getInputStream(entry).use(::sha256) != Files.newInputStream(file).use(::sha256)) return false
                file
            }.toSet()
            Files.walk(destination).use { files ->
                expected.size == entries.size && files.filter { !Files.isDirectory(it) && it != destination.resolve(INSTALL_MARKER) }.toList().toSet() == expected
            }
        }
    }.getOrDefault(false)

    private data class Extraction(
        val entryCount: Int,
        val extractedBytes: Long,
        val sawExecutableEntry: Boolean,
    )

    private fun extractZip(input: InputStream, destination: Path, executable: String, isCurrent: () -> Boolean): Extraction {
        var count = 0
        var extractedBytes = 0L
        var sawExecutableEntry = false
        val written = mutableSetOf<Path>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                if (!isCurrent()) throw CancellationException("Agent preparation cancelled.")
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
                    require(written.add(output)) { "Runtime archive contains duplicate file paths: ${entry.name}" }
                    Files.createDirectories(output.parent)
                    Files.newOutputStream(output).use { target ->
                        extractedBytes = copyBounded(zip, target, extractedBytes, isCurrent)
                    }
                }
                zip.closeEntry()
            }
        }
        return Extraction(count, extractedBytes, sawExecutableEntry)
    }

    private fun copyBounded(input: InputStream, output: OutputStream, alreadyWritten: Long, isCurrent: () -> Boolean): Long {
        var total = alreadyWritten
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            if (!isCurrent()) throw CancellationException("Agent preparation cancelled.")
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
        internal fun selfCheck(executable: Path, expectedVersion: String? = null): String? = try {
            val process = ProcessBuilder(executable.toString(), "--self-check")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(20L, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "The bundled Code4Me runtime self-check timed out."
            } else if (process.exitValue() != 0) {
                val detail = process.inputStream.readNBytes(8192)
                    .toString(Charsets.UTF_8)
                    .trim()
                    .take(500)
                "The bundled Code4Me runtime self-check failed" +
                    if (detail.isBlank()) "." else ": $detail"
            } else {
                val output = process.inputStream.readNBytes(8192).toString(Charsets.UTF_8)
                if (expectedVersion != null && Json.parseToJsonElement(output).jsonObject["runtime_version"]?.jsonPrimitive?.content != expectedVersion)
                    "The installed agent version does not match the study release."
                else null
            }
        } catch (e: Exception) {
            com.intellij.openapi.diagnostic.Logger.getInstance(ManagedRuntimeInstaller::class.java).warn("Managed runtime self-check could not start", e)
            "The bundled Code4Me runtime could not start (${e.message ?: "unknown error"})."
        }

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

        private val INSTALL_LOCKS = ConcurrentHashMap<String, ReentrantLock>()
        private val DOWNLOAD_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        // Only runtime releases published by this repository are downloaded; the server's
        // release import enforces the same pin (manifest_import.RUNTIME_RELEASE_REPOSITORY).
        private const val RELEASE_OWNER = "AISE-TUDelft"
        private const val RELEASE_REPOSITORY = "code4me2-server"
        private val DOWNLOAD_CLIENT = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).build()
        private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        private const val INSTALL_MARKER = ".code4me-runtime.sha256"
        private const val MAX_ARCHIVE_ENTRIES = 50_000
        private const val MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024
    }
}
