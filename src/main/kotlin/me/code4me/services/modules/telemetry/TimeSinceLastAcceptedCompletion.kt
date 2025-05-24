package me.code4me.services.modules.telemetry

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType

class TimeSinceLastAcceptedCompletion : PluginModule {
    override val moduleName: String
        get() = "TimeSinceLastAcceptedCompletion"

    override fun collectData(request: InlineCompletionRequest): List<Record> {
        TODO("Not yet implemented")
    }

    override fun getStatus(): String {
        TODO("Not yet implemented")
    }

    override fun initializeModules() {
        // No initialization needed for this module
    }

    override fun getPreferenceList(): List<Preference> {
        return listOf(
            Preference(
                "telemetry.time_since_last_accepted_completion",
                PreferenceType.BOOLEAN,
                "true",
                "Enable Time Since Last Accepted Completion Telemetry",
                "Enable or disable time since last accepted completion telemetry. " +
                    "This will send the time since the last accepted completion to the server for analysis.",
            ),
        )
    }

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.TELEMETRY
    }
}
