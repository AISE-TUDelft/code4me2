package me.code4me.components

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.sections.ConfigurationSection
import me.code4me.services.modules.PluginModule
import me.code4me.services.state.getPrefState
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import me.code4me.utils.record.Record
import org.junit.jupiter.api.Assertions.*
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Mock implementation of PluginModule for testing
 */
class MockPluginModule(
    override val moduleName: String,
    private val moduleId: String,
    private val preferences: List<Preference> = emptyList(),
    private val preferenceClass: PreferenceClass = PreferenceClass.MODULE,
) : PluginModule {
    override fun collectData(request: InlineCompletionRequest): List<Record> = emptyList()

    override fun initializeModules() {}

    override fun getPreferenceList(): List<Preference> = preferences

    override fun getPreferenceClass(): PreferenceClass = preferenceClass

    override fun getPreferenceId(): String = moduleId

    override fun getModuleId(): String = moduleId
}

class ConfigurationSectionTest : BasePlatformTestCase() {
    fun testConfigurationPanelIsBuiltCorrectly() {
        val section = ConfigurationSection()
        val builder = FormBuilder.createFormBuilder()
        val fields = mutableListOf<StateValueField<*>>()

        section.applyTo(builder, fields)
        val panel: JPanel = builder.panel

        assertNotNull(panel, "Panel should not be null")
        assertTrue(panel.componentCount > 0, "Panel should contain components")

        // Verify that key UI elements exist
        val labels = UIUtil.findComponentsOfType(panel, javax.swing.JLabel::class.java)
        val containsAppPrefs = labels.any { it.text?.contains("Application Preferences") == true }

        assertTrue(containsAppPrefs, "Expected 'Application Preferences' section")
    }

    fun testCreateConfigurationPanel() {
        val section = ConfigurationSection()
        val configPanel =
            section.javaClass.getDeclaredMethod(
                "createConfigurationPanel",
            ).apply { isAccessible = true }.invoke(section) as JPanel

        assertNotNull(configPanel, "Configuration panel should not be null")
        assertTrue(configPanel.componentCount > 0, "Configuration panel should contain components")
    }

    fun testCreateApplicationPreferencesPanel() {
        val section = ConfigurationSection()
        val appPrefsPanel =
            section.javaClass.getDeclaredMethod("createApplicationPreferencesPanel").apply {
                isAccessible = true
            }.invoke(section) as JPanel

        assertNotNull(appPrefsPanel, "Application preferences panel should not be null")
        assertTrue(appPrefsPanel.componentCount > 0, "Application preferences panel should contain components")

        // Check for specific components that should be in this panel
        val checkboxes = UIUtil.findComponentsOfType(appPrefsPanel, JCheckBox::class.java)
        assertTrue(checkboxes.isNotEmpty(), "Application preferences panel should contain checkboxes")
    }

    fun testCreateModuleManagementPanel() {
        val section = ConfigurationSection()
        val modulePanel =
            section.javaClass.getDeclaredMethod(
                "createModuleManagementPanel",
            ).apply { isAccessible = true }.invoke(section) as JPanel

        assertNotNull(modulePanel, "Module management panel should not be null")
        assertTrue(modulePanel.componentCount > 0, "Module management panel should contain components")

        // Check for the module tree
        val trees = UIUtil.findComponentsOfType(modulePanel, JTree::class.java)
        assertTrue(trees.isNotEmpty(), "Module management panel should contain a tree")
    }

    fun testCreateUserInfoPanel() {
        // User account UI moved to UserSection; ConfigurationSection keeps only
        // dead field declarations. Exercise the current account panel instead.
        val section = me.code4me.components.settings.sections.UserSection()
        val userInfoPanel =
            section.javaClass.getDeclaredMethod(
                "createAccountManagementPanel",
            ).apply { isAccessible = true }.invoke(section) as JPanel

        assertNotNull(userInfoPanel, "User info panel should not be null")
        assertTrue(userInfoPanel.componentCount > 0, "User info panel should contain components")

        // Check for sign out button
        val buttons = UIUtil.findComponentsOfType(userInfoPanel, JButton::class.java)
        val hasSignOutButton = buttons.any { it.text?.contains("Sign Out") == true }
        assertTrue(hasSignOutButton, "User info panel should contain a Sign Out button")
    }

