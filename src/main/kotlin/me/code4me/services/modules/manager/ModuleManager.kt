package me.code4me.services.modules.manager

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.services.modules.aggregators.BaseContextAggregator
import me.code4me.services.modules.aggregators.BaseTelemetryAggregator
import me.code4me.services.state.PrefState
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import java.util.concurrent.CopyOnWriteArrayList

fun getModuleManager(project: Project): ModuleManager {
    return project.service<ModuleManager>()
}

@Service(Service.Level.PROJECT)
class ModuleManager(private val project: Project) : PluginModule {
    private val aggregators = mutableListOf<PluginModule>()
    private val contextAggregators = mutableListOf<PluginModule>()

    // Module management
    private val modules: MutableList<PluginModule> = mutableListOf()
    private val enabledModuleIds: MutableSet<String> = mutableSetOf()
    private val initializedModules: MutableSet<String> = mutableSetOf()
    private val moduleInstances: MutableMap<String, Any> = mutableMapOf()

    // List of submodules to be initialized
    private val submodules =
        listOf(
            BaseContextAggregator(),
            BaseTelemetryAggregator(),
        )

    init {
        // Initialize the base aggregators
        initializeBaseAggregators()

        // Initialize enabled modules from preferences
        val enabledModules = PrefState.getEnabledModules()
        enabledModuleIds.addAll(enabledModules)
    }

    // Initialize base aggregators
    private fun initializeBaseAggregators() {
        // Create and register aggregators
        submodules.forEach {
            it.initializeModules()
            registerAggregator(it)
        }

        println("Base aggregators initialized successfully.")
    }

    // Initialize modules and aggregators
    override fun initializeModules() {
        // Initialize all enabled modules
        getEnabledModules().forEach { module ->
            initializeModule(module)
        }

        println("All modules initialized successfully.")
    }

    /**
     * Store modules in the module manager
     * 
     * @param modulesToStore List of modules to store
     */
    fun storeModules(modulesToStore: List<PluginModule>) {
        // Clear existing modules
        modules.clear()

        // Store the modules
        modules.addAll(modulesToStore)

        // Register modules with PrefState
        modules.forEach { module ->
            PrefState.registerModule(module)

            // Enable modules that are enabled by default in config
            if (module.getPreferenceId() in enabledModuleIds) {
                enableModule(module.getPreferenceId())
            }
        }
    }

    /**
     * Initialize a specific module and its dependencies.
     *
     * @param module The module to initialize.
     * @return True if initialization was successful, false otherwise.
     */
    private fun initializeModule(module: PluginModule): Boolean {
        // Skip if already initialized
        if (initializedModules.contains(module.getModuleId())) {
            return true
        }

        // Check dependencies
        if (!module.checkDependencies()) {
            return false
        }

        try {
            // Get or create the module service instance
            getOrCreateModuleService(module)

            // Initialize the module
            module.initializeModules()
            initializedModules.add(module.getModuleId())
            return true
        } catch (e: Exception) {
            // If initialization fails, disable the module
            disableModule(module.getPreferenceId())
            return false
        }
    }

    /**
     * Gets or creates a module service instance.
     * 
     * @param module The module to get or create a service for.
     * @return The module service instance.
     */
    private fun getOrCreateModuleService(module: PluginModule): PluginModule? {
        val moduleId = module.getModuleId()

        // If the module instance already exists, return it
        if (moduleInstances.containsKey(moduleId)) {
            return moduleInstances[moduleId] as? PluginModule
        }

        // Store the module instance
        moduleInstances[moduleId] = module

        return module
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
    override fun collectData(request: InlineCompletionRequest): List<Record> =
        runBlocking {
            val aggregatedData = CopyOnWriteArrayList<Record>()
            coroutineScope {
                val deferredResults =
                    aggregators.map { module ->
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
            Preference(
                key = "minConfidence",
                type = PreferenceType.DOUBLE,
                defaultValue = "0.85",
                displayName = "Minimum Confidence",
                description = "Minimum confidence threshold for suggestions (0.0 to 1.0)",
            ),
            Preference(
                key = "requestTimeout",
                type = PreferenceType.INT,
                defaultValue = "5000",
                displayName = "Request Timeout",
                description = "Maximum time in milliseconds to wait for completions",
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

    /**
     * Get all available modules
     */
    fun getAvailableModules(): List<PluginModule> {
        return modules.toList()
    }

    /**
     * Get enabled module IDs
     */
    fun getEnabledModuleIds(): Set<String> {
        return enabledModuleIds.toSet()
    }

    /**
     * Get enabled modules
     */
    fun getEnabledModules(): List<PluginModule> {
        return modules.filter { enabledModuleIds.contains(it.getPreferenceId()) }
    }

    /**
     * Enable a module
     */
    fun enableModule(moduleId: String) {
        enabledModuleIds.add(moduleId)
        PrefState.enableModule(moduleId)

        // Initialize the module if it's not already initialized
        val module = getModule(moduleId)
        if (module != null && !initializedModules.contains(module.getModuleId())) {
            initializeModule(module)
        }
    }

    /**
     * Disable a module
     */
    fun disableModule(moduleId: String) {
        enabledModuleIds.remove(moduleId)
        PrefState.disableModule(moduleId)

        // Dispose the module if it's initialized
        val moduleInstance = moduleInstances[moduleId]
        if (moduleInstance is PluginModule) {
            moduleInstance.dispose()
            initializedModules.remove(moduleId)
            moduleInstances.remove(moduleId)
        }
    }

    /**
     * Check if a module is enabled
     */
    fun isModuleEnabled(moduleId: String): Boolean {
        return enabledModuleIds.contains(moduleId)
    }

    /**
     * Get a module by ID
     */
    fun getModule(moduleId: String): PluginModule? {
        return modules.find { it.getPreferenceId() == moduleId }
    }
}
