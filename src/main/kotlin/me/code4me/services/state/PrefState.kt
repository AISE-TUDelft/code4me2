package me.code4me.services.state

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.Tag
import me.code4me.services.modules.ModuleRegistryService
import me.code4me.services.modules.PluginModule
import me.code4me.utils.configuration.Preference
import kotlin.reflect.KProperty

/**
 * Constant defining the name of the preference state component.
 * Used for service identification and storage.
 */
const val PREF_STATE_NAME = "me.code4me.state.auth"

/**
 * Helper function to access the current preference settings.
 *
 * @return The current [PrefSettings] instance from the service.
 */
fun getPrefState(): PrefSettings {
    return service<PrefState>().state
}

/**
 * Service responsible for managing and persisting user preferences.
 *
 * This service handles storage and retrieval of user preferences including:
 * - Module availability and enablement
 * - Module-specific preferences
 * - Application-wide settings
 *
 * The preferences are stored in an XML file defined in the [Storage] annotation.
 */
@Service
@State(
    name = PREF_STATE_NAME,
    storages = [Storage("code4me-pref.xml")],
)
class PrefState : SimplePersistentStateComponent<PrefSettings>(PrefSettings()) {
    companion object {
        /**
         * Clears the stored completions by setting the storeCompletions flag to false.
         * This effectively disables the storage of completions in the application.
         */
        fun clearCompletions() {
            PrefState().state.storeCompletions = false
        }

        /**
         * Retrieves all available modules from the ModuleRegistryService.
         *
         * @return A list of available [PluginModule] instances.
         */
        fun getAvailableModules(): List<PluginModule> {
            // Use ModuleRegistryService to get available modules
            return service<ModuleRegistryService>().getAvailableModules()
        }

        /**
         * Retrieves the list of enabled module IDs.
         *
         * @return A list of strings representing the IDs of enabled modules.
         */
        fun getEnabledModules(): List<String> {
            return getPrefState().enabledModules
        }

        /**
         * Enables a module by adding its ID to the list of enabled modules.
         * If the module is already enabled, this method has no effect.
         *
         * @param moduleId The ID of the module to enable.
         */
        fun enableModule(moduleId: String) {
            val state = getPrefState()
            if (!state.enabledModules.contains(moduleId)) {
                state.enabledModules = state.enabledModules + moduleId
            }
        }

        /**
         * Disables a module by removing its ID from the list of enabled modules.
         *
         * @param moduleId The ID of the module to disable.
         */
        fun disableModule(moduleId: String) {
            val state = getPrefState()
            state.enabledModules = state.enabledModules.filter { it != moduleId }
        }

        /**
         * Registers a module and its preferences in the preference state.
         *
         * This method:
         * 1. Adds the module to the list of available modules if not already present
         * 2. Registers all preferences defined by the module
         * 3. Initializes preference values with their defaults if not already set
         *
         * @param module The [PluginModule] to register.
         */
        fun registerModule(module: PluginModule) {
            val state = getPrefState()
            val preferences = module.getPreferenceList()

            // Add module if not already registered
            if (state.availableModules.none { it.getPreferenceId() == module.getPreferenceId() }) {
                state.availableModules = state.availableModules + module
            }

            // Register preferences
            preferences.forEach { pref ->
                val key = "${module.getPreferenceId()}.${pref.key}"
                if (!state.modulePreferences.containsKey(key)) {
                    state.modulePreferences[key] = pref

                    // Initialize with default value if not already set
                    if (!state.moduleValues.containsKey(key)) {
                        state.moduleValues[key] = pref.defaultValue
                    }
                }
            }
        }

        /**
         * Retrieves the value of a specific preference for a module.
         *
         * @param moduleId The ID of the module.
         * @param key The key of the preference.
         * @return The value of the preference, or null if not found.
         */
        fun getPreferenceValue(
            moduleId: String,
            key: String,
        ): String? {
            val fullKey = "$moduleId.$key"
            return getPrefState().moduleValues[fullKey]
        }

        /**
         * Sets the value of a specific preference for a module.
         *
         * @param moduleId The ID of the module.
         * @param key The key of the preference.
         * @param value The value to set.
         */
        fun setPreferenceValue(
            moduleId: String,
            key: String,
            value: String,
        ) {
            val fullKey = "$moduleId.$key"
            getPrefState().moduleValues[fullKey] = value
        }

        /**
         * Retrieves all preferences for a specific module.
         *
         * @param moduleId The ID of the module.
         * @return A list of [Preference] objects for the specified module.
         */
        fun getModulePreferences(moduleId: String): List<Preference> {
            return getPrefState().modulePreferences
                .filter { it.key.startsWith("$moduleId.") }
                .values.toList()
        }
    }
}

