package me.code4me.services.modules.manager

import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.code4me.services.config.getConfig
import me.code4me.services.config.models.ModuleConfig
import me.code4me.services.modules.PluginModule
import me.code4me.services.state.PrefState
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import me.code4me.utils.record.Record
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Retrieves the ModuleManager service instance for the given project.
 *
 * @param project The IntelliJ project instance
 * @return The ModuleManager service for the project
 */
@Deprecated("use `getModuleManager` instead")
fun getModuleManager(project: Project): ModuleManager {
    return getModuleManager()
}

fun getModuleManager(): ModuleManager {
    return service<ModuleManager>()
}

/**
 * Central orchestrator for all plugin modules within the Code4Me system.
 *
 * The ModuleManager is responsible for:
 * - Managing the lifecycle of all plugin modules (initialization, enabling/disabling)
 * - Coordinating data collection across all active modules
 * - Maintaining module state and dependencies
 * - Integrating with the plugin's preference system
 * - Providing concurrent data collection capabilities
 *
 * This service operates at the project level, ensuring each IntelliJ project
 * has its own isolated module management context.
 *
 * @since 1.0.0
 */
@Service
class ModuleManager : PluginModule {
    companion object {
        private val LOG = thisLogger()
    }

    // Core module storage and state management
    private val modules: MutableList<PluginModule> = mutableListOf()
    private val enabledModuleIds: MutableSet<String> = mutableSetOf()
    private val initializedModules: MutableSet<String> = mutableSetOf()
    private val moduleInstances: MutableMap<String, Any> = mutableMapOf()

    init {
        // Load previously enabled modules from persistent state
        val enabledModules = PrefState.getEnabledModules()
        enabledModuleIds.addAll(enabledModules)
        LOG.info("ModuleManager initialized with ${enabledModules.size} enabled modules")
    }

    override val moduleName: String = "ModuleManager"

    /**
     * Initializes all enabled modules and their dependencies.
     *
     * This method iterates through all currently enabled modules and ensures
     * they are properly initialized. Modules that fail initialization are
     * automatically disabled to prevent system instability.
     */
    override fun initializeModules() {
        val enabledModules = getEnabledModules()
        var successfulInitializations = 0

        enabledModules.forEach { module ->
            if (initializeModule(module)) {
                successfulInitializations++
            }
        }

        LOG.info("Module initialization completed: $successfulInitializations/${enabledModules.size} modules initialized successfully")
    }

    /**
     * Stores and registers a collection of plugin modules.
     *
     * This method performs a complete refresh of the module registry:
     * 1. Clears existing modules
     * 2. Stores new modules and registers them with the preference system
     * 3. Auto-enables modules that are configured as enabled by default
     * 4. Recursively processes all submodules
     *
     * @param modulesToStore List of modules to register and store
     */
    fun storeModules(modulesToStore: List<PluginModule>) {
        modules.clear()
        modules.addAll(modulesToStore)

        val configService = getConfig()
        val availableModuleConfigs = configService.getAvailableModules()

        modules.forEach { module ->
            val newlyAddedModuleIds = registerModuleRecursively(module)
            processDefaultEnabledModules(newlyAddedModuleIds, availableModuleConfigs)

            // Process the enabled state of top-level modules
            processModuleEnabledState(module, availableModuleConfigs)
        }

        LOG.info("Stored ${modulesToStore.size} modules with their submodules")
    }

