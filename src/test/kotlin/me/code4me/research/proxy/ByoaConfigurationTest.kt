package me.code4me.research.proxy

import me.code4me.research.bootstrap.AgentConfigBindingRef
import me.code4me.research.bootstrap.ManifestAgentProfile
import org.junit.jupiter.api.Assertions.assertEquals
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
}
