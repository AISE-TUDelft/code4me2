package me.code4me.research.proxy

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.code4me.research.runtime.ContentHasher
import me.code4me.research.runtime.ProcessOutcome
import me.code4me.research.telemetry.parseCanonicalJsonObject
import me.code4me.research.telemetry.sha256Hex
import me.code4me.services.agent.AcpRegistryWriter
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

// --------------------------------------------------------------------------
// AcpHostRegistrationTest.kt
// --------------------------------------------------------------------------

class AcpHostRegistrationTest {
    private lateinit var directory: Path
    private lateinit var registry: Path

    @BeforeEach
    fun setUp() {
        directory = Files.createTempDirectory("acp-host-registration")
        registry = directory.resolve("acp.json")
    }

    @Test
    fun `register preserves unrelated goose and codex entries and includes the pinned agent`() {
        writeExistingRegistry()
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        val agentExecutable = directory.resolve("agent")
        Files.writeString(agentExecutable, "agent-binary")
        val capabilityFile = directory.resolve("capability.txt")
        val agentDigest = ContentHasher.STREAMING.sha256(agentExecutable)
        val spoolEndpoint = "file:///var/run/code4me/spool.jsonl"

        val result =
            registration().register(
                resolved = runtime(proxyExecutable),
                agentArgv = listOf(agentExecutable.toString()),
                spoolEndpoint = spoolEndpoint,
                capabilityFile = capabilityFile,
                env = mapOf("CODE4ME_RESEARCH_PROXY" to "digest"),
            )

        assertTrue(result.isSuccess)
        val servers = servers()
        assertEquals("\"kept\"", rootValue("custom"))
        assertTrue(servers.getValue("Goose (Code4Me)").toString().contains("goose"))
        assertTrue(servers.getValue("Codex (Code4Me)").toString().contains("npx"))
        assertTrue(servers.containsKey("Other"))

        val entry = servers.getValue(AcpHostRegistration.DEFAULT_ENTRY_NAME).jsonObject
        assertEquals(proxyExecutable.toString(), entry.getValue("command").jsonPrimitive.content)
        val args = entry.getValue("args").jsonArray.map { it.jsonPrimitive.content }
        assertEquals("--stdio", args.first())
        assertTrue(args.containsAll(listOf("--agent-cmd", agentExecutable.toString())))
        assertTrue(args.containsAll(listOf("--agent-digest", agentDigest)))
        assertTrue(args.containsAll(listOf("--spool-endpoint", spoolEndpoint)))
        val expectedCapabilityPath = capabilityFile.toAbsolutePath().normalize().toString()
        assertTrue(args.containsAll(listOf("--capability-file", expectedCapabilityPath)))
        // `--agent-cmd` is an argparse REMAINDER: it must be the LAST option so
        // every following token is the agent argv.
        val agentCmdIndex = args.indexOf("--agent-cmd")
        assertEquals(args.size - 2, agentCmdIndex, "the agent argv must contain exactly one token here")
        assertEquals(agentExecutable.toString(), args.last(), "the agent executable must be the final argument")
        assertTrue(args.indexOf("--agent-digest") in 0 until agentCmdIndex)
        assertTrue(args.indexOf("--spool-endpoint") in 0 until agentCmdIndex)
        assertTrue(args.indexOf("--capability-file") in 0 until agentCmdIndex)
        assertTrue(
            AcpHostRegistration.COMPAT_IDEMPOTENT_INITIALIZE_FLAG in args,
            "the bundled proxy compatibility mode must be enabled in the entry",
        )
        assertTrue(
            args.indexOf(AcpHostRegistration.COMPAT_IDEMPOTENT_INITIALIZE_FLAG) in 0 until agentCmdIndex,
            "the compatibility flag must precede the --agent-cmd REMAINDER",
        )

        assertTrue(Files.exists(capabilityFile))
        assertTrue(Files.readString(capabilityFile).isNotBlank())
        assertOwnerOnly(capabilityFile)
        assertNoTempFiles()
    }

    @Test
    fun `register carries the one-time capability in the entry env`() {
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        val agentExecutable = directory.resolve("agent")
        Files.writeString(agentExecutable, "agent-binary")
        val capabilityFile = directory.resolve("capability.txt")

        val result =
            registration().register(
                resolved = runtime(proxyExecutable),
                agentArgv = listOf(agentExecutable.toString()),
                spoolEndpoint = "file:///spool",
                capabilityFile = capabilityFile,
            )

        assertTrue(result.isSuccess)
        val env =
            servers()
                .getValue(AcpHostRegistration.DEFAULT_ENTRY_NAME)
                .jsonObject
                .getValue("env")
                .jsonObject
        val capability = env.getValue(AcpHostRegistration.CAPABILITY_ENV_VAR).jsonPrimitive.content
        assertTrue(capability.isNotBlank(), "the entry env must carry the capability")
        // The env token is authoritative; the fallback file holds the same value.
        assertEquals(capability, Files.readString(capabilityFile).trim())
    }

    @Test
    fun `re-register with the same capability is idempotent and leaves no temp file`() {
        writeExistingRegistry()
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        val agentExecutable = directory.resolve("agent")
        Files.writeString(agentExecutable, "agent-binary")
        val capabilityFile = directory.resolve("capability.txt")

        // The entry now carries the capability in its env, so idempotency
        // requires the same capability the live IPC server authenticates with
        // (production always supplies it). A freshly minted token is genuinely a
        // different entry.
        val first =
            registration().register(
                runtime(proxyExecutable),
                listOf(agentExecutable.toString()),
                "file:///spool",
                capabilityFile,
                emptyMap(),
                capabilityValue = "stable-capability",
            )
        assertTrue(first.isSuccess)
        val firstContent = Files.readString(registry)

        val second =
            registration().register(
                runtime(proxyExecutable),
                listOf(agentExecutable.toString()),
                "file:///spool",
                capabilityFile,
                emptyMap(),
                capabilityValue = "stable-capability",
            )
        assertTrue(second.isSuccess)
        assertEquals(firstContent, Files.readString(registry))
        assertNoTempFiles()
    }

    @Test
    fun `unregister removes only our entry and keeps unrelated entries`() {
        writeExistingRegistry()
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        val agentExecutable = directory.resolve("agent")
        Files.writeString(agentExecutable, "agent-binary")
        val registration = registration()
        val registered =
            registration.register(
                runtime(proxyExecutable),
                listOf(agentExecutable.toString()),
                "file:///spool",
                directory.resolve("cap.txt"),
                emptyMap(),
            )
        assertTrue(registered.isSuccess)
        assertTrue(registration.hasEntry())

        assertTrue(registration.unregister().isSuccess)

        assertFalse(registration.hasEntry())
        val servers = servers()
        assertFalse(servers.containsKey(AcpHostRegistration.DEFAULT_ENTRY_NAME))
        assertTrue(servers.containsKey("Goose (Code4Me)"))
        assertTrue(servers.containsKey("Codex (Code4Me)"))
        assertTrue(servers.containsKey("Other"))
    }

