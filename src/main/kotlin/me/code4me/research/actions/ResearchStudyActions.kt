package me.code4me.research.actions

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.code4me.api.wrapper.CookieAwareApiClient
import me.code4me.research.bootstrap.JoinCodeResolution
import me.code4me.research.bootstrap.JoinEnrollResolution
import me.code4me.research.bootstrap.participantMessage
import me.code4me.research.session.ResearchActivationResult
import me.code4me.research.session.ResearchSessionService
import me.code4me.research.session.StudyBlockReason
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/** File-level logger for participant-safe diagnostics (never server bodies). */
private val log = Logger.getInstance("me.code4me.research.actions")

/** The notification group registered by the plugin descriptor. */
private const val NOTIFICATION_GROUP_ID = "Code4Me V2"

/** A participant-safe notice; never carries study internals or credentials. */
data class ParticipantNotice(
    val title: String,
    val message: String,
    val type: NotificationType,
)

/** The resolution of an entered value into an activation or a notice. */
internal sealed interface ActivationOutcome {
    data class Ready(val result: ResearchActivationResult, val enrollmentId: String) : ActivationOutcome

    data class Notice(val notice: ParticipantNotice) : ActivationOutcome
}

/** Matches an opaque enrollment UUID (activated directly, unchanged). */
private val ENROLLMENT_UUID_PATTERN =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** Matches the short study join code alphabet (case-insensitive, separators stripped). */
private val JOIN_CODE_PATTERN = Regex("[A-Za-z0-9]{6,12}")

/** True when [value] is an enrollment UUID (which must be activated directly). */
internal fun looksLikeEnrollmentUuid(value: String): Boolean = ENROLLMENT_UUID_PATTERN.matches(value.trim())

/**
 * True when [value] looks like a short join code and not an enrollment UUID.
 *
 * Dashes/spaces are ignored so both `AB12CD34` and `AB12-CD34` style codes are
 * recognised, matching the server's join-code normalization.
 */
internal fun looksLikeJoinCode(value: String): Boolean {
    val normalized = value.trim().replace("-", "").replace(" ", "")
    return normalized.isNotEmpty() &&
        !looksLikeEnrollmentUuid(value) &&
        JOIN_CODE_PATTERN.matches(normalized)
}

/**
 * Turns an entered value and the resolved backend answers into an activation or
 * a participant notice.
 *
 * Pure and injectable (no IDE services), so the self-enroll flow — resolve code,
 * resolve, redeem — is verifiable without an IDE. A resolved active
 * enrollment is reused idempotently; only a code the account has no enrollment
 * for is redeemed directly.
 */
internal class JoinActivationResolver(
    private val activate: (String) -> ResearchActivationResult,
    private val resolveJoinCode: (String) -> JoinCodeResolution,
    private val enroll: (String) -> JoinEnrollResolution,
) {
    fun resolve(entered: String): ActivationOutcome {
        val value = entered.trim()
        if (!looksLikeJoinCode(value)) {
            return ActivationOutcome.Ready(activate(value), value)
        }
        return when (val resolution = resolveJoinCode(value)) {
            is JoinCodeResolution.ActiveEnrollment ->
                ActivationOutcome.Ready(activate(resolution.enrollmentId), resolution.enrollmentId)
            is JoinCodeResolution.EnrollmentRequired -> enrollCode(value)
            is JoinCodeResolution.AlreadyEnrolled -> ActivationOutcome.Notice(alreadyEnrolledNotice())
            is JoinCodeResolution.Rejected -> ActivationOutcome.Notice(joinCodeRejectedNotice(resolution.message))
            is JoinCodeResolution.Unavailable -> ActivationOutcome.Notice(joinCodeUnavailableNotice())
        }
    }

    /** Self-enroll: redeem the code directly for the returned enrollment id. */
    private fun enrollCode(code: String): ActivationOutcome =
        when (val enrolled = enroll(code)) {
            is JoinEnrollResolution.Enrolled ->
                ActivationOutcome.Ready(activate(enrolled.enrollmentId), enrolled.enrollmentId)
            is JoinEnrollResolution.Rejected ->
                ActivationOutcome.Notice(joinCodeRejectedNotice(enrolled.message))
            is JoinEnrollResolution.Unavailable -> ActivationOutcome.Notice(joinCodeUnavailableNotice())
        }

}

/**
 * Tools-menu action that joins this project to a research study.
 *
 * The prompt accepts the short **join code** from a study invitation (or an
 * opaque enrollment id, which is activated directly). A join code is resolved
 * against the backend first: an existing active enrollment is reused, otherwise
 * the code is redeemed and the returned enrollment is activated. There is no
 * consent dialog in the plugin; joining is the acceptance step.
 */
