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
import me.code4me.utils.services.state.getTextualPreference

/**
 * Plugin module for managing chat model preferences and configuration.
 *
 * Handles user preferences for chat model selection and system prompt configuration,
 * providing model-related data for AI chat interactions. Integrates with the plugin's
 * preference system to allow users to customize their chat experience through settings.
 */
class ChatModel : PluginModule {
    companion object {
        val LOG = thisLogger()
        private const val PREFERRED_MODEL_KEY = "preferredChatModel"
        private const val SYSTEM_PROMPT_KEY = "systemPrompt"
    }

    /**
     * Defines the list of configurable preferences for chat model behavior.
     *
     * Creates preferences for model selection and system prompt customization,
     * with default values sourced from the plugin configuration. Users can
     * modify these preferences through the settings interface.
     *
     * @return List of Preference objects defining chat model configuration options
     */
    override fun getPreferenceList(): List<Preference> {
        return listOf(
            Preference(
                key = PREFERRED_MODEL_KEY,
                type = PreferenceType.LIST,
                defaultValue =
                    getConfig().getModelsConfiguration()?.getAvailableChatModels()?.joinToString(",") {
                        it.name
                    } ?: "default",
                limitedDefaultValue =
                    getConfig().getModelsConfiguration()?.getAvailableChatModels()?.joinToString(",") {
                        it.name
                    } ?: "default",
                displayName = "Preferred Model",
                description =
                    "Select your preferred chat model for chat. " +
                        "This will be used to determine which model to use for generating responses.",
            ),
            Preference(
                key = SYSTEM_PROMPT_KEY,
                type = PreferenceType.TEXT,
                defaultValue = getConfig().getModelsConfiguration()?.systemPrompt ?: "You are a helpful assistant.",
                limitedDefaultValue = getConfig().getModelsConfiguration()?.systemPrompt ?: "You are a helpful assistant.",
                displayName = "System Prompt",
                description =
                    "The system prompt to use for the chat model. " +
                        "This prompt is used to set the context for the chat model's responses.",
            ),
        )
    }

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.MODEL
    }

    override val moduleName: String
        get() = "ChatModel"

    /**
     * Collects model-related configuration data for AI completion requests.
     *
     * Extracts the user's preferred chat model and system prompt settings from
     * the preference store and packages them into a Record for use by the AI
     * completion system. Only includes preferences that have been explicitly set.
     *
     * @param request The inline completion request context (not used by this module)
     * @return List containing a single MODEL-type Record with chat configuration data
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

        if (getTextualPreference(getPreferenceId(), SYSTEM_PROMPT_KEY, "").isNotBlank()) {
            val systemPromptKey = Record.key<String>(SYSTEM_PROMPT_KEY)
            val systemPromptValue = getTextualPreference(getPreferenceId(), SYSTEM_PROMPT_KEY, "")
            expanded[systemPromptKey] = systemPromptValue
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
