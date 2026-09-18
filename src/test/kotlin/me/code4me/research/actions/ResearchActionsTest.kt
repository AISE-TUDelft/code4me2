package me.code4me.research.actions

import java.nio.file.Files
import java.nio.file.Path
import com.intellij.notification.NotificationType
import me.code4me.research.bootstrap.BootstrapRejection
import me.code4me.research.bootstrap.EnrollmentDiscovery
import me.code4me.research.bootstrap.JoinCodeResolution
import me.code4me.research.bootstrap.JoinEnrollResolution
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

// --------------------------------------------------------------------------
// ResearchEnrollmentSettingsTest.kt
// --------------------------------------------------------------------------

/**
 * Tests the research enrollment persistence decision and state round-trip.
 *
 * The decision is a pure helper so the Join action's "only remember a working
 * enrollment" rule is verifiable without an IDE.
 */
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

// --------------------------------------------------------------------------
// DiscoverEnrollmentTest.kt
// --------------------------------------------------------------------------

/** The pure membership classification behind the login/startup discovery (E09). */
class DiscoverEnrollmentTest {
    @Test
    fun `an active enrollment wins over terminal ones`() {
        val discovery =
            classifyEnrollmentDiscovery(
                listOf(
                    ResolvedEnrollment("e-old", "s", "r", "WITHDRAWN"),
                    ResolvedEnrollment("e-active", "s", "r", "ACTIVE"),
                )
            )

        assertEquals(EnrollmentDiscovery.Active("e-active"), discovery)
    }

    @Test
    fun `a terminal enrollment is reported as terminal`() {
        assertEquals(
            EnrollmentDiscovery.Terminal("COMPLETED"),
            classifyEnrollmentDiscovery(listOf(ResolvedEnrollment("e", "s", "r", "completed"))),
        )
        assertEquals(
            EnrollmentDiscovery.Terminal("COMPLETED"),
            classifyEnrollmentDiscovery(listOf(ResolvedEnrollment("e", "s", "r", "COMPLETED"))),
        )
    }

    @Test
    fun `no enrollments is the none outcome`() {
        assertEquals(EnrollmentDiscovery.None, classifyEnrollmentDiscovery(emptyList()))
    }

    @Test
    fun `the own-enrollment projection parses the membership rows`() {
        val enrollments =
            parseEnrollmentEntries(
                """
                {"enrollments":[
                  {"enrollment_id":"e-1","study_id":"s-1","study_revision_id":"r-1","status":"ACTIVE",
                   "participant_code":"P-1","email":"never@surfaced"}
                ]}
                """.trimIndent()
            )

        assertEquals(1, enrollments.size)
        assertEquals("e-1", enrollments.single().enrollmentId)
        assertEquals("ACTIVE", enrollments.single().status)
    }

    @Test
    fun `a malformed projection yields no membership rather than a fabricated one`() {
        assertTrue(parseEnrollmentEntries("{ not json").isEmpty())
        assertTrue(parseEnrollmentEntries("{}").isEmpty())
    }
}

// --------------------------------------------------------------------------
// --------------------------------------------------------------------------
// ResearchStudyActionRegistrationTest.kt
// --------------------------------------------------------------------------

/**
 * Guards the plugin descriptor wiring for the participant Join action.
 *
 * The descriptor is read from the project tree (not the test classpath) because
 * every IntelliJ distribution jar also ships a `META-INF/plugin.xml`.
 */
class ResearchStudyActionRegistrationTest {
    private fun pluginXml(): String {
        val candidates =
            listOf(
                Path.of("src/main/resources/META-INF/plugin.xml"),
                Path.of("build/resources/main/META-INF/plugin.xml"),
            )
        val path =
            candidates.firstOrNull { Files.exists(it) }
                ?: error("Could not locate plugin.xml from ${Path.of("").toAbsolutePath()}")
        return Files.readString(path)
    }

    @Test
    fun `join action is registered in the tools menu`() {
        assertActionRegistered(
            id = "me.code4me.research.actions.JoinResearchStudyAction",
            className = "me.code4me.research.actions.JoinResearchStudyAction",
        )
    }

    
    @Test
    fun `enrollment settings is registered as a project service`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<projectService[^>]*" +
                    "serviceImplementation=\"me\\.code4me\\.research\\.actions\\.ResearchEnrollmentSettings\"",
            ).containsMatchIn(xml),
            "ResearchEnrollmentSettings must be registered as a projectService",
        )
    }

    private fun assertActionRegistered(
        id: String,
        className: String,
    ) {
        val xml = pluginXml()
        val escapedId = id.replace(".", "\\.")
        val escapedClass = className.replace(".", "\\.")
        assertTrue(
            Regex(
                "<action[^>]*id=\"$escapedId\"[^>]*class=\"$escapedClass\"[\\s\\S]*?" +
                    "<add-to-group group-id=\"ToolsMenu\"",
            ).containsMatchIn(xml),
            "$id must be registered as an action in the ToolsMenu",
        )
    }
}

// --------------------------------------------------------------------------
// ResearchJoinCodeDetectionTest.kt
// --------------------------------------------------------------------------

