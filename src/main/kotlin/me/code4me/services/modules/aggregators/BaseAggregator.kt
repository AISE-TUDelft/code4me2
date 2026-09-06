
package me.code4me.services.modules.aggregators

import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.code4me.services.config.getConfig
import me.code4me.services.modules.PluginModule
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.record.Record
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Abstract base class for all aggregator modules in the Code4Me plugin system.
 *
 * This class provides the foundation for aggregating data from multiple submodules
 * in a concurrent and efficient manner. Aggregators are responsible for:
 * - Loading and managing submodules from configuration
 * - Coordinating data collection across multiple modules
 * - Providing thread-safe access to collected data
 * - Managing module lifecycle and dependencies
 *
 * Aggregators utilize Kotlin coroutines for concurrent data collection, ensuring
 * that multiple submodules can collect data in parallel without blocking the UI thread.
 * All data collection operations are performed asynchronously and safely aggregated.
 *
 * @since 1.0.0
 * @see PluginModule
 * @see BaseContextAggregator
 * @see BaseBehavioralTelemetryAggregator
 * @see BaseContextualTelemetryAggregator
 */
abstract class BaseAggregator : PluginModule {
    companion object {
        private val LOG = thisLogger()
    }

    /**
     * Thread-safe list of registered submodules managed by this aggregator.
     * Uses [CopyOnWriteArrayList] to ensure safe concurrent access during
     * data collection operations.
     */
    protected val modules = mutableListOf<PluginModule>()

    /**
     * The configuration module identifier used to locate this aggregator's
     * configuration in the plugin configuration file.
     *
     * This ID is used by the configuration service to:
     * - Load submodule configurations
     * - Apply aggregator-specific settings
     * - Manage module dependencies
     */
    abstract val configModuleId: String

    /**
     * Initializes the aggregator and all its configured submodules.
     *
     * This method performs the following operations:
     * 1. Loads submodule configurations from the plugin configuration
     * 2. Instantiates each configured submodule
     * 3. Initializes each submodule recursively
     * 4. Registers the initialized modules with this aggregator
     *
     * The initialization process is recursive, meaning that if any submodule
     * is itself an aggregator, its submodules will also be initialized.
     *
     * @throws IllegalStateException If module configuration is invalid
     * @throws ClassNotFoundException If a configured module class cannot be found
     */
    override fun initializeModules() {
        try {
            val configModules = loadModulesFromConfig()

            configModules.forEach { module ->
                module.initializeModules()
                registerModule(module)
            }

            LOG.info("Initialized aggregator '$moduleName' with ${modules.size} submodules")
        } catch (e: Exception) {
            LOG.error("Failed to initialize aggregator '$moduleName'", e)
            throw IllegalStateException("Aggregator initialization failed", e)
        }
    }

    /**
     * Returns the preference identifier for this aggregator module.
     *
     * By default, this delegates to the parent implementation, but can be
     * overridden by specific aggregator implementations if custom preference
     * handling is required.
     *
     * @return The preference identifier string
     */
    override fun getPreferenceId(): String {
        return super.getPreferenceId()
    }

    /**
     * Loads and instantiates submodules from the plugin configuration.
     *
     * This method queries the configuration service to:
     * 1. Retrieve all available module configurations
     * 2. Find the configuration specific to this aggregator
     * 3. Extract the list of configured submodules
     * 4. Instantiate each submodule using the configuration service
     *
     * If no configuration is found for this aggregator, or if no submodules
     * are configured, an empty list is returned.
     *
     * @return List of instantiated [PluginModule] instances from configuration
     * @throws ClassNotFoundException If a configured module class cannot be located
     * @throws IllegalArgumentException If module configuration is malformed
     */
    protected fun loadModulesFromConfig(): List<PluginModule> {
        val configService = getConfig()
        val availableModules = configService.getAvailableModules()

        val aggregatorConfig =
            availableModules.find { it.id == configModuleId }
                ?: run {
                    LOG.warn("No configuration found for aggregator: $configModuleId")
                    return emptyList()
                }

        if (aggregatorConfig.submodules.isEmpty()) {
            LOG.info("No submodules configured for aggregator: $configModuleId")
            return emptyList()
        }

        return try {
            configService.instantiateModulesFromConfigs(aggregatorConfig.submodules)
        } catch (e: Exception) {
            LOG.error("Failed to instantiate modules for aggregator: $configModuleId", e)
            throw IllegalArgumentException("Module instantiation failed", e)
        }
    }

