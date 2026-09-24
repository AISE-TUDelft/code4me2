package me.code4me.lifecycle

import me.code4me.services.app.publishSessionReady
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.ArrayDeque

class AcpLoginReconciliationServiceTest {
    @Test
    fun `reconciler acquisitions do not create cross-project retry storms`() {
        val firstScheduler = ManualScheduler()
        val secondScheduler = ManualScheduler()
        lateinit var first: AcpLoginReconciliationController
        lateinit var second: AcpLoginReconciliationController
        val listeners = mutableListOf<() -> Unit>()
        var firstAttempts = 0
        var secondAttempts = 0
        first = AcpLoginReconciliationController(
            firstScheduler,
            attempt = {
                firstAttempts++
                publishSessionReady(publish = false, listeners)
                ReconciliationAttemptResult.RETRY
            },
            cleanup = {},
            retryDelaysMs = listOf(1_000L),
        )
        second = AcpLoginReconciliationController(
            secondScheduler,
            attempt = {
                secondAttempts++
                publishSessionReady(publish = false, listeners)
                ReconciliationAttemptResult.RETRY
            },
            cleanup = {},
            retryDelaysMs = listOf(1_000L),
        )
        listeners += first::trigger
        listeners += second::trigger

        first.start(initiallyAuthenticated = true)
        second.start(initiallyAuthenticated = true)
        firstScheduler.runImmediate()
        secondScheduler.runImmediate()

        assertEquals(1, firstAttempts)
        assertEquals(1, secondAttempts)
        assertFalse(firstScheduler.hasImmediate())
        assertFalse(secondScheduler.hasImmediate())
        assertEquals(1, firstScheduler.activeDelayedCount())
        assertEquals(1, secondScheduler.activeDelayedCount())
    }

    @Test
    fun `session readiness expedites a delayed retry and healthy setup stays quiet`() {
        val scheduler = ManualScheduler()
        var ready = false
        var attempts = 0
        val controller =
            AcpLoginReconciliationController(
                scheduler,
                attempt = {
                    attempts++
                    if (ready) ReconciliationAttemptResult.COMPLETE else ReconciliationAttemptResult.RETRY
                },
                cleanup = {},
                retryDelaysMs = listOf(1_000L),
            )

        controller.start(initiallyAuthenticated = true)
        scheduler.runImmediate()
        assertEquals(1, attempts)
        assertEquals(1, scheduler.activeDelayedCount())

        ready = true
        controller.trigger()
        scheduler.runImmediate()
        assertEquals(2, attempts)
        assertEquals(0, scheduler.activeDelayedCount())

        controller.trigger()
        controller.trigger()
        assertFalse(scheduler.hasImmediate(), "a completed auth generation must ignore duplicate signals")
    }

    @Test
    fun `transient failures keep retrying beyond the old seconds-only window`() {
        val scheduler = ManualScheduler()
        var attempts = 0
        val controller =
            AcpLoginReconciliationController(
                scheduler,
                attempt = {
                    attempts++
                    ReconciliationAttemptResult.RETRY
                },
                cleanup = {},
                retryDelaysMs = listOf(1L, 2L, 5L),
            )

        controller.start(initiallyAuthenticated = true)
        scheduler.runImmediate()
        repeat(6) { scheduler.runNextDelay() }

        assertEquals(7, attempts)
        assertEquals(1, scheduler.activeDelayedCount(), "retry remains armed at the capped delay")
        assertEquals(listOf(1L, 2L, 5L, 5L, 5L, 5L, 5L), scheduler.recordedDelays)
    }

    @Test
    fun `duplicate triggers while running coalesce into one follow-up`() {
        val scheduler = ManualScheduler()
        lateinit var controller: AcpLoginReconciliationController
        var attempts = 0
        controller =
            AcpLoginReconciliationController(
                scheduler,
                attempt = {
                    attempts++
                    if (attempts == 1) {
                        controller.trigger()
                        controller.trigger()
                        ReconciliationAttemptResult.RETRY
                    } else {
                        ReconciliationAttemptResult.COMPLETE
                    }
                },
                cleanup = {},
            )

        controller.start(initiallyAuthenticated = true)
        scheduler.runImmediate()
        assertTrue(scheduler.hasImmediate())
        scheduler.runImmediate()

        assertEquals(2, attempts)
        assertFalse(scheduler.hasImmediate())
        assertEquals(0, scheduler.activeDelayedCount())
    }

    @Test
    fun `logout and later login create a new completed generation`() {
        val scheduler = ManualScheduler()
        val events = mutableListOf<String>()
        var attempts = 0
        val controller =
            AcpLoginReconciliationController(
                scheduler,
                attempt = {
                    attempts++
                    events += "attempt-$attempts"
                    ReconciliationAttemptResult.COMPLETE
                },
                cleanup = { events += "cleanup" },
            )

        controller.start(initiallyAuthenticated = true)
        scheduler.runImmediate()
        controller.authenticationChanged(false)
        assertEquals(listOf("attempt-1"), events, "auth callbacks must not run cleanup inline")
        scheduler.runImmediate()
        controller.authenticationChanged(true)
        scheduler.runImmediate()

        assertEquals(listOf("attempt-1", "cleanup", "attempt-2"), events)
    }

