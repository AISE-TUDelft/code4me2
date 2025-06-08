package me.code4me.startup

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import me.code4me.api.generated.model.ActivateProject
import me.code4me.api.generated.model.CreateProject
import me.code4me.api.wrapper.CookieAwareApiClient.Companion.getAuthToken
import me.code4me.services.app.getAppService
import me.code4me.services.config.getConfig
import me.code4me.services.modules.manager.getModuleManager
import me.code4me.services.project.getProjectTokenService
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
