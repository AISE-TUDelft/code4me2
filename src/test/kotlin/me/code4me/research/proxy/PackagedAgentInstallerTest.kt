package me.code4me.research.proxy

import me.code4me.services.agent.ManagedRuntimeInstaller
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The production packaged-agent seam: the single shipped recipe
 * (`code4me-runtime/manifest.json`) and its archive, matched against the
 * bootstrap pin before any byte is written.
 *
 * The [ManagedRuntimeInstaller] is injected with a temporary install root and an
 * in-memory recipe/archive, so nothing here touches an IDE system path, the real
 * plugin resources, or the developer's runtime cache.
 */
class PackagedAgentInstallerTest {
    private val agentArchivePath = "code4me-runtime/code4me-agent-macos-arm64.zip"

    @Test
    fun `a matching pin installs the agent and returns its managed argv and digest`() {
        val installRoot = Files.createTempDirectory("packaged-agent-match")
        val zip = agentArchive()
        val artifact = artifact(zip = zip, platform = "macos", architecture = "arm64")
        val seam = seam(installRoot, artifact, zip)

        val result = seam.install(sha256(zip))

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
    fun `a pin that differs from the recipe digest blocks before writing anything`() {
        val installRoot = Files.createTempDirectory("packaged-agent-mismatch")
        val zip = agentArchive()
        val artifact = artifact(zip = zip, platform = "macos", architecture = "arm64")
        val seam = seam(installRoot, artifact, zip)

        val result = seam.install("a".repeat(64))

        assertTrue(result is PackagedAgentInstall.Blocked)
        assertTrue(
            (result as PackagedAgentInstall.Blocked).detail.contains("does not match the pinned release archive"),
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
        val artifact = artifact(zip = zip, platform = "macos", architecture = "arm64")
        val seam = seam(installRoot, artifact, zip)

        val result = seam.install("nope")

        assertTrue(result is PackagedAgentInstall.Blocked)
        assertTrue(
            (result as PackagedAgentInstall.Blocked).detail.contains("not a 64-hex sha256"),
            "the block detail must name the malformed pin: ${result.detail}",
        )
        assertFalse(Files.exists(installRoot.resolve("code4me-agent")), "a malformed pin must write nothing")
    }

    @Test
    fun `a recipe with no host-platform artifact blocks and names the host platform`() {
        val installRoot = Files.createTempDirectory("packaged-agent-platform")
        val zip = agentArchive()
        val artifact = artifact(zip = zip, platform = "linux", architecture = "x64")
        val seam = seam(installRoot, artifact, zip)

        val result = seam.install(sha256(zip))

        assertTrue(result is PackagedAgentInstall.Blocked)
        val detail = (result as PackagedAgentInstall.Blocked).detail
        assertTrue(
            detail.contains(
                "${ManagedRuntimeInstaller.platformId()}-${ManagedRuntimeInstaller.architectureId()}",
            ),
            "the block detail must name the host platform: $detail",
        )
        assertFalse(Files.exists(installRoot.resolve("code4me-agent")), "an unsupported platform must write nothing")
    }

    @Test
    fun `the recipe adapter digest is surfaced and never invented`() {
        val installRoot = Files.createTempDirectory("packaged-agent-adapter")
        val zip = agentArchive()
        val adapter = "cd".repeat(32)
        val seamWithAdapter =
            seam(installRoot, artifact(zip = zip, platform = "macos", architecture = "arm64", adapter = adapter), zip)

        assertEquals(adapter, seamWithAdapter.recipeAdapterDigest())

        val withoutAdapter =
            seam(
                Files.createTempDirectory("packaged-agent-no-adapter"),
                artifact(zip = zip, platform = "macos", architecture = "arm64"),
                zip,
            )

        assertNull(withoutAdapter.recipeAdapterDigest(), "an adapter-less recipe must not invent an identity")
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

    /** One recipe artifact declaring [zip]'s exact bytes. */
    private fun artifact(
        zip: ByteArray,
        platform: String,
        architecture: String,
        adapter: String? = null,
    ): String {
        val adapterField = adapter?.let { ""","adapter":{"digest":"$it"}""" }.orEmpty()
        return """{"runtime_id":"code4me-agent","version":"9.9.9","platform":"$platform",""" +
            """"architecture":"$architecture","archive":"$agentArchivePath","sha256":"${sha256(zip)}",""" +
            """"executable":"code4me2-agent","managed_protocol":"1","size":${zip.size}$adapterField}"""
    }

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
