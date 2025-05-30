package me.code4me.components.settings

import com.intellij.openapi.diagnostic.thisLogger
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

/**
 * Main UI component responsible for organizing and managing all settings sections.
 *
 * This component coordinates between different settings sections based on the user's
 * authentication state. It provides:
 * - Dynamic UI switching between authentication and configuration views
 * - Centralized field state management
 * - Property change event handling for reactive UI updates
 * - Proper lifecycle management for UI components
 *
 * The component automatically switches between:
 * - Authentication section (when user is not logged in)
 * - Configuration section (when user is authenticated)
 *
 * @since 1.0.0
 */
class Code4MeConfigurableComponent {

    companion object {
        private val LOG = thisLogger()

        // UI dimensions for consistent layout
        private const val MIN_WIDTH = 500
        private const val MIN_HEIGHT = 400
        private const val PREFERRED_WIDTH = 600
        private const val PREFERRED_HEIGHT = 500
    }

    /**
     * Collection of all field state managers across all sections.
     * Used for centralized validation, saving, and resetting operations.
     */
    private val fieldStates = mutableListOf<StateValueField<*>>()

    /**
     * Section responsible for user authentication (login/signup).
     */
    private val authSettingsSection = AuthenticationSection()

    /**
     * Section responsible for application configuration and module management.
     */
    private val configurationSection = ConfigurationSection()

    /**
     * Authentication state service for monitoring login status changes.
     */
    private val authService = getAuthState()

    /**
     * Listener that responds to authentication token changes by rebuilding the UI.
     */
    private val tokenChangeListener = PropertyChangeListener { event ->
        if (event.propertyName == TOKEN_PROPERTY) {
            LOG.debug("Authentication token changed, refreshing UI")
            SwingUtilities.invokeLater { rebuildUI() }
        }
    }

    /**
     * Main container panel that holds all UI components.
     */
    private var mainPanel: JPanel

    init {
        // Register for authentication state changes
        authService.addPropertyChangeListener(tokenChangeListener)

        // Build initial UI
        mainPanel = buildUI()

        LOG.debug("Code4MeConfigurableComponent initialized")
    }

    /**
     * Constructs the complete UI based on current authentication state.
     *
     * This method determines which sections to display based on whether the user
     * is authenticated and builds the appropriate UI components.
     *
     * @return A new JPanel containing the complete UI
     */
    private fun buildUI(): JPanel {
        val builder = FormBuilder.createFormBuilder()
        fieldStates.clear()

        // Create main container with fixed dimensions
        val newMainPanel = JPanel(BorderLayout()).apply {
            minimumSize = java.awt.Dimension(MIN_WIDTH, MIN_HEIGHT)
            preferredSize = java.awt.Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT)
        }

        try {
            // Show appropriate section based on authentication state
            if (authService.getToken().isNullOrBlank()) {
                LOG.debug("User not authenticated, showing authentication section")
                authSettingsSection.applyTo(builder, fieldStates)
            } else {
                LOG.debug("User authenticated, showing configuration section")
                configurationSection.applyTo(builder, fieldStates)
            }

            // Add separator and fill remaining space
            builder.addSeparator()
            val contentPanel = builder
                .addComponentFillVertically(JPanel(), 0)
                .panel

            newMainPanel.add(contentPanel, BorderLayout.CENTER)

        } catch (e: Exception) {
            LOG.error("Failed to build UI", e)
            // Return a minimal error panel instead of crashing
            val errorPanel = JPanel()
            newMainPanel.add(errorPanel, BorderLayout.CENTER)
        }

        return newMainPanel
    }

    /**
     * Rebuilds the UI by replacing the main panel's content.
     *
     * This method is called when the authentication state changes and a
     * complete UI refresh is needed. It preserves the main panel reference
     * while updating all its contents.
     */
    private fun rebuildUI() {
        try {
            val newPanel = buildUI()

            // Replace content while preserving the main panel reference
            mainPanel.removeAll()
            mainPanel.layout = newPanel.layout
            newPanel.components.forEach { mainPanel.add(it) }
            mainPanel.revalidate()
            mainPanel.repaint()

            LOG.debug("UI successfully rebuilt")

        } catch (e: Exception) {
            LOG.error("Failed to rebuild UI", e)
        }
    }

    /**
     * Returns the main panel containing all UI components.
     *
     * @return The root JPanel for this component
     */
    fun getPanel(): JPanel = mainPanel

    /**
     * Checks if any settings have been modified.
     *
     * This method also handles special cases like UI refresh requirements
     * from the authentication section.
     *
     * @return True if any field values differ from their stored state
     */
    fun isModified(): Boolean {
        // Handle UI refresh requirements from authentication section
        val needsRefresh = authSettingsSection.requiresUIRefresh.getAndSet(false)
        if (needsRefresh) {
            reset()
        }

        // Check if any field has been modified
        return needsRefresh || fieldStates.any { field ->
            try {
                field.getStateValue() != field.getFieldValue()
            } catch (e: Exception) {
                LOG.warn("Failed to check modification state for field", e)
                false
            }
        }
    }

    /**
     * Saves all field values to their respective state services.
     *
     * This method iterates through all registered fields and persists
     * their current values to the appropriate state storage.
     */
    fun save() {
        var savedCount = 0
        var failedCount = 0

        fieldStates.forEach { fieldState ->
            try {
                @Suppress("UNCHECKED_CAST")
                (fieldState as StateValueField<Any>).setStateValue(fieldState.getFieldValue())
                savedCount++
            } catch (e: Exception) {
                LOG.warn("Failed to save field state", e)
                failedCount++
            }
        }

        LOG.debug("Settings saved: $savedCount successful, $failedCount failed")
    }

    /**
     * Resets all field values to their last saved state.
     *
     * This method reverts all UI fields to their stored values,
     * effectively undoing any unsaved changes.
     */
    fun reset() {
        var resetCount = 0
        var failedCount = 0

        fieldStates.forEach { fieldState ->
            try {
                fieldState.getStateValue()?.let { stateValue ->
                    @Suppress("UNCHECKED_CAST")
                    (fieldState as StateValueField<Any>).setFieldValue(stateValue)
                    resetCount++
                }
            } catch (e: Exception) {
                LOG.warn("Failed to reset field state", e)
                failedCount++
            }
        }

        LOG.debug("Settings reset: $resetCount successful, $failedCount failed")
    }

    /**
     * Performs cleanup when the component is being disposed.
     *
     * This method removes property change listeners and cleans up resources
     * to prevent memory leaks.
     */
    fun dispose() {
        try {
            authService.removePropertyChangeListener(tokenChangeListener)
            fieldStates.clear()
            LOG.debug("Code4MeConfigurableComponent disposed successfully")
        } catch (e: Exception) {
            LOG.error("Error during component disposal", e)
        }
    }
}