/** The Join action accepts a short join code or an enrollment UUID. */
class ResearchJoinCodeDetectionTest {
    @Test
    fun `short alphanumeric codes are join codes but UUIDs are not`() {
        assertTrue(looksLikeJoinCode("AB12CD34"))
        assertTrue(looksLikeJoinCode("ab12-cd34"))
        assertTrue(looksLikeJoinCode("ABCD2345"))
        assertTrue(looksLikeJoinCode("  ab12cd34  "))
        assertFalse(looksLikeJoinCode("11111111-1111-1111-1111-111111111111"))
        assertFalse(looksLikeJoinCode("short"))
        assertFalse(looksLikeJoinCode(""))
        assertFalse(looksLikeJoinCode("way-too-long-to-be-a-join-code"))
    }

    @Test
    fun `UUIDs are recognized as enrollment ids`() {
        assertTrue(looksLikeEnrollmentUuid("11111111-1111-1111-1111-111111111111"))
        assertTrue(looksLikeEnrollmentUuid("  ABCDEF01-2345-6789-ABCD-EF0123456789  "))
        assertFalse(looksLikeEnrollmentUuid("AB12CD34"))
    }
}

// --------------------------------------------------------------------------
// JoinNoticeTest.kt
// --------------------------------------------------------------------------

/** The notification shows the typed server reason, never a generic collapse. */
class JoinNoticeTypedReasonTest {
    @Test
    fun `a typed rejection is shown with its own message`() {
        val notice =
            joinNotice(
                ResearchActivationResult.Blocked(
                    reason = StudyBlockReason.INCOMPATIBLE_ENVIRONMENT,
                    detail = "a compatibility receipt is required",
                    rejection = BootstrapRejection.COMPATIBILITY_MISSING,
                ),
            )

        assertTrue(notice.message.contains("COMPATIBILITY_MISSING"), notice.message)
        assertFalse(notice.message.contains("WITHDRAWN"), notice.message)
        assertEquals(NotificationType.WARNING, notice.type)
    }

    @Test
    fun `each typed rejection yields a distinct notification message`() {
        val rejections =
            listOf(
                BootstrapRejection.ENROLLMENT_NOT_FOUND,
                BootstrapRejection.NOT_AUTHENTICATED,
                BootstrapRejection.NOT_PERMITTED,
                BootstrapRejection.COMPATIBILITY_MISSING,
                BootstrapRejection.ENROLLMENT_NOT_ACTIVE,
                BootstrapRejection.REVOKED,
                BootstrapRejection.INELIGIBLE,
            )
        val messages =
            rejections.map { rejection ->
                joinNotice(ResearchActivationResult.Blocked(StudyBlockReason.REVOKED, null, rejection)).message
            }

        assertEquals(messages.size, messages.toSet().size)
        rejections.forEachIndexed { index, rejection ->
            assertTrue(messages[index].contains(rejection.name), rejection.name)
        }
    }

    @Test
    fun `an untyped block still names the block reason`() {
        val notice = joinNotice(ResearchActivationResult.Blocked(StudyBlockReason.REVOKED))

        assertTrue(notice.message.contains("REVOKED"), notice.message)
        assertEquals(NotificationType.WARNING, notice.type)
    }
}

// --------------------------------------------------------------------------
// JoinActivationResolverTest.kt
// --------------------------------------------------------------------------

/**
 * The plugin-side self-enroll flow: a join code without an enrollment is
 * redeemed directly for an enrollment id, then activated. The plugin asks the
 * participant nothing consent-related.
 */
class JoinActivationResolverTest {
    private fun activated(id: String) = ResearchActivationResult.Activated("session-$id", false, "digest")

    private fun resolver(
        activate: (String) -> ResearchActivationResult = { activated(it) },
        resolveJoinCode: (String) -> JoinCodeResolution = { JoinCodeResolution.EnrollmentRequired },
        enroll: (String) -> JoinEnrollResolution = { JoinEnrollResolution.Enrolled("enr-1", reused = false) },
    ): JoinActivationResolver =
        JoinActivationResolver(
            activate = activate,
            resolveJoinCode = resolveJoinCode,
            enroll = enroll,
        )

    @Test
    fun `an enrollment UUID activates directly without resolving a join code`() {
        var joinCodeCalls = 0
        val resolver =
            resolver(
                resolveJoinCode = {
                    joinCodeCalls++
                    JoinCodeResolution.Rejected("unused")
                },
            )

        val ready = resolver.resolve("11111111-1111-1111-1111-111111111111") as ActivationOutcome.Ready

        assertEquals("11111111-1111-1111-1111-111111111111", ready.enrollmentId)
        assertEquals(0, joinCodeCalls)
    }



    @Test
    fun `an idempotent reuse of an existing enrollment still activates`() {
        val resolver = resolver(enroll = { JoinEnrollResolution.Enrolled("enr-reused", reused = true) })

        val ready = resolver.resolve("AB12CD34") as ActivationOutcome.Ready

        assertEquals("enr-reused", ready.enrollmentId)
    }



    @Test
    fun `a rejected redemption is surfaced as a participant notice`() {
        val resolver = resolver(enroll = { JoinEnrollResolution.Rejected("That join code could not be redeemed.") })

        val notice = (resolver.resolve("AB12CD34") as ActivationOutcome.Notice).notice

        assertEquals("That join code could not be redeemed.", notice.message)
    }

}
