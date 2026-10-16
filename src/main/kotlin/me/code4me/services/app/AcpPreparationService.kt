package me.code4me.services.app

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import me.code4me.api.generated.infrastructure.ClientException
import me.code4me.api.generated.infrastructure.ServerException
import me.code4me.api.generated.model.PrepareAcpGrant
import me.code4me.api.generated.model.PrepareAcpGrantPostResponse
import me.code4me.api.wrapper.CookieAwareApiClient
import me.code4me.services.project.ProjectTokenService
import me.code4me.services.project.getProjectTokenService
import me.code4me.services.state.AuthSettings
import me.code4me.services.state.getAuthState
import me.code4me.utils.api.activateOrCreateProject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * Reports whether a credential is present without disclosing it. Used instead of interpolating
 * grants/tokens into log lines — the IDE log is written to disk in plaintext and is routinely
 * attached to bug reports.
 */
private fun secretForTrace(value: String?): String = if (value.isNullOrBlank()) "missing" else "present"

fun interface ProjectAcpPreparation {
    fun prepare(project: Project): PreparedAcpRuntimeHandoff
}

data class PreparedAcpRuntimeHandoff(
    val workspace: String,
    val handoffPath: Path,
    val expiresInSeconds: Int,
)

class AcpPreparationException(
    override val message: String,
) : RuntimeException(message)

/**
 * Obtains a one-time ACP launch grant from the backend and writes it, with the backend URL, to
 * the env-file locations a locally-running `code4me2-agent` process reads at startup.
 *
 * This is the handoff for the agent runtime Code4Me itself ships. Third-party ACP agents (Goose,
 * Codex) take the other path: they are registered in `~/.jetbrains/acp.json` by
 * [me.code4me.services.agent.AcpManager] and observed through
 * [me.code4me.services.agent.LocalProxyServer], because we cannot make them read our env files.
 *
 * All collaborators are injected so the service is testable without an IDE or a live backend.
 */
class AcpPreparationService(
    private val authStateProvider: () -> AuthSettings = ::getAuthState,
    private val projectTokenServiceProvider: (Project) -> ProjectTokenService = ::getProjectTokenService,
    private val backendUrlProvider: () -> String = { getAppService().getApiBaseUrl() },
    private val runtimeBackendUrlProvider: () -> String = { getAppService().getAcpRuntimeBaseUrl() },
    private val grantRequester: (PrepareAcpGrant) -> PrepareAcpGrantPostResponse = { request ->
        getAppService().prepareAcpGrant(request)
    },
    private val sessionRefresher: () -> Unit = { getAppService().acquireSessionWithStoredToken() },
    private val projectActivator: (Project) -> Unit = { project -> activateOrCreateProject(project, LOG) },
    private val staleStateResetter: () -> Unit = { CookieAwareApiClient.clearCookies() },
    private val handoffWriter: RuntimeCredentialHandoffWriter = RuntimeCredentialHandoffWriter(),
) : ProjectAcpPreparation {
    companion object {
        private val LOG = thisLogger()
    }

    override fun prepare(project: Project): PreparedAcpRuntimeHandoff {
        LOG.debug("prepare() started for project=${project.name}")

        val authState = authStateProvider()
        if (!authState.isAuthenticated()) {
            throw AcpPreparationException("Log in to Code4Me before preparing an ACP agent session.")
        }

        val projectTokenService = projectTokenServiceProvider(project)
        ensureProjectActivation(project, authState, projectTokenService)

        val projectToken =
            projectTokenService.getProjectToken()
                ?: throw AcpPreparationException("Code4Me could not activate this project for the ACP agent session.")
        LOG.debug("project token ${secretForTrace(projectToken)}")

        val projectId =
            try {
                UUID.fromString(projectToken)
            } catch (_: IllegalArgumentException) {
                throw AcpPreparationException("Code4Me could not determine the activated project identifier.")
            }

        val workspace = canonicalWorkspace(project)
        val backendUrl = backendUrlProvider().trim()
        if (backendUrl.isBlank()) {
            throw AcpPreparationException("Code4Me could not determine which backend should authorize the ACP agent.")
        }
        // The agent process may sit in a different network namespace than the IDE (e.g. a
        // container), in which case the URL it must call differs from the plugin's own.
        val runtimeBackendUrl = runtimeBackendUrlProvider().trim().ifBlank { backendUrl }
        LOG.debug("requesting ACP grant (backendUrl=$backendUrl runtimeBackendUrl=$runtimeBackendUrl workspace=$workspace)")

        val response =
            try {
                grantRequester(PrepareAcpGrant(projectId = projectId, workspace = workspace))
            } catch (error: ClientException) {
                LOG.warn("ACP grant request rejected with status ${error.statusCode}")
                when (error.statusCode) {
                    401 -> {
                        staleStateResetter()
                        authState.clearUserData()
                        projectTokenService.setActivated(false)
                        throw AcpPreparationException(
                            "Your Code4Me login is no longer valid. Log in again and prepare the ACP agent session again.",
                        )
                    }

                    404 -> {
                        staleStateResetter()
                        projectTokenService.clearProjectToken()
                        projectTokenService.setActivated(false)
                        throw AcpPreparationException(
                            "Code4Me could not activate this project for the ACP agent session. Try again.",
                        )
                    }

                    else ->
                        throw AcpPreparationException(
                            "Code4Me could not prepare the ACP agent session (${error.statusCode}).",
                        )
                }
            } catch (error: ServerException) {
                LOG.warn("ACP grant request failed server-side", error)
                throw AcpPreparationException("The Code4Me server could not prepare the ACP agent session. Try again.")
            }

        val expiresInSeconds = response.expiresInSeconds ?: DEFAULT_GRANT_TTL_SECONDS
        LOG.debug("grant received (${secretForTrace(response.grant)}, expiresInSeconds=$expiresInSeconds)")

        val handoffPath = handoffWriter.write(project, runtimeBackendUrl, response.grant)
        LOG.info("ACP runtime handoff written to $handoffPath")

        return PreparedAcpRuntimeHandoff(
            workspace = workspace,
            handoffPath = handoffPath,
            expiresInSeconds = expiresInSeconds,
        )
    }

    private fun ensureProjectActivation(
        project: Project,
        authState: AuthSettings,
        projectTokenService: ProjectTokenService,
    ) {
        try {
            sessionRefresher()
            projectActivator(project)
        } catch (error: ClientException) {
            if (error.statusCode == 401 || error.statusCode == 404) {
                LOG.warn("Project activation invalidated the stored login (status ${error.statusCode})")
                staleStateResetter()
                authState.clearUserData()
                projectTokenService.setActivated(false)
                throw AcpPreparationException(
                    "Your Code4Me login is no longer valid. Log in again and prepare the ACP agent session again.",
                )
            }
            throw error
        }
    }

    /**
     * The workspace string the grant is scoped to. It has to be canonical (symlinks resolved,
     * forward slashes) because the backend compares it verbatim against the path the agent
     * process reports, and the two sides may reach the same directory by different routes.
     */
    private fun canonicalWorkspace(project: Project): String {
        val basePath =
            project.basePath
                ?: throw AcpPreparationException("Code4Me could not determine a canonical workspace for this project.")
        return try {
            Path.of(basePath).toRealPath().toString().replace('\\', '/')
        } catch (e: Exception) {
            LOG.warn("Could not canonicalize project base path", e)
            throw AcpPreparationException("Code4Me could not determine a canonical workspace for this project.")
        }
    }
}

private const val DEFAULT_GRANT_TTL_SECONDS = 300

/**
 * Writes the grant + backend URL as an env file to every location an agent runtime might look in.
 *
 * All three targets are written because the agent's working directory is not known in advance:
 * it may be launched from the IDE project, from the user's home, or from a sibling server
 * checkout discovered by walking up the tree.
 */
class RuntimeCredentialHandoffWriter(
    private val handoffPathProvider: (Project) -> Path = { project ->
        val basePath =
            project.basePath
                ?: throw AcpPreparationException("Code4Me could not determine a canonical workspace for this project.")
        Path.of(basePath).resolve(".idea/code4me/acp-runtime.env")
    },
    private val sharedHandoffPath: Path = Paths.get(System.getProperty("user.home"), ".code4me", "acp-runtime.env"),
    private val agentHandoffPathProvider: (Project) -> Path? = { project ->
        project.basePath?.let { basePath ->
            // Walk up from the IntelliJ project looking for a server checkout marked by
            // .code4me/agent-config.json. The server may be the directory itself or a
            // conventionally-named sibling/child at any level above the open project.
            var dir = Path.of(basePath).toAbsolutePath().normalize()
            while (true) {
                if (Files.isRegularFile(dir.resolve(".code4me/agent-config.json"))) {
                    return@let dir.resolve(".code4me/acp-runtime.env")
                }
                if (Files.isRegularFile(dir.resolve("ml4se-agent-server/.code4me/agent-config.json"))) {
                    return@let dir.resolve("ml4se-agent-server/.code4me/acp-runtime.env")
                }
                val parent = dir.parent ?: break
                if (parent == dir) break
                dir = parent
            }
            null
        }
    },
) {
    private val LOG = thisLogger()

    fun write(
        project: Project,
        backendUrl: String,
        grant: String,
    ): Path {
        LOG.debug("writing runtime handoff for project=${project.name} backendUrl=$backendUrl grant=${secretForTrace(grant)}")
        val handoffPath = handoffPathProvider(project)
        writeAtomic(handoffPath, backendUrl, grant)
        writeAtomic(sharedHandoffPath, backendUrl, grant)
        val agentHandoffPath = agentHandoffPathProvider(project)
        if (agentHandoffPath != null) {
            writeAtomic(agentHandoffPath, backendUrl, grant)
        } else {
            LOG.debug("no sibling agent-server checkout detected — skipping its handoff copy")
        }
        return handoffPath
    }

    private fun writeAtomic(
        path: Path,
        backendUrl: String,
        grant: String,
    ) {
        Files.createDirectories(path.parent)
        val payload =
            buildString {
                append("CODE4ME_ACP_BACKEND_URL=")
                append(backendUrl)
                append('\n')
                append("CODE4ME_ACP_GRANT=")
                append(grant)
                append('\n')
            }
        // Write-then-rename so an agent reading the file concurrently never sees a partial grant.
        val tempFile = Files.createTempFile(path.parent, "acp-runtime", ".tmp")
        try {
            Files.writeString(tempFile, payload, StandardCharsets.UTF_8)
            try {
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING)
            }
            restrictOwnerOnly(path)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    // The file holds a live credential, so keep it unreadable by other local users.
    private fun restrictOwnerOnly(path: Path) {
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (Windows): fall back to the coarse File API equivalent.
            path.toFile().setReadable(false, false)
            path.toFile().setWritable(false, false)
            path.toFile().setExecutable(false, false)
            path.toFile().setReadable(true, true)
            path.toFile().setWritable(true, true)
        } catch (e: Exception) {
            LOG.warn("Could not restrict permissions on $path", e)
        }
    }
}
