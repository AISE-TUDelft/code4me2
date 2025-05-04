package me.code4me.components.settings.sections

import me.code4me.components.settings.fields.StateValueField
import com.intellij.util.ui.FormBuilder

interface SettingsSection {
    fun applyTo(builder: FormBuilder, stateValueFields: MutableList<StateValueField<*>>)
}