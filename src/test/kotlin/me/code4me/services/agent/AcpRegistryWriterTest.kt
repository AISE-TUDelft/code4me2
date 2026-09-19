package me.code4me.services.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class AcpRegistryWriterTest {
    @Test
    fun `managed registration preserves third party entries and backs up local dev`() {
        val directory = Files.createTempDirectory("acp-registry")
        val registry = directory.resolve("acp.json")
        Files.writeString(registry, """{"custom":"kept","agent_servers":{"Other":{"command":"other"},"Code4Me Agent":{"command":"/repo/local-dev/.venv/bin/python"}}}""")

        val result = AcpRegistryWriter(registry).registerManagedAgent("/runtime/code4me-agent", "/bridges")

        assertTrue(result.isSuccess)
        val root = Json.parseToJsonElement(Files.readString(registry)).jsonObject
        assertEquals("kept", root["custom"]?.toString()?.trim('"'))
        val servers = root.getValue("agent_servers").jsonObject
        assertTrue(servers.containsKey("Other"))
        assertTrue(servers.containsKey("Code4Me Agent (local-dev backup)"))
        assertTrue(servers.getValue("Code4Me Agent").toString().contains("--managed"))
    }

    @Test
    fun `invalid registry remains untouched`() {
        val directory = Files.createTempDirectory("acp-registry-invalid")
        val registry = directory.resolve("acp.json")
        Files.writeString(registry, "not json")

        val result = AcpRegistryWriter(registry).registerManagedAgent("agent", "bridges")

        assertTrue(result.isFailure)
        assertEquals("not json", Files.readString(registry))
        assertFalse(Files.exists(directory.resolve("acp.json.tmp")))
    }

    @Test
    fun `a proxy registration leaves the registry owner only`() {
        val directory = Files.createTempDirectory("acp-registry-perms")
        val registry = directory.resolve("acp.json")
        val writer = AcpRegistryWriter(registry)
        val env = mapOf("CODE4ME_RESEARCH_CAPABILITY" to "one-time-secret")

        assertTrue(
            writer.registerProxyEntry(
                name = "Code4Me Research Proxy",
                command = "/runtime/proxy",
                args = listOf("--agent-digest", "a".repeat(64), "--agent-cmd", "agent"),
                env = env,
            ).isSuccess,
        )

        // A registry written by an older plugin (or a copy) may be loose while
        // still carrying the capability in the entry env; an idempotent refresh
        // must tighten it even though the entry itself is already correct.
        Files.setPosixFilePermissions(registry, PosixFilePermission.values().toSet())
        assertTrue(
            writer.registerProxyEntry(
                name = "Code4Me Research Proxy",
                command = "/runtime/proxy",
                args = listOf("--agent-digest", "a".repeat(64), "--agent-cmd", "agent"),
                env = env,
            ).isSuccess,
        )

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(registry),
        )
    }

    @Test
    fun `an idempotent managed registration also leaves the registry owner only`() {
        val directory = Files.createTempDirectory("acp-registry-managed-perms")
        val registry = directory.resolve("acp.json")
        val writer = AcpRegistryWriter(registry)

        assertTrue(writer.registerManagedAgent("/runtime/agent", "/bridges").isSuccess)
        // A registry copied from an older install may be loose; the no-op
        // refresh must restore the owner-only invariant.
        Files.setPosixFilePermissions(registry, PosixFilePermission.values().toSet())
        assertTrue(writer.registerManagedAgent("/runtime/agent", "/bridges").isSuccess)

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(registry),
        )
    }
}
