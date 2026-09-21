package me.code4me.services.agent

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import me.code4me.research.session.ResearchSessionService
import me.code4me.services.app.AppService
import me.code4me.services.app.PreparedAcpRuntimeHandoff
import me.code4me.services.app.ProjectAcpPreparation
import me.code4me.services.app.getAppService
import me.code4me.services.project.ProjectTokenService
import me.code4me.services.project.getProjectTokenService
import me.code4me.services.state.AuthSettings
import me.code4me.services.state.getAuthState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path

/**
 * ISSUE-18: while a research study owns a project, the direct managed
 * "Code4Me Agent" entry must not be registered or offered; the research
 * activation entry is authoritative. A project without a study keeps the
 * ordinary managed-agent setup.
 *
 * The study gate reads only an already-created [ResearchSessionService], so the
 * tests mock the project's service lookup instead of starting the research
 * stack. The active-study cases return before any auth/server access; the
 * non-study cases mock the auth state lookup. No test ever touches the
 * developer's real `~/.jetbrains/acp.json`.
 */
class ParticipantAgentSetupServiceTest {
    private val service = ParticipantAgentSetupService()

    @AfterEach
    fun tearDown() {
        service.dispose()
        unmockkAll()
    }

    @Test
    fun `an active study redirects prepare to the research entry`() {
        val project = studyProject()

        val status = service.prepare(project)

        assertEquals(ParticipantSetupStep.STUDY_ACTIVE, status.step)
        assertNotEquals(ParticipantSetupStep.READY, status.step)
        assertTrue(status.message.contains("Code4Me Research Proxy"), status.message)
        assertTrue(status.message.contains("research activation"), status.message)
        assertFalse(status.message.contains("Code4Me Agent is ready"), status.message)
        assertFalse(status.canRepair)
        assertFalse(status.useLegacyAcpPreparation)
    }

    @Test
    fun `an active study also blocks the legacy fallback preparation`() {
        val project = studyProject()
        var fallbackRuns = 0
        val preparation =
            ProjectAcpPreparation {
                fallbackRuns++
                PreparedAcpRuntimeHandoff(
                    workspace = "/tmp/project",
                    handoffPath = Path.of("/tmp/project/.idea/code4me/acp-runtime.env"),
                    expiresInSeconds = 300,
                )
            }

        val status = service.prepareWithLegacyFallback(project, preparation)

        assertEquals(ParticipantSetupStep.STUDY_ACTIVE, status.step)
        assertEquals(0, fallbackRuns, "the legacy ACP handoff must not run during a study")
    }

    @Test
    fun `a project without a research service keeps the ordinary setup path`() {
        val project = mock<Project>()
        whenever(project.getServiceIfCreated(ResearchSessionService::class.java)).thenReturn(null)
        whenever(project.name).thenReturn("Example Project")
        stubSignedOut()

        val status = service.prepare(project)

        assertNotEquals(ParticipantSetupStep.STUDY_ACTIVE, status.step)
        assertFalse(status.message.contains("research study is active"), status.message)
    }

    @Test
    fun `a research service without a study context keeps the ordinary setup path`() {
        val project = mock<Project>()
        val research = mock<ResearchSessionService>()
        whenever(research.hasStudyContext()).thenReturn(false)
        whenever(project.getServiceIfCreated(ResearchSessionService::class.java)).thenReturn(research)
        whenever(project.name).thenReturn("Example Project")
        stubSignedOut()

        val status = service.prepare(project)

        assertNotEquals(ParticipantSetupStep.STUDY_ACTIVE, status.step)
        verify(research).hasStudyContext()
    }

    @Test
    fun `a failing research lookup is treated as no study context`() {
        val project = mock<Project>()
        whenever(project.getServiceIfCreated(ResearchSessionService::class.java))
            .thenThrow(IllegalStateException("research service unavailable"))
        whenever(project.name).thenReturn("Example Project")
        stubSignedOut()

        val status = service.prepare(project)

        assertNotEquals(ParticipantSetupStep.STUDY_ACTIVE, status.step)
    }

