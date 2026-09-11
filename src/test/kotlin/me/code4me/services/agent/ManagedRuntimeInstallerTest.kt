package me.code4me.services.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ManagedRuntimeInstallerTest {
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
