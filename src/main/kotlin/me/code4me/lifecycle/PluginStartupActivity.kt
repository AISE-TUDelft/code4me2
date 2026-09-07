package me.code4me.lifecycle

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.code4me.api.generated.infrastructure.ClientException
import me.code4me.api.generated.model.UpdateMultiFileContext
import me.code4me.services.agent.AgentStartupManager
import me.code4me.services.agent.LocalProxyServer
import me.code4me.services.app.AcpPreparationService
import me.code4me.services.app.getAppService
import me.code4me.services.config.ConfigService
import me.code4me.services.modules.context.MultiFileContextRetrievalModule
import me.code4me.services.modules.manager.getModuleManager
import me.code4me.services.project.getProjectMultiFileContextService
import me.code4me.services.state.TOKEN_PROPERTY
import me.code4me.services.state.getAuthState
import me.code4me.services.state.getPrefState
import me.code4me.utils.api.activateOrCreateProject
import me.code4me.utils.api.fromSerializableMap
import me.code4me.utils.notification.showAuthNotification
import me.code4me.utils.notification.showLoginRequiredNotification
import me.code4me.utils.notification.showTokenInvalidationNotification
import toApiModel
import java.beans.PropertyChangeListener
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Project activity that initializes modules at startup.
 *
 * This activity is executed after the ConfigService has been initialized
 * by the ConfigInitializer, ensuring that configuration is loaded first
 * before modules are initialized.
 */
class PluginStartupActivity : ProjectActivity {
    private val LOG = thisLogger()

    /**
     * Main startup execution that orchestrates plugin initialization for the project.
     *
     * Handles authentication flow, session management, and registers project close
     * listeners for proper cleanup when projects are closed.
     *
     * @param project The IntelliJ project being initialized
     */
    override suspend fun execute(project: Project) {
        // Handle authentication and session acquisition
        handleAuthenticationAndSession(project)
        // Register the ProjectCloseListener to save the last chat when a project is closed
        val connection: MessageBusConnection = project.messageBus.connect()
        connection.subscribe(ProjectManager.TOPIC, ProjectCloseListener())
        thisLogger().info("ProjectCloseListener registered successfully.")
    }

