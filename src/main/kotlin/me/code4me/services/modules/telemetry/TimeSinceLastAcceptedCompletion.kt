package me.code4me.services.modules.telemetry

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass

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
        TODO("Not yet implemented")
    }

    override fun getPreferenceClass(): PreferenceClass {
        TODO("Not yet implemented")
    }
}
