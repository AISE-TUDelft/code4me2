package me.code4me.research.session

/** Raised when a caller requests an illegal session state transition. */
class IllegalSessionTransitionException(
    val from: SessionState,
    val to: SessionState,
) : IllegalStateException("Illegal research session transition: ${from.value} -> ${to.value}")

/** How a resume attempt resolved. */
enum class ResumeOutcome {
    /** The suspended session reopened within the resume grace window. */
    REOPENED_SAME_SESSION,

    /** The grace window expired; the prior session is now ended. */
    SESSION_ENDED_GRACE_EXPIRED,

    /** The session was already terminal and cannot resume. */
    REJECTED_TERMINAL,

    /** The session was not suspended, so there was nothing to resume. */
    NOT_SUSPENDED,
}

/** Result of a resume attempt: the (possibly transitioned) session plus outcome. */
data class SessionResumeResult(
    val session: ResearchSession,
    val reopened: Boolean,
    val outcome: ResumeOutcome,
)

/**
 * Research session state machine (Issue 07).
 *
 * Idle and resume values are injected revision-policy inputs, never compiled
 * constants. Only the transitions in the published lifecycle are legal; all
 * others throw [IllegalSessionTransitionException].
 *
 * @property resumeGraceMs how long a suspended session may be reopened as-is.
 * @property idleTimeoutMs inactivity after which a live session ends.
 */
class SessionStateMachine(
    val resumeGraceMs: Long,
    val idleTimeoutMs: Long,
) {
    init {
        require(resumeGraceMs > 0) { "resumeGraceMs must be positive" }
        require(idleTimeoutMs > 0) { "idleTimeoutMs must be positive" }
    }

    /** True when [to] is a legal successor of [from]. */
    fun canTransition(
        from: SessionState,
        to: SessionState,
    ): Boolean = to in legalTargets(from)

    /** Apply a legal transition, or throw [IllegalSessionTransitionException]. */
    fun transition(
        session: ResearchSession,
        to: SessionState,
        atEpochMs: Long,
        reason: SessionTerminalReason? = null,
    ): ResearchSession {
        if (!canTransition(session.state, to)) {
            throw IllegalSessionTransitionException(session.state, to)
        }
        return when (to) {
            SessionState.RUNNING -> session.copy(state = to, lastActivityEpochMs = atEpochMs)
            SessionState.ENDED ->
                session.copy(
                    state = to,
                    closedAtEpochMs = atEpochMs,
                    closeReason = reason ?: SessionTerminalReason.EXPLICIT_COMPLETION,
                )
            SessionState.REVOKED ->
                session.copy(
                    state = to,
                    closedAtEpochMs = atEpochMs,
                    closeReason = SessionTerminalReason.REVOKED,
                )
            else -> session.copy(state = to, lastActivityEpochMs = atEpochMs)
        }
    }

    /** First qualifying activity: `NotStarted -> Running`. */
    fun onQualifyingActivity(
        session: ResearchSession,
        atEpochMs: Long,
    ): ResearchSession =
        transition(session, SessionState.RUNNING, atEpochMs).copy(
            openedAtEpochMs = session.openedAtEpochMs ?: atEpochMs,
        )

    /** Update the last-activity marker without changing state. */
    fun recordActivity(
        session: ResearchSession,
        atEpochMs: Long,
    ): ResearchSession {
        if (session.isTerminal) return session
        return session.copy(lastActivityEpochMs = atEpochMs)
    }

    /** `Running -> Offline` (network unavailable). */
    fun goOffline(
        session: ResearchSession,
        atEpochMs: Long,
    ): ResearchSession = transition(session, SessionState.OFFLINE, atEpochMs)

    /** `Offline -> Running` (heartbeat/upload recovers). */
    fun recover(
        session: ResearchSession,
        atEpochMs: Long,
    ): ResearchSession = transition(session, SessionState.RUNNING, atEpochMs)

    /** `Running -> Suspended` (IDE closes or the machine sleeps). */
    fun suspend(
        session: ResearchSession,
        atEpochMs: Long,
    ): ResearchSession = transition(session, SessionState.SUSPENDED, atEpochMs)

    /**
     * Reopen a suspended session within the grace window, or end it once.
     *
     * The grace boundary is inclusive: `now - lastActivity <= resumeGraceMs`
     * reopens the same session; anything later ends it with
     * [SessionTerminalReason.RESUME_GRACE_EXPIRED].
     */
    fun resume(
        session: ResearchSession,
        atEpochMs: Long,
    ): SessionResumeResult {
        if (session.isTerminal) {
            return SessionResumeResult(session, reopened = false, outcome = ResumeOutcome.REJECTED_TERMINAL)
        }
        if (session.state != SessionState.SUSPENDED) {
            return SessionResumeResult(session, reopened = false, outcome = ResumeOutcome.NOT_SUSPENDED)
        }
        val lastActivity = session.lastActivityEpochMs
        val withinGrace = lastActivity != null && atEpochMs - lastActivity <= resumeGraceMs
        return if (withinGrace) {
            val reopened =
                transition(session, SessionState.RUNNING, atEpochMs)
                    .copy(resumeGeneration = session.resumeGeneration + 1)
            SessionResumeResult(reopened, reopened = true, outcome = ResumeOutcome.REOPENED_SAME_SESSION)
        } else {
            val ended =
                session.copy(
                    state = SessionState.ENDED,
                    closedAtEpochMs = atEpochMs,
                    closeReason = SessionTerminalReason.RESUME_GRACE_EXPIRED,
                )
            SessionResumeResult(ended, reopened = false, outcome = ResumeOutcome.SESSION_ENDED_GRACE_EXPIRED)
        }
    }

    /** End a live session because it exceeded the revision idle policy. */
    fun expireIfIdle(
        session: ResearchSession,
        atEpochMs: Long,
    ): ResearchSession {
        if (session.isTerminal || session.state == SessionState.NOT_STARTED) return session
        val lastActivity = session.lastActivityEpochMs ?: return session
        if (atEpochMs - lastActivity <= idleTimeoutMs) return session
        return transition(session, SessionState.ENDED, atEpochMs, SessionTerminalReason.IDLE_TIMEOUT)
    }

    /** Explicit participant completion. */
    fun end(
        session: ResearchSession,
        atEpochMs: Long,
    ): ResearchSession = transition(session, SessionState.ENDED, atEpochMs, SessionTerminalReason.EXPLICIT_COMPLETION)

    /** Withdrawal/consent pause: terminal revocation. */
    fun revoke(
        session: ResearchSession,
        atEpochMs: Long,
    ): ResearchSession = transition(session, SessionState.REVOKED, atEpochMs)

    private fun legalTargets(from: SessionState): Set<SessionState> =
        when (from) {
            SessionState.NOT_STARTED -> setOf(SessionState.RUNNING)
            SessionState.RUNNING ->
                setOf(SessionState.OFFLINE, SessionState.SUSPENDED, SessionState.ENDED, SessionState.REVOKED)
            SessionState.OFFLINE -> setOf(SessionState.RUNNING, SessionState.ENDED, SessionState.REVOKED)
            SessionState.SUSPENDED -> setOf(SessionState.RUNNING, SessionState.ENDED)
            SessionState.ENDED, SessionState.REVOKED -> emptySet()
        }
}
