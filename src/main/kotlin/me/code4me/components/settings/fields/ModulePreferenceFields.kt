
package me.code4me.components.settings.fields

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTextArea
import me.code4me.services.state.PrefState
import me.code4me.utils.configuration.Preference
import javax.swing.JComponent

/**
 * StateValueField implementation for module boolean preferences.
 */
class ModuleBooleanPreferenceField(
    private val moduleId: String,
    private val preference: Preference,
    private val checkbox: JBCheckBox,
) : StateValueField<Boolean> {
    private val fieldInfo: MutableList<FieldInfo> = mutableListOf()

    override fun getComponent(): JComponent = checkbox

    override fun getFieldValue(): Boolean = checkbox.isSelected

    override fun setFieldValue(value: Boolean) {
        checkbox.isSelected = value
    }

    override fun getStateValue(): Boolean {
        val value = PrefState.getPreferenceValue(moduleId, preference.key)
        return value?.toBoolean() ?: preference.defaultValue.toBoolean()
    }

    override fun setStateValue(value: Boolean) {
        checkbox.isSelected = value
        PrefState.setPreferenceValue(moduleId, preference.key, value.toString())
    }

    override fun getFieldInfo(): MutableList<FieldInfo> = fieldInfo
}

/**
 * StateValueField implementation for module string preferences.
 */
class ModuleStringPreferenceField(
    private val moduleId: String,
    private val preference: Preference,
    private val textField: JBTextField,
) : StateValueField<String> {
    private val fieldInfo: MutableList<FieldInfo> = mutableListOf()

    override fun getComponent(): JComponent = textField

    override fun getFieldValue(): String = textField.text

    override fun setFieldValue(value: String) {
        textField.text = value
    }

    override fun getStateValue(): String {
        return PrefState.getPreferenceValue(moduleId, preference.key) ?: preference.defaultValue
    }

    override fun setStateValue(value: String) {
        textField.text = value
        PrefState.setPreferenceValue(moduleId, preference.key, value)
    }

    override fun getFieldInfo(): MutableList<FieldInfo> = fieldInfo
}

/**
 * StateValueField implementation for module text area preferences.
 */
class ModuleTextPreferenceField(
    private val moduleId: String,
    private val preference: Preference,
    private val textArea: JBTextArea,
) : StateValueField<String> {
    private val fieldInfo: MutableList<FieldInfo> = mutableListOf()

    override fun getComponent(): JComponent = textArea

    override fun getFieldValue(): String = textArea.text ?: ""

    override fun setFieldValue(value: String) {
        textArea.text = value
    }

    override fun getStateValue(): String {
        return PrefState.getPreferenceValue(moduleId, preference.key) ?: preference.defaultValue
    }

    override fun setStateValue(value: String) {
        textArea.text = value
        PrefState.setPreferenceValue(moduleId, preference.key, value)
    }

    override fun getFieldInfo(): MutableList<FieldInfo> = fieldInfo
}

/**
 * StateValueField implementation for module list preferences.
 * Handles comma-separated lists where the first item is the selected value.
 */
class ModuleListPreferenceField(
    private val moduleId: String,
    private val preference: Preference,
    private val comboBox: ComboBox<String>,
    private val options: List<String>
) : StateValueField<String> {
    private val fieldInfo: MutableList<FieldInfo> = mutableListOf()

    override fun getComponent(): JComponent = comboBox

    override fun getFieldValue(): String {
        val selected = comboBox.selectedItem?.toString() ?: ""
        // Place selected value first, then other options
        val remainingOptions = options.filter { it != selected }
        return if (selected.isNotEmpty()) {
            listOf(selected) + remainingOptions
        } else {
            options
        }.joinToString(",")
    }

    override fun setFieldValue(value: String) {
        val values = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val selectedValue = values.firstOrNull() ?: ""
        comboBox.selectedItem = selectedValue
    }

    override fun getStateValue(): String {
        return PrefState.getPreferenceValue(moduleId, preference.key) ?: preference.defaultValue
    }

    override fun setStateValue(value: String) {
        setFieldValue(value)
        PrefState.setPreferenceValue(moduleId, preference.key, getFieldValue())
    }

    override fun getFieldInfo(): MutableList<FieldInfo> = fieldInfo
}

/**
 * StateValueField implementation for module integer preferences.
 */
class ModuleIntegerPreferenceField(
    private val moduleId: String,
    private val preference: Preference,
    private val textField: JBTextField,
) : StateValueField<Int> {
    private val fieldInfo: MutableList<FieldInfo> = mutableListOf()

    override fun getComponent(): JComponent = textField

    override fun getFieldValue(): Int {
        return textField.text.toIntOrNull() ?: preference.defaultValue.toInt()
    }

    override fun setFieldValue(value: Int) {
        textField.text = value.toString()
    }

    override fun getStateValue(): Int {
        val value = PrefState.getPreferenceValue(moduleId, preference.key)
        return value?.toIntOrNull() ?: preference.defaultValue.toInt()
    }

    override fun setStateValue(value: Int) {
        textField.text = value.toString()
        PrefState.setPreferenceValue(moduleId, preference.key, value.toString())
    }

    override fun getFieldInfo(): MutableList<FieldInfo> = fieldInfo
}

/**
 * StateValueField implementation for module float preferences.
 */
class ModuleFloatPreferenceField(
    private val moduleId: String,
    private val preference: Preference,
    private val textField: JBTextField,
) : StateValueField<Float> {
    private val fieldInfo: MutableList<FieldInfo> = mutableListOf()

    override fun getComponent(): JComponent = textField

    override fun getFieldValue(): Float {
        return textField.text.toFloatOrNull() ?: preference.defaultValue.toFloat()
    }

    override fun setFieldValue(value: Float) {
        textField.text = value.toString()
    }

    override fun getStateValue(): Float {
        val value = PrefState.getPreferenceValue(moduleId, preference.key)
        return value?.toFloatOrNull() ?: preference.defaultValue.toFloat()
    }

    override fun setStateValue(value: Float) {
        textField.text = value.toString()
        PrefState.setPreferenceValue(moduleId, preference.key, value.toString())
    }

    override fun getFieldInfo(): MutableList<FieldInfo> = fieldInfo
}