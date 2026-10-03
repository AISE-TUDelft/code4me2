package me.code4me.research.proxy

import me.code4me.services.agent.ManagedRuntimeInstaller
import me.code4me.services.agent.RuntimeArtifact
import me.code4me.research.bootstrap.AgentReleaseRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

/**
 * The production research-agent seam: the assigned release metadata and archive
 * digest select a public release download or verified cache.
 *
 * The [ManagedRuntimeInstaller] is injected with a temporary install root and an
 * in-memory recipe/archive, so nothing here touches an IDE system path, the real
 * plugin resources, or the developer's runtime cache.
 */
class PackagedAgentInstallerTest {
    // The installer selects the artifact for the running host, so the fixtures
    // declare that platform (and a different one for the "no artifact" case).
    private val hostPlatform = ManagedRuntimeInstaller.platformId()
    private val hostArchitecture = ManagedRuntimeInstaller.architectureId()
    private val otherPlatform = if (hostPlatform == "linux") "macos" else "linux"
    private val otherArchitecture = if (hostArchitecture == "x64") "arm64" else "x64"
    private val agentArchivePath = "code4me-runtime/code4me-agent-$hostPlatform-$hostArchitecture.zip"

    @Test
    fun `a matching assigned release downloads the agent and returns its managed argv and digest`() {
        val installRoot = Files.createTempDirectory("packaged-agent-match")
        val zip = agentArchive()
        val artifact = artifact(zip = zip, platform = hostPlatform, architecture = hostArchitecture)
        val seam = seam(installRoot, artifact, zip)

        val result = seam.prepare(release(zip), { _, _ -> }, { true })

        assertTrue(result is PackagedAgentInstall.Ready, (result as? PackagedAgentInstall.Blocked)?.detail)
        val ready = result as PackagedAgentInstall.Ready
        val pin12 = sha256(zip).take(12)
        val expectedExecutable = installRoot.resolve("code4me-agent/9.9.9-$pin12/code4me2-agent")
        assertEquals(
            listOf(expectedExecutable.toString(), "--managed"),
            ready.argv,
            "the installed agent must run in managed mode from the content-addressed install root",
        )
        assertEquals(sha256("agent-binary".toByteArray()), ready.digest)
        assertTrue(Files.isRegularFile(expectedExecutable), "the executable must be installed")
        assertTrue(Files.isExecutable(expectedExecutable), "the installed executable must be executable")
        assertEquals(
            "agent-binary",
            Files.readString(expectedExecutable),
        )
        assertTrue(
            Files.isRegularFile(expectedExecutable.parent.resolve("_internal/data.bin")),
            "the archive's dependency tree must be extracted beside the executable",
        )
    }

    @Test
    fun `a pin that differs from the assigned artifact digest blocks before writing anything`() {
        val installRoot = Files.createTempDirectory("packaged-agent-mismatch")
        val zip = agentArchive()
        val artifact = artifact(zip = zip, platform = hostPlatform, architecture = hostArchitecture)
        val seam = seam(installRoot, artifact, zip)

        val result = seam.prepare(release(zip).copy(artifactDigest = "a".repeat(64)), { _, _ -> }, { true })

        assertTrue(result is PackagedAgentInstall.Blocked)
        assertTrue(
            (result as PackagedAgentInstall.Blocked).detail.contains("does not match the study's checksum"),
            "the block detail must explain the pin mismatch: ${result.detail}",
        )
        assertFalse(
            Files.exists(installRoot.resolve("code4me-agent")),
            "a mismatched pin must not write a single byte to disk",
        )
    }

    @Test
    fun `a malformed pin blocks without installing`() {
        val installRoot = Files.createTempDirectory("packaged-agent-malformed")
        val zip = agentArchive()
        val artifact = artifact(zip = zip, platform = hostPlatform, architecture = hostArchitecture)
        val seam = seam(installRoot, artifact, zip)

        val result = seam.prepare(release(zip).copy(artifactDigest = "nope"), { _, _ -> }, { true })

        assertTrue(result is PackagedAgentInstall.Blocked)
        assertTrue(
            (result as PackagedAgentInstall.Blocked).detail.contains("checksum is invalid"),
            "the block detail must name the malformed pin: ${result.detail}",
        )
        assertFalse(Files.exists(installRoot.resolve("code4me-agent")), "a malformed pin must write nothing")
    }

