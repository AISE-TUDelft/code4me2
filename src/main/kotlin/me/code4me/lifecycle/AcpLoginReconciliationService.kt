package me.code4me.lifecycle

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.code4me.research.session.ResearchActivationResult
import me.code4me.research.session.ResearchReconciliationResult
import me.code4me.research.session.ResearchSessionService
import me.code4me.services.agent.ParticipantSetupStep
import me.code4me.services.agent.getParticipantAgentSetupService
import me.code4me.services.agent.mayPrepareDeveloperAgents
import me.code4me.services.app.getAppService
import me.code4me.services.project.getProjectTokenService
import me.code4me.services.state.TOKEN_PROPERTY
import me.code4me.services.state.getAuthState
import me.code4me.utils.api.activateOrCreateProject
import me.code4me.utils.notification.AcpPreparationLease
import me.code4me.utils.notification.getAcpPreparationProgress
import java.beans.PropertyChangeListener

fun getAcpLoginReconciliationService(project: Project): AcpLoginReconciliationService = project.service()

internal enum class ReconciliationAttemptResult {
    COMPLETE,
    RETRY,
}

internal fun interface ReconciliationTask {
    fun cancel()
}

internal interface ReconciliationScheduler {
    fun execute(task: () -> Unit)

    fun schedule(delayMs: Long, task: () -> Unit): ReconciliationTask

    fun dispose()
}

private class CoroutineReconciliationScheduler : ReconciliationScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun execute(task: () -> Unit) {
        scope.launch { task() }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): ReconciliationTask {
        val job: Job = scope.launch {
            delay(delayMs)
            task()
        }
        return ReconciliationTask { job.cancel() }
    }

    override fun dispose() {
        scope.cancel()
    }
}

/**
 * Serializes login reconciliation for one project.
 *
 * A generation changes on every login/logout. Work that finishes after its
 * generation became stale is cleaned up before a newer generation may run.
 * Duplicate triggers are folded into one follow-up attempt, and successful
 * generations remain quiet until authentication changes.
 */
