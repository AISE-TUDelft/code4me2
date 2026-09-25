package me.code4me.utils.notification

import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.util.ArrayDeque

class AcpPreparationProgressTest {
    @Test
    fun `fast preparation never shows a waiting balloon`() {
        val ui = ArrayDeque<() -> Unit>()
        val delayed = ArrayDeque<() -> Unit>()
        var shown = 0
        val progress = AcpPreparationProgressController(
            mock<Project>(),
            runOnUi = { ui.addLast(it) },
            scheduleOnUi = { delay, task ->
                assertEquals(700, delay)
                delayed.addLast(task)
            },
            showNotice = { shown++; AcpPreparationNotice {} },
        )

        val lease = progress.acquire()
        ui.removeFirst().invoke()
        lease.finish()
        delayed.removeFirst().invoke()

        assertEquals(0, shown)
    }

    @Test
    fun `automatic and manual attempts share one waiting balloon`() {
        val ui = ArrayDeque<() -> Unit>()
        val delayed = ArrayDeque<() -> Unit>()
        var shown = 0
        var expired = 0
        val progress = AcpPreparationProgressController(
            mock<Project>(),
            runOnUi = { ui.addLast(it) },
            scheduleOnUi = { _, task -> delayed.addLast(task) },
            showNotice = { shown++; AcpPreparationNotice { expired++ } },
        )

        val automatic = progress.acquire()
        val manual = progress.acquire(delayMs = 0)
        ui.removeFirst().invoke() // schedule the automatic delay
        ui.removeFirst().invoke() // manual request expedites the same balloon
        assertEquals(1, shown)
        delayed.removeFirst().invoke()
        assertEquals(1, shown)

        automatic.finish()
        ui.removeFirst().invoke()
        assertEquals(0, expired, "manual preparation still owns the waiting message")
        manual.finish()
        ui.removeFirst().invoke()
        assertEquals(1, expired)
    }

    @Test
    fun `ready result expires the shared balloon before notifying and invalidates older attempts`() {
        val ui = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val progress = AcpPreparationProgressController(
            mock<Project>(),
            runOnUi = { ui.addLast(it) },
            scheduleOnUi = { _, task -> task() },
            showNotice = { AcpPreparationNotice { events += "expired" } },
        )

        val automatic = progress.acquire(delayMs = 0)
        val manual = progress.acquire(delayMs = 0)
        ui.removeFirst().invoke()
        ui.removeFirst().invoke()
        manual.complete { events += "ready" }
        ui.removeFirst().invoke()
        automatic.finish { events += "stale" }

        assertEquals(listOf("expired", "ready"), events)
    }

    @Test
    fun `stale queued display cannot reappear after cancellation or a new generation`() {
        val ui = ArrayDeque<() -> Unit>()
        var shown = 0
        val progress = AcpPreparationProgressController(
            mock<Project>(),
            runOnUi = { ui.addLast(it) },
            scheduleOnUi = { _, task -> task() },
            showNotice = { shown++; AcpPreparationNotice {} },
        )

        progress.acquire(delayMs = 0).finish()
        val newLease = progress.acquire(delayMs = 0)
        ui.removeFirst().invoke() // stale display skips
        ui.removeFirst().invoke() // old completion callback
        ui.removeFirst().invoke() // new generation displays
        assertEquals(1, shown)
        newLease.finish()
        ui.removeFirst().invoke()
    }

    @Test
    fun `auth change clears every in-flight attempt and suppresses stale outcomes`() {
        val ui = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val progress = AcpPreparationProgressController(
            mock<Project>(),
            runOnUi = { ui.addLast(it) },
            scheduleOnUi = { _, task -> task() },
            showNotice = { AcpPreparationNotice { events += "expired" } },
        )

        progress.acquire(delayMs = 0)
        val manual = progress.acquire(delayMs = 0)
        ui.removeFirst().invoke()
        ui.removeFirst().invoke()
        progress.cancelAll()
        ui.removeFirst().invoke()
        manual.complete { events += "stale result" }

        assertEquals(listOf("expired"), events)
    }

    @Test
    fun `queued result is revoked when authentication changes before UI delivery`() {
        val ui = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val progress = AcpPreparationProgressController(
            mock<Project>(),
            runOnUi = { ui.addLast(it) },
            scheduleOnUi = { _, task -> task() },
            showNotice = { AcpPreparationNotice { events += "expired" } },
        )

        val lease = progress.acquire(delayMs = 0)
        ui.removeFirst().invoke()
        lease.finish { events += "stale result" }
        progress.cancelAll()
        while (ui.isNotEmpty()) ui.removeFirst().invoke()

        assertEquals(listOf("expired"), events)
    }

    @Test
    fun `queued result is revoked when the project service is disposed`() {
        val ui = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val progress = AcpPreparationProgressController(
            mock<Project>(),
            runOnUi = { ui.addLast(it) },
            scheduleOnUi = { _, task -> task() },
            showNotice = { AcpPreparationNotice { events += "expired" } },
        )

        val lease = progress.acquire(delayMs = 0)
        ui.removeFirst().invoke()
        lease.finish { events += "stale result" }
        progress.dispose()
        while (ui.isNotEmpty()) ui.removeFirst().invoke()

        assertEquals(listOf("expired"), events)
    }

    @Test
    fun `replacement display expires the old balloon even when UI callbacks run out of order`() {
        val ui = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        var nextNotice = 0
        val progress = AcpPreparationProgressController(
            mock<Project>(),
            runOnUi = { ui.addLast(it) },
            scheduleOnUi = { _, task -> task() },
            showNotice = {
                val id = ++nextNotice
                events += "shown $id"
                AcpPreparationNotice { events += "expired $id" }
            },
        )

        val old = progress.acquire(delayMs = 0)
        ui.removeFirst().invoke() // show the old balloon
        old.finish()
        progress.acquire(delayMs = 0)
        ui.removeLast().invoke() // show replacement before the old expiry callback

        assertEquals(listOf("shown 1", "expired 1", "shown 2"), events)
        while (ui.isNotEmpty()) ui.removeFirst().invoke()
        assertEquals(listOf("shown 1", "expired 1", "shown 2"), events)
    }
}
