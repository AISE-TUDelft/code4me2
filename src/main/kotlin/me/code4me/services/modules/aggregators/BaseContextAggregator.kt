package me.code4me.services.modules.aggregators

import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.context.EditorContextRetrievalModule
import me.code4me.services.modules.context.FileContextRetrievalModule
import me.code4me.services.modules.context.MultiFileContextRetrievalModule

class BaseContextAggregator : BaseAggregator() {
    override val moduleName: String
        get() = "BaseContextAggregator"

    override fun getStatus(): String {
        TODO("not yet implemented")
    }

    override val modulesList: List<PluginModule>
        get() =
            listOf(
                EditorContextRetrievalModule(),
                FileContextRetrievalModule(),
                MultiFileContextRetrievalModule(),
            )
}
