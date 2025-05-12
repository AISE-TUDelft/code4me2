package me.code4me.services.modules.aggregators

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import java.util.concurrent.CopyOnWriteArrayList

abstract class BaseAggregator : PluginModule {
    protected val modules = mutableListOf<PluginModule>()
    abstract val submodules: List<PluginModule>

    override fun initializeModules() {
        submodules.forEach {
            it.initializeModules()
            registerModule(it)
        }
    }

    fun registerModule(pluginModule: PluginModule) {
        modules.add(pluginModule)
        println("Module registered: ${pluginModule.moduleName}")
    }

    override fun collectData(request: InlineCompletionRequest): List<Record> =
        runBlocking {
            val aggregatedData = CopyOnWriteArrayList<Record>()
            coroutineScope {
                val deferredResults =
                    modules.map { module ->
                        async {
                            module.collectData(request)
                        }
                    }
                deferredResults.forEach { deferred ->
                    aggregatedData.addAll(deferred.await())
                }
            }
            aggregatedData
        }

    override fun getPreferenceList(): List<Preference> {
        return listOf(
            Preference(
                key = "useAI",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Use AI Completion",
                description = "Use AI-powered code completion",
            ),
            Preference(
                key = "maxSuggestions",
                type = PreferenceType.STRING,
                defaultValue = "5",
                displayName = "Max Suggestions",
                description = "Maximum number of suggestions to show",
            ),
        )
    }

    override fun getPreferenceClass(): PreferenceClass {
        TODO("Not yet implemented")
    }

    fun retrieveModules(): List<PluginModule> {
        return modules.toList()
    }
}
