package me.code4me.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import me.code4me.services.app.AcpPreparationException
import me.code4me.services.app.AcpPreparationService
import me.code4me.services.app.ProjectAcpPreparation
import me.code4me.services.agent.ParticipantSetupStatus
import me.code4me.services.agent.ParticipantSetupStep
import me.code4me.services.agent.getParticipantAgentSetupService
import me.code4me.utils.notification.AcpPreparationIndicator
import me.code4me.utils.notification.AcpPreparationLease
import me.code4me.utils.notification.getAcpPreparationProgress
import me.code4me.utils.notification.showAuthNotification
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
    private val setup: (Project, ProjectAcpPreparation) -> ParticipantSetupStatus =
        { project, fallback -> getParticipantAgentSetupService().prepareWithLegacyFallback(project, fallback, reactivate = true) },
    private val backgroundRunner: ((() -> Unit) -> Unit) = { task ->
        ApplicationManager.getApplication().executeOnPooledThread(task)
    },
    private val notify: (Project, ParticipantSetupStatus) -> Unit = ::showPrepareNotification,
    private val progressFor: (Project) -> AcpPreparationIndicator = ::getAcpPreparationProgress,
) : AnAction() {
    private val log = thisLogger()

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT) ?: return

        val lease = runCatching { progressFor(project).acquire(delayMs = 0) }
            .onFailure { log.warn("Could not show ACP preparation progress", it) }
            .getOrNull()

        try {
            backgroundRunner {
                try {
                    val status = setup(project, preparation)
                    if (status.step != ParticipantSetupStep.READY && status.step != ParticipantSetupStep.STUDY_ACTIVE) {
                        throw AcpPreparationException(status.message)
                    }
                    finishProgress(lease, ready = true) { notifySafely { notify(project, status) } }
                } catch (error: AcpPreparationException) {
                    finishProgress(lease, ready = false) {
                        notifySafely {
                            project.showErrorNotification(
                                title = "ACP Agent Session Preparation Failed",
                                message = error.message,
                            )
                        }
                    }
                } catch (error: Exception) {
                    log.warn("Unexpected ACP preparation failure for project: ${project.name}", error)
                    finishProgress(lease, ready = false) {
                        notifySafely {
                            project.showErrorNotification(
                                title = "ACP Agent Session Preparation Failed",
                                message = "Code4Me hit an unexpected error while preparing the ACP agent session.",
                            )
                        }
                    }
                }
            }
        } catch (error: Exception) {
            log.warn("Could not start ACP preparation for project: ${project.name}", error)
            finishProgress(lease, ready = false) {
                notifySafely {
                    project.showErrorNotification(
                        title = "ACP Agent Session Preparation Failed",
                        message = "Code4Me could not start ACP preparation.",
                    )
                }
            }
        }
    }

    private fun finishProgress(lease: AcpPreparationLease?, ready: Boolean, showResult: () -> Unit) {
        if (lease == null) {
            showResult()
            return
        }
        runCatching {
            if (ready) lease.complete(showResult) else lease.finish(showResult)
        }.onFailure {
            log.warn("Could not clear ACP preparation progress", it)
            showResult()
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

/**
 * Participant-safe outcome notification for a prepare request.
 *
 * A study-active project gets an informational redirect to the authoritative
 * research entry (never an error and never the direct managed agent); every
 * other non-ready outcome stays an error, and ready stays the success balloon.
 */
private fun showPrepareNotification(
    project: Project,
    status: ParticipantSetupStatus,
) {
    when (status.step) {
        ParticipantSetupStep.READY ->
            project.showAuthSuccessNotification(
                title = "Code4Me Agent Prepared",
                message = status.message,
            )
        ParticipantSetupStep.STUDY_ACTIVE -> {
            // Repeated prepare requests must not stack the same balloon.
            runCatching {
                com.intellij.notification.NotificationsManager.getNotificationsManager()
                    .getNotificationsOfType(com.intellij.notification.Notification::class.java, project)
                    .filter { it.title == "Research study active" }
                    .forEach { it.expire() }
            }
            project.showAuthNotification(
                title = "Research study active",
                message = status.message,
                type = NotificationType.INFORMATION,
                includeSettingsAction = false,
            )
        }
        else -> project.showErrorNotification(
            title = "ACP Agent Session Preparation Failed",
            message = status.message,
        )
    }
}
