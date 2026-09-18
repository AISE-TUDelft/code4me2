package me.code4me.research.lifecycle

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.code4me.research.session.ResearchSessionService

/**
 * Discovers the account's server-side research membership on project open.
 *
 * The membership authority is the server (`GET /api/research/participants/me`),
 * never a project-local enrollment id. The stored id is passed to the service as
 * a hint only: an active enrollment from the server is adopted and persisted, a
 * terminal (withdrawn/completed) enrollment clears the hint and blocks, and "no
 * enrollment" clears any stale hint and leaves the component inactive without an
 * error. Strictly best-effort: any failure is swallowed and never blocks ordinary
 * Code4Me startup.
 */
class ResearchActivationStartupActivity : ProjectActivity {
    private val log = thisLogger()

    override suspend fun execute(project: Project) {
        withContext(Dispatchers.IO) {
            try {
                ResearchSessionService.getInstance(project).reactivateFromServer()
            } catch (error: Exception) {
                log.warn("Research enrollment discovery failed — non-blocking", error)
            }
        }
    }
}

/**
 * Logout/account-switch hook (Issue 03 F13; folded from ResearchLogoutHook.kt).
 *
 * On sign-out every open project's research context is stopped (managers,
 * collectors, uploaders) and its spool is quarantined, so no queued record can be
 * uploaded under the next account. It only touches contexts that were already
 * created, never constructs one, and swallows every failure so ordinary login,
 * logout and chat are unaffected.
 */
object ResearchLogoutHook {
    private val log = thisLogger()

    fun stopAllContexts() {
        try {
            for (project in ProjectManager.getInstance().openProjects) {
                val service = project.getServiceIfCreated(ResearchSessionService::class.java) ?: continue
                runCatching { service.stop() }.onFailure {
                    log.warn("Stopping a research context on sign-out failed", it)
                }
                runCatching { service.quarantine() }.onFailure {
                    log.warn("Quarantining a research spool on sign-out failed", it)
                }
            }
        } catch (error: Exception) {
            log.warn("Research sign-out cleanup failed", error)
        }
    }
}
