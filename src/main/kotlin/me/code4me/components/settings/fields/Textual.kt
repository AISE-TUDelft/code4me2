package me.code4me.components.settings.fields

import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField

/**
* This class is used to represent a field that contains a password.
* It extends the JBPasswordField class and implements the TextualStateValueField interface.
* It provides methods to get and set the state of the field, as well as to get and set the value of the field.
* */
abstract class CredentialField(
    protected val field: JBPasswordField,
) : TextualStateValueField {
    private val fieldInfo: MutableList<FieldInfo> = mutableListOf()

    override fun getFieldInfo(): MutableList<FieldInfo> {
        return fieldInfo
    }

    override fun getFieldValue(): String {
        return String(field.password)
    }

    override fun setFieldValue(value: String) {
        field.text = value
    }

    override fun getComponent(): JBPasswordField {
        return field
    }
}

/*
* This class is used to represent a simple text field.
* It extends the swing JTextField class and implements the TextualStateValueField interface.
 */
abstract class TextField(
    protected val field: JBTextField,
) : TextualStateValueField {
    private val fieldInfo: MutableList<FieldInfo> = mutableListOf()

    override fun getFieldInfo(): MutableList<FieldInfo> {
        return fieldInfo
    }

    override fun getFieldValue(): String {
        return field.text
    }

    override fun setFieldValue(value: String) {
        field.text = value
    }

    override fun getComponent(): JBTextField {
        return field
    }
}
