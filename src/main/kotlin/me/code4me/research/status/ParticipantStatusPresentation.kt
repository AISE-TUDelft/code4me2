package me.code4me.research.status

import me.code4me.research.session.ParticipantStudyStateV1
import me.code4me.research.session.SpoolDeliveryState
import me.code4me.research.session.StudyBlockReason
import me.code4me.research.session.StudyComponentState

/**
 * Participant status surface presentation (Issue 10), pure JVM.
 *
 * It maps a [ParticipantStudyStateV1] to a short, non-identifying status view:
 * a status-bar headline, a tooltip, a severity, the typed reason code, and a
 * recovery action. It deliberately never includes the enrollment/study/revision
 * ids, the manifest digest, paths, or any credential, so a status surface can
 * never reveal study internals.
 *
 * The five participant states are [StudyComponentState] values (`AVAILABLE`,
 * `UNAVAILABLE`, `PAUSED`, `BLOCKED`, `FAILED`). A `null` measurement is never
 * interpreted as "no activity".
 *
 * [ParticipantStatusSeverity] is the icon/colour class; [ParticipantStatusView]
 * is the safe, non-identifying view returned to the status bar and editor banner.
 */
enum class ParticipantStatusSeverity { OK, INFO, WARNING, ERROR }

/** A safe, non-identifying participant status view. */
data class ParticipantStatusView(
    val headline: String,
    val tooltip: String,
    val severity: ParticipantStatusSeverity,
    val reasonCode: String? = null,
    val actionHint: String? = null,
) {
    /** True only when collection is fully active. */
    val isCollecting: Boolean
        get() = severity == ParticipantStatusSeverity.OK
}

object ParticipantStatusPresentation {
    const val ACTIVE_HEADLINE = "Research: active"
    const val PAUSED_HEADLINE = "Research: paused"
    const val BLOCKED_HEADLINE = "Research: blocked"
    const val FAILED_HEADLINE = "Research: failed"
    const val RECOVERING_HEADLINE = "Research: recovering"
    const val INACTIVE_HEADLINE = "Research: not active"
    const val UNAVAILABLE_HEADLINE = "Research: unavailable"

    private const val NO_ACTION = "No action is required."

    /** The view used when the research service itself cannot be read. */
    fun unavailable(): ParticipantStatusView =
        ParticipantStatusView(
            headline = UNAVAILABLE_HEADLINE,
            tooltip = "Code4Me research status is unavailable. $NO_ACTION",
            severity = ParticipantStatusSeverity.INFO,
        )

    /**
     * Whether this view warrants an intrusive editor banner. Only states that
     * need the participant's attention (paused/blocked/failed/recovering) do;
     * an inactive or active study never nags in the editor.
     */
    fun shouldNotify(view: ParticipantStatusView): Boolean =
        view.severity == ParticipantStatusSeverity.WARNING || view.severity == ParticipantStatusSeverity.ERROR

    /** Build the participant status view for [state]. */
    fun of(state: ParticipantStudyStateV1): ParticipantStatusView {
        val resolved = resolve(state)
        val tooltip = tooltipOf(resolved)
        return ParticipantStatusView(
            headline = resolved.headline,
            tooltip = tooltip,
            severity = resolved.severity,
            reasonCode = resolved.reasonCode,
            actionHint = resolved.actionHint,
        )
    }

    private data class Resolved(
        val headline: String,
        val severity: ParticipantStatusSeverity,
        val reasonCode: String?,
        val actionHint: String?,
    )

