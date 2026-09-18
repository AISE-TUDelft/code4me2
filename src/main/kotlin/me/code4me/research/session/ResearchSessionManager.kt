package me.code4me.research.session

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import me.code4me.research.bootstrap.BootstrapClient
import me.code4me.research.bootstrap.BootstrapEnvironment
import me.code4me.research.bootstrap.BootstrapManifest
import me.code4me.research.bootstrap.BootstrapRejection
import me.code4me.research.bootstrap.BootstrapResult
import me.code4me.research.bootstrap.BootstrapStatus
import me.code4me.research.bootstrap.BootstrapTransport
import me.code4me.research.bootstrap.EnrollmentDiscovery
import me.code4me.research.bootstrap.InMemoryManifestCache
import me.code4me.research.bootstrap.ManifestCache
import me.code4me.research.bootstrap.ManifestValidationReason
import me.code4me.research.bootstrap.PluginCompatibility
import me.code4me.research.bootstrap.normalizeSha256Hex
import me.code4me.research.bootstrap.parseInstant
import me.code4me.research.telemetry.CanonicalEvent
import me.code4me.research.telemetry.FieldClass
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.parseCanonicalJson
import me.code4me.research.telemetry.sha256Hex
import me.code4me.research.ide.CanonicalEventSink
import me.code4me.research.ide.IdeActivityCollector
import me.code4me.research.ide.IdeActivitySource
import me.code4me.research.ide.IdeActivityType
import me.code4me.research.ide.IdeCollectionScope
import me.code4me.research.telemetry.CodeMetadataMode
import me.code4me.research.telemetry.PrivacyFilter
import me.code4me.research.telemetry.PrivacyPolicy
import me.code4me.research.bootstrap.AgentDistributionMode
import me.code4me.research.proxy.AcpHostRegistration
import me.code4me.research.proxy.ByoaAgentResolution
import me.code4me.research.proxy.ByoaAgentResolver
import me.code4me.research.proxy.ByoaAgentSpec
import me.code4me.research.proxy.ProxyLaunchException
import me.code4me.research.proxy.ProxyLaunchRequest
import me.code4me.research.proxy.ProxyLaunchSpec
import me.code4me.research.proxy.ProxyLaunchSpecBuilder
import me.code4me.research.proxy.ProxyRuntimeResolution
import me.code4me.research.proxy.ProxyRuntimeResolver
import me.code4me.research.proxy.ResolvedProxyRuntime
import me.code4me.research.spool.DurableSpool
import me.code4me.research.spool.ResearchSpoolIpcServer
import me.code4me.research.spool.SpoolDelivery
import me.code4me.research.spool.SpoolIpcServer
import me.code4me.research.spool.SpoolStats
import me.code4me.research.spool.SpoolUploader
import me.code4me.research.spool.SpoolUploadResult
import me.code4me.research.spool.SpoolUploaderContext
import me.code4me.research.session.ParticipantStudyStateV1
import me.code4me.research.session.SpoolDeliveryState
import me.code4me.research.session.StudyBlockReason
import me.code4me.research.session.StudyComponentState
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A handle to a running proxy process; [stop] tears it down (and is idempotent). */
fun interface ProxyHandle {
    fun stop()
}

/**
 * Launches a resolved proxy spec. Injectable so tests and builds that have not
 * resolved a runtime never spawn a process.
 */
fun interface ProxyLauncher {
    fun launch(spec: ProxyLaunchSpec): ProxyHandle
}

/** Typed result of a proxy launch attempt. */
sealed interface ProxyLaunchResult {
    /** The proxy is running for [sessionId]. */
    data class Running(val sessionId: String) : ProxyLaunchResult

    /** No launch was attempted because the manifest/session is not launchable. */
    data class Rejected(val reason: StudyBlockReason, val detail: String? = null) : ProxyLaunchResult

    /** The launch was attempted and failed. */
    data class Failed(val detail: String? = null) : ProxyLaunchResult
}

/** Typed result of an activation attempt; never thrown. */
sealed interface ResearchActivationResult {
    data class Activated(
        val sessionId: String,
        val resumedExistingSession: Boolean,
        val manifestDigest: String,
    ) : ResearchActivationResult

    /** A policy/validation block: no collectors and no proxy may run. */
    data class Blocked(
        val reason: StudyBlockReason,
        val detail: String? = null,
        /** Typed server rejection, when the block came from the bootstrap API. */
        val rejection: BootstrapRejection? = null,
    ) : ResearchActivationResult

    /** A transient transport failure; retry later. */
    data class Retryable(val detail: String? = null) : ResearchActivationResult

    /** An unexpected research failure; ordinary plugin behaviour is unaffected. */
    data class Failed(val detail: String? = null) : ResearchActivationResult
}

/** Typed result of a session lifecycle operation; never thrown. */
sealed interface ResearchSessionResult {
    data class Applied(val sessionState: SessionState, val detail: String? = null) : ResearchSessionResult

    data class Rejected(val reason: StudyBlockReason, val detail: String? = null) : ResearchSessionResult
}

/** Typed result of an agent-run operation; never thrown. */
sealed interface AgentRunResult {
    data class Started(val run: AgentRun) : AgentRunResult

    data class Ended(val run: AgentRun) : AgentRunResult

    data class Rejected(val reason: StudyBlockReason, val detail: String? = null) : AgentRunResult
}

/** Typed result of stopping the manager. */
sealed interface ResearchStopResult {
    /**
     * @property finalSessionState the session state after teardown (suspended when
     * a live session may still resume within the revision grace window).
     * @property pendingSpoolRecords bounded count of locally retained events.
     */
    data class Stopped(
        val finalSessionState: SessionState?,
        val pendingSpoolRecords: Int,
        val spoolStats: SpoolStats?,
    ) : ResearchStopResult
}

/** Idle/resume timing derived from a validated manifest, never a compiled constant. */
data class ResearchSessionPolicy(
    val resumeGraceMs: Long,
    val idleTimeoutMs: Long,
) {
    init {
        require(resumeGraceMs > 0) { "resumeGraceMs must be positive" }
        require(idleTimeoutMs > 0) { "idleTimeoutMs must be positive" }
    }
}

/**
 * Resumable, per-enrollment session store. The default implementation is
 * process-local; the durable [FileResearchSessionStore] is what the IDE service
 * injects so a restart inside the resume grace reopens the same session.
 */
interface ResearchSessionStore {
    fun load(enrollmentId: String): ResearchSession?

    fun save(session: ResearchSession)

    fun clear(enrollmentId: String)
}

/** Process-local, thread-safe session store, scoped per execution context. */
class InMemoryResearchSessionStore : ResearchSessionStore {
    private val entries = HashMap<String, ResearchSession>()

    private fun key(enrollmentId: String?, contextId: String?): String? {
        if (enrollmentId == null) return null
        return if (contextId.isNullOrBlank()) enrollmentId else "$enrollmentId::$contextId"
    }

    @Synchronized
    override fun load(enrollmentId: String): ResearchSession? = entries[enrollmentId]

    @Synchronized
    override fun save(session: ResearchSession) {
        val key = key(session.enrollmentId, session.contextId) ?: return
        entries[key] = session
    }

    @Synchronized
    override fun clear(enrollmentId: String) {
        entries.remove(enrollmentId)
    }
}

/** Default durable session directory under the IDE system path. */
fun defaultResearchSessionRoot(): Path = Path.of(PathManager.getSystemPath(), "code4me", "research")

/**
 * File-backed [ResearchSessionStore] used by the IDE service so a research
 * session survives an IDE restart.
 *
 * Each enrollment's session is one canonical-JSON document at
 * `<root>/session-<opaqueKey>.json` (the key is a non-reversible hash, never the
 * raw enrollment id). Saves are atomic: the document is written to a sibling
 * temp file and moved into place, so a crash can never leave a partially
 * written session. Loads are safe: a missing, unreadable, or corrupt document
 * is reported as "no stored session" and never throws, so a damaged store
 * degrades to a fresh session instead of breaking research activation.
 *
 * @property root directory holding the per-enrollment session documents.
 */