    @Test
    fun `closing one context removes only its own entry, not another window's`() {
        writeExistingRegistry()
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")

        val contextA = AcpHostRegistration.contextEntryName("ctx-aaaaaaaaaaaaaaaa")
        val contextB = AcpHostRegistration.contextEntryName("ctx-bbbbbbbbbbbbbbbb")
        assertTrue(contextA != contextB)

        val agentExecutable = directory.resolve("agent")
        Files.writeString(agentExecutable, "agent-binary")
        val windowA = registration(contextA)
        val windowB = registration(contextB)
        assertTrue(
            windowA.register(
                runtime(proxyExecutable), listOf(agentExecutable.toString()), "file:///spool", directory.resolve("a.txt"),
            ).isSuccess,
        )
        assertTrue(
            windowB.register(
                runtime(proxyExecutable), listOf(agentExecutable.toString()), "file:///spool", directory.resolve("b.txt"),
            ).isSuccess,
        )
        assertTrue(servers().containsKey(contextA))
        assertTrue(servers().containsKey(contextB))

        // Closing window A removes A's entry and leaves B's intact.
        assertTrue(windowA.unregister().isSuccess)
        val servers = servers()
        assertFalse(servers.containsKey(contextA))
        assertTrue(servers.containsKey(contextB))
        assertTrue(servers.containsKey("Goose (Code4Me)"))
    }

    @Test
    fun `the ACP entry carries the resolved agent argv and digest with --agent-cmd last`() {
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        val agentExecutable = directory.resolve("runtime/agents/macos-aarch64/code4me-agent")
        Files.createDirectories(agentExecutable.parent)
        Files.writeString(agentExecutable, "agent-binary")
        val agentDigest = "ef".repeat(32)
        val resolved =
            ResolvedProxyRuntime(
                runtimeRoot = directory.resolve("runtime"),
                proxyArgv = listOf(proxyExecutable.toString(), "--stdio"),
                proxyDigest = "ab".repeat(32),
            )
        val capabilityFile = directory.resolve("capability-argv.txt")

        val result =
            registration().register(
                resolved = resolved,
                agentArgv = listOf(agentExecutable.toString(), "acp"),
                spoolEndpoint = "file:///spool",
                capabilityFile = capabilityFile,
                agentDigest = agentDigest,
            )

        assertTrue(result.isSuccess)
        val args =
            servers()
                .getValue(AcpHostRegistration.DEFAULT_ENTRY_NAME)
                .jsonObject
                .getValue("args")
                .jsonArray
                .map { it.jsonPrimitive.content }
        val agentCmdIndex = args.indexOf("--agent-cmd")
        assertEquals(AcpHostRegistration.AGENT_CMD_FLAG, args[args.size - 3], "--agent-cmd is a REMAINDER and must be last")
        assertEquals(agentExecutable.toString(), args[args.size - 2])
        assertEquals("acp", args.last())
        assertTrue(args.containsAll(listOf(AcpHostRegistration.AGENT_DIGEST_FLAG, agentDigest)))
        assertTrue(args.indexOf(AcpHostRegistration.AGENT_DIGEST_FLAG) in 0 until agentCmdIndex)
        assertTrue(args.indexOf(AcpHostRegistration.SPOOL_ENDPOINT_FLAG) in 0 until agentCmdIndex)
        assertTrue(args.indexOf(AcpHostRegistration.CAPABILITY_FILE_FLAG) in 0 until agentCmdIndex)
        assertTrue(
            args.indexOf(AcpHostRegistration.COMPAT_IDEMPOTENT_INITIALIZE_FLAG) in 0 until agentCmdIndex,
            "the compatibility flag must precede the --agent-cmd REMAINDER",
        )
    }

    @Test
    fun `a spool endpoint without a capability file fails and writes nothing`() {
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")

        val result =
            registration().register(
                runtime(proxyExecutable),
                listOf(proxyExecutable.toString()),
                "file:///spool",
                null,
                emptyMap(),
            )

        assertTrue(result.isFailure)
        assertFalse(Files.exists(registry))
    }

    @Test
    fun `hasEntry is false for a missing registry`() {
        assertFalse(registration().hasEntry())
    }

    @Test
    fun `register emits the adapter and frozen policy contract before the agent command`() {
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        val agentExecutable = directory.resolve("agent")
        Files.writeString(agentExecutable, "agent-binary")
        val capabilityFile = directory.resolve("capability.txt")
        val policyFile = directory.resolve("telemetry-policy.json")
        Files.writeString(policyFile, "{\"policy_digest\":\"abc\"}")

        val result =
            registration().register(
                resolved = runtime(proxyExecutable),
                agentArgv = listOf(agentExecutable.toString()),
                spoolEndpoint = "file:///spool",
                capabilityFile = capabilityFile,
                adapterId = "codex-v1",
                adapterVersion = "0.4.0",
                policyFile = policyFile,
                policyDigest = "abc",
            )

        assertTrue(result.isSuccess)
        val entry = servers().getValue(AcpHostRegistration.DEFAULT_ENTRY_NAME).jsonObject
        val args = entry.getValue("args").jsonArray.map { it.jsonPrimitive.content }
        val agentCmdIndex = args.indexOf("--agent-cmd")
        assertEquals(args.size - 2, agentCmdIndex)
        assertTrue(
            args.containsAll(
                listOf(
                    "--adapter",
                    "codex-v1",
                    "--telemetry-policy",
                    policyFile.toAbsolutePath().normalize().toString(),
                    "--telemetry-policy-digest",
                    "abc",
                )
            )
        )
        assertTrue(args.indexOf("--adapter") in 0 until agentCmdIndex)
        assertTrue(args.indexOf("--telemetry-policy") in 0 until agentCmdIndex)
        assertTrue(args.indexOf("--telemetry-policy-digest") in 0 until agentCmdIndex)
        assertTrue(args.indexOf(AcpHostRegistration.COMPAT_IDEMPOTENT_INITIALIZE_FLAG) in 0 until agentCmdIndex)
    }