    @Test
    fun `stale completion cleans up before the next login generation starts`() {
        val scheduler = ManualScheduler()
        val events = mutableListOf<String>()
        lateinit var controller: AcpLoginReconciliationController
        var attempts = 0
        controller =
            AcpLoginReconciliationController(
                scheduler,
                attempt = {
                    attempts++
                    events += "attempt-$attempts-start"
                    if (attempts == 1) {
                        controller.authenticationChanged(false)
                        controller.authenticationChanged(true)
                    }
                    events += "attempt-$attempts-end"
                    ReconciliationAttemptResult.COMPLETE
                },
                cleanup = { events += "cleanup" },
            )

        controller.start(initiallyAuthenticated = true)
        scheduler.runImmediate()

        assertEquals(listOf("attempt-1-start", "attempt-1-end", "cleanup"), events)
        assertTrue(scheduler.hasImmediate(), "the newer generation starts only after stale cleanup")
        scheduler.runImmediate()
        assertEquals(
            listOf("attempt-1-start", "attempt-1-end", "cleanup", "attempt-2-start", "attempt-2-end"),
            events,
        )
    }

    @Test
    fun `disposal cancels retry and rejects later triggers`() {
        val scheduler = ManualScheduler()
        var attempts = 0
        var cleanups = 0
        val controller =
            AcpLoginReconciliationController(
                scheduler,
                attempt = {
                    attempts++
                    ReconciliationAttemptResult.RETRY
                },
                cleanup = { cleanups++ },
                retryDelaysMs = listOf(1L),
            )

        controller.start(initiallyAuthenticated = true)
        scheduler.runImmediate()
        controller.dispose()
        controller.trigger()

        assertEquals(1, attempts)
        assertEquals(1, cleanups)
        assertEquals(0, scheduler.activeDelayedCount())
        assertTrue(scheduler.disposed)
    }

    @Test
    fun `cleanup failure cannot strand the next login generation`() {
        val scheduler = ManualScheduler()
        var attempts = 0
        val controller =
            AcpLoginReconciliationController(
                scheduler,
                attempt = {
                    attempts++
                    ReconciliationAttemptResult.COMPLETE
                },
                cleanup = { error("cleanup failed") },
            )

        controller.start(initiallyAuthenticated = true)
        scheduler.runImmediate()
        controller.authenticationChanged(false)
        scheduler.runImmediate()
        controller.authenticationChanged(true)
        scheduler.runImmediate()

        assertEquals(2, attempts)
    }

    @Test
    fun `auth-change cleanup quarantines while plain disposal does not`() {
        val scheduler = ManualScheduler()
        val cleanups = mutableListOf<Boolean>()
        val controller =
            AcpLoginReconciliationController(
                scheduler,
                attempt = { ReconciliationAttemptResult.COMPLETE },
                cleanup = { quarantine -> cleanups += quarantine },
            )

        controller.start(initiallyAuthenticated = true)
        scheduler.runImmediate()
        controller.authenticationChanged(false)
        scheduler.runImmediate()

        assertEquals(listOf(true), cleanups, "a logout cleanup must quarantine the spool")

        controller.dispose()

        assertEquals(listOf(true, false), cleanups, "plain project disposal must not quarantine")
        assertTrue(scheduler.disposed)
    }

    @Test
    fun `disposal during a running attempt cleans up once without quarantine`() {
        val scheduler = ManualScheduler()
        lateinit var controller: AcpLoginReconciliationController
        val cleanups = mutableListOf<Boolean>()
        var attempts = 0
        controller =
            AcpLoginReconciliationController(
                scheduler,
                attempt = {
                    attempts++
                    controller.dispose()
                    ReconciliationAttemptResult.RETRY
                },
                cleanup = { quarantine -> cleanups += quarantine },
                retryDelaysMs = listOf(1L),
            )

        controller.start(initiallyAuthenticated = true)
        scheduler.runImmediate()

        assertEquals(1, attempts)
        assertEquals(listOf(false), cleanups, "disposal must clean up exactly once, without quarantine")
        assertTrue(scheduler.disposed)
        assertEquals(0, scheduler.activeDelayedCount(), "no retry may stay queued after disposal")
        assertFalse(scheduler.hasImmediate())
    }

    @Test
    fun `thrown errors still arm the retry`() {
        val scheduler = ManualScheduler()
        var attempts = 0
        val controller =
            AcpLoginReconciliationController(
                scheduler,
                attempt = {
                    attempts++
                    throw AssertionError("attempt failed hard")
                },
                cleanup = {},
                retryDelaysMs = listOf(1_000L),
            )

        controller.start(initiallyAuthenticated = true)
        scheduler.runImmediate()

        assertEquals(1, attempts)
        assertEquals(listOf(1_000L), scheduler.recordedDelays)
        assertEquals(1, scheduler.activeDelayedCount(), "a thrown Error must still re-arm the retry")
    }

    private class ManualScheduler : ReconciliationScheduler {
        private val immediate = ArrayDeque<() -> Unit>()
        private val delayed = ArrayDeque<Scheduled>()
        val recordedDelays = mutableListOf<Long>()
        var disposed = false
            private set

        override fun execute(task: () -> Unit) {
            if (!disposed) immediate.addLast(task)
        }

        override fun schedule(delayMs: Long, task: () -> Unit): ReconciliationTask {
            recordedDelays += delayMs
            val scheduled = Scheduled(task)
            delayed.addLast(scheduled)
            return ReconciliationTask { scheduled.cancelled = true }
        }

        override fun dispose() {
            disposed = true
            delayed.forEach { it.cancelled = true }
            immediate.clear()
        }

        fun runImmediate() {
            immediate.removeFirst().invoke()
        }

        fun runNextDelay() {
            while (true) {
                val scheduled = delayed.removeFirst()
                if (!scheduled.cancelled) {
                    scheduled.task()
                    runImmediate()
                    return
                }
            }
        }

        fun hasImmediate(): Boolean = immediate.isNotEmpty()

        fun activeDelayedCount(): Int = delayed.count { !it.cancelled }

        private data class Scheduled(
            val task: () -> Unit,
            var cancelled: Boolean = false,
        )
    }
}
