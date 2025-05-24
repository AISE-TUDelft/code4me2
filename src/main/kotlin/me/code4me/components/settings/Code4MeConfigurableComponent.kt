package me.code4me.components.settings

import com.intellij.util.ui.FormBuilder
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.sections.AuthenticationSection
import me.code4me.components.settings.sections.ConfigurationSection
import me.code4me.services.state.TOKEN_PROPERTY
import me.code4me.services.state.getAuthState
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JPanel
import javax.swing.SwingUtilities

class Code4MeConfigurableComponent {
    private val fieldStates = mutableListOf<StateValueField<*>>()
    private val authSettingsSection = AuthenticationSection()
    private val configurationSection = ConfigurationSection()

    // Stateful Services
    private val authService = getAuthState()
    private val tokenChangeListener =
        PropertyChangeListener { event ->
            if (event.propertyName == TOKEN_PROPERTY) {
                println("Token changed. Updating UI sections...")
                SwingUtilities.invokeLater { rebuildUI() }
            }
        }

    private var mainPanel: JPanel

    init {
        // Register the token change listener
        authService.addPropertyChangeListener(tokenChangeListener)

        mainPanel = buildUI()
    }

    private fun buildUI(): JPanel {
        val builder = FormBuilder.createFormBuilder()
        fieldStates.clear()

        // Main container with fixed minimum/preferred size to prevent shrinking
        val mainPanel =
            JPanel(BorderLayout()).apply {
                minimumSize = java.awt.Dimension(500, 400)
                preferredSize = java.awt.Dimension(600, 500)
            }

        // check whether the user has been authenticated
        // if not, then show the authentication section
        // otherwise show the configuration page
        if (authService.getToken().isNullOrBlank()) {
            authSettingsSection.applyTo(builder, fieldStates)
        } else {
            configurationSection.applyTo(builder, fieldStates)
        }
        // add a separator between the two sections with a bit of padding
        builder.addSeparator()
        // TODO : add other sections here
        val contentPanel =
            builder
                .addComponentFillVertically(JPanel(), 0)
                .panel
        mainPanel.add(contentPanel, BorderLayout.CENTER)
        return mainPanel
    }

    private fun rebuildUI() {
        val newPanel = buildUI()
        // Replace the content of the main panel with the new one
        mainPanel.removeAll()
        mainPanel.layout = newPanel.layout
        newPanel.components.forEach { mainPanel.add(it) }
        mainPanel.revalidate()
        mainPanel.repaint()
    }

    fun getPanel(): JPanel {
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

    /**
     * Clean up listeners when the component is disposed
     */
    fun dispose() {
        authService.removePropertyChangeListener(tokenChangeListener)
    }
}
