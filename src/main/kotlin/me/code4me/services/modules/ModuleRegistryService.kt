package me.code4me.services.modules

import PluginModule
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import me.code4me.services.config.ModuleConfigService
import me.code4me.services.state.PrefState

/**
 * Service for managing plugin modules
 */
@Service
class ModuleRegistryService {
    private val moduleConfigService = ModuleConfigService.getInstance()
    private val modules: MutableList<PluginModule> = mutableListOf()
    private val enabledModuleIds: MutableSet<String> = mutableSetOf()

    init {
        // Load modules from configuration
        loadModules()
        
        // Initialize enabled modules from preferences
        val enabledModules = PrefState.getEnabledModules()
        enabledModuleIds.addAll(enabledModules)
    }

    /**
     * Load modules from configuration
     */
    private fun loadModules() {
        // Clear existing modules
        modules.clear()
        
        // Load modules from configuration
        val configModules = moduleConfigService.instantiateModules()
        modules.addAll(configModules)
        
        // Register modules with PrefState
        modules.forEach { module ->
            PrefState.registerModule(module)
            
            // Enable modules that are enabled by default in config
            val moduleConfig = moduleConfigService.getAvailableModules().find { it.id == module.getPreferenceId() }
            if (moduleConfig?.enabled == true) {
                enableModule(module.getPreferenceId())
            }
        }
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
    }

    /**
     * Disable a module
     */
    fun disableModule(moduleId: String) {
        enabledModuleIds.remove(moduleId)
        PrefState.disableModule(moduleId)
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

    companion object {
        /**
         * Get the ModuleRegistryService instance
         */
        fun getInstance(): ModuleRegistryService {
            return service()
        }
    }
}