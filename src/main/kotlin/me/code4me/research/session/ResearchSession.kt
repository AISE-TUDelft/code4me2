package me.code4me.research.session

/**
 * Participant activity-session lifecycle states (Issue 07).
 *
 * A research session is authoritative for participant activity boundaries.
 * [ENDED] and [REVOKED] are terminal; [NOT_STARTED] becomes [RUNNING] on the
 * first qualifying activity.
 */
enum class SessionState(val value: String) {
    NOT_STARTED("not_started"),
    RUNNING("running"),
    OFFLINE("offline"),
    SUSPENDED("suspended"),
    ENDED("ended"),
    REVOKED("revoked"),
    ;

    val isTerminal: Boolean
        get() = this == ENDED || this == REVOKED
}

/** Why a research session reached a terminal state. */
enum class SessionTerminalReason(val value: String) {
    EXPLICIT_COMPLETION("explicit_completion"),
    IDLE_TIMEOUT("idle_timeout"),
    RESUME_GRACE_EXPIRED("resume_grace_expired"),
    REVOKED("revoked"),
    FAILED("failed"),
}

/**
 * One research session (Issue 07, `ResearchSessionV1`).
 *
 * Idle/resume values live in [SessionStateMachine], never here, because they are
 * revision policy inputs rather than session facts.
 */
data class ResearchSession(
    val sessionId: String,
    val enrollmentId: String? = null,
    /**
     * Opaque execution-context id (project/window). Scopes the durable
     * session-store document and capability file so two windows never share or
     * overwrite each other's session state. Empty for legacy/unspecified.
     */
    val contextId: String = "",
    val state: SessionState = SessionState.NOT_STARTED,
    val openedAtEpochMs: Long? = null,
    val lastActivityEpochMs: Long? = null,
    val closedAtEpochMs: Long? = null,
    val closeReason: SessionTerminalReason? = null,
    val resumeGeneration: Int = 0,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(resumeGeneration >= 0) { "resumeGeneration must not be negative" }
    }

    val isTerminal: Boolean
        get() = state.isTerminal
}