    @Test
    fun `register omits the adapter flag when the manifest declares none`() {
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        val agentExecutable = directory.resolve("agent")
        Files.writeString(agentExecutable, "agent-binary")

        val result =
            registration().register(
                resolved = runtime(proxyExecutable),
                agentArgv = listOf(agentExecutable.toString()),
                spoolEndpoint = null,
                capabilityFile = null,
                adapterId = "   ",
            )

        assertTrue(result.isSuccess)
        val entry = servers().getValue(AcpHostRegistration.DEFAULT_ENTRY_NAME).jsonObject
        val args = entry.getValue("args").jsonArray.map { it.jsonPrimitive.content }
        assertFalse(args.contains("--adapter"))
    }

    @Test
    fun `register emits sorted agent env overrides before the agent command`() {
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        val agentExecutable = directory.resolve("agent")
        Files.writeString(agentExecutable, "agent-binary")

        val result =
            registration().register(
                resolved = runtime(proxyExecutable),
                agentArgv = listOf(agentExecutable.toString()),
                spoolEndpoint = null,
                capabilityFile = null,
                agentEnv = mapOf("GOOSE_MODEL" to "gpt-5", "GOOSE_MODE" to "auto"),
            )

        assertTrue(result.isSuccess)
        val entry = servers().getValue(AcpHostRegistration.DEFAULT_ENTRY_NAME).jsonObject
        val args = entry.getValue("args").jsonArray.map { it.jsonPrimitive.content }
        val agentCmdIndex = args.indexOf("--agent-cmd")
        assertTrue(agentCmdIndex > 0)
        assertEquals(
            listOf(
                AcpHostRegistration.AGENT_ENV_FLAG,
                "GOOSE_MODE=auto",
                AcpHostRegistration.AGENT_ENV_FLAG,
                "GOOSE_MODEL=gpt-5",
            ),
            args.subList(
                args.indexOf(AcpHostRegistration.AGENT_ENV_FLAG),
                agentCmdIndex,
            ),
        )
    }

    @Test
    fun `register emits the agent run id as a flag and an env marker before the agent command`() {
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        val agentExecutable = directory.resolve("agent")
        Files.writeString(agentExecutable, "agent-binary")

        val result =
            registration().register(
                resolved = runtime(proxyExecutable),
                agentArgv = listOf(agentExecutable.toString()),
                spoolEndpoint = null,
                capabilityFile = null,
                agentRunId = "run-123",
            )

        assertTrue(result.isSuccess)
        val entry = servers().getValue(AcpHostRegistration.DEFAULT_ENTRY_NAME).jsonObject
        val args = entry.getValue("args").jsonArray.map { it.jsonPrimitive.content }
        val env = entry.getValue("env").jsonObject
        assertEquals("run-123", env.getValue(AcpHostRegistration.RUN_ID_ENV_VAR).jsonPrimitive.content)
        val runIndex = args.indexOf(AcpHostRegistration.AGENT_RUN_ID_FLAG)
        assertTrue(runIndex >= 0, "the run id flag must be present")
        assertEquals("run-123", args[runIndex + 1])
        assertTrue(
            runIndex < args.indexOf(AcpHostRegistration.AGENT_CMD_FLAG),
            "--agent-run-id must precede the --agent-cmd REMAINDER",
        )
    }

    @Test
    fun `register omits the agent contract when the development runtime carries no packaged agent`() {
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")

        val result =
            registration().register(
                resolved = runtime(proxyExecutable),
                agentArgv = emptyList(),
                spoolEndpoint = null,
                capabilityFile = null,
                digestFallbackToAgentArgv = false,
            )

        assertTrue(result.isSuccess)
        val args =
            servers()
                .getValue(AcpHostRegistration.DEFAULT_ENTRY_NAME)
                .jsonObject
                .getValue("args")
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertFalse(args.contains(AcpHostRegistration.AGENT_CMD_FLAG))
        assertFalse(args.contains(AcpHostRegistration.AGENT_DIGEST_FLAG))
        assertTrue(
            AcpHostRegistration.COMPAT_IDEMPOTENT_INITIALIZE_FLAG in args,
            "the compatibility flag is always emitted, even without a packaged agent",
        )
    }

    @Test
    fun `register omits the run id flag and env when no run id is supplied`() {
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        val agentExecutable = directory.resolve("agent")
        Files.writeString(agentExecutable, "agent-binary")

        val result =
            registration().register(
                resolved = runtime(proxyExecutable),
                agentArgv = listOf(agentExecutable.toString()),
                spoolEndpoint = null,
                capabilityFile = null,
                agentRunId = "   ",
            )

        assertTrue(result.isSuccess)
        val entry = servers().getValue(AcpHostRegistration.DEFAULT_ENTRY_NAME).jsonObject
        val args = entry.getValue("args").jsonArray.map { it.jsonPrimitive.content }
        val env = entry.getValue("env").jsonObject
        assertFalse(args.contains(AcpHostRegistration.AGENT_RUN_ID_FLAG))
        assertFalse(env.containsKey(AcpHostRegistration.RUN_ID_ENV_VAR))
    }

    @Test
    fun `register emits the inference credential file path before the agent command and never the credential`() {
        val proxyExecutable = directory.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        val agentExecutable = directory.resolve("goose")
        Files.writeString(agentExecutable, "agent-binary")
        val credentialFile = directory.resolve("research/inference-credential-1.json")
        val canary = "CANARY-BEARER-" + "x".repeat(24)
        assertTrue(writeInferenceCredentialFile(credentialFile, "OPENAI_API_KEY", canary).isSuccess)

        val result =
            registration().register(
                resolved = runtime(proxyExecutable),
                agentArgv = listOf(agentExecutable.toString(), "acp"),
                spoolEndpoint = null,
                capabilityFile = null,
                agentEnv = mapOf("OPENAI_HOST" to "https://research.example.org", "GOOSE_PROVIDER" to "openai"),
                inferenceCredentialFile = credentialFile,
            )

        assertTrue(result.isSuccess)
        val entry = servers().getValue(AcpHostRegistration.DEFAULT_ENTRY_NAME).jsonObject
        val args = entry.getValue("args").jsonArray.map { it.jsonPrimitive.content }
        val env = entry.getValue("env").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
        val expectedPath = credentialFile.toAbsolutePath().normalize().toString()
        val flagIndex = args.indexOf(AcpHostRegistration.INFERENCE_CREDENTIAL_FILE_FLAG)
        val agentCmdIndex = args.indexOf(AcpHostRegistration.AGENT_CMD_FLAG)
        assertTrue(flagIndex >= 0, "the credential file flag must be present")
        assertEquals(expectedPath, args[flagIndex + 1])
        assertTrue(flagIndex < agentCmdIndex, "--inference-credential-file must precede the --agent-cmd REMAINDER")
        assertEquals(expectedPath, env[AcpHostRegistration.INFERENCE_CREDENTIAL_FILE_ENV_VAR])
        // Only the path travels: the credential never enters argv or the entry env.
        assertFalse(Files.readString(registry).contains(canary), "the credential must never enter the ACP registry")
        assertFalse(args.any { it.startsWith("OPENAI_API_KEY=") })
        assertFalse(env.containsKey("OPENAI_API_KEY"))
        assertTrue(Files.readString(credentialFile).contains(canary))
    }