internal class AcpLoginReconciliationController(
    private val scheduler: ReconciliationScheduler,
    private val attempt: ((() -> Boolean) -> ReconciliationAttemptResult),
    private val cleanup: (quarantine: Boolean) -> Unit,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    private val onPreparingChanged: (Boolean) -> Unit = {},
) {
    private val lock = Any()
    private var disposed = false
    private var started = false
    private var authenticated = false
    private var generation = 0L
    private var completedGeneration = -1L
    private var running = false
    private var rerunRequested = false
    private var cleanupVersion = 0L
    private var cleanupQuarantine = false
    private var retryIndex = 0
    private var retryTask: ReconciliationTask? = null

    fun start(initiallyAuthenticated: Boolean) {
        val runGeneration = synchronized(lock) {
            if (disposed || started) return
            started = true
            authenticated = initiallyAuthenticated
            generation++
            if (authenticated) reportPreparing(true)
            if (authenticated) generation else null
        }
        runGeneration?.let(::requestRun)
    }

    fun authenticationChanged(isAuthenticated: Boolean) {
        var scheduleCleanup = false
        val runGeneration: Long?
        synchronized(lock) {
            if (disposed) return
            if (!started) started = true
            val wasAuthenticated = authenticated
            generation++
            authenticated = isAuthenticated
            reportPreparing(false)
            if (authenticated) reportPreparing(true)
            completedGeneration = -1L
            retryIndex = 0
            retryTask?.cancel()
            retryTask = null
            rerunRequested = isAuthenticated && running
            if (!isAuthenticated || wasAuthenticated) {
                cleanupVersion++
                cleanupQuarantine = true
                if (!running) {
                    running = true
                    scheduleCleanup = true
                }
            }
            runGeneration = if (isAuthenticated && !scheduleCleanup) generation else null
        }
        if (scheduleCleanup) scheduler.execute(::runCleanupLoop)
        runGeneration?.let(::requestRun)
    }

    /** Session readiness or an explicit retry signal. */
    fun trigger() {
        val currentGeneration = synchronized(lock) {
            if (disposed || !started || !authenticated || completedGeneration == generation) return
            generation
        }
        requestRun(currentGeneration)
    }

    fun dispose() {
        var quarantine = false
        val cleanupNow = synchronized(lock) {
            if (disposed) return
            disposed = true
            generation++
            authenticated = false
            reportPreparing(false)
            cleanupVersion++
            retryTask?.cancel()
            retryTask = null
            rerunRequested = false
            // Disposal is not a logout: only an auth change opts cleanup into
            // quarantining the spool. A plain project close must keep unuploaded
            // events adoptable by a later session.
            quarantine = cleanupQuarantine
            !running
        }
        if (cleanupNow) {
            safeCleanup(quarantine)
            scheduler.dispose()
        }
    }

    private fun requestRun(requestedGeneration: Long) {
        synchronized(lock) {
            if (
                disposed || !authenticated || requestedGeneration != generation ||
                completedGeneration == generation
            ) {
                return
            }
            retryTask?.cancel()
            retryTask = null
            if (running) {
                rerunRequested = true
                return
            }
            running = true
            scheduler.execute { runAttempt(requestedGeneration) }
        }
    }

    private fun runAttempt(attemptGeneration: Long) {
        val result =
            try {
                attempt { isCurrent(attemptGeneration) }
            } catch (_: Exception) {
                ReconciliationAttemptResult.RETRY
            } catch (_: LinkageError) {
                ReconciliationAttemptResult.RETRY
            } catch (_: Throwable) {
                // A thrown Error (e.g. AssertionError from an injected test runtime)
                // must still re-arm the retry instead of stranding the reconciler.
                ReconciliationAttemptResult.RETRY
            }

        var stale = false
        var runImmediately = false
        var retryDelay: Long? = null
        synchronized(lock) {
            if (!isCurrentLocked(attemptGeneration)) {
                stale = true
            } else if (result == ReconciliationAttemptResult.COMPLETE) {
                running = false
                completedGeneration = attemptGeneration
                rerunRequested = false
                retryIndex = 0
                reportPreparing(false)
            } else if (rerunRequested) {
                running = false
                rerunRequested = false
                runImmediately = true
            } else {
                running = false
                retryDelay = retryDelaysMs[retryIndex.coerceAtMost(retryDelaysMs.lastIndex)]
                retryIndex = (retryIndex + 1).coerceAtMost(retryDelaysMs.lastIndex)
            }
        }

        if (stale) {
            runCleanupLoop()
        } else if (runImmediately) {
            requestRun(attemptGeneration)
        } else {
            retryDelay?.let { scheduleRetry(attemptGeneration, it) }
        }
    }

    /** Runs transition cleanup on the serial worker before any newer attempt. */
    private fun runCleanupLoop() {
        while (true) {
            // Each invocation uses the latest quarantine intent: an auth-change
            // cleanup quarantines, while the final dispose pass inherits the
            // already-reset flag so a plain project close never quarantines.
            val (version, quarantine) = synchronized(lock) { cleanupVersion to cleanupQuarantine }
            safeCleanup(quarantine)
            var runGeneration: Long? = null
            var disposeScheduler = false
            val repeatCleanup = synchronized(lock) {
                if (cleanupVersion != version) {
                    true
                } else {
                    running = false
                    rerunRequested = false
                    cleanupQuarantine = false
                    if (disposed) {
                        disposeScheduler = true
                    } else if (authenticated) {
                        runGeneration = generation
                    }
                    false
                }
            }
            if (repeatCleanup) continue
            if (disposeScheduler) scheduler.dispose() else runGeneration?.let(::requestRun)
            return
        }
    }

    private fun safeCleanup(quarantine: Boolean) {
        try {
            cleanup(quarantine)
        } catch (_: Exception) {
            // Cleanup is best-effort, but controller serialization must always advance.
        } catch (_: LinkageError) {
            // A partially unloaded plugin must not strand the project controller.
        }
    }

    private fun scheduleRetry(attemptGeneration: Long, delayMs: Long) {
        synchronized(lock) {
            if (!isCurrentLocked(attemptGeneration) || completedGeneration == generation || running) return
            retryTask = scheduler.schedule(delayMs) {
                synchronized(lock) { retryTask = null }
                requestRun(attemptGeneration)
            }
        }
    }

    private fun isCurrent(attemptGeneration: Long): Boolean =
        synchronized(lock) { isCurrentLocked(attemptGeneration) }

    private fun isCurrentLocked(attemptGeneration: Long): Boolean =
        !disposed && authenticated && attemptGeneration == generation

    private fun reportPreparing(preparing: Boolean) {
        runCatching { onPreparingChanged(preparing) }
    }

    companion object {
        internal val DEFAULT_RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 300_000L)
    }
}

/** Persistent, project-scoped owner for post-login research/direct ACP setup. */
@Service(Service.Level.PROJECT)
class AcpLoginReconciliationService(private val project: Project) : Disposable {
    private val log = thisLogger()
    private val authState = getAuthState()
    private val appService = getAppService()
    private val scheduler = CoroutineReconciliationScheduler()
    private val preparationProgress = getAcpPreparationProgress(project)
    private var automaticPreparation: AcpPreparationLease? = null
    private val controller =
        AcpLoginReconciliationController(
            scheduler = scheduler,
            attempt = ::reconcile,
            cleanup = ::cleanupProject,
            onPreparingChanged = { preparing ->
                if (preparing) {
                    if (automaticPreparation == null) automaticPreparation = preparationProgress.acquire()
                } else {
                    automaticPreparation?.finish()
                    automaticPreparation = null
                }
            },
        )
    private val authListener =
        PropertyChangeListener { event ->
            val token = event.newValue as? String
            if (!token.isNullOrBlank()) {
                // A new login is a new server session: the project activation
                // stored from the previous session no longer authorizes grants.
                runCatching { getProjectTokenService(project).setActivated(false) }
            }
            preparationProgress.cancelAll()
            controller.authenticationChanged(!token.isNullOrBlank())
        }
    private val sessionReadyListener: () -> Unit = { controller.trigger() }
    private var started = false
    private var disposed = false