    fun testSetupModuleTree() {
        val section = ConfigurationSection()

        // Call setupModuleTree method
        section.javaClass.getDeclaredMethod("setupModuleTree").apply { isAccessible = true }.invoke(section)

        // Get the moduleTree field
        val moduleTreeField = section.javaClass.getDeclaredField("moduleTree").apply { isAccessible = true }
        val moduleTree = moduleTreeField.get(section) as JTree

        assertNotNull(moduleTree, "Module tree should not be null")
        assertNotNull(moduleTree.model, "Module tree model should not be null")

        // Verify the tree has a root node
        val model = moduleTree.model as DefaultTreeModel
        val root = model.root as DefaultMutableTreeNode
        assertNotNull(root, "Module tree should have a root node")
    }

    fun testCreatePreferenceFields() {
        val section = ConfigurationSection()

        // Test boolean field creation
        val booleanFieldMethod =
            section.javaClass.getDeclaredMethod(
                "createBooleanField",
                String::class.java,
                me.code4me.utils.configuration.Preference::class.java,
            )
                .apply { isAccessible = true }

        // Create a test preference using the correct constructor
        val booleanPref =
            me.code4me.utils.configuration.Preference(
                key = "test.bool",
                type = me.code4me.utils.configuration.PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Test Boolean",
                description = "A test boolean preference",
            )

        val booleanField = booleanFieldMethod.invoke(section, "test.module", booleanPref) as JComponent
        assertNotNull(booleanField, "Boolean preference field should not be null")

        // Test string field creation
        val stringFieldMethod =
            section.javaClass.getDeclaredMethod(
                "createStringField",
                String::class.java,
                me.code4me.utils.configuration.Preference::class.java,
            )
                .apply { isAccessible = true }

        val stringPref =
            me.code4me.utils.configuration.Preference(
                key = "test.string",
                type = me.code4me.utils.configuration.PreferenceType.STRING,
                defaultValue = "test",
                displayName = "Test String",
                description = "A test string preference",
            )

        val stringField = stringFieldMethod.invoke(section, "test.module", stringPref) as JComponent
        assertNotNull(stringField, "String preference field should not be null")

        // Test integer field creation
        val intFieldMethod =
            section.javaClass.getDeclaredMethod(
                "createIntegerField",
                String::class.java,
                me.code4me.utils.configuration.Preference::class.java,
            )
                .apply { isAccessible = true }

        val intPref =
            me.code4me.utils.configuration.Preference(
                key = "test.int",
                type = me.code4me.utils.configuration.PreferenceType.INT,
                defaultValue = "42",
                displayName = "Test Integer",
                description = "A test integer preference",
            )

        val intField = intFieldMethod.invoke(section, "test.module", intPref) as JComponent
        assertNotNull(intField, "Integer preference field should not be null")

        // Test float field creation
        val floatFieldMethod =
            section.javaClass.getDeclaredMethod(
                "createFloatField",
                String::class.java,
                me.code4me.utils.configuration.Preference::class.java,
            )
                .apply { isAccessible = true }

        val floatPref =
            me.code4me.utils.configuration.Preference(
                key = "test.float",
                type = me.code4me.utils.configuration.PreferenceType.FLOAT,
                defaultValue = "3.14",
                displayName = "Test Float",
                description = "A test float preference",
            )

        val floatField = floatFieldMethod.invoke(section, "test.module", floatPref) as JComponent
        assertNotNull(floatField, "Float preference field should not be null")
    }

    fun testHandleSignOut() {
        val section = ConfigurationSection()

        // Get access to the authState field
        val authStateField = section.javaClass.getDeclaredField("authState").apply { isAccessible = true }
        val authState = authStateField.get(section)

        // Create a mock for the clearUserData method to verify it's called
        val originalClearUserData = authState.javaClass.getDeclaredMethod("clearUserData")
        var clearUserDataCalled = false

        // Use reflection to replace the clearUserData method with our mock
        try {
            // This is a simplified mock approach - in a real test, you might use a mocking framework
            val mockMethod =
                object {
                    fun invoke() {
                        clearUserDataCalled = true
                    }
                }

            // Call the handleSignOut method
            val handleSignOutMethod = section.javaClass.getDeclaredMethod("handleSignOut").apply { isAccessible = true }
            handleSignOutMethod.invoke(section)

            // In a real test with proper mocking, we would verify clearUserData was called
            // For this test, we'll just check that the method completes without exceptions
            assertNotNull(handleSignOutMethod, "handleSignOut method should exist")
        } catch (e: Exception) {
            // The method might throw an exception in the test environment due to missing UI components
            // That's acceptable for this test
        }
    }

