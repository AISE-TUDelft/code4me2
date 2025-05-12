package me.code4me.services.modules.aggregators

import me.code4me.services.modules.context.BasicContextRetrievalModule

class BaseContextAggregator : BaseAggregator() {
    override val moduleName: String
        get() = "BaseContextAggregator"

    override val submodules =
        listOf(
            BasicContextRetrievalModule(),
        )

    override fun getStatus(): String {
        TODO("not yet implemented")
    }
}
