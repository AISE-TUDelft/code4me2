package me.code4me.services.modules.aggregators

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.code4me.services.config.getConfig
import me.code4me.services.modules.PluginModule
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.record.Record
import java.util.concurrent.CopyOnWriteArrayList

abstract class BaseAggregator : PluginModule {
    protected val modules = mutableListOf<PluginModule>()

    // This property is now deprecated - submodules should be loaded from config
    @Deprecated("Use loadModulesFromConfig() instead", ReplaceWith("loadModulesFromConfig()"))
    open val modulesList: List<PluginModule> = emptyList()

    // The module ID used to find configuration in the config file
    abstract val configModuleId: String

    override fun initializeModules() {
        // Load modules from config instead of using hard-coded list
        val configModules = loadModulesFromConfig()

        configModules.forEach {
            it.initializeModules()
            registerModule(it)
        }
    }

    override fun getPreferenceId(): String {
        return super.getPreferenceId()
    }

    /**
     * Loads modules from the configuration service based on the aggregator's module ID.
     *
     * @return List of instantiated modules from configuration
     */
    protected fun loadModulesFromConfig(): List<PluginModule> {
        val configService = getConfig()

        // Get all available modules from config
        val availableModules = configService.getAvailableModules()

        // Find this aggregator's configuration
        val aggregatorConfig = availableModules.find { it.id == configModuleId }

        // If no configuration is found or no submodules are defined, return empty list
        if (aggregatorConfig == null || aggregatorConfig.submodules.isEmpty()) {
            return emptyList()
        }

        // Instantiate the submodules
        return configService.instantiateModulesFromConfigs(aggregatorConfig.submodules)
    }

    fun registerModule(pluginModule: PluginModule) {
        modules.add(pluginModule)
        println("Module registered: ${pluginModule.moduleName}")
    }

    override fun collectData(request: InlineCompletionRequest): List<Record> =
        runBlocking {
            val aggregatedData = CopyOnWriteArrayList<Record>()
            coroutineScope {
                val submodules = getSubmodules()
                val deferredResults =
                    submodules.map { module ->
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
        return emptyList()
    }

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.MODULE
    }

    fun retrieveModules(): List<PluginModule> {
        return modules.toList()
    }

    override fun getSubmodules(): List<PluginModule> {
        return loadModulesFromConfig()
    }
}
