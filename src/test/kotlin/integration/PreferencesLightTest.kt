package integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.sections.ConfigurationSection
import org.junit.jupiter.api.Assertions
import javax.swing.JLabel
import javax.swing.JPanel

class PreferencesLightTest : BasePlatformTestCase() {
    fun testConfigurationPanelIsBuiltCorrectly() {
        val section = ConfigurationSection()
        val builder = FormBuilder.createFormBuilder()
        val fields = mutableListOf<StateValueField<*>>()

        section.applyTo(builder, fields)
        val panel: JPanel = builder.panel

        Assertions.assertNotNull(panel, "Panel should not be null")
        Assertions.assertTrue(panel.componentCount > 0, "Panel should contain components")

        // Optionally, test that key UI elements exist
        val labels = UIUtil.findComponentsOfType(panel, JLabel::class.java)
        val containsAppPrefs = labels.any { it.text?.contains("Application Preferences") == true }

        Assertions.assertTrue(containsAppPrefs, "Expected 'Application Preferences' section")
    }
}
