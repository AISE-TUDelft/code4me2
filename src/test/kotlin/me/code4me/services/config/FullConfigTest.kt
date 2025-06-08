package me.code4me.services.config

import com.intellij.testFramework.HeavyPlatformTestCase
import me.code4me.services.modules.manager.getModuleManager

class FullConfigTest : HeavyPlatformTestCase() {
    override fun setUp() {
        // Test with a full config file
        System.setProperty("plugin.conf.path", "src/test/testData/full-plugin.conf")
        super.setUp()
    }

    override fun tearDown() {
        // Additional teardown for config initialization tests if required
        System.clearProperty("plugin.conf.path")
        super.tearDown()
    }

    fun testModuleInitializationFromConfig() {
        val config = getConfig()
        assertNotNull("Config should not be null. Check if the config file exists and is valid.", config)
        val moduleManager = getModuleManager(project)

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
