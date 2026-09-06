package me.code4me.services.agent

import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.code4me.api.wrapper.CookieAwareApiClient
import me.code4me.services.app.getAppService
import me.code4me.services.state.getPrefState
import me.code4me.utils.notification.showAuthNotification
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the third-party-agent startup path: mint an `AgentTask` server-side, locate the
 * Goose binary, bootstrap the vendored codex-acp proxy, and register both in
 * `~/.jetbrains/acp.json` so the native AI Assistant can launch them.
 *
 * Every failure here is non-fatal: the plugin's completion and chat features must keep working
 * whether or not any agent runtime is installed.
 */
object AgentStartupManager {
    private val LOG = thisLogger()

    suspend fun ensureActiveTask(project: Project) {
        LOG.info("[AgentStartupManager] ensureActiveTask — begin")

        val sessionId = CookieAwareApiClient.getSessionToken()
        if (sessionId.isNullOrBlank()) {
            LOG.warn("[AgentStartupManager] no session token available — skipping acp.json write")
            return
        }
        LOG.info("[AgentStartupManager] session token present")

        if (provisionTask() == null) {
            LOG.warn("[AgentStartupManager] task provisioning failed — skipping acp.json write")
            return
        }

        LOG.info("[AgentStartupManager] detecting Goose binary…")
        val goosePath = GooseRuntime.detect()
        if (goosePath == null) {
            showGooseNotFoundNotification(project)
            return
        }
        LOG.info("[AgentStartupManager] Goose binary resolved: $goosePath")

        val localProxyBaseUrl = LocalProxyServer.baseUrl()
        val envBundle = GooseRuntime.buildEnvBundle(localProxyBaseUrl)
        LOG.info("[AgentStartupManager] proxy=$localProxyBaseUrl  env bundle keys=${envBundle.keys}")

        // Resolve and prepare the vendored codex-acp proxy. We only register the Codex
        // entry if its npm dependencies are installed (auto-installing them on first run),
        // so we never hand the ACP runner a source dir that would crash on launch.
        val rawCodexDir = detectCodexProxySourceDir()
        val codexSourceDir =
            if (rawCodexDir != null && withContext(Dispatchers.IO) { ensureCodexInstalled(rawCodexDir) }) rawCodexDir else null
        LOG.info("[AgentStartupManager] codex proxy source: ${codexSourceDir ?: "not available — skipping Codex entry"}")

        AcpManager.writeOrUpdate(goosePath, envBundle, localProxyBaseUrl, codexSourceDir)
        AgentContextProvider().writeContext(project)

        LOG.info("[AgentStartupManager] setup complete — proxy=$localProxyBaseUrl")
    }

    /**
     * Closes the current agent task and provisions a fresh one, returning its id.
     *
     * Intended for use after a trajectory/telemetry upload completes, so subsequent inference
     * calls are attributed to a new session. `acp.json` deliberately does *not* need rewriting:
     * the task id is injected per-request by [LocalProxyServer] from `PrefSettings.pendingTaskId`
     * rather than baked into the registered agent's environment, so a rotation is visible to an
     * already-running agent process immediately.
     */
    suspend fun rotateTask(): UUID? {
        LOG.info("[AgentStartupManager] rotateTask — begin")
        return provisionTask().also {
            LOG.info("[AgentStartupManager] rotateTask — ${if (it != null) "new task $it" else "failed"}")
        }
    }

    /**
     * Closes any previously pending task and pre-mints a new one on the server.
     *
     * The mint is synchronous because the server enforces session-ownership on every
     * `/api/agent/inference` call, so the row must exist before an agent issues its first LLM
     * request through the local proxy. A fresh UUID is always generated rather than reusing a
     * stored one: the old row belongs to the previous session and would be rejected with a 403.
     *
     * Returns the new task id, or null if the server rejected the mint.
     */
    private suspend fun provisionTask(): UUID? {
        val prefs = getPrefState()

        // Best-effort close of the previous task. It may have accrued inference events during
        // the last session; /close aggregates the token counts and marks it done. A 404 (no
        // prior task) is swallowed by AppService.closeAgentTask.
        prefs.pendingTaskId?.takeIf { it.isNotBlank() }?.let { previousTaskId ->
            try {
                getAppService().closeAgentTask(UUID.fromString(previousTaskId))
                LOG.info("[AgentStartupManager] closed previous task $previousTaskId")
            } catch (e: Exception) {
                LOG.warn("[AgentStartupManager] failed to close previous task $previousTaskId — continuing", e)
            }
        }

        val taskUuid = UUID.randomUUID()
        val profile = prefs.selectedAgentProfile?.takeIf { it.isNotBlank() } ?: "default"
        LOG.info("[AgentStartupManager] pre-minting AgentTask on server (taskId=$taskUuid, profile=$profile)")
        return try {
            getAppService().createAgentTask(taskUuid, profile)
            prefs.pendingTaskId = taskUuid.toString()
            LOG.info("[AgentStartupManager] AgentTask pre-mint OK (taskId=$taskUuid)")
            taskUuid
        } catch (e: Exception) {
            LOG.warn("[AgentStartupManager] failed to pre-mint AgentTask", e)
            null
        }
    }

