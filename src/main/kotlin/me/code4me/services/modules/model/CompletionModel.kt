package me.code4me.services.modules.model

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.diagnostic.thisLogger
import me.code4me.services.config.getConfig
import me.code4me.services.modules.PluginModule
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import me.code4me.utils.record.Record
import me.code4me.utils.services.state.getListPreference

/**
 * Plugin module for managing code completion model preferences and settings.
 *
 * Handles user preferences for completion model selection and inline completion feature
 * toggle, providing model-related configuration for AI-powered code completions.
 * Integrates with the plugin's preference system to allow users to customize their
 * completion experience through settings.
 */
class CompletionModel : PluginModule {
    companion object {
        val LOG = thisLogger()
        private const val PREFERRED_MODEL_KEY = "preferredCompletionModel"
        internal const val COMPLETION_INLINE_KEY = "getCompletionInline"
    }

    /**
     * Defines the list of configurable preferences for completion model behavior.
     *
     * Creates preferences for model selection and inline completion feature control,
     * with default values sourced from the plugin configuration. Users can modify
     * these preferences through the settings interface.
     *
     * @return List of Preference objects defining completion model configuration options
     */
    override fun getPreferenceList(): List<Preference> {
        return listOf(
            Preference(
                key = PREFERRED_MODEL_KEY,
                type = PreferenceType.LIST,
                defaultValue =
                    getConfig().getModelsConfiguration()?.getAvailableCompletionModels()?.joinToString(",") {
                        it.name
                    } ?: "default",
                limitedDefaultValue =
                    getConfig().getModelsConfiguration()?.getAvailableCompletionModels()?.joinToString(",") {
                        it.name
                    } ?: "default",
                displayName = "Preferred Model",
                description =
                    "Select your preferred chat model for inline completion. " +
                        "This will be used to determine which model to use for generating responses.",
            ),
            Preference(
                key = COMPLETION_INLINE_KEY,
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                limitedDefaultValue = "true",
                displayName = "Enable Inline Completion",
                description =
                    "Enable or disable inline completion feature. " +
                        "When disabled, the plugin will only provide dropdown suggestions ",
            ),
        )
    }

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.MODEL
    }

    override val moduleName: String
        get() = "CompletionModel"

    /**
     * Collects completion model configuration data for AI completion requests.
     *
     * Extracts the user's preferred completion model setting from the preference
     * store and packages it into a Record for use by the AI completion system.
     * Only includes the model preference if it has been explicitly set.
     *
     * @param request The inline completion request context (not used by this module)
     * @return List containing a single MODEL-type Record with completion configuration data
     */
    override fun collectData(request: InlineCompletionRequest): List<Record> {
        val expanded = mutableMapOf<Record.EntryKey, Any>()
        if (getListPreference(getPreferenceId(), PREFERRED_MODEL_KEY, "").isNotBlank()) {
            val preferredModelKey = Record.key<String>(PREFERRED_MODEL_KEY)
            val preferredModelValue =
                getListPreference(getPreferenceId(), PREFERRED_MODEL_KEY, "default")
                    .split(",")
                    .map { it.trim() }
                    .firstOrNull() ?: "default"
            expanded[preferredModelKey] = preferredModelValue
        }
        return listOf(
            Record(
                type = Record.Type.MODEL,
                expanded = expanded,
            ),
        )
    }

    override fun initializeModules() {
        LOG.debug("Initialized $moduleName")
    }
}
