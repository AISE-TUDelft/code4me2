package me.code4me.lifecycle

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.messages.MessageBusConnection
import me.code4me.services.app.getAppService
import me.code4me.services.config.getConfig
import me.code4me.services.modules.manager.getModuleManager
import me.code4me.services.state.getAuthState
import me.code4me.utils.api.activateOrCreateProject

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
        val configService = getConfig()

        // Instantiate modules from configuration
        val instantiatedModules = configService.instantiateModules()

        // Get the ModuleManager for this project
        val moduleManager = getModuleManager(project)

        // Store the instantiated modules in the ModuleManager
        moduleManager.storeModules(instantiatedModules)

        // Initialize all enabled modules
        moduleManager.initializeModules()

        thisLogger().info("Modules initialized successfully.")

        // Register the ProjectCloseListener to save the last chat when a project is closed
        val connection: MessageBusConnection = project.messageBus.connect()
        connection.subscribe(ProjectManager.TOPIC, ProjectCloseListener())
        thisLogger().info("ProjectCloseListener registered successfully.")

        // if the auth token is set, acquire a session
        val authToken = getAuthState().getToken()
        if (authToken != null) {
            thisLogger().info("Acquiring session with stored token")
            try {
                // Acquire session using the stored auth token
                getAppService().acquireSessionWithStoredToken()
                thisLogger().info("Session acquired successfully.")
                activateOrCreateProject(project, thisLogger())
            } catch (e: Exception) {
                thisLogger().error("Failed to acquire session with stored token", e)
            }
        } else {
            thisLogger().warn("No authentication token found. Skipping session acquisition.")
        }
    }
}
