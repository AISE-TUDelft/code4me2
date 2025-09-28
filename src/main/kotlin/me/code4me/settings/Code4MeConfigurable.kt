package me.code4me.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.NlsContexts
import com.intellij.util.ui.FormBuilder
import me.code4me.api.generated.model.UpdateUser
import me.code4me.components.settings.LandingPageComponent
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.sections.AuthenticationSection
import me.code4me.services.app.getAppService
import me.code4me.services.state.TOKEN_PROPERTY
import me.code4me.services.state.getAuthState
import me.code4me.services.state.getPrefState
import me.code4me.utils.api.toSerializableMap
import me.code4me.utils.notification.showPreferenceSyncFailedNotification
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Main configuration entry point for the Code4Me plugin settings.
 *
 * This configurable dynamically shows different content based on authentication state:
 * - When not authenticated: Shows login/signup form
 * - When authenticated: Shows landing page with navigation to child settings
 *
 * @since 1.0.0
 */
class Code4MeConfigurable : SearchableConfigurable {
    private val appService = getAppService()
    private val authService = getAuthState()
    private val LOG = thisLogger()

    override fun getId(): String = "me.code4me.settings.Code4MeConfigurable"

    companion object {
        val atomicSettingsChanged = AtomicReference(false)
    }

    /**
     * Collection of all field state managers for the authentication section.
     */
    private val fieldStates = mutableListOf<StateValueField<*>>()

    /**
     * Section responsible for user authentication (login/signup).
     */
    private val authenticationSection = AuthenticationSection()

    /**
     * Landing page component for authenticated users.
     */
    private var landingPageComponent: LandingPageComponent? = null

    /**
     * Main container panel.
     */
    private var mainPanel: JPanel? = null

    /**
     * Listener that responds to authentication token changes by rebuilding the UI.
     */
    private val tokenChangeListener =
        PropertyChangeListener { event ->
            if (event.propertyName == TOKEN_PROPERTY) {
                LOG.debug("Authentication token changed, refreshing main UI")
                SwingUtilities.invokeLater { rebuildUI() }
            }
        }

    /**
     * Returns the display name shown in the IntelliJ settings tree.
     */
    override fun getDisplayName(): @NlsContexts.ConfigurableName String {
        return "Code4Me V2"
    }

    /**
     * Creates and returns the main UI component for the settings panel.
     */
    override fun createComponent(): JComponent? {
        // Register for authentication state changes
        authService.addPropertyChangeListener(tokenChangeListener)

        mainPanel = buildUI()
        return mainPanel
    }

    /**
     * Builds the UI based on current authentication state.
     */
    private fun buildUI(): JPanel {
        val panel =
            JPanel(BorderLayout()).apply {
                minimumSize = java.awt.Dimension(600, 500)
                preferredSize = java.awt.Dimension(700, 600)
            }

        try {
            fieldStates.clear()

            if (authService.getToken().isNullOrBlank()) {
                // Not authenticated - show login/signup form
                LOG.debug("User not authenticated, showing authentication section")
                val builder = FormBuilder.createFormBuilder()
                authenticationSection.applyTo(builder, fieldStates)

                val contentPanel =
                    builder
                        .addSeparator()
                        .addComponentFillVertically(JPanel(), 0)
                        .panel

                panel.add(contentPanel, BorderLayout.CENTER)
            } else {
                // Authenticated - show landing page
                LOG.debug("User authenticated, showing landing page")
                landingPageComponent = LandingPageComponent()
                panel.add(landingPageComponent?.getPanel(), BorderLayout.CENTER)
            }
        } catch (e: Exception) {
            LOG.error("Failed to build main UI", e)
            // Return minimal error panel instead of crashing
            val errorPanel = JPanel()
            panel.add(errorPanel, BorderLayout.CENTER)
        }

        return panel
    }

    /**
     * Rebuilds the UI by replacing the main panel's content.
     */
    private fun rebuildUI() {
        try {
            mainPanel?.let { panel ->
                val newPanel = buildUI()

                // Replace content while preserving the main panel reference
                panel.removeAll()
                panel.layout = newPanel.layout
                newPanel.components.forEach { panel.add(it) }
                panel.revalidate()
                panel.repaint()

                LOG.debug("Main UI successfully rebuilt")
            }
        } catch (e: Exception) {
            LOG.error("Failed to rebuild main UI", e)
        }
    }

    /**
     * Checks if any settings have been modified.
     */
    override fun isModified(): Boolean {
        // Handle UI refresh requirements from authentication section
        val needsRefresh = authenticationSection.requiresUIRefresh.getAndSet(false)
        if (needsRefresh) {
            SwingUtilities.invokeLater { rebuildUI() }
        }

        // Check if any field has been modified (only for auth section when not logged in)
        return needsRefresh ||
            (
                !authService.isAuthenticated() &&
                    fieldStates.any { field ->
                        try {
                            field.getStateValue() != field.getFieldValue()
                        } catch (e: Exception) {
                            LOG.warn("Failed to check modification state for field", e)
                            false
                        }
                    }
            )
    }

    /**
     * Saves all field values to their respective state services.
     */
    override fun apply() {
        if (!authService.isAuthenticated()) {
            // Only save auth fields if not authenticated
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

        atomicSettingsChanged.set(true)

        // NEW: also push to server on Apply for authenticated users
        if (authService.isAuthenticated()) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    updatePreferencesOnServer()
                    // only clear the flag if successful
                    atomicSettingsChanged.set(false)
                } catch (e: Exception) {
                    // keep the flag true so dispose can retry (or future Apply)
                    LOG.error("Apply-time preference sync failed", e)
                }
            }
        }
    }

    /**
     * Resets all field values to their last saved state.
     */
    override fun reset() {
        if (!authService.isAuthenticated()) {
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
    }

    /**
     * Cleans up UI resources when the settings dialog is closed.
     */
    override fun disposeUIResources() {
        if (atomicSettingsChanged.get()) {
            // Trigger server-side preference update
            if (authService.isAuthenticated()) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        updatePreferencesOnServer()
                        atomicSettingsChanged.set(false)
                    } catch (e: Exception) {
                        LOG.error("Dispose-time preference sync failed", e)
                        // keep the flag true for later retry
                    }
                }
            } else {
                // Not authenticated; leave the flag as-is to retry after login
                LOG.debug("Skip preference sync on dispose: user not authenticated")
            }
        }

        authService.removePropertyChangeListener(tokenChangeListener)
        landingPageComponent?.dispose()
        landingPageComponent = null
        fieldStates.clear()
        mainPanel = null

        super.disposeUIResources()
    }

    /**
     * Updates user preferences on the server when settings have been changed.
     */
    private fun updatePreferencesOnServer() {
        try {
            val prefState = getPrefState()
            val preferences = prefState.toSerializableMap()

            val updateUser =
                UpdateUser(
                    preference = preferences,
                )

            appService.updateUser(updateUser)
            LOG.info("User preferences updated on server successfully")
        } catch (e: Exception) {
            LOG.error("Failed to update user preferences on server", e)

            // Use the dedicated notification function
            ProjectManager.getInstance().openProjects.firstOrNull()?.showPreferenceSyncFailedNotification()
            throw e
        }
    }
}
