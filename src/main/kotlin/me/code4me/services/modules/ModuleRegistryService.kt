package me.code4me.services.modules

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import me.code4me.services.modules.manager.getModuleManager

/**
 * DEPRECATED: This service has been refactored and its functionality moved to ModuleManager.
 * 
 * The ModuleRegistryService has been removed as part of a refactoring to improve the module management architecture.
 * Its responsibilities have been moved to the ModuleManager class.
 * 
 * The new architecture flow is:
 * 1. ConfigService instantiates modules from configuration
 * 2. MyProjectActivity gets the instantiated modules and stores them in ModuleManager
 * 3. ModuleManager handles module storage, initialization, and management
 * 
 * @see me.code4me.services.modules.manager.ModuleManager
 * @see me.code4me.startup.PluginStartupActivity
 */
@Deprecated("This service has been refactored and its functionality moved to ModuleManager")
@Service(Service.Level.PROJECT)
class ModuleRegistryService(private val project: Project) {
    /**
     * This method is deprecated. Use ModuleManager.initializeModules() instead.
     * 
     * @see me.code4me.services.modules.manager.ModuleManager.initializeModules
     */
    @Deprecated("Use ModuleManager.initializeModules() instead", 
                ReplaceWith("getModuleManager(project).initializeModules()", 
                "me.code4me.services.modules.manager.getModuleManager"))
    fun initializeModules() {
        // Delegate to ModuleManager
        getModuleManager(project).initializeModules()
    }
}
