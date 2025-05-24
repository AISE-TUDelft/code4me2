package me.code4me.services.modules.aggregators

import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.telemetry.TimeSinceLastShownCompletion
import me.code4me.services.modules.telemetry.TypingSpeed

class BaseTelemetryAggregator : BaseAggregator() {
    override val moduleName: String
        get() = "BaseTelemetryAggregator"

    override val modulesList: List<PluginModule>
        get() =
            listOf(
                TypingSpeed(),
                TimeSinceLastShownCompletion(),
            )

    override fun getStatus(): String {
        TODO("Not yet implemented")
    }
}
