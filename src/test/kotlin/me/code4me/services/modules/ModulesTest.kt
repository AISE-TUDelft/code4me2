package me.code4me.services.modules

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.code4me.services.config.ConfigService
import me.code4me.services.modules.aggregators.BaseBehavioralTelemetryAggregator
import me.code4me.services.modules.manager.ModuleManager
import me.code4me.services.modules.telemetry.behavioral.TypingSpeed
import me.code4me.services.state.PrefState
import org.junit.jupiter.api.assertDoesNotThrow

class ModulesTest : BasePlatformTestCase() {
    fun testTypingSpeedInitialization() {
        val typingSpeed = TypingSpeed()
        PrefState.enableModule(typingSpeed.getPreferenceId())

        assertDoesNotThrow("TypingSpeed should initialize without exception") {
            typingSpeed.initializeModules()
        }
    }

    fun testBehavioralTelemetryAggregatorInitialization() {
        val aggregator = BaseBehavioralTelemetryAggregator()
        val configService = ConfigService()
        PrefState.enableModule(aggregator.getPreferenceId())

        configService.getAvailableModules()
            .find { it.id == "BehavioralTelemetryAggregator" }
            ?.submodules
            ?.forEach { PrefState.enableModule(it.id) }

        aggregator.initializeModules()

        val loadedSubmodules = aggregator.getSubmodules()
        assertTrue("Aggregator should have submodules", loadedSubmodules.isNotEmpty())
        assertTrue("TypingSpeed should be one of the submodules", loadedSubmodules.any { it is TypingSpeed })
    }

    fun testModuleManagerWithActiveModules() {
        val moduleManager = ModuleManager(project)
        val modules = listOf(TypingSpeed(), BaseBehavioralTelemetryAggregator())

        moduleManager.storeModules(modules)
        modules.forEach { moduleManager.enableModule(it.getPreferenceId()) }

        moduleManager.initializeModules()

        modules.forEach {
            assertTrue("Module ${it.moduleName} should be enabled", moduleManager.isModuleEnabled(it.getPreferenceId()))
        }

        assertEquals("Should have the correct number of enabled modules", modules.size, moduleManager.getEnabledModules().size)

        moduleManager.disableModule(modules[0].getPreferenceId())

        assertFalse("Module ${modules[0].moduleName} should be disabled", moduleManager.isModuleEnabled(modules[0].getPreferenceId()))
        assertEquals("Should have one less enabled module", modules.size - 1, moduleManager.getEnabledModules().size)
    }

    fun testModuleManagerWithAggregator() {
        val moduleManager = ModuleManager(project)
        val aggregator = BaseBehavioralTelemetryAggregator()

        moduleManager.storeModules(listOf(aggregator))
        moduleManager.enableModule(aggregator.getPreferenceId())
        moduleManager.initializeModules()

        assertTrue("Aggregator should be enabled", moduleManager.isModuleEnabled(aggregator.getPreferenceId()))
        assertTrue(
            "Enabled modules should contain the aggregator",
            moduleManager.getEnabledModules().any {
                it.getPreferenceId() == aggregator.getPreferenceId()
            },
        )

        val retrieved = moduleManager.getModule(aggregator.getPreferenceId())
        assertNotNull("Should be able to retrieve the aggregator by ID", retrieved)
        assertEquals("Retrieved aggregator should match", aggregator.moduleName, retrieved?.moduleName)
    }
}
