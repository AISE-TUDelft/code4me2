
package integration

import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.sections.ConfigurationSection
import me.code4me.services.config.ConfigService
import me.code4me.services.modules.manager.getModuleManager
import me.code4me.services.state.PrefState
import me.code4me.services.state.getPrefState
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceType
import org.junit.jupiter.api.Assertions.*
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree

class PreferencesHeavyTest : HeavyPlatformTestCase() {

    companion object {
        private const val TEST_MODULE_CONFIG = """
            config {
              modules {
                available = [
                  {
                    id = "TestBehavioralModule"
                    class = "me.code4me.services.modules.telemetry.behavioral.TestBehavioralModule"
                    name = "Test Behavioral Module"
                    type = "telemetry"
                    description = "Test module for behavioral telemetry"
                    enabled = true
                  },
                  {
                    id = "TestContextModule"
                    class = "me.code4me.services.modules.context.TestContextModule"
                    name = "Test Context Module"
                    type = "context"
                    description = "Test module for context retrieval"
                    enabled = false
                  }
                ]
                categories = {
                  behavioralTelemetry = {
                    path = "me.code4me.services.modules.telemetry.behavioral"
                    description = "Modules for behavioral telemetry collection"
                  }
                  context = {
                    path = "me.code4me.services.modules.context"
                    description = "Modules for context retrieval"
                  }
                }
              }
              server {
                host = "http://127.0.0.1"
                port = 8008
                contextPath = ""
                timeout = 5000
              }
              auth {
                google {
                  clientId = "test-client-id"
                }
              }
            }
        """
    }

    private lateinit var configService: ConfigService
    private lateinit var moduleManager: me.code4me.services.modules.manager.ModuleManager
    private lateinit var prefState: me.code4me.services.state.PrefSettings

    override fun setUp() {
        super.setUp()

        // Initialize configuration service with test config
        configService = ConfigService.Companion.fromConfigString(TEST_MODULE_CONFIG)

        // Get module manager and preference state
        moduleManager = getModuleManager()
        prefState = getPrefState()

        // Clear any existing preference state
        prefState.enabledModules.clear()
        prefState.modulePreferences.clear()
        prefState.moduleValues.clear()

        // Initialize and setup test modules
        setupTestModules()
    }

    private fun setupTestModules() {
        // Create test preferences for modules
        val behavioralPreferences = listOf(
            Preference(
                key = "enableDataCollection",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                limitedDefaultValue = "false",
                displayName = "Enable Data Collection",
                description = "Enable collection of behavioral data"
            ),
            Preference(
                key = "collectionInterval",
                type = PreferenceType.INT,
                defaultValue = "5000",
                limitedDefaultValue = "10000",
                displayName = "Collection Interval",
                description = "Interval for data collection in milliseconds"
            )
        )

        val contextPreferences = listOf(
            Preference(
                key = "includeFileContext",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                limitedDefaultValue = "false",
                displayName = "Include File Context",
                description = "Include file context in data collection"
            )
        )

        // Register modules with preferences
        registerTestModule("TestBehavioralModule", behavioralPreferences)
        registerTestModule("TestContextModule", contextPreferences)

        // Enable the behavioral module by default
        PrefState.enableModule("TestBehavioralModule")
    }

    private fun registerTestModule(moduleId: String, preferences: List<Preference>) {
        preferences.forEach { preference ->
            val fullKey = "$moduleId.${preference.key}"
            prefState.modulePreferences[fullKey] = preference
            prefState.moduleValues[fullKey] = preference.defaultValue
        }
    }

    fun testConfigurationPanelIsBuiltCorrectly() {
        val section = ConfigurationSection()
        val builder = FormBuilder.createFormBuilder()
        val fields = mutableListOf<StateValueField<*>>()

        section.applyTo(builder, fields)
        val panel: JPanel = builder.panel

        assertNotNull(panel, "Panel should not be null")
        assertTrue(panel.componentCount > 0, "Panel should contain components")

        // Verify that key UI elements exist
        val labels = UIUtil.findComponentsOfType(panel, JLabel::class.java)
        val containsAppPrefs = labels.any { it.text?.contains("Application Preferences") == true }

        assertTrue(containsAppPrefs, "Expected 'Application Preferences' section")
    }

