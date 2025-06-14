package me.code4me.completion

import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class PluginCompletionContributorTest {
    @Test
    fun `should instantiate without exceptions`() {
        assertDoesNotThrow {
            PluginCompletionContributor()
        }
    }
}