    @Test
    fun `the inference credential file is owner-only, atomically replaced, and shaped for the proxy`() {
        val credentialFile = directory.resolve("research/inference-credential-2.json")

        assertTrue(writeInferenceCredentialFile(credentialFile, "OPENAI_API_KEY", "first-" + "a".repeat(20)).isSuccess)
        assertTrue(writeInferenceCredentialFile(credentialFile, "OPENAI_API_KEY", "second-" + "b".repeat(20)).isSuccess)

        assertOwnerOnly(credentialFile)
        Files.newDirectoryStream(credentialFile.parent).use { stream ->
            assertTrue(stream.none { it.fileName.toString().endsWith(".tmp") }, "no staging file may remain")
        }
        val document = parseCanonicalJsonObject(Files.readString(credentialFile))
        assertEquals(setOf("schema_version", "credential_env_key", "credential"), document.keys)
        assertEquals("1", document["schema_version"])
        assertEquals("OPENAI_API_KEY", document["credential_env_key"])
        assertEquals("second-" + "b".repeat(20), document["credential"])
    }

    @Test
    fun `the inference credential writer refuses documents the proxy would reject`() {
        val credentialFile = directory.resolve("research/inference-credential-3.json")

        assertTrue(writeInferenceCredentialFile(credentialFile, "not a var", "value").isFailure)
        assertTrue(writeInferenceCredentialFile(credentialFile, "OPENAI_API_KEY", "  ").isFailure)
        assertTrue(writeInferenceCredentialFile(credentialFile, "OPENAI_API_KEY", "bad\nvalue").isFailure)
        assertFalse(Files.exists(credentialFile), "a refused document must not be written")
        val failure = writeInferenceCredentialFile(credentialFile, "OPENAI_API_KEY", "secret\u0000value").exceptionOrNull()
        assertTrue(failure != null)
        assertFalse(failure?.message?.contains("secret") == true, "failure messages never carry the credential")
    }

    private fun registration(): AcpHostRegistration = AcpHostRegistration(registry)

    private fun registration(entryName: String): AcpHostRegistration =
        AcpHostRegistration(registry, entryName = entryName)

    /**
     * An [AcpHostRegistration] whose registry writer is [writer]; the other
     * injected collaborators are never exercised by [AcpHostRegistration.unregister].
     */
    private fun registrationWith(writer: AcpRegistryWriter): AcpHostRegistration =
        AcpHostRegistration(
            registryPath = registry,
            entryName = AcpHostRegistration.DEFAULT_ENTRY_NAME,
            registryWriterFactory = { writer },
            capabilityWriter = { _, _ -> },
            capabilityFactory = { "test-capability" },
            digestFactory = { "test-digest" },
        )

    @Test
    fun `unregister retries once when the entry survives the first removal`() {
        val writer = mock<AcpRegistryWriter>()
        whenever(writer.removeEntry(AcpHostRegistration.DEFAULT_ENTRY_NAME)).thenReturn(Result.success(Unit))
        // The entry is reported present after the first removal and gone after
        // the retry: the second removal must have been attempted.
        whenever(writer.hasEntry(AcpHostRegistration.DEFAULT_ENTRY_NAME)).thenReturn(true, false)

        val result = registrationWith(writer).unregister()

        assertTrue(result.isSuccess)
        verify(writer, times(2)).removeEntry(AcpHostRegistration.DEFAULT_ENTRY_NAME)
        verify(writer, times(2)).hasEntry(AcpHostRegistration.DEFAULT_ENTRY_NAME)
    }

    @Test
    fun `unregister fails when the entry survives the retry`() {
        val writer = mock<AcpRegistryWriter>()
        whenever(writer.removeEntry(AcpHostRegistration.DEFAULT_ENTRY_NAME)).thenReturn(Result.success(Unit))
        whenever(writer.hasEntry(AcpHostRegistration.DEFAULT_ENTRY_NAME)).thenReturn(true)

        val result = registrationWith(writer).unregister()

        assertTrue(result.isFailure, "a surviving entry must not be reported as success")
        val failure = result.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "expected an IllegalStateException, got $failure")
        assertTrue(
            failure?.message?.contains(AcpHostRegistration.DEFAULT_ENTRY_NAME) == true,
            "the failure must name the surviving entry: ${failure?.message}",
        )
        verify(writer, times(2)).removeEntry(AcpHostRegistration.DEFAULT_ENTRY_NAME)
        verify(writer, times(2)).hasEntry(AcpHostRegistration.DEFAULT_ENTRY_NAME)
    }

    private fun runtime(proxyExecutable: Path): ResolvedProxyRuntime =
        ResolvedProxyRuntime(
            runtimeRoot = proxyExecutable.parent.parent,
            proxyArgv = listOf(proxyExecutable.toString(), "--stdio"),
            proxyDigest = "ab".repeat(32),
        )

    private fun writeExistingRegistry() {
        val registryJson =
            """{"custom":"kept","agent_servers":{""" +
                """"Goose (Code4Me)":{"command":"goose"},""" +
                """"Codex (Code4Me)":{"command":"npx"},""" +
                """"Other":{"command":"other"}}}"""
        Files.writeString(registry, registryJson)
    }

    private fun servers(): Map<String, kotlinx.serialization.json.JsonElement> =
        Json.parseToJsonElement(Files.readString(registry)).jsonObject
            .getValue("agent_servers")
            .jsonObject

    private fun rootValue(key: String): String = Json.parseToJsonElement(Files.readString(registry)).jsonObject.getValue(key).toString()

    private fun assertNoTempFiles() {
        Files.newDirectoryStream(directory).use { stream ->
            assertTrue(stream.none { it.fileName.toString().endsWith(".tmp") })
        }
    }

    private fun assertOwnerOnly(file: Path) {
        try {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem: the user ACL keeps the file scoped.
        }
    }
}

// --------------------------------------------------------------------------
// ByoaAgentResolverTest.kt
// --------------------------------------------------------------------------