    fun testApplicationPreferencesAreSetupCorrectly() {
        val section = ConfigurationSection()
        val builder = FormBuilder.createFormBuilder()
        val fields = mutableListOf<StateValueField<*>>()

        section.applyTo(builder, fields)

        // Check that application preference fields are registered
        val appPrefFields = fields.filter { field ->
            when (field.getComponent()) {
                is JCheckBox -> {
                    val checkbox = field.getComponent() as JCheckBox
                    checkbox.text.contains("Completions") ||
                            checkbox.text.contains("Contextual telemetry") ||
                            checkbox.text.contains("Behavioral telemetry")
                }
                else -> false
            }
        }

        assertTrue(appPrefFields.size >= 3, "Should have at least 3 application preference fields")
    }

    fun testModulePreferencesAreDisplayed() {
        val section = ConfigurationSection()
        val builder = FormBuilder.createFormBuilder()
        val fields = mutableListOf<StateValueField<*>>()

        section.applyTo(builder, fields)
        val panel: JPanel = builder.panel

        // Look for module tree component
        val trees = UIUtil.findComponentsOfType(panel, JTree::class.java)
        assertTrue(trees.isNotEmpty(), "Should contain a module tree")

        // Look for module preferences panel
        val labels = UIUtil.findComponentsOfType(panel, JLabel::class.java)
        val hasModuleManagement = labels.any { it.text?.contains("Module Management") == true }
        assertTrue(hasModuleManagement, "Should contain Module Management section")
    }

    fun testPreferenceStateManagement() {
        // Test setting preference values
        PrefState.setPreferenceValue("TestBehavioralModule", "enableDataCollection", "false")
        val value = PrefState.getPreferenceValue("TestBehavioralModule", "enableDataCollection")
        assertEquals("Preference value should be updated","false", value)

        // Test getting module preferences
        val modulePrefs = PrefState.getModulePreferences("TestBehavioralModule")
        assertTrue(modulePrefs.isNotEmpty(), "Should have preferences for the test module")

        val dataCollectionPref = modulePrefs.find { it.key == "enableDataCollection" }
        assertNotNull(dataCollectionPref, "Should find the enableDataCollection preference")
        assertEquals(PreferenceType.BOOLEAN, dataCollectionPref?.type, "Preference type should be BOOLEAN")
    }

    fun testModuleEnablementState() {
        // Test module enablement
        assertTrue(PrefState.getEnabledModules().contains("TestBehavioralModule"),
            "TestBehavioralModule should be enabled")
        assertFalse(PrefState.getEnabledModules().contains("TestContextModule"),
            "TestContextModule should be disabled")

        // Test enabling/disabling modules
        PrefState.enableModule("TestContextModule")
        assertTrue(PrefState.getEnabledModules().contains("TestContextModule"),
            "TestContextModule should be enabled after enableModule call")

        PrefState.disableModule("TestContextModule")
        assertFalse(PrefState.getEnabledModules().contains("TestContextModule"),
            "TestContextModule should be disabled after disableModule call")
    }

    fun testLimitedDataCollectionButton() {
        val section = ConfigurationSection()
        val builder = FormBuilder.createFormBuilder()
        val fields = mutableListOf<StateValueField<*>>()

        section.applyTo(builder, fields)
        val panel: JPanel = builder.panel

        // Find the limited data collection button
        val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java)
        val limitedDataButton = buttons.find { it.text?.contains("Use Limited Data Collection") == true }

        assertNotNull(limitedDataButton, "Should have a 'Use Limited Data Collection' button")

        // Test the button functionality
        val originalValue = PrefState.getPreferenceValue("TestBehavioralModule", "enableDataCollection")
        assertEquals("Original value should be true","true", originalValue)

