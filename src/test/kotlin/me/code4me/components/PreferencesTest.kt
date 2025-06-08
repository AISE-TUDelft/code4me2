package me.code4me.components

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.sections.ConfigurationSection
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import javax.swing.JPanel

class PreferencesTest : BasePlatformTestCase() {

    fun testConfigurationPanelIsBuiltCorrectly() {
        val section = ConfigurationSection()
        val builder = FormBuilder.createFormBuilder()
        val fields = mutableListOf<StateValueField<*>>()

        section.applyTo(builder, fields)
        val panel: JPanel = builder.panel

        assertNotNull(panel, "Panel should not be null")
        assertTrue(panel.componentCount > 0, "Panel should contain components")

        // Optionally, test that key UI elements exist
        val labels = UIUtil.findComponentsOfType(panel, javax.swing.JLabel::class.java)
        val containsAppPrefs = labels.any { it.text?.contains("Application Preferences") == true }

        assertTrue(containsAppPrefs, "Expected 'Application Preferences' section")
    }
}
