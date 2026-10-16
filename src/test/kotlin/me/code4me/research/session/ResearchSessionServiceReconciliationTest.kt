package me.code4me.research.session

import com.intellij.openapi.project.Project
import me.code4me.research.actions.ResearchEnrollmentSettings
import me.code4me.research.bootstrap.EnrollmentDiscovery
import me.code4me.research.lifecycle.ResearchLogoutHook
import me.code4me.research.spool.SpoolStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

class ResearchSessionServiceReconciliationTest {
    private val project = mock<Project>()

    @Test
    fun `unavailable membership remains distinct and does not fall through`() {
        val settings = ResearchEnrollmentSettings().also { it.setEnrollmentId("existing-enrollment") }
        var managerBuilds = 0
        val service =
            ResearchSessionService(
                project,
                discoveryOverride = { EnrollmentDiscovery.Unavailable("temporarily unavailable") },
                managerFactoryOverride = {
                    managerBuilds++
                    mock()
                },
                enrollmentSettingsOverride = settings,
            )

        val result = service.reconcileFromServer()

        assertTrue(result is ResearchReconciliationResult.Unavailable)
        assertEquals("existing-enrollment", settings.enrollmentId())
        assertEquals(0, managerBuilds, "an unavailable lookup must neither activate nor clear study ownership")
    }

    @Test
    fun `definitive no membership clears and stops the old study before ordinary setup`() {
        val settings = ResearchEnrollmentSettings().also { it.setEnrollmentId("stale-enrollment") }
        val manager = mock<ResearchSessionManager>()
        val service = service(EnrollmentDiscovery.None, manager, settings)
        assertSame(manager, service.manager())

        val result = service.reconcileFromServer()

        assertSame(ResearchReconciliationResult.NoEnrollment, result)
        assertNull(settings.enrollmentId())
        // A quarantine path never uploads first.
        verify(manager).stop(0L, drain = false, drainTimeoutMs = ResearchSessionManager.STOP_DRAIN_TIMEOUT_MS)
        verify(manager).quarantineSpool()
    }

    @Test
    fun `healthy active membership does not restart the research session`() {
        val settings = ResearchEnrollmentSettings()
        val manager = mock<ResearchSessionManager>()
        whenever(manager.state()).thenReturn(
            ParticipantStudyStateV1(
                enrollmentId = "enrollment-1",
                consentState = StudyComponentState.AVAILABLE,
                compatibilityState = StudyComponentState.AVAILABLE,
                sessionState = StudyComponentState.AVAILABLE,
                manifestExpiry = "2099-01-01T00:00:00Z",
                manifestDigest = "digest",
            ),
        )
        whenever(manager.isActive).thenReturn(true)
        val service = service(EnrollmentDiscovery.Active("enrollment-1"), manager, settings)
        service.manager()

        val result = service.reconcileFromServer() as ResearchReconciliationResult.StudyOwned

        assertNull(result.activation)
        assertEquals("enrollment-1", settings.enrollmentId())
        verify(manager, never()).activate(eq("enrollment-1"), any(), any())
    }

    @Test
    fun `active study before first activity is not restarted on bridge retries`() {
        val manager = mock<ResearchSessionManager>()
        whenever(manager.isActive).thenReturn(true)
        whenever(manager.state()).thenReturn(
            ParticipantStudyStateV1(
                enrollmentId = "enrollment-1",
                consentState = StudyComponentState.AVAILABLE,
                compatibilityState = StudyComponentState.AVAILABLE,
                sessionState = StudyComponentState.UNAVAILABLE,
                manifestExpiry = "2099-01-01T00:00:00Z",
                manifestDigest = "digest",
            ),
        )
        val service = service(EnrollmentDiscovery.Active("enrollment-1"), manager, ResearchEnrollmentSettings())
        service.manager()

        repeat(3) {
            val result = service.reconcileFromServer() as ResearchReconciliationResult.StudyOwned
            assertNull(result.activation)
            assertTrue(!result.shouldRetry)
        }

        verify(manager, never()).activate(eq("enrollment-1"), any(), any())
    }

    @Test
    fun `pauses and runtime hiccups retry while participant-actionable blocks do not`() {
        fun retries(reason: StudyBlockReason): Boolean =
            ResearchReconciliationResult.StudyOwned(ResearchActivationResult.Blocked(reason, "detail")).shouldRetry

        assertTrue(retries(StudyBlockReason.RUNTIME_UNAVAILABLE))
        assertTrue(retries(StudyBlockReason.KILL_SWITCH_ENGAGED), "an operator pause lifts on its own")
        assertFalse(retries(StudyBlockReason.AI_ASSISTANT_MISSING), "needs the participant: no retry storm")
        assertFalse(retries(StudyBlockReason.AI_ASSISTANT_OUTDATED), "needs the participant: no retry storm")
        assertFalse(retries(StudyBlockReason.REVOKED))
        assertFalse(retries(StudyBlockReason.PREPARATION_CANCELLED))
        assertFalse(retries(StudyBlockReason.PREPARATION_FAILED))
        assertTrue(ResearchReconciliationResult.StudyOwned(ResearchActivationResult.Retryable("down")).shouldRetry)
    }

