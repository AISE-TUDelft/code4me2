package me.code4me.research.status

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import me.code4me.research.session.InferenceBudgetState
import me.code4me.research.session.ParticipantStudyStateV1
import me.code4me.research.session.SpoolDeliveryState
import me.code4me.research.session.StudyBlockReason
import me.code4me.research.session.StudyComponentState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// --------------------------------------------------------------------------
// ParticipantStatusPresentationTest.kt
// --------------------------------------------------------------------------

/**
 * Pure-JVM tests for the participant status presentation (Issue 10).
 *
 * They pin the mapping from [ParticipantStudyStateV1] to the participant-visible
 * status view and, critically, assert that no study internal (enrollment/study
 * ids, manifest digest) or synthetic canary can leak through the
 * status surface.
 */
class ParticipantStatusPresentationTest {
    private fun state(
        blockReason: StudyBlockReason? = null,
        consent: StudyComponentState = StudyComponentState.AVAILABLE,
        compatibility: StudyComponentState = StudyComponentState.AVAILABLE,
        session: StudyComponentState = StudyComponentState.AVAILABLE,
        manifestDigest: String? = "sha256:" + "a".repeat(64),
        delivery: SpoolDeliveryState = SpoolDeliveryState.UNAVAILABLE,
    ): ParticipantStudyStateV1 =
        ParticipantStudyStateV1(
            consentState = consent,
            compatibilityState = compatibility,
            sessionState = session,
            manifestDigest = manifestDigest,
            blockReason = blockReason,
            deliveryState = delivery,
        )

    @Test
    fun `active study reports collecting without an action`() {
        val view = ParticipantStatusPresentation.of(state())

        assertEquals(ParticipantStatusPresentation.ACTIVE_HEADLINE, view.headline)
        assertEquals(ParticipantStatusSeverity.OK, view.severity)
        assertTrue(view.isCollecting)
        assertNull(view.actionHint)
        assertNull(view.reasonCode)
        assertFalse(ParticipantStatusPresentation.shouldNotify(view))
    }

    @Test
    fun `inactive study is informational and never notifies`() {
        val view =
            ParticipantStatusPresentation.of(
                state(session = StudyComponentState.UNAVAILABLE),
            )

        assertEquals(ParticipantStatusPresentation.INACTIVE_HEADLINE, view.headline)
        assertEquals(ParticipantStatusSeverity.INFO, view.severity)
        assertFalse(ParticipantStatusPresentation.shouldNotify(view))
        assertTrue(view.actionHint?.contains("research settings") == true)
    }

    
    @Test
    fun `revoked studies are terminal blocks`() {
        for (reason in listOf(StudyBlockReason.REVOKED)) {
            val view = ParticipantStatusPresentation.of(state(blockReason = reason))

            assertEquals(ParticipantStatusPresentation.BLOCKED_HEADLINE, view.headline)
            assertEquals(ParticipantStatusSeverity.ERROR, view.severity)
            assertEquals(reason.value, view.reasonCode)
            assertTrue(ParticipantStatusPresentation.shouldNotify(view))
        }
    }

    @Test
    fun `manifest expiry and incompatibility are actionable blocks`() {
        val expired =
            ParticipantStatusPresentation.of(
                state(blockReason = StudyBlockReason.MANIFEST_EXPIRED),
            )
        assertEquals(ParticipantStatusPresentation.BLOCKED_HEADLINE, expired.headline)
        assertEquals(ParticipantStatusSeverity.WARNING, expired.severity)
        assertTrue(expired.actionHint?.contains("Reconnect") == true)

        val incompatible =
            ParticipantStatusPresentation.of(
                state(blockReason = StudyBlockReason.INCOMPATIBLE_ENVIRONMENT),
            )
        assertEquals(ParticipantStatusPresentation.BLOCKED_HEADLINE, incompatible.headline)
        assertEquals(StudyBlockReason.INCOMPATIBLE_ENVIRONMENT.value, incompatible.reasonCode)
        assertTrue(incompatible.actionHint?.contains("Update") == true)

        val invalid =
            ParticipantStatusPresentation.of(
                state(blockReason = StudyBlockReason.MANIFEST_INVALID),
            )
        assertEquals(ParticipantStatusPresentation.BLOCKED_HEADLINE, invalid.headline)
        assertEquals(ParticipantStatusSeverity.ERROR, invalid.severity)
    }

    @Test
    fun `a missing study policy is an actionable configuration block`() {
        val view =
            ParticipantStatusPresentation.of(
                state(blockReason = StudyBlockReason.POLICY_INVALID),
            )

        assertEquals(ParticipantStatusPresentation.BLOCKED_HEADLINE, view.headline)
        assertEquals(ParticipantStatusSeverity.ERROR, view.severity)
        assertEquals(StudyBlockReason.POLICY_INVALID.value, view.reasonCode)
        assertTrue(ParticipantStatusPresentation.shouldNotify(view))
        assertTrue(view.actionHint?.contains("session policy") == true, view.actionHint)
    }