    /**
     * Registers a plugin module as a submodule of this aggregator.
     *
     * This method adds the specified module to the internal list of managed
     * submodules. Registered modules will participate in data collection
     * operations and be included in dependency resolution.
     *
     * @param pluginModule The module to register as a submodule
     * @throws IllegalArgumentException If the module is null or already registered
     */
    fun registerModule(pluginModule: PluginModule) {
        require(!modules.contains(pluginModule)) {
            "Module '${pluginModule.moduleName}' is already registered"
        }

        modules.add(pluginModule)
        LOG.debug("Module registered: ${pluginModule.moduleName}")
    }

    /**
     * Collects data from all registered submodules concurrently.
     *
     * This method orchestrates parallel data collection across all submodules
     * using Kotlin coroutines. Each submodule's [collectData] method is called
     * asynchronously, and all results are aggregated into a single list.
     *
     * The collection process:
     * 1. Creates an async coroutine for each submodule
     * 2. Calls [collectData] on each submodule in parallel
     * 3. Waits for all operations to complete
     * 4. Aggregates all collected records into a thread-safe list
     *
     * @param request The inline completion request containing context information
     * @return List of [Record] objects collected from all submodules
     * @throws Exception If any submodule fails during data collection
     */
    override fun collectData(request: InlineCompletionRequest): List<Record> =
        runBlocking {
            val aggregatedData = CopyOnWriteArrayList<Record>()

            try {
                coroutineScope {
                    val submodules = getSubmodules()

                    if (submodules.isEmpty()) {
                        LOG.debug("No submodules available for data collection in: $moduleName")
                        return@coroutineScope
                    }

                    val deferredResults =
                        submodules.map { module ->
                            async {
                                try {
                                    module.collectData(request)
                                } catch (e: Exception) {
                                    LOG.warn("Data collection failed for module: ${module.moduleName}", e)
                                    emptyList<Record>()
                                }
                            }
                        }

                    deferredResults.forEach { deferred ->
                        aggregatedData.addAll(deferred.await())
                    }
                }

                LOG.debug("Collected ${aggregatedData.size} records from ${getSubmodules().size} submodules")
            } catch (e: Exception) {
                LOG.error("Failed to collect data from submodules", e)
                throw e
            }

            aggregatedData
        }

    /**
     * NOTE: if you ever need to wait for the return values or anything else,
     * consider using 'runBlocking' or other coroutine scopes
     */
    @OptIn(DelicateCoroutinesApi::class)
    override fun afterInsertion(
        environment: InlineCompletionInsertEnvironment,
        elements: List<InlineCompletionElement>,
    ) {
        val submodules = getSubmodules()
        // The read action must be *inside* the coroutine, not around the launches. Wrapping the
        // launches meant the read action was released as soon as the last coroutine was dispatched,
        // so each submodule actually ran afterInsertion with no read access at all.
        submodules.forEach { module ->
            GlobalScope.launch {
                ApplicationManager.getApplication().runReadAction {
                    try {
                        module.afterInsertion(environment, elements)
                    } catch (e: Exception) {
                        LOG.warn("After insertion failed for module: ${module.moduleName}", e)
                    }
                }
            }
        }
    }

    /**
     * Returns the preference list for this aggregator.
     *
     * Base aggregators typically don't define their own preferences,
     * as they primarily coordinate submodules. Subclasses can override
     * this method to provide aggregator-specific configuration options.
     *
     * @return Empty list of preferences by default
     */
    override fun getPreferenceList(): List<Preference> {
        return emptyList()
    }

    /**
     * Returns the preference class type for this aggregator.
     *
     * Aggregators are classified as MODULE-level preferences,
     * indicating they are part of the plugin's modular architecture.
     *
     * @return [PreferenceClass.MODULE]
     */
    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.MODULE
    }

    /**
     * Retrieves a copy of all currently registered modules.
     *
     * This method returns an immutable snapshot of the current module list,
     * preventing external modification while allowing safe iteration.
     *
     * @return Immutable list of currently registered [PluginModule] instances
     */
    fun retrieveModules(): List<PluginModule> {
        return modules.toList()
    }

    /**
     * Returns the list of active submodules for this aggregator.
     *
     * This method loads submodules from configuration each time it's called,
     * ensuring that any configuration changes are reflected. For performance-
     * sensitive operations, consider caching the result if appropriate.
     *
     * @return List of [PluginModule] instances loaded from current configuration
     */
    override fun getSubmodules(): List<PluginModule> {
        return loadModulesFromConfig()
    }
}
