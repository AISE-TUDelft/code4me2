package me.code4me.components.settings

import com.intellij.openapi.components.service
import com.intellij.util.ui.FormBuilder
import kotlinx.coroutines.Dispatchers
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.sections.AuthenticationSection
import me.code4me.services.launchAppScope
import javax.swing.JComponent
import javax.swing.JPanel


class Code4MeConfigurableComponent {
    private val fieldStates = mutableListOf<StateValueField<*>>()
    private val authSettingsSection = AuthenticationSection()
    private var mainPanel: JPanel

    init {
        val builder = FormBuilder.createFormBuilder()
        authSettingsSection.applyTo(builder, fieldStates)
        // add a separator between the two sections with a bit of padding
        builder.addSeparator()
        // TODO : add other sections here
        mainPanel = builder
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    fun getPanel(): JPanel{
        return mainPanel
    }

    fun isModified(): Boolean {
        val needsRefresh = authSettingsSection.requiresUIRefresh.getAndSet(false)
        if (needsRefresh) {
            reset()
        }
        return needsRefresh || fieldStates.any { it.getStateValue() != it.getFieldValue() }
    }

    fun save() {
        fieldStates.forEach { fieldState ->
            @Suppress("UNCHECKED_CAST")
            (fieldState as StateValueField<Any>).setStateValue(fieldState.getFieldValue())
        }
    }

    fun reset() {
        fieldStates.forEach { fieldState ->
            fieldState.getStateValue()?.let { stateValue ->
                @Suppress("UNCHECKED_CAST")
                (fieldState as StateValueField<Any>).setFieldValue(stateValue)
            }
        }
    }
}