    @Test
    fun `transport failure with a held manifest is recovering`() {
        val view =
            ParticipantStatusPresentation.of(
                state(blockReason = StudyBlockReason.TRANSPORT_FAILED),
            )

        assertEquals(ParticipantStatusPresentation.RECOVERING_HEADLINE, view.headline)
        assertEquals(ParticipantStatusSeverity.WARNING, view.severity)
        assertTrue(ParticipantStatusPresentation.shouldNotify(view))
    }

    @Test
    fun `transport failure without a manifest is a failure`() {
        val view =
            ParticipantStatusPresentation.of(
                state(blockReason = StudyBlockReason.TRANSPORT_FAILED, manifestDigest = null),
            )

        assertEquals(ParticipantStatusPresentation.FAILED_HEADLINE, view.headline)
        assertEquals(ParticipantStatusSeverity.WARNING, view.severity)
    }

    @Test
    fun `runtime unavailable and unknown are failures`() {
        assertEquals(
            ParticipantStatusPresentation.FAILED_HEADLINE,
            ParticipantStatusPresentation.of(state(blockReason = StudyBlockReason.RUNTIME_UNAVAILABLE)).headline,
        )
        assertEquals(
            ParticipantStatusSeverity.ERROR,
            ParticipantStatusPresentation.of(state(blockReason = StudyBlockReason.UNKNOWN)).severity,
        )
    }

    @Test
    fun `component states without a block reason are mapped explicitly`() {
        assertEquals(
            ParticipantStatusPresentation.BLOCKED_HEADLINE,
            ParticipantStatusPresentation.of(state(compatibility = StudyComponentState.BLOCKED)).headline,
        )
        assertEquals(
            ParticipantStatusPresentation.FAILED_HEADLINE,
            ParticipantStatusPresentation.of(state(session = StudyComponentState.FAILED)).headline,
        )
        assertEquals(
            ParticipantStatusPresentation.PAUSED_HEADLINE,
            ParticipantStatusPresentation.of(state(session = StudyComponentState.PAUSED)).headline,
        )
    }

    @Test
    fun `session ended is informational`() {
        val view =
            ParticipantStatusPresentation.of(
                state(blockReason = StudyBlockReason.SESSION_ENDED),
            )

        assertEquals(ParticipantStatusPresentation.INACTIVE_HEADLINE, view.headline)
        assertFalse(ParticipantStatusPresentation.shouldNotify(view))
    }

    @Test
    fun `status view never leaks study internals or canaries`() {
        val canaries =
            listOf(
                "CANARY_ENROLLMENT",
                "CANARY_STUDY",
                "CANARY_MANIFEST_DIGEST",
                "/Users/participant/secret-project",
                "CANARY_PROFILE",
                "CANARY_PROFILE_DIGEST",
            )
        val state =
            ParticipantStudyStateV1(
                enrollmentId = canaries[0],
                studyId = canaries[1],
                assignmentId = canaries[2],
                agentProfileId = canaries[4],
                profileDigest = canaries[5],
                consentState = StudyComponentState.BLOCKED,
                compatibilityState = StudyComponentState.BLOCKED,
                sessionState = StudyComponentState.BLOCKED,
                manifestDigest = canaries[2],
                manifestExpiry = "2026-01-01T00:00:00Z",
                blockReason = StudyBlockReason.MANIFEST_INVALID,
                blockReasonDetail = canaries[3],
            )

        val view = ParticipantStatusPresentation.of(state)
        val rendered = "${view.headline}\n${view.tooltip}\n${view.actionHint}"

        canaries.forEach { canary ->
            assertFalse(rendered.contains(canary), "status surface leaked $canary")
        }
        assertTrue(view.tooltip.contains(StudyBlockReason.MANIFEST_INVALID.value))
    }

    @Test
    fun `unavailable fallback is informational and safe`() {
        val view = ParticipantStatusPresentation.unavailable()

        assertEquals(ParticipantStatusPresentation.UNAVAILABLE_HEADLINE, view.headline)
        assertEquals(ParticipantStatusSeverity.INFO, view.severity)
        assertFalse(ParticipantStatusPresentation.shouldNotify(view))
    }

