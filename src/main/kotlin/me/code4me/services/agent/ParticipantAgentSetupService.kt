package me.code4me.services.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.ide.plugins.PluginManagerCore
import me.code4me.services.project.getProjectTokenService
import me.code4me.services.state.getAuthState
import me.code4me.utils.api.activateOrCreateProject
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

enum class ParticipantSetupStep { SIGN_IN, CHECK_SERVER, PREPARE_AGENT, READY }

data class ParticipantSetupStatus(
    val step: ParticipantSetupStep,
    val message: String,
    val canRepair: Boolean = false,
)

fun getParticipantAgentSetupService(): ParticipantAgentSetupService = service()

/** Application-scoped participant setup. Third-party developer runtimes remain opt-in. */
@Service
class ParticipantAgentSetupService : Disposable {
    private val log = thisLogger()
    private val bridgeDirectory = Path.of(PathManager.getSystemPath(), "code4me", "bridges")
    private val installer = ManagedRuntimeInstaller()
    private val bridge = ManagedAuthBridge(bridgeDirectory)
    @Volatile private var runtimeResult: RuntimeInstallResult? = null
    @Volatile private var lastStatus: ParticipantSetupStatus? = null
    private val projects = java.util.concurrent.ConcurrentHashMap.newKeySet<Project>()

    @Synchronized
    fun prepare(project: Project, repair: Boolean = false): ParticipantSetupStatus {
        if (!getAuthState().isAuthenticated()) {
            bridge.unregister(project)
            return remember(ParticipantSetupStatus(ParticipantSetupStep.SIGN_IN, "Sign in to Code4Me to prepare the agent."))
        }
        val aiAssistantId = PluginId.getId(AI_ASSISTANT_PLUGIN_ID)
        if (
            PluginManagerCore.getPlugin(aiAssistantId) == null ||
            PluginManagerCore.isDisabled(aiAssistantId)
        ) {
            bridge.unregister(project)
            return remember(
                ParticipantSetupStatus(
                    ParticipantSetupStep.PREPARE_AGENT,
                    "JetBrains AI Assistant is not installed. Install it from Settings > Plugins, restart the IDE, then choose Prepare agent.",
                ),
            )
        }
        val projectToken = getProjectTokenService(project)
        if (!projectToken.isActivated() || !projectToken.hasProjectToken()) {
            try {
                activateOrCreateProject(project, log)
            } catch (e: Exception) {
                log.warn("Managed project activation is not ready", e)
                bridge.unregister(project)
                return remember(
                    ParticipantSetupStatus(
                        ParticipantSetupStep.CHECK_SERVER,
                        "Code4Me is signed in, but this project could not be activated yet. " +
                            "Wait a moment and choose Prepare agent again.",
                    ),
                )
            }
        }
        if (!projectToken.isActivated() || !projectToken.hasProjectToken()) {
            bridge.unregister(project)
            return remember(
                ParticipantSetupStatus(
                    ParticipantSetupStep.CHECK_SERVER,
                    "Code4Me could not activate this project. Choose Prepare agent to retry.",
                ),
            )
        }
        val serverCheck = try {
            me.code4me.services.app.getAppService().checkManagedCapabilities()
        } catch (e: Exception) {
            log.warn("Managed server check failed", e)
            return remember(ParticipantSetupStatus(
                ParticipantSetupStep.CHECK_SERVER,
                "Study server is unavailable: ${e.message}. Check network and server selection, then retry.",
            ))
        }
        if (serverCheck.isFailure) {
            return remember(ParticipantSetupStatus(
                ParticipantSetupStep.CHECK_SERVER,
                serverCheck.exceptionOrNull()?.message
                    ?: "Study server is unavailable. Check network and server selection.",
            ))
        }
        val result = installer.ensureInstalled(repair)
        runtimeResult = result
        val status = when (result) {
            is RuntimeInstallResult.Ready -> {
                val selfCheckFailure = runtimeSelfCheck(result.executable)
                if (selfCheckFailure != null) {
                    return remember(
                        ParticipantSetupStatus(
                            ParticipantSetupStep.PREPARE_AGENT,
                            "$selfCheckFailure Use Repair agent; if it still fails, reinstall the plugin.",
                            canRepair = true,
                        ),
                    )
                }
                val registration = runCatching {
                    AcpManager.registerManagedAgent(result.executable.toString(), bridgeDirectory.toString()).getOrThrow()
                    // Do not issue grants until both the runtime and the ACP registry are ready.
                    bridge.register(project)
                    projects += project
                }
                if (registration.isFailure) ParticipantSetupStatus(
                    ParticipantSetupStep.PREPARE_AGENT,
                    "Code4Me could not register the managed agent or start its authentication bridge: " +
                        (registration.exceptionOrNull()?.message ?: "unknown error") +
                        ". Check ~/.jetbrains/acp.json, then use Repair agent. Unrelated entries were left unchanged.",
                    canRepair = true,
                ) else {
                    ParticipantSetupStatus(
                        ParticipantSetupStep.READY,
                        "Code4Me Agent is ready (runtime ${result.artifact.version}, protocol ${result.artifact.managedProtocol}). " +
                            "Select it in JetBrains AI Chat. If it does not appear, install AI Assistant from Settings > Plugins and restart.",
                    )
                }
            }
            is RuntimeInstallResult.Unavailable -> ParticipantSetupStatus(
                ParticipantSetupStep.PREPARE_AGENT,
                "${result.message} This build does not support your OS/architecture.",
            )
            is RuntimeInstallResult.Failed -> ParticipantSetupStatus(
                ParticipantSetupStep.PREPARE_AGENT,
                "${result.message} The bundled runtime is corrupted or incomplete.",
                canRepair = true,
            )
        }
        return remember(status)
    }

