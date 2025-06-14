
package me.code4me.services.state

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.Tag
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.manager.getModuleManager
import me.code4me.settings.Code4MeConfigurable
import me.code4me.utils.configuration.Preference
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

/**
 * Constant defining the name of the preference state component.
 * Used for service identification and storage.
 */
const val PREF_STATE_NAME = "me.code4me.state.preferences"

/**
 * Helper function to access the current preference settings.
 *
 * @return The current [PrefSettings] instance from the service
 */
fun getPrefState(): PrefSettings {
    return service<PrefState>().state
}

/**
 * Service responsible for managing and persisting user preferences and module state.
 *
 * This service provides centralized management for:
 * - Application-wide preferences (completion storage, context storage)
 * - Module enablement tracking
 * - Module-specific preference definitions and values
 * - Preference lifecycle management and validation
 *
 * The service integrates with the IntelliJ Platform's persistence mechanism
 * to ensure preferences survive IDE restarts and are properly synchronized
 * across plugin components.
 *
 * Thread Safety: This service is thread-safe and can be accessed from any thread.
 *
 * @since 1.0.0
 */
@Service
@State(
    name = PREF_STATE_NAME,
    storages = [Storage("code4me-preferences.xml")],
)
class PrefState : SimplePersistentStateComponent<PrefSettings>(PrefSettings()) {
    companion object {
        private val LOG = thisLogger()

        /**
         * Retrieves all available modules from the active project's ModuleManager.
         *
         * This method attempts to find an active project and retrieve its module list.
         * If no active project is found, it returns an empty list.
         *
         * @return List of available [PluginModule] instances, or empty list if none found
         */
        fun getAvailableModules(): List<PluginModule> {
            return try {
                val activeProject = ProjectManager.getInstance().openProjects.firstOrNull()
                if (activeProject != null) {
                    getModuleManager().getAvailableModules()
                } else {
                    LOG.warn("No active project found, returning empty module list")
                    emptyList()
                }
            } catch (e: Exception) {
                LOG.error("Failed to retrieve available modules", e)
                emptyList()
            }
        }

        /**
         * Retrieves the set of currently enabled module IDs.
         *
         * @return HashSet of enabled module IDs, never null but may be empty
         */
        fun getEnabledModules(): HashSet<String> {
            return getPrefState().enabledModules
        }

        /**
         * Enables a module by adding its ID to the enabled modules set.
         *
         * This method is idempotent - calling it multiple times with the same
         * module ID has no additional effect.
         *
         * @param moduleId The preference ID of the module to enable
         */
        fun enableModule(moduleId: String) {
            require(moduleId.isNotBlank()) { "Module ID cannot be blank" }

            try {
                val state = getPrefState()
                if (state.enabledModules.add(moduleId)) {
                    LOG.debug("Module enabled: $moduleId")
                } else {
                    LOG.debug("Module was already enabled: $moduleId")
                }
            } catch (e: Exception) {
                LOG.error("Failed to enable module: $moduleId", e)
            }
        }

        /**
         * Disables a module by removing its ID from the enabled modules set.
         *
         * This method is idempotent - calling it on an already disabled module
         * has no effect.
         *
         * @param moduleId The preference ID of the module to disable
         */
        fun disableModule(moduleId: String) {
            require(moduleId.isNotBlank()) { "Module ID cannot be blank" }

            try {
                val state = getPrefState()
                if (state.enabledModules.remove(moduleId)) {
                    LOG.debug("Module disabled: $moduleId")
                } else {
                    LOG.debug("Module was already disabled: $moduleId")
                }
            } catch (e: Exception) {
                LOG.error("Failed to disable module: $moduleId", e)
            }
        }

        /**
         * Registers a module and its preferences with the preference system.
         *
         * This method performs a complete registration process:
         * 1. Registers all module preferences with their metadata
         * 2. Initializes preference values with defaults if not already set
         * 3. Removes obsolete preferences that are no longer defined
         *
         * Note: Module availability information is now retrieved dynamically
         * from the ModuleManager instead of being stored in state.
         *
         * @param module The [PluginModule] to register
         * @return True if this is a new module registration, false if updating existing
         */
        fun registerModule(module: PluginModule): Boolean {
            return try {
                val state = getPrefState()
                val moduleId = module.getPreferenceId()
                val preferences = module.getPreferenceList()

                // Check if this is a new module by looking at existing preferences
                val moduleKeyPrefix = "$moduleId."
                val isNewModule = state.modulePreferences.keys.none { it.startsWith(moduleKeyPrefix) }

                if (isNewModule) {
                    LOG.debug("New module registered: $moduleId")
                } else {
                    LOG.debug("Module updated: $moduleId")
                }

                // Register or update preferences
                preferences.forEach { preference ->
                    val fullKey = moduleKeyPrefix + preference.key

                    // Register preference definition
                    state.modulePreferences[fullKey] = preference

                    // Initialize with default value if not already set
                    if (!state.moduleValues.containsKey(fullKey)) {
                        state.moduleValues[fullKey] = preference.defaultValue
                        LOG.debug("Initialized preference: $fullKey = ${preference.defaultValue}")
                    }
                }

                // Clean up obsolete preferences
                cleanupObsoletePreferences(moduleId, preferences, state)

                LOG.debug("Module registration completed for: $moduleId (${preferences.size} preferences)")
                isNewModule
            } catch (e: Exception) {
                LOG.error("Failed to register module: ${module.getPreferenceId()}", e)
                false
            }
        }

        /**
         * Removes preferences that are no longer defined by the module.
         */
        private fun cleanupObsoletePreferences(
            moduleId: String,
            currentPreferences: List<Preference>,
            state: PrefSettings,
        ) {
            val moduleKeyPrefix = "$moduleId."
            val currentPrefKeys = currentPreferences.map { "$moduleKeyPrefix${it.key}" }.toSet()

            val keysToRemove =
                state.modulePreferences.keys
                    .filter { it.startsWith(moduleKeyPrefix) && it !in currentPrefKeys }

            if (keysToRemove.isNotEmpty()) {
                keysToRemove.forEach { key ->
                    state.modulePreferences.remove(key)
                    state.moduleValues.remove(key)
                }
                LOG.debug("Removed ${keysToRemove.size} obsolete preferences for module: $moduleId")
            }
        }

        /**
         * Retrieves the current value of a specific module preference.
         *
         * @param moduleId The ID of the module
         * @param key The preference key
         * @return The preference value, or null if not found
         */
        fun getPreferenceValue(
            moduleId: String,
            key: String,
        ): String? {
            require(moduleId.isNotBlank()) { "Module ID cannot be blank" }
            require(key.isNotBlank()) { "Preference key cannot be blank" }

            return try {
                val fullKey = "$moduleId.$key"
                getPrefState().moduleValues[fullKey]
            } catch (e: Exception) {
                LOG.error("Failed to get preference value: $moduleId.$key", e)
                null
            }
        }

        /**
         * Sets the value of a specific module preference.
         *
         * @param moduleId The ID of the module
         * @param key The preference key
         * @param value The value to set
         */
        fun setPreferenceValue(
            moduleId: String,
            key: String,
            value: String,
        ) {
            require(moduleId.isNotBlank()) { "Module ID cannot be blank" }
            require(key.isNotBlank()) { "Preference key cannot be blank" }
            require(value.isNotBlank()) { "Preference value cannot be blank" }

            try {
                val fullKey = "$moduleId.$key"
                val state = getPrefState()
                val oldValue = state.moduleValues[fullKey]

                // Use direct property assignment instead of creating a new map
                state.moduleValues[fullKey] = value

                // Fire property change event
                state.propertyChangeSupport.firePropertyChange(fullKey, oldValue, value)
                Code4MeConfigurable.atomicSettingsChanged.set(true)

                LOG.debug("Set preference: $fullKey = $value")
            } catch (e: Exception) {
                LOG.error("Failed to set preference value: $moduleId.$key", e)
            }
        }

        /**
         * Retrieves all preference definitions for a specific module.
         *
         * @param moduleId The ID of the module
         * @return List of [Preference] definitions for the specified module
         */
        fun getModulePreferences(moduleId: String): List<Preference> {
            require(moduleId.isNotBlank()) { "Module ID cannot be blank" }

            return try {
                val moduleKeyPrefix = "$moduleId."
                getPrefState().modulePreferences
                    .filter { it.key.startsWith(moduleKeyPrefix) }
                    .values.toList()
            } catch (e: Exception) {
                LOG.error("Failed to get module preferences for: $moduleId", e)
                emptyList()
            }
        }
    }
}

