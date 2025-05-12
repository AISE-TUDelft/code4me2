package me.code4me.components.settings.sections

import com.intellij.util.ui.FormBuilder
import me.code4me.components.settings.fields.StateValueField

interface SettingsSection {
    fun applyTo(
        builder: FormBuilder,
        stateValueFields: MutableList<StateValueField<*>>,
    )
}