/**
 * BYOA agent resolution: a participant-installed agent is located in the
 * documented order (configured command, release command, package discovery via
 * PATH then known locations) and the observed identity records what was used.
 * A missing configured command never falls back silently.
 */
class ByoaAgentResolverTest {
    private fun executable(
        directory: Path,
        name: String,
        content: String = "$name-binary",
    ): Path {
        Files.createDirectories(directory)
        val file = directory.resolve(name)
        Files.writeString(file, content)
        file.toFile().setExecutable(true, false)
        return file
    }

    private fun resolver(
        pathEnv: String? = "",
        knownLocations: List<Path> = emptyList(),
        version: String? = null,
    ): DefaultByoaAgentResolver =
        DefaultByoaAgentResolver(
            pathEnv = pathEnv,
            knownLocations = knownLocations,
            isExecutable = { Files.isRegularFile(it) },
            versionProbe = { version },
        )

    @Test
    fun `a configured command is authoritative and records the configured source`() {
        val directory = Files.createTempDirectory("byoa-configured")
        val goose = executable(directory, "goose", "goose-binary")

        val result = resolver(version = "goose version 1.2.3").resolve(
            ByoaAgentSpec(configuredCommand = goose.toString(), command = "codex", agentPackage = "codex"),
        ) as ByoaAgentResolution.Resolved

        assertEquals(goose.toAbsolutePath().normalize(), result.identity.executable)
        assertEquals(AgentDiscoverySource.CONFIGURED, result.identity.source)
        assertEquals(sha256Hex("goose-binary"), result.identity.digest)
        assertEquals("goose version 1.2.3", result.identity.version)
        assertEquals(listOf(goose.toAbsolutePath().normalize().toString()), result.argv)
    }

    @Test
    fun `a missing configured command blocks instead of falling back to PATH`() {
        val pathDirectory = Files.createTempDirectory("byoa-path-fallback")
        executable(pathDirectory, "goose")
        val byoa = resolver(pathEnv = pathDirectory.toString())

        val result =
            byoa.resolve(
                ByoaAgentSpec(configuredCommand = "definitely-not-installed", command = "goose", agentPackage = "goose"),
            )

        assertTrue(result is ByoaAgentResolution.NotFound, "a configured-but-missing command must not fall back")
    }

    @Test
    fun `a release command is used and records the release source`() {
        val directory = Files.createTempDirectory("byoa-release-command")
        val codex = executable(directory, "codex-acp")

        val result = resolver().resolve(ByoaAgentSpec(command = codex.toString())) as ByoaAgentResolution.Resolved

        assertEquals(codex.toAbsolutePath().normalize(), result.identity.executable)
        assertEquals(AgentDiscoverySource.RELEASE_COMMAND, result.identity.source)
    }

    @Test
    fun `a bare package name is discovered on PATH then in known locations`() {
        val pathDirectory = Files.createTempDirectory("byoa-path")
        val knownDirectory = Files.createTempDirectory("byoa-known")
        val goose = executable(pathDirectory, "goose")
        val codex = executable(knownDirectory, "codex")
        val byoa = resolver(pathEnv = pathDirectory.toString(), knownLocations = listOf(knownDirectory))

        val fromPath = byoa.resolve(ByoaAgentSpec(agentPackage = "goose")) as ByoaAgentResolution.Resolved
        assertEquals(goose.toAbsolutePath().normalize(), fromPath.identity.executable)
        assertEquals(AgentDiscoverySource.PATH, fromPath.identity.source)

        val fromKnown = byoa.resolve(ByoaAgentSpec(agentPackage = "codex")) as ByoaAgentResolution.Resolved
        assertEquals(codex.toAbsolutePath().normalize(), fromKnown.identity.executable)
        assertEquals(AgentDiscoverySource.KNOWN_LOCATION, fromKnown.identity.source)
    }

    @Test
    fun `the codex package resolves codex before codex-acp`() {
        val directory = Files.createTempDirectory("byoa-codex-order")
        val codex = executable(directory, "codex")
        executable(directory, "codex-acp")
        val byoa = resolver(pathEnv = directory.toString())

        val result = byoa.resolve(ByoaAgentSpec(agentPackage = "codex")) as ByoaAgentResolution.Resolved

        assertEquals(codex.toAbsolutePath().normalize(), result.identity.executable)
    }

    @Test
    fun `the codex package falls back to codex-acp when codex is absent`() {
        val directory = Files.createTempDirectory("byoa-codex-acp")
        val codexAcp = executable(directory, "codex-acp")
        val byoa = resolver(pathEnv = directory.toString())

        val result = byoa.resolve(ByoaAgentSpec(agentPackage = "codex")) as ByoaAgentResolution.Resolved

        assertEquals(codexAcp.toAbsolutePath().normalize(), result.identity.executable)
    }

    @Test
    fun `an unknown or absent package is not found`() {
        val byoa = resolver()

        assertTrue(byoa.resolve(ByoaAgentSpec(agentPackage = "goose")) is ByoaAgentResolution.NotFound)
        assertTrue(byoa.resolve(ByoaAgentSpec()) is ByoaAgentResolution.NotFound)
    }

    @Test
    fun `command args are appended as a separate argv array`() {
        val directory = Files.createTempDirectory("byoa-args")
        val goose = executable(directory, "goose")

        val result =
            resolver().resolve(ByoaAgentSpec(command = goose.toString(), commandArgs = listOf("acp", "--verbose"))) as
                ByoaAgentResolution.Resolved

        assertEquals(listOf(goose.toAbsolutePath().normalize().toString(), "acp", "--verbose"), result.argv)
    }
}

// --------------------------------------------------------------------------
// ProxyRuntimeResolverTest.kt
// --------------------------------------------------------------------------

class ProxyRuntimeResolverTest {
    private val os = "macos"
    private val arch = "aarch64"

    // ------------------------------------------------------------------
    // Exploded layout
    // ------------------------------------------------------------------

    @Test
    fun `valid manifest resolves the exact platform and digest with no fallback`() {
        val root = Files.createTempDirectory("proxy-runtime")
        writeManifest(
            root,
            files = listOf(Fixture("bin/telemetry-acp-proxy", "#!/bin/sh\nexit 0\n", executable = true)),
            entrypoint = "bin/telemetry-acp-proxy",
        )

        val resolved = resolver(root).resolve() as ProxyRuntimeResolution.Resolved

        assertEquals(root.toRealPath().resolve("bin/telemetry-acp-proxy"), Path.of(resolved.runtime.proxyArgv.first()).toRealPath())
        assertEquals(sha256Hex("#!/bin/sh\nexit 0\n"), resolved.runtime.proxyDigest)
        assertTrue(resolved.runtime.selfContained)
        assertFalse(resolved.runtime.development)
    }