    private fun resolve(state: ParticipantStudyStateV1): Resolved =
        when (state.blockReason) {
            StudyBlockReason.REVOKED ->
                Resolved(
                    BLOCKED_HEADLINE,
                    ParticipantStatusSeverity.ERROR,
                    StudyBlockReason.REVOKED.value,
                    "Collection has ended for this study; $NO_ACTION",
                )
            StudyBlockReason.MANIFEST_EXPIRED ->
                Resolved(
                    BLOCKED_HEADLINE,
                    ParticipantStatusSeverity.WARNING,
                    StudyBlockReason.MANIFEST_EXPIRED.value,
                    "Reconnect to refresh the study manifest.",
                )
            StudyBlockReason.MANIFEST_INVALID ->
                Resolved(
                    BLOCKED_HEADLINE,
                    ParticipantStatusSeverity.ERROR,
                    StudyBlockReason.MANIFEST_INVALID.value,
                    "The study configuration could not be verified; contact study support with the reason code.",
                )
            StudyBlockReason.INCOMPATIBLE_ENVIRONMENT ->
                Resolved(
                    BLOCKED_HEADLINE,
                    ParticipantStatusSeverity.WARNING,
                    StudyBlockReason.INCOMPATIBLE_ENVIRONMENT.value,
                    "Update the Code4Me plugin to the study-required version.",
                )
            StudyBlockReason.POLICY_INVALID ->
                Resolved(
                    BLOCKED_HEADLINE,
                    ParticipantStatusSeverity.ERROR,
                    StudyBlockReason.POLICY_INVALID.value,
                    "The study session policy is not configured; contact study support with the reason code.",
                )
            StudyBlockReason.RUNTIME_UNAVAILABLE ->
                Resolved(
                    FAILED_HEADLINE,
                    ParticipantStatusSeverity.ERROR,
                    StudyBlockReason.RUNTIME_UNAVAILABLE.value,
                    "Reconnect; the study runtime could not start.",
                )
            StudyBlockReason.AGENT_NOT_FOUND ->
                Resolved(
                    BLOCKED_HEADLINE,
                    ParticipantStatusSeverity.WARNING,
                    StudyBlockReason.AGENT_NOT_FOUND.value,
                    "Install the agent this study requires (or set its path in the Code4Me research settings), then retry.",
                )
            StudyBlockReason.TRANSPORT_FAILED ->
                if (state.manifestDigest != null) {
                    Resolved(
                        RECOVERING_HEADLINE,
                        ParticipantStatusSeverity.WARNING,
                        StudyBlockReason.TRANSPORT_FAILED.value,
                        "Check your network connection; collection resumes automatically.",
                    )
                } else {
                    Resolved(
                        FAILED_HEADLINE,
                        ParticipantStatusSeverity.WARNING,
                        StudyBlockReason.TRANSPORT_FAILED.value,
                        "Check your network connection and reopen the project.",
                    )
                }
            StudyBlockReason.SESSION_ENDED ->
                Resolved(
                    INACTIVE_HEADLINE,
                    ParticipantStatusSeverity.INFO,
                    StudyBlockReason.SESSION_ENDED.value,
                    "Rejoin the study from the Code4Me research settings if you are still enrolled.",
                )
            StudyBlockReason.UNKNOWN ->
                Resolved(
                    FAILED_HEADLINE,
                    ParticipantStatusSeverity.ERROR,
                    StudyBlockReason.UNKNOWN.value,
                    "Contact study support with the reason code.",
                )
            null ->
                when {
                    state.consentState == StudyComponentState.PAUSED ->
                        Resolved(
                            PAUSED_HEADLINE,
                            ParticipantStatusSeverity.INFO,
                            StudyComponentState.PAUSED.value,
                            "Resume consent in the Code4Me research settings to continue.",
                        )
                    state.compatibilityState == StudyComponentState.BLOCKED ->
                        Resolved(
                            BLOCKED_HEADLINE,
                            ParticipantStatusSeverity.WARNING,
                            StudyComponentState.BLOCKED.value,
                            "The environment does not permit collection.",
                        )
                    state.compatibilityState == StudyComponentState.FAILED ||
                        state.sessionState == StudyComponentState.FAILED ->
                        Resolved(
                            FAILED_HEADLINE,
                            ParticipantStatusSeverity.ERROR,
                            StudyComponentState.FAILED.value,
                            "Reconnect; if the problem persists, contact study support with the reason code.",
                        )
                    state.sessionState == StudyComponentState.PAUSED ->
                        Resolved(
                            PAUSED_HEADLINE,
                            ParticipantStatusSeverity.INFO,
                            StudyComponentState.PAUSED.value,
                            "Collection is suspended and resumes when the session reopens.",
                        )
                    state.deliveryState == SpoolDeliveryState.REVOKED ->
                        Resolved(
                            BLOCKED_HEADLINE,
                            ParticipantStatusSeverity.ERROR,
                            SpoolDeliveryState.REVOKED.value,
                            "Collection has ended for this study; retained data is not deleted.",
                        )
                    state.deliveryState == SpoolDeliveryState.SPOOL_FULL ->
                        Resolved(
                            RECOVERING_HEADLINE,
                            ParticipantStatusSeverity.WARNING,
                            SpoolDeliveryState.SPOOL_FULL.value,
                            "Local research storage is full; captured data is retained until storage is available.",
                        )
                    state.deliveryState == SpoolDeliveryState.RECOVERING ->
                        Resolved(
                            RECOVERING_HEADLINE,
                            ParticipantStatusSeverity.WARNING,
                            SpoolDeliveryState.RECOVERING.value,
                            "Check your network connection; research delivery resumes automatically.",
                        )
                    state.isCollecting ->
                        Resolved(
                            ACTIVE_HEADLINE,
                            ParticipantStatusSeverity.OK,
                            null,
                            null,
                        )
                    else ->
                        Resolved(
                            INACTIVE_HEADLINE,
                            ParticipantStatusSeverity.INFO,
                            null,
                            "Join a study from the Code4Me research settings.",
                        )
                }
        }

    private fun tooltipOf(resolved: Resolved): String {
        val label = resolved.headline.removePrefix("Research: ")
        val reason =
            if (resolved.reasonCode != null) {
                "Reason code: ${resolved.reasonCode}."
            } else {
                "No blocking reason."
            }
        val action = resolved.actionHint ?: NO_ACTION
        return "Code4Me research is $label. $reason $action"
    }
}
