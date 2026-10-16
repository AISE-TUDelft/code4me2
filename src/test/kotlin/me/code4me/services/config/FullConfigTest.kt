package me.code4me.services.config

import com.intellij.testFramework.HeavyPlatformTestCase
import java.io.File

class FullConfigTest : HeavyPlatformTestCase() {
    fun testModuleInitializationFromConfig() {
        // Mirrors integration-tests/src/test/testData/full-plugin.conf. Loaded as
        // an explicit string because ConfigService only reads the plugin.conf
        // resource or a provided string (there is no plugin.conf.path override).
        val confText = File("src/test/testData/full-plugin.conf").readText()
        val config = ConfigService(confText)
        val all = config.getAvailableModules().flatMap { listOf(it) + it.submodules }
        val byClass = all.associateBy { it.className.substringAfterLast('.') }
        println("Parsed modules: ${byClass.keys.sorted().joinToString()}")

        // Verify specific modules from the test configuration
        assertNotNull(
            "BehavioralTelemetryAggregator should be initialized",
            byClass["BaseBehavioralTelemetryAggregator"],
        )
        assertNotNull(
            "ContextualTelemetryAggregator should be initialized",
            byClass["BaseContextualTelemetryAggregator"],
        )
        assertNotNull("contextAggregator should be initialized", byClass["BaseContextAggregator"])

        // Verify submodules are also initialized
        assertNotNull(
            "TimeSinceLastAcceptedCompletion should be initialized",
            byClass["TimeSinceLastAcceptedCompletion"],
        )
        assertNotNull(
            "EditorContextRetrievalModule should be initialized",
            byClass["EditorContextRetrievalModule"],
        )
        assertNotNull(
            "FileContextRetrievalModule should be initialized",
            byClass["FileContextRetrievalModule"],
        )
        assertNotNull(
            "MultiFileContextRetrievalModule should be initialized",
            byClass["MultiFileContextRetrievalModule"],
        )
        assertNotNull(
            "TimeSinceLastShownCompletion should be initialized",
            byClass["TimeSinceLastShownCompletion"],
        )
        assertNotNull("TypingSpeed should be initialized", byClass["TypingSpeed"])

        // Print initialized modules for debugging
        println("Initialized modules: ${byClass.keys.sorted().joinToString()}")
    }
}