    /**
     * Manages authentication validation and complete plugin initialization flow.
     *
     * Validates stored auth tokens, synchronizes user preferences, initializes modules
     * from server configuration, acquires sessions, and handles token invalidation
     * scenarios with appropriate user notifications and cleanup.
     *
     * @param project The project context for initialization
     */
    private fun handleAuthenticationAndSession(project: Project) {
        val authState = getAuthState()
        val authToken = authState.getToken()

        if (authToken != null) {
            thisLogger().info("Acquiring session with stored token")
            try {
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
                val moduleManager = getModuleManager()
                moduleManager.storeModules(instantiatedModules)

                moduleManager.initializeModules()
                thisLogger().info("Modules initialized successfully.")

                if (!response.user.preference.isNullOrEmpty()) {
                    LOG.info("User preferences found, updating preference state")
                    getPrefState().fromSerializableMap(response.user.preference!!)
                } else {
                    LOG.info("No user preferences found, using default preference state")
                }

                // Acquire session using the stored auth token
                getAppService().acquireSessionWithStoredToken()
                thisLogger().info("Session acquired successfully.")
                activateOrCreateProject(project, thisLogger())
                startCacheValidation(project)
                launchAgentSetup(project)
            } catch (e: ClientException) {
                if (e.statusCode == 401) {
                    // The backend explicitly rejected the stored token — it's genuinely invalid
                    // (expired/revoked), so there's nothing to gain by keeping it around.
                    thisLogger().error("Stored token rejected (401) — signing out", e)
                    project.showTokenInvalidationNotification()
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            authState.clearUserData()
                            LOG.info("User data cleared successfully during sign out")
                        } catch (clearError: Exception) {
                            LOG.error("Failed to clear user data during sign out", clearError)
                        }
                    }
                } else {
                    // Any other client error (e.g. a transient backend/proxy hiccup returning
                    // 5xx-shaped content as a 4xx, or a temporary outage) says nothing about
                    // whether the token itself is valid — keep it and just retry on next open.
                    thisLogger().warn(
                        "Could not verify stored session (HTTP ${e.statusCode}) — keeping credentials, will retry next time",
                        e,
                    )
                    project.showAuthNotification(
                        title = "Code4Me could not reach the server",
                        message = "Your login is kept — this will retry automatically next time the project opens.",
                        type = NotificationType.WARNING,
                    )
                }
                // Either way, agent setup never ran this time. Arm the post-auth hook so a fresh
                // login (or the notification's retry) brings the agent paths up without an IDE
                // restart.
                registerPostAuthAgentSetup(project)
            } catch (e: Exception) {
                // Network/server-side failures (ServerException, IOException, ...): the token
                // itself was never actually checked, so signing the user out here would be
                // punishing them for a backend outage rather than an invalid credential.
                thisLogger().warn("Could not verify stored session (non-client error) — keeping credentials, will retry next time", e)
                project.showAuthNotification(
                    title = "Code4Me could not reach the server",
                    message = "Your login is kept — this will retry automatically next time the project opens.",
                    type = NotificationType.WARNING,
                )
                registerPostAuthAgentSetup(project)
            }
        } else {
            thisLogger().warn("No authentication token found. Skipping session acquisition.")
            // Show notification prompting user to login
            project.showLoginRequiredNotification()
            registerPostAuthAgentSetup(project)
        }

        // Register the ProjectCloseListener to save the last chat when a project is closed
        val connection: MessageBusConnection = project.messageBus.connect()
        connection.subscribe(ProjectManager.TOPIC, ProjectCloseListener())
        thisLogger().info("ProjectCloseListener registered successfully.")
    }

    /**
     * Brings up both agent integration paths, off the startup thread.
     *
     * The two are siblings under a [SupervisorJob] so neither can block or cancel the other:
     *  - the third-party path ([LocalProxyServer] + [AgentStartupManager]) registers Goose/Codex
     *    in `~/.jetbrains/acp.json` for the native AI Assistant, and may shell out to `npm` on
     *    first run, which is slow;
     *  - the custom-runtime path ([AcpPreparationService]) writes a one-time launch grant for a
     *    locally-running `code4me2-agent`, which needs a server round-trip.
     *
     * Neither is required for completions or the built-in chat panel to work, so every failure is
     * logged and swallowed.
     */
    private fun launchAgentSetup(project: Project) {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            launch {
                try {
                    LocalProxyServer.start(project)
                    AgentStartupManager.ensureActiveTask(project)
                    LOG.info("Third-party agent (ACP registry) setup complete")
                } catch (e: Exception) {
                    LOG.warn("Third-party agent setup failed — non-blocking", e)
                }
            }
            launch {
                try {
                    AcpPreparationService().prepare(project)
                    LOG.info("ACP runtime handoff prepared for the custom agent")
                } catch (e: Exception) {
                    LOG.warn("ACP runtime handoff preparation failed — non-blocking", e)
                }
            }
        }
    }

    /**
     * Registers a one-shot auth-token listener so [launchAgentSetup] runs as soon as the user logs
     * in, instead of only at project open. Removes itself once fired, so a later token refresh in
     * the same session doesn't provision a second task.
     */
    private fun registerPostAuthAgentSetup(project: Project) {
        val listenerRef = AtomicReference<PropertyChangeListener>()
        val listener =
            PropertyChangeListener { event ->
                val newToken = event.newValue as? String
                if (!newToken.isNullOrBlank()) {
                    listenerRef.get()?.let { getAuthState().removePropertyChangeListener(TOKEN_PROPERTY, it) }
                    LOG.info("Post-auth trigger fired — starting agent setup")
                    launchAgentSetup(project)
                }
            }
        listenerRef.set(listener)
        getAuthState().addPropertyChangeListener(TOKEN_PROPERTY, listener)
        LOG.info("Post-auth agent setup listener registered")
    }

    /**
     * Starts background cache validation coroutine for multi-file context management.
     *
     * Launches a periodic validation process that checks cached files against the
     * actual filesystem and sends deletion notifications to the server when files
     * are no longer present locally.
     *
     * @param project The project whose file cache should be validated
     */
    private fun startCacheValidation(project: Project) {
        val contextService = getProjectMultiFileContextService(project)
        val appService = getAppService()
        val basePath = project.basePath ?: return

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            LOG.info("Cache validation coroutine started for project: $basePath")
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(5))

                try {
                    validateCache(contextService, appService, basePath)
                } catch (e: Exception) {
                    LOG.warn("Error during cache validation", e)
                }
            }
        }
    }

    /**
     * Validates cached files against the filesystem and manages server synchronization.
     *
     * Checks each cached file to determine if the original file still exists,
     * and sends deletion notifications to the server for files that have been
     * removed locally. Cleans up local cache only after successful server updates.
     *
     * @param contextService Service for managing project file context cache
     * @param appService Service for communicating with the Code4Me server
     * @param basePath Base path of the project for resolving relative file paths
     */
    private suspend fun validateCache(
        contextService: me.code4me.services.project.ProjectMultiFileContextService,
        appService: me.code4me.services.app.AppService,
        basePath: String,
    ) {
        val cacheDir = contextService.contextCacheDir
        val cachedFiles = cacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".xml") } ?: return

        LOG.debug("Validating ${cachedFiles.size} cached files")

        for (cacheFile in cachedFiles) {
            try {
                val sanitizedName = cacheFile.name
                val originalPath = contextService.getMappedPath(sanitizedName)

                if (originalPath == null) {
                    LOG.debug("No mapping found for cached file: $sanitizedName")
                    continue
                }

                // Construct proper absolute path
                val absolutePath = File(basePath, originalPath).canonicalPath
                val actualFile = File(absolutePath)

                // Also check using IntelliJ's VFS for better accuracy
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
                val fileExists = actualFile.exists() && virtualFile?.isValid == true

                LOG.debug("Checking file: $originalPath")
                LOG.debug("  Absolute path: $absolutePath")
                LOG.debug("  File exists: ${actualFile.exists()}")
                LOG.debug("  Virtual file valid: ${virtualFile?.isValid}")
                LOG.debug("  Overall exists: $fileExists")

                if (!fileExists) {
                    LOG.info("File no longer exists, sending delete notification: $originalPath")

                    val lineCount =
                        try {
                            cacheFile.useLines { it.count() }
                        } catch (e: Exception) {
                            LOG.warn("Failed to count lines in cache file for deletion: ${cacheFile.name}", e)
                            continue
                        }

                    val endLine = maxOf(0, lineCount - 1)

                    val diff =
                        listOf(
                            MultiFileContextRetrievalModule.FileContextChangeData(
                                changeType = "delete",
                                startLine = 0,
                                endLine = endLine,
                                newLines = emptyList(),
                            ).toApiModel(),
                        )

                    val update = UpdateMultiFileContext(contextUpdates = mapOf(originalPath to diff))

                    try {
                        val success = appService.sendMultiFileContextUpdate(update)
                        if (success) {
                            // Only cleanup local cache if server update was successful
                            val deleted = cacheFile.delete()
                            if (deleted) {
                                contextService.removeMapping(sanitizedName)
                                LOG.info("Successfully sent delete notification and cleaned up cache for: $originalPath")
                            } else {
                                LOG.warn("Server update successful but failed to delete local cache file: ${cacheFile.name}")
                            }
                        } else {
                            LOG.warn("Failed to send delete notification for: $originalPath - will retry next cycle")
                        }
                    } catch (e: Exception) {
                        LOG.warn("Exception while sending delete notification for: $originalPath - will retry next cycle", e)
                    }
                } else {
                    LOG.debug("File still exists: $originalPath")
                }
            } catch (e: Exception) {
                LOG.warn("Error validating cached file: ${cacheFile.name}", e)
                continue
            }
        }
    }
}
