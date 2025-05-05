package me.code4me.services.modules.aggregators

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import java.util.concurrent.CopyOnWriteArrayList
import me.code4me.services.modules.telemetry.*




class BaseTelemetryAggregator : PluginModule {
    override val moduleName: String
        get() = "BaseTelemetryAggregator"

    private val modules = mutableListOf<PluginModule>()

    // Initialize modules and aggregators
    override fun initializeModules() {
        val basicTelemetryModule = BasicTelemetryModule()
        basicTelemetryModule.initializeModules()
        registerModule(basicTelemetryModule)
    }

    // Register a module
    fun registerModule(pluginModule: PluginModule) {
        modules.add(pluginModule)
        println("Module registered: ${pluginModule.moduleName}")
    }

    /**
     * Collect data from all registered modules and aggregators.
     * @return List of records containing the collected data.
     */
    override fun collectData(): List<Record> = runBlocking {
        val aggregatedData = CopyOnWriteArrayList<Record>()
        coroutineScope {
            val deferredResults = modules.map { module ->
                async {
                    module.collectData()
                }
            }
            deferredResults.forEach { deferred ->
                aggregatedData.addAll(deferred.await())
            }
        }
        aggregatedData
    }

    override fun getStatus(): String {
        return "BaseTelemetryAggregator is running"
    }

    override fun getPreferenceList(): List<Preference> {
        return listOf(
            Preference(
                key = "useAI",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Use AI Completion",
                description = "Use AI-powered code completion"
            ),
            Preference(
                key = "maxSuggestions",
                type = PreferenceType.STRING,
                defaultValue = "5",
                displayName = "Max Suggestions",
                description = "Maximum number of suggestions to show"
            )
        )
    }

    override fun getPreferenceClass(): PreferenceClass {
        TODO("Not yet implemented")
    }

    fun getModules(): List<PluginModule> {
        return modules.toList()
    }
}