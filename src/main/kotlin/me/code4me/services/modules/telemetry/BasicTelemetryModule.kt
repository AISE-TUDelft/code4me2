package me.code4me.services.modules.telemetry

import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass

// Example implementation of a telemetry module
class BasicTelemetryModule : PluginModule {
    override val moduleName = "BasicTelemetryModule"

    // TODO: Implement the logic to collect telemetry data
    override fun collectData(): List<Record> {
        val record =
            Record(
                type = Record.Type.TELEMETRY,
                expanded =
                    mutableMapOf(
                        Record.EntryKey("event", String::class.java) to "example_event",
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
