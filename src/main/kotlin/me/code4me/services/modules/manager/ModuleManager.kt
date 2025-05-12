package me.code4me.services.modules.manager

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.services.modules.aggregators.BaseContextAggregator
import me.code4me.services.modules.aggregators.BaseTelemetryAggregator
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import java.util.concurrent.CopyOnWriteArrayList

fun getModuleManager(): ModuleManager {
    return service<ModuleManager>()
}

@Service
class ModuleManager : PluginModule {
    private val aggregators = mutableListOf<PluginModule>()

    init {
        initializeModules()
    }

    // Initialize modules and aggregators
    override fun initializeModules() {
        // Create and register aggregators
        val telemetryAggregator = BaseTelemetryAggregator()
        val contextAggregator = BaseContextAggregator()

        telemetryAggregator.initializeModules()
        contextAggregator.initializeModules()

        registerAggregator(telemetryAggregator)
        registerAggregator(contextAggregator)

        println("Modules and aggregators initialized successfully.")
    }

    // Register a module
    fun registerAggregator(aggregator: PluginModule) {
        aggregators.add(aggregator)
        println("Aggregator registered: ${aggregator.moduleName}")
    }

    override val moduleName: String
        get() = "ModuleManager"

    /**
     * Collect data from all registered modules and aggregators.
     * @return List of records containing the collected data.
     */
    override fun collectData(): List<Record> =
        runBlocking {
            val aggregatedData = CopyOnWriteArrayList<Record>()
            coroutineScope {
                val deferredResults =
                    aggregators.map { module ->
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
        TODO("Not yet implemented")
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

    override fun toString(): String {
        return moduleName
    }

    fun getAggregators(): List<PluginModule> {
        return aggregators.toList()
    }
}