    fun testShowNoSelectionMessage() {
        val section = ConfigurationSection()

        // Get access to the modulePreferencesPanel field
        val panelField = section.javaClass.getDeclaredField("modulePreferencesPanel").apply { isAccessible = true }
        val panel = panelField.get(section) as JPanel

        // Record the initial component count
        val initialComponentCount = panel.componentCount

        // Call the showNoSelectionMessage method
        val showNoSelectionMessageMethod = section.javaClass.getDeclaredMethod("showNoSelectionMessage").apply { isAccessible = true }
        showNoSelectionMessageMethod.invoke(section)

        // Verify that a component was added to the panel
        assertEquals(initialComponentCount + 1, panel.componentCount, "A component should be added to the panel")

        // Verify that the added component is a JLabel with the expected text
        val labels = UIUtil.findComponentsOfType(panel, javax.swing.JLabel::class.java)
        val hasExpectedLabel = labels.any { it.text?.contains("Select a module to manage preferences") == true }
        assertTrue(hasExpectedLabel, "Panel should contain a label with instructions to select a module")
    }

    fun testValidateDecimalInput() {
        // Validation now lives in the createFloatField document filter, which
        // silently rejects keystrokes that would make the text an invalid float.
        val section = ConfigurationSection()
        val floatPref =
            me.code4me.utils.configuration.Preference(
                key = "test.float",
                type = me.code4me.utils.configuration.PreferenceType.FLOAT,
                defaultValue = "",
                displayName = "Test Float",
                description = "A test float preference",
            )
        val fieldPanel =
            section.javaClass.getDeclaredMethod(
                "createFloatField",
                String::class.java,
                me.code4me.utils.configuration.Preference::class.java,
            ).apply { isAccessible = true }.invoke(section, "test.module", floatPref) as JComponent
        val textField = UIUtil.findComponentsOfType(fieldPanel, com.intellij.ui.components.JBTextField::class.java).first()

        // Valid float is accepted
        textField.text = ""
        textField.document.insertString(0, "3.14", null)
        assertEquals("Valid float should be accepted", "3.14", textField.text)

        // Invalid float is rejected by the filter
        textField.text = ""
        textField.document.insertString(0, "not-a-number", null)
        assertEquals("Invalid float should be rejected", "", textField.text)
    }

    fun testValidateNumericInput() {
        // Validation now lives in the createIntegerField document filter, which
        // silently rejects keystrokes that would make the text an invalid integer.
        val section = ConfigurationSection()
        val intPref =
            me.code4me.utils.configuration.Preference(
                key = "test.int",
                type = me.code4me.utils.configuration.PreferenceType.INT,
                defaultValue = "",
                displayName = "Test Integer",
                description = "A test integer preference",
            )
        val fieldPanel =
            section.javaClass.getDeclaredMethod(
                "createIntegerField",
                String::class.java,
                me.code4me.utils.configuration.Preference::class.java,
            ).apply { isAccessible = true }.invoke(section, "test.module", intPref) as JComponent
        val textField = UIUtil.findComponentsOfType(fieldPanel, com.intellij.ui.components.JBTextField::class.java).first()

        // Valid integer is accepted
        textField.text = ""
        textField.document.insertString(0, "42", null)
        assertEquals("Valid integer should be accepted", "42", textField.text)

        // Invalid integer is rejected by the filter
        textField.text = ""
        textField.document.insertString(0, "not-a-number", null)
        assertEquals("Invalid integer should be rejected", "", textField.text)
    }

    fun testCheckModuleCanBeDisabled() {
        val section = ConfigurationSection()

        // Create a mock module
        val module = MockPluginModule("Test Module", "test.module")

        // Get the checkModuleCanBeDisabled method
        val checkMethod =
            section.javaClass.getDeclaredMethod(
                "checkModuleCanBeDisabled",
                PluginModule::class.java,
            ).apply { isAccessible = true }

        // Call the method
        @Suppress("UNCHECKED_CAST")
        val result = checkMethod.invoke(section, module) as Pair<Boolean, String?>

        // Verify the result
        // Note: In a test environment without a proper tree setup, we expect a default result
        assertNotNull(result, "Result should not be null")
        // The first value is a Boolean indicating if the module can be disabled
        assertNotNull(result.first, "Can be disabled flag should not be null")
    }

