package integration

import com.intellij.testFramework.HeavyPlatformTestCase
import me.code4me.services.config.getConfig
import me.code4me.services.modules.manager.getModuleManager

/**
 * This test class is designed to verify the initialization of modules from a full configuration file.
 * It should be completed whenever config file can be loaded from server and can be injected easily
 */
class ConfigHeavyTest : HeavyPlatformTestCase() {

    fun testModuleInitializationFromConfig() {
        val config = getConfig()
        assertNotNull("Config should not be null. Check if the config file exists and is valid.", config)
        val moduleManager = getModuleManager()

        val initializedModuleIds = moduleManager.getEnabledModuleIds()

        println("Initialized modules: ${initializedModuleIds.joinToString()}")

        // Verify specific modules from the test configuration
        assertTrue(
            "BehavioralTelemetryAggregator should be initialized",
            initializedModuleIds.contains("BaseBehavioralTelemetryAggregator"),
        )
        assertTrue(
            "ContextualTelemetryAggregator should be initialized",
            initializedModuleIds.contains("BaseContextualTelemetryAggregator"),
        )
        assertTrue("contextAggregator should be initialized", initializedModuleIds.contains("BaseContextAggregator"))

        // Verify submodules are also initialized
        assertTrue(
            "TimeSinceLastAcceptedCompletion should be initialized",
            initializedModuleIds.contains("TimeSinceLastAcceptedCompletion"),
        )
        assertTrue("EditorContextRetrievalModule should be initialized", initializedModuleIds.contains("EditorContextRetrievalModule"))
        assertTrue("FileContextRetrievalModule should be initialized", initializedModuleIds.contains("FileContextRetrievalModule"))
        assertTrue(
            "MultiFileContextRetrievalModule should be initialized",
            initializedModuleIds.contains("MultiFileContextRetrievalModule"),
        )
        assertTrue("TimeSinceLastShownCompletion should be initialized", initializedModuleIds.contains("TimeSinceLastShownCompletion"))
        assertTrue("TypingSpeed should be initialized", initializedModuleIds.contains("TimeSinceLastAcceptedCompletion"))

        // Print initialized modules for debugging
        println("Initialized modules: ${initializedModuleIds.joinToString()}")
    }
}
