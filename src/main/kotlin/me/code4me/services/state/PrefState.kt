package me.code4me.services.state

import PluginModule
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.Tag
import me.code4me.services.modules.ModuleRegistryService
import kotlin.reflect.KProperty
import me.code4me.utils.configuration.Preference

const val PREF_STATE_NAME = "me.code4me.state.auth"

fun getPrefState(): PrefSettings {
    return service<PrefState>().state
}

@Service
@State(
    name = PREF_STATE_NAME,
    storages = [Storage("code4me-pref.xml")]
)
class PrefState : SimplePersistentStateComponent<PrefSettings>(PrefSettings()) {
    companion object {
        // clear the stored completions
        fun clearCompletions() {
            PrefState().state.storeCompletions = false
        }

        // Get all available modules
        fun getAvailableModules(): List<PluginModule> {
            // Use ModuleRegistryService to get available modules
            return service<ModuleRegistryService>().getAvailableModules()
        }

        // Get enabled modules
        fun getEnabledModules(): List<String> {
            return getPrefState().enabledModules
        }

        // Enable a module
        fun enableModule(moduleId: String) {
            val state = getPrefState()
            if (!state.enabledModules.contains(moduleId)) {
                state.enabledModules = state.enabledModules + moduleId
            }
        }

        // Disable a module
        fun disableModule(moduleId: String) {
            val state = getPrefState()
            state.enabledModules = state.enabledModules.filter { it != moduleId }
        }

        // Register a module
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

        // Get a preference value
        fun getPreferenceValue(moduleId: String, key: String): String? {
            val fullKey = "$moduleId.$key"
            return getPrefState().moduleValues[fullKey]
        }

        // Set a preference value
        fun setPreferenceValue(moduleId: String, key: String, value: String) {
            val fullKey = "$moduleId.$key"
            getPrefState().moduleValues[fullKey] = value
        }

        // Get all preferences for a module
        fun getModulePreferences(moduleId: String): List<Preference> {
            return getPrefState().modulePreferences
                .filter { it.key.startsWith("$moduleId.") }
                .values.toList()
        }
    }
}

class PrefSettings : BaseState() {
    var storeCompletions by property(false)
    var storeContext by property(false)

   // List of available modules
   @Tag("availableModules")
   var availableModules: List<PluginModule> = emptyList()

   // List of enabled module IDs
   @Tag("enabledModules")
   var enabledModules: List<String> = emptyList()

   // Map of module preference definitions (moduleId.key -> ModulePreference)
   @Tag("modulePreferences")
   @MapAnnotation(surroundWithTag = true, surroundKeyWithTag = true, surroundValueWithTag = true)
   var modulePreferences: MutableMap<String, Preference> = mutableMapOf()

   // Map of module preference values (moduleId.key -> value)
   @Tag("moduleValues")
   @MapAnnotation(surroundWithTag = true, surroundKeyWithTag = true, surroundValueWithTag = true)
   var moduleValues: MutableMap<String, String> = mutableMapOf()

    // Delegate for accessing module preferences
    inner class ModulePreferenceDelegate(private val moduleId: String, private val key: String, private val defaultValue: String) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): String {
            val fullKey = "$moduleId.$key"
            return moduleValues[fullKey] ?: defaultValue
        }

        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            val fullKey = "$moduleId.$key"
            moduleValues[fullKey] = value
        }
    }
}