    @Test
    fun `an injected runtime root is honored`() {
        val root = Files.createTempDirectory("proxy-runtime-injected")
        writeManifest(
            root,
            files = listOf(Fixture("bin/telemetry-acp-proxy", "proxy", executable = true)),
            entrypoint = "bin/telemetry-acp-proxy",
        )

        assertTrue(resolver(root).resolve() is ProxyRuntimeResolution.Resolved)
        val wrongPlatform = resolver(root, os = "linux", arch = "x64").resolve() as ProxyRuntimeResolution.Failed
        assertEquals(ProxyRuntimeErrorCode.UNSUPPORTED_PLATFORM, wrongPlatform.error.code)
    }

    @Test
    fun `a missing runtime manifest in both layouts fails closed`() {
        val failure =
            PackagedProxyRuntimeResolver(
                explodedRoot = Files.createTempDirectory("proxy-runtime-none"),
                os = os,
                arch = arch,
                cacheRoot = Files.createTempDirectory("proxy-cache"),
                resources = RuntimeResourceSource { null },
            ).resolve() as ProxyRuntimeResolution.Failed
        assertEquals(ProxyRuntimeErrorCode.ARTIFACT_MISSING, failure.error.code)
    }

    @Test
    fun `an unsupported platform fails closed`() {
        val root = Files.createTempDirectory("proxy-runtime-platform")
        writeManifest(
            root,
            files = listOf(Fixture("bin/telemetry-acp-proxy", "proxy", executable = true)),
            entrypoint = "bin/telemetry-acp-proxy",
            platformOs = "linux",
            platformArch = "x64",
        )

        val failure = resolver(root).resolve() as ProxyRuntimeResolution.Failed

        assertEquals(ProxyRuntimeErrorCode.UNSUPPORTED_PLATFORM, failure.error.code)
    }

    @Test
    fun `an unsafe declared path fails closed`() {
        val root = Files.createTempDirectory("proxy-runtime-path")
        writeManifest(
            root,
            files = listOf(Fixture("../evil-proxy", "evil", executable = true)),
            entrypoint = "../evil-proxy",
            writePayloads = false,
        )

        val failure = resolver(root).resolve() as ProxyRuntimeResolution.Failed

        assertEquals(ProxyRuntimeErrorCode.PATH_ESCAPE, failure.error.code)
    }

    @Test
    fun `a digest mismatch fails closed`() {
        val root = Files.createTempDirectory("proxy-runtime-digest")
        writeFile(root, Fixture("bin/telemetry-acp-proxy", "proxy", executable = true))
        writeManifest(
            root,
            files = listOf(Fixture("bin/telemetry-acp-proxy", "proxy", executable = true)),
            entrypoint = "bin/telemetry-acp-proxy",
            digestOverride = "0".repeat(64),
        )

        val failure = resolver(root).resolve() as ProxyRuntimeResolution.Failed

        assertEquals(ProxyRuntimeErrorCode.DIGEST_MISMATCH, failure.error.code)
    }

    @Test
    fun `a missing declared file fails closed`() {
        val root = Files.createTempDirectory("proxy-runtime-missing")
        writeManifest(
            root,
            files = listOf(Fixture("bin/absent-proxy", "absent", executable = true)),
            entrypoint = "bin/absent-proxy",
            writePayloads = false,
        )

        val failure = resolver(root).resolve() as ProxyRuntimeResolution.Failed

        assertEquals(ProxyRuntimeErrorCode.ARTIFACT_MISSING, failure.error.code)
    }

    @Test
    fun `a non self contained platform fails closed when dev mode is disabled`() {
        val root = Files.createTempDirectory("proxy-runtime-source")
        writeManifest(root, files = listOf(Fixture("run.py", "print('proxy')")), entrypoint = "run.py", selfContained = false)

        val failure = resolver(root).resolve() as ProxyRuntimeResolution.Failed

        assertEquals(ProxyRuntimeErrorCode.NOT_SELF_CONTAINED, failure.error.code)
    }

    @Test
    fun `a malformed manifest fails closed`() {
        val root = Files.createTempDirectory("proxy-runtime-malformed")
        Files.writeString(root.resolve("proxy-manifest.json"), "{ not json")

        val failure = resolver(root).resolve() as ProxyRuntimeResolution.Failed

        assertEquals(ProxyRuntimeErrorCode.MALFORMED_MANIFEST, failure.error.code)
    }

    @Test
    fun `a proxy-only manifest never falls back to a PATH or package-manager agent`() {
        val root = Files.createTempDirectory("proxy-runtime-no-agent-fallback")
        val proxy = Fixture("bin/telemetry-acp-proxy", "proxy", executable = true)
        writeManifest(root, files = listOf(proxy), entrypoint = proxy.path)

        val resolved = resolver(root).resolve() as ProxyRuntimeResolution.Resolved

        // Every argv entry the resolver can produce comes from the verified root;
        // no PATH/npm/npx/host-agent lookup is ever attempted.
        val realRoot = root.toRealPath()
        resolved.runtime.proxyArgv.forEach { arg ->
            assertTrue(Path.of(arg).isAbsolute, "argv entry must be an absolute resolved path: $arg")
        }
        assertTrue(Path.of(resolved.runtime.proxyArgv.first()).toRealPath().startsWith(realRoot))
        val tokens = resolved.runtime.proxyArgv.joinToString(" ").lowercase().split(Regex("[^a-z0-9-]+"))
        listOf("npm", "npx", "node", "goose", "codex", "codex-acp").forEach { tool ->
            assertFalse(tool in tokens, "argv must not reference the host tool '$tool'")
        }
    }

    // ------------------------------------------------------------------
    // Development (source) runtime
    // ------------------------------------------------------------------

    @Test
    fun `dev mode off refuses a source bundle`() {
        val root = Files.createTempDirectory("proxy-runtime-dev-off")
        writeManifest(root, files = listOf(Fixture("run.py", "print('proxy')")), entrypoint = "run.py", selfContained = false)

        val failure =
            resolver(
                root,
                devRuntime = DevelopmentRuntime(allowed = false, interpreter = "/usr/bin/python3"),
            ).resolve() as ProxyRuntimeResolution.Failed

        assertEquals(ProxyRuntimeErrorCode.NOT_SELF_CONTAINED, failure.error.code)
    }

