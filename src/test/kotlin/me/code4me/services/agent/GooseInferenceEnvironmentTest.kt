package me.code4me.services.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GooseInferenceEnvironmentTest {
    @Test
    fun `study goose uses the local relay and a placeholder credential`() {
        val env = GooseRuntime.buildEnvBundle("http://127.0.0.1:8765", "study-model")

        assertEquals("openai", env["GOOSE_PROVIDER"])
        assertEquals("study-model", env["GOOSE_MODEL"])
        assertEquals("http://127.0.0.1:8765/v1", env["OPENAI_BASE_URL"])
        assertEquals(GooseRuntime.PROXY_API_KEY_PLACEHOLDER, env["OPENAI_API_KEY"])
    }
}
