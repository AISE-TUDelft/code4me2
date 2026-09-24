package me.code4me.research.session

import com.intellij.openapi.project.Project
import me.code4me.research.actions.ResearchEnrollmentSettings
import me.code4me.research.bootstrap.EnrollmentDiscovery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

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
        verify(manager).stop()
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
        verify(manager, never()).activate("enrollment-1")
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

        verify(manager, never()).activate("enrollment-1")
    }

    @Test
    fun `temporary runtime block retries and a later activation succeeds`() {
        val manager = mock<ResearchSessionManager>()
        whenever(manager.activate("enrollment-1")).thenReturn(
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
        verify(manager, times(2)).activate("enrollment-1")
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
        whenever(first.activate("enrollment-1")).thenReturn(activated("first-session"))
        whenever(second.activate("enrollment-1")).thenReturn(activated("second-session"))
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
        verify(first).stop()
        verify(first).quarantineSpool()
        verify(first, times(1)).activate("enrollment-1")
        verify(second, times(1)).activate("enrollment-1")
    }

    @Test
    fun `dispose stops the manager without quarantining the spool`() {
        val settings = ResearchEnrollmentSettings()
        val manager = mock<ResearchSessionManager>()
        val service = service(EnrollmentDiscovery.None, manager, settings)
        service.manager()

        service.dispose()

        verify(manager, times(1)).stop()
        verify(manager, never()).quarantineSpool()
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