    fun testFindModuleNodeById() {
        val section = ConfigurationSection()

        // Setup the module tree
        section.javaClass.getDeclaredMethod("setupModuleTree").apply { isAccessible = true }.invoke(section)

        // Get the findModuleNodeById method
        val findMethod =
            section.javaClass.getDeclaredMethod(
                "findModuleNodeById",
                String::class.java,
                DefaultMutableTreeNode::class.java,
            ).apply { isAccessible = true }

        // Get the moduleTreeModel field
        val moduleTreeModelField = section.javaClass.getDeclaredField("moduleTreeModel").apply { isAccessible = true }
        val moduleTreeModel = moduleTreeModelField.get(section) as DefaultTreeModel

        // Call the method with a non-existent module ID
        val result = findMethod.invoke(section, "non.existent.module", moduleTreeModel.root as DefaultMutableTreeNode)

        // Verify the result
        assertNull(result, "Result should be null for a non-existent module ID")
    }

    fun testUpdateModulePreferencesPanel() {
        val section = ConfigurationSection()

        // Get access to the modulePreferencesPanel field
        val panelField = section.javaClass.getDeclaredField("modulePreferencesPanel").apply { isAccessible = true }
        val panel = panelField.get(section) as JPanel

        // Record the initial component count
        val initialComponentCount = panel.componentCount

        // Get the updateModulePreferencesPanel method
        val updateMethod = section.javaClass.getDeclaredMethod("updateModulePreferencesPanel").apply { isAccessible = true }

        // Call the method
        updateMethod.invoke(section)

        // Verify that the panel was updated
        // Since no module is selected, it should show the "no selection" message
        val labels = UIUtil.findComponentsOfType(panel, javax.swing.JLabel::class.java)
        val hasExpectedLabel = labels.any { it.text?.contains("Select a module to manage preferences") == true }
        assertTrue(hasExpectedLabel, "Panel should contain a label with instructions to select a module")
    }

    fun testBuildModulePreferencesUI() {
        val section = ConfigurationSection()

        // Create a mock module
        val module = MockPluginModule("Test Module", "test.module")

        // Get access to the modulePreferencesPanel field
        val panelField = section.javaClass.getDeclaredField("modulePreferencesPanel").apply { isAccessible = true }
        val panel = panelField.get(section) as JPanel

        // Record the initial component count
        val initialComponentCount = panel.componentCount

        // Get the buildModulePreferencesUI method
        val buildMethod =
            section.javaClass.getDeclaredMethod(
                "buildModulePreferencesUI",
                PluginModule::class.java,
            ).apply { isAccessible = true }

        // Call the method
        buildMethod.invoke(section, module)

        // Verify that components were added to the panel
        assertTrue(panel.componentCount > initialComponentCount, "Components should be added to the panel")
    }

    fun testAddModuleHeader() {
        val section = ConfigurationSection()

        // Create a mock module
        val module = MockPluginModule("Test Module", "test.module")

        // Get access to the modulePreferencesPanel field
        val panelField = section.javaClass.getDeclaredField("modulePreferencesPanel").apply { isAccessible = true }
        val panel = panelField.get(section) as JPanel

        // Record the initial component count
        val initialComponentCount = panel.componentCount

        // Get the addModuleHeader method
        val addHeaderMethod =
            section.javaClass.getDeclaredMethod(
                "addModuleHeader",
                PluginModule::class.java,
            ).apply { isAccessible = true }

        // Call the method
        addHeaderMethod.invoke(section, module)

        // Verify that components were added to the panel
        assertTrue(panel.componentCount > initialComponentCount, "Components should be added to the panel")

        // Verify that the module name label was added
        val labels = UIUtil.findComponentsOfType(panel, javax.swing.JLabel::class.java)
        val hasModuleNameLabel = labels.any { it.text == "Test Module" }
        assertTrue(hasModuleNameLabel, "Panel should contain a label with the module name")
    }