    @Test
    fun `temporary runtime block retries and a later activation succeeds`() {
        val manager = mock<ResearchSessionManager>()
        whenever(manager.activate(eq("enrollment-1"), any(), any())).thenReturn(
            ResearchActivationResult.Blocked(StudyBlockReason.RUNTIME_UNAVAILABLE, "proxy registration failed"),
            activated("recovered-session"),
        )
        val service = service(EnrollmentDiscovery.Active("enrollment-1"), manager, ResearchEnrollmentSettings())

        val first = service.reconcileFromServer() as ResearchReconciliationResult.StudyOwned
        val second = service.reconcileFromServer() as ResearchReconciliationResult.StudyOwned

        assertTrue(first.shouldRetry, "the study must keep ordinary ACP setup fenced while the runtime is unavailable")
        assertEquals(StudyBlockReason.RUNTIME_UNAVAILABLE, (first.activation as ResearchActivationResult.Blocked).reason)
        assertEquals("recovered-session", (second.activation as ResearchActivationResult.Activated).sessionId)
        assertTrue(!second.shouldRetry)
        verify(manager, times(2)).activate(eq("enrollment-1"), any(), any())
    }

    @Test
    fun `disposal during enrollment discovery cannot create a new manager`() {
        var managerBuilds = 0
        lateinit var service: ResearchSessionService
        service = ResearchSessionService(
            project,
            discoveryOverride = {
                service.dispose()
                EnrollmentDiscovery.Active("enrollment-1")
            },
            managerFactoryOverride = {
                managerBuilds++
                mock()
            },
            enrollmentSettingsOverride = ResearchEnrollmentSettings(),
        )

        val result = service.reconcileFromServer()

        assertTrue(result is ResearchReconciliationResult.Unavailable)
        assertEquals(0, managerBuilds)
    }

    @Test
    fun `policy and revocation blocks remain terminal`() {
        for (reason in listOf(StudyBlockReason.POLICY_INVALID, StudyBlockReason.REVOKED)) {
            val result = ResearchReconciliationResult.StudyOwned(ResearchActivationResult.Blocked(reason))
            assertTrue(!result.shouldRetry, "$reason must not trigger automatic activation retries")
        }
    }

    @Test
    fun `logout discards stopped manager so later login can activate a fresh one`() {
        val settings = ResearchEnrollmentSettings()
        val first = mock<ResearchSessionManager>()
        val second = mock<ResearchSessionManager>()
        whenever(first.activate(eq("enrollment-1"), any(), any())).thenReturn(activated("first-session"))
        whenever(second.activate(eq("enrollment-1"), any(), any())).thenReturn(activated("second-session"))
        val managers = ArrayDeque(listOf(first, second))
        val service =
            ResearchSessionService(
                project,
                discoveryOverride = { EnrollmentDiscovery.Active("enrollment-1") },
                managerFactoryOverride = { managers.removeFirst() },
                enrollmentSettingsOverride = settings,
            )

        val firstResult = service.reconcileFromServer() as ResearchReconciliationResult.StudyOwned
        service.onLogout()
        val secondResult = service.reconcileFromServer() as ResearchReconciliationResult.StudyOwned

        assertEquals("first-session", (firstResult.activation as ResearchActivationResult.Activated).sessionId)
        assertEquals("second-session", (secondResult.activation as ResearchActivationResult.Activated).sessionId)
        // A sign-out ships what it can under its own capability, then quarantines the rest.
        verify(first).stop(0L, drain = true, drainTimeoutMs = ResearchSessionManager.STOP_DRAIN_TIMEOUT_MS)
        verify(first).quarantineSpool()
        verify(first, times(1)).activate(eq("enrollment-1"), any(), any())
        verify(second, times(1)).activate(eq("enrollment-1"), any(), any())
    }

    @Test
    fun `a sign-out uploads first only on the same server and outside a privacy erase`() {
        assertTrue(signOutMayUpload("https://a.example", "https://a.example", eraseInFlight = false))
        assertTrue(signOutMayUpload(null, "https://a.example", eraseInFlight = false), "no origin, no uploader")
        assertFalse(signOutMayUpload("https://a.example", "https://b.example", eraseInFlight = false), "server switched")
        assertFalse(signOutMayUpload("https://a.example", null, eraseInFlight = false), "no server configured now")
        assertFalse(signOutMayUpload("https://a.example", "https://a.example", eraseInFlight = true), "erase running")
    }

    @Test
    fun `a sign-out while a privacy erase runs quarantines without uploading`() {
        val manager = mock<ResearchSessionManager>()
        val service = service(EnrollmentDiscovery.None, manager, ResearchEnrollmentSettings())
        service.manager()

        ResearchLogoutHook.whileErasing { service.onLogout() }

        verify(manager).stop(0L, drain = false, drainTimeoutMs = ResearchSessionManager.STOP_DRAIN_TIMEOUT_MS)
        verify(manager).quarantineSpool()
        assertFalse(ResearchLogoutHook.isEraseInFlight(), "the erase mark ends with the erase")
    }