    @Test
    fun `dev mode on with an interpreter resolves an interpreter argv and verifies digests`() {
        val root = Files.createTempDirectory("proxy-runtime-dev-on")
        writeManifest(root, files = listOf(Fixture("run.py", "print('proxy')")), entrypoint = "run.py", selfContained = false)

        val resolved =
            resolver(
                root,
                devRuntime = DevelopmentRuntime(allowed = true, interpreter = "/usr/bin/python3"),
            ).resolve() as ProxyRuntimeResolution.Resolved

        assertTrue(resolved.runtime.development)
        assertFalse(resolved.runtime.selfContained)
        assertEquals("/usr/bin/python3", resolved.runtime.proxyArgv.first())
        assertEquals(root.toRealPath().resolve("run.py"), Path.of(resolved.runtime.proxyArgv[1]).toRealPath())
    }

    @Test
    fun `dev mode on with a blank interpreter is still refused`() {
        val root = Files.createTempDirectory("proxy-runtime-dev-blank")
        writeManifest(root, files = listOf(Fixture("run.py", "print('proxy')")), entrypoint = "run.py", selfContained = false)

        val failure =
            resolver(root, devRuntime = DevelopmentRuntime(allowed = true, interpreter = "  ")).resolve() as ProxyRuntimeResolution.Failed

        assertEquals(ProxyRuntimeErrorCode.NOT_SELF_CONTAINED, failure.error.code)
    }

    @Test
    fun `dev mode still verifies digests before accepting a source bundle`() {
        val root = Files.createTempDirectory("proxy-runtime-dev-digest")
        writeFile(root, Fixture("run.py", "print('proxy')"))
        writeManifest(
            root,
            files = listOf(Fixture("run.py", "print('proxy')")),
            entrypoint = "run.py",
            selfContained = false,
            digestOverride = "2".repeat(64),
        )

        val failure =
            resolver(
                root,
                devRuntime = DevelopmentRuntime(allowed = true, interpreter = "/usr/bin/python3"),
            ).resolve() as ProxyRuntimeResolution.Failed

        assertEquals(ProxyRuntimeErrorCode.DIGEST_MISMATCH, failure.error.code)
    }

    // ------------------------------------------------------------------
    // Packaged (classpath) layout + extraction
    // ------------------------------------------------------------------

    @Test
    fun `a classpath manifest is extracted verified and resolved`() {
        val cache = Files.createTempDirectory("proxy-cache")
        val resources = ResourceMap()
        resources.manifest(
            files = listOf(Fixture("bin/telemetry-acp-proxy", "proxy-binary", executable = true)),
            entrypoint = "bin/telemetry-acp-proxy",
        )

        val resolved =
            PackagedProxyRuntimeResolver(
                explodedRoot = null,
                os = os,
                arch = arch,
                cacheRoot = cache,
                resources = resources.source,
            ).resolve() as ProxyRuntimeResolution.Resolved

        val cachedExecutable = Path.of(resolved.runtime.proxyArgv.first())
        assertTrue(
            resolved.runtime.runtimeRoot.startsWith(cache.toAbsolutePath().normalize()),
            "expected a cache-rooted runtime, got ${resolved.runtime.runtimeRoot}",
        )
        assertTrue(cachedExecutable.startsWith(resolved.runtime.runtimeRoot), "expected a cache path, got $cachedExecutable")
        assertEquals("proxy-binary", Files.readString(cachedExecutable))
        assertTrue(Files.isExecutable(cachedExecutable))
    }

    @Test
    fun `a tampered classpath resource fails with a digest mismatch`() {
        val cache = Files.createTempDirectory("proxy-cache-tampered")
        val resources = ResourceMap()
        resources.manifest(
            files = listOf(Fixture("bin/telemetry-acp-proxy", "expected-bytes", executable = true)),
            entrypoint = "bin/telemetry-acp-proxy",
            bytesOverride = mapOf("bin/telemetry-acp-proxy" to "tampered-bytes"),
        )

        val failure =
            PackagedProxyRuntimeResolver(
                explodedRoot = null,
                os = os,
                arch = arch,
                cacheRoot = cache,
                resources = resources.source,
            ).resolve() as ProxyRuntimeResolution.Failed

        assertEquals(ProxyRuntimeErrorCode.DIGEST_MISMATCH, failure.error.code)
    }

    @Test
    fun `a second packaged resolve reuses the verified cache without re-reading payloads`() {
        val cache = Files.createTempDirectory("proxy-cache-idempotent")
        val resources = ResourceMap()
        resources.manifest(
            files = listOf(Fixture("bin/telemetry-acp-proxy", "proxy-binary", executable = true)),
            entrypoint = "bin/telemetry-acp-proxy",
        )
        val resolver =
            PackagedProxyRuntimeResolver(
                explodedRoot = null,
                os = os,
                arch = arch,
                cacheRoot = cache,
                resources = resources.source,
            )
        val first = resolver.resolve() as ProxyRuntimeResolution.Resolved
        val firstMtime = Files.getLastModifiedTime(Path.of(first.runtime.proxyArgv.first()))

        resources.openedPayloads.clear()
        val second = resolver.resolve() as ProxyRuntimeResolution.Resolved

        assertTrue(resources.openedPayloads.isEmpty(), "payload resources must not be re-read from a verified cache")
        assertEquals(first.runtime.proxyArgv.first(), second.runtime.proxyArgv.first())
        assertEquals(firstMtime, Files.getLastModifiedTime(Path.of(second.runtime.proxyArgv.first())))
    }