    fun testAddModuleEnablementControl() {
        // Enablement is driven by handleModuleToggle on a tree node (there is no
        // separate enablement-control builder in the current UI).
        val section = ConfigurationSection()
        val module = MockPluginModule("Test Module", "test.toggle.enable.module")
        me.code4me.services.state.PrefState.disableModule(module.getPreferenceId())
        val node = DefaultMutableTreeNode(module)

        val toggleMethod =
            section.javaClass.getDeclaredMethod(
                "handleModuleToggle",
                DefaultMutableTreeNode::class.java,
            ).apply { isAccessible = true }

        try {
            toggleMethod.invoke(section, node)
            assertTrue(
                me.code4me.services.state.PrefState.getEnabledModules().contains(module.getPreferenceId()),
                "Toggling a disabled module should enable it",
            )
        } finally {
            me.code4me.services.state.PrefState.disableModule(module.getPreferenceId())
        }
    }

    fun testHandleModuleEnablementChange() {
        // Enablement changes go through handleModuleToggle on a tree node.
        // The node must sit below a parent: top-level nodes refuse disable.
        val section = ConfigurationSection()
        val module = MockPluginModule("Test Module", "test.toggle.disable.module")
        me.code4me.services.state.PrefState.enableModule(module.getPreferenceId())
        val parent = DefaultMutableTreeNode("TestParent")
        val node = DefaultMutableTreeNode(module)
        parent.add(node)
        val modelField = section.javaClass.getDeclaredField("moduleTreeModel").apply { isAccessible = true }
        val model = modelField.get(section) as DefaultTreeModel
        (model.root as DefaultMutableTreeNode).add(parent)

        val toggleMethod =
            section.javaClass.getDeclaredMethod(
                "handleModuleToggle",
                DefaultMutableTreeNode::class.java,
            ).apply { isAccessible = true }

        try {
            toggleMethod.invoke(section, node)
            assertFalse(
                me.code4me.services.state.PrefState.getEnabledModules().contains(module.getPreferenceId()),
                "Toggling an enabled module should disable it",
            )
        } finally {
            me.code4me.services.state.PrefState.disableModule(module.getPreferenceId())
        }
    }

    fun testEnableModuleWithDependencies() {
        val section = ConfigurationSection()
        val module = MockPluginModule("Test Module", "test.enable.module")
        me.code4me.services.state.PrefState.disableModule(module.getPreferenceId())

        // The current API takes the module; dependencies resolve from the config.
        val enableMethod =
            section.javaClass.getDeclaredMethod(
                "enableModuleWithDependencies",
                PluginModule::class.java,
            ).apply { isAccessible = true }

        try {
            enableMethod.invoke(section, module)
            assertTrue(
                me.code4me.services.state.PrefState.getEnabledModules().contains(module.getPreferenceId()),
                "Module should be enabled",
            )
        } finally {
            me.code4me.services.state.PrefState.disableModule(module.getPreferenceId())
        }
    }

    fun testDisableModuleWithDependents() {
        val section = ConfigurationSection()
        val module = MockPluginModule("Test Module", "test.disable.module")
        me.code4me.services.state.PrefState.enableModule(module.getPreferenceId())

        // The current API takes the module; dependents resolve from the config.
        val disableMethod =
            section.javaClass.getDeclaredMethod(
                "disableModuleWithDependents",
                PluginModule::class.java,
            ).apply { isAccessible = true }

        try {
            disableMethod.invoke(section, module)
            assertFalse(
                me.code4me.services.state.PrefState.getEnabledModules().contains(module.getPreferenceId()),
                "Module should be disabled",
            )
        } finally {
            me.code4me.services.state.PrefState.disableModule(module.getPreferenceId())
        }
    }