    // Locates the vendored codex-acp source directory. It lives in THIS plugin's repo, not
    // the user's open project, so we can't use project.basePath. `./gradlew runIde` passes
    // the repo path via the code4me.codexProxyDir system property; CODE4ME_CODEX_PROXY_DIR
    // is an env-var override for other setups. Returns the absolute path if package.json is
    // present, or null (Codex entry skipped) otherwise.
    //
    // NOTE: running codex-acp from vendored source like this is a DEVELOPMENT-only setup.
    // For a real production release we would instead build a self-contained codex-acp binary
    // (e.g. the `bundle:*` scripts in codex-acp/package.json) and ship it inside the plugin
    // distribution zip, then resolve it from the plugin install path — no Node/npm or source
    // tree required on the user's machine.
    private fun detectCodexProxySourceDir(): String? {
        val candidates =
            listOfNotNull(
                System.getProperty("code4me.codexProxyDir"),
                System.getenv("CODE4ME_CODEX_PROXY_DIR"),
            )
        for (path in candidates) {
            if (path.isBlank()) continue
            val dir = File(path)
            if (dir.resolve("package.json").exists()) return dir.absolutePath
        }
        LOG.info(
            "[AgentStartupManager] codex proxy dir not found — set code4me.codexProxyDir " +
                "(runIde does this) or CODE4ME_CODEX_PROXY_DIR. Checked: $candidates",
        )
        return null
    }

    // Ensures the vendored codex-acp has its npm dependencies installed. Returns true if
    // node_modules is present — either already, or after a successful install.
    private fun ensureCodexInstalled(sourceDir: String): Boolean {
        val dir = File(sourceDir)
        if (dir.resolve("node_modules").isDirectory) {
            LOG.info("[AgentStartupManager] codex node_modules present — skipping npm install")
            return true
        }

        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val npm = if (isWindows) "npm.cmd" else "npm"
        // Prefer a reproducible install from the committed lockfile when available.
        val args = if (dir.resolve("package-lock.json").exists()) listOf("ci") else listOf("install")
        LOG.info(
            "[AgentStartupManager] codex node_modules missing — running '$npm ${args.joinToString(
                " ",
            )}' in $sourceDir (first run only, may take a few minutes)",
        )

        return try {
            val process =
                ProcessBuilder(listOf(npm) + args)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()
            val drain =
                Thread { process.inputStream.bufferedReader().forEachLine { LOG.info("[npm] $it") } }
                    .apply {
                        isDaemon = true
                        start()
                    }
            val finished = process.waitFor(10, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                LOG.warn("[AgentStartupManager] npm install timed out after 10 min — skipping Codex entry")
                return false
            }
            drain.join(2_000)
            val ok = process.exitValue() == 0 && dir.resolve("node_modules").isDirectory
            if (ok) {
                LOG.info("[AgentStartupManager] codex npm install completed successfully")
            } else {
                LOG.warn("[AgentStartupManager] codex npm install failed (exit=${process.exitValue()}) — skipping Codex entry")
            }
            ok
        } catch (e: Exception) {
            LOG.warn("[AgentStartupManager] codex npm install could not be started (is npm on PATH?) — skipping Codex entry", e)
            false
        }
    }

    private fun showGooseNotFoundNotification(project: Project) {
        LOG.warn("[AgentStartupManager] Goose binary not found — acp.json not written")
        project.showAuthNotification(
            title = "Code4Me agent runtime not found",
            message =
                "No Goose binary was found on PATH or in the JetBrains ACP agent registry, so no " +
                    "Code4Me agent was registered with AI Assistant. Install Goose (or an agent " +
                    "from the AI Assistant agent list) and reopen the project to retry.",
            type = NotificationType.WARNING,
            includeSettingsAction = false,
        )
    }
}
