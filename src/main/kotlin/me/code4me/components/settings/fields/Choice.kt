package me.code4me.components.settings.fields

import javax.swing.JCheckBox

/**
 * This class is used to represent a field that contains a boolean value as a checkbox.
 * It extends the JCheckBox class and implements the BooleanStateValueField interface.
 * It provides methods to get and set the state of the field, as well as to get and set the value of the field.
 */
abstract class CheckBoxField(
    protected val field: JCheckBox
) : BooleanStateValueField {
    override fun getState(): FieldStates {
        return if (field.isEnabled) {
            FieldStates.ACTIVE
        } else {
            FieldStates.INACTIVE
        }
    }

    override fun setState(state: FieldStates) {
        field.isEnabled = state == FieldStates.ACTIVE
    }

    override fun getValue(): Boolean {
        return field.isSelected
    }

    override fun setValue(value: Boolean) {
        field.isSelected = value
    }
}

/**
 * This class is used to represent a field that contains a boolean value as a toggle button.
 * it extends the swing JToggleButton class and implements the BooleanStateValueField interface.
 * It provides methods to get and set the state of the field, as well as to get and set the value of the field.
 */
abstract class ToggleButtonField(
    protected val field: javax.swing.JToggleButton
) : BooleanStateValueField {
    override fun getState(): FieldStates {
        return if (field.isEnabled) {
            FieldStates.ACTIVE
        } else {
            FieldStates.INACTIVE
        }
    }

    override fun setState(state: FieldStates) {
        field.isEnabled = state == FieldStates.ACTIVE
    }

    override fun getValue(): Boolean {
        return field.isSelected
    }

    override fun setValue(value: Boolean) {
        field.isSelected = value
    }
}