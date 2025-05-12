package me.code4me.services.modules.aggregators

import me.code4me.services.modules.telemetry.BasicTelemetryModule

class BaseTelemetryAggregator : BaseAggregator() {
    override val moduleName: String
        get() = "BaseTelemetryAggregator"

    override val submodules =
        listOf(
            BasicTelemetryModule(),
        )

    override fun getStatus(): String {
        TODO("Not yet implemented")
    }
}