    @Test
    fun `spool delivery posture is surfaced without study internals`() {
        val recovering = ParticipantStatusPresentation.of(state(delivery = SpoolDeliveryState.RECOVERING))
        assertEquals(ParticipantStatusPresentation.RECOVERING_HEADLINE, recovering.headline)
        assertEquals(ParticipantStatusSeverity.WARNING, recovering.severity)
        assertEquals(SpoolDeliveryState.RECOVERING.value, recovering.reasonCode)

        val full = ParticipantStatusPresentation.of(state(delivery = SpoolDeliveryState.SPOOL_FULL))
        assertEquals(ParticipantStatusPresentation.RECOVERING_HEADLINE, full.headline)
        assertEquals(SpoolDeliveryState.SPOOL_FULL.value, full.reasonCode)

        val revoked = ParticipantStatusPresentation.of(state(delivery = SpoolDeliveryState.REVOKED))
        assertEquals(ParticipantStatusPresentation.BLOCKED_HEADLINE, revoked.headline)
        assertEquals(ParticipantStatusSeverity.ERROR, revoked.severity)
    }

    @Test
    fun `only warning and error severities notify`() {
        for (severity in ParticipantStatusSeverity.entries) {
            val view = ParticipantStatusView("h", "t", severity)
            assertEquals(
                severity == ParticipantStatusSeverity.WARNING || severity == ParticipantStatusSeverity.ERROR,
                ParticipantStatusPresentation.shouldNotify(view),
                "unexpected notify decision for $severity",
            )
        }
    }

    // ------------------------------------------------------------------
    // Advisory AI budget (participant budgets): never a block
    // ------------------------------------------------------------------

    private fun budget(
        exhausted: Boolean,
        fractionUsed: Double,
    ): InferenceBudgetState {
        val limit = 5_000_000L
        val consumed = (fractionUsed * limit).toLong()
        return InferenceBudgetState(
            limitMicroUsd = limit,
            consumedMicroUsd = consumed,
            reservedMicroUsd = 0L,
            remainingMicroUsd = (limit - consumed).coerceAtLeast(0L),
            fractionUsed = fractionUsed,
            warningFraction = 0.8,
            warning = exhausted || fractionUsed >= 0.8,
            exhausted = exhausted,
            exhaustedAt = if (exhausted) "2026-01-01T00:20:00Z" else null,
            asOf = "2026-01-01T00:30:00Z",
        )
    }

    private fun rendered(view: ParticipantStatusView): String = "${view.headline}\n${view.tooltip}\n${view.actionHint}"

    /** A collecting, launchable state (the shared fixture carries no expiry) holding [budget]. */
    private fun activeWithBudget(budget: InferenceBudgetState): ParticipantStudyStateV1 =
        state().copy(manifestExpiry = "2026-01-01T01:00:00Z", inferenceBudget = budget)

    @Test
    fun `an exhausted AI budget warns with a banner but never blocks`() {
        val state = activeWithBudget(budget(exhausted = true, fractionUsed = 1.0))

        val view = ParticipantStatusPresentation.of(state)

        assertEquals(ParticipantStatusPresentation.BUDGET_EXHAUSTED_HEADLINE, view.headline)
        assertEquals(ParticipantStatusSeverity.WARNING, view.severity)
        assertEquals(ParticipantStatusPresentation.BUDGET_EXHAUSTED_CODE, view.reasonCode)
        assertTrue(ParticipantStatusPresentation.shouldNotify(view), "an exhausted budget shows the editor banner")
        assertTrue(view.actionHint?.contains("tops up") == true, view.actionHint)
        assertTrue(view.actionHint?.contains("research collection continues") == true, view.actionHint)
        assertTrue(view.tooltip.contains("active"), view.tooltip)
        // Advisory only: the state itself still collects and may launch.
        assertNull(state.blockReason)
        assertTrue(state.isCollecting)
        assertTrue(state.canLaunch)
        // Never an amount, only the posture.
        val text = rendered(view)
        assertFalse(text.contains("5000000"), text)
        assertFalse(text.contains("micro"), text)
        assertFalse(text.contains("$"), text)
    }

    @Test
    fun `a nearly used up AI budget is status-bar information with the percentage only`() {
        val state = activeWithBudget(budget(exhausted = false, fractionUsed = 0.85))

        val view = ParticipantStatusPresentation.of(state)

        assertEquals(ParticipantStatusPresentation.budgetWarningHeadline(85), view.headline)
        assertTrue(view.headline.contains("85%"), view.headline)
        assertEquals(ParticipantStatusSeverity.INFO, view.severity)
        assertEquals(ParticipantStatusPresentation.BUDGET_WARNING_CODE, view.reasonCode)
        assertFalse(ParticipantStatusPresentation.shouldNotify(view), "a budget warning never nags in the editor")
        assertTrue(state.isCollecting)
        assertTrue(state.canLaunch)
        val text = rendered(view)
        assertFalse(text.contains("4250000"), text)
        assertFalse(text.contains("micro"), text)
        assertFalse(text.contains("$"), text)

        // Below the warning threshold the budget is invisible.
        val quiet = ParticipantStatusPresentation.of(state().copy(inferenceBudget = budget(exhausted = false, fractionUsed = 0.5)))
        assertEquals(ParticipantStatusPresentation.ACTIVE_HEADLINE, quiet.headline)
        assertEquals(ParticipantStatusSeverity.OK, quiet.severity)
        assertNull(quiet.reasonCode)
    }