    @Synchronized
    fun start() {
        if (disposed || started || project.isDisposed) return
        started = true
        authState.addPropertyChangeListener(TOKEN_PROPERTY, authListener)
        appService.addSessionReadyListener(sessionReadyListener)
        controller.start(authState.isAuthenticated())
    }

    private fun reconcile(isCurrent: () -> Boolean): ReconciliationAttemptResult {
        if (!isCurrent() || project.isDisposed) return ReconciliationAttemptResult.RETRY
        // The token notification is synchronous and precedes session acquisition.
        // Acquire here as part of the retry loop so a failed UI/startup handoff
        // can recover without another login or project reopen.
        appService.acquireSessionForReconciliation()
        if (!isCurrent() || project.isDisposed) return ReconciliationAttemptResult.RETRY
        // Every ACP grant (research proxy included) needs this project activated
        // in the current server session. The study path returned before the
        // ordinary setup could do it, so after a sign-out/sign-in every grant
        // was refused with 401 until the project was reopened.
        if (!ensureProjectActivated()) {
            log.info("Project activation is not ready; ACP reconciliation will retry")
            return ReconciliationAttemptResult.RETRY
        }
        val research = ResearchSessionService.getInstance(project).reconcileFromServer()
        return when (research) {
            is ResearchReconciliationResult.Unavailable -> {
                log.info("Research membership is not ready; ACP reconciliation will retry")
                ReconciliationAttemptResult.RETRY
            }
            is ResearchReconciliationResult.StudyOwned -> {
                if (research.shouldRetry) {
                    log.info("Research ACP setup is not ready; reconciliation will retry")
                    ReconciliationAttemptResult.RETRY
                } else {
                    // Participant setup observes the now-authoritative study
                    // context and registers only its auth bridge, never the
                    // direct managed ACP entry.
                    reconcileOrdinarySetup(isCurrent)
                }
            }
            is ResearchReconciliationResult.NoEnrollment,
            is ResearchReconciliationResult.Terminal,
            -> reconcileOrdinarySetup(isCurrent)
        }
    }

    private fun ensureProjectActivated(): Boolean {
        val tokens = getProjectTokenService(project)
        if (tokens.isActivated() && tokens.hasProjectToken()) return true
        return runCatching { activateOrCreateProject(project, log) }
            .onFailure { log.warn("Managed project activation is not ready", it) }
            .isSuccess && tokens.isActivated() && tokens.hasProjectToken()
    }

    private fun reconcileOrdinarySetup(isCurrent: () -> Boolean): ReconciliationAttemptResult {
        if (!isCurrent() || project.isDisposed) return ReconciliationAttemptResult.RETRY
        val setup = getParticipantAgentSetupService()
        val status = setup.prepareWithLegacyFallback(project)
        if (!isCurrent() || project.isDisposed) {
            // This attempt became stale (logout) or the project closed while setup
            // registered: do not leak the registration into the next generation.
            runCatching { setup.unregister(project) }
            return ReconciliationAttemptResult.RETRY
        }
        return when (status.step) {
            ParticipantSetupStep.READY, ParticipantSetupStep.STUDY_ACTIVE -> {
                if (status.step == ParticipantSetupStep.READY && mayPrepareDeveloperAgents(status)) {
                    setup.prepareDeveloperAgents(project)
                }
                ReconciliationAttemptResult.COMPLETE
            }
            ParticipantSetupStep.SIGN_IN, ParticipantSetupStep.PREPARE_AGENT -> {
                // Needs the user (sign in, install JetBrains AI Assistant, Repair
                // agent): retrying cannot change it, and would keep the
                // "Preparing Code4Me agent" balloon up forever for an ordinary
                // (non-study) setup. The settings page offers Prepare/Repair.
                log.info("Managed participant ACP setup needs the user (${status.step}): ${status.message}")
                ReconciliationAttemptResult.COMPLETE
            }
            ParticipantSetupStep.CHECK_SERVER -> {
                log.info("Managed participant ACP setup is not ready (${status.step}); reconciliation will retry")
                ReconciliationAttemptResult.RETRY
            }
        }
    }

    /**
     * [quarantine] is true only for an auth change (logout/account switch), where
     * stopped research contexts must have their spool quarantined. On a plain
     * project close it is false: the platform disposes the research service
     * separately with quarantine disabled, so unuploaded events stay adoptable.
     */
    private fun cleanupProject(quarantine: Boolean) {
        if (quarantine) {
            runCatching { project.getServiceIfCreated(ResearchSessionService::class.java)?.onLogout() }
                .onFailure { log.warn("Research cleanup after auth change failed", it) }
        }
        runCatching { getParticipantAgentSetupService().unregister(project) }
            .onFailure { log.warn("Managed ACP cleanup after auth change failed", it) }
    }

    @Synchronized
    override fun dispose() {
        if (disposed) return
        disposed = true
        if (started) {
            authState.removePropertyChangeListener(TOKEN_PROPERTY, authListener)
            appService.removeSessionReadyListener(sessionReadyListener)
        }
        controller.dispose()
    }
}
