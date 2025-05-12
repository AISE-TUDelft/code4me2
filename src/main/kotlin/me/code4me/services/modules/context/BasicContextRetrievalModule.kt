package me.code4me.services.modules.context

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass

// Example implementation of a context retrieval module
class BasicContextRetrievalModule : PluginModule {
    override val moduleName = "BasicContextRetrievalModule"

    // TODO: Implement the logic to collect telemetry data
    override fun collectData(request: InlineCompletionRequest): List<Record> {
        val record =
            Record(
                type = Record.Type.CONTEXT,
                expanded =
                    mutableMapOf(
                        Record.EntryKey("context", String::class.java) to "example_context",
                        Record.EntryKey("timestamp", Long::class.java) to System.currentTimeMillis(),
                    ),
            )
        return listOf(record)
    }

    override fun getStatus(): String {
        TODO("not yet implemented")
    }

    // This is a concrete module, so it doesn't need to register modules
    override fun initializeModules() {
        println("Concrete module $moduleName doesn't need to register modules.")
    }

    override fun getPreferenceList(): List<Preference> {
        TODO("Not yet implemented")
    }

    override fun getPreferenceClass(): PreferenceClass {
        TODO("Not yet implemented")
    }
}
