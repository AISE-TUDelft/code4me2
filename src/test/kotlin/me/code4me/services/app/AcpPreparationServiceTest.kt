package me.code4me.services.app

import com.intellij.openapi.project.Project
import me.code4me.api.generated.infrastructure.ClientException
import me.code4me.api.generated.model.PrepareAcpGrant
import me.code4me.api.generated.model.PrepareAcpGrantPostResponse
import me.code4me.services.project.ProjectTokenService
import me.code4me.services.state.AuthSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

private const val TEST_BACKEND_URL = "https://code4me.example.test/api"

// Every case must stub backendUrlProvider/runtimeBackendUrlProvider: their defaults reach for the
// AppService application service, which does not exist in a plain JUnit JVM.
class AcpPreparationServiceTest {
    @Test
    fun `prepare rejects when plugin user is not authenticated`() {
        val authSettings = mock<AuthSettings>()
        whenever(authSettings.isAuthenticated()).thenReturn(false)

        val service =
            AcpPreparationService(
                authStateProvider = { authSettings },
            )

        val error =
            assertThrows(AcpPreparationException::class.java) {
                service.prepare(mockProject())
            }

        assertEquals("Log in to Code4Me before preparing an ACP agent session.", error.message)
    }

    @Test
    fun `prepare activates the project before requesting the ACP grant`() {
        val authSettings = mock<AuthSettings>()
        whenever(authSettings.isAuthenticated()).thenReturn(true)

        val tokenService = mock<ProjectTokenService>()
        val projectId = UUID.randomUUID()
        whenever(tokenService.getProjectToken()).thenReturn(projectId.toString())

        val tempDir = Files.createTempDirectory("acp-preparation-activation")
        val project = mockProject(basePath = tempDir.toString())
        var activatedProject: Project? = null

        val service =
            AcpPreparationService(
                authStateProvider = { authSettings },
                projectTokenServiceProvider = { tokenService },
                backendUrlProvider = { TEST_BACKEND_URL },
                runtimeBackendUrlProvider = { "" },
                sessionRefresher = {},
                projectActivator = { activatedProject = it },
                grantRequester = { request ->
                    PrepareAcpGrantPostResponse(
                        grant = "prepared-grant",
                        workspace = request.workspace,
                        expiresInSeconds = 300,
                    )
                },
                handoffWriter = writerInto(tempDir),
            )

        service.prepare(project)

        assertEquals(project, activatedProject)
    }

    @Test
    fun `prepare rejects when canonical workspace cannot be obtained`() {
        val authSettings = mock<AuthSettings>()
        whenever(authSettings.isAuthenticated()).thenReturn(true)

        val tokenService = mock<ProjectTokenService>()
        whenever(tokenService.getProjectToken()).thenReturn(UUID.randomUUID().toString())

        val project = mockProject(basePath = null)

        val service =
            AcpPreparationService(
                authStateProvider = { authSettings },
                projectTokenServiceProvider = { tokenService },
                sessionRefresher = {},
                projectActivator = {},
            )

        val error =
            assertThrows(AcpPreparationException::class.java) {
                service.prepare(project)
            }

        assertEquals("Code4Me could not determine a canonical workspace for this project.", error.message)
    }

    @Test
    fun `prepare requests a grant with the canonical workspace and writes launcher env file`() {
        val authSettings = mock<AuthSettings>()
        whenever(authSettings.isAuthenticated()).thenReturn(true)

        val tokenService = mock<ProjectTokenService>()
        val projectId = UUID.randomUUID()
        whenever(tokenService.getProjectToken()).thenReturn(projectId.toString())

        val tempDir = Files.createTempDirectory("acp-preparation-test")
        val project = mockProject(basePath = tempDir.toString())

        val requests = mutableListOf<PrepareAcpGrant>()
        val service =
            AcpPreparationService(
                authStateProvider = { authSettings },
                projectTokenServiceProvider = { tokenService },
                backendUrlProvider = { TEST_BACKEND_URL },
                runtimeBackendUrlProvider = { "" },
                sessionRefresher = {},
                projectActivator = {},
                grantRequester = { request ->
                    requests += request
                    PrepareAcpGrantPostResponse(
                        grant = "prepared-grant",
                        workspace = request.workspace,
                        expiresInSeconds = 300,
                    )
                },
                handoffWriter = writerInto(tempDir),
            )

        val handoff = service.prepare(project)

        assertEquals(1, requests.size)
        assertEquals(projectId, requests.single().projectId)
        assertEquals(tempDir.toRealPath().toString().replace('\\', '/'), requests.single().workspace)
        assertEquals(tempDir.resolve("runtime/acp.env"), handoff.handoffPath)
        assertEquals(300, handoff.expiresInSeconds)

        val handoffText = Files.readString(handoff.handoffPath)
        assertTrue(handoffText.contains("CODE4ME_ACP_BACKEND_URL=$TEST_BACKEND_URL"))
        assertTrue(handoffText.contains("CODE4ME_ACP_GRANT=prepared-grant"))
        assertFalse(handoffText.contains("auth_token"))
        assertFalse(handoffText.contains(projectId.toString()))
    }