    /**
     * Recursively registers a module and all its submodules with the preference system.
     *
     * This method ensures complete module tree registration, handling nested
     * aggregators and their submodules. It also applies default enabled states
     * from configuration.
     *
     * @param module The root module to register
     * @return List of newly registered module IDs
     */
    private fun registerModuleRecursively(module: PluginModule): List<String> {
        val newModuleIds = mutableListOf<String>()

        // Register the module itself
        if (PrefState.registerModule(module)) {
            newModuleIds.add(module.getPreferenceId())
        }

        // Process submodules
        try {
            val submodules = module.getSubmodules()
            val configService = getConfig()
            val availableModuleConfigs = configService.getAvailableModules()

            submodules.forEach { submodule ->
                newModuleIds.addAll(registerModuleRecursively(submodule))
                processSubmoduleEnabledState(submodule, availableModuleConfigs)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to process submodules for ${module.moduleName}: ${e.message}")
        }

        return newModuleIds
    }

    /**
     * Processes the enabled state of a submodule based on configuration.
     */
    private fun processSubmoduleEnabledState(
        submodule: PluginModule,
        availableModuleConfigs: List<ModuleConfig>,
    ) {
        val isEnabledInConfig = isSubmoduleEnabledInConfig(submodule, availableModuleConfigs)
        val isAlreadyEnabled = submodule.getPreferenceId() in enabledModuleIds

        if (isEnabledInConfig || isAlreadyEnabled) {
            enableModule(submodule.getPreferenceId())
        }
    }

    /**
     * Determines if a submodule is enabled in the configuration.
     */
    private fun isSubmoduleEnabledInConfig(
        submodule: PluginModule,
        availableModuleConfigs: List<ModuleConfig>,
    ): Boolean {
        // Check direct configuration
        val directConfig =
            availableModuleConfigs.find {
                it.className == submodule.javaClass.name || it.id == submodule.getPreferenceId()
            }

        // Check if submodule is in any module's submodules list
        val submoduleConfig =
            availableModuleConfigs.flatMap { it.submodules }
                .find { it.className == submodule.javaClass.name || it.id == submodule.getPreferenceId() }

        return submoduleConfig?.enabled ?: directConfig?.enabled ?: false
    }

    /**
     * Determines if a module is enabled in the configuration.
     */
    private fun isModuleEnabledInConfig(
        module: PluginModule,
        availableModuleConfigs: List<ModuleConfig>,
    ): Boolean {
        // Check direct configuration
        val directConfig =
            availableModuleConfigs.find {
                it.className == module.javaClass.name || it.id == module.getPreferenceId()
            }

        return directConfig?.enabled ?: false
    }

    /**
     * Processes the enabled state of a module based on configuration.
     */
    private fun processModuleEnabledState(
        module: PluginModule,
        availableModuleConfigs: List<ModuleConfig>,
    ) {
        val isEnabledInConfig = isModuleEnabledInConfig(module, availableModuleConfigs)
        val isAlreadyEnabled = module.getPreferenceId() in enabledModuleIds

        if (isEnabledInConfig || isAlreadyEnabled) {
            enableModule(module.getPreferenceId())
        }
    }

    /**
     * Enables newly added modules that are configured as enabled by default.
     */
    private fun processDefaultEnabledModules(
        newlyAddedModuleIds: List<String>,
        availableModuleConfigs: List<ModuleConfig>,
    ) {
        newlyAddedModuleIds.forEach { moduleId ->
            val shouldEnable =
                availableModuleConfigs.any { config ->
                    (config.id == moduleId && config.enabled) ||
                        config.submodules.any { sub -> sub.id == moduleId && sub.enabled }
                }

            if (shouldEnable && !enabledModuleIds.contains(moduleId)) {
                enableModule(moduleId)
            }
        }
    }

    /**
     * Initializes a specific module and its dependencies.
     *
     * @param module The module to initialize
     * @return True if initialization succeeded, false otherwise
     */
    private fun initializeModule(module: PluginModule): Boolean {
        if (initializedModules.contains(module.getModuleId())) {
            return true
        }

        if (!module.checkDependencies()) {
            LOG.warn("Module ${module.moduleName} failed dependency check")
            return false
        }

        return try {
            getOrCreateModuleService(module)
            module.initializeModules()
            initializedModules.add(module.getModuleId())
            LOG.debug("Successfully initialized module: ${module.moduleName}")
            true
        } catch (e: Exception) {
            LOG.error("Failed to initialize module: ${module.moduleName}", e)
            disableModule(module.getPreferenceId())
            false
        }
    }

    /**
     * Gets or creates a service instance for the specified module.
     *
     * @param module The module to get or create a service for
     * @return The module service instance, or null if creation failed
     */
    private fun getOrCreateModuleService(module: PluginModule): PluginModule? {
        val moduleId = module.getModuleId()

        moduleInstances[moduleId]?.let { existing ->
            return existing as? PluginModule
        }

        moduleInstances[moduleId] = module
        return module
    }

    /**
     * Collects data from all registered modules concurrently.
     *
     * This method orchestrates parallel data collection across all stored modules,
     * regardless of their enabled state. For enabled-only collection, use the
     * enabled modules list explicitly.
     *
     * @param request The inline completion request context
     * @return Aggregated list of records from all modules
     */
    override fun collectData(request: InlineCompletionRequest): List<Record> {
        return ApplicationManager.getApplication().runReadAction<List<Record>> {
            runBlocking {
                val aggregatedData = CopyOnWriteArrayList<Record>()

                coroutineScope {
                    val deferredResults =
                        modules.map { module ->
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

                LOG.debug("Collected ${aggregatedData.size} records from ${modules.size} modules")
                aggregatedData
            }
        }
    }

    /**
     * Collects data from all enabled modules concurrently.
     *
     * This method orchestrates parallel data collection across all enabled modules.
     * It is optimized for performance and ensures that only active modules contribute
     * to the data collection process.
     *
     * @param request The inline completion request context
     * @return Aggregated list of records from enabled modules
     */
    override fun afterInsertion(
        environment: InlineCompletionInsertEnvironment,
        elements: List<InlineCompletionElement>,
    ) {
        // Call the afterInsertion method on all modules
        modules.forEach { module ->
            try {
                module.afterInsertion(environment, elements)
            } catch (e: Exception) {
                LOG.warn("After insertion failed for module: ${module.moduleName}", e)
            }
        }
    }

    override fun getPreferenceList(): List<Preference> {
        return listOf(
            Preference(
                key = "useAI",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Use AI Completion",
                description = "Enable AI-powered code completion suggestions",
            ),
            Preference(
                key = "maxSuggestions",
                type = PreferenceType.STRING,
                defaultValue = "5",
                displayName = "Max Suggestions",
                description = "Maximum number of completion suggestions to display",
            ),
            Preference(
                key = "minConfidence",
                type = PreferenceType.DOUBLE,
                defaultValue = "0.85",
                displayName = "Minimum Confidence",
                description = "Minimum confidence threshold for displaying suggestions (0.0 to 1.0)",
            ),
            Preference(
                key = "requestTimeout",
                type = PreferenceType.INT,
                defaultValue = "5000",
                displayName = "Request Timeout",
                description = "Maximum time in milliseconds to wait for completion responses",
            ),
        )
    }

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.SYSTEM
    }

    // Public API for module management

    /**
     * Returns all available modules regardless of enabled state.
     */
    fun getAvailableModules(): List<PluginModule> = modules.toList()

    /**
     * Returns the set of currently enabled module IDs.
     */
    fun getEnabledModuleIds(): Set<String> = enabledModuleIds.toSet()

    /**
     * Returns only the modules that are currently enabled.
     */
    fun getEnabledModules(): List<PluginModule> {
        return modules.filter { enabledModuleIds.contains(it.getModuleId()) }
    }

    /**
     * Enables a module by its ID and initializes it if not already done.
     *
     * @param moduleId The preference ID of the module to enable
     */
    fun enableModule(moduleId: String) {
        enabledModuleIds.add(moduleId)
        PrefState.enableModule(moduleId)

        getModule(moduleId)?.let { module ->
            if (!initializedModules.contains(module.getModuleId())) {
                initializeModule(module)
            }
        }

        LOG.debug("Enabled module: $moduleId")
    }

    /**
     * Disables a module by its ID and cleans up its resources.
     *
     * @param moduleId The preference ID of the module to disable
     */
    fun disableModule(moduleId: String) {
        enabledModuleIds.remove(moduleId)
        PrefState.disableModule(moduleId)

        // Clean up module instance and initialization state
        moduleInstances[moduleId]?.let { instance ->
            if (instance is PluginModule) {
                initializedModules.remove(instance.getModuleId())
                moduleInstances.remove(moduleId)
            }
        }

        LOG.debug("Disabled module: $moduleId")
    }

    /**
     * Checks if a module is currently enabled.
     *
     * @param moduleId The preference ID of the module to check
     * @return True if the module is enabled, false otherwise
     */
    fun isModuleEnabled(moduleId: String): Boolean {
        return enabledModuleIds.contains(moduleId)
    }

    /**
     * Retrieves a module by its preference ID.
     *
     * @param moduleId The preference ID of the module to find
     * @return The module instance, or null if not found
     */
    fun getModule(moduleId: String): PluginModule? {
        return modules.find { it.getPreferenceId() == moduleId }
    }

    override fun toString(): String = moduleName
}
