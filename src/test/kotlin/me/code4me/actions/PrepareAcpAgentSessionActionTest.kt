package me.code4me.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.project.Project
import me.code4me.services.app.AcpPreparationException
import me.code4me.services.app.PreparedAcpRuntimeHandoff
import me.code4me.services.app.ProjectAcpPreparation
import me.code4me.services.agent.ParticipantAgentSetupService
import me.code4me.services.agent.ParticipantSetupStatus
import me.code4me.services.agent.ParticipantSetupStep
import me.code4me.utils.notification.AcpPreparationIndicator
import me.code4me.utils.notification.AcpPreparationLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.nio.file.Path

class PrepareAcpAgentSessionActionTest {
    private lateinit var event: AnActionEvent
    private lateinit var presentation: Presentation

    @BeforeEach
    fun setUp() {
        event = mock()
        presentation = Presentation()
        whenever(event.presentation).thenReturn(presentation)
    }

    @Test
    fun `manual prepare shows progress before background work and clears it when ready`() {
        val project = mock<Project>()
        whenever(event.getData(CommonDataKeys.PROJECT)).thenReturn(project)
        val events = mutableListOf<String>()
        lateinit var work: () -> Unit
        val progress = recordingProgress(events)

        PrepareAcpAgentSessionAction(
            mock(),
            setup = { _, _ ->
                events += "setup"
                ParticipantSetupStatus(ParticipantSetupStep.READY, "ready")
            },
            backgroundRunner = { work = it },
            notify = { _, _ -> events += "ready" },
            progressFor = { progress },
        ).actionPerformed(event)

        assertEquals(listOf("preparing"), events)
        work()
        assertEquals(listOf("preparing", "setup", "done", "ready"), events)
    }

    @Test
    fun `manual prepare clears progress when setup fails`() {
        val project = mock<Project>()
        whenever(event.getData(CommonDataKeys.PROJECT)).thenReturn(project)
        val events = mutableListOf<String>()
        val progress = recordingProgress(events)

        PrepareAcpAgentSessionAction(
            mock(),
            setup = { _, _ -> throw AcpPreparationException("unavailable") },
            backgroundRunner = { it() },
            progressFor = { progress },
        ).actionPerformed(event)

        assertEquals(listOf("preparing", "done"), events)
    }

    @Test
    fun `update enables the action when a project is open`() {
        whenever(event.getData(CommonDataKeys.PROJECT)).thenReturn(mock<Project>())

        PrepareAcpAgentSessionAction(mock(), backgroundRunner = { it() }).update(event)

        assertTrue(presentation.isEnabledAndVisible)
    }

    @Test
    fun `update disables the action when no project is open`() {
        whenever(event.getData(CommonDataKeys.PROJECT)).thenReturn(null)

        PrepareAcpAgentSessionAction(mock(), backgroundRunner = { it() }).update(event)

        assertFalse(presentation.isEnabledAndVisible)
    }

    @Test
    fun `actionPerformed delegates to the preparation service for the current project`() {
        val project = mock<Project>()
        whenever(event.getData(CommonDataKeys.PROJECT)).thenReturn(project)

        val preparation = mock<ProjectAcpPreparation>()
        whenever(preparation.prepare(project)).thenReturn(
            PreparedAcpRuntimeHandoff(
                workspace = "/tmp/project",
                handoffPath = Path.of("/tmp/project/.idea/code4me/acp-runtime.env"),
                expiresInSeconds = 300,
            ),
        )

        PrepareAcpAgentSessionAction(
            preparation,
            setup = { currentProject, fallback ->
                fallback.prepare(currentProject)
                ParticipantSetupStatus(ParticipantSetupStep.READY, "ready")
            },
            backgroundRunner = { it() },
        ).actionPerformed(event)

        verify(preparation).prepare(project)
    }

    @Test
    fun `an active study shows the research redirect instead of the legacy prepare`() {
        val project = mock<Project>()
        whenever(event.getData(CommonDataKeys.PROJECT)).thenReturn(project)

        val preparation = mock<ProjectAcpPreparation>()
        val notified = mutableListOf<ParticipantSetupStatus>()

        PrepareAcpAgentSessionAction(
            preparation,
            setup = { _, _ ->
                ParticipantSetupStatus(
                    ParticipantSetupStep.STUDY_ACTIVE,
                    ParticipantAgentSetupService.STUDY_ACTIVE_MESSAGE,
                )
            },
            backgroundRunner = { it() },
            notify = { _, status -> notified += status },
        ).actionPerformed(event)

        // The legacy fallback must never run while a study owns the project.
        verifyNoInteractions(preparation)
        val status = notified.single()
        assertEquals(ParticipantSetupStep.STUDY_ACTIVE, status.step)
        assertTrue(status.message.contains("Code4Me Research Proxy"), status.message)
        assertFalse(status.message.contains("Code4Me Agent is ready"), status.message)
    }

    @Test
    fun `a ready outcome still shows the managed-agent success status`() {
        val project = mock<Project>()
        whenever(event.getData(CommonDataKeys.PROJECT)).thenReturn(project)

        val preparation = mock<ProjectAcpPreparation>()
        val notified = mutableListOf<ParticipantSetupStatus>()

        PrepareAcpAgentSessionAction(
            preparation,
            setup = { _, _ -> ParticipantSetupStatus(ParticipantSetupStep.READY, "Code4Me Agent is ready.") },
            backgroundRunner = { it() },
            notify = { _, status -> notified += status },
        ).actionPerformed(event)

        assertEquals(ParticipantSetupStep.READY, notified.single().step)
    }

    @Test
    fun `actionPerformed ignores events without a project`() {
        val preparation = mock<ProjectAcpPreparation>()
        whenever(event.getData(CommonDataKeys.PROJECT)).thenReturn(null)

        PrepareAcpAgentSessionAction(preparation, backgroundRunner = { it() }).actionPerformed(event)

        verifyNoInteractions(preparation)
    }

    private fun recordingProgress(events: MutableList<String>): AcpPreparationIndicator =
        object : AcpPreparationIndicator {
            override fun acquire(delayMs: Int): AcpPreparationLease {
                assertEquals(0, delayMs)
                events += "preparing"
                return object : AcpPreparationLease {
                    override fun finish(after: () -> Unit) {
                        events += "done"
                        after()
                    }

                    override fun complete(after: () -> Unit) {
                        events += "done"
                        after()
                    }
                }
            }
        }
}
