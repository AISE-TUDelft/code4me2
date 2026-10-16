package me.code4me.lifecycle

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import me.code4me.services.agent.getParticipantAgentSetupService
import me.code4me.services.project.getProjectChatService
import me.code4me.services.project.getProjectTokenService

/**
 * Listener for project closing events.
 *
 * This listener ensures that the last open chat session is saved when a project is closed.
 * It retrieves the ChatSessionManager for the project and saves the current session to the repository.
 */
class ProjectCloseListener(private val ownerProject: Project) : ProjectManagerListener {
    /**
     * Called when a project is about to be closed.
     *
     * @param project The project that is being closed
     */
    override fun projectClosing(project: Project) {
        if (project !== ownerProject) return
        thisLogger().info("Project closing: ${project.name}")
        getParticipantAgentSetupService().unregister(project)

        // Authentication is application-scoped. Closing a project revokes its
        // local bridge claim but must not sign the participant out; backend
        // session deactivation is reserved for explicit logout.
        getProjectTokenService(project).setActivated(false)

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

            thisLogger().info("Successfully saved chat sessions while closing project: ${project.name}")
        } catch (e: Exception) {
            thisLogger().error("Failed to save chat state for project: ${project.name}", e)
        }
    }
}