    @Test
    fun `dispose stops the manager without quarantining the spool`() {
        val settings = ResearchEnrollmentSettings()
        val manager = mock<ResearchSessionManager>()
        val service = service(EnrollmentDiscovery.None, manager, settings)
        service.manager()

        service.dispose()

        // A plain project close keeps the spool IPC endpoint up briefly for the
        // assistant's agent processes to flush their last events, and ships the
        // spool tail once (bounded) before the uploader closes.
        verify(manager, times(1)).stop(
            ResearchSessionManager.IPC_CLOSE_GRACE_MS,
            drain = true,
            drainTimeoutMs = ResearchSessionManager.STOP_DRAIN_TIMEOUT_MS,
        )
        verify(manager, never()).quarantineSpool()
    }

    @Test
    fun `erase stops the manager and deletes its spool instead of keeping it quarantined`() {
        val quarantined = quarantinedSpoolWithPendingRecord()
        val first = mock<ResearchSessionManager>()
        val second = mock<ResearchSessionManager>()
        whenever(first.quarantineSpool()).thenReturn(quarantined)
        val managers = ArrayDeque(listOf(first, second))
        val service =
            ResearchSessionService(
                project,
                discoveryOverride = { EnrollmentDiscovery.None },
                managerFactoryOverride = { managers.removeFirst() },
                enrollmentSettingsOverride = ResearchEnrollmentSettings(),
            )
        service.manager()

        assertTrue(service.onErase())

        // Records the participant asked to erase are never uploaded first.
        verify(first).stop(0L, drain = false, drainTimeoutMs = ResearchSessionManager.STOP_DRAIN_TIMEOUT_MS)
        verify(first).quarantineSpool()
        assertFalse(Files.exists(quarantined))
        assertSame(second, service.manager(), "a later activation must receive a fresh manager")
    }

    @Test
    fun `a context that cannot be stopped keeps its spool quarantined on erase`() {
        val quarantined = quarantinedSpoolWithPendingRecord()
        val manager = mock<ResearchSessionManager>()
        whenever(manager.stop(any(), any(), any())).thenThrow(IllegalStateException("runtime did not stop"))
        whenever(manager.quarantineSpool()).thenReturn(quarantined)
        val service = service(EnrollmentDiscovery.None, manager, ResearchEnrollmentSettings())
        service.manager()

        assertFalse(service.onErase())

        verify(manager).quarantineSpool()
        assertTrue(Files.exists(quarantined.resolve("spool.log")))
    }

    @Test
    fun `a spool that could not be moved aside is reported as not deleted on erase`() {
        val manager = mock<ResearchSessionManager>()
        val stats =
            SpoolStats(
                totalCount = 2,
                pendingCount = 2,
                ackedCount = 0,
                oldestPendingAgeMs = 0L,
                retainedBytes = 64L,
                quotaExceeded = false,
            )
        whenever(manager.stop(any(), any(), any())).thenReturn(ResearchStopResult.Stopped(SessionState.SUSPENDED, 2, stats))
        // The manager reported a spool, but quarantine returned no path: the move failed.
        whenever(manager.quarantineSpool()).thenReturn(null)
        val service = service(EnrollmentDiscovery.None, manager, ResearchEnrollmentSettings())
        service.manager()

        assertFalse(service.onErase())

        verify(manager).quarantineSpool()
    }

    @Test
    fun `a context that never opened a spool reports its erase as complete`() {
        val manager = mock<ResearchSessionManager>()
        whenever(manager.stop(any(), any(), any())).thenReturn(ResearchStopResult.Stopped(null, 0, null))
        whenever(manager.quarantineSpool()).thenReturn(null)
        val service = service(EnrollmentDiscovery.None, manager, ResearchEnrollmentSettings())
        service.manager()

        assertTrue(service.onErase())
    }

    @Test
    fun `erase without a research context changes nothing`() {
        var managerBuilds = 0
        val service =
            ResearchSessionService(
                project,
                discoveryOverride = { EnrollmentDiscovery.None },
                managerFactoryOverride = {
                    managerBuilds++
                    mock()
                },
                enrollmentSettingsOverride = ResearchEnrollmentSettings(),
            )

        assertTrue(service.onErase())
        assertEquals(0, managerBuilds)
    }

    private fun quarantinedSpoolWithPendingRecord(): Path =
        Files.createTempDirectory("research-spool-quarantine").also { directory ->
            Files.writeString(directory.resolve("spool.log"), "{\"event_id\":\"evt-1\"}\n")
        }

    private fun service(
        discovery: EnrollmentDiscovery,
        manager: ResearchSessionManager,
        settings: ResearchEnrollmentSettings,
    ): ResearchSessionService =
        ResearchSessionService(
            project,
            discoveryOverride = { discovery },
            managerFactoryOverride = { manager },
            enrollmentSettingsOverride = settings,
        )

    private fun activated(sessionId: String): ResearchActivationResult.Activated =
        ResearchActivationResult.Activated(sessionId, resumedExistingSession = false, manifestDigest = "digest")
}