    @Test
    fun `a recipe with no host-platform artifact blocks and names the host platform`() {
        val installRoot = Files.createTempDirectory("packaged-agent-platform")
        val zip = agentArchive()
        val artifact = artifact(zip = zip, platform = otherPlatform, architecture = otherArchitecture)
        val seam = seam(installRoot, artifact, zip)

        val result = seam.prepare(release(zip, otherPlatform, otherArchitecture), { _, _ -> }, { true })

        assertTrue(result is PackagedAgentInstall.Blocked)
        val detail = (result as PackagedAgentInstall.Blocked).detail
        assertTrue(
            detail.contains(
                "${ManagedRuntimeInstaller.platformId()}/${ManagedRuntimeInstaller.architectureId()}",
            ),
            "the block detail must name the host platform: $detail",
        )
        assertFalse(Files.exists(installRoot.resolve("code4me-agent")), "an unsupported platform must write nothing")
    }

    @Test
    fun `pin only legacy path cannot install a bundled study agent`() {
        val installRoot = Files.createTempDirectory("packaged-agent-legacy")
        val zip = agentArchive()
        val seam = seam(installRoot, artifact(zip, hostPlatform, hostArchitecture), zip)

        val result = seam.install(sha256(zip))

        assertTrue(result is PackagedAgentInstall.Blocked)
        assertTrue((result as PackagedAgentInstall.Blocked).detail.contains("public release metadata"))
        assertFalse(Files.exists(installRoot.resolve("code4me-agent")))
    }

    @Test
    fun `a matching bundled archive is never used for a study cache miss`() {
        val installRoot = Files.createTempDirectory("packaged-agent-bundle-ignored")
        val zip = agentArchive()
        val artifact = artifact(zip = zip, platform = hostPlatform, architecture = hostArchitecture)
        val release = release(zip)
        val seam = seam(installRoot, artifact, zip)

        val result = seam.prepare(release.copy(artifact = release.artifact!!.copy(downloadUrl = null)), { _, _ -> }, { true })

        assertTrue(result is PackagedAgentInstall.Blocked)
        assertTrue((result as PackagedAgentInstall.Blocked).detail.contains("no public GitHub Release URL"), result.detail)
        assertFalse(Files.exists(installRoot.resolve("code4me-agent/9.9.9-${sha256(zip).take(12)}")))
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun seam(
        installRoot: Path,
        artifactJson: String,
        zip: ByteArray,
        manifest: String = recipe(artifactJson),
    ): ProductionPackagedAgentInstaller {
        val installer =
            ManagedRuntimeInstaller(
                installRoot = installRoot,
                downloadClient = OkHttpClient.Builder().addInterceptor { chain ->
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(zip.toResponseBody()).build()
                }.build(),
                resourceLoader = { path ->
                    when (path) {
                        ManagedRuntimeInstaller.MANIFEST_RESOURCE -> manifest.byteInputStream()
                        "/$agentArchivePath" -> zip.inputStream()
                        else -> null
                    }
                },
            )
        return ProductionPackagedAgentInstaller { installer }
    }

    private fun release(zip: ByteArray, platform: String = hostPlatform, architecture: String = hostArchitecture): AgentReleaseRef {
        val artifact = RuntimeArtifact(
            "code4me-agent", "9.9.9", platform, architecture, agentArchivePath,
            sha256(zip), "code4me2-agent", "1",
            downloadUrl = "https://github.com/AISE-TUDelft/code4me2-server/releases/download/runtime-v9.9.9/${Path.of(agentArchivePath).fileName}",
            size = zip.size.toLong(),
        )
        return AgentReleaseRef("code4me-agent", "runtime-v9.9.9", "9.9.9", sha256(zip), artifact = artifact)
    }

    /** One recipe artifact declaring [zip]'s exact bytes. */
    private fun artifact(
        zip: ByteArray,
        platform: String,
        architecture: String,
    ): String =
        """{"runtime_id":"code4me-agent","version":"9.9.9","platform":"$platform",""" +
            """"architecture":"$architecture","archive":"$agentArchivePath","sha256":"${sha256(zip)}",""" +
            """"executable":"code4me2-agent","managed_protocol":"1","size":${zip.size}}"""

    private fun recipe(artifactJson: String): String =
        """{"manifest_version":1,"runtime_version":"9.9.9","managed_protocol_version":"1",""" +
            """"artifacts":[$artifactJson]}"""

    /** A real in-memory onedir-shaped ZIP: executable plus its dependency tree. */
    private fun agentArchive(): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("code4me2-agent"))
                zip.write("agent-binary".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("_internal/data.bin"))
                zip.write("dependency-payload".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
