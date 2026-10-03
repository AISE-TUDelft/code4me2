package me.code4me.services.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import me.code4me.research.bootstrap.AgentReleaseRef
import me.code4me.research.proxy.ProductionPackagedAgentInstaller
import me.code4me.research.proxy.PackagedAgentInstall

class ManagedRuntimeInstallerTest {
    @Test
    fun `two supplied native releases install side by side and speak ACP without a source checkout`() {
        val directories = System.getenv("CODE4ME_NATIVE_RUNTIME_BUNDLES")?.split(java.io.File.pathSeparator).orEmpty()
        Assumptions.assumeTrue(directories.size == 2, "supply two producer bundles")
        val artifacts = directories.map { directory ->
            val manifest = Json.parseToJsonElement(Files.readString(Path.of(directory, "code4me-managed-runtime-release.json"))).jsonObject
            val entry = manifest.getValue("artifacts").jsonArray.map { it.jsonObject }.single {
                it.getValue("platform").jsonPrimitive.content == ManagedRuntimeInstaller.platformId() &&
                    it.getValue("architecture").jsonPrimitive.content == ManagedRuntimeInstaller.architectureId()
            }
            val version = entry.getValue("version").jsonPrimitive.content
            val archive = entry.getValue("archive").jsonPrimitive.content
            RuntimeArtifact(
                "code4me-agent", version,
                ManagedRuntimeInstaller.platformId(), ManagedRuntimeInstaller.architectureId(),
                archive, entry.getValue("sha256").jsonPrimitive.content,
                entry.getValue("executable").jsonPrimitive.content, manifest.getValue("managed_protocol_version").jsonPrimitive.content,
                downloadUrl = "https://github.com/AISE-TUDelft/code4me2-server/releases/download/$version/$archive",
                size = entry.getValue("size").jsonPrimitive.content.toLong(),
            )
        }
        assertTrue(artifacts[0].sha256 != artifacts[1].sha256 && artifacts[0].version != artifacts[1].version)
        var downloads = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            downloads++
            val selected = artifacts.single { chain.request().url.encodedPath.endsWith("/${it.archive}") }
            val bytes = Files.readAllBytes(Path.of(directories[artifacts.indexOf(selected)], selected.archive))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(bytes.toResponseBody()).build()
        }.build()
        val installer = ManagedRuntimeInstaller(Files.createTempDirectory("native-study-runtimes"), client)
        val packaged = ProductionPackagedAgentInstaller { installer }
        val installed = artifacts.map { artifact ->
            val release = AgentReleaseRef("native-agent", artifact.sha256, artifact.version, artifact.sha256, artifact = artifact)
            val ready = packaged.prepare(release, { _, _ -> }, { true }) as PackagedAgentInstall.Ready
            assertNull(packaged.validate(ready), "native self-check for ${artifact.version}")
            val process = ProcessBuilder(ready.argv).directory(Path.of(ready.argv.first()).parent.toFile()).start()
            process.outputStream.bufferedWriter().use {
                it.write(
                    Json.parseToJsonElement(
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                            "protocolVersion":1,"clientCapabilities":{},"clientInfo":{"name":"study-test","version":"1"}}}
                        """,
                    ).toString(),
                )
                it.newLine()
            }
            val exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) process.destroyForcibly()
            assertTrue(exited, "native ACP initialize timed out")
            assertEquals(0, process.exitValue(), process.errorStream.readAllBytes().decodeToString())
            val response = process.inputStream.bufferedReader().readLines().map { Json.parseToJsonElement(it).jsonObject }
                .single { it["id"]?.jsonPrimitive?.content == "1" }
            assertEquals("1", response.getValue("result").jsonObject.getValue("protocolVersion").jsonPrimitive.content)
            val cached = packaged.prepare(release, { _, _ -> }, { true }) as PackagedAgentInstall.Ready
            assertEquals(ready.argv, cached.argv)
            ready.argv.first()
        }
        assertTrue(installed[0] != installed[1])
        assertEquals(2, downloads, "each study release must come from its public URL")
    }

    @Test
    fun `cancelled download removes partial bytes and never publishes an installation`() {
        val zip = archive("agent", "cancelled")
        val root = Files.createTempDirectory("cancelled-study-download")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(zip.toResponseBody()).build()
        }.build()
        val artifact = RuntimeArtifact("code4me-agent", "1.0.0", ManagedRuntimeInstaller.platformId(),
            ManagedRuntimeInstaller.architectureId(), "agent.zip", sha256(zip), "agent", "1",
            downloadUrl = "https://github.com/AISE-TUDelft/code4me2-server/releases/download/v1/agent.zip", size = zip.size.toLong())
        var current = true
        val result = ManagedRuntimeInstaller(root, client) { null }.ensureInstalled(
            artifact, progress = { _, _ -> current = false }, isCurrent = { current },
        )
        assertTrue(result is RuntimeInstallResult.Failed)
        Files.walk(root).use { files -> assertEquals(0, files.filter { Files.isRegularFile(it) }.count()) }
    }

    @Test
    fun `concurrent projects share one download even with differently cased sha pins`() {
        val root = Files.createTempDirectory("shared-study-runtime")
        val zip = archive("agent", "shared")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val downloads = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            downloads.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(zip.toResponseBody()).build()
        }.build()
        val artifact = RuntimeArtifact("code4me-agent", "1.0.0", ManagedRuntimeInstaller.platformId(),
            ManagedRuntimeInstaller.architectureId(), "agent.zip", sha256(zip), "agent", "1",
            downloadUrl = "https://github.com/AISE-TUDelft/code4me2-server/releases/download/v1/agent.zip", size = zip.size.toLong())
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<RuntimeInstallResult> { ManagedRuntimeInstaller(root, client) { null }.ensureInstalled(artifact) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val second = workers.submit<RuntimeInstallResult> { ManagedRuntimeInstaller(root, client) { null }.ensureInstalled(artifact.copy(sha256 = artifact.sha256.uppercase())) }
            release.countDown()
            assertTrue(first.get(5, java.util.concurrent.TimeUnit.SECONDS) is RuntimeInstallResult.Ready)
            assertTrue(second.get(5, java.util.concurrent.TimeUnit.SECONDS) is RuntimeInstallResult.Ready)
            assertEquals(1, downloads.get())
        } finally { workers.shutdownNow() }
    }

    @Test
    fun `an archive cannot overwrite a previously extracted file through another entry name`() {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { stream ->
                for (entry in listOf("agent", "./agent")) {
                    stream.putNextEntry(ZipEntry(entry))
                    stream.write(entry.toByteArray())
                    stream.closeEntry()
                }
            }
        }.toByteArray()
        val recipe = manifest("agent", sha256(zip))
        val installer = ManagedRuntimeInstaller(Files.createTempDirectory("ambiguous-study-archive")) { path ->
            if (path == ManagedRuntimeInstaller.MANIFEST_RESOURCE) recipe.byteInputStream() else zip.inputStream()
        }
        assertTrue(installer.ensureInstalled() is RuntimeInstallResult.Failed)
    }

    @Test
    fun `a damaged installation is repaired once and reused on the next preparation`() {
        val root = Files.createTempDirectory("damaged-study-runtime")
        val zip = archive("agent", "original")
        val recipe = manifest("agent", sha256(zip))
        val installer = ManagedRuntimeInstaller(root) { path ->
            if (path == ManagedRuntimeInstaller.MANIFEST_RESOURCE) recipe.byteInputStream() else zip.inputStream()
        }
        val original = installer.ensureInstalled() as RuntimeInstallResult.Ready
        Files.writeString(original.executable, "corrupted")
        val repaired = installer.ensureInstalled() as RuntimeInstallResult.Ready
        val reused = installer.ensureInstalled() as RuntimeInstallResult.Ready
        assertEquals("original", Files.readString(repaired.executable))
        assertTrue(original.executable != repaired.executable)
        assertEquals(repaired.executable, reused.executable)
    }

    @Test
    fun `a study downloads its pinned agent and reuses it when the plugin bundle differs`() {
        val root = Files.createTempDirectory("study-runtime-download")
        val zip = archive("agent", "study-v1")
        var downloads = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            downloads++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(zip.toResponseBody()).build()
        }.build()
        val requested = RuntimeArtifact(
            "code4me-agent", "1.0.0", ManagedRuntimeInstaller.platformId(),
            ManagedRuntimeInstaller.architectureId(), "agent.zip", sha256(zip), "agent", "1",
            downloadUrl = "https://github.com/AISE-TUDelft/code4me2-server/releases/download/runtime-v1.0.0/agent.zip",
            size = zip.size.toLong(),
        )
        val bundled = archive("agent", "plugin-v2")
        val recipe = manifest("agent", sha256(bundled))
        val installer = ManagedRuntimeInstaller(root, downloadClient = client) { path ->
            if (path == ManagedRuntimeInstaller.MANIFEST_RESOURCE) recipe.byteInputStream() else bundled.inputStream()
        }

        val first = installer.ensureInstalled(requested) as RuntimeInstallResult.Ready
        val second = installer.ensureInstalled(requested) as RuntimeInstallResult.Ready

        assertEquals("study-v1", Files.readString(first.executable))
        assertEquals(first.executable, second.executable)
        assertEquals(1, downloads)
    }

    @Test
    fun `study downloads matching bundled bytes from release while ordinary setup can use bundle`() {
        val zip = archive("agent", "same-agent")
        val recipe = manifest("agent", sha256(zip))
        val artifact = RuntimeArtifact(
            "code4me-agent", "1.0.0", ManagedRuntimeInstaller.platformId(),
            ManagedRuntimeInstaller.architectureId(), "runtime.zip", sha256(zip), "agent", "1",
            downloadUrl = "https://github.com/AISE-TUDelft/code4me2-server/releases/download/runtime-v1.0.0/runtime.zip",
            size = zip.size.toLong(),
        )
        var downloads = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            downloads++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(zip.toResponseBody()).build()
        }.build()
        fun installer(root: Path) = ManagedRuntimeInstaller(root, client) { path ->
            if (path == ManagedRuntimeInstaller.MANIFEST_RESOURCE) recipe.byteInputStream() else zip.inputStream()
        }

        val study = installer(Files.createTempDirectory("study-matching-bundle"))
        assertTrue(study.ensureInstalled(artifact, requirePublicRelease = true) is RuntimeInstallResult.Ready)
        assertEquals(1, downloads, "study cache miss must download even when identical bytes are bundled")
        assertTrue(study.ensureInstalled(artifact, requirePublicRelease = true) is RuntimeInstallResult.Ready)
        assertEquals(1, downloads, "verified cache should work without another download")

        val ordinary = installer(Files.createTempDirectory("ordinary-matching-bundle"))
        assertTrue(ordinary.ensureInstalled() is RuntimeInstallResult.Ready)
        assertEquals(1, downloads, "ordinary setup may still use its bundled runtime")
    }

    @Test
    fun `study without a public URL never uses the bundle and runs only from a verified cache`() {
        val zip = archive("agent", "same-agent")
        val recipe = manifest("agent", sha256(zip))
        val root = Files.createTempDirectory("study-no-public-url")
        val artifact = RuntimeArtifact(
            "code4me-agent", "1.0.0", ManagedRuntimeInstaller.platformId(),
            ManagedRuntimeInstaller.architectureId(), "runtime.zip", sha256(zip), "agent", "1",
            size = zip.size.toLong(),
        )
        var downloads = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            downloads++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(zip.toResponseBody()).build()
        }.build()
        val installer = ManagedRuntimeInstaller(root, client) { path ->
            if (path == ManagedRuntimeInstaller.MANIFEST_RESOURCE) recipe.byteInputStream() else zip.inputStream()
        }
        assertTrue(installer.ensureInstalled(artifact, requirePublicRelease = true) is RuntimeInstallResult.Unavailable,
            "a cache miss without a public URL must not fall back to the matching bundle")

        // A seeded cache entry (as the e2e harness does) is verified by its digest alone.
        Files.write(Files.createDirectories(root.resolve("code4me-agent/archives")).resolve("${sha256(zip)}.zip"), zip)
        val cached = installer.ensureInstalled(artifact, requirePublicRelease = true)
        assertTrue(cached is RuntimeInstallResult.Ready, "a verified cache entry needs no download URL")
        assertEquals("same-agent", Files.readString((cached as RuntimeInstallResult.Ready).executable))

        val fresh = ManagedRuntimeInstaller(Files.createTempDirectory("study-latest-url"), client) { null }
        assertTrue(fresh.ensureInstalled(
            artifact.copy(downloadUrl = "https://github.com/AISE-TUDelft/code4me2-server/releases/download/latest/runtime.zip"),
            requirePublicRelease = true,
        ) is RuntimeInstallResult.Failed)
        assertEquals(0, downloads)
    }

    @Test
    fun `a corrupted cache entry is not trusted when the study has no public URL`() {
        val zip = archive("agent", "assigned")
        val root = Files.createTempDirectory("study-corrupted-cache")
        val artifact = RuntimeArtifact(
            "code4me-agent", "1.0.0", ManagedRuntimeInstaller.platformId(),
            ManagedRuntimeInstaller.architectureId(), "runtime.zip", sha256(zip), "agent", "1",
            size = zip.size.toLong(),
        )
        Files.write(Files.createDirectories(root.resolve("code4me-agent/archives")).resolve("${sha256(zip)}.zip"),
            archive("agent", "tampered"))
        var downloads = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            downloads++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(zip.toResponseBody()).build()
        }.build()

        val result = ManagedRuntimeInstaller(root, client) { null }.ensureInstalled(artifact, requirePublicRelease = true)

        assertTrue(result is RuntimeInstallResult.Unavailable)
        assertEquals(0, downloads)
        assertTrue(Files.notExists(root.resolve("code4me-agent/1.0.0-${sha256(zip).take(12)}")), "nothing may be installed")
    }

    @Test
    fun `only release assets of the canonical repository are downloaded`() {
        val zip = archive("agent", "canonical")
        var downloads = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            downloads++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(zip.toResponseBody()).build()
        }.build()
        val artifact = RuntimeArtifact(
            "code4me-agent", "1.0.0", ManagedRuntimeInstaller.platformId(),
            ManagedRuntimeInstaller.architectureId(), "runtime.zip", sha256(zip), "agent", "1",
            size = zip.size.toLong(),
        )
        fun install(url: String) = ManagedRuntimeInstaller(Files.createTempDirectory("study-repository"), client) { null }
            .ensureInstalled(artifact.copy(downloadUrl = url), requirePublicRelease = true)

        assertTrue(install("https://github.com/someone/code4me2-server/releases/download/runtime-v1.0.0/runtime.zip")
            is RuntimeInstallResult.Failed)
        assertEquals(0, downloads, "another repository's asset must be refused before any request")
        // GitHub treats owner and repository names case-insensitively.
        assertTrue(install("https://github.com/aise-tudelft/Code4Me2-Server/releases/download/runtime-v1.0.0/runtime.zip")
            is RuntimeInstallResult.Ready)
        assertEquals(1, downloads)
    }

    @Test
    fun `study rejects tampered release archive before installation`() {
        val expected = archive("agent", "assigned")
        val tampered = archive("agent", "different")
        val root = Files.createTempDirectory("study-tampered-release")
        val artifact = RuntimeArtifact(
            "code4me-agent", "1.0.0", ManagedRuntimeInstaller.platformId(),
            ManagedRuntimeInstaller.architectureId(), "runtime.zip", sha256(expected), "agent", "1",
            downloadUrl = "https://github.com/AISE-TUDelft/code4me2-server/releases/download/runtime-v1.0.0/runtime.zip",
            size = expected.size.toLong(),
        )
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(tampered.toResponseBody()).build()
        }.build()
        val result = ManagedRuntimeInstaller(root, client) { null }.ensureInstalled(
            artifact, requirePublicRelease = true,
        )

        assertTrue(result is RuntimeInstallResult.Failed)
        assertTrue((result as RuntimeInstallResult.Failed).message.contains("checksum") || result.message.contains("size"))
        Files.walk(root).use { paths ->
            assertEquals(0, paths.filter { Files.isRegularFile(it) }.count(), "no tampered archive or executable may remain")
        }
    }

    @Test
    fun `installs matching verified archive atomically`() {
        val root = Files.createTempDirectory("managed-runtime")
        val executable = if (ManagedRuntimeInstaller.platformId() == "windows") "agent.exe" else "agent"
        val zip = archive(executable, "runtime")
        val manifest = manifest(executable, sha256(zip))
        val installer = ManagedRuntimeInstaller(root) { path ->
            when (path) {
                ManagedRuntimeInstaller.MANIFEST_RESOURCE -> manifest.byteInputStream()
                "/runtime.zip" -> zip.inputStream()
                else -> null
            }
        }

        val result = installer.ensureInstalled()

        assertTrue(result is RuntimeInstallResult.Ready)
        assertEquals("runtime", Files.readString((result as RuntimeInstallResult.Ready).executable))
    }

    @Test
    fun `repair installs beside a potentially running runtime`() {
        val root = Files.createTempDirectory("managed-runtime-repair")
        val executable = if (ManagedRuntimeInstaller.platformId() == "windows") "agent.exe" else "agent"
        val zip = archive(executable, "runtime")
        val manifest = manifest(executable, sha256(zip))
        val installer = ManagedRuntimeInstaller(root) { path ->
            when (path) {
                ManagedRuntimeInstaller.MANIFEST_RESOURCE -> manifest.byteInputStream()
                "/runtime.zip" -> zip.inputStream()
                else -> null
            }
        }

        val first = installer.ensureInstalled() as RuntimeInstallResult.Ready
        val repaired = installer.ensureInstalled(repair = true) as RuntimeInstallResult.Ready

        assertTrue(Files.isRegularFile(first.executable))
        assertTrue(Files.isRegularFile(repaired.executable))
        assertTrue(first.executable != repaired.executable)
    }

    @Test
    fun `rejects checksum mismatch before extraction`() {
        val installer = ManagedRuntimeInstaller(Files.createTempDirectory("managed-runtime-bad")) { path ->
            when (path) {
                ManagedRuntimeInstaller.MANIFEST_RESOURCE -> manifest("agent", "0".repeat(64)).byteInputStream()
                "/runtime.zip" -> archive("agent", "runtime").inputStream()
                else -> null
            }
        }
        assertTrue(installer.ensureInstalled() is RuntimeInstallResult.Failed)
    }

    @Test
    fun `accepts parent-folder archive by flattening single root`() {
        val root = Files.createTempDirectory("managed-runtime-nested")
        val executable = if (ManagedRuntimeInstaller.platformId() == "windows") "agent.exe" else "agent"
        val zip = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zipStream ->
                zipStream.putNextEntry(ZipEntry("bundle/")); zipStream.closeEntry()
                zipStream.putNextEntry(ZipEntry("bundle/$executable"))
                zipStream.write("runtime".toByteArray()); zipStream.closeEntry()
            }
        }.toByteArray()
        val manifest = manifest(executable, sha256(zip))
        val installer = ManagedRuntimeInstaller(root) { path ->
            when (path) {
                ManagedRuntimeInstaller.MANIFEST_RESOURCE -> manifest.byteInputStream()
                "/runtime.zip" -> zip.inputStream()
                else -> null
            }
        }

        val result = installer.ensureInstalled()

        assertTrue(result is RuntimeInstallResult.Ready)
        assertEquals("runtime", Files.readString((result as RuntimeInstallResult.Ready).executable))
    }

    @Test
    fun `accepts mixed archive with executable nested beside flat entries`() {
        val root = Files.createTempDirectory("managed-runtime-mixed")
        val executable = if (ManagedRuntimeInstaller.platformId() == "windows") "agent.exe" else "agent"
        val zip = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zipStream ->
                zipStream.putNextEntry(ZipEntry("_internal/")); zipStream.closeEntry()
                zipStream.putNextEntry(ZipEntry("_internal/lib.txt"))
                zipStream.write("lib".toByteArray()); zipStream.closeEntry()
                zipStream.putNextEntry(ZipEntry("bundle/")); zipStream.closeEntry()
                zipStream.putNextEntry(ZipEntry("bundle/$executable"))
                zipStream.write("runtime".toByteArray()); zipStream.closeEntry()
            }
        }.toByteArray()
        val manifest = manifest(executable, sha256(zip))
        val installer = ManagedRuntimeInstaller(root) { path ->
            when (path) {
                ManagedRuntimeInstaller.MANIFEST_RESOURCE -> manifest.byteInputStream()
                "/runtime.zip" -> zip.inputStream()
                else -> null
            }
        }

        val result = installer.ensureInstalled()

        assertTrue(result is RuntimeInstallResult.Ready)
        val ready = result as RuntimeInstallResult.Ready
        assertEquals("runtime", Files.readString(ready.executable))
        assertTrue(Files.isRegularFile(ready.executable.parent.resolve("_internal/lib.txt")))
    }

    @Test
    fun `missing executable reports archive contents`() {
        val zip = archive("other", "runtime")
        val manifest = manifest("agent", sha256(zip))
        val installer = ManagedRuntimeInstaller(Files.createTempDirectory("managed-runtime-empty")) { path ->
            when (path) {
                ManagedRuntimeInstaller.MANIFEST_RESOURCE -> manifest.byteInputStream()
                "/runtime.zip" -> zip.inputStream()
                else -> null
            }
        }

        val result = installer.ensureInstalled()

        assertTrue(result is RuntimeInstallResult.Failed)
        assertTrue((result as RuntimeInstallResult.Failed).message.contains("other"))
    }

    @Test
    fun `development manifest reports missing archive clearly`() {
        val installer = ManagedRuntimeInstaller(Files.createTempDirectory("managed-runtime-missing")) { path ->
            if (path == ManagedRuntimeInstaller.MANIFEST_RESOURCE) manifest("agent", "0".repeat(64)).byteInputStream() else null
        }
        assertTrue(installer.ensureInstalled() is RuntimeInstallResult.Unavailable)
    }

    @Test
    fun `rejects artifact for another managed protocol`() {
        val executable = if (ManagedRuntimeInstaller.platformId() == "windows") "agent.exe" else "agent"
        val zip = archive(executable, "runtime")
        val incompatible = manifest(executable, sha256(zip)).replace(
            "\"managed_protocol\":\"1\"",
            "\"managed_protocol\":\"2\"",
        )
        val installer = ManagedRuntimeInstaller(Files.createTempDirectory("managed-runtime-protocol")) { path ->
            when (path) {
                ManagedRuntimeInstaller.MANIFEST_RESOURCE -> incompatible.byteInputStream()
                "/runtime.zip" -> zip.inputStream()
                else -> null
            }
        }

        assertTrue(installer.ensureInstalled() is RuntimeInstallResult.Failed)
    }

    /**
     * The bundled manifest and archive must agree, or `ensureInstalled` fails
     * checksum verification in the IDE. A clean checkout has no staged archive,
     * so the check is skipped there; a build that stages one must keep them in
     * sync.
     */
    @Test
    fun `the bundled manifest matches the bundled archive when a runtime is staged`() {
        val installerClass = ManagedRuntimeInstaller::class.java
        val manifestStream = installerClass.getResourceAsStream(ManagedRuntimeInstaller.MANIFEST_RESOURCE)
        Assumptions.assumeTrue(manifestStream != null, "no bundled runtime manifest in this build")
        val manifest = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(manifestStream!!.use { it.readBytes().decodeToString() })
            .jsonObject
        val artifact = manifest.getValue("artifacts").jsonArray
            .map { it.jsonObject }
            .firstOrNull {
                it["platform"]?.jsonPrimitive?.content == ManagedRuntimeInstaller.platformId() &&
                    it["architecture"]?.jsonPrimitive?.content == ManagedRuntimeInstaller.architectureId()
            }
        Assumptions.assumeTrue(artifact != null, "no artifact for this host platform")
        val archivePath = "/${artifact!!.getValue("archive").jsonPrimitive.content}"
        val archive = installerClass.getResourceAsStream(archivePath)
        Assumptions.assumeTrue(archive != null, "runtime archive not staged in this build")

        val actual = sha256(archive!!.use { it.readBytes() })

        assertEquals(
            artifact.getValue("sha256").jsonPrimitive.content,
            actual,
            "the bundled runtime archive does not match the bundled manifest ($archivePath)",
        )
    }

    private fun manifest(executable: String, checksum: String): String = """
        {"manifest_version":1,"managed_protocol_version":"1",
        "artifacts":[{"runtime_id":"code4me-agent","version":"test","platform":"${ManagedRuntimeInstaller.platformId()}",
        "architecture":"${ManagedRuntimeInstaller.architectureId()}","archive":"runtime.zip","sha256":"$checksum",
        "executable":"$executable","managed_protocol":"1"}]}
    """.trimIndent()

    private fun archive(name: String, content: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
        }
    }.toByteArray()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