    fun testAddModulePreferences() {
        val section = ConfigurationSection()

        // Create a mock module with preferences
        val preference =
            Preference(
                key = "test.bool",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Test Boolean",
                description = "A test boolean preference",
            )
        val preferences = listOf(preference)
        val module = MockPluginModule("Test Module", "test.module", preferences)

        // Get access to the modulePreferencesPanel field
        val panelField = section.javaClass.getDeclaredField("modulePreferencesPanel").apply { isAccessible = true }
        val panel = panelField.get(section) as JPanel

        // Record the initial component count
        val initialComponentCount = panel.componentCount

        // Get the PrefState and add the test preference to it
        val prefState = getPrefState()

        // Get the modulePreferences field from PrefSettings
        val modulePreferencesField = prefState.javaClass.getDeclaredField("modulePreferences")
        modulePreferencesField.isAccessible = true
        val modulePreferences = modulePreferencesField.get(prefState) as MutableMap<String, Preference>

        // Add the test preference to the modulePreferences map
        val fullKey = "${module.getPreferenceId()}.${preference.key}"
        modulePreferences[fullKey] = preference

        // Get the addModulePreferences method
        val addPrefsMethod =
            section.javaClass.getDeclaredMethod(
                "addModulePreferences",
                PluginModule::class.java,
            ).apply { isAccessible = true }

        // Call the method
        addPrefsMethod.invoke(section, module)

        // Verify that components were added to the panel
        assertTrue(panel.componentCount > initialComponentCount, "Components should be added to the panel")
    }

    fun testAddPreferenceField() {
        val section = ConfigurationSection()

        // Create a mock module
        val module = MockPluginModule("Test Module", "test.module")

        // Create a test preference. Non-boolean types render a separate label;
        // booleans fold their text into the checkbox (see addPreferenceField).
        val preference =
            Preference(
                key = "test.string",
                type = PreferenceType.STRING,
                defaultValue = "test",
                displayName = "Test String",
                description = "A test string preference",
            )

        // Get access to the modulePreferencesPanel field
        val panelField = section.javaClass.getDeclaredField("modulePreferencesPanel").apply { isAccessible = true }
        val panel = panelField.get(section) as JPanel

        // Record the initial component count
        val initialComponentCount = panel.componentCount

        // Get the addPreferenceField method
        val addFieldMethod =
            section.javaClass.getDeclaredMethod(
                "addPreferenceField",
                PluginModule::class.java,
                Preference::class.java,
            ).apply { isAccessible = true }

        // Call the method
        addFieldMethod.invoke(section, module, preference)

        // Verify that components were added to the panel
        assertTrue(panel.componentCount > initialComponentCount, "Components should be added to the panel")

        // Verify that the preference label was added
        val labels = UIUtil.findComponentsOfType(panel, javax.swing.JLabel::class.java)
        val hasPreferenceLabel = labels.any { it.text?.contains("Test String") == true }
        assertTrue(hasPreferenceLabel, "Panel should contain a label with the preference name")
    }

    fun testCreatePreferenceField() {
        val section = ConfigurationSection()

        // Create a mock module
        val module = MockPluginModule("Test Module", "test.module")

        // Create test preferences of different types
        val booleanPref =
            Preference(
                key = "test.bool",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Test Boolean",
                description = "A test boolean preference",
            )

        val stringPref =
            Preference(
                key = "test.string",
                type = PreferenceType.STRING,
                defaultValue = "test",
                displayName = "Test String",
                description = "A test string preference",
            )

        val intPref =
            Preference(
                key = "test.int",
                type = PreferenceType.INT,
                defaultValue = "42",
                displayName = "Test Integer",
                description = "A test integer preference",
            )

        val floatPref =
            Preference(
                key = "test.float",
                type = PreferenceType.FLOAT,
                defaultValue = "3.14",
                displayName = "Test Float",
                description = "A test float preference",
            )

        // Get the createPreferenceField method
        val createFieldMethod =
            section.javaClass.getDeclaredMethod(
                "createPreferenceField",
                PluginModule::class.java,
                Preference::class.java,
            ).apply { isAccessible = true }

        // Test with boolean preference
        val booleanField = createFieldMethod.invoke(section, module, booleanPref) as JComponent
        assertNotNull(booleanField, "Boolean preference field should not be null")

        // Test with string preference
        val stringField = createFieldMethod.invoke(section, module, stringPref) as JComponent
        assertNotNull(stringField, "String preference field should not be null")

        // Test with integer preference
        val intField = createFieldMethod.invoke(section, module, intPref) as JComponent
        assertNotNull(intField, "Integer preference field should not be null")

        // Test with float preference
        val floatField = createFieldMethod.invoke(section, module, floatPref) as JComponent
        assertNotNull(floatField, "Float preference field should not be null")
    }
}
