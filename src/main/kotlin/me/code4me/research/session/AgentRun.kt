package me.code4me.research.session

/** Terminal outcome of one agent run. */
enum class AgentRunOutcome(val value: String) {
    COMPLETED("completed"),
    FAILED("failed"),
    CRASHED("crashed"),
    CANCELLED("cancelled"),
}

/**
 * One agent process inside a research session (Issue 07, `AgentRunV1`).
 *
 * A run is a distinct child identity: its [runId] is never the
 * [researchSessionId], and each restart inside the same session creates a new
 * run. Agent crash ends the run, not necessarily the session.
 */
data class AgentRun(
    val runId: String,
    val researchSessionId: String,
    val agentReleaseId: String? = null,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val outcome: AgentRunOutcome? = null,
) {
    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(researchSessionId.isNotBlank()) { "researchSessionId must not be blank" }
        require(runId != researchSessionId) {
            "AgentRun and ResearchSession identifiers must never be interchangeable"
        }
        if (endedAtEpochMs != null) {
            require(endedAtEpochMs >= startedAtEpochMs) { "AgentRun cannot end before it starts" }
            require(outcome != null) { "A terminal AgentRun requires an outcome" }
        } else {
            require(outcome == null) { "A running AgentRun cannot have an outcome" }
        }
    }

    val isTerminal: Boolean
        get() = endedAtEpochMs != null

    /** Finish this run with [outcome] at [atEpochMs]; idempotent once terminal. */
    fun end(
        atEpochMs: Long,
        outcome: AgentRunOutcome,
    ): AgentRun {
        if (isTerminal) return this
        return copy(endedAtEpochMs = atEpochMs, outcome = outcome)
    }

    companion object {
        /** Start a run that belongs to [session]. */
        fun start(
            runId: String,
            session: ResearchSession,
            agentReleaseId: String?,
            startedAtEpochMs: Long,
        ): AgentRun =
            AgentRun(
                runId = runId,
                researchSessionId = session.sessionId,
                agentReleaseId = agentReleaseId,
                startedAtEpochMs = startedAtEpochMs,
            )
    }
}

/** The new run state and the unchanged-or-updated session after an agent crash. */
data class AgentCrashResult(val run: AgentRun, val session: ResearchSession)

/**
 * Agent crash handling: the run is ended with [AgentRunOutcome.CRASHED] while
 * the research session is preserved (a restart simply creates a new run).
 */
fun SessionStateMachine.onAgentRunCrashed(
    run: AgentRun,
    session: ResearchSession,
    atEpochMs: Long,
): AgentCrashResult = AgentCrashResult(run.end(atEpochMs, AgentRunOutcome.CRASHED), session)