class FileResearchSessionStore(
    private val root: Path = defaultResearchSessionRoot(),
) : ResearchSessionStore {
    override fun load(enrollmentId: String): ResearchSession? =
        try {
            val file = fileFor(enrollmentId)
            if (!Files.exists(file)) {
                null
            } else {
                val parsed = parseCanonicalJson(Files.readString(file, StandardCharsets.UTF_8))
                (parsed as? Map<*, *>)?.let { fromCanonicalMap(it) }
            }
        } catch (_: Exception) {
            null
        }

    override fun save(session: ResearchSession) {
        val key = ResearchSessionManager.sessionStoreKey(session.enrollmentId, session.contextId) ?: return
        try {
            Files.createDirectories(root)
            val target = fileFor(key)
            val temporary = Files.createTempFile(root, SESSION_FILE_PREFIX, TEMP_SUFFIX)
            try {
                Files.writeString(temporary, canonicalJson(toCanonicalMap(session)), StandardCharsets.UTF_8)
                try {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: Exception) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (_: Exception) {
            // A session-store write failure must never break the research lifecycle.
        }
    }

    override fun clear(enrollmentId: String) {
        try {
            Files.deleteIfExists(fileFor(enrollmentId))
        } catch (_: Exception) {
            // Best effort: a missing file is already cleared.
        }
    }

    private fun fileFor(key: String): Path =
        root.resolve("$SESSION_FILE_PREFIX${ResearchSessionManager.opaqueSessionKey(key)}$SESSION_FILE_EXTENSION")

    private fun toCanonicalMap(session: ResearchSession): Map<String, Any?> =
        linkedMapOf(
            "schema_version" to SESSION_SCHEMA_VERSION,
            "session_id" to session.sessionId,
            "enrollment_id" to session.enrollmentId,
            "study_id" to session.studyId,
            "assignment_id" to session.assignmentId,
            "profile_digest" to session.profileDigest,
            "context_id" to session.contextId,
            "state" to session.state.value,
            "opened_at_epoch_ms" to session.openedAtEpochMs,
            "last_activity_epoch_ms" to session.lastActivityEpochMs,
            "closed_at_epoch_ms" to session.closedAtEpochMs,
            "close_reason" to session.closeReason?.value,
            "resume_generation" to session.resumeGeneration,
        )

    private fun fromCanonicalMap(map: Map<*, *>): ResearchSession? {
        val sessionId = (map["session_id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val state =
            SessionState.entries.firstOrNull { it.value == map["state"] }
                ?: return null
        val closeReason =
            (map["close_reason"] as? String)?.let { wire ->
                SessionTerminalReason.entries.firstOrNull { it.value == wire }
            }
        return try {
            ResearchSession(
                sessionId = sessionId,
                enrollmentId = map["enrollment_id"] as? String,
                studyId = map["study_id"] as? String,
                assignmentId = map["assignment_id"] as? String,
                profileDigest = map["profile_digest"] as? String,
                contextId = (map["context_id"] as? String) ?: "",
                state = state,
                openedAtEpochMs = (map["opened_at_epoch_ms"] as? Number)?.toLong(),
                lastActivityEpochMs = (map["last_activity_epoch_ms"] as? Number)?.toLong(),
                closedAtEpochMs = (map["closed_at_epoch_ms"] as? Number)?.toLong(),
                closeReason = closeReason,
                resumeGeneration = (map["resume_generation"] as? Number)?.toInt() ?: 0,
            )
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val SESSION_SCHEMA_VERSION = "1"
        const val SESSION_FILE_PREFIX = "session-"
        const val SESSION_FILE_EXTENSION = ".json"
        const val TEMP_SUFFIX = ".tmp"
    }
}

/**
 * Project-scoped research lifecycle coordinator (Issue 10).
 *
 * It owns one project's manifest/session/spool/collector/proxy lifecycle and
 * composes the existing research core:
 * [BootstrapClient], [SessionStateMachine]/[ResearchSession]/[AgentRun],
 * [DurableSpool], `ProxyLaunchSpec`, [IdeActivityCollector], and
 * [ParticipantStudyStateV1].
 *
 * Invariants:
 * - nothing is collected or launched before a manifest validates;
 * - the manager never reads or stores editor content, only metadata that the
 *   collector already allowlisted;
 * - research failures are returned as typed results and never thrown out of the
 *   public API, so ordinary Code4Me features cannot be disabled by research;
 * - all timing is injected, so lifecycle behaviour is deterministic in tests.
 */
class ResearchSessionManager(
    private val projectKey: String,
    private val transport: BootstrapTransport,
    private val compatibility: PluginCompatibility,
    private val spoolProvider: (String) -> DurableSpool = { enrollmentId ->
        DurableSpool(defaultSpoolRoot().resolve(opaqueSpoolKey(enrollmentId)))
    },
    private val source: IdeActivitySource? = null,
    private val proxyLauncher: ProxyLauncher = ProxyLauncher { _ -> ProxyHandle { } },
    private val proxyRuntimeResolver: ProxyRuntimeResolver? = null,
    private val acpHostRegistration: AcpHostRegistration? = null,
    private val agentEnvProvider: () -> Map<String, String> = { emptyMap() },
    private val capabilityFilePathProvider: () -> Path? = { null },
    private val capabilityRootProvider: () -> Path = { defaultResearchSessionRoot() },
    private val byoaAgentResolver: ByoaAgentResolver = ByoaAgentResolver.DEFAULT,
    private val byoaAgentCommandProvider: () -> String? = { null },
    private val serverBaseUrlProvider: () -> String? = { null },
    private val environmentProvider: () -> BootstrapEnvironment = { BootstrapEnvironment() },
    private val httpClient: Call.Factory = defaultHttpClient,
    private val clientInstanceIdProvider: (String) -> String = { enrollmentId -> opaqueClientInstanceId(enrollmentId) },
    private val ipcServerFactory: ((DurableSpool) -> SpoolIpcServer)? = null,
    private val uploaderFactory: (SpoolUploaderContext) -> SpoolDelivery = { context -> defaultUploader(context) },
    private val sessionStore: ResearchSessionStore = InMemoryResearchSessionStore(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val instantClock: () -> Instant = { Instant.now() },
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val runIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val collectorFactory: (IdeActivitySource, CanonicalEventSink) -> IdeActivityCollector =
        { ideSource, sink -> IdeActivityCollector(ideSource, sink) },
    private val manifestCache: ManifestCache = InMemoryManifestCache(),
    private val nearExpiryWindow: Duration = BootstrapManifest.DEFAULT_NEAR_EXPIRY_WINDOW,
    private val defaultResumeGraceMs: Long = DEFAULT_RESUME_GRACE_MS,
    private val defaultIdleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val maintenanceScheduler: MaintenanceScheduler = ThreadedMaintenanceScheduler(),
    private val capabilityRefreshMargin: Duration = DEFAULT_CAPABILITY_REFRESH_MARGIN,
    /**
     * Membership authority checked before any bootstrap/session request.
     *
     * When present (the IDE service wires [ResearchJoinCodeResolver.discover]),
     * a terminal enrollment (revoked, stopped, completed) blocks activation
     * without touching the bootstrap or session endpoints. `null` keeps the
     * pure bootstrap path (tests and builds without discovery). A discovery
     * failure never blocks: the bootstrap API remains authoritative.
     */
    private val enrollmentDiscoveryProvider: (() -> EnrollmentDiscovery)? = null,
) {
    init {
        require(projectKey.isNotBlank()) { "projectKey must not be blank" }
        require(defaultResumeGraceMs > 0) { "defaultResumeGraceMs must be positive" }
        require(defaultIdleTimeoutMs > 0) { "defaultIdleTimeoutMs must be positive" }
    }

    private val log = thisLogger()

    /**
     * Opaque, stable execution-context id for this project/window. Derived
     * (never random, never the raw path) so a restart reuses the same server
     * session for this context, while a different project/window gets its own.
     */
    val contextId: String = opaqueContextId(projectKey)

    /** Durable-store key for this manager's execution context. */
    private fun sessionKey(enrollmentId: String): String =
        sessionStoreKey(enrollmentId, contextId) ?: enrollmentId

    private val bootstrap = BootstrapClient(transport, compatibility, manifestCache, instantClock, nearExpiryWindow, contextId = contextId)
    private val lock = Any()

    @Volatile private var manifest: BootstrapManifest? = null

    @Volatile private var machine: SessionStateMachine? = null

    @Volatile private var session: ResearchSession? = null

    @Volatile private var currentRun: AgentRun? = null

    @Volatile private var spool: DurableSpool? = null

    @Volatile private var collector: IdeActivityCollector? = null

    @Volatile private var proxyHandle: ProxyHandle? = null

    @Volatile private var capabilityFile: Path? = null

    @Volatile private var ipcServer: SpoolIpcServer? = null

    @Volatile private var uploader: SpoolDelivery? = null

    @Volatile private var maintenanceHandle: MaintenanceHandle? = null

    @Volatile private var scheduledHeartbeatMs: Long? = null

    @Volatile private var activePrivacyPolicy: PrivacyPolicy = PrivacyPolicy.default()

    @Volatile private var privacyFilter: PrivacyFilter = PrivacyFilter(PrivacyPolicy.default())

    @Volatile private var consentState: StudyComponentState = StudyComponentState.UNAVAILABLE

    @Volatile private var compatibilityState: StudyComponentState = StudyComponentState.UNAVAILABLE

    @Volatile private var blockReason: StudyBlockReason? = null

    @Volatile private var blockReasonDetail: String? = null

    @Volatile private var active = false

    @Volatile private var stopped = false

    /** True while a validated session may collect and launch. */
    val isActive: Boolean
        get() = active && !stopped

    /** True while the IDE collector is attached and forwarding metadata. */
    val isCollecting: Boolean
        get() = collector?.isActive == true

    /** The current session, exposed for diagnostics and tests (metadata only). */
    val currentSession: ResearchSession?
        get() = session

    /** The most recent agent run, exposed for diagnostics and tests. */
    val currentAgentRun: AgentRun?
        get() = currentRun

    /**
     * The participant-visible study state. Never exposes content, paths, or
     * credentials; blocked states always carry a typed reason.
     */
    fun state(): ParticipantStudyStateV1 {
        val current = session
        val held = manifest
        val effectiveBlock =
            when {
                blockReason != null -> blockReason
                current?.state == SessionState.ENDED -> StudyBlockReason.SESSION_ENDED
                current?.state == SessionState.REVOKED -> StudyBlockReason.REVOKED
                else -> null
            }
        return ParticipantStudyStateV1(
            enrollmentId = current?.enrollmentId ?: held?.enrollmentId,
            studyId = held?.studyId,
            assignmentId = held?.assignment?.assignmentId,
            agentProfileId = held?.assignment?.agentProfileId,
            profileDigest = held?.assignment?.profileDigest,
            consentState = consentState,
            compatibilityState = compatibilityState,
            sessionState = sessionComponentOf(current?.state),
            manifestExpiry = held?.expiresAt,
            manifestDigest = held?.manifestDigest,
            blockReason = effectiveBlock,
            blockReasonDetail = blockReasonDetail,
            deliveryState = currentDeliveryState(),
        )
    }

    /**
     * Request, validate, and (only on success) start a research session.
     *
     * A blocked, invalid, or expired manifest produces a typed [Blocked] result
     * with no collector and no proxy. A transient transport failure produces
     * [Retryable]. Nothing here ever throws.
     */
    fun activate(enrollmentId: String): ResearchActivationResult {
        if (enrollmentId.isBlank()) {
            return ResearchActivationResult.Blocked(StudyBlockReason.UNKNOWN, "enrollmentId must not be blank")
        }
        if (stopped) {
            return ResearchActivationResult.Blocked(StudyBlockReason.REVOKED, "research session manager is stopped")
        }
        // Membership first (plan 05.5 step 1): a terminal enrollment never
        // reaches the bootstrap or session endpoints.
        when (val discovery = safeDiscovery()) {
            is EnrollmentDiscovery.Terminal -> {
                val detail = "This enrollment is ${discovery.status.lowercase()}; it cannot be activated."
                markBlocked(StudyBlockReason.REVOKED, detail)
                return ResearchActivationResult.Blocked(StudyBlockReason.REVOKED, detail)
            }
            else -> Unit
        }
        deactivateRuntime()
        return try {
            val result = bootstrap.acquire(enrollmentId)
            when (result.status) {
                BootstrapStatus.OK -> {
                    val validManifest = result.manifest
                    if (validManifest == null) {
                        markBlocked(StudyBlockReason.MANIFEST_INVALID, "validated manifest was not returned")
                        ResearchActivationResult.Blocked(StudyBlockReason.MANIFEST_INVALID, "validated manifest was not returned")
                    } else {
                        startSession(enrollmentId, validManifest)
                    }
                }
                BootstrapStatus.BLOCKED -> {
                    val reason = blockReasonFor(result)
                    markBlocked(reason, result.reason)
                    ResearchActivationResult.Blocked(reason, result.reason, result.rejection)
                }
                BootstrapStatus.REFRESH_REQUIRED -> {
                    markBlocked(StudyBlockReason.MANIFEST_EXPIRED, result.reason)
                    ResearchActivationResult.Blocked(StudyBlockReason.MANIFEST_EXPIRED, result.reason)
                }
                BootstrapStatus.RETRYABLE -> {
                    markFailed(StudyBlockReason.TRANSPORT_FAILED, result.reason)
                    ResearchActivationResult.Retryable(result.reason)
                }
            }
        } catch (exception: Exception) {
            markFailed(StudyBlockReason.TRANSPORT_FAILED, exception.message)
            ResearchActivationResult.Failed(exception.message)
        }
    }

    /**
     * Heartbeat/upload recovery: checks idle expiry first, then recovers an
     * offline session to running or refreshes the activity marker.
     */
    fun heartbeat(): ResearchSessionResult {
        val current = session ?: return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "no active session")
        if (!isActive) return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "collection is not active")
        if (current.isTerminal) return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "session is terminal")
        val now = clock()
        val currentMachine = machine ?: return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "no session policy")
        val idle = currentMachine.expireIfIdle(current, now)
        if (idle.isTerminal) {
            session = idle
            sessionStore.save(idle)
            onSessionEnded()
            return ResearchSessionResult.Applied(idle.state, "idle timeout")
        }
        val next =
            when (idle.state) {
                SessionState.OFFLINE -> currentMachine.recover(idle, now)
                SessionState.NOT_STARTED, SessionState.SUSPENDED -> idle
                else -> currentMachine.recordActivity(idle, now)
            }
        session = next
        sessionStore.save(next)
        return ResearchSessionResult.Applied(next.state)
    }

    /** Network loss: a running session becomes offline and keeps spooling locally. */
    fun onNetworkUnavailable(): ResearchSessionResult {
        val current = session ?: return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "no active session")
        if (!isActive || current.isTerminal) {
            return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "collection is not active")
        }
        val currentMachine = machine ?: return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "no session policy")
        val next = if (current.state == SessionState.RUNNING) currentMachine.goOffline(current, clock()) else current
        if (next !== current) {
            session = next
            sessionStore.save(next)
        }
        return ResearchSessionResult.Applied(next.state)
    }

    /** Explicit idle check (safe to call periodically); idle timeout ends the session. */
    fun checkIdle(): ResearchSessionResult {
        val current = session ?: return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "no active session")
        if (current.isTerminal) return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "session is terminal")
        val currentMachine = machine ?: return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "no session policy")
        val idle = currentMachine.expireIfIdle(current, clock())
        if (idle.isTerminal && !current.isTerminal) {
            session = idle
            sessionStore.save(idle)
            onSessionEnded()
            return ResearchSessionResult.Applied(idle.state, "idle timeout")
        }
        return ResearchSessionResult.Applied(idle.state)
    }

    /**
     * One periodic maintenance tick: refresh the telemetry capability before it
     * expires, then heartbeat the server session at its declared cadence.
     *
     * Fail-soft by construction: every transport failure becomes a typed
     * [ResearchMaintenanceResult.Retryable] and never affects ordinary plugin
     * behaviour. A terminal server answer (session ended/revoked, kill switch)
     * tears the runtime down and is never resurrected by a later tick.
     */
    fun performMaintenance(): ResearchMaintenanceResult {
        val current = session
        if (stopped || !isActive || current == null || current.isTerminal) {
            return ResearchMaintenanceResult.Inactive("no active research session")
        }
        var capabilityRefreshed = false
        when (val refresh = refreshCapabilityIfNeeded(force = false)) {
            is CapabilityRefresh.Refreshed -> capabilityRefreshed = true
            is CapabilityRefresh.Blocked -> {
                handleServerTerminal(refresh.reason, refresh.detail)
                return ResearchMaintenanceResult.Ended(refresh.reason, refresh.detail)
            }
            is CapabilityRefresh.Retryable -> {
                // A refresh failure must not stop the session: the heartbeat
                // below still uses the held capability and reports the server's
                // own answer.
            }
            CapabilityRefresh.NotNeeded -> Unit
        }
        val afterRefresh = session
        val held = manifest
        if (stopped || !isActive || afterRefresh == null || afterRefresh.isTerminal || held == null) {
            return ResearchMaintenanceResult.Inactive("research session is no longer active")
        }
        return when (val heartbeat = sendHeartbeatRequest(held, afterRefresh)) {
            is SessionHeartbeat.Ok -> {
                if (stopped || !isActive) {
                    return ResearchMaintenanceResult.Inactive("research session is no longer active")
                }
                heartbeat.heartbeatSeconds?.let { rescheduleMaintenance(it) }
                val state = heartbeat.sessionState
                if (state == SERVER_STATE_ENDED || state == SERVER_STATE_REVOKED) {
                    val reason = if (state == SERVER_STATE_REVOKED) StudyBlockReason.REVOKED else StudyBlockReason.SESSION_ENDED
                    handleServerTerminal(reason, "the research server reports session state '$state'")
                    ResearchMaintenanceResult.Ended(reason, state)
                } else {
                    ResearchMaintenanceResult.Maintained(heartbeat.heartbeatSeconds, capabilityRefreshed)
                }
            }
            is SessionHeartbeat.ExpiredCapability -> when (val forced = refreshCapabilityIfNeeded(force = true)) {
                is CapabilityRefresh.Refreshed -> ResearchMaintenanceResult.Maintained(null, true)
                is CapabilityRefresh.Blocked -> {
                    handleServerTerminal(forced.reason, forced.detail)
                    ResearchMaintenanceResult.Ended(forced.reason, forced.detail)
                }
                is CapabilityRefresh.Retryable -> ResearchMaintenanceResult.Retryable(forced.detail)
                CapabilityRefresh.NotNeeded -> ResearchMaintenanceResult.Retryable(heartbeat.detail)
            }
            is SessionHeartbeat.Terminal -> {
                handleServerTerminal(heartbeat.reason, heartbeat.detail)
                ResearchMaintenanceResult.Ended(heartbeat.reason, heartbeat.detail)
            }
            is SessionHeartbeat.Retryable -> ResearchMaintenanceResult.Retryable(heartbeat.detail)
        }
    }

    /** Explicit participant completion: terminal end. */
    fun close(): ResearchSessionResult {
        val current = session ?: return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "no active session")
        if (current.isTerminal) return ResearchSessionResult.Applied(current.state)
        val now = clock()
        val currentMachine = machine
        val ended =
            if (currentMachine != null && currentMachine.canTransition(current.state, SessionState.ENDED)) {
                currentMachine.end(current, now)
            } else {
                current.copy(
                    state = SessionState.ENDED,
                    closedAtEpochMs = now,
                    closeReason = SessionTerminalReason.EXPLICIT_COMPLETION,
                )
            }
        session = ended
        blockReason = StudyBlockReason.SESSION_ENDED
        blockReasonDetail = "session closed by the participant"
        sessionStore.save(ended)
        onSessionEnded()
        return ResearchSessionResult.Applied(ended.state)
    }

    /** Withdrawal/server revocation: terminal, no further events, proxy torn down. */
    fun onRevoked(): ResearchSessionResult {
        val current = session
        deactivateRuntime()
        synchronized(lock) {
            active = false
            consentState = StudyComponentState.BLOCKED
            blockReason = StudyBlockReason.REVOKED
            blockReasonDetail = "study enrollment was revoked"
        }
        if (current != null && !current.isTerminal) {
            val now = clock()
            val currentMachine = machine
            val revoked =
                if (currentMachine != null && currentMachine.canTransition(current.state, SessionState.REVOKED)) {
                    currentMachine.revoke(current, now)
                } else {
                    current.copy(
                        state = SessionState.REVOKED,
                        closedAtEpochMs = now,
                        closeReason = SessionTerminalReason.REVOKED,
                    )
                }
            session = revoked
            sessionStore.save(revoked)
        }
        return ResearchSessionResult.Applied(session?.state ?: SessionState.REVOKED)
    }

    /** Consent pause: collectors/proxy stop, no new events, session is suspended. */
    fun onConsentPaused(): ResearchSessionResult {
        val current = session
        deactivateRuntime()
        synchronized(lock) {
            active = false
            consentState = StudyComponentState.PAUSED
            blockReasonDetail = "participant consent is paused"
        }
        if (current != null && !current.isTerminal) {
            val now = clock()
            val currentMachine = machine
            val suspended =
                if (currentMachine != null && currentMachine.canTransition(current.state, SessionState.SUSPENDED)) {
                    currentMachine.suspend(current, now)
                } else {
                    current
                }
            session = suspended
            sessionStore.save(suspended)
        }
        return ResearchSessionResult.Applied(session?.state ?: SessionState.NOT_STARTED)
    }

    /** Start an agent run inside the live session; a prior live run is cancelled. */
    fun agentStarted(agentReleaseId: String? = null): AgentRunResult {
        val current = session ?: return AgentRunResult.Rejected(StudyBlockReason.SESSION_ENDED, "no active session")
        if (!isActive || current.isTerminal) {
            return AgentRunResult.Rejected(StudyBlockReason.SESSION_ENDED, "collection is not active")
        }
        val now = clock()
        currentRun?.takeIf { !it.isTerminal }?.let { currentRun = it.end(now, AgentRunOutcome.CANCELLED) }
        val run = AgentRun.start(runIdFactory(), current, agentReleaseId, now)
        currentRun = run
        return AgentRunResult.Started(run)
    }

    /** An agent crash ends the run, never the research session. */
    fun agentCrashed(): AgentRunResult {
        val run = currentRun ?: return AgentRunResult.Rejected(StudyBlockReason.RUNTIME_UNAVAILABLE, "no agent run is active")
        if (run.isTerminal) return AgentRunResult.Rejected(StudyBlockReason.RUNTIME_UNAVAILABLE, "the agent run already ended")
        val current = session ?: return AgentRunResult.Rejected(StudyBlockReason.SESSION_ENDED, "no active session")
        val currentMachine =
            machine
                ?: return AgentRunResult.Rejected(StudyBlockReason.SESSION_ENDED, "no session policy")
        val result = currentMachine.onAgentRunCrashed(run, current, clock())
        currentRun = result.run
        session = result.session
        sessionStore.save(result.session)
        return AgentRunResult.Ended(result.run)
    }

    /**
     * Build and launch the pinned proxy for the active session.
     *
     * The launch is refused (typed [ProxyLaunchResult.Rejected]) whenever the
     * manifest has not validated or the session is not active; a spec/runtime
     * failure is reported as [ProxyLaunchResult.Failed].
     */
    fun launchProxy(request: ProxyLaunchRequest): ProxyLaunchResult {
        val current = session
        if (!isActive || current == null || current.isTerminal) {
            return ProxyLaunchResult.Rejected(StudyBlockReason.SESSION_ENDED, "research collection is not active")
        }
        return try {
            val spec = ProxyLaunchSpecBuilder.build(request.copy(researchSessionId = current.sessionId))
            val handle = proxyLauncher.launch(spec)
            val previous = proxyHandle
            proxyHandle = handle
            runCatching { previous?.stop() }
            ProxyLaunchResult.Running(current.sessionId)
        } catch (exception: ProxyLaunchException) {
            ProxyLaunchResult.Rejected(StudyBlockReason.RUNTIME_UNAVAILABLE, exception.message)
        } catch (exception: Exception) {
            ProxyLaunchResult.Failed(exception.message)
        }
    }

    /**
     * Stop the manager: tear down collectors/proxy, flush the bounded pending
     * spool, and terminally stop the runtime. A live session is suspended (not
     * discarded) so a restart inside the revision grace window may resume it;
     * [close] is the explicit participant completion.
     */
    /**
     * One bounded pre-withdrawal delivery attempt (Issue 03 F09).
     *
     * Runs the active spool uploader's single manual pass on a worker thread and
     * waits at most [timeoutMs]; it never blocks indefinitely and never throws.
     * Returns the typed upload disposition, or `null` when there is nothing to
     * flush (no live delivery).
     */
    fun flushBounded(timeoutMs: Long = 5_000L): SpoolUploadResult? {
        val delivery = uploader ?: return null
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit(java.util.concurrent.Callable { delivery.drainOnce() })
            future.get(timeoutMs.coerceAtLeast(0L), java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Stop the live delivery and quarantine this context's spool directory so
     * nothing in it can upload under a different account (Issue 03 F13).
     */
    fun quarantineSpool(): Path? {
        deactivateRuntime()
        return runCatching { spool?.quarantine() }.getOrNull()
    }

    fun stop(): ResearchStopResult {
        if (!stopped) {
            deactivateRuntime()
            val current = session
            if (current != null && !current.isTerminal && current.state != SessionState.NOT_STARTED) {
                val currentMachine = machine
                val suspended =
                    if (currentMachine != null && currentMachine.canTransition(current.state, SessionState.SUSPENDED)) {
                        currentMachine.suspend(current, clock())
                    } else {
                        current
                    }
                session = suspended
                sessionStore.save(suspended)
            } else if (current != null) {
                sessionStore.save(current)
            }
            synchronized(lock) {
                active = false
                stopped = true
            }
        }
        val stats = flushSpool()
        return ResearchStopResult.Stopped(session?.state, stats?.pendingCount ?: 0, stats)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun startSession(
        enrollmentId: String,
        validManifest: BootstrapManifest,
    ): ResearchActivationResult {
        val now = clock()
        val policy = policyFor(validManifest)
        val newMachine = SessionStateMachine(policy.resumeGraceMs, policy.idleTimeoutMs)
        val resolution = resolveSession(enrollmentId, newMachine, now)
        val blocked = resolution.blockedReason
        if (blocked != null) {
            markBlocked(blocked, resolution.blockedDetail)
            return ResearchActivationResult.Blocked(blocked, resolution.blockedDetail)
        }
        val resolvedSession =
            resolution.session
                ?: return ResearchActivationResult.Failed("session resolution produced no session")
        // The server-issued manifest session id is authoritative for this
        // enrollment. A locally resolved session may carry a fresh
        // `sessionIdFactory()` id or a stale stored id; either way, adopt the
        // manifest id so the collector scope, spooled events, proxy launch
        // request, and uploader all reference the session the server persisted.
        val manifestSessionId = validManifest.researchSession.researchSessionId
        val adoptedManifestId = manifestSessionId.isNotBlank() && manifestSessionId != resolvedSession.sessionId
        val authoritativeSession =
            resolvedSession.copy(
                sessionId = if (adoptedManifestId) manifestSessionId else resolvedSession.sessionId,
                studyId = validManifest.studyId,
                assignmentId = validManifest.assignment.assignmentId,
                profileDigest = validManifest.assignment.profileDigest,
            )
        val resumed = resolution.resumed && !adoptedManifestId
        val resolvedSpool =
            try {
                // Context-scoped spool: two windows of the same enrollment never
                // share a spool directory/queue.
                spoolProvider("${validManifest.enrollmentId}::$contextId")
            } catch (exception: Exception) {
                markFailed(StudyBlockReason.TRANSPORT_FAILED, exception.message)
                return ResearchActivationResult.Failed(exception.message)
            }
        val resolvedPolicy = privacyPolicyFor(validManifest)

        // Fail closed: without a live local spool IPC endpoint there is no
        // delivery authority, so nothing may be collected or launched.
        val startedIpc =
            try {
                ipcServerFactory?.invoke(resolvedSpool)
                    ?: ResearchSpoolIpcServer(resolvedSpool)
            } catch (exception: Exception) {
                val detail = exception.message ?: "the local spool IPC server could not start"
                markBlocked(StudyBlockReason.RUNTIME_UNAVAILABLE, detail)
                return ResearchActivationResult.Blocked(StudyBlockReason.RUNTIME_UNAVAILABLE, detail)
            }
        synchronized(lock) { ipcServer = startedIpc }

        // The ACP entry must point the proxy at the live IPC endpoint and hand
        // it that server's capability (never the server session capability).
        val runtimeSetup = prepareProxyRuntime(startedIpc, validManifest)
        if (runtimeSetup is RuntimeSetup.Failed) {
            markBlocked(runtimeSetup.reason, runtimeSetup.detail)
            return ResearchActivationResult.Blocked(runtimeSetup.reason, runtimeSetup.detail)
        }
        synchronized(lock) {
            capabilityFile = (runtimeSetup as? RuntimeSetup.Ready)?.capabilityFile
        }

        // The uploader reads the same durable spool; without it the spool would
        // never drain, so a start failure is fatal to activation.
        val uploaderStart = startUploader(resolvedSpool, validManifest)
        if (uploaderStart is UploaderStart.Failed) {
            markBlocked(StudyBlockReason.RUNTIME_UNAVAILABLE, uploaderStart.detail)
            return ResearchActivationResult.Blocked(StudyBlockReason.RUNTIME_UNAVAILABLE, uploaderStart.detail)
        }

        synchronized(lock) {
            manifest = validManifest
            machine = newMachine
            session = authoritativeSession
            currentRun = null
            spool = resolvedSpool
            uploader = (uploaderStart as? UploaderStart.Started)?.delivery
            activePrivacyPolicy = resolvedPolicy
            privacyFilter = PrivacyFilter(resolvedPolicy)
            consentState = StudyComponentState.AVAILABLE
            compatibilityState = StudyComponentState.AVAILABLE
            blockReason = null
            blockReasonDetail = null
            active = true
        }
        sessionStore.save(authoritativeSession)
        startCollector(authoritativeSession, validManifest)

        // Establish the server-side session (open/reuse) and start periodic
        // maintenance. Both are fail-soft: a transport failure must never
        // terminate an otherwise valid activation, and the periodic loop is what
        // keeps the capability alive past its short TTL.
        val heartbeatSeconds = establishServerSession(validManifest, authoritativeSession)
        if (!isActive) {
            val reason = blockReason ?: StudyBlockReason.UNKNOWN
            return ResearchActivationResult.Blocked(reason, blockReasonDetail)
        }
        startMaintenance(validManifest, heartbeatSeconds)
        return ResearchActivationResult.Activated(
            sessionId = authoritativeSession.sessionId,
            resumedExistingSession = resumed,
            manifestDigest = validManifest.manifestDigest,
        )
    }

    /** Typed spool-uploader start outcome; a failure blocks activation. */
    private sealed interface UploaderStart {
        /** No backend base URL is configured, so there is nothing to upload to. */
        object Disabled : UploaderStart

        data class Started(val delivery: SpoolDelivery) : UploaderStart

        data class Failed(val detail: String) : UploaderStart
    }

    /**
     * Build and start the spool uploader for [spool]. Failure to construct or
     * start it is fail-closed (typed [UploaderStart.Failed]) but never thrown.
     */
    private fun startUploader(
        spool: DurableSpool,
        validManifest: BootstrapManifest,
    ): UploaderStart {
        val serverBaseUrl = safeValue { serverBaseUrlProvider() } ?: return UploaderStart.Disabled
        return try {
            val context =
                SpoolUploaderContext(
                    spool = spool,
                    serverBaseUrl = serverBaseUrl,
                    sessionCapability = validManifest.sessionCapabilityObject(),
                    clientInstanceId = clientInstanceIdProvider(validManifest.enrollmentId),
                    httpClient = httpClient,
                )
            val delivery = uploaderFactory(context)
            delivery.start()
            UploaderStart.Started(delivery)
        } catch (exception: Exception) {
            UploaderStart.Failed(exception.message ?: "the spool uploader could not start")
        }
    }

    // ------------------------------------------------------------------
    // Server session lifecycle / periodic maintenance
    // ------------------------------------------------------------------

    /**
     * Open (or reuse) the server-side session for a freshly activated manifest and
     * return the server-declared heartbeat cadence.
     *
     * The server opens a session in `NOT_STARTED`; a `report_activity` heartbeat
     * is the documented transition to `RUNNING`, so one is sent immediately.
     * Every failure is fail-soft: a transient error leaves the session active and
     * the periodic loop retries; a terminal server answer tears the runtime down.
     */
    private fun establishServerSession(
        validManifest: BootstrapManifest,
        authoritativeSession: ResearchSession,
    ): Long? {
        if (safeValue { serverBaseUrlProvider() } == null) return null
        var heartbeatSeconds: Long? = null
        val create =
            postSessionJson(
                SESSIONS_PATH,
                linkedMapOf<String, Any?>(
                    "capability" to validManifest.sessionCapabilityObject(),
                    "enrollment_id" to validManifest.enrollmentId,
                    "study_id" to validManifest.studyId,
                    "manifest_digest" to validManifest.manifestDigest,
                    "context_id" to contextId,
                ),
            )
        if (create == null) return null
        val reason = reasonCodeFrom(create.body)
        if (isTerminalServerCode(reason)) {
            handleServerTerminal(serverTerminalReason(reason), reason)
            return null
        }
        if (create.code !in 200..299) {
            log.warn("Research session create was refused with HTTP ${create.code}; periodic maintenance will retry.")
            return null
        }
        heartbeatSeconds = heartbeatSecondsFrom(create.body)
        when (val heartbeat = sendHeartbeatRequest(validManifest, authoritativeSession)) {
            is SessionHeartbeat.Ok -> heartbeatSeconds = heartbeat.heartbeatSeconds ?: heartbeatSeconds
            is SessionHeartbeat.Terminal -> handleServerTerminal(heartbeat.reason, heartbeat.detail)
            else -> Unit
        }
        return heartbeatSeconds
    }

    /** Schedule (or reschedule) the periodic loop at the server-provided cadence. */
    private fun startMaintenance(
        validManifest: BootstrapManifest,
        heartbeatSeconds: Long?,
    ) {
        if (safeValue { serverBaseUrlProvider() } == null) return
        val seconds =
            heartbeatSeconds?.takeIf { it > 0 }
                ?: validManifest.policies.session?.heartbeatSeconds?.takeIf { it > 0 }
                ?: DEFAULT_HEARTBEAT_SECONDS
        rescheduleMaintenance(seconds)
    }

    private fun rescheduleMaintenance(seconds: Long) {
        // A tick that raced a teardown must never start a fresh loop.
        if (stopped || !active) return
        val periodMs = (seconds * SECONDS_TO_MILLIS).coerceAtLeast(MIN_HEARTBEAT_PERIOD_MS)
        if (maintenanceHandle != null && scheduledHeartbeatMs == periodMs) return
        val previous = maintenanceHandle
        maintenanceHandle = null
        scheduledHeartbeatMs = null
        runCatching { previous?.cancel() }
        val handle =
            try {
                maintenanceScheduler.schedule(periodMs) { runMaintenanceSafely() }
            } catch (exception: Exception) {
                log.warn("Research session maintenance could not be scheduled: ${exception.message ?: "unexpected error"}")
                null
            }
        maintenanceHandle = handle
        scheduledHeartbeatMs = if (handle != null) periodMs else null
    }

    /** Cancel the periodic loop; idempotent and exception-safe. */
    private fun stopMaintenance() {
        val handle = maintenanceHandle
        maintenanceHandle = null
        scheduledHeartbeatMs = null
        runCatching { handle?.cancel() }
    }

    private fun runMaintenanceSafely() {
        try {
            performMaintenance()
        } catch (exception: Exception) {
            log.warn("Research session maintenance failed — non-blocking", exception)
        }
    }

    /** Re-bootstrap only when the held capability is inside the refresh margin. */
    private fun capabilityNeedsRefresh(
        held: BootstrapManifest,
        force: Boolean,
    ): Boolean {
        if (force) return true
        val expires = parseInstant(held.sessionCapability.expiresAt) ?: return false
        return !instantClock().plus(capabilityRefreshMargin).isBefore(expires)
    }

    /**
     * Refresh the telemetry capability by re-running bootstrap, then hand the new
     * capability to the already-running uploader. Fail-soft: a transport failure
     * is [CapabilityRefresh.Retryable], a terminal server refusal is
     * [CapabilityRefresh.Blocked].
     */
    private fun refreshCapabilityIfNeeded(force: Boolean): CapabilityRefresh {
        val held = manifest ?: return CapabilityRefresh.Retryable("no validated manifest")
        if (!capabilityNeedsRefresh(held, force)) return CapabilityRefresh.NotNeeded
        return try {
            bootstrap.invalidate(held.enrollmentId)
            val result = bootstrap.acquire(held.enrollmentId)
            when (result.status) {
                BootstrapStatus.OK -> adoptRefreshedManifest(result.manifest)
                BootstrapStatus.BLOCKED -> CapabilityRefresh.Blocked(blockReasonFor(result), result.reason)
                BootstrapStatus.REFRESH_REQUIRED -> CapabilityRefresh.Retryable(result.reason)
                BootstrapStatus.RETRYABLE -> CapabilityRefresh.Retryable(result.reason)
            }
        } catch (exception: Exception) {
            log.warn("Research capability refresh failed: ${exception.message ?: "unexpected error"}")
            CapabilityRefresh.Retryable(exception.message)
        }
    }

    /** Adopt a re-bootstrapped manifest and push its capability to the uploader. */
    private fun adoptRefreshedManifest(fresh: BootstrapManifest?): CapabilityRefresh =
        synchronized(lock) {
            val current = session
            when {
                fresh == null -> CapabilityRefresh.Retryable("capability refresh returned no manifest")
                !active || stopped || current == null || current.isTerminal ->
                    CapabilityRefresh.Retryable("session is no longer active")
                fresh.researchSession.researchSessionId != current.sessionId ->
                    // The server no longer treats our session as active: do not
                    // resurrect it under a different session id.
                    CapabilityRefresh.Blocked(
                        StudyBlockReason.SESSION_ENDED,
                        "the research server moved to a different session",
                    )
                else -> {
                    manifest = fresh
                    val adopted = safeUpdateCapability(fresh.sessionCapabilityObject())
                    if (!adopted) {
                        log.warn("A refreshed research capability could not be adopted by the running uploader.")
                    }
                    CapabilityRefresh.Refreshed(fresh)
                }
            }
        }

    private fun safeUpdateCapability(capability: Map<String, Any?>): Boolean =
        try {
            uploader?.updateCapability(capability) ?: false
        } catch (exception: Exception) {
            log.warn("Research uploader rejected the refreshed capability: ${exception.message ?: "unexpected error"}")
            false
        }

    /** POST the heartbeat for [held]/[current]; never throws. */
    private fun sendHeartbeatRequest(
        held: BootstrapManifest,
        current: ResearchSession,
    ): SessionHeartbeat {
        val response =
            postSessionJson(
                HEARTBEAT_PATH,
                linkedMapOf<String, Any?>(
                    "capability" to held.sessionCapabilityObject(),
                    "research_session_id" to current.sessionId,
                ),
            ) ?: return SessionHeartbeat.Retryable("the heartbeat request could not be delivered")
        val reason = reasonCodeFrom(response.body)
        return when {
            response.code in 200..299 -> SessionHeartbeat.Ok(heartbeatSecondsFrom(response.body), sessionStateFrom(response.body))
            response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN ->
                if (isTerminalServerCode(reason)) {
                    SessionHeartbeat.Terminal(serverTerminalReason(reason), reason)
                } else {
                    // Usually an expired capability: re-bootstrap and resume.
                    SessionHeartbeat.ExpiredCapability(reason ?: "HTTP ${response.code}")
                }
            response.code == HTTP_CONFLICT ->
                if (isTerminalServerCode(reason) || reason == SESSION_TERMINAL_CODE) {
                    SessionHeartbeat.Terminal(serverTerminalReason(reason), reason)
                } else {
                    SessionHeartbeat.Retryable(reason ?: "HTTP ${response.code}")
                }
            else -> SessionHeartbeat.Retryable(reason ?: "HTTP ${response.code}")
        }
    }

    /** Best-effort JSON POST; a transport failure is logged and reported as null. */
    private fun postSessionJson(
        path: String,
        payload: Map<String, Any?>,
    ): SessionHttpResponse? {
        val baseUrl = safeValue { serverBaseUrlProvider() } ?: return null
        return try {
            val request =
                Request
                    .Builder()
                    .url(baseUrl.trimEnd('/') + path)
                    .post(canonicalJson(payload).toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json")
                    .build()
            httpClient.newCall(request).execute().use { response ->
                SessionHttpResponse(response.code, response.body?.string().orEmpty())
            }
        } catch (exception: Exception) {
            log.warn("Research session request to $path failed: ${exception.message ?: "unexpected error"}")
            null
        }
    }

    private fun heartbeatSecondsFrom(body: String): Long? =
        try {
            val map = parseCanonicalJson(body) as? Map<*, *> ?: return null
            (map["heartbeat_seconds"] as? Number)?.toLong()?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }

    private fun sessionStateFrom(body: String): String? =
        try {
            val map = parseCanonicalJson(body) as? Map<*, *> ?: return null
            val sessionSummary = map["session"] as? Map<*, *> ?: return null
            (sessionSummary["state"] as? String)?.trim()?.lowercase()
        } catch (_: Exception) {
            null
        }

    private fun reasonCodeFrom(body: String): String? =
        try {
            val map = parseCanonicalJson(body) as? Map<*, *> ?: return null
            when (val detail = map["detail"]) {
                is Map<*, *> -> (detail["code"] as? String) ?: (detail["reason"] as? String)
                is String -> detail
                else -> null
            }
        } catch (_: Exception) {
            null
        }

    private fun isTerminalServerCode(code: String?): Boolean =
        code == REVOKED_CODE ||
            code == ENROLLMENT_NOT_ACTIVE_CODE ||
            code == STUDY_STOPPED_CODE ||
            code == KILL_SWITCH_CODE ||
            code == SESSION_TERMINAL_CODE

    private fun serverTerminalReason(code: String?): StudyBlockReason =
        when (code) {
            REVOKED_CODE, ENROLLMENT_NOT_ACTIVE_CODE, STUDY_STOPPED_CODE -> StudyBlockReason.REVOKED
            SESSION_TERMINAL_CODE -> StudyBlockReason.SESSION_ENDED
            else -> StudyBlockReason.REVOKED
        }

    /**
     * Apply an authoritative terminal answer from the server. The runtime stops
     * and the local session is made terminal (where the state allows) so a later
     * re-activation inside the resume grace cannot resurrect it.
     */
    private fun handleServerTerminal(
        reason: StudyBlockReason,
        detail: String?,
    ) {
        when (reason) {
            StudyBlockReason.REVOKED -> onRevoked()
            StudyBlockReason.SESSION_ENDED -> endSessionFromServer(detail)
            else -> {
                markBlocked(reason, detail ?: "the research server blocked this session")
                stopMaintenance()
            }
        }
    }

    private fun endSessionFromServer(detail: String?) {
        val current = session
        if (current != null && !current.isTerminal) {
            val now = clock()
            val currentMachine = machine
            val ended =
                if (currentMachine != null && currentMachine.canTransition(current.state, SessionState.ENDED)) {
                    currentMachine.end(current, now)
                } else {
                    current.copy(
                        state = SessionState.ENDED,
                        closedAtEpochMs = now,
                        closeReason = SessionTerminalReason.EXPLICIT_COMPLETION,
                    )
                }
            session = ended
            sessionStore.save(ended)
        }
        markBlocked(StudyBlockReason.SESSION_ENDED, detail ?: "the research server ended the session")
        stopMaintenance()
    }

    /** Typed result of a capability refresh attempt; never thrown. */
    private sealed interface CapabilityRefresh {
        data class Refreshed(val manifest: BootstrapManifest) : CapabilityRefresh

        object NotNeeded : CapabilityRefresh

        data class Blocked(val reason: StudyBlockReason, val detail: String?) : CapabilityRefresh

        data class Retryable(val detail: String?) : CapabilityRefresh
    }

    /** Typed result of one heartbeat request; never thrown. */
    private sealed interface SessionHeartbeat {
        data class Ok(val heartbeatSeconds: Long?, val sessionState: String?) : SessionHeartbeat

        data class ExpiredCapability(val detail: String?) : SessionHeartbeat

        data class Terminal(val reason: StudyBlockReason, val detail: String?) : SessionHeartbeat

        data class Retryable(val detail: String?) : SessionHeartbeat
    }

    private data class SessionHttpResponse(val code: Int, val body: String)

    private data class SessionResolution(
        val session: ResearchSession?,
        val resumed: Boolean,
        val blockedReason: StudyBlockReason? = null,
        val blockedDetail: String? = null,
    )

    private fun resolveSession(
        enrollmentId: String,
        machine: SessionStateMachine,
        now: Long,
    ): SessionResolution {
        val stored = sessionStore.load(sessionKey(enrollmentId)) ?: return newSession(enrollmentId, resumed = false)
        if (stored.state == SessionState.REVOKED) {
            return SessionResolution(
                session = null,
                resumed = false,
                blockedReason = StudyBlockReason.REVOKED,
                blockedDetail = "enrollment was revoked; a new consent/revision is required",
            )
        }
        if (stored.isTerminal) {
            // A previously ended session is never merged with later activity.
            return newSession(enrollmentId, resumed = false)
        }
        if (stored.state == SessionState.NOT_STARTED) {
            // Activated but no qualifying activity yet: reopen the same session.
            return SessionResolution(session = stored, resumed = true)
        }
        if (stored.state == SessionState.SUSPENDED) {
            val resume = machine.resume(stored, now)
            return if (resume.reopened) {
                SessionResolution(session = resume.session, resumed = true)
            } else {
                newSession(enrollmentId, resumed = false)
            }
        }
        // RUNNING/OFFLINE without a clean suspend: a crash/restart inside grace.
        val lastActivity = stored.lastActivityEpochMs ?: stored.openedAtEpochMs
        val withinGrace = lastActivity != null && now - lastActivity <= machine.resumeGraceMs
        return if (withinGrace) {
            SessionResolution(
                session = stored.copy(resumeGeneration = stored.resumeGeneration + 1),
                resumed = true,
            )
        } else {
            newSession(enrollmentId, resumed = false)
        }
    }

    private fun newSession(
        enrollmentId: String,
        resumed: Boolean,
    ): SessionResolution =
        SessionResolution(
            session =
                ResearchSession(
                    sessionId = sessionIdFactory(),
                    enrollmentId = enrollmentId,
                    contextId = contextId,
                ),
            resumed = resumed,
        )

    private fun startCollector(
        resolvedSession: ResearchSession,
        validManifest: BootstrapManifest,
    ) {
        val ideSource = source ?: return
        val created =
            collectorFactory(ideSource, CanonicalEventSink { event -> onCanonicalEvent(event) })
        // Activate before subscribing so a source lifecycle signal emitted while
        // subscribing (project.opened) is already attributable to this session.
        created.activate(
            IdeCollectionScope(
                researchSessionId = resolvedSession.sessionId,
                studyId = validManifest.studyId,
                assignmentId = validManifest.assignment.assignmentId,
                profileDigest = validManifest.assignment.profileDigest,
                enrollmentId = validManifest.enrollmentId,
                manifestDigest = validManifest.manifestDigest,
            ),
        )
        created.start()
        collector = created
    }

    /**
     * Sink boundary: privacy-filter first, then durably spool, then advance the
     * session. Any failure is swallowed so an IDE listener never sees a research
     * exception.
     */
    private fun onCanonicalEvent(event: CanonicalEvent) {
        if (!active || stopped) return
        synchronized(lock) {
            val current = session ?: return
            if (current.isTerminal) return
            val filtered =
                try {
                    privacyFilter.filter(event.payload)
                } catch (_: Exception) {
                    return
                }
            if (filtered.blocked) return
            val sanitized =
                event.copy(
                    payload = filtered.sanitizedPayload,
                    privacy =
                        event.privacy.copy(
                            policyDigest = activePrivacyPolicy.policyDigest,
                            actions = filtered.actionsCounts.entries.associate { it.key.value to it.value.toLong() },
                            redactedFields = filtered.redactedPaths,
                            fieldClasses = filtered.fieldClassCounts.entries.associate { it.key.value to it.value.toLong() },
                        ),
                )
            val targetSpool = spool ?: return
            try {
                targetSpool.append(sanitized)
            } catch (_: Exception) {
                return
            }
            // Only documented IDE activity qualifies as session activity; a
            // preserved-for-review lifecycle event is spooled but does not start
            // or refresh the session.
            if (!isQualifyingActivity(event)) return
            val now = clock()
            val currentMachine = machine
            val advanced =
                when {
                    current.state == SessionState.NOT_STARTED ->
                        currentMachine?.onQualifyingActivity(current, now) ?: current
                    current.state == SessionState.SUSPENDED -> current
                    else -> currentMachine?.recordActivity(current, now) ?: current
                }
            session = advanced
            sessionStore.save(advanced)
        }
    }

    /**
     * Tear down every session-owned runtime resource. Idempotent and
     * exception-safe: a research teardown failure must never affect ordinary
     * plugin behaviour. The uploader and IPC server are closed first so no new
     * delivery/append can race the ACP unregistration and capability cleanup.
     *
     * The ACP entry is removed unconditionally, not only when this instance
     * registered it: a stale entry left by a crashed or earlier instance would
     * otherwise outlive its capability. Entry and capability stay atomic —
     * active => both present, inactive => both absent. The capability also
     * travels in the entry env, so an unregistration that fails cannot strand a
     * launch.
     */
    private fun deactivateRuntime() {
        stopMaintenance()
        val currentCollector = collector
        collector = null
        val currentProxy = proxyHandle
        proxyHandle = null
        val currentUploader = uploader
        uploader = null
        val currentIpc = ipcServer
        ipcServer = null
        runCatching { currentCollector?.deactivate() }
        runCatching { currentCollector?.stop() }
        runCatching { currentProxy?.stop() }
        runCatching { currentUploader?.close() }
        runCatching { currentIpc?.close() }
        runCatching { acpHostRegistration?.unregister() }
        val staged = capabilityFile
        capabilityFile = null
        AcpHostRegistration.cleanupCapabilityFile(staged)
    }

    /** Participant-safe delivery posture derived from the live uploader state. */
    private fun currentDeliveryState(): SpoolDeliveryState {
        val current = uploader ?: return SpoolDeliveryState.UNAVAILABLE
        val snapshot =
            try {
                current.state()
            } catch (_: Exception) {
                return SpoolDeliveryState.UNAVAILABLE
            }
        return when {
            snapshot.revoked -> SpoolDeliveryState.REVOKED
            snapshot.spoolFull -> SpoolDeliveryState.SPOOL_FULL
            snapshot.nextAttemptAtEpochMs != null -> SpoolDeliveryState.RECOVERING
            else -> SpoolDeliveryState.ACTIVE
        }
    }

    /** Typed runtime setup outcome; a failure blocks activation. */
    private sealed interface RuntimeSetup {
        /** No resolver injected: runtime resolution is not part of this build (tests). */
        object Skipped : RuntimeSetup

        /** The packaged runtime resolved and the ACP entry was registered. */
        data class Ready(val capabilityFile: Path?) : RuntimeSetup

        /**
         * Resolution or registration failed; activation must be blocked with the
         * typed [reason] ([StudyBlockReason.RUNTIME_UNAVAILABLE] for a packaged
         * runtime failure, [StudyBlockReason.AGENT_NOT_FOUND] for a BYOA agent
         * that is not installed).
         */
        data class Failed(val reason: StudyBlockReason, val detail: String) : RuntimeSetup
    }

    /** Logs a typed proxy-runtime setup failure before it blocks activation. */
    private fun runtimeSetupFailure(
        detail: String,
        reason: StudyBlockReason = StudyBlockReason.RUNTIME_UNAVAILABLE,
    ): RuntimeSetup {
        log.warn("Research proxy runtime setup failed (reason=${reason.value}): $detail")
        return RuntimeSetup.Failed(reason, detail)
    }

    /**
     * Resolve the packaged proxy runtime and register it as the ACP agent entry.
     *
     * Fail-closed: any typed resolution failure, an agent whose digest does not
     * match the bootstrap manifest's pinned `agent_release.artifact_digest`, or an
     * ACP registration failure yields [RuntimeSetup.Failed] so no launch happens.
     * When no resolver is injected (pure tests / builds without a packaged
     * runtime), setup is skipped so existing behaviour is preserved.
     *
     * The registered entry points the proxy at [ipc]'s loopback endpoint and hands
     * it [SpoolIpcServer.capability] (the LOCAL IPC capability), never the signed
     * server session capability.
     *
     * @param validManifest the validated bootstrap manifest whose pinned artifact
     * digest the resolved agent must match.
     */
    private fun prepareProxyRuntime(
        ipc: SpoolIpcServer?,
        validManifest: BootstrapManifest,
    ): RuntimeSetup {
        val resolver = proxyRuntimeResolver ?: return RuntimeSetup.Skipped
        val resolution =
            try {
                resolver.resolve()
            } catch (exception: Exception) {
                return runtimeSetupFailure(exception.message ?: "the packaged proxy runtime could not be resolved")
            }
        return when (resolution) {
            is ProxyRuntimeResolution.Failed ->
                runtimeSetupFailure("${resolution.error.code}: ${resolution.error.message}")
            is ProxyRuntimeResolution.Resolved -> {
                val registration = acpHostRegistration ?: return RuntimeSetup.Skipped
                val runtime = resolution.runtime
                // The agent contract depends on how the pinned release is
                // distributed: PACKAGED is content-addressed against the plugin
                // artifact (never PATH); BYOA_EXTERNAL is a participant-installed
                // executable resolved from release metadata / settings.
                val agentPlan =
                    when (validManifest.agentRelease.distributionMode) {
                        AgentDistributionMode.PACKAGED -> packagedAgentPlan(runtime, validManifest)
                        AgentDistributionMode.BYOA_EXTERNAL -> byoaAgentPlan(validManifest)
                    }
                val ready =
                    when (agentPlan) {
                        is AgentPlan.Failed -> return runtimeSetupFailure(agentPlan.detail, agentPlan.reason)
                        is AgentPlan.Ready -> agentPlan
                    }
                val spoolEndpoint = ipc?.endpointUrl
                val capability = if (spoolEndpoint != null) capabilityFileFor(validManifest.enrollmentId) else null
                if (spoolEndpoint != null && capability == null) {
                    return runtimeSetupFailure("the one-time capability file could not be created")
                }
                val environment =
                    mapOf(
                        "CODE4ME_RESEARCH_PROXY" to runtime.proxyDigest,
                    )
                val result =
                    try {
                        registration.register(
                            resolved = runtime,
                            agentArgv = ready.argv,
                            spoolEndpoint = spoolEndpoint,
                            capabilityFile = capability,
                            env = environment + agentEnvProvider(),
                            agentDigest = ready.digest,
                            capabilityValue = ipc?.capability,
                        )
                    } catch (exception: Exception) {
                        Result.failure(exception)
                    }
                if (result.isFailure) {
                    AcpHostRegistration.cleanupCapabilityFile(capability)
                    runtimeSetupFailure(
                        result.exceptionOrNull()?.message ?: "the research proxy ACP entry could not be registered",
                    )
                } else {
                    RuntimeSetup.Ready(capability)
                }
            }
        }
    }

    /**
     * PACKAGED agent contract: the runtime's bundled agent must be present and
     * its digest must equal the bootstrap manifest's pinned artifact digest. A
     * missing agent or a digest mismatch is terminal; PATH is never consulted.
     */
    private fun packagedAgentPlan(
        runtime: ResolvedProxyRuntime,
        validManifest: BootstrapManifest,
    ): AgentPlan {
        val release = validManifest.agentRelease
        // The manifest pin: the exact artifact digest the server assigned to this
        // participant's os/arch. `sha256:` is display convention.
        val pinnedAgentDigest = release.normalizedArtifactDigest
        val resolvedAgentDigest = normalizeSha256Hex(runtime.agentDigest)
        if (runtime.agentArgv.isNullOrEmpty()) {
            if (!runtime.development) {
                return AgentPlan.Failed(
                    StudyBlockReason.RUNTIME_UNAVAILABLE,
                    "the packaged runtime declares no agent; refusing to launch without a digest-pinned agent",
                )
            }
            return AgentPlan.Ready(argv = null, digest = null)
        }
        if (pinnedAgentDigest == null) {
            return AgentPlan.Failed(
                StudyBlockReason.RUNTIME_UNAVAILABLE,
                "the bootstrap manifest declares no usable agent artifact digest; " +
                    "refusing to launch an unpinned agent",
            )
        }
        if (resolvedAgentDigest == null) {
            return AgentPlan.Failed(
                StudyBlockReason.RUNTIME_UNAVAILABLE,
                "the resolved agent digest is not a 64-hex sha256; refusing to launch",
            )
        }
        if (resolvedAgentDigest != pinnedAgentDigest) {
            return AgentPlan.Failed(
                StudyBlockReason.RUNTIME_UNAVAILABLE,
                "the resolved agent digest does not match the bootstrap manifest pin " +
                    "(manifest=$pinnedAgentDigest, resolved=$resolvedAgentDigest); refusing to launch",
            )
        }
        return AgentPlan.Ready(
            argv = runtime.agentArgv,
            digest = pinnedAgentDigest,
        )
    }

    /**
     * BYOA agent contract: resolve the participant-installed executable from the
     * release metadata, a configured settings command, or the documented
     * discovery order. A missing agent blocks with `AGENT_NOT_FOUND`; there is no
     * silent fallback to another executable.
     */
    private fun byoaAgentPlan(validManifest: BootstrapManifest): AgentPlan {
        val release = validManifest.agentRelease
        val configured = safeValue { byoaAgentCommandProvider() }
        val resolution =
            try {
                byoaAgentResolver.resolve(
                    ByoaAgentSpec(
                        command = release.agentCommand,
                        commandArgs = release.agentCommandArgs,
                        agentPackage = release.agentPackage,
                        configuredCommand = configured,
                    ),
                )
            } catch (exception: Exception) {
                return AgentPlan.Failed(
                    StudyBlockReason.AGENT_NOT_FOUND,
                    exception.message ?: "the BYOA agent could not be resolved",
                )
            }
        return when (resolution) {
            is ByoaAgentResolution.NotFound ->
                AgentPlan.Failed(StudyBlockReason.AGENT_NOT_FOUND, resolution.detail)
            is ByoaAgentResolution.Resolved ->
                AgentPlan.Ready(
                    argv = resolution.argv,
                    digest = resolution.identity.digest,
                )
        }
    }

    /** One typed agent-resolution outcome for a resolved proxy runtime. */
    private sealed interface AgentPlan {
        data class Ready(
            val argv: List<String>?,
            val digest: String?,
        ) : AgentPlan

        data class Failed(val reason: StudyBlockReason, val detail: String) : AgentPlan
    }

    /**
     * Resolve the stable capability file for [enrollmentId].
     *
     * A configured provider path wins. Otherwise the path is **stable per
     * enrollment** under the research root, never a fresh temp file, because the
     * ACP entry is persistent and the AI Assistant may launch the proxy
     * repeatedly: every launch must read the same file the plugin rewrote on
     * activation. The plugin owns the file and deletes it only on teardown.
     */
    private fun capabilityFileFor(enrollmentId: String): Path? =
        try {
            capabilityFilePathProvider()?.toAbsolutePath()?.normalize()
                ?: capabilityRootProvider()
                    .resolve("$CAPABILITY_FILE_PREFIX${opaqueSessionKey(enrollmentId)}$CAPABILITY_FILE_EXTENSION")
        } catch (_: Exception) {
            null
        }

    private fun safeValue(read: () -> String?): String? =
        try {
            read()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

    /**
     * Best-effort membership check. `null` means "no discovery configured" or
     * "discovery failed": the caller proceeds to the bootstrap API, which
     * stays authoritative. Never throws.
     */
    private fun safeDiscovery(): EnrollmentDiscovery? {
        val provider = enrollmentDiscoveryProvider ?: return null
        return try {
            provider()
        } catch (_: Exception) {
            null
        }
    }

    private fun onSessionEnded() {
        deactivateRuntime()
        synchronized(lock) { active = false }
    }

    private fun markBlocked(
        reason: StudyBlockReason,
        detail: String?,
    ) {
        log.warn("Research session blocked (reason=${reason.value}): ${detail ?: "no detail"}")
        deactivateRuntime()
        synchronized(lock) {
            active = false
            consentState =
                when (reason) {
                    StudyBlockReason.REVOKED -> StudyComponentState.BLOCKED
                    else -> StudyComponentState.UNAVAILABLE
                }
            compatibilityState =
                when (reason) {
                    StudyBlockReason.MANIFEST_EXPIRED,
                    StudyBlockReason.MANIFEST_INVALID,
                    StudyBlockReason.INCOMPATIBLE_ENVIRONMENT,
                    -> StudyComponentState.BLOCKED
                    else -> StudyComponentState.UNAVAILABLE
                }
            blockReason = reason
            blockReasonDetail = detail
        }
    }

    private fun markFailed(
        reason: StudyBlockReason,
        detail: String?,
    ) {
        log.warn("Research session failed (reason=${reason.value}): ${detail ?: "no detail"}")
        deactivateRuntime()
        synchronized(lock) {
            active = false
            compatibilityState = StudyComponentState.FAILED
            blockReason = reason
            blockReasonDetail = detail
        }
    }

    private fun flushSpool(): SpoolStats? =
        spool?.let { targetSpool ->
            try {
                targetSpool.pending(BOUNDED_FLUSH_RECORDS)
                targetSpool.stats()
            } catch (_: Exception) {
                null
            }
        }

    /** True only for the documented IDE activity types that may start/refresh a session. */
    private fun isQualifyingActivity(event: CanonicalEvent): Boolean =
        event.unknownEventType == null && event.eventType in QUALIFYING_EVENT_TYPES

    private fun sessionComponentOf(state: SessionState?): StudyComponentState =
        when (state) {
            null -> StudyComponentState.UNAVAILABLE
            SessionState.NOT_STARTED -> StudyComponentState.UNAVAILABLE
            SessionState.RUNNING, SessionState.OFFLINE -> StudyComponentState.AVAILABLE
            SessionState.SUSPENDED -> StudyComponentState.PAUSED
            SessionState.ENDED -> StudyComponentState.UNAVAILABLE
            SessionState.REVOKED -> StudyComponentState.BLOCKED
        }

    private fun policyFor(validManifest: BootstrapManifest): ResearchSessionPolicy {
        val sessionPolicy = validManifest.policies.session
        val resumeGraceMs =
            sessionPolicy?.resumeGraceSeconds?.takeIf { it > 0 }?.times(SECONDS_TO_MILLIS) ?: defaultResumeGraceMs
        val idleTimeoutMs =
            sessionPolicy?.idleTimeoutSeconds?.takeIf { it > 0 }?.times(SECONDS_TO_MILLIS) ?: defaultIdleTimeoutMs
        return ResearchSessionPolicy(resumeGraceMs, idleTimeoutMs)
    }

    private fun privacyPolicyFor(validManifest: BootstrapManifest): PrivacyPolicy {
        val declared =
            validManifest.policies.telemetry?.allowedFieldClasses.orEmpty()
                .mapNotNull { FieldClass.fromWire(it) }
                .toSet()
        val allowed = if (declared.isEmpty()) DEFAULT_ALLOWED_FIELD_CLASSES else declared - FieldClass.SECRET
        return PrivacyPolicy(
            allowedFieldClasses = allowed,
            contentAllowed = validManifest.policies.telemetry?.contentCapture ?: false,
            codeMetadataMode =
                if (FieldClass.CODE_METADATA in allowed) CodeMetadataMode.ALLOW else CodeMetadataMode.HASH,
        )
    }

    private fun blockReasonFor(result: BootstrapResult): StudyBlockReason {
        val validationReason = result.validationReason
        if (validationReason != null) {
            return when (validationReason) {
                ManifestValidationReason.EXPIRED, ManifestValidationReason.NEAR_EXPIRY -> StudyBlockReason.MANIFEST_EXPIRED
                ManifestValidationReason.INCOMPATIBLE_PLUGIN,
                ManifestValidationReason.SCHEMA_VERSION_UNSUPPORTED,
                -> StudyBlockReason.INCOMPATIBLE_ENVIRONMENT
                ManifestValidationReason.SECRET_DETECTED,
                ManifestValidationReason.ABSOLUTE_PATH_DETECTED,
                ManifestValidationReason.DIGEST_MISMATCH,
                ManifestValidationReason.INVALID_ARTIFACT_DIGEST,
                ManifestValidationReason.MALFORMED,
                ManifestValidationReason.NOT_YET_VALID,
                ManifestValidationReason.WRONG_AUDIENCE,
                ManifestValidationReason.SCOPE_MISSING,
                ManifestValidationReason.OK,
                -> StudyBlockReason.MANIFEST_INVALID
            }
        }
        // No manifest was validated: the transport block is classified by the
        // server's typed rejection, never collapsed to a generic WITHDRAWN.
        return when (result.rejection) {
            BootstrapRejection.COMPATIBILITY_MISSING,
            BootstrapRejection.INCOMPATIBLE_ENVIRONMENT,
            -> StudyBlockReason.INCOMPATIBLE_ENVIRONMENT
            BootstrapRejection.REVOKED,
            BootstrapRejection.CAPABILITY_INVALID,
            -> StudyBlockReason.REVOKED
            BootstrapRejection.RELEASE_NOT_FOUND,
            BootstrapRejection.RELEASE_NOT_QUALIFIED,
            BootstrapRejection.ARTIFACT_UNAVAILABLE,
            BootstrapRejection.ARTIFACT_MISMATCH,
            -> StudyBlockReason.RUNTIME_UNAVAILABLE
            BootstrapRejection.NOT_AUTHENTICATED,
            BootstrapRejection.NOT_PERMITTED,
            BootstrapRejection.SIGNING_SECRET_MISSING,
            -> StudyBlockReason.UNKNOWN
            BootstrapRejection.ENROLLMENT_NOT_FOUND,
            BootstrapRejection.ENROLLMENT_NOT_ACTIVE,
            BootstrapRejection.INELIGIBLE,
            BootstrapRejection.STUDY_NOT_OPEN,
            BootstrapRejection.STUDY_CLOSED,
            BootstrapRejection.STUDY_STOPPED,
            BootstrapRejection.STUDY_MISMATCH,
            BootstrapRejection.ASSIGNMENT_MISMATCH,
            BootstrapRejection.KILL_SWITCH_ENGAGED,
            BootstrapRejection.UNKNOWN,
            null,
            -> StudyBlockReason.REVOKED
        }
    }

    companion object {
        const val DEFAULT_RESUME_GRACE_MS: Long = 120_000L
        const val DEFAULT_IDLE_TIMEOUT_MS: Long = 600_000L

        /**
         * How long before the session capability expires the plugin re-bootstraps.
         * A capability TTL is short (900 s server-side), so this must be a
         * comfortable fraction of it: the periodic loop has several ticks to
         * retry a transient bootstrap failure before uploads would 401.
         */
        val DEFAULT_CAPABILITY_REFRESH_MARGIN: Duration = Duration.ofSeconds(120)

        /** Last-resort heartbeat cadence when the server declares none. */
        private const val DEFAULT_HEARTBEAT_SECONDS: Long = 30L

        private const val MIN_HEARTBEAT_PERIOD_MS: Long = 1_000L
        private const val SECONDS_TO_MILLIS = 1_000L
        private const val BOUNDED_FLUSH_RECORDS = 1_000

        private const val SESSIONS_PATH = "/api/research/sessions/"
        private const val HEARTBEAT_PATH = "/api/research/sessions/heartbeat"

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_CONFLICT = 409

        private const val REVOKED_CODE = "REVOKED"
        private const val ENROLLMENT_NOT_ACTIVE_CODE = "ENROLLMENT_NOT_ACTIVE"
        private const val STUDY_STOPPED_CODE = "STUDY_STOPPED"
        private const val KILL_SWITCH_CODE = "KILL_SWITCH_ENGAGED"
        private const val SESSION_TERMINAL_CODE = "SESSION_TERMINAL"

        private const val SERVER_STATE_ENDED = "ended"
        private const val SERVER_STATE_REVOKED = "revoked"

        /** Stable, plugin-owned capability file name under the research root. */
        private const val CAPABILITY_FILE_PREFIX = "capability-"
        private const val CAPABILITY_FILE_EXTENSION = ".txt"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val DEFAULT_ALLOWED_FIELD_CLASSES: Set<FieldClass> =
            setOf(FieldClass.SYSTEM, FieldClass.BEHAVIORAL, FieldClass.CODE_METADATA)

        private val QUALIFYING_EVENT_TYPES: Set<String> =
            setOf(
                IdeActivityType.FILE_OPENED.canonicalType,
                IdeActivityType.FILE_SAVED.canonicalType,
                IdeActivityType.DOCUMENT_CHANGED.canonicalType,
                IdeActivityType.FILE_CLOSED.canonicalType,
                IdeActivityType.RUN_EXECUTED.canonicalType,
            )

        /** Non-reversible spool directory key; never the raw enrollment id. */
        fun opaqueSpoolKey(enrollmentId: String): String = "spool-" + sha256Hex("code4me.research.spool.v1:$enrollmentId").take(32)

        /**
         * Stable, opaque execution-context id for a project/window: a hash of the
         * project key, never the raw path. Two windows of one project share it
         * (idempotent session); different projects get different contexts.
         */
        fun opaqueContextId(projectKey: String): String = "ctx-" + sha256Hex("code4me.research.context.v1:$projectKey").take(32)

        /**
         * Durable session-store key: one document per (enrollment, execution
         * context) so two windows never share or overwrite session state. A blank
         * context keeps the legacy per-enrollment key.
         */
        fun sessionStoreKey(enrollmentId: String?, contextId: String?): String? {
            if (enrollmentId == null) return null
            return if (contextId.isNullOrBlank()) enrollmentId else "$enrollmentId::$contextId"
        }

        /** Non-reversible durable session-file key; never the raw enrollment id. */
        fun opaqueSessionKey(enrollmentId: String): String = sha256Hex("code4me.research.session.v1:$enrollmentId").take(32)

        /**
         * Stable, non-identifying `client_instance_id` for [enrollmentId]. It is
         * derived (never random) so a plugin restart uploads under the same
         * client instance.
         */
        fun opaqueClientInstanceId(enrollmentId: String): String =
            "client-" + sha256Hex("code4me.research.client.v1:$enrollmentId").take(32)

        /** Process-local default spool root used when no provider is injected. */
        fun defaultSpoolRoot(): Path = Path.of(System.getProperty("java.io.tmpdir"), "code4me", "research")

        /** Shared default HTTP client for uploads when none is injected. */
        private val defaultHttpClient: Call.Factory by lazy { OkHttpClient() }

        private fun defaultUploader(context: SpoolUploaderContext): SpoolDelivery =
            SpoolUploader(
                spool = context.spool,
                serverBaseUrl = context.serverBaseUrl,
                sessionCapability = context.sessionCapability,
                clientInstanceId = context.clientInstanceId,
                httpClient = context.httpClient,
            )
    }
}
