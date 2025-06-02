/**
 * IMPORTANT: This test class needs to be modified whenever the structure of the modules changes.
 * For example, if a new module is added or an existing module is removed, the tests should be updated accordingly.
 */
import me.code4me.services.config.ConfigService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModulesStructureTest {
    @Test
    fun testDynamicModuleLoading() {
        // Test that the ConfigService correctly loads module configurations
        val configService = ConfigService()
        val availableModules = configService.getAvailableModules()

        // Verify that the expected modules are loaded from the config
        assertTrue(
            "BehavioralTelemetryAggregator should be in available modules",
            availableModules.any { it.id == "BehavioralTelemetryAggregator" },
        )
        assertTrue(
            "ContextualTelemetryAggregator should be in available modules",
            availableModules.any { it.id == "ContextualTelemetryAggregator" },
        )
        assertTrue(
            "contextAggregator should be in available modules",
            availableModules.any { it.id == "contextAggregator" },
        )

        // Verify that the modules have the expected submodules
        val behavioralTelemetryAggregator = availableModules.find { it.id == "BehavioralTelemetryAggregator" }
        assertNotNull("BehavioralTelemetryAggregator should not be null", behavioralTelemetryAggregator)
        assertTrue(
            "BehavioralTelemetryAggregator should have submodules",
            behavioralTelemetryAggregator!!.submodules.isNotEmpty(),
        )

        // Verify specific submodules
        assertTrue(
            "TelemetryAggregator should have TypingSpeed submodule",
            behavioralTelemetryAggregator.submodules.any { it.id == "TypingSpeed" },
        )
        assertTrue(
            "TelemetryAggregator should have TimeSinceLastShownCompletion submodule",
            behavioralTelemetryAggregator.submodules.any { it.id == "TimeSinceLastShownCompletion" },
        )
        assertTrue(
            "TelemetryAggregator should have TimeSinceLastAcceptedCompletion submodule",
            behavioralTelemetryAggregator.submodules.any { it.id == "TimeSinceLastShownCompletion" },
        )

        val contextualTelemetryAggregator = availableModules.find { it.id == "ContextualTelemetryAggregator" }
        assertNotNull("ContextualTelemetryAggregator should not be null", contextualTelemetryAggregator)
        assertTrue(
            "ContextualTelemetryAggregator should have submodules",
            contextualTelemetryAggregator!!.submodules.isNotEmpty(),
        )

        assertTrue(
            "TelemetryAggregator should have EditorContextRetrievalModule submodule",
            contextualTelemetryAggregator.submodules.any { it.id == "EditorContextRetrievalModule" },
        )

        // Verify context aggregator
        val contextAggregator = availableModules.find { it.id == "contextAggregator" }
        assertNotNull("contextAggregator should not be null", contextAggregator)
        assertTrue(
            "contextAggregator should have submodules",
            contextAggregator!!.submodules.isNotEmpty(),
        )

        // Verify specific submodules
        assertTrue(
            "contextAggregator should have FileContextRetrievalModule submodule",
            contextAggregator.submodules.any { it.id == "FileContextRetrievalModule" },
        )
        assertTrue(
            "contextAggregator should have MultiFileContextRetrievalModule submodule",
            contextAggregator.submodules.any { it.id == "MultiFileContextRetrievalModule" },
        )
    }
}
