package me.code4me.services.modules.aggregators

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.services.modules.context.BasicContextRetrievalModule
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import java.util.concurrent.CopyOnWriteArrayList
import me.code4me.services.modules.telemetry.*

class BaseTelemetryAggregator : BaseAggregator() {
    override val moduleName: String
        get() = "BaseTelemetryAggregator"

    override val submodules = listOf(
        BasicTelemetryModule()
    )

    override fun getStatus(): String {
        TODO("Not yet implemented")
    }


}