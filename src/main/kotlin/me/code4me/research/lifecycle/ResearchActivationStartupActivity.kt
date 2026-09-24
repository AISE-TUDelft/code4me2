package me.code4me.research.lifecycle

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import me.code4me.lifecycle.getAcpLoginReconciliationService
import me.code4me.research.session.ResearchSessionService

/** Starts the shared project reconciler; the second startup hook is intentionally idempotent. */
class ResearchActivationStartupActivity : ProjectActivity {
    private val log = thisLogger()

    override suspend fun execute(project: Project) {
        try {
            getAcpLoginReconciliationService(project).start()
        } catch (error: Exception) {
            log.warn("ACP login reconciliation could not start — non-blocking", error)
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
                runCatching { service.onLogout() }.onFailure {
                    log.warn("Stopping a research context on sign-out failed", it)
                }
            }
        } catch (error: Exception) {
            log.warn("Research sign-out cleanup failed", error)
        }
    }
}
