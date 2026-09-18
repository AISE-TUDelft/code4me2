package me.code4me.research.actions

import me.code4me.research.bootstrap.EnrollmentDiscovery
import me.code4me.research.bootstrap.ResolvedEnrollment
import me.code4me.research.bootstrap.classifyEnrollmentDiscovery
import me.code4me.research.bootstrap.parseEnrollmentEntries
import me.code4me.research.session.ResearchActivationResult
import me.code4me.research.session.StudyBlockReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResearchEnrollmentSettingsTest {
    @Test
    fun `enrollment id round trips and clears`() {
        val settings = ResearchEnrollmentSettings()
        assertNull(settings.enrollmentId())
        settings.setEnrollmentId("enrollment-1")
        assertEquals("enrollment-1", settings.enrollmentId())
        settings.clear()
        assertNull(settings.enrollmentId())
    }

    @Test
    fun `state survives a persisted snapshot reload`() {
        val persisted = ResearchEnrollmentSettings()
        persisted.setEnrollmentId("enrollment-2")
        val reloaded = ResearchEnrollmentSettings()
        reloaded.loadState(persisted.state)
        assertEquals("enrollment-2", reloaded.enrollmentId())
    }

    @Test
    fun `only a successful activation is persisted`() {
        assertTrue(shouldPersistEnrollment(ResearchActivationResult.Activated("session-1", false, "digest")))
        assertFalse(shouldPersistEnrollment(ResearchActivationResult.Blocked(StudyBlockReason.MANIFEST_INVALID)))
        assertFalse(shouldPersistEnrollment(ResearchActivationResult.Retryable("timeout")))
        assertFalse(shouldPersistEnrollment(ResearchActivationResult.Failed("boom")))
    }
}

class DiscoverEnrollmentTest {
    @Test
    fun `an active enrollment wins over terminal ones`() {
        val discovery = classifyEnrollmentDiscovery(
            listOf(
                ResolvedEnrollment("e-old", "s", "STUDY_STOPPED"),
                ResolvedEnrollment("e-active", "s", "ACTIVE"),
            ),
        )
        assertEquals(EnrollmentDiscovery.Active("e-active"), discovery)
    }

    @Test
    fun `terminal enrollments block reactivation`() {
        assertEquals(
            EnrollmentDiscovery.Terminal("STUDY_STOPPED"),
            classifyEnrollmentDiscovery(listOf(ResolvedEnrollment("e", "s", "STUDY_STOPPED"))),
        )
    }

    @Test
    fun `no enrollments is the none outcome`() {
        assertEquals(EnrollmentDiscovery.None, classifyEnrollmentDiscovery(emptyList()))
    }

    @Test
    fun `the own-enrollment projection parses membership rows`() {
        val enrollments = parseEnrollmentEntries(
            """
            {"enrollments":[
              {"enrollment_id":"e-1","study_id":"s-1","status":"ACTIVE",
               "participant_code":"P-1"}
            ]}
            """.trimIndent(),
        )
        assertEquals(1, enrollments.size)
        assertEquals("e-1", enrollments.single().enrollmentId)
        assertEquals("ACTIVE", enrollments.single().status)
    }

    @Test
    fun `a malformed projection yields no membership`() {
        assertTrue(parseEnrollmentEntries("{ not json").isEmpty())
        assertTrue(parseEnrollmentEntries("{}").isEmpty())
    }
}