    @Test
    fun `prepare prefers the runtime backend url when one is configured`() {
        val authSettings = mock<AuthSettings>()
        whenever(authSettings.isAuthenticated()).thenReturn(true)

        val tokenService = mock<ProjectTokenService>()
        whenever(tokenService.getProjectToken()).thenReturn(UUID.randomUUID().toString())

        val tempDir = Files.createTempDirectory("acp-preparation-runtime-url")
        val project = mockProject(basePath = tempDir.toString())

        val service =
            AcpPreparationService(
                authStateProvider = { authSettings },
                projectTokenServiceProvider = { tokenService },
                backendUrlProvider = { TEST_BACKEND_URL },
                runtimeBackendUrlProvider = { "http://host.docker.internal:8008" },
                sessionRefresher = {},
                projectActivator = {},
                grantRequester = { request ->
                    PrepareAcpGrantPostResponse(
                        grant = "prepared-grant",
                        workspace = request.workspace,
                        expiresInSeconds = 300,
                    )
                },
                handoffWriter = writerInto(tempDir),
            )

        val handoff = service.prepare(project)

        val handoffText = Files.readString(handoff.handoffPath)
        assertTrue(handoffText.contains("CODE4ME_ACP_BACKEND_URL=http://host.docker.internal:8008"))
    }

    @Test
    fun `handoff writer always writes shared runtime credentials for project independent agent launch`() {
        val tempDir = Files.createTempDirectory("acp-preparation-shared")
        val projectDir = tempDir.resolve("arbitrary-user-project")
        Files.createDirectories(projectDir)
        val sharedHandoff = tempDir.resolve("home/.code4me/acp-runtime.env")
        val project = mockProject(basePath = projectDir.toString())

        val writer =
            RuntimeCredentialHandoffWriter(
                handoffPathProvider = { projectDir.resolve(".idea/code4me/acp-runtime.env") },
                sharedHandoffPath = sharedHandoff,
                agentHandoffPathProvider = { null },
            )

        writer.write(project, "http://localhost:8008", "prepared-grant")

        val sharedText = Files.readString(sharedHandoff)
        assertTrue(sharedText.contains("CODE4ME_ACP_BACKEND_URL=http://localhost:8008"))
        assertTrue(sharedText.contains("CODE4ME_ACP_GRANT=prepared-grant"))
    }

