package me.code4me.services.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AcpRegistryWriterTest {
    @Test
    fun `concurrent project registrations preserve every proxy entry`() {
        val directory = Files.createTempDirectory("acp-registry-concurrent")
        val registry = directory.resolve("acp.json")
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val writes =
                (1..8).map { index ->
                    workers.submit<Result<Unit>> {
                        start.await()
                        AcpRegistryWriter(registry).registerProxyEntry(
                            name = "Code4Me Research Proxy $index",
                            command = "/runtime/proxy",
                            args = emptyList(),
                            env = emptyMap(),
                        )
                    }
                }
            start.countDown()
            writes.forEach { assertTrue(it.get(10, TimeUnit.SECONDS).isSuccess) }

            val servers = Json.parseToJsonElement(Files.readString(registry)).jsonObject.getValue("agent_servers").jsonObject
            assertEquals(8, servers.size)
        } finally {
            workers.shutdownNow()
        }
    }

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

    @Test
    fun `developer entries keep the research entry and leave the registry owner only`() {
        val registry = Files.createTempDirectory("acp-registry-developer").resolve("acp.json")
        val research = mapOf("CODE4ME_RESEARCH_CAPABILITY" to "one-time-secret")
        assertTrue(
            AcpRegistryWriter(registry)
                .registerProxyEntry("Code4Me Research Proxy", "/runtime/proxy", emptyList(), research)
                .isSuccess,
        )
        Files.setPosixFilePermissions(registry, PosixFilePermission.values().toSet())

        AcpManager.writeDeveloperEntries(
            registry,
            "/usr/local/bin/goose",
            mapOf("GOOSE_PROVIDER" to "openai"),
            "http://127.0.0.1:8765/",
            "/src/codex-acp",
        )

        val servers = servers(registry)
        assertEquals(setOf("Code4Me Research Proxy", AcpManager.GOOSE_ENTRY_NAME, AcpManager.CODEX_ENTRY_NAME), servers.keys)
        val codex = servers.getValue(AcpManager.CODEX_ENTRY_NAME).jsonObject
        // npm must not print its script banner onto the ACP stdout.
        assertEquals(
            listOf("npm", "run", "--silent", "start", "--prefix", "/src/codex-acp"),
            codex.getValue("args").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("http://127.0.0.1:8765/v1", codex.getValue("env").jsonObject.getValue("CODEX_PROXY_URL").jsonPrimitive.content)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(registry))
    }

    @Test
    fun `developer writes never replace a registry they cannot parse`() {
        val registry = Files.createTempDirectory("acp-registry-developer-invalid").resolve("acp.json")
        Files.writeString(registry, "{not json")

        writeDeveloperEntries(registry)
        AcpManager.removeDeveloperEntry(registry, AcpManager.GOOSE_ENTRY_NAME)

        assertEquals("{not json", Files.readString(registry))
    }

    @Test
    fun `removing a developer entry keeps every other entry`() {
        val registry = Files.createTempDirectory("acp-registry-developer-remove").resolve("acp.json")
        assertTrue(AcpRegistryWriter(registry).registerProxyEntry("Third Party", "/bin/agent", emptyList(), emptyMap()).isSuccess)
        writeDeveloperEntries(registry)

        AcpManager.removeDeveloperEntry(registry, AcpManager.GOOSE_ENTRY_NAME)

        assertEquals(setOf("Third Party", AcpManager.CODEX_ENTRY_NAME), servers(registry).keys)
    }

    @Test
    fun `developer and research writers running together lose no entry`() {
        val registry = Files.createTempDirectory("acp-registry-mixed").resolve("acp.json")
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val research =
                (1..6).map { index ->
                    workers.submit<Result<Unit>> {
                        start.await()
                        AcpRegistryWriter(registry).registerProxyEntry(
                            "Code4Me Research Proxy $index",
                            "/runtime/proxy",
                            emptyList(),
                            emptyMap(),
                        )
                    }
                }
            val developer =
                (1..6).map {
                    workers.submit {
                        start.await()
                        writeDeveloperEntries(registry)
                    }
                }
            start.countDown()
            research.forEach { assertTrue(it.get(10, TimeUnit.SECONDS).isSuccess) }
            developer.forEach { it.get(10, TimeUnit.SECONDS) }

            val names = servers(registry).keys
            assertTrue((1..6).all { "Code4Me Research Proxy $it" in names })
            assertTrue(AcpManager.GOOSE_ENTRY_NAME in names && AcpManager.CODEX_ENTRY_NAME in names)
        } finally {
            workers.shutdownNow()
        }
    }

    private fun writeDeveloperEntries(registry: java.nio.file.Path) =
        AcpManager.writeDeveloperEntries(registry, "/usr/local/bin/goose", emptyMap(), "http://127.0.0.1:8765", "/src/codex-acp")

    private fun servers(registry: java.nio.file.Path) =
        Json.parseToJsonElement(Files.readString(registry)).jsonObject.getValue("agent_servers").jsonObject
}