class JoinResearchStudyAction(
    private val activate: (Project, String) -> ResearchActivationResult = { project, id ->
        ResearchSessionService.getInstance(project).activate(id)
    },
    private val resolveJoinCode: (Project, String) -> JoinCodeResolution = { project, code ->
        ResearchSessionService.getInstance(project).resolveJoinCode(code)
    },
    private val enroll: (Project, String) -> JoinEnrollResolution = { project, code ->
        ResearchSessionService.getInstance(project).enrollWithJoinCode(code)
    },
) : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT) ?: return
        val entered =
            Messages.showInputDialog(
                project,
                "Enter the join code from your study invitation.",
                "Join Research Study",
                Messages.getQuestionIcon(),
            ) ?: return
        when (val outcome = resolveActivation(project, entered)) {
            is ActivationOutcome.Ready -> {
                if (shouldPersistEnrollment(outcome.result)) {
                    persistEnrollment(project, outcome.enrollmentId)
                }
                notifySafely(project, joinNotice(outcome.result))
            }
            is ActivationOutcome.Notice -> notifySafely(project, outcome.notice)
        }
    }

    /**
     * Turn the entered value into an activation or a participant notice.
     *
     * A UUID (or any non-join-code value) is activated unchanged. A join code is
     * resolved first: an active enrollment is reused, a code without one opens
     * is redeemed directly.
     */
    private fun resolveActivation(
        project: Project,
        entered: String,
    ): ActivationOutcome =
        JoinActivationResolver(
            activate = { activate(project, it) },
            resolveJoinCode = { resolveJoinCode(project, it) },
            enroll = { enroll(project, it) },
        ).resolve(entered)

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.getData(CommonDataKeys.PROJECT) != null
    }

    private fun persistEnrollment(
        project: Project,
        enrollmentId: String,
    ) {
        try {
            project.getService(ResearchEnrollmentSettings::class.java)?.setEnrollmentId(enrollmentId)
        } catch (error: Exception) {
            log.warn("Could not persist the research enrollment id", error)
        }
    }

    private fun notifySafely(
        project: Project,
        notice: ParticipantNotice,
    ) {
        try {
            Notifications.Bus.notify(
                Notification(NOTIFICATION_GROUP_ID, notice.title, notice.message, notice.type),
                project,
            )
        } catch (error: Exception) {
            log.warn("Could not show the research join notification", error)
        }
    }
}

/** Map an activation result to a participant-safe notice (no study internals). */
internal fun joinNotice(result: ResearchActivationResult): ParticipantNotice =
    when (result) {
        is ResearchActivationResult.Activated ->
            ParticipantNotice(
                title = "Research Study Joined",
                message =
                    "This project joined the research study. Only metadata collection is enabled; " +
                        "use \"Leave Research Study\" to stop at any time.",
                type = NotificationType.INFORMATION,
            )
        is ResearchActivationResult.Blocked ->
            ParticipantNotice(
                title = "Research Study Not Joined",
                message = blockedMessage(result),
                type = NotificationType.WARNING,
            )
        is ResearchActivationResult.Retryable ->
            ParticipantNotice(
                title = "Research Server Unavailable",
                message =
                    "The research server could not be reached. Your enrollment was not changed; " +
                        "please try again later. (reason: ${StudyBlockReason.TRANSPORT_FAILED.value})",
                type = NotificationType.WARNING,
            )
        is ResearchActivationResult.Failed -> unexpectedNotice("Research Study Error")
    }

/**
 * The real typed reason for a block, never a generic collapse.
 *
 * A typed server rejection yields its own participant-safe message (which names
 * the typed code); otherwise a generic message carries the [StudyBlockReason].
 * No server free-text, account, enrollment, or study identifier is included.
 */
private fun blockedMessage(blocked: ResearchActivationResult.Blocked): String {
    val typed = blocked.rejection?.participantMessage.orEmpty()
    if (typed.isNotBlank()) return typed
    return "The enrollment could not be activated. Check the code and your enrollment status, then " +
        "try again. (reason: ${blocked.reason.value})"
}


/** The account is already actively enrolled in a different study. */
private fun alreadyEnrolledNotice(): ParticipantNotice =
    ParticipantNotice(
        title = "Already in a Research Study",
        message =
            "This account is already participating in another research study. Leave that study " +
                "first, or use its enrollment id.",
        type = NotificationType.WARNING,
    )

/** The join code was refused; [message] is already participant-safe. */
private fun joinCodeRejectedNotice(message: String): ParticipantNotice =
    ParticipantNotice(
        title = "Research Study Not Joined",
        message = message,
        type = NotificationType.WARNING,
    )

/** The research server could not be reached while resolving the code. */
private fun joinCodeUnavailableNotice(): ParticipantNotice =
    ParticipantNotice(
        title = "Research Server Unavailable",
        message = "The research server could not be reached. Your enrollment was not changed; please try again later.",
        type = NotificationType.WARNING,
    )

private fun unexpectedNotice(title: String): ParticipantNotice =
    ParticipantNotice(
        title = title,
        message = "An unexpected research error occurred. Normal Code4Me features are unaffected.",
        type = NotificationType.ERROR,
    )