/**
 * Class representing user preferences and settings for the application.
 *
 * This class stores:
 * - Application-wide settings like completion and context storage preferences
 * - Available and enabled modules
 * - Module-specific preferences and their values
 *
 * It extends [BaseState] to support persistence through the IntelliJ platform's
 * state persistence mechanism.
 */
class PrefSettings : BaseState() {
    /**
     * Flag indicating whether completions should be stored.
     * When true, the application will save completion history.
     */
    var storeCompletions by property(false)

    /**
     * Flag indicating whether context should be stored.
     * When true, the application will save context information.
     */
    var storeContext by property(false)

    /**
     * List of all available modules in the application.
     * These are modules that can be enabled or disabled by the user.
     */
    @Tag("availableModules")
    var availableModules: List<PluginModule> = emptyList()

    /**
     * List of enabled module IDs.
     * Only modules with IDs in this list will be active in the application.
     */
    @Tag("enabledModules")
    var enabledModules: List<String> = emptyList()

    /**
     * Map of module preference definitions.
     * Keys are in the format "moduleId.preferenceKey" and values are [Preference] objects
     * that define the preference's type, default value, display name, etc.
     */
    @Tag("modulePreferences")
    @MapAnnotation(surroundWithTag = true, surroundKeyWithTag = true, surroundValueWithTag = true)
    var modulePreferences: MutableMap<String, Preference> = mutableMapOf()

    /**
     * Map of module preference values.
     * Keys are in the format "moduleId.preferenceKey" and values are the actual
     * preference values as strings.
     */
    @Tag("moduleValues")
    @MapAnnotation(surroundWithTag = true, surroundKeyWithTag = true, surroundValueWithTag = true)
    var moduleValues: MutableMap<String, String> = mutableMapOf()

    /**
     * Delegate class for convenient access to module preferences.
     *
     * This class provides property delegate functionality that allows module preferences
     * to be accessed as if they were direct properties of a class, while automatically
     * handling the storage and retrieval from the moduleValues map.
     *
     * @property moduleId The ID of the module.
     * @property key The key of the preference.
     * @property defaultValue The default value to return if the preference is not set.
     */
    inner class ModulePreferenceDelegate(private val moduleId: String, private val key: String, private val defaultValue: String) {
        /**
         * Gets the value of the preference.
         *
         * @param thisRef The reference to the object on which this delegate is used.
         * @param property The property being accessed.
         * @return The preference value, or the default value if not set.
         */
        operator fun getValue(
            thisRef: Any?,
            property: KProperty<*>,
        ): String {
            val fullKey = "$moduleId.$key"
            return moduleValues[fullKey] ?: defaultValue
        }

        /**
         * Sets the value of the preference.
         *
         * @param thisRef The reference to the object on which this delegate is used.
         * @param property The property being modified.
         * @param value The new value to set.
         */
        operator fun setValue(
            thisRef: Any?,
            property: KProperty<*>,
            value: String,
        ) {
            val fullKey = "$moduleId.$key"
            moduleValues[fullKey] = value
        }
    }
}