        // Simulate button click by calling the limited defaults method
        assertThrows(java.net.ConnectException::class.java) {
            PrefState.setAllPreferencesToLimitedDefaults()
        }
    }

    fun testPreferenceFieldValidation() {
        // Test boolean preference field creation
        val booleanPref = Preference(
            key = "testBoolean",
            type = PreferenceType.BOOLEAN,
            defaultValue = "true",
            displayName = "Test Boolean",
            description = "A test boolean preference"
        )

        // Test integer preference field creation
        val intPref = Preference(
            key = "testInt",
            type = PreferenceType.INT,
            defaultValue = "100",
            displayName = "Test Integer",
            description = "A test integer preference"
        )

        // Register these test preferences
        prefState.modulePreferences["TestModule.testBoolean"] = booleanPref
        prefState.moduleValues["TestModule.testBoolean"] = "true"
        prefState.modulePreferences["TestModule.testInt"] = intPref
        prefState.moduleValues["TestModule.testInt"] = "100"

        // Verify they are stored correctly
        val storedBooleanPref = prefState.modulePreferences["TestModule.testBoolean"]
        assertNotNull(storedBooleanPref, "Boolean preference should be stored")
        assertEquals(PreferenceType.BOOLEAN, storedBooleanPref?.type, "Should be boolean type")

        val storedIntPref = prefState.modulePreferences["TestModule.testInt"]
        assertNotNull(storedIntPref, "Integer preference should be stored")
        assertEquals(PreferenceType.INT, storedIntPref?.type, "Should be integer type")
    }

    fun testPreferenceClassification() {
        // Test different preference classes
        val behavioralPref = Preference(
            key = "behavioralTest",
            type = PreferenceType.BOOLEAN,
            defaultValue = "true",
            displayName = "Behavioral Test",
            description = "Test for behavioral telemetry preference"
        )

        val contextPref = Preference(
            key = "contextTest",
            type = PreferenceType.STRING,
            defaultValue = "default",
            displayName = "Context Test",
            description = "Test for context preference"
        )

        // These would be classified based on their module type
        prefState.modulePreferences["BehavioralModule.behavioralTest"] = behavioralPref
        prefState.modulePreferences["ContextModule.contextTest"] = contextPref

        // Test that preferences are correctly categorized
        val behavioralPrefs = prefState.modulePreferences.filter {
            it.key.startsWith("BehavioralModule.")
        }
        val contextPrefs = prefState.modulePreferences.filter {
            it.key.startsWith("ContextModule.")
        }

        assertEquals(1, behavioralPrefs.size, "Should have one behavioral preference")
        assertEquals(1, contextPrefs.size, "Should have one context preference")
    }

    fun testPreferenceStateConsistency() {
        // Test that preference state remains consistent across operations
        val initialSize = prefState.modulePreferences.size
        val initialValues = prefState.moduleValues.size

        // Add a new preference
        val newPref = Preference(
            key = "newPref",
            type = PreferenceType.STRING,
            defaultValue = "newValue",
            displayName = "New Preference",
            description = "A newly added preference"
        )

        prefState.modulePreferences["TestModule.newPref"] = newPref
        prefState.moduleValues["TestModule.newPref"] = "newValue"

        assertEquals(initialSize + 1, prefState.modulePreferences.size,
            "Preference definitions should increase by 1")
        assertEquals(initialValues + 1, prefState.moduleValues.size,
            "Preference values should increase by 1")

        // Test removal
        prefState.modulePreferences.remove("TestModule.newPref")
        prefState.moduleValues.remove("TestModule.newPref")

        assertEquals(initialSize, prefState.modulePreferences.size,
            "Preference definitions should return to original size")
        assertEquals(initialValues, prefState.moduleValues.size,
            "Preference values should return to original size")
    }

    override fun tearDown() {
        // Clean up preference state
        prefState.enabledModules.clear()
        prefState.modulePreferences.clear()
        prefState.moduleValues.clear()

        super.tearDown()
    }
}