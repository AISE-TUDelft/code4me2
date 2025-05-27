package me.code4me.services.modules.manager

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.code4me.services.config.getConfig
import me.code4me.services.modules.PluginModule
import me.code4me.services.state.PrefState
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import me.code4me.utils.record.Record
import java.util.concurrent.CopyOnWriteArrayList

fun getModuleManager(project: Project): ModuleManager {
    return project.service<ModuleManager>()
}

@Service(Service.Level.PROJECT)
class ModuleManager(private val project: Project) : PluginModule {
    // Module management
    private val modules: MutableList<PluginModule> = mutableListOf()
    private val enabledModuleIds: MutableSet<String> = mutableSetOf()
    private val initializedModules: MutableSet<String> = mutableSetOf()
    private val moduleInstances: MutableMap<String, Any> = mutableMapOf()

    init {
        // Initialize enabled modules from preferences
        val enabledModules = PrefState.getEnabledModules()
        enabledModuleIds.addAll(enabledModules)
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

        // Get the configuration service to check default enabled state
        val configService = getConfig()
        val availableModuleConfigs = configService.getAvailableModules()

        // Register modules with PrefState
        modules.forEach { module ->
            val newlyAddedModuleIds = registerModuleRecursively(module)

            // Find the module config to check if it should be enabled by default
            val moduleConfig =
                availableModuleConfigs.find {
                    it.className == module.javaClass.name || it.id == module.getPreferenceId()
                }

            // Enable newly added modules that should be enabled by default
            newlyAddedModuleIds.forEach { moduleId ->
                val moduleConfigForId =
                    availableModuleConfigs.find {
                        it.id == moduleId || it.submodules.any { sub -> sub.id == moduleId }
                    }
                if (moduleConfigForId?.enabled == true && !enabledModuleIds.contains(moduleId)) {
                    enableModule(moduleId)
                }
            }
        }
    }

    /**
     * Recursively registers a module and all its submodules with PrefState
     *
     * @param module The module to register
     */
    private fun registerModuleRecursively(module: PluginModule): List<String> {
        val newModuleIds = mutableListOf<String>()

        // Register the module itself and add to list if newly registered
        if (PrefState.registerModule(module)) {
            newModuleIds.add(module.getPreferenceId())
        }

        // Get all submodules and register them recursively
        try {
            val submodules = module.getSubmodules()

            // Get the configuration service to check default enabled state
            val configService = me.code4me.services.config.getConfig()
            val availableModuleConfigs = configService.getAvailableModules()

            submodules.forEach { submodule ->
                newModuleIds.addAll(registerModuleRecursively(submodule))

                // Find the submodule config to check if it should be enabled by default
                val submoduleConfig =
                    availableModuleConfigs.find {
                        it.className == submodule.javaClass.name || it.id == submodule.getPreferenceId()
                    }

                // Also check if this submodule is in any module's submodules list in the config
                val isSubmoduleInConfig =
                    availableModuleConfigs.any { moduleConfig ->
                        moduleConfig.submodules.any {
                            it.className == submodule.javaClass.name || it.id == submodule.getPreferenceId()
                        }
                    }

                // If submodule is found in config, check its enabled status
                val isEnabledInConfig =
                    if (isSubmoduleInConfig) {
                        availableModuleConfigs.flatMap { it.submodules }
                            .find { it.className == submodule.javaClass.name || it.id == submodule.getPreferenceId() }
                            ?.enabled ?: false
                    } else {
                        submoduleConfig?.enabled ?: false
                    }

                // Enable submodules that are enabled by default in config or already in enabledModuleIds
                if (isEnabledInConfig || submodule.getPreferenceId() in enabledModuleIds) {
                    enableModule(submodule.getPreferenceId())
                }
            }
        } catch (e: Exception) {
            // Ignore exceptions if getSubmodules fails or is not implemented
            println("Warning: Failed to get submodules for ${module.moduleName}: ${e.message}")
        }

        return newModuleIds
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
