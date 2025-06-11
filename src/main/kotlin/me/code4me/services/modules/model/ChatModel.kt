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

class ChatModel : PluginModule {
    companion object {
        val LOG = thisLogger()
        private const val PREFERRED_MODEL_KEY = "preferredChatModel"
        private const val SYSTEM_PROMPT_KEY = "systemPrompt"
    }

    override fun getPreferenceList(): List<Preference> {
        return listOf(
            Preference(
                key = PREFERRED_MODEL_KEY,
                type = PreferenceType.LIST,
                defaultValue = getConfig().getModelsConfiguration()?.getAvailableChatModels()?.joinToString(","){
                    it.name
                } ?: "default",
                displayName = "Preferred Model",
                description = "Select your preferred chat model for chat. " +
                        "This will be used to determine which model to use for generating responses."
            ),
            Preference(
                key = SYSTEM_PROMPT_KEY,
                type = PreferenceType.TEXT,
                defaultValue = getConfig().getModelsConfiguration()?.systemPrompt ?: "You are a helpful assistant.",
                displayName = "System Prompt",
                description = "The system prompt to use for the chat model. " +
                        "This prompt is used to set the context for the chat model's responses."
            )
        )
    }

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.MODEL
    }

    override val moduleName: String
        get() = "ChatModel"

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

        return listOf(Record(
            type = Record.Type.MODEL,
            expanded = expanded
        ))
    }

    override fun initializeModules() {
        LOG.debug("Initialized $moduleName")
    }

}