package me.code4me.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.NlsContexts
import me.code4me.api.generated.model.UpdateUser
import me.code4me.components.settings.Code4MeConfigurableComponent
import me.code4me.services.app.getAppService
import me.code4me.services.state.getPrefState
import me.code4me.utils.api.toSerializableMap
import me.code4me.utils.notification.showPreferenceSyncFailedNotification
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent

/**
 * Main configuration entry point for the Code4Me plugin settings.
 *
 * This class implements the IntelliJ Platform's [Configurable] interface to integrate
 * with the IDE's settings system. It provides the UI components and handles the
 * settings lifecycle including loading, validation, and persistence.
 *
 * The configurable manages:
 * - Authentication settings (login/signup)
 * - Module configuration and preferences
 * - Application-wide settings
 * - User profile management
 *
 * @since 1.0.0
 * @see Code4MeConfigurableComponent
 */
class Code4MeConfigurable : SearchableConfigurable {
    private val appService = getAppService()
    private val LOG = thisLogger()

    override fun getId(): String = "me.code4me.settings.Code4MeConfigurable"

    companion object {
        val atomicSettingsChanged = AtomicReference(false)
    }

    /**
     * The main UI component that handles all settings interactions.
     * Lazily initialized when [createComponent] is called.
     */
    private var code4MeConfigurableComponent: Code4MeConfigurableComponent? = null

    /**
     * Returns the display name shown in the IntelliJ settings tree.
     *
     * @return The localized display name for this configurable
     */
    override fun getDisplayName(): @NlsContexts.ConfigurableName String {
        return "Code4Me V2 Settings"
    }

    /**
     * Creates and returns the main UI component for the settings panel.
     *
     * This method is called by the IntelliJ platform when the settings panel
     * needs to be displayed. The component is created lazily and cached for
     * the lifetime of the settings dialog.
     *
     * @return The root JComponent containing all settings UI elements
     */
    override fun createComponent(): JComponent? {
        code4MeConfigurableComponent = Code4MeConfigurableComponent()
        return code4MeConfigurableComponent?.getPanel()
    }

    /**
     * Checks if any settings have been modified since the last save or reset.
     *
     * This method is called frequently by the platform to determine whether
     * the "Apply" button should be enabled. It also handles UI refresh
     * requirements when authentication state changes.
     *
     * @return True if settings have been modified, false otherwise
     */
    override fun isModified(): Boolean {
        return code4MeConfigurableComponent?.isModified() ?: false
    }

    /**
     * Applies all pending changes to the persistent state.
     *
     * This method is called when the user clicks "Apply" or "OK" in the
     * settings dialog. All field values are persisted to their respective
     * state services.
     */
    override fun apply() {
        code4MeConfigurableComponent?.save()
        atomicSettingsChanged.set(true)
    }

    /**
     * Resets all fields to their last saved values.
     *
     * This method is called when the user clicks "Reset" or "Cancel" in the
     * settings dialog. All UI fields are reverted to their stored values.
     */
    override fun reset() {
        code4MeConfigurableComponent?.reset()
    }

    /**
     * Cleans up UI resources when the settings dialog is closed.
     *
     * This method ensures proper cleanup of listeners and prevents memory leaks
     * by disposing of the component and setting the reference to null.
     */
    override fun disposeUIResources() {
        if (atomicSettingsChanged.get()) {
            // Trigger server-side preference update
            ApplicationManager.getApplication().executeOnPooledThread {
                updatePreferencesOnServer()
            }
            atomicSettingsChanged.set(false)
        }
        super.disposeUIResources()
    }

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
        }
    }
}
