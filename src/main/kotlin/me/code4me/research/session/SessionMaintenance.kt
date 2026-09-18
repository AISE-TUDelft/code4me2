package me.code4me.research.session

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Cancels a scheduled maintenance task; idempotent and never throws. */
fun interface MaintenanceHandle {
    fun cancel()
}

/**
 * Schedules the periodic research-session maintenance loop.
 *
 * Injectable so the manager's cadence and ticks are deterministic in tests and
 * no thread is started off-IDE. Implementations must never throw from
 * [schedule]; a scheduler failure degrades to "no periodic maintenance" and
 * must never disable the research session.
 */
fun interface MaintenanceScheduler {
    /**
     * Run [task] every [periodMs] (the first run after one full period), until
     * the returned handle is cancelled.
     */
    fun schedule(
        periodMs: Long,
        task: () -> Unit,
    ): MaintenanceHandle
}

/**
 * Default [MaintenanceScheduler]: one daemon thread per scheduled loop, released
 * when its handle is cancelled. The loop is a fixed-delay loop so a slow tick
 * can never stack ticks; a task failure never stops the loop (the task itself is
 * fail-soft, and this only guards against an unexpected throw).
 */
class ThreadedMaintenanceScheduler : MaintenanceScheduler {
    override fun schedule(
        periodMs: Long,
        task: () -> Unit,
    ): MaintenanceHandle {
        val period = periodMs.coerceAtLeast(MIN_PERIOD_MS)
        val executor =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, THREAD_NAME).apply { isDaemon = true }
            }
        val cancelled = AtomicBoolean(false)
        val future: ScheduledFuture<*> =
            executor.scheduleWithFixedDelay(
                { if (!cancelled.get()) runCatching { task() } },
                period,
                period,
                TimeUnit.MILLISECONDS,
            )
        return MaintenanceHandle {
            if (cancelled.compareAndSet(false, true)) {
                future.cancel(false)
                executor.shutdownNow()
            }
        }
    }

    private companion object {
        const val THREAD_NAME = "code4me-research-maintenance"
        const val MIN_PERIOD_MS = 1L
    }
}

/**
 * Typed result of one periodic research maintenance tick; never thrown.
 *
 * [Ended] means the server authoritatively reported the session terminal (or the
 * kill switch engaged): the runtime was torn down and must not be resurrected.
 * [Retryable] means a transient failure only; local state is intact.
 */
sealed interface ResearchMaintenanceResult {
    /** No maintenance is due: no active session, or the manager is stopped. */
    data class Inactive(val detail: String) : ResearchMaintenanceResult

    /** A heartbeat was delivered; [capabilityRefreshed] marks a re-bootstrap. */
    data class Maintained(
        val heartbeatSeconds: Long?,
        val capabilityRefreshed: Boolean,
    ) : ResearchMaintenanceResult

    /** The server reported a terminal session/revocation; the runtime stopped. */
    data class Ended(
        val reason: StudyBlockReason,
        val detail: String?,
    ) : ResearchMaintenanceResult

    /** A transient failure; local state is intact and the next tick retries. */
    data class Retryable(val detail: String?) : ResearchMaintenanceResult
}
