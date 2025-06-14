package me.code4me.services.modules.aggregators

/**
 * Aggregator for handling after insertion tasks in the plugin.
 * This aggregator is responsible for calling all after insertion modules.
 * It doesn't collect or return any data for now.
 */
class BaseAfterInsertionAggregator : BaseAggregator() {
    override val configModuleId: String
        get() = "AfterInsertionAggregator"
    override val moduleName: String
        get() = "BaseAfterInsertionAggregator"
}
