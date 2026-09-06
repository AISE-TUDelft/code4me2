package me.code4me.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import me.code4me.services.app.AcpPreparationException
import me.code4me.services.app.AcpPreparationService
import me.code4me.services.app.ProjectAcpPreparation
import me.code4me.utils.notification.showAuthSuccessNotification
import me.code4me.utils.notification.showErrorNotification

/**
 * Tools-menu action that mints a fresh ACP launch grant on demand.
 *
 * Grants are short-lived and are normally refreshed automatically at startup and on login; this
 * exists for the case where the grant expired mid-session and the user wants to start a new agent
 * chat without reopening the project.
 */
class PrepareAcpAgentSessionAction(
    private val preparation: ProjectAcpPreparation = AcpPreparationService(),
    private val backgroundRunner: ((() -> Unit) -> Unit) = { task ->
        ApplicationManager.getApplication().executeOnPooledThread(task)
    },
) : AnAction() {
    private val log = thisLogger()

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT) ?: return

        backgroundRunner {
            try {
                val handoff = preparation.prepare(project)
                notifySafely {
                    project.showAuthSuccessNotification(
                        title = "ACP Agent Session Prepared",
                        message =
                            "Start a new ACP chat within ${handoff.expiresInSeconds / 60} minutes for " +
                                "workspace ${handoff.workspace}.",
                    )
                }
            } catch (error: AcpPreparationException) {
                notifySafely {
                    project.showErrorNotification(
                        title = "ACP Agent Session Preparation Failed",
                        message = error.message ?: "Code4Me could not prepare an ACP agent session.",
                    )
                }
            } catch (error: Exception) {
                log.warn("Unexpected ACP preparation failure for project: ${project.name}", error)
                notifySafely {
                    project.showErrorNotification(
                        title = "ACP Agent Session Preparation Failed",
                        message = "Code4Me hit an unexpected error while preparing the ACP agent session.",
                    )
                }
            }
        }
    }

    // Runs on a pooled thread, where an uncaught throwable is only visible in the log. Showing a
    // balloon needs a live Application, so a UI failure must not become the reported outcome.
    private fun notifySafely(show: () -> Unit) {
        try {
            show()
        } catch (e: Exception) {
            log.warn("Could not show ACP agent session notification", e)
        }
    }

    override fun update(event: AnActionEvent) {
        val hasProject = event.getData(CommonDataKeys.PROJECT) != null
        event.presentation.isEnabledAndVisible = hasProject
    }
}
