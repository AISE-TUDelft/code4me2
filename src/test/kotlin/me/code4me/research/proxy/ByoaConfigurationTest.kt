package me.code4me.research.proxy

import me.code4me.research.bootstrap.AgentConfigBindingRef
import me.code4me.research.bootstrap.ManifestAgentProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ByoaConfigurationTest {
    private val profile =
        ManifestAgentProfile(
            profileId = "11111111-1111-1111-1111-111111111111",
            model = "gpt-5",
            temperature = 0.25,
            toolsJson = """["shell","read"]""",
            approvalPolicy = "per_step",
            maxSteps = 4,
        )

    @Test
    fun `env and arg transports carry the frozen values`() {
        val mapping =
            applyByoaConfiguration(
                listOf(
                    AgentConfigBindingRef("model", "env", "GOOSE_MODEL"),
                    AgentConfigBindingRef("approval_policy", "arg", "--approval"),
                    AgentConfigBindingRef("max_steps", "env", "GOOSE_MAX_TURNS"),
                ),
                profile,
            )

        assertEquals("gpt-5", mapping.env["GOOSE_MODEL"])
        assertEquals("4", mapping.env["GOOSE_MAX_TURNS"])
        assertEquals(listOf("--approval", "per_step"), mapping.args)
    }

    @Test
    fun `value map and list formats are applied`() {
        val mapping =
            applyByoaConfiguration(
                listOf(
                    AgentConfigBindingRef(
                        "approval_policy",
                        "arg",
                        "--approval",
                        valueMap = mapOf("per_step" to "on-request"),
                    ),
                    AgentConfigBindingRef("tools", "env", "GOOSE_EXTENSIONS", format = "csv"),
                ),
                profile,
            )

        assertEquals(listOf("--approval", "on-request"), mapping.args)
        assertEquals("shell,read", mapping.env["GOOSE_EXTENSIONS"])

        // A value the map does not mention passes through unchanged.
        val unmapped =
            applyByoaConfiguration(
                listOf(
                    AgentConfigBindingRef(
                        "approval_policy",
                        "arg",
                        "--approval",
                        valueMap = mapOf("auto" to "never"),
                    ),
                ),
                profile,
            )
        assertEquals(listOf("--approval", "per_step"), unmapped.args)
    }

    @Test
    fun `json tools render as a canonical array`() {
        val mapping =
            applyByoaConfiguration(
                listOf(
                    AgentConfigBindingRef("tools", "env", "AGENT_TOOLS", format = "json"),
                ),
                profile,
            )

        assertEquals("""["shell","read"]""", mapping.env["AGENT_TOOLS"])
    }

    @Test
    fun `missing bindings report every set field`() {
        val missing =
            missingByoaBindings(
                listOf(AgentConfigBindingRef("model", "env", "AGENT_MODEL")),
                profile,
            )

        assertEquals(
            listOf("temperature", "max_steps", "tools", "approval_policy"),
            missing,
        )
    }

    @Test
    fun `unknown transports do not count as mapped`() {
        val profile =
            ManifestAgentProfile(
                profileId = "33333333-3333-3333-3333-333333333333",
                model = "gpt-5",
            )

        val missing =
            missingByoaBindings(
                listOf(AgentConfigBindingRef("model", "file", "agent.json")),
                profile,
            )

        assertEquals(listOf("model", "max_steps", "approval_policy"), missing)
    }

    @Test
    fun `absent optional values are skipped and never mapped`() {
        val bare =
            ManifestAgentProfile(
                profileId = "22222222-2222-2222-2222-222222222222",
                model = "",
                temperature = null,
                toolsJson = "[]",
                approvalPolicy = "",
                maxSteps = 0,
            )

        val mapping =
            applyByoaConfiguration(
                listOf(
                    AgentConfigBindingRef("model", "env", "AGENT_MODEL"),
                    AgentConfigBindingRef("temperature", "env", "AGENT_TEMPERATURE"),
                ),
                bare,
            )

        assertTrue(mapping.env.isEmpty())
        assertTrue(mapping.args.isEmpty())
        assertTrue(missingByoaBindings(listOf(AgentConfigBindingRef("model", "env", "X")), bare).isEmpty())
    }

    // ------------------------------------------------------------------
    // Research inference gateway runtime fields (participant budgets)
    // ------------------------------------------------------------------

    private val runtime =
        ByoaRuntimeValues(
            host = "https://research.example.org",
            basePath = "api/research/inference/v1/chat/completions",
            providerKind = "openai_compatible",
            stateDir = "/tmp/research/agent-state-abc",
        )

    private fun gatewayBindings(
        providerValueMap: Map<String, String> = mapOf("openai_compatible" to "openai"),
    ): List<AgentConfigBindingRef> =
        listOf(
            AgentConfigBindingRef("model", "env", "GOOSE_MODEL"),
            AgentConfigBindingRef("inference_gateway_host", "env", "OPENAI_HOST"),
            AgentConfigBindingRef("inference_gateway_base_path", "env", "OPENAI_BASE_PATH"),
            AgentConfigBindingRef("inference_gateway_credential", "env", "OPENAI_API_KEY"),
            AgentConfigBindingRef("provider_kind", "env", "GOOSE_PROVIDER", valueMap = providerValueMap),
            AgentConfigBindingRef("state_dir", "env", "GOOSE_PATH_ROOT"),
        )

    @Test
    fun `gateway runtime values render through the release bindings and the credential never does`() {
        val mapping = applyByoaConfiguration(gatewayBindings(), null, runtime)

        assertEquals("https://research.example.org", mapping.env["OPENAI_HOST"])
        assertEquals("api/research/inference/v1/chat/completions", mapping.env["OPENAI_BASE_PATH"])
        assertEquals("openai", mapping.env["GOOSE_PROVIDER"])
        assertEquals("/tmp/research/agent-state-abc", mapping.env["GOOSE_PATH_ROOT"])
        assertEquals(setOf("GOOSE_PATH_ROOT", "GOOSE_PROVIDER", "OPENAI_BASE_PATH", "OPENAI_HOST"), mapping.env.keys)
        assertTrue(mapping.args.isEmpty())
        // Only the env key is reported; the value never exists on this path.
        assertEquals("OPENAI_API_KEY", mapping.credentialEnvKey)
        assertFalse(mapping.env.containsKey("OPENAI_API_KEY"))
        assertTrue(missingByoaBindings(gatewayBindings(), null, runtime).isEmpty())

        // Profile and runtime values compose; a profile alone reports no key.
        val withProfile = applyByoaConfiguration(gatewayBindings(), profile, runtime)
        assertEquals("gpt-5", withProfile.env["GOOSE_MODEL"])
        assertEquals("OPENAI_API_KEY", withProfile.credentialEnvKey)
        val profileOnly = applyByoaConfiguration(gatewayBindings(), profile)
        assertNull(profileOnly.credentialEnvKey)
        assertEquals(setOf("GOOSE_MODEL"), profileOnly.env.keys)
    }

    @Test
    fun `runtime fields are required only when the manifest carries a gateway`() {
        val profileOnly = listOf(AgentConfigBindingRef("model", "env", "GOOSE_MODEL"))
        val bare =
            ManifestAgentProfile(
                profileId = "44444444-4444-4444-4444-444444444444",
                model = "gpt-5",
                approvalPolicy = "",
                maxSteps = 0,
            )

        assertTrue(missingByoaBindings(profileOnly, bare).isEmpty())
        assertEquals(BYOA_RUNTIME_FIELDS, missingByoaBindings(profileOnly, bare, runtime))
        assertEquals(BYOA_RUNTIME_FIELDS, missingByoaBindings(profileOnly, null, runtime))
        // A credential bound through argv does not count as delivered.
        val argCredential =
            gatewayBindings().map {
                if (it.field == "inference_gateway_credential") it.copy(transport = "arg", key = "--api-key") else it
            }
        assertEquals(listOf("inference_gateway_credential"), missingByoaBindings(argCredential, null, runtime))
    }

    @Test
    fun `an untranslated provider kind is never rendered and fails closed`() {
        val unmapped = gatewayBindings(providerValueMap = emptyMap())

        val mapping = applyByoaConfiguration(unmapped, null, runtime)

        assertFalse(mapping.env.containsKey("GOOSE_PROVIDER"), "the server vocabulary must never reach the agent")
        assertFalse(mapping.env.values.any { it == "openai_compatible" })
        assertEquals(listOf("provider_kind"), missingByoaBindings(unmapped, null, runtime))
        // A value_map for a different vocabulary is just as unusable.
        val wrongVocabulary = gatewayBindings(providerValueMap = mapOf("anthropic" to "anthropic"))
        assertEquals(listOf("provider_kind"), missingByoaBindings(wrongVocabulary, null, runtime))
        assertFalse(applyByoaConfiguration(wrongVocabulary, null, runtime).env.containsKey("GOOSE_PROVIDER"))
    }

    @Test
    fun `a credential binding must exist for a gateway, use env, and own its key`() {
        assertNull(credentialBindingViolation(gatewayBindings(), gatewayPresent = true))
        assertNull(
            credentialBindingViolation(listOf(AgentConfigBindingRef("model", "env", "GOOSE_MODEL")), gatewayPresent = false),
        )

        val missing =
            credentialBindingViolation(
                gatewayBindings().filterNot { it.field == "inference_gateway_credential" },
                gatewayPresent = true,
            )
        assertTrue(missing?.contains("inference_gateway_credential") == true, missing)

        val argCredential =
            gatewayBindings().map {
                if (it.field == "inference_gateway_credential") it.copy(transport = "arg", key = "--api-key") else it
            }
        val argViolation = credentialBindingViolation(argCredential, gatewayPresent = true)
        assertTrue(argViolation?.contains("env transport") == true, argViolation)
        // Even then the credential is not rendered anywhere.
        val argMapping = applyByoaConfiguration(argCredential, null, runtime)
        assertNull(argMapping.credentialEnvKey)
        assertFalse(argMapping.args.contains("--api-key"))

        val collision = gatewayBindings() + AgentConfigBindingRef("temperature", "env", "OPENAI_API_KEY")
        val collisionViolation = credentialBindingViolation(collision, gatewayPresent = true)
        assertTrue(collisionViolation?.contains("OPENAI_API_KEY") == true, collisionViolation)

        val noGateway = credentialBindingViolation(gatewayBindings(), gatewayPresent = false)
        assertTrue(noGateway?.contains("no inference gateway") == true, noGateway)
    }
}