    @Test
    fun `typed blocks and delivery problems outrank the AI budget`() {
        val exhausted = budget(exhausted = true, fractionUsed = 1.0)

        assertEquals(
            ParticipantStatusPresentation.BLOCKED_HEADLINE,
            ParticipantStatusPresentation.of(state(blockReason = StudyBlockReason.REVOKED).copy(inferenceBudget = exhausted)).headline,
        )
        assertEquals(
            ParticipantStatusPresentation.PAUSED_HEADLINE,
            ParticipantStatusPresentation.of(state(consent = StudyComponentState.PAUSED).copy(inferenceBudget = exhausted)).headline,
        )
        assertEquals(
            ParticipantStatusPresentation.RECOVERING_HEADLINE,
            ParticipantStatusPresentation.of(state(delivery = SpoolDeliveryState.RECOVERING).copy(inferenceBudget = exhausted)).headline,
        )
        // A warning-level budget is only shown while collecting.
        assertEquals(
            ParticipantStatusPresentation.INACTIVE_HEADLINE,
            ParticipantStatusPresentation
                .of(state(session = StudyComponentState.UNAVAILABLE).copy(inferenceBudget = budget(exhausted = false, fractionUsed = 0.9)))
                .headline,
        )
    }
}

// --------------------------------------------------------------------------
// ResearchStatusRegistrationTest.kt
// --------------------------------------------------------------------------

/**
 * Guards the plugin descriptor wiring for the participant status surface (Issue 10).
 *
 * The descriptor is read from the project tree rather than the test classpath
 * because every IntelliJ distribution jar also ships a `META-INF/plugin.xml`.
 */
class ResearchStatusRegistrationTest {
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
    fun `status bar widget factory is registered`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<statusBarWidgetFactory[^>]*id=\"me\\.code4me\\.research\\.status\"[^>]*" +
                    "implementation=\"me\\.code4me\\.research\\.status\\.ResearchStatusBarWidgetFactory\"",
            ).containsMatchIn(xml),
            "ResearchStatusBarWidgetFactory must be registered as a statusBarWidgetFactory",
        )
    }

    @Test
    fun `editor notification provider is registered`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<editorNotificationProvider[^>]*" +
                    "implementation=\"me\\.code4me\\.research\\.status\\.ResearchEditorNotificationProvider\"",
            ).containsMatchIn(xml),
            "ResearchEditorNotificationProvider must be registered as an editorNotificationProvider",
        )
    }
}

// --------------------------------------------------------------------------
// ResearchStatusSurfaceTest.kt
// --------------------------------------------------------------------------

/**
 * Light-platform test for the participant status surface (Issue 10).
 *
 * It verifies the status-bar widget is constructed with the registered id and
 * yields a non-blank text presentation, and that the editor notification
 * provider returns a decision function without a banner while the study is
 * inactive (so ordinary plugin use is not disturbed).
 */
class ResearchStatusSurfaceTest : BasePlatformTestCase() {
    fun testFactoryCreatesConfiguredWidget() {
        val factory = ResearchStatusBarWidgetFactory()

        assertEquals("me.code4me.research.status", factory.getId())
        assertEquals("Code4Me Research Status", factory.getDisplayName())
        assertTrue(factory.isEnabledByDefault())
        assertTrue(factory.isAvailable(project))

        val widget = factory.createWidget(project)
        assertEquals(factory.getId(), widget.ID())
        assertNotNull(widget.getPresentation())
    }

    fun testStatusTextAndTooltipAreNonBlank() {
        val widget = ResearchStatusBarWidget(project)
        val presentation = widget.getPresentation() as StatusBarWidget.TextPresentation

        assertTrue(presentation.getText().isNotBlank())
        assertTrue(presentation.getTooltipText()?.isNotBlank() == true)
    }

    fun testEditorNotificationReturnsADecisionFunction() {
        val file = myFixture.configureByText("Sample.kt", "fun main() {}").virtualFile

        val decision = ResearchEditorNotificationProvider().collectNotificationData(project, file)

        assertNotNull(decision)
    }

    fun testInactiveStudyDoesNotShowAnEditorBanner() {
        val file = myFixture.configureByText("Sample.kt", "fun main() {}").virtualFile
        val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(file)
        assertNotNull(fileEditor)

        val decision = ResearchEditorNotificationProvider().collectNotificationData(project, file)

        assertNull(decision.apply(fileEditor!!))
    }
}