    @Test
    fun `handoff writer restricts runtime credential file permissions when supported`() {
        val tempDir = Files.createTempDirectory("acp-preparation-permissions")
        val handoffPath = tempDir.resolve("runtime/acp.env")
        val project = mockProject(basePath = tempDir.toString())

        val writer =
            RuntimeCredentialHandoffWriter(
                handoffPathProvider = { handoffPath },
                sharedHandoffPath = tempDir.resolve("home/.code4me/acp-runtime.env"),
                agentHandoffPathProvider = { null },
            )

        writer.write(project, "http://localhost:8008", "prepared-grant")

        try {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(handoffPath),
            )
        } catch (_: UnsupportedOperationException) {
            assertTrue(handoffPath.toFile().canRead())
            assertTrue(handoffPath.toFile().canWrite())
        }
    }

    @Test
    fun `prepare maps unauthorized backend response to login guidance`() {
        val authSettings = mock<AuthSettings>()
        whenever(authSettings.isAuthenticated()).thenReturn(true)

        val tokenService = mock<ProjectTokenService>()
        whenever(tokenService.getProjectToken()).thenReturn(UUID.randomUUID().toString())
        var staleStateReset = false

        val tempDir = Files.createTempDirectory("acp-preparation-auth")
        val project = mockProject(basePath = tempDir.toString())

        val service =
            AcpPreparationService(
                authStateProvider = { authSettings },
                projectTokenServiceProvider = { tokenService },
                backendUrlProvider = { TEST_BACKEND_URL },
                runtimeBackendUrlProvider = { "" },
                sessionRefresher = {},
                projectActivator = {},
                staleStateResetter = { staleStateReset = true },
                grantRequester = { throw ClientException("unauthorized", 401) },
            )

        val error =
            assertThrows(AcpPreparationException::class.java) {
                service.prepare(project)
            }

        assertEquals(
            "Your Code4Me login is no longer valid. Log in again and prepare the ACP agent session again.",
            error.message,
        )
        assertTrue(staleStateReset)
        verify(authSettings).clearUserData()
    }

    @Test
    fun `prepare maps missing backend project response to retry guidance`() {
        val authSettings = mock<AuthSettings>()
        whenever(authSettings.isAuthenticated()).thenReturn(true)

        val tokenService = mock<ProjectTokenService>()
        whenever(tokenService.getProjectToken()).thenReturn(UUID.randomUUID().toString())
        var staleStateReset = false

        val tempDir = Files.createTempDirectory("acp-preparation-project")
        val project = mockProject(basePath = tempDir.toString())

        val service =
            AcpPreparationService(
                authStateProvider = { authSettings },
                projectTokenServiceProvider = { tokenService },
                backendUrlProvider = { TEST_BACKEND_URL },
                runtimeBackendUrlProvider = { "" },
                sessionRefresher = {},
                projectActivator = {},
                staleStateResetter = { staleStateReset = true },
                grantRequester = { throw ClientException("not found", 404) },
            )

        val error =
            assertThrows(AcpPreparationException::class.java) {
                service.prepare(project)
            }

        assertEquals(
            "Code4Me could not activate this project for the ACP agent session. Try again.",
            error.message,
        )
        assertTrue(staleStateReset)
        verify(tokenService).clearProjectToken()
    }

    @Test
    fun `prepare maps activation auth failure to login guidance`() {
        val authSettings = mock<AuthSettings>()
        whenever(authSettings.isAuthenticated()).thenReturn(true)

        val tokenService = mock<ProjectTokenService>()
        var staleStateReset = false

        val service =
            AcpPreparationService(
                authStateProvider = { authSettings },
                projectTokenServiceProvider = { tokenService },
                sessionRefresher = {},
                staleStateResetter = { staleStateReset = true },
                projectActivator = { throw ClientException("unauthorized", 401) },
            )

        val error =
            assertThrows(AcpPreparationException::class.java) {
                service.prepare(mockProject())
            }

        assertEquals(
            "Your Code4Me login is no longer valid. Log in again and prepare the ACP agent session again.",
            error.message,
        )
        assertTrue(staleStateReset)
        verify(authSettings).clearUserData()
    }

    @Test
    fun `prepare refreshes the plugin session before project activation`() {
        val authSettings = mock<AuthSettings>()
        whenever(authSettings.isAuthenticated()).thenReturn(true)

        val tokenService = mock<ProjectTokenService>()
        whenever(tokenService.getProjectToken()).thenReturn(UUID.randomUUID().toString())

        val tempDir = Files.createTempDirectory("acp-preparation-session")
        val project = mockProject(basePath = tempDir.toString())
        val steps = mutableListOf<String>()

        val service =
            AcpPreparationService(
                authStateProvider = { authSettings },
                projectTokenServiceProvider = { tokenService },
                backendUrlProvider = { TEST_BACKEND_URL },
                runtimeBackendUrlProvider = { "" },
                sessionRefresher = { steps += "session" },
                projectActivator = { steps += "project" },
                grantRequester = { request ->
                    PrepareAcpGrantPostResponse(
                        grant = "prepared-grant",
                        workspace = request.workspace,
                        expiresInSeconds = 300,
                    )
                },
                handoffWriter = writerInto(tempDir),
            )

        service.prepare(project)

        assertEquals(listOf("session", "project"), steps)
    }

    // Keeps every write inside [tempDir] so tests never touch the developer's real
    // ~/.code4me/acp-runtime.env or discover a sibling server checkout on the machine.
    private fun writerInto(tempDir: Path): RuntimeCredentialHandoffWriter =
        RuntimeCredentialHandoffWriter(
            handoffPathProvider = { tempDir.resolve("runtime/acp.env") },
            sharedHandoffPath = tempDir.resolve("home/.code4me/acp-runtime.env"),
            agentHandoffPathProvider = { null },
        )

    private fun mockProject(basePath: String? = "/tmp/project"): Project {
        val project = mock<Project>()
        whenever(project.basePath).thenReturn(basePath)
        whenever(project.name).thenReturn("Example Project")
        return project
    }
}
