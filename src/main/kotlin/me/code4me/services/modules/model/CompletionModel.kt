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

class CompletionModel : PluginModule {
    companion object {
        val LOG = thisLogger()
        private const val PREFERRED_MODEL_KEY = "preferredCompletionModel"
    }

    override fun getPreferenceList(): List<Preference> {
        return listOf(
            Preference(
                key = PREFERRED_MODEL_KEY,
                type = PreferenceType.LIST,
                defaultValue =
                    getConfig().getModelsConfiguration()?.getAvailableCompletionModels()?.joinToString(",") {
                        it.name
                    } ?: "default",
                displayName = "Preferred Model",
                description =
                    "Select your preferred chat model for inline completion. " +
                        "This will be used to determine which model to use for generating responses.",
            ),
        )
    }

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.MODEL
    }

    override val moduleName: String
        get() = "CompletionModel"

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