/**
 * Data class representing the complete preference state for the application.
 *
 * This class stores all persistent preference data including:
 * - Application-wide settings
 * - Module enablement state
 * - Module preference definitions and current values
 *
 * The class extends [BaseState] to integrate with IntelliJ Platform's
 * persistence mechanism, ensuring all changes are automatically saved.
 *
 * Note: Module availability information is now retrieved dynamically
 * from the ModuleManager instead of being stored in persistent state
 * to avoid serialization issues with complex module objects.
 *
 * @since 1.0.0
 */
class PrefSettings : BaseState() {
    // ================= APPLICATION SETTINGS =================

    var storeContext by property(false)

    var storeBehavioralTelemetry by property(false)

    var storeContextualTelemetry by property(false)

    // ================= MODULE MANAGEMENT =================

    /**
     * Set of module IDs that are currently enabled.
     *
     * Only modules with IDs in this set will be active and participate in
     * code completion and other plugin functionality.
     */
    @Tag("enabledModules")
    var enabledModules: HashSet<String> = hashSetOf()

    // ================= PREFERENCE STORAGE =================

    /**
     * Map containing all module preference definitions.
     *
     * Keys: "moduleId.preferenceKey" format
     * Values: [Preference] objects containing metadata (type, default value, description, etc.)
     *
     * This map defines the structure and validation rules for all preferences.
     */
    @Tag("modulePreferences")
    @MapAnnotation(surroundWithTag = true, surroundKeyWithTag = true, surroundValueWithTag = true)
    var modulePreferences: MutableMap<String, Preference> = mutableMapOf()

    /**
     * Map containing the current values of all module preferences.
     *
     * Keys: "moduleId.preferenceKey" format
     * Values: String representations of the current preference values
     *
     * All preference values are stored as strings and converted to appropriate
     * types when retrieved by modules.
     */
    @Tag("moduleValues")
    @MapAnnotation(surroundWithTag = true, surroundKeyWithTag = true, surroundValueWithTag = true)
    var moduleValues: MutableMap<String, String> = mutableMapOf()

    // ================= PROPERTY CHANGE SUPPORT =================
    val propertyChangeSupport = PropertyChangeSupport(this)

    // Add methods to register/unregister listeners
    fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(listener)
    }

    fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(listener)
    }
}
