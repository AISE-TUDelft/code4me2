package me.code4me.services.modules.aggregators

import me.code4me.services.modules.context.BasicContextRetrievalModule
import me.code4me.services.modules.context.EditorContextRetrievalModule
import me.code4me.services.modules.context.FileContextRetrievalModule
import me.code4me.services.modules.context.MultiFileContextRetrievalModule

class BaseContextAggregator : BaseAggregator() {
    override val moduleName: String
        get() = "BaseContextAggregator"

    override val submodules =
        listOf(
            BasicContextRetrievalModule(),
            EditorContextRetrievalModule(),
            FileContextRetrievalModule(),
            MultiFileContextRetrievalModule()
        )

    override fun getStatus(): String {
        TODO("not yet implemented")
    }
}
