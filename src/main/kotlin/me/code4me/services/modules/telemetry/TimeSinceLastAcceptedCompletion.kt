package me.code4me.services.modules.telemetry

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass

class TimeSinceLastAcceptedCompletion : PluginModule {
    override val moduleName: String
        get() = "TimeSinceLastAcceptedCompletion"

    /**
     * Collects the time since the last accepted completion.
     * Only collects data if the module is enabled in the global configuration.
     */
    override fun collectData(request: InlineCompletionRequest): List<Record> {
        // Check if this module is enabled in the global configuration
        val prefState = me.code4me.services.state.getPrefState()
        if (!prefState.enabledModules.contains(getPreferenceId())) {
            return emptyList()
        }

        // TODO: Implement actual data collection logic
        return emptyList()
    }

    override fun getStatus(): String {
        TODO("Not yet implemented")
    }

    override fun initializeModules() {
        // No initialization needed for this module
    }

    override fun getPreferenceList(): List<Preference> {
        return emptyList()
    }

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.TELEMETRY
    }
}
