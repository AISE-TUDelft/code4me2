package me.code4me.services.agent

import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Goose binary, bootstrap the vendored codex-acp proxy, and register whichever of the two are
 * available in `~/.jetbrains/acp.json` so the native AI Assistant can launch them. Goose and Codex
 * are detected and registered independently — one being missing never prevents the other from
 * being registered.
 *
 * Every failure here is non-fatal: the plugin's completion and chat features must keep working
 * whether or not any agent runtime is installed.
 */
object AgentStartupManager {
    private val LOG = thisLogger()

    // A fresh login fires this via a property-change listener (see
    // PluginStartupActivity.registerPostAuthAgentSetup) the instant the auth token is stored —
    // which is *before* the caller acquires a session a few lines later. The session_token cookie
    // CookieAwareApiClient.getSessionToken() reads is only set once that HTTP round-trip completes,
    // so a single immediate check reliably loses the race. Poll briefly instead of bailing outright.
    private const val SESSION_TOKEN_POLL_ATTEMPTS = 10
    private const val SESSION_TOKEN_POLL_INTERVAL_MS = 300L

    suspend fun ensureActiveTask(project: Project) {
        LOG.info("[AgentStartupManager] ensureActiveTask — begin")

        val sessionId = awaitSessionToken()
        if (sessionId.isNullOrBlank()) {
            LOG.warn("[AgentStartupManager] no session token available after waiting — skipping acp.json write")
            return
        }
        LOG.info("[AgentStartupManager] session token present")

        val task = provisionTask()
        if (task == null) {
            LOG.warn("[AgentStartupManager] task provisioning failed — skipping acp.json write")
            return
        }

        if (task.frameworkVersion != "goose") {
            LOG.info(
                "[AgentStartupManager] assigned runtime=${task.frameworkVersion ?: "unknown"}; " +
                    "Goose entry will not be registered",
            )
            AcpManager.removeDeveloperGooseEntry()
            return
        }

        LOG.info("[AgentStartupManager] detecting Goose binary…")
        val goosePath = GooseRuntime.detect()
        if (goosePath == null) {
            // Goose is one optional runtime among several (Codex is the other) — its absence
            // must not block registering whichever runtimes *are* available.
            showGooseNotFoundNotification(project)
        } else {
            LOG.info("[AgentStartupManager] Goose binary resolved: $goosePath")
        }

        val localProxyBaseUrl = LocalProxyServer.baseUrl()
        val envBundle = GooseRuntime.buildEnvBundle(localProxyBaseUrl, task.model)
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
        val task = provisionTask()
        LOG.info("[AgentStartupManager] rotateTask — ${task?.taskId ?: "failed"}")
        return task?.taskId
    }