    /**
     * ISSUE-005 F2 repair: prove the legacy-fallback trio actually executes. With a
     * non-study project, an Unavailable install result, and the sandbox property
     * unset (so [localDevelopmentRuntime] yields nothing), [prepare] must flag
     * [useLegacyAcpPreparation], and [prepareWithLegacyFallback] must then really
     * invoke the injected [ProjectAcpPreparation] and resolve READY via the
     * development handoff — not merely reference the call sites. The installer is
     * constructor-mocked (narrowest seam: the real flag-setting branch still runs);
     * the handoff itself is a fake returning a well-formed result, which proves
     * invocation, not handoff internals.
     */
    @Test
    fun `an unavailable runtime outside the sandbox runs the legacy fallback and resolves ready`() {
        val sandboxBefore = System.getProperty("idea.plugin.in.sandbox.mode")
        System.clearProperty("idea.plugin.in.sandbox.mode")
        try {
            val project = mock<Project>()
            whenever(project.getServiceIfCreated(ResearchSessionService::class.java)).thenReturn(null)

            val auth = mockk<AuthSettings>()
            every { auth.isAuthenticated() } returns true
            mockkStatic("me.code4me.services.state.AuthStateKt")
            every { getAuthState() } returns auth

            mockkStatic(PluginManagerCore::class)
            every { PluginManagerCore.getPlugin(any<PluginId>()) } returns mockk<IdeaPluginDescriptor>()
            every { PluginManagerCore.isDisabled(any<PluginId>()) } returns false

            val tokens = mock<ProjectTokenService>()
            whenever(tokens.isActivated()).thenReturn(true)
            whenever(tokens.hasProjectToken()).thenReturn(true)
            mockkStatic("me.code4me.services.project.ProjectTokenServiceKt")
            every { getProjectTokenService(any()) } returns tokens

            val app = mock<AppService>()
            whenever(app.checkManagedCapabilities()).thenReturn(Result.success(Unit))
            mockkStatic("me.code4me.services.app.AppServiceKt")
            every { getAppService() } returns app

            mockkConstructor(ManagedRuntimeInstaller::class)
            every { anyConstructed<ManagedRuntimeInstaller>().ensureInstalled(any()) } returns
                RuntimeInstallResult.Unavailable("No managed runtime for this platform in tests.")

            // NB: constructed AFTER mockkConstructor, so its installer is the mocked one
            // (the shared field instance was built before this test's mocks existed).
            val localService = ParticipantAgentSetupService()
            try {
                var fallbackRuns = 0
                val preparation =
                    ProjectAcpPreparation {
                        fallbackRuns++
                        PreparedAcpRuntimeHandoff(
                            workspace = "/tmp/project",
                            handoffPath = Path.of("/tmp/project/.idea/code4me/acp-runtime.env"),
                            expiresInSeconds = 300,
                        )
                    }

                val flagged = localService.prepare(project)

                assertTrue(
                    flagged.useLegacyAcpPreparation,
                    "Unavailable with no sandbox runtime must flag the legacy handoff",
                )

                val status = localService.prepareWithLegacyFallback(project, preparation)

                assertEquals(1, fallbackRuns, "the injected legacy ACP handoff must actually execute")
                assertEquals(ParticipantSetupStep.READY, status.step)
                assertTrue(status.message.contains("development ACP handoff"), status.message)
                assertFalse(status.canRepair)
            } finally {
                localService.dispose()
            }
        } finally {
            if (sandboxBefore != null) System.setProperty("idea.plugin.in.sandbox.mode", sandboxBefore)
        }
    }

    private fun studyProject(): Project {
        val project = mock<Project>()
        val research = mock<ResearchSessionService>()
        whenever(research.hasStudyContext()).thenReturn(true)
        whenever(project.getServiceIfCreated(ResearchSessionService::class.java)).thenReturn(research)
        return project
    }

    /** Signs out so `prepare` stops at the first ordinary participant step. */
    private fun stubSignedOut() {
        val auth = mockk<AuthSettings>()
        every { auth.isAuthenticated() } returns false
        mockkStatic("me.code4me.services.state.AuthStateKt")
        every { getAuthState() } returns auth
    }
}

/**
 * ISSUE-18 developer boundary: `prepareDeveloperAgents` stays behind the
 * `code4me.developerAgents` system property and is never reachable from a
 * study-active participant surface.
 */
class DeveloperAgentGateTest {
    private val service = ParticipantAgentSetupService()

    @AfterEach
    fun tearDown() {
        service.dispose()
        System.clearProperty(ParticipantAgentSetupService.DEVELOPER_AGENTS_PROPERTY)
    }

    @Test
    fun `developer agents are a no-op while the gate is unset`() {
        System.clearProperty(ParticipantAgentSetupService.DEVELOPER_AGENTS_PROPERTY)
        var started = 0

        service.prepareDeveloperAgents(mock(), { started++ }, runAsync = { it() })

        assertEquals(0, started)
        assertFalse(developerAgentsEnabled())
    }

    @Test
    fun `developer agents run when the gate is set`() {
        System.setProperty(ParticipantAgentSetupService.DEVELOPER_AGENTS_PROPERTY, "true")
        var started = 0

        service.prepareDeveloperAgents(mock(), { started++ }, runAsync = { it() })

        assertEquals(1, started)
        assertTrue(developerAgentsEnabled())
    }

    @Test
    fun `a study-active participant surface never reaches developer agents`() {
        assertFalse(
            mayPrepareDeveloperAgents(
                ParticipantSetupStatus(
                    ParticipantSetupStep.STUDY_ACTIVE,
                    ParticipantAgentSetupService.STUDY_ACTIVE_MESSAGE,
                ),
            ),
        )
        assertTrue(mayPrepareDeveloperAgents(ParticipantSetupStatus(ParticipantSetupStep.READY, "ready")))
        assertTrue(mayPrepareDeveloperAgents(ParticipantSetupStatus(ParticipantSetupStep.SIGN_IN, "sign in")))
        assertTrue(mayPrepareDeveloperAgents(ParticipantSetupStatus(ParticipantSetupStep.PREPARE_AGENT, "prepare")))
        assertTrue(mayPrepareDeveloperAgents(ParticipantSetupStatus(ParticipantSetupStep.CHECK_SERVER, "check")))
    }
}
