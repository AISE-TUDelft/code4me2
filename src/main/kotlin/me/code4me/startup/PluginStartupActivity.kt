package me.code4me.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import me.code4me.services.config.ConfigService
import me.code4me.services.modules.manager.getModuleManager

/**
 * Project activity that initializes modules at startup.
 * 
 * This activity is executed after the ConfigService has been initialized
 * by the ConfigInitializer, ensuring that configuration is loaded first
 * before modules are initialized.
 */
class PluginStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Ensure ConfigService is loaded
        val configService = service<ConfigService>()

        // Instantiate modules from configuration
        val instantiatedModules = configService.instantiateModules()

        // Get the ModuleManager for this project
        val moduleManager = getModuleManager(project)

        // Store the instantiated modules in the ModuleManager
        moduleManager.storeModules(instantiatedModules)

        // Initialize all enabled modules
        moduleManager.initializeModules()

        thisLogger().info("Modules initialized successfully.")
    }
}