    @Test
    fun `a cache root that cannot be created yields a typed extraction failure`() {
        val base = Files.createTempDirectory("proxy-cache-blocked")
        val blocker = base.resolve("blocker")
        Files.writeString(blocker, "not a directory")
        val resources = ResourceMap()
        resources.manifest(
            files = listOf(Fixture("bin/telemetry-acp-proxy", "proxy-binary", executable = true)),
            entrypoint = "bin/telemetry-acp-proxy",
        )

        val failure =
            PackagedProxyRuntimeResolver(
                explodedRoot = null,
                os = os,
                arch = arch,
                cacheRoot = blocker.resolve("child"),
                resources = resources.source,
            ).resolve() as ProxyRuntimeResolution.Failed

        assertEquals(ProxyRuntimeErrorCode.EXTRACTION_FAILED, failure.error.code)
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private data class Fixture(val path: String, val content: String, val executable: Boolean = false)

    private fun resolver(
        root: Path?,
        os: String = this.os,
        arch: String = this.arch,
        devRuntime: DevelopmentRuntime = DevelopmentRuntime.DISABLED,
    ): PackagedProxyRuntimeResolver =
        PackagedProxyRuntimeResolver(root, os, arch, devRuntime = devRuntime)

    private fun writeFile(
        root: Path,
        fixture: Fixture,
    ) {
        val file = root.resolve(fixture.path)
        Files.createDirectories(file.parent)
        Files.writeString(file, fixture.content)
        if (fixture.executable) file.toFile().setExecutable(true, false)
    }

    private fun writeManifest(
        root: Path,
        files: List<Fixture>,
        entrypoint: String,
        selfContained: Boolean = true,
        platformOs: String = os,
        platformArch: String = arch,
        digestOverride: String? = null,
        writePayloads: Boolean = true,
    ) {
        if (writePayloads) {
            files.forEach { writeFile(root, it) }
        }
        val json =
            manifestJson(
                files,
                entrypoint,
                selfContained,
                platformOs,
                platformArch,
                digestOverride,
            )
        Files.writeString(root.resolve("proxy-manifest.json"), json)
    }

    private fun manifestJson(
        files: List<Fixture>,
        entrypoint: String,
        selfContained: Boolean,
        platformOs: String,
        platformArch: String,
        digestOverride: String?,
    ): String =
        buildJsonObject {
            put("schema_version", "1")
            putJsonArray("platforms") {
                addJsonObject {
                    put("os", platformOs)
                    put("arch", platformArch)
                    put("self_contained", selfContained)
                    putJsonArray("entrypoint") { add(JsonPrimitive(entrypoint)) }
                    putJsonArray("files") {
                        files.forEach { add(fileJson(it, if (files.size == 1) digestOverride else null)) }
                    }
                }
            }
        }.toString()

    private fun fileJson(
        fixture: Fixture,
        digestOverride: String?,
    ): JsonObject =
        buildJsonObject {
            put("path", fixture.path)
            put("sha256", digestOverride ?: sha256Hex(fixture.content))
            put("size", fixture.content.toByteArray(Charsets.UTF_8).size)
            put("executable", fixture.executable)
        }

    /** In-memory classpath resource base (absolute resource paths -> bytes). */
    private inner class ResourceMap {
        private val files = linkedMapOf<String, ByteArray>()
        val openedPayloads = mutableListOf<String>()

        val source: RuntimeResourceSource =
            RuntimeResourceSource { path ->
                if (path.endsWith("/${ResearchRuntimeLocation.MANIFEST_FILE}")) {
                    files[path]?.let { ByteArrayInputStream(it) }
                } else {
                    openedPayloads += path
                    files[path]?.let { ByteArrayInputStream(it) }
                }
            }

        fun manifest(
            files: List<Fixture>,
            entrypoint: String,
            selfContained: Boolean = true,
            bytesOverride: Map<String, String> = emptyMap(),
        ) {
            val json =
                manifestJson(
                    files = files,
                    entrypoint = entrypoint,
                    selfContained = selfContained,
                    platformOs = os,
                    platformArch = arch,
                    digestOverride = null,
                )
            this.files["/research-runtime/proxy-manifest.json"] = json.toByteArray(Charsets.UTF_8)
            for (fixture in files) {
                val content = bytesOverride[fixture.path] ?: fixture.content
                this.files["/research-runtime/${fixture.path}"] = content.toByteArray(Charsets.UTF_8)
            }
        }
    }
}

// --------------------------------------------------------------------------
// ResearchProxyPackagingTest.kt
// --------------------------------------------------------------------------

/**
 * Packaging-integrity test: reads the staged `research-runtime/proxy-manifest.json`
 * produced by the `stageResearchProxy` Gradle task and recomputes every file
 * digest from the staged payload. A mismatch means the ZIP would ship an
 * unverifiable runtime.
 */
class ResearchProxyPackagingTest {
    @kotlin.test.Test
    fun `the staged manifest digests match the staged files`() {
        val root = runtimeRoot()
        val manifestPath = root.resolve("proxy-manifest.json")
        assertTrue(Files.isRegularFile(manifestPath), "staged proxy manifest is missing at $manifestPath")

        val document = parseCanonicalJsonObject(Files.readString(manifestPath))
        assertEquals("1", document["schema_version"])

        val platforms = document["platforms"] as? List<*> ?: error("manifest has no platforms")
        assertTrue(platforms.isNotEmpty())

        var verified = 0
        for (rawPlatform in platforms) {
            val platform = rawPlatform as? Map<*, *> ?: error("platform must be an object")
            val files = platform["files"] as? List<*> ?: error("platform has no files")
            assertTrue(files.isNotEmpty(), "platform declares no files")
            val declaredPaths = verifyEntries(root, files) { verified++ }
            val entrypoint = (platform["entrypoint"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            assertTrue(entrypoint.isNotEmpty(), "platform declares no entrypoint")
            assertTrue(
                declaredPaths.contains(entrypoint.first()),
                "entrypoint '${entrypoint.first()}' is not declared in files",
            )

            assertFalse(platform.containsKey("agent"), "the proxy manifest must not declare a legacy agent block")
            assertFalse(platform.containsKey("agents"), "the proxy manifest must not declare release-keyed agents")
        }
        assertNotEquals(0, verified)
        assertTrue(verified >= 2, "expected the staged proxy package to declare its payload files")
    }

    private fun verifyEntries(
        root: Path,
        entries: List<*>,
        onVerified: () -> Unit,
    ): List<String> =
        entries.map { entry ->
            val map = entry as Map<*, *>
            val relative = map["path"] as? String ?: error("file entry has no path")
            assertTrue(!Path.of(relative).isAbsolute, "declared path must be relative: $relative")
            assertTrue(!relative.split('/').contains(".."), "declared path must not contain '..': $relative")
            val file = root.resolve(relative).normalize()
            assertTrue(file.startsWith(root), "declared path escapes the runtime root: $relative")
            assertTrue(Files.isRegularFile(file), "declared file is missing: $relative")
            assertEquals((map["size"] as? Number)?.toLong(), Files.size(file), "size mismatch for $relative")
            assertEquals(map["sha256"] as? String, sha256Hex(Files.readAllBytes(file)), "sha256 mismatch for $relative")
            onVerified()
            relative
        }

    private fun runtimeRoot(): Path {
        val resource = javaClass.getResource("/research-runtime/proxy-manifest.json")
        if (resource != null && resource.protocol == "file") {
            return Path.of(resource.toURI()).parent
        }
        return Path
            .of(System.getProperty("user.dir"))
            .resolve("build")
            .resolve("resources")
            .resolve("main")
            .resolve("research-runtime")
    }
}
