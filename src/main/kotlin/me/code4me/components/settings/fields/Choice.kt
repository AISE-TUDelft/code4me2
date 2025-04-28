package me.code4me.components.settings.fields

import javax.swing.JCheckBox
import javax.swing.JToggleButton

/**
 * This class is used to represent a field that contains a boolean value as a checkbox.
 * It extends the JCheckBox class and implements the BooleanStateValueField interface.
 * It provides methods to get and set the state of the field, as well as to get and set the value of the field.
 */
abstract class CheckBoxField(
    protected val field: JCheckBox
) : BooleanStateValueField {
    private val fieldInfo: MutableList<FieldInfo> = mutableListOf()

    override fun getFieldInfo(): MutableList<FieldInfo> {
        return fieldInfo
    }

    override fun getFieldValue(): Boolean {
        return field.isSelected
    }

    override fun setFieldValue(value: Boolean) {
        field.isSelected = value
    }

    override fun getComponent(): JCheckBox {
        return field
    }
}

/**
 * This class is used to represent a field that contains a boolean value as a toggle button.
 * it extends the swing JToggleButton class and implements the BooleanStateValueField interface.
 * It provides methods to get and set the state of the field, as well as to get and set the value of the field.
 */
abstract class ToggleButtonField(
    protected val field: JToggleButton
) : BooleanStateValueField {
    private val fieldInfo: MutableList<FieldInfo> = mutableListOf()

    override fun getFieldInfo(): MutableList<FieldInfo> {
        return fieldInfo
    }

    override fun getFieldValue(): Boolean {
        return field.isSelected
    }

    override fun setFieldValue(value: Boolean) {
        field.isSelected = value
    }

    override fun getComponent(): JToggleButton {
        return field
    }
}