    fun currentStatus(): ParticipantSetupStatus {
        if (!getAuthState().isAuthenticated()) return ParticipantSetupStatus(
            ParticipantSetupStep.SIGN_IN, "Sign in to Code4Me to prepare the agent.",
        )
        lastStatus?.let { return it }
        return when (val result = runtimeResult) {
            is RuntimeInstallResult.Ready -> ParticipantSetupStatus(ParticipantSetupStep.READY, "Code4Me Agent is ready.")
            is RuntimeInstallResult.Failed -> ParticipantSetupStatus(ParticipantSetupStep.PREPARE_AGENT, result.message, true)
            is RuntimeInstallResult.Unavailable -> ParticipantSetupStatus(ParticipantSetupStep.PREPARE_AGENT, result.message)
            null -> ParticipantSetupStatus(ParticipantSetupStep.CHECK_SERVER, "Code4Me is signed in. Prepare the agent to continue.")
        }
    }

    fun unregister(project: Project) {
        projects -= project
        bridge.unregister(project)
        if (projects.isEmpty()) {
            // A Ready result belongs to an open, authorized project. Do not
            // retain it after the final project closes and mislead the next
            // project opened in the same IDE process.
            lastStatus = null
        }
    }

    fun onServerChanged() {
        projects.toList().forEach { bridge.unregister(it) }
        projects.clear()
        runtimeResult = null
        lastStatus = null
    }

    fun onLogout() {
        projects.toList().forEach { bridge.unregister(it) }
        projects.clear()
        runtimeResult = null
        lastStatus = null
    }

    /** TODO: managed Goose/Codex distribution, onboarding, policy enforcement and certification. */
    fun prepareDeveloperAgents(project: Project) {
        if (!java.lang.Boolean.getBoolean("code4me.developerAgents")) return
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                LocalProxyServer.start(project)
                kotlinx.coroutines.runBlocking { AgentStartupManager.ensureActiveTask(project) }
            }.onFailure { log.warn("Developer agent setup failed", it) }
        }
    }

    override fun dispose() {
        bridge.close()
        projects.clear()
    }

    private fun remember(status: ParticipantSetupStatus): ParticipantSetupStatus {
        lastStatus = status
        return status
    }

    private fun runtimeSelfCheck(executable: Path): String? = try {
        val process = ProcessBuilder(executable.toString(), "--self-check")
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(SELF_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "The bundled Code4Me runtime self-check timed out."
        } else if (process.exitValue() != 0) {
            val detail = process.inputStream.readNBytes(MAX_SELF_CHECK_OUTPUT_BYTES)
                .toString(StandardCharsets.UTF_8)
                .trim()
                .take(500)
            "The bundled Code4Me runtime self-check failed" +
                if (detail.isBlank()) "." else ": $detail"
        } else {
            null
        }
    } catch (e: Exception) {
        log.warn("Managed runtime self-check could not start", e)
        "The bundled Code4Me runtime could not start (${e.message ?: "unknown error"})."
    }

    private companion object {
        const val AI_ASSISTANT_PLUGIN_ID = "com.intellij.ml.llm"
        const val SELF_CHECK_TIMEOUT_SECONDS = 20L
        const val MAX_SELF_CHECK_OUTPUT_BYTES = 8 * 1024
    }
}
