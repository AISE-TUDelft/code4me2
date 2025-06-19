package me.code4me.components.settings.fields

import javax.swing.JComponent

/**
 * Interface for settings fields that manage both UI component values and persistent state.
 *
 * Provides a contract for settings fields that need to synchronize between what's displayed
 * in the UI component and what's stored in persistent configuration. Supports field metadata
 * and validation through FieldInfo objects.
 *
 * @param T The type of value this field manages (String, Boolean, etc.)
 */
interface StateValueField<T> {
    fun getFieldValue(): T

    fun setFieldValue(value: T)

    fun getStateValue(): T?

    fun setStateValue(value: T)

    // additional functionality for later on to decide whether a field is enabled or not
    fun getFieldInfo(): MutableList<FieldInfo>

    fun getComponent(): JComponent
}

typealias TextualStateValueField = StateValueField<String>
typealias BooleanStateValueField = StateValueField<Boolean>
