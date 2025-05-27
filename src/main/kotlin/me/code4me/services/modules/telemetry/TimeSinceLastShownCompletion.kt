package me.code4me.services.modules.telemetry

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import me.code4me.services.modules.PluginModule
import me.code4me.utils.record.Record
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass

/**
 * This module collects the time since the last shown completion.
 * It calculates the time difference between the current time and the last shown completion time.
 */
class TimeSinceLastShownCompletion : PluginModule {
    override val moduleName: String
        get() = "TimeSinceLastShownCompletion"

    private var lastCollectDataTime: Long? = null

    /**
     * Collects the time since the last shown completion.
     * It returns record containing the time difference in milliseconds.
     * Only collects data if the module is enabled in the global configuration.
     */
    override fun collectData(request: InlineCompletionRequest): List<Record> {
        // Check if this module is enabled in the global configuration
        val prefState = me.code4me.services.state.getPrefState()
        if (!prefState.enabledModules.contains(getPreferenceId())) {
            return emptyList()
        }

        val previousTime = lastCollectDataTime
        lastCollectDataTime = System.currentTimeMillis()
        val record = Record(Record.Type.TELEMETRY)
        val timeSinceLastShownCompletionKey = Record.key<Long>("time_since_last_completion")
        var timeSinceLastShownCompletion = 0L
        if (previousTime != null) {
            // Calculate the time difference in milliseconds
            timeSinceLastShownCompletion = lastCollectDataTime!! - previousTime
        }
        record.put(timeSinceLastShownCompletionKey, timeSinceLastShownCompletion)
        return listOf(record)
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
