package me.code4me.services.modules.telemetry

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass

class TimeSinceLastShownCompletion : PluginModule {
    override val moduleName: String
        get() = "TimeSinceLastShownCompletion"

    private var lastCollectDataTime: Long? = null

    override fun collectData(request: InlineCompletionRequest): List<Record> {
        if(lastCollectDataTime == null) {
            lastCollectDataTime = System.currentTimeMillis()
            return emptyList()
        }
        val previousTime = lastCollectDataTime
        lastCollectDataTime = System.currentTimeMillis()
        val record = Record(Record.Type.TELEMETRY)
        val timeSinceLastShownCompletionKey = Record.EntryKey("time_since_last_shown_completion", java.lang.Long::class.java)
        val timeSinceLastShownCompletion = lastCollectDataTime!! - previousTime!!
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
        TODO("Not yet implemented")
    }
}
