package me.code4me.lifecycle

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import me.code4me.services.app.getAppService
import me.code4me.services.project.getProjectChatService

/**
 * Listener for project closing events.
 *
 * This listener ensures that the last open chat session is saved when a project is closed.
 * It retrieves the ChatSessionManager for the project and saves the current session to the repository.
 */
class ProjectCloseListener : ProjectManagerListener {
    /**
     * Called when a project is about to be closed.
     *
     * @param project The project that is being closed
     */
    override fun projectClosing(project: Project) {
        thisLogger().info("Project closing: ${project.name}")

        try {
            getAppService().deactivateSession()
        } catch (e: Exception) {
            thisLogger().error("Failed to deactivate session for project: ${project.name}", e)
        }

        try {
            // Get the ProjectChatService for this project
            val chatRepository = getProjectChatService(project)

            // Find all chat sessions for this project
            val sessions = chatRepository.getAllChatSessions()

            // If there are no sessions, there's nothing to save
            if (sessions.isEmpty()) {
                thisLogger().info("No chat sessions found for project: ${project.name}")
                return
            }

            // Get the current session (should be the first one in the list)
            val currentSession = sessions.firstOrNull()

            // If there's a current session, save it to ensure it's persisted
            currentSession?.let {
                thisLogger().info("Saving current chat session (${it.id}) for project: ${project.name}")
                chatRepository.saveChat(it)
            }

            thisLogger().info("Successfully saved chat sessions and deactivated session for project: ${project.name}")
        } catch (e: Exception) {
            thisLogger().error("Failed to save or deactivate session for project: ${project.name}", e)
        }
    }
}
