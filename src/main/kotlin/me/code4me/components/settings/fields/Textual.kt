package me.code4me.components.settings.fields

import com.intellij.ui.components.JBPasswordField


/*
* This class is used to represent a field that contains a password.
* It extends the JBPasswordField class and implements the TextualStateValueField interface.
* It provides methods to get and set the state of the field, as well as to get and set the value of the field.
* */
abstract class CredentialField (
    protected val field: JBPasswordField
) : TextualStateValueField {
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

    override fun getValue(): String {
        return String(field.password)
    }

    override fun setValue(value: String) {
        field.text = value
    }
}


/*
* This class is used to represent a simple text field.
* It extends the swing JTextField class and implements the TextualStateValueField interface.
 */
abstract class TextField(
    protected val field: com.intellij.ui.components.JBTextField
) : TextualStateValueField {
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

    override fun getValue(): String {
        return field.text
    }

    override fun setValue(value: String) {
        field.text = value
    }
}

