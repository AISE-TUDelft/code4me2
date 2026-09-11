package me.code4me.services.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

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
}
