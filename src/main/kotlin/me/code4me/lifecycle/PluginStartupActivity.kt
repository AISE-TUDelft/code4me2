package me.code4me.lifecycle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.code4me.api.generated.model.UpdateMultiFileContext
import me.code4me.services.app.getAppService
import me.code4me.services.config.getConfig
import me.code4me.services.modules.context.MultiFileContextRetrievalModule
import me.code4me.services.config.ConfigService
import me.code4me.services.modules.manager.getModuleManager
import me.code4me.services.project.getProjectMultiFileContextService
import me.code4me.services.state.getAuthState
import me.code4me.services.state.getPrefState
import me.code4me.utils.api.activateOrCreateProject
import me.code4me.utils.api.fromSerializableMap
import me.code4me.utils.notification.showLoginRequiredNotification
import me.code4me.utils.notification.showTokenInvalidationNotification
import toApiModel
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Project activity that initializes modules at startup.
 *
 * This activity is executed after the ConfigService has been initialized
 * by the ConfigInitializer, ensuring that configuration is loaded first
 * before modules are initialized.
 */
class PluginStartupActivity : ProjectActivity {
    private val LOG = thisLogger()

    override suspend fun execute(project: Project) {
        // Handle authentication and session acquisition
        handleAuthenticationAndSession(project)
        // Register the ProjectCloseListener to save the last chat when a project is closed
        val connection: MessageBusConnection = project.messageBus.connect()
        connection.subscribe(ProjectManager.TOPIC, ProjectCloseListener())
        thisLogger().info("ProjectCloseListener registered successfully.")
    }

    private fun handleAuthenticationAndSession(project: Project) {
        val authState = getAuthState()
        val authToken = authState.getToken()

        if (authToken != null) {
            thisLogger().info("Acquiring session with stored token")
            try {
                // First load the config and set the preferences
                val response = getAppService().getCurrentUser()

                if (!response.user.preference.isNullOrEmpty()) {
                    LOG.info("User preferences found, updating preference state")
                    getPrefState().fromSerializableMap(response.user.preference!!)
                } else {
                    LOG.info("No user preferences found, using default preference state")
                }

                val configService = ConfigService.fromConfigString(response.config)
                val instantiatedModules = configService.instantiateModules()
                LOG.info("Modules instantiated successfully: ${instantiatedModules.size} modules")

                // Get the ModuleManager for this project
                val moduleManager = getModuleManager()
                // Store the instantiated modules in the ModuleManager
                moduleManager.storeModules(instantiatedModules)

                // Initialize all enabled modules
                moduleManager.initializeModules()
                startCacheValidation(project)
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

                // Show notification about token invalidation and clear user data
                project.showTokenInvalidationNotification()

                // Clear user data as the token is invalid
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        authState.clearUserData()
                        LOG.info("User data cleared successfully during sign out")
                    } catch (e: Exception) {
                        LOG.error("Failed to clear user data during sign out", e)
                    }
                }
            }
        } else {
            thisLogger().warn("No authentication token found. Skipping session acquisition.")
            // Show notification prompting user to login
            project.showLoginRequiredNotification()
        }
    }

    private fun startCacheValidation(project: Project) {
        val contextService = getProjectMultiFileContextService(project)
        val appService = getAppService()
        val basePath = project.basePath ?: return

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            LOG.info("Cache validation coroutine started")
            while (isActive) {
                // TODO choose a better interval, potentially use a config value
                delay(TimeUnit.MINUTES.toMillis(1))

                val cacheDir = contextService.contextCacheDir
                val cachedFiles = cacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".xml") } ?: continue

                for (cacheFile in cachedFiles) {
                    val sanitizedName = cacheFile.name
                    val originalPath = contextService.getMappedPath(sanitizedName)

                    if (originalPath == null) {
                        continue
                    }
                    val actualFile = File(originalPath)

                    LOG.info("Checking file existence: $originalPath — exists=${actualFile.exists()}")

                    if (!actualFile.exists()) {
                        val lines = cacheFile.readLines()
                        val diff =
                            listOf(
                                MultiFileContextRetrievalModule.FileContextChangeData(
                                    changeType = "delete",
                                    startLine = 0,
                                    endLine = lines.size,
                                    newLines = emptyList(),
                                ).toApiModel(),
                            )

                        val relativePath = originalPath.removePrefix(basePath).removePrefix(File.separator)
                        val update = UpdateMultiFileContext(contextUpdates = mapOf(relativePath to diff))
                        LOG.info("File deleted, sending update: $relativePath")

                        try {
                            appService.sendMultiFileContextUpdate(update)
                            cacheFile.delete()
                            // TODO check if it's worth it to remove the mapping (optimization)
                            contextService.removeMapping(cacheFile.name)
                        } catch (e: Exception) {
                            thisLogger().warn("Failed to send delete diff for missing file: $relativePath", e)
                        }
                    }
                }
            }
        }
    }
}