    /**
     * Polls for [CookieAwareApiClient.getSessionToken] for up to
     * `SESSION_TOKEN_POLL_ATTEMPTS * SESSION_TOKEN_POLL_INTERVAL_MS` (~3s), instead of a single
     * immediate check that loses the startup race described above. Returns null if the cookie
     * still isn't there once the window elapses.
     */
    private suspend fun awaitSessionToken(): String? {
        repeat(SESSION_TOKEN_POLL_ATTEMPTS) { attempt ->
            val token = CookieAwareApiClient.getSessionToken()
            if (!token.isNullOrBlank()) {
                if (attempt > 0) LOG.info("[AgentStartupManager] session token appeared after ${attempt + 1} check(s)")
                return token
            }
            if (attempt < SESSION_TOKEN_POLL_ATTEMPTS - 1) delay(SESSION_TOKEN_POLL_INTERVAL_MS)
        }
        return null
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
    private suspend fun provisionTask(): AgentTaskInfo? {
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
            val task = getAppService().createAgentTask(taskUuid, profile)
            prefs.pendingTaskId = task.taskId.toString()
            LOG.info(
                "[AgentStartupManager] AgentTask pre-mint OK (taskId=${task.taskId}, " +
                    "runtime=${task.frameworkVersion ?: "unknown"})",
            )
            task
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

    // Ensures the vendored codex-acp has its npm dependencies installed *and* that the native
    // Codex CLI binary the proxy shells out to (@openai/codex's platform package) is actually
    // present on disk. That binary is a frequent target of a known macOS XProtect false positive
    // (openai/codex#31377): Gatekeeper/XProtect deletes the vendored Mach-O as "malware" some time
    // after npm placed it there, while leaving `node_modules/` itself intact — so a node_modules
    // existence check alone would keep reporting Codex as ready after it's already been eaten.
    // Returns true only if the binary that would actually be spawned is present.
    private fun ensureCodexInstalled(sourceDir: String): Boolean {
        val dir = File(sourceDir)
        if (dir.resolve("node_modules").isDirectory) {
            if (codexNativeBinaryPresent(dir)) {
                LOG.info("[AgentStartupManager] codex node_modules present — skipping npm install")
                return true
            }
            LOG.warn(
                "[AgentStartupManager] codex node_modules present but the native Codex binary is " +
                    "missing — likely removed by macOS Gatekeeper/XProtect as a known false positive " +
                    "(see https://github.com/openai/codex/issues/31377). Skipping Codex entry; " +
                    "reinstalling won't help until @openai/codex is updated past the flagged build.",
            )
            return false
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
            val installed = process.exitValue() == 0 && dir.resolve("node_modules").isDirectory
            val ok = installed && codexNativeBinaryPresent(dir)
            when {
                ok -> LOG.info("[AgentStartupManager] codex npm install completed successfully")
                installed ->
                    LOG.warn(
                        "[AgentStartupManager] codex npm install succeeded but the native Codex binary is " +
                            "missing right after install — likely removed by macOS Gatekeeper/XProtect as a " +
                            "known false positive (see https://github.com/openai/codex/issues/31377). Skipping Codex entry.",
                    )
                else -> LOG.warn("[AgentStartupManager] codex npm install failed (exit=${process.exitValue()}) — skipping Codex entry")
            }
            ok
        } catch (e: Exception) {
            LOG.warn("[AgentStartupManager] codex npm install could not be started (is npm on PATH?) — skipping Codex entry", e)
            false
        }
    }

    // Checks that the native Codex CLI binary @openai/codex's launcher (bin/codex.js) will
    // actually spawn is present on disk, at node_modules/@openai/codex-<platform>/vendor/<target
    // triple>/bin/codex(.exe) — see findCodexExecutable() in that launcher. We match by the
    // codex-* platform-package naming pattern and search all target-triple subdirs rather than
    // hardcoding the current OS/arch's triple, since npm only ever installs the one optional
    // platform dependency matching the running machine, and the exact vendor layout has changed
    // across @openai/codex releases (older builds used vendor/<triple>/codex/codex instead).
    private fun codexNativeBinaryPresent(sourceDir: File): Boolean {
        val platformPackagesDir = sourceDir.resolve("node_modules/@openai")
        val platformDirs = platformPackagesDir.listFiles { f -> f.isDirectory && f.name.startsWith("codex-") } ?: return false
        return platformDirs.any { platformDir ->
            val vendorDir = platformDir.resolve("vendor")
            vendorDir.listFiles { f -> f.isDirectory }?.any { targetTripleDir ->
                val binDir = targetTripleDir.resolve("bin")
                binDir.resolve("codex").let { it.isFile && it.length() > 0 } ||
                    binDir.resolve("codex.exe").let { it.isFile && it.length() > 0 }
            } ?: false
        }
    }

    private fun showGooseNotFoundNotification(project: Project) {
        LOG.warn("[AgentStartupManager] Goose binary not found — Goose entry not written (other runtimes, e.g. Codex, are unaffected)")
        project.showAuthNotification(
            title = "Code4Me: Goose runtime not found",
            message =
                "No Goose binary was found on PATH or in the JetBrains ACP agent registry, so no " +
                    "Goose agent was registered with AI Assistant. Other Code4Me-managed runtimes " +
                    "(e.g. Codex) are unaffected. Install Goose and reopen the project to retry.",
            type = NotificationType.WARNING,
            includeSettingsAction = false,
        )
    }
}
