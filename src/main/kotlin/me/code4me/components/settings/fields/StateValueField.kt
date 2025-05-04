package me.code4me.components.settings.fields

import javax.swing.JComponent

interface StateValueField<T> {
    fun getFieldValue(): T

    fun setFieldValue(value: T)

    fun getStateValue(): T?

    fun setStateValue(value: T)

    // additional functionality for later on to decide whether a field is enabled or not
    fun getFieldInfo() : MutableList<FieldInfo>

    fun getComponent() : JComponent
}

typealias TextualStateValueField = StateValueField<String>
typealias BooleanStateValueField = StateValueField<Boolean>