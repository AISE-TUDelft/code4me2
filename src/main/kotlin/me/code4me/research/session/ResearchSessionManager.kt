package me.code4me.research.session

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
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
import me.code4me.research.bootstrap.parseInstant
import me.code4me.research.telemetry.CanonicalEvent
import me.code4me.research.telemetry.FieldClass
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.parseCanonicalJson
import me.code4me.research.bootstrap.AgentPreparation
import me.code4me.research.bootstrap.BootstrapTransportResult
import me.code4me.research.telemetry.sha256Hex
import me.code4me.research.ide.CanonicalEventSink
import me.code4me.research.ide.IdeActivityCollector
import me.code4me.research.ide.IdeActivitySource
import me.code4me.research.ide.IdeActivityType
import me.code4me.research.ide.IdeCollectionScope
import me.code4me.research.lifecycle.HostPreflightResult
import me.code4me.research.telemetry.CodeMetadataMode
import me.code4me.research.telemetry.PrivacyFilter
import me.code4me.research.telemetry.PrivacyPolicy
import me.code4me.research.bootstrap.AgentDistributionMode
import me.code4me.research.bootstrap.AgentReleaseRef
import me.code4me.research.bootstrap.InferenceGatewayRef
import me.code4me.research.proxy.AcpHostRegistration
import me.code4me.research.proxy.ByoaAgentResolution
import me.code4me.research.proxy.ByoaAgentResolver
import me.code4me.research.proxy.ByoaAgentSpec
import me.code4me.research.proxy.ByoaRuntimeValues
import me.code4me.research.proxy.PackagedAgentInstall
import me.code4me.research.proxy.PackagedAgentInstaller
import me.code4me.research.proxy.ProxyRuntimeResolution
import me.code4me.research.proxy.ProxyRuntimeResolver
import me.code4me.research.proxy.ResolvedProxyRuntime
import me.code4me.research.proxy.applyByoaConfiguration
import me.code4me.research.proxy.credentialBindingViolation
import me.code4me.research.proxy.missingByoaBindings
import me.code4me.research.proxy.writeFrozenTelemetryPolicy
import me.code4me.research.proxy.writeInferenceCredentialFile
import me.code4me.research.spool.DurableSpool
import me.code4me.research.spool.ResearchSpoolIpcServer
import me.code4me.research.spool.SpoolEventContext
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
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.time.Instant
import java.util.UUID

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
 * It owns one project's manifest/session/spool/collector/ACP-registration
 * lifecycle and composes the existing research core:
 * [BootstrapClient], [SessionStateMachine]/[ResearchSession]/[AgentRun],
 * [DurableSpool], [IdeActivityCollector], and
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
    private val proxyRuntimeResolver: ProxyRuntimeResolver? = null,
    private val acpHostRegistration: AcpHostRegistration? = null,
    /**
     * Installs the single packaged agent artifact for a PACKAGED distribution.
     * The production default installs the assignment's pinned archive (verified
     * cache or exact release download) through the shared managed-runtime
     * installer; tests inject a fake.
     */
    private val packagedAgentInstaller: PackagedAgentInstaller = PackagedAgentInstaller.PRODUCTION,
    private val agentEnvProvider: () -> Map<String, String> = { emptyMap() },
    private val capabilityFilePathProvider: () -> Path? = { null },
    private val capabilityRootProvider: () -> Path = { defaultResearchSessionRoot() },
    /**
     * Optional override for the proxy's content-free delivery status document.
     * When absent, a stable per-enrollment path beside the frozen policy file is
     * used (see [statusFileFor]).
     */
    private val statusFilePathProvider: () -> Path? = { null },
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
        { ideSource, sink ->
            // One emitter per activation, like each proxy launch: the collector's
            // sequences restart at 1, and reusing the previous activation's emitter
            // id within the same research session made the server reject the new
            // events as integrity conflicts.
            val activation = UUID.randomUUID().toString().replace("-", "").take(8)
            IdeActivityCollector(ideSource, sink, emitterIdFactory = { contextId -> "ide:$contextId:$activation" })
        },
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
    /**
     * Where IDE events are processed (privacy filter, durable spool append and
     * session bookkeeping). The IDE service passes one background thread so the
     * UI thread never waits on the manager lock, which activation holds across
     * network calls, nor on spool I/O. `null` processes inline (tests).
     */
    private val eventExecutor: java.util.concurrent.Executor? = null,
    /**
     * Host preflight (A-03) run on every activation after membership discovery
     * and before any bootstrap request: JetBrains AI Assistant must be present,
     * enabled and new enough to read the ACP registry, otherwise the study
     * would report "active" while nothing could be observed. `null` skips the
     * check (pure tests and builds without the IDE).
     */
    private val hostPreflightProvider: (() -> HostPreflightResult)? = null,
    /**
     * Renews the backend sign-in when a capability refresh or session rotation
     * is refused as `NOT_AUTHENTICATED`/`NOT_PERMITTED` (the session cookie
     * expires long before the login token). `true` means a session was acquired
     * and the bootstrap may be retried in the same tick. `null` disables the
     * in-tick retry; the refusal then stays retryable on later ticks.
     */
    private val reauthenticate: (() -> Boolean)? = null,
    /**
     * How long refresh authentication may keep failing *after the held
     * capability expired* before the typed `AUTHENTICATION_REQUIRED` block is
     * shown. The runtime stays registered either way.
     */
    private val authRetryBlockAfterMs: Long = AUTH_RETRY_BLOCK_AFTER_MS,
) {
    init {
        require(projectKey.isNotBlank()) { "projectKey must not be blank" }
        require(defaultResumeGraceMs > 0) { "defaultResumeGraceMs must be positive" }
        require(defaultIdleTimeoutMs > 0) { "defaultIdleTimeoutMs must be positive" }
        require(authRetryBlockAfterMs >= 0) { "authRetryBlockAfterMs must not be negative" }
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

    @Volatile private var capabilityFile: Path? = null

    /**
     * The proxy-owned, content-free delivery status document for this window, or
     * `null` when no proxy runtime was registered. It is read (never fabricated)
     * to surface local telemetry loss in [state].
     */
    @Volatile private var statusFile: Path? = null

    /**
     * The plugin-owned, owner-only inference credential file (and the env key
     * it names) for a gateway-bound agent, or `null` when the arm does not use
     * the research inference gateway. It is rewritten on every manifest
     * refresh and deleted on teardown; the ACP entry only carries its path.
     */
    @Volatile private var inferenceCredential: InferenceCredentialHandle? = null

    /**
     * The advisory AI budget the server last reported on a session response.
     * Held only while the runtime is active (cleared on teardown); it never
     * blocks anything, it only drives the status surface.
     */
    @Volatile private var inferenceBudget: InferenceBudgetState? = null

    @Volatile private var ipcServer: SpoolIpcServer? = null

    @Volatile private var uploader: SpoolDelivery? = null

    @Volatile private var maintenanceHandle: MaintenanceHandle? = null

    @Volatile private var scheduledHeartbeatMs: Long? = null

    /**
     * True when a real qualifying activity event was recorded locally since the
     * last activity report. Only the explicit activity channel consumes it;
     * periodic liveness heartbeats never set it (ISSUE-06).
     */
    @Volatile private var pendingActivityReport = false

    @Volatile private var activePrivacyPolicy: PrivacyPolicy = PrivacyPolicy.default()

    @Volatile private var privacyFilter: PrivacyFilter = PrivacyFilter(PrivacyPolicy.default())

    @Volatile private var consentState: StudyComponentState = StudyComponentState.UNAVAILABLE

    @Volatile private var compatibilityState: StudyComponentState = StudyComponentState.UNAVAILABLE

    @Volatile private var blockReason: StudyBlockReason? = null

    @Volatile private var blockReasonDetail: String? = null

    /**
     * A transient, self-clearing condition that pauses collection *without*
     * tearing the runtime down: a pending session rotation
     * ([StudyBlockReason.SESSION_ROTATING]), an operator kill switch
     * ([StudyBlockReason.KILL_SWITCH_ENGAGED]) or a sign-in the plugin could not
     * renew ([StudyBlockReason.AUTHENTICATION_REQUIRED]). Unlike [blockReason]
     * it never sets [active] false and clears itself on the next good answer.
     */
    @Volatile private var transientBlock: StudyBlockReason? = null

    @Volatile private var transientBlockDetail: String? = null

    /**
     * True from the moment the local session ended (idle, server end) until a
     * fresh server session is adopted. Events captured meanwhile are still
     * spooled under the ended session id; the server accepts them within its
     * grace window.
     */
    @Volatile private var rotationPending = false

    @Volatile private var pendingRotationReason: String? = null

    /** Serializes rotations: a concurrent caller sees the first one's outcome on its next tick. */
    private val rotationInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** When refresh authentication first failed, for the [authRetryBlockAfterMs] timer. */
    @Volatile private var authFailureSinceMs: Long? = null

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
     * The resolved privacy policy for the active session, exposed for
     * diagnostics and tests. It reflects the most recent manifest refresh
     * (D-1/TC-05) and never carries content.
     */
    val currentPrivacyPolicy: PrivacyPolicy
        get() = activePrivacyPolicy

    /** The in-process filter derived from [currentPrivacyPolicy]; diagnostics/tests only. */
    internal val currentPrivacyFilter: PrivacyFilter
        get() = privacyFilter

    /**
     * The participant-visible study state. Never exposes content, paths, or
     * credentials; blocked states always carry a typed reason.
     */
    fun state(): ParticipantStudyStateV1 {
        val current = session
        val held = manifest
        val terminalBlock = blockReason
        val transient = transientBlock
        val effectiveBlock =
            when {
                terminalBlock != null -> terminalBlock
                // A pending rotation outranks the ended session it is replacing:
                // the participant sees "reconnecting", never "ended".
                transient != null -> transient
                current?.state == SessionState.ENDED -> StudyBlockReason.SESSION_ENDED
                current?.state == SessionState.REVOKED -> StudyBlockReason.REVOKED
                else -> null
            }
        val effectiveDetail =
            when {
                terminalBlock != null -> blockReasonDetail
                transient != null -> transientBlockDetail
                else -> blockReasonDetail
            }
        val delivery = currentDeliveryState()
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
            blockReasonDetail = effectiveDetail,
            deliveryState = delivery.state,
            droppedTelemetryCount = delivery.droppedTelemetryCount,
            discardedEventCount = delivery.discardedEventCount,
            inferenceBudget = inferenceBudget,
        )
    }

    /**
     * Request, validate, and (only on success) start a research session.
     *
     * A blocked, invalid, or expired manifest produces a typed [Blocked] result
     * with no collector and no proxy. A transient transport failure produces
     * [Retryable]. Nothing here ever throws.
     */
    private val activationInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var preparationCancelled = false
    @Volatile private var preparationIdentity: String? = null

    fun cancelPreparation() {
        preparationCancelled = true
        markBlocked(StudyBlockReason.PREPARATION_CANCELLED, "Agent preparation cancelled. Choose Prepare agent to retry.")
    }
    fun retryPreparation() { preparationCancelled = false }

    fun activate(
        enrollmentId: String,
        isCurrent: () -> Boolean = { true },
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): ResearchActivationResult {
        if (enrollmentId.isBlank()) {
            return ResearchActivationResult.Blocked(StudyBlockReason.UNKNOWN, "enrollmentId must not be blank")
        }
        if (stopped || !isCurrent()) {
            return ResearchActivationResult.Blocked(StudyBlockReason.REVOKED, "research session manager is stopped")
        }
        // Membership first (plan 05.5 step 1): a terminal enrollment never
        // reaches the bootstrap or session endpoints.
        val discovery = safeDiscovery()
        if (discovery is EnrollmentDiscovery.Terminal) {
            val detail = "This enrollment is ${discovery.status.lowercase()}; it cannot be activated."
            markBlocked(StudyBlockReason.REVOKED, detail)
            return ResearchActivationResult.Blocked(StudyBlockReason.REVOKED, detail)
        }
        // Host preflight (A-03): without a usable AI Assistant the research entry
        // can never be launched, so block with a participant-actionable reason
        // before any bootstrap request is made.
        when (val preflight = runHostPreflight()) {
            is HostPreflightResult.Blocked -> {
                markBlocked(preflight.reason, preflight.detail)
                return ResearchActivationResult.Blocked(preflight.reason, preflight.detail)
            }
            is HostPreflightResult.Ok, null -> Unit
        }
        if (!activationInProgress.compareAndSet(false, true)) return ResearchActivationResult.Retryable("Agent preparation is already running.")
        return try {
            deactivateRuntime()
            var preparation: AgentPreparation? = null
            var preparedAgent: PackagedAgentInstall.Ready? = null
            val current = { !stopped && !preparationCancelled && isCurrent() }
            when (val response = transport.prepare(enrollmentId, contextId)) {
                is BootstrapTransportResult.Success -> {
                    preparation = AgentPreparation.parse(response.manifestJson)
                    require(preparation.enrollmentId == enrollmentId) { "The preparation belongs to another enrollment." }
                    val identity = "${preparation.assignmentId}:${preparation.profileDigest}:" +
                        preparation.release.normalizedArtifactDigest
                    if (preparationIdentity != null && preparationIdentity != identity) preparationCancelled = false
                    preparationIdentity = identity
                    preparation.release.compatibilityIssue(compatibility.pluginVersion)?.let { detail ->
                        markBlocked(StudyBlockReason.INCOMPATIBLE_ENVIRONMENT, detail)
                        return ResearchActivationResult.Blocked(StudyBlockReason.INCOMPATIBLE_ENVIRONMENT, detail)
                    }
                    if (!current()) return ResearchActivationResult.Blocked(StudyBlockReason.PREPARATION_CANCELLED, "Agent preparation cancelled. Choose Prepare agent to retry.")
                    if (!preparation.release.isByoa) {
                        markBlocked(StudyBlockReason.PREPARING_AGENT, "Preparing your study's agent.")
                        when (val install = packagedAgentInstaller.prepare(preparation.release, progress, current)) {
                            is PackagedAgentInstall.Blocked -> {
                                val reason = when {
                                    !current() -> StudyBlockReason.PREPARATION_CANCELLED
                                    install.retryable -> StudyBlockReason.RUNTIME_UNAVAILABLE
                                    else -> StudyBlockReason.PREPARATION_FAILED
                                }
                                markBlocked(reason, install.detail)
                                return ResearchActivationResult.Blocked(reason, install.detail)
                            }
                            is PackagedAgentInstall.Ready -> preparedAgent = install
                        }
                    }
                    bootstrap.invalidate(enrollmentId)
                }
                is BootstrapTransportResult.Failure -> {
                    val reason = response.rejection?.let { blockReasonFor(it) } ?: StudyBlockReason.TRANSPORT_FAILED
                    markBlocked(reason, response.message)
                    return if (response.retryable) ResearchActivationResult.Retryable(response.message)
                    else ResearchActivationResult.Blocked(reason, response.message, response.rejection)
                }
                is BootstrapTransportResult.Revoked -> {
                    val reason = blockReasonFor(response.rejection)
                    markBlocked(reason, response.reason)
                    return ResearchActivationResult.Blocked(reason, response.reason, response.rejection)
                }
                null -> Unit // In-process/legacy transports retain their existing bootstrap contract.
            }
            if (!current()) return ResearchActivationResult.Blocked(StudyBlockReason.PREPARATION_CANCELLED, "Agent preparation cancelled.")
            val result = bootstrap.acquire(enrollmentId)
            when (result.status) {
                BootstrapStatus.OK -> {
                    val validManifest = result.manifest
                    if (validManifest == null) {
                        markBlocked(StudyBlockReason.MANIFEST_INVALID, "validated manifest was not returned")
                        ResearchActivationResult.Blocked(StudyBlockReason.MANIFEST_INVALID, "validated manifest was not returned")
                    } else {
                        if (preparation != null && !preparation.matches(validManifest)) {
                            bootstrap.invalidate(enrollmentId)
                            return ResearchActivationResult.Retryable("Your study assignment changed while the agent was preparing. Preparing it again.")
                        }
                        if (!current()) return ResearchActivationResult.Blocked(StudyBlockReason.PREPARATION_CANCELLED, "Agent preparation cancelled.")
                        preparedAgent?.let { ready ->
                            packagedAgentInstaller.validate(ready)?.let { detail ->
                                markBlocked(StudyBlockReason.PREPARATION_FAILED, detail)
                                return ResearchActivationResult.Blocked(StudyBlockReason.PREPARATION_FAILED, detail)
                            }
                        }
                        // Serialize the resource-producing half of activation
                        // with stop(). If logout won while bootstrap was in
                        // flight, this old manager must never register a late
                        // proxy or restart collectors.
                        synchronized(lock) {
                            if (!current()) {
                                ResearchActivationResult.Blocked(
                                    StudyBlockReason.REVOKED,
                                    "research session manager is stopped",
                                )
                            } else {
                                startSession(
                                    enrollmentId,
                                    validManifest,
                                    discoveryActive = discovery is EnrollmentDiscovery.Active,
                                    preparedAgent = preparedAgent,
                                )
                            }
                        }
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
        } finally {
            activationInProgress.set(false)
        }
    }

    /**
     * Heartbeat/upload recovery: checks idle expiry first (an idle end rotates
     * the session, see [rotateSession]), then recovers an offline session to
     * running or refreshes the activity marker.
     */
    fun heartbeat(): ResearchSessionResult {
        val current = session ?: return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "no active session")
        if (!isActive) return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "collection is not active")
        if (current.isTerminal) {
            // A session that ended but is not yet replaced is rotated here too,
            // not only on the maintenance tick.
            return if (rotationPending) {
                sessionResultOf(rotateSession(pendingRotationReason ?: "session ended"))
            } else {
                ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "session is terminal")
            }
        }
        if (machine == null) return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "no session policy")
        if (expireIdleLocally()) return sessionResultOf(rotateSession(IDLE_TIMEOUT_REASON))
        val now = clock()
        val live = session ?: return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "no active session")
        val currentMachine = machine ?: return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "no session policy")
        val next =
            when (live.state) {
                SessionState.OFFLINE -> currentMachine.recover(live, now)
                SessionState.NOT_STARTED, SessionState.SUSPENDED, SessionState.ENDED, SessionState.REVOKED -> live
                else -> currentMachine.recordActivity(live, now)
            }
        if (next !== live) {
            session = next
            sessionStore.save(next)
        }
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

    /**
     * Explicit idle check (safe to call periodically). An idle timeout ends the
     * session and immediately rotates to a fresh one; the runtime stays up.
     */
    fun checkIdle(): ResearchSessionResult {
        val current = session ?: return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "no active session")
        if (current.isTerminal) {
            return if (rotationPending && isActive) {
                sessionResultOf(rotateSession(pendingRotationReason ?: "session ended"))
            } else {
                ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "session is terminal")
            }
        }
        if (machine == null) return ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "no session policy")
        if (expireIdleLocally()) return sessionResultOf(rotateSession(IDLE_TIMEOUT_REASON))
        return ResearchSessionResult.Applied(session?.state ?: current.state)
    }

    /**
     * One periodic maintenance tick: expire the session locally when idle
     * (and rotate to a fresh one), refresh the telemetry capability before it
     * expires, report any real qualifying activity, then heartbeat the server
     * session at its declared cadence.
     *
     * Fail-soft by construction: every transport failure becomes a typed
     * [ResearchMaintenanceResult.Retryable] and never affects ordinary plugin
     * behaviour. A session end (local idle, server `ended`/`SESSION_TERMINAL`)
     * rotates the session and keeps the runtime; an operator kill switch is a
     * typed pause that lifts on the next accepted answer; only a real refusal
     * (revocation, missing/invalid policy, invalid manifest) tears the runtime
     * down and is never resurrected by a later tick.
     */
    fun performMaintenance(): ResearchMaintenanceResult {
        val current = session
        if (stopped || !isActive || current == null) {
            return ResearchMaintenanceResult.Inactive("no active research session")
        }
        if (current.isTerminal) {
            // The session ended but the runtime is alive: finish (or retry) the
            // rotation instead of going quiet. A terminal session without a
            // pending rotation only exists after an explicit close.
            return if (rotationPending) {
                maintenanceResultOf(rotateSession(pendingRotationReason ?: "session ended"))
            } else {
                ResearchMaintenanceResult.Inactive("no active research session")
            }
        }
        // Local idle expiry: only real qualifying activity keeps a session
        // alive, and the periodic heartbeat never refreshes it. Once the
        // manifest idle policy is exceeded, end the session locally and open a
        // fresh one; the server remains authoritative for final expiry.
        if (expireIdleLocally()) {
            return maintenanceResultOf(rotateSession(IDLE_TIMEOUT_REASON))
        }
        var capabilityRefreshed = false
        when (val refresh = refreshCapabilityIfNeeded(force = false)) {
            is CapabilityRefresh.Refreshed -> capabilityRefreshed = true
            is CapabilityRefresh.Rotated -> return maintenanceResultOf(finishRotation(refresh.manifest, refresh.session))
            is CapabilityRefresh.Blocked -> return terminalMaintenance(refresh.reason, refresh.detail, refresh.rejection?.name)
            is CapabilityRefresh.Paused -> {
                markPaused(refresh.detail)
                return ResearchMaintenanceResult.Paused(StudyBlockReason.KILL_SWITCH_ENGAGED, refresh.detail)
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
        // Real qualifying activity observed locally is reported explicitly; a
        // plain heartbeat is liveness only and must never fabricate activity.
        var activitySeconds: Long? = null
        when (val activity = flushPendingActivityReport(held, afterRefresh)) {
            null, is SessionHeartbeat.ExpiredCapability, is SessionHeartbeat.Retryable -> Unit
            is SessionHeartbeat.Paused -> {
                markPaused(activity.detail)
                return ResearchMaintenanceResult.Paused(StudyBlockReason.KILL_SWITCH_ENGAGED, activity.detail)
            }
            is SessionHeartbeat.Terminal -> return terminalMaintenance(activity.reason, activity.detail, activity.detail)
            is SessionHeartbeat.Ok -> {
                clearPause()
                val stateBlock = serverStateBlockReason(activity.sessionState)
                if (stateBlock != null) {
                    return terminalMaintenance(
                        stateBlock,
                        "the research server reports session state '${activity.sessionState}'",
                        serverCode = serverStateCode(stateBlock),
                    )
                }
                activitySeconds = activity.heartbeatSeconds
            }
        }
        return when (val heartbeat = sendHeartbeatRequest(held, afterRefresh)) {
            is SessionHeartbeat.Ok -> {
                if (stopped || !isActive) {
                    return ResearchMaintenanceResult.Inactive("research session is no longer active")
                }
                clearPause()
                heartbeat.heartbeatSeconds?.let { rescheduleMaintenance(it) }
                val stateBlock = serverStateBlockReason(heartbeat.sessionState)
                if (stateBlock != null) {
                    terminalMaintenance(
                        stateBlock,
                        "the research server reports session state '${heartbeat.sessionState}'",
                        serverCode = serverStateCode(stateBlock),
                    )
                } else {
                    ResearchMaintenanceResult.Maintained(heartbeat.heartbeatSeconds ?: activitySeconds, capabilityRefreshed)
                }
            }
            is SessionHeartbeat.ExpiredCapability -> when (val forced = refreshCapabilityIfNeeded(force = true)) {
                is CapabilityRefresh.Refreshed -> ResearchMaintenanceResult.Maintained(null, true)
                is CapabilityRefresh.Rotated -> maintenanceResultOf(finishRotation(forced.manifest, forced.session))
                is CapabilityRefresh.Blocked -> terminalMaintenance(forced.reason, forced.detail, forced.rejection?.name)
                is CapabilityRefresh.Paused -> {
                    markPaused(forced.detail)
                    ResearchMaintenanceResult.Paused(StudyBlockReason.KILL_SWITCH_ENGAGED, forced.detail)
                }
                is CapabilityRefresh.Retryable -> ResearchMaintenanceResult.Retryable(forced.detail)
                CapabilityRefresh.NotNeeded -> ResearchMaintenanceResult.Retryable(heartbeat.detail)
            }
            is SessionHeartbeat.Paused -> {
                markPaused(heartbeat.detail)
                ResearchMaintenanceResult.Paused(StudyBlockReason.KILL_SWITCH_ENGAGED, heartbeat.detail)
            }
            is SessionHeartbeat.Terminal -> terminalMaintenance(heartbeat.reason, heartbeat.detail, heartbeat.detail)
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

    /**
     * Withdrawal/confirmed server revocation: terminal, no further events,
     * proxy torn down, `REVOKED` persisted so a restart cannot resume it.
     */
    fun onRevoked(): ResearchSessionResult = revoke(persistRevoked = true, detail = "study enrollment was revoked")

    /**
     * A server answer that names the enrollment as revoked/inactive/stopped (or
     * any other refusal that maps to [StudyBlockReason.REVOKED]). Only a
     * *confirmed* revocation is persisted as `REVOKED`: the server code must be
     * exactly `REVOKED` or `ENROLLMENT_NOT_ACTIVE` and, when membership
     * discovery is wired, `participants/me` must agree (Terminal/None). Every
     * other code (a stopped or closed study, an unknown refusal, a revocation
     * discovery does not confirm) blocks in memory and suspends the local
     * session instead, so the next activation re-checks the server rather than
     * being bricked by a temporary operator action (D-04).
     */
    private fun revokeFromServer(
        serverCode: String?,
        detail: String?,
    ) {
        val revocationCode = serverCode == REVOKED_CODE || serverCode == ENROLLMENT_NOT_ACTIVE_CODE
        val persist = revocationCode && revocationConfirmed()
        revoke(
            persistRevoked = persist,
            detail =
                detail
                    ?: when (serverCode) {
                        STUDY_STOPPED_CODE -> "the study was stopped by the research team"
                        else -> "study enrollment was revoked"
                    },
        )
    }

    /** True when no discovery is wired (bootstrap is the authority) or discovery agrees the enrollment is over. */
    private fun revocationConfirmed(): Boolean {
        if (enrollmentDiscoveryProvider == null) return true
        return when (safeDiscovery()) {
            is EnrollmentDiscovery.Terminal, EnrollmentDiscovery.None -> true
            is EnrollmentDiscovery.Active, is EnrollmentDiscovery.Unavailable, null -> false
        }
    }

    private fun revoke(
        persistRevoked: Boolean,
        detail: String,
    ): ResearchSessionResult {
        val current = session
        deactivateRuntime()
        synchronized(lock) {
            active = false
            consentState = StudyComponentState.BLOCKED
            blockReason = StudyBlockReason.REVOKED
            blockReasonDetail = detail
        }
        if (current != null && !current.isTerminal) {
            val now = clock()
            val currentMachine = machine
            val next =
                when {
                    persistRevoked && currentMachine != null && currentMachine.canTransition(current.state, SessionState.REVOKED) ->
                        currentMachine.revoke(current, now)
                    persistRevoked ->
                        current.copy(
                            state = SessionState.REVOKED,
                            closedAtEpochMs = now,
                            closeReason = SessionTerminalReason.REVOKED,
                        )
                    currentMachine != null && currentMachine.canTransition(current.state, SessionState.SUSPENDED) ->
                        currentMachine.suspend(current, now)
                    else -> current
                }
            session = next
            sessionStore.save(next)
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
     * One bounded pre-withdrawal delivery attempt (Issue 03 F09).
     *
     * Runs the active spool uploader's single manual pass on a worker thread and
     * waits at most [timeoutMs]; it never blocks indefinitely and never throws.
     * Returns the typed upload disposition, or `null` when there is nothing to
     * flush (no live delivery).
     */
    fun flushBounded(timeoutMs: Long = 5_000L): SpoolUploadResult? {
        val delivery = uploader ?: return null
        // Daemon: a drain that outlives its deadline (the uploader mid-request)
        // must never keep the IDE JVM alive at shutdown.
        val executor =
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "code4me-research-spool-drain").apply { isDaemon = true }
            }
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

    /**
     * Stop the manager: optionally ship the spool tail with one bounded
     * delivery attempt, tear down the collectors, flush the bounded pending
     * spool, and terminally stop the runtime. A live session is suspended (not
     * discarded) so a restart inside the revision grace window may resume it;
     * [close] is the explicit participant completion. Idempotent, and never
     * blocks longer than [drainTimeoutMs] plus the IPC grace.
     *
     * @param drain `true` on a plain project close / IDE shutdown and on a
     * sign-out, where the last events should reach the server before the
     * uploader closes (a sign-out then quarantines what is left). It must be
     * `false` on a privacy erase: records the participant asked to delete are
     * never uploaded first.
     * @param drainTimeoutMs upper bound of that one attempt; the service passes
     * a shorter budget when it is stopping on the UI thread.
     */
    fun stop(
        ipcGraceMs: Long = 0L,
        drain: Boolean = true,
        drainTimeoutMs: Long = STOP_DRAIN_TIMEOUT_MS,
    ): ResearchStopResult {
        val shouldStop = synchronized(lock) {
            if (stopped) {
                false
            } else {
                // Publish the terminal lifecycle state before teardown. An
                // activation returning from bootstrap will either finish while
                // holding this lock (then be torn down below) or observe stopped.
                active = false
                stopped = true
                true
            }
        }
        if (shouldStop) {
            // A project close or IDE shutdown is the moment the last events of
            // the day were spooled; one bounded drain ships them before the
            // uploader is closed instead of leaving them for the next launch.
            if (drain) flushBounded(drainTimeoutMs)
            deactivateRuntime(ipcGraceMs)
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
        discoveryActive: Boolean,
        preparedAgent: PackagedAgentInstall.Ready? = null,
    ): ResearchActivationResult {
        val now = clock()
        val policy = policyFor(validManifest)
        val newMachine = SessionStateMachine(policy.resumeGraceMs, policy.idleTimeoutMs)
        val resolution = resolveSession(enrollmentId, newMachine, now, discoveryActive)
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
        val resolvedPolicy = privacyPolicyFor(validManifest).withComputedDigest()

        // D-2/TA-01: exactly one native agent run per activation. It is minted
        // before the IPC endpoint and the ACP entry so both carry the same id
        // and the proxy can stamp `agent_run_id` on every event it produces.
        // It is assigned to `currentRun` only after activation succeeds, so a
        // blocked activation never leaves a live run behind.
        val agentRun =
            AgentRun.start(
                runId = runIdFactory(),
                session = authoritativeSession,
                agentReleaseId = validManifest.agentRelease.releaseId.takeIf { it.isNotBlank() },
                startedAtEpochMs = now,
            )

        // Fail closed: without a live local spool IPC endpoint there is no
        // delivery authority, so nothing may be collected or launched.
        val startedIpc =
            try {
                ipcServerFactory?.invoke(resolvedSpool)
                    ?: ResearchSpoolIpcServer(
                        resolvedSpool,
                        onEventAppended = { noteAgentActivity() },
                        eventContext = SpoolEventContext(
                            validManifest.studyId,
                            validManifest.enrollmentId,
                            authoritativeSession.sessionId,
                            agentRunId = agentRun.runId,
                        ),
                    )
            } catch (exception: Exception) {
                val detail = exception.message ?: "the local spool IPC server could not start"
                markBlocked(StudyBlockReason.RUNTIME_UNAVAILABLE, detail)
                return ResearchActivationResult.Blocked(StudyBlockReason.RUNTIME_UNAVAILABLE, detail)
            }
        synchronized(lock) { ipcServer = startedIpc }

        // The ACP entry must point the proxy at the live IPC endpoint and hand
        // it that server's capability (never the server session capability).
        val runtimeSetup = prepareProxyRuntime(startedIpc, validManifest, resolvedPolicy, agentRun.runId, preparedAgent)
        if (runtimeSetup is RuntimeSetup.Failed) {
            markBlocked(runtimeSetup.reason, runtimeSetup.detail)
            return ResearchActivationResult.Blocked(runtimeSetup.reason, runtimeSetup.detail)
        }
        synchronized(lock) {
            capabilityFile = (runtimeSetup as? RuntimeSetup.Ready)?.capabilityFile
            statusFile = (runtimeSetup as? RuntimeSetup.Ready)?.statusFile
            inferenceCredential = (runtimeSetup as? RuntimeSetup.Ready)?.inferenceCredential
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
            // The activation's single run survives here (it is never nulled
            // afterwards); `agentCrashed`/a later activation end or replace it.
            currentRun = agentRun
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
                    researchSessionId = validManifest.researchSession.researchSessionId.takeIf { it.isNotBlank() },
                )
            val delivery = uploaderFactory(context)
            // D-09: a terminal marker persisted for this same session id by an
            // earlier uploader (a 401 incident before a restart) must not silence
            // a fresh activation for the whole capability TTL; adopting the
            // activation capability clears it before delivery starts.
            delivery.updateCapability(context.sessionCapability)
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
     * The server opens a session in `NOT_STARTED`; the immediate signal here is
     * a liveness heartbeat only. Qualifying activity is reported separately
     * through `/api/research/sessions/activity` once the collector observes it
     * (ISSUE-06). Every failure is fail-soft: a transient error leaves the
     * session active and the periodic loop retries; a terminal server answer
     * (including a missing/invalid study policy, ISSUE-05) tears the runtime
     * down and blocks activation instead of reporting `Activated`.
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
            handleServerTerminal(serverTerminalReason(reason), reason, serverCode = reason)
            return null
        }
        if (create.code !in 200..299) {
            if (reason == KILL_SWITCH_CODE) {
                // An operator pause, not a refusal: keep the runtime and the
                // session and let the periodic loop retry at its normal cadence.
                markPaused(reason)
            }
            log.warn("Research session create was refused with HTTP ${create.code}; periodic maintenance will retry.")
            return null
        }
        heartbeatSeconds = heartbeatSecondsFrom(create.body)
        applyBudgetFrom(create.body)
        when (val heartbeat = sendHeartbeatRequest(validManifest, authoritativeSession)) {
            is SessionHeartbeat.Ok -> {
                clearPause()
                heartbeatSeconds = heartbeat.heartbeatSeconds ?: heartbeatSeconds
            }
            is SessionHeartbeat.Paused -> markPaused(heartbeat.detail)
            is SessionHeartbeat.Terminal -> handleServerTerminal(heartbeat.reason, heartbeat.detail, serverCode = heartbeat.detail)
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
     * [CapabilityRefresh.Blocked], an operator kill switch is
     * [CapabilityRefresh.Paused], and an authentication refusal stays retryable
     * (see [reacquireManifest]). A fresh manifest that names a different session
     * is a server-side rotation and is adopted ([CapabilityRefresh.Rotated]).
     */
    private fun refreshCapabilityIfNeeded(force: Boolean): CapabilityRefresh {
        val held = manifest ?: return CapabilityRefresh.Retryable("no validated manifest")
        if (!capabilityNeedsRefresh(held, force)) return CapabilityRefresh.NotNeeded
        return try {
            val result = reacquireManifest(held)
            when (result.status) {
                BootstrapStatus.OK -> adoptRefreshedManifest(result.manifest)
                BootstrapStatus.BLOCKED ->
                    when {
                        result.rejection.isAuthenticationRefusal() -> CapabilityRefresh.Retryable(result.reason)
                        result.rejection == BootstrapRejection.KILL_SWITCH_ENGAGED -> CapabilityRefresh.Paused(result.reason)
                        else -> CapabilityRefresh.Blocked(blockReasonFor(result), result.reason, result.rejection)
                    }
                BootstrapStatus.REFRESH_REQUIRED -> CapabilityRefresh.Retryable(result.reason)
                BootstrapStatus.RETRYABLE -> CapabilityRefresh.Retryable(result.reason)
            }
        } catch (exception: Exception) {
            log.warn("Research capability refresh failed: ${exception.message ?: "unexpected error"}")
            CapabilityRefresh.Retryable(exception.message)
        }
    }

    /**
     * Re-run bootstrap for [held]'s enrollment (capability refresh and session
     * rotation share this). A `NOT_AUTHENTICATED`/`NOT_PERMITTED` answer is
     * usually an expired backend session, not a lost enrollment: the injected
     * [reauthenticate] hook renews it once and the acquire is retried in the
     * same call. A refusal that survives that stays retryable; only after the
     * held capability has expired *and* it kept failing for
     * [authRetryBlockAfterMs] does the participant see
     * [StudyBlockReason.AUTHENTICATION_REQUIRED] (a transient block: the
     * runtime stays registered and the next successful refresh clears it).
     * Never holds the manager lock (network).
     */
    private fun reacquireManifest(held: BootstrapManifest): BootstrapResult {
        bootstrap.invalidate(held.enrollmentId)
        var result = bootstrap.acquire(held.enrollmentId)
        if (result.status == BootstrapStatus.BLOCKED && result.rejection.isAuthenticationRefusal()) {
            val renew = reauthenticate
            val renewed =
                renew != null &&
                    try {
                        renew()
                    } catch (_: Exception) {
                        false
                    }
            if (renewed) {
                bootstrap.invalidate(held.enrollmentId)
                result = bootstrap.acquire(held.enrollmentId)
            }
        }
        if (result.status == BootstrapStatus.BLOCKED && result.rejection.isAuthenticationRefusal()) {
            noteAuthenticationFailure(held)
        } else if (result.status == BootstrapStatus.OK) {
            authFailureSinceMs = null
            clearTransient(StudyBlockReason.AUTHENTICATION_REQUIRED)
        }
        return result
    }

    private fun BootstrapRejection?.isAuthenticationRefusal(): Boolean =
        this == BootstrapRejection.NOT_AUTHENTICATED || this == BootstrapRejection.NOT_PERMITTED

    private fun noteAuthenticationFailure(held: BootstrapManifest) {
        val now = clock()
        val since = authFailureSinceMs ?: now.also { authFailureSinceMs = it }
        val expires = parseInstant(held.sessionCapability.expiresAt)
        val heldExpired = expires != null && !instantClock().isBefore(expires)
        if (heldExpired && now - since >= authRetryBlockAfterMs) {
            setTransient(StudyBlockReason.AUTHENTICATION_REQUIRED, AUTHENTICATION_REQUIRED_DETAIL)
        }
    }

    /**
     * Adopt a re-bootstrapped manifest and push its capability to the uploader.
     * When the server moved this context to a different session (it expired
     * ours), the old session is ended locally and the new one adopted in place
     * (a rotation); the caller then establishes it on the server.
     */
    private fun adoptRefreshedManifest(fresh: BootstrapManifest?): CapabilityRefresh {
        val outcome =
            synchronized(lock) {
                val current = session
                when {
                    fresh == null -> CapabilityRefresh.Retryable("capability refresh returned no manifest")
                    !active || stopped || current == null || current.isTerminal ->
                        CapabilityRefresh.Retryable("session is no longer active")
                    fresh.researchSession.researchSessionId != current.sessionId -> {
                        endSessionLocally(
                            current,
                            SessionTerminalReason.EXPLICIT_COMPLETION,
                            "the research server moved to a different session",
                        )
                        CapabilityRefresh.Rotated(fresh, adoptRotatedManifest(fresh))
                    }
                    else -> {
                        manifest = fresh
                        // D-1/TC-05: the enrollment/UI is the consent authority, so
                        // re-resolve the in-process policy from the fresh manifest. A
                        // consent withdrawal then starts redacting CONTENT on the very
                        // next capture, without an IDE restart. Only replace the filter
                        // when the policy digest actually changed.
                        val refreshedPolicy = privacyPolicyFor(fresh).withComputedDigest()
                        if (refreshedPolicy.policyDigest != activePrivacyPolicy.policyDigest) {
                            activePrivacyPolicy = refreshedPolicy
                            privacyFilter = PrivacyFilter(refreshedPolicy)
                        }
                        CapabilityRefresh.Refreshed(fresh)
                    }
                }
            }
        // The credential file write and the uploader hand-off wait on I/O and on
        // the uploader's own lock (held across an upload round-trip), so they
        // run after the manager lock is released: IPC request threads appending
        // events must never queue behind an upload.
        if (fresh != null && (outcome is CapabilityRefresh.Refreshed || outcome is CapabilityRefresh.Rotated)) {
            applyRefreshedCredentials(fresh)
        }
        return outcome
    }

    /**
     * Hand a freshly adopted manifest's credentials to the running runtime.
     *
     * Intentionally NOT rewriting the frozen proxy policy file or re-registering
     * the ACP entry: the running proxy keeps its launch-time policy file/digest,
     * and the server independently enforces the current consent at persistence.
     * Rewriting either would put the proxy's launch-time digest out of sync. The
     * inference credential file is the one launch file that does follow a
     * refresh: the entry keeps pointing at the same path, and an agent launched
     * later must present a capability that is still valid (a running one keeps
     * the bearer it read). Never called under the manager lock.
     */
    private fun applyRefreshedCredentials(fresh: BootstrapManifest) {
        refreshInferenceCredential(fresh)
        if (!safeUpdateCapability(fresh.sessionCapabilityObject())) {
            log.warn("A refreshed research capability could not be adopted by the running uploader.")
        }
    }

    private fun safeUpdateCapability(capability: Map<String, Any?>): Boolean =
        try {
            uploader?.updateCapability(capability) ?: false
        } catch (exception: Exception) {
            log.warn("Research uploader rejected the refreshed capability: ${exception.message ?: "unexpected error"}")
            false
        }

    /** POST the liveness heartbeat for [held]/[current]; never throws. */
    private fun sendHeartbeatRequest(
        held: BootstrapManifest,
        current: ResearchSession,
    ): SessionHeartbeat = sendSessionSignal(HEARTBEAT_PATH, held, current)

    /** POST one qualifying-activity report for [held]/[current]; never throws. */
    private fun sendActivityRequest(
        held: BootstrapManifest,
        current: ResearchSession,
    ): SessionHeartbeat = sendSessionSignal(ACTIVITY_PATH, held, current)

    /**
     * Flush the pending qualifying-activity report, if any (ISSUE-06).
     *
     * Returns `null` when no real activity was observed locally since the last
     * flush. The pending flag survives a retryable/expired-capability answer so
     * the next tick re-reports the same activity; a terminal answer clears it
     * because the session must not be resurrected.
     */
    private fun flushPendingActivityReport(
        held: BootstrapManifest,
        current: ResearchSession,
    ): SessionHeartbeat? {
        if (!pendingActivityReport) return null
        pendingActivityReport = false
        val outcome = sendActivityRequest(held, current)
        if (outcome !is SessionHeartbeat.Ok && outcome !is SessionHeartbeat.Terminal) {
            pendingActivityReport = true
        }
        return outcome
    }

    /** POST one session lifecycle signal at [path] and classify the answer; never throws. */
    private fun sendSessionSignal(
        path: String,
        held: BootstrapManifest,
        current: ResearchSession,
    ): SessionHeartbeat {
        val response =
            postSessionJson(
                path,
                linkedMapOf<String, Any?>(
                    "capability" to held.sessionCapabilityObject(),
                    "research_session_id" to current.sessionId,
                ),
            ) ?: return SessionHeartbeat.Retryable("the session request to $path could not be delivered")
        val reason = reasonCodeFrom(response.body)
        return when {
            response.code in 200..299 -> {
                applyBudgetFrom(response.body)
                SessionHeartbeat.Ok(heartbeatSecondsFrom(response.body), sessionStateFrom(response.body))
            }
            // The operator kill switch is a pause the server lifts later; it is
            // answered on the auth and conflict codes alike, and is never terminal.
            reason == KILL_SWITCH_CODE &&
                (response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN || response.code == HTTP_CONFLICT) ->
                SessionHeartbeat.Paused(reason)
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

    /**
     * Adopt the advisory `budget` block of a session create/heartbeat/activity
     * response. An absent key (a server that predates budgets) keeps what is
     * held; `null` means an unmetered arm. Only a live activation holds a
     * budget, so an answer that arrives after teardown is ignored. Never a
     * block: the server enforces the budget on every relay call.
     */
    private fun applyBudgetFrom(body: String) {
        val map =
            try {
                parseCanonicalJson(body) as? Map<*, *>
            } catch (_: Exception) {
                null
            } ?: return
        if (!map.containsKey("budget")) return
        val parsed = InferenceBudgetState.fromWire(map["budget"])
        synchronized(lock) {
            if (active && !stopped) inferenceBudget = parsed
        }
    }

    /**
     * Rewrite the inference credential file from a refreshed manifest.
     * Fail-soft: the running agent keeps the bearer it read at start (valid for
     * its own TTL) and the next refresh retries. Nothing here logs the credential.
     */
    private fun refreshInferenceCredential(fresh: BootstrapManifest) {
        val handle = inferenceCredential ?: return
        val bearer = fresh.inferenceBearer()
        if (bearer == null) {
            log.warn("The refreshed research manifest carries no inference gateway; the agent keeps the previously delivered credential.")
            return
        }
        val written = writeInferenceCredentialFile(handle.file, handle.envKey, bearer)
        if (written.isFailure) {
            log.warn(
                "The refreshed inference credential could not be written: " +
                    (written.exceptionOrNull()?.message ?: "unexpected error"),
            )
        }
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

    /**
     * True for server reason codes that cannot succeed on retry: the session or
     * enrollment is terminal, or the study policy itself is missing/invalid so
     * every session endpoint will keep refusing until a researcher fixes it.
     * `KILL_SWITCH_ENGAGED` is deliberately absent: it is an operator pause
     * that lifts on its own (see [SessionHeartbeat.Paused]).
     */
    private fun isTerminalServerCode(code: String?): Boolean =
        code == REVOKED_CODE ||
            code == ENROLLMENT_NOT_ACTIVE_CODE ||
            code == STUDY_STOPPED_CODE ||
            code == SESSION_TERMINAL_CODE ||
            code == POLICY_MISSING_CODE ||
            code == SESSION_POLICY_INVALID_CODE

    private fun serverTerminalReason(code: String?): StudyBlockReason =
        when (code) {
            REVOKED_CODE, ENROLLMENT_NOT_ACTIVE_CODE, STUDY_STOPPED_CODE -> StudyBlockReason.REVOKED
            SESSION_TERMINAL_CODE -> StudyBlockReason.SESSION_ENDED
            POLICY_MISSING_CODE, SESSION_POLICY_INVALID_CODE -> StudyBlockReason.POLICY_INVALID
            else -> StudyBlockReason.REVOKED
        }

    /** The block reason for a server-reported terminal session state, if any. */
    private fun serverStateBlockReason(state: String?): StudyBlockReason? =
        when (state) {
            SERVER_STATE_REVOKED -> StudyBlockReason.REVOKED
            SERVER_STATE_ENDED -> StudyBlockReason.SESSION_ENDED
            else -> null
        }

    /**
     * The server code equivalent of a terminal session *state*: a session the
     * server reports as `revoked` is a `REVOKED` answer (still subject to the
     * discovery confirmation in [revokeFromServer]); an `ended` state carries no
     * code (it rotates).
     */
    private fun serverStateCode(stateBlock: StudyBlockReason): String? = if (stateBlock == StudyBlockReason.REVOKED) REVOKED_CODE else null

    /**
     * Apply an authoritative terminal answer from the server.
     *
     * A revocation or a real refusal (missing/invalid policy) stops the runtime
     * and makes the local session terminal (where the state allows) so a later
     * re-activation inside the resume grace cannot resurrect it. A session end
     * is different: the local session is ended and a rotation is left pending,
     * which the caller (or the next maintenance tick) completes; the runtime
     * stays up. [serverCode] is the server's typed reason code when known (it
     * decides whether a revocation is persisted, see [revokeFromServer]).
     */
    private fun handleServerTerminal(
        reason: StudyBlockReason,
        detail: String?,
        serverCode: String? = null,
    ) {
        when (reason) {
            StudyBlockReason.REVOKED -> revokeFromServer(serverCode, detail?.takeIf { it != serverCode })
            StudyBlockReason.SESSION_ENDED -> endSessionFromServer(detail)
            else -> {
                markBlocked(reason, detail ?: "the research server blocked this session")
                stopMaintenance()
            }
        }
    }

    /**
     * A terminal server answer observed by the maintenance loop. A session end
     * rotates in this same tick; everything else is applied as before.
     */
    private fun terminalMaintenance(
        reason: StudyBlockReason,
        detail: String?,
        serverCode: String?,
    ): ResearchMaintenanceResult {
        if (reason == StudyBlockReason.SESSION_ENDED) {
            endSessionFromServer(detail)
            return maintenanceResultOf(rotateSession(pendingRotationReason ?: "the research server ended the session"))
        }
        handleServerTerminal(reason, detail, serverCode)
        return ResearchMaintenanceResult.Ended(reason, detail)
    }

    /**
     * The server ended our session (`ended` state or `SESSION_TERMINAL`): end
     * it locally and leave a rotation pending. Nothing is torn down and the
     * maintenance loop keeps running: the rotation is completed by the caller
     * or by the next tick.
     */
    private fun endSessionFromServer(detail: String?) {
        synchronized(lock) {
            val current = session
            if (current != null && !current.isTerminal) {
                endSessionLocally(current, SessionTerminalReason.EXPLICIT_COMPLETION, detail ?: "the research server ended the session")
            } else {
                markRotationPending(detail ?: "the research server ended the session")
            }
        }
    }

    // ------------------------------------------------------------------
    // Session rotation (D-01): a session end never tears the runtime down
    // ------------------------------------------------------------------

    /** Typed outcome of one rotation attempt; never thrown. */
    private sealed interface RotationOutcome {
        /** A fresh session was adopted and established; [heartbeatSeconds] is its cadence. */
        data class Rotated(val session: ResearchSession, val heartbeatSeconds: Long?) : RotationOutcome

        /** The bootstrap could not complete now; the runtime is intact and the next tick retries. */
        data class Retryable(val detail: String?) : RotationOutcome

        /** A real refusal: the typed block was applied and the runtime torn down. */
        data class Blocked(val reason: StudyBlockReason, val detail: String?) : RotationOutcome

        /** The manager stopped or was deactivated meanwhile. */
        object Inactive : RotationOutcome
    }

    /**
     * End the live [current] session locally (idle timeout, server end) and
     * leave a rotation pending. Must hold [lock]. The collector keeps its scope,
     * so events captured until the fresh session is adopted still carry this
     * session id and are accepted by the server within its grace window.
     */
    private fun endSessionLocally(
        current: ResearchSession,
        closeReason: SessionTerminalReason,
        reason: String,
    ) {
        val now = clock()
        val currentMachine = machine
        val ended =
            when {
                closeReason == SessionTerminalReason.IDLE_TIMEOUT && currentMachine != null ->
                    currentMachine.expireIfIdle(current, now).takeIf { it.isTerminal }
                        ?: current.copy(state = SessionState.ENDED, closedAtEpochMs = now, closeReason = closeReason)
                currentMachine != null && currentMachine.canTransition(current.state, SessionState.ENDED) ->
                    currentMachine.end(current, now)
                else -> current.copy(state = SessionState.ENDED, closedAtEpochMs = now, closeReason = closeReason)
            }
        session = ended
        sessionStore.save(ended)
        markRotationPending(reason)
    }

    /** Must hold [lock]. */
    private fun markRotationPending(reason: String) {
        rotationPending = true
        pendingRotationReason = reason
        setTransient(StudyBlockReason.SESSION_ROTATING, "renewing the research session ($reason)")
    }

    /**
     * End the session locally when the manifest idle policy is exceeded and
     * leave a rotation pending. Returns `true` when the session ended here.
     */
    private fun expireIdleLocally(): Boolean =
        synchronized(lock) {
            val current = session ?: return false
            if (current.isTerminal) return false
            val currentMachine = machine ?: return false
            if (!currentMachine.expireIfIdle(current, clock()).isTerminal) return false
            endSessionLocally(current, SessionTerminalReason.IDLE_TIMEOUT, IDLE_TIMEOUT_REASON)
            true
        }

    /**
     * Replace the ended session with a fresh server session **without**
     * recreating any resource: the ACP entry, capability/policy/status files,
     * IPC server, collector and uploader all stay. Re-bootstrap (outside the
     * lock), adopt the manifest in place (inside the lock), then open the new
     * session on the server and reschedule the loop at its cadence.
     *
     * Fail-soft: a transport or authentication problem keeps everything alive
     * and is retried on every tick; an operator kill switch pauses; only a real
     * refusal applies the typed block. Serialized: a concurrent caller gets
     * [RotationOutcome.Retryable] and observes the outcome on its next tick.
     */
    private fun rotateSession(reason: String): RotationOutcome {
        if (stopped || !active) return RotationOutcome.Inactive
        val held = manifest ?: return RotationOutcome.Retryable("no validated manifest")
        if (!rotationInFlight.compareAndSet(false, true)) {
            return RotationOutcome.Retryable("a session rotation is already in progress")
        }
        try {
            val endedSessionId =
                synchronized(lock) {
                    if (!rotationPending) markRotationPending(reason)
                    session?.sessionId
                }
            val result =
                try {
                    reacquireManifest(held)
                } catch (exception: Exception) {
                    log.warn("Research session rotation bootstrap failed: ${exception.message ?: "unexpected error"}")
                    return RotationOutcome.Retryable(exception.message)
                }
            return when (result.status) {
                BootstrapStatus.OK -> {
                    val fresh = result.manifest ?: return RotationOutcome.Retryable("bootstrap returned no manifest")
                    if (fresh.researchSession.researchSessionId == endedSessionId) {
                        // The server still considers the session we just ended live
                        // (its activity marker trails ours by up to one heartbeat).
                        // Never reopen an ended session under its own id: leave the
                        // rotation pending; the server closes it within a tick and
                        // the next attempt receives a fresh id.
                        return RotationOutcome.Retryable("the research server still reports the ended session; waiting for it to close")
                    }
                    val adopted =
                        synchronized(lock) {
                            if (stopped || !active) null else adoptRotatedManifest(fresh)
                        } ?: return RotationOutcome.Inactive
                    applyRefreshedCredentials(fresh)
                    finishRotation(fresh, adopted)
                }
                BootstrapStatus.BLOCKED ->
                    when {
                        result.rejection.isAuthenticationRefusal() -> RotationOutcome.Retryable(result.reason)
                        result.rejection == BootstrapRejection.KILL_SWITCH_ENGAGED -> {
                            markPaused(result.reason)
                            RotationOutcome.Retryable(result.reason)
                        }
                        else -> {
                            val blocked = blockReasonFor(result)
                            if (blocked == StudyBlockReason.REVOKED) {
                                revokeFromServer(result.rejection?.name, result.reason)
                            } else {
                                markBlocked(blocked, result.reason)
                            }
                            RotationOutcome.Blocked(blocked, result.reason)
                        }
                    }
                BootstrapStatus.REFRESH_REQUIRED, BootstrapStatus.RETRYABLE -> RotationOutcome.Retryable(result.reason)
            }
        } finally {
            rotationInFlight.set(false)
        }
    }

    /**
     * Adopt [fresh] as the new session in place. Must hold [lock]. The manifest
     * session id is authoritative; the collector scope and the IPC context
     * follow it. The native `AgentRun` is **per activation**, exactly as the
     * frozen ACP entry encodes it (`--agent-run-id` / `CODE4ME_RESEARCH_RUN_ID`,
     * which the proxy stamps on every event): it is kept, and the IPC context is
     * rebound with the same run id, otherwise every ACP event after the
     * rotation would be refused as a run mismatch. The uploader capability and
     * the inference credential are applied by the caller after the lock is
     * released ([applyRefreshedCredentials]).
     */
    private fun adoptRotatedManifest(fresh: BootstrapManifest): ResearchSession {
        val rotated =
            ResearchSession(
                sessionId = fresh.researchSession.researchSessionId.takeIf { it.isNotBlank() } ?: sessionIdFactory(),
                enrollmentId = fresh.enrollmentId,
                studyId = fresh.studyId,
                assignmentId = fresh.assignment.assignmentId,
                profileDigest = fresh.assignment.profileDigest,
                contextId = contextId,
            )
        val policy = policyFor(fresh)
        machine = SessionStateMachine(policy.resumeGraceMs, policy.idleTimeoutMs)
        manifest = fresh
        val refreshedPolicy = privacyPolicyFor(fresh).withComputedDigest()
        if (refreshedPolicy.policyDigest != activePrivacyPolicy.policyDigest) {
            activePrivacyPolicy = refreshedPolicy
            privacyFilter = PrivacyFilter(refreshedPolicy)
        }
        collector?.activate(
            IdeCollectionScope(
                researchSessionId = rotated.sessionId,
                studyId = fresh.studyId,
                assignmentId = fresh.assignment.assignmentId,
                profileDigest = fresh.assignment.profileDigest,
                enrollmentId = fresh.enrollmentId,
                manifestDigest = fresh.manifestDigest,
            ),
        )
        runCatching {
            ipcServer?.rebind(
                SpoolEventContext(fresh.studyId, fresh.enrollmentId, rotated.sessionId, agentRunId = currentRun?.runId),
            )
        }.onFailure { log.warn("The spool IPC context could not follow the rotated session: ${it.message ?: "unexpected error"}") }
        session = rotated
        pendingActivityReport = false
        rotationPending = false
        pendingRotationReason = null
        clearTransient(StudyBlockReason.SESSION_ROTATING)
        sessionStore.save(rotated)
        return rotated
    }

    /** Open the adopted session on the server and reschedule the loop; never under the lock. */
    private fun finishRotation(
        fresh: BootstrapManifest,
        rotated: ResearchSession,
    ): RotationOutcome {
        val heartbeatSeconds = establishServerSession(fresh, rotated)
        if (stopped || !isActive) return RotationOutcome.Inactive
        startMaintenance(fresh, heartbeatSeconds)
        log.info("Research session rotated; the runtime and ACP entry were kept.")
        return RotationOutcome.Rotated(rotated, heartbeatSeconds)
    }

    private fun maintenanceResultOf(outcome: RotationOutcome): ResearchMaintenanceResult =
        when (outcome) {
            is RotationOutcome.Rotated -> ResearchMaintenanceResult.Rotated(outcome.session.sessionId, outcome.heartbeatSeconds)
            is RotationOutcome.Retryable -> ResearchMaintenanceResult.Retryable(outcome.detail)
            is RotationOutcome.Blocked -> ResearchMaintenanceResult.Ended(outcome.reason, outcome.detail)
            RotationOutcome.Inactive -> ResearchMaintenanceResult.Inactive("research session is no longer active")
        }

    private fun sessionResultOf(outcome: RotationOutcome): ResearchSessionResult =
        when (outcome) {
            is RotationOutcome.Rotated -> ResearchSessionResult.Applied(outcome.session.state, "session rotated")
            is RotationOutcome.Retryable ->
                ResearchSessionResult.Applied(
                    session?.state ?: SessionState.ENDED,
                    "session ended; reconnecting" + (outcome.detail?.let { ": $it" } ?: ""),
                )
            is RotationOutcome.Blocked -> ResearchSessionResult.Rejected(outcome.reason, outcome.detail)
            RotationOutcome.Inactive -> ResearchSessionResult.Rejected(StudyBlockReason.SESSION_ENDED, "collection is not active")
        }

    // ------------------------------------------------------------------
    // Transient (self-clearing) conditions: pause, rotation, sign-in
    // ------------------------------------------------------------------

    private fun setTransient(
        reason: StudyBlockReason,
        detail: String?,
    ) {
        synchronized(lock) {
            transientBlock = reason
            transientBlockDetail = detail
        }
    }

    /** Clear the transient condition only when it is [reason]; another condition keeps its slot. */
    private fun clearTransient(reason: StudyBlockReason) {
        synchronized(lock) {
            if (transientBlock == reason) {
                transientBlock = null
                transientBlockDetail = null
            }
        }
    }

    /** Operator kill switch: keep session and runtime, show the typed pause, keep retrying. */
    private fun markPaused(detail: String?) {
        if (stopped || !active) return
        setTransient(StudyBlockReason.KILL_SWITCH_ENGAGED, detail ?: "the study team paused collection")
    }

    private fun clearPause() = clearTransient(StudyBlockReason.KILL_SWITCH_ENGAGED)

    /** Forget every transient condition (teardown / fresh activation). */
    private fun clearTransientConditions() {
        synchronized(lock) {
            transientBlock = null
            transientBlockDetail = null
            rotationPending = false
            pendingRotationReason = null
            authFailureSinceMs = null
        }
    }

    /** The A-03 host preflight, or `null` when none is wired. A throwing provider fails closed. */
    private fun runHostPreflight(): HostPreflightResult? {
        val provider = hostPreflightProvider ?: return null
        return try {
            provider()
        } catch (exception: Exception) {
            HostPreflightResult.Blocked(
                StudyBlockReason.AI_ASSISTANT_MISSING,
                "the JetBrains AI Assistant check failed: ${exception.message ?: "unexpected error"}",
            )
        }
    }

    /** Typed result of a capability refresh attempt; never thrown. */
    private sealed interface CapabilityRefresh {
        data class Refreshed(val manifest: BootstrapManifest) : CapabilityRefresh

        /** The server moved this context to a different session; it was adopted in place. */
        data class Rotated(val manifest: BootstrapManifest, val session: ResearchSession) : CapabilityRefresh

        object NotNeeded : CapabilityRefresh

        data class Blocked(
            val reason: StudyBlockReason,
            val detail: String?,
            val rejection: BootstrapRejection? = null,
        ) : CapabilityRefresh

        /** An operator kill switch: retry at the normal cadence, nothing torn down. */
        data class Paused(val detail: String?) : CapabilityRefresh

        data class Retryable(val detail: String?) : CapabilityRefresh
    }

    /** Typed result of one heartbeat request; never thrown. */
    private sealed interface SessionHeartbeat {
        data class Ok(val heartbeatSeconds: Long?, val sessionState: String?) : SessionHeartbeat

        data class ExpiredCapability(val detail: String?) : SessionHeartbeat

        data class Terminal(val reason: StudyBlockReason, val detail: String?) : SessionHeartbeat

        /** `KILL_SWITCH_ENGAGED`: an operator pause, retried at the normal cadence. */
        data class Paused(val detail: String?) : SessionHeartbeat

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
        discoveryActive: Boolean,
    ): SessionResolution {
        val stored = sessionStore.load(sessionKey(enrollmentId)) ?: return newSession(enrollmentId, resumed = false)
        if (stored.state == SessionState.REVOKED) {
            if (discoveryActive) {
                // The server just reported this enrollment as active, so the
                // stored revocation is stale (a temporary operator action an
                // earlier build persisted, or a re-enrollment). The server is
                // the authority: drop the document and open a fresh session.
                sessionStore.clear(sessionKey(enrollmentId))
                return newSession(enrollmentId, resumed = false)
            }
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
        val executor = eventExecutor
        if (executor == null) {
            processCanonicalEvent(event)
            return
        }
        try {
            executor.execute { processCanonicalEvent(event) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The executor is shut down with the manager; a late event is dropped.
        }
    }

    private fun processCanonicalEvent(event: CanonicalEvent) {
        // No active/stopped check here: the event was accepted while the
        // session was active (see onCanonicalEvent). A project close stops the
        // manager before the queue drains, and those last events (final saves)
        // must still reach the durable spool for the next activation.
        synchronized(lock) {
            val current = session ?: return
            // While a rotation is pending the ended session's events are still
            // spooled under its id (the server accepts them inside its grace
            // window); they only never advance a terminal session.
            if (current.isTerminal && !rotationPending) return
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
            // or refresh the session. An ended (rotating) session is never advanced.
            if (current.isTerminal || !isQualifyingActivity(event)) return
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
            // This is real qualifying activity: report it to the server on the
            // next maintenance tick. A heartbeat alone never sets this flag.
            pendingActivityReport = true
        }
    }

    @Volatile private var lastAgentActivityEpochMs: Long = 0L

    /**
     * An agent event the proxy appended to the spool is participant activity too:
     * without this, agent-only use never refreshed the session and the idle
     * timeout ended it in the middle of a chat. Throttled: the proxy appends one
     * event per streamed chunk.
     */
    private fun noteAgentActivity() {
        val now = clock()
        if (now - lastAgentActivityEpochMs < AGENT_ACTIVITY_THROTTLE_MS) return
        lastAgentActivityEpochMs = now
        if (!active || stopped) return
        synchronized(lock) {
            val current = session ?: return
            if (current.isTerminal) return
            // Only refresh a session that participant activity already started:
            // agent processes also report at startup with no participant action.
            if (current.state == SessionState.NOT_STARTED || current.state == SessionState.SUSPENDED) return
            val advanced = machine?.recordActivity(current, now) ?: current
            session = advanced
            sessionStore.save(advanced)
            pendingActivityReport = true
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
    private fun deactivateRuntime(ipcGraceMs: Long = 0L) {
        stopMaintenance()
        pendingActivityReport = false
        clearTransientConditions()
        val currentCollector = collector
        collector = null
        val currentUploader = uploader
        uploader = null
        val currentIpc = ipcServer
        ipcServer = null
        runCatching { currentCollector?.deactivate() }
        runCatching { currentCollector?.stop() }
        runCatching { currentUploader?.close() }
        if (currentIpc != null && ipcGraceMs > 0L) {
            // Project close: the assistant's agent processes flush their last
            // events after the IDE tears the service down. Keep accepting them
            // briefly; anything appended lands in the durable spool for the
            // next activation of this context.
            Thread(
                {
                    try {
                        Thread.sleep(ipcGraceMs)
                    } catch (_: InterruptedException) {
                    }
                    runCatching { currentIpc.close() }
                },
                "code4me-research-ipc-grace",
            ).apply { isDaemon = true }.start()
        } else {
            runCatching { currentIpc?.close() }
        }
        // Never silent: a failed unregistration leaves the previous account's
        // entry in the ACP registry, where the next login reads it as a changed
        // configuration instead of an added one. Teardown itself stays
        // best-effort — the failure is reported, never thrown.
        runCatching {
            acpHostRegistration?.let { registration ->
                me.code4me.services.agent.AcpRegistryVfs.beforeWrite(registration.registryLocation)
                registration.unregister().also {
                    me.code4me.services.agent.AcpRegistryVfs.afterWrite(registration.registryLocation)
                }
            }
        }
            .onSuccess { outcome ->
                outcome?.onFailure { failure ->
                    log.warn(
                        "Research proxy ACP entry could not be unregistered; a stale entry may remain: " +
                            (failure.message ?: "unexpected error"),
                        failure,
                    )
                }
            }
            .onFailure { exception ->
                log.warn(
                    "Research proxy ACP entry unregistration failed unexpectedly: " +
                        (exception.message ?: "unexpected error"),
                    exception,
                )
            }
        val staged = capabilityFile
        capabilityFile = null
        val credential = inferenceCredential
        inferenceCredential = null
        // The budget is advisory and belongs to the live activation only.
        inferenceBudget = null
        // The status document is read-only from the plugin's side; a stale one
        // is superseded by the next activation, and clearing the path makes the
        // loss state `unknown` rather than stale.
        statusFile = null
        AcpHostRegistration.cleanupCapabilityFile(staged)
        AcpHostRegistration.cleanupInferenceCredentialFile(credential?.file)
    }

    /** The participant-visible delivery posture and local telemetry loss. */
    private data class DeliveryPosture(
        val state: SpoolDeliveryState,
        /** Proxy-reported locally dropped events, or `null` when unknown. */
        val droppedTelemetryCount: Int?,
        /** Uploader discards after permanent rejections, or `null` without a live uploader. */
        val discardedEventCount: Int? = null,
    )

    /**
     * Participant-safe delivery posture derived from the live uploader state
     * plus the proxy's content-free status document.
     *
     * The dropped count is read from the proxy status file when it exists. An
     * absent or unreadable file is reported as `null` (unknown), never as a
     * fabricated zero: the surface must not claim full coverage it cannot prove.
     */
    private fun currentDeliveryState(): DeliveryPosture {
        val dropped = readDroppedTelemetryCount()
        val current = uploader ?: return DeliveryPosture(SpoolDeliveryState.UNAVAILABLE, dropped)
        val snapshot =
            try {
                current.state()
            } catch (_: Exception) {
                return DeliveryPosture(SpoolDeliveryState.UNAVAILABLE, dropped)
            }
        val state =
            when {
                snapshot.revoked -> SpoolDeliveryState.REVOKED
                snapshot.spoolFull -> SpoolDeliveryState.SPOOL_FULL
                snapshot.nextAttemptAtEpochMs != null -> SpoolDeliveryState.RECOVERING
                else -> SpoolDeliveryState.ACTIVE
            }
        return DeliveryPosture(state, dropped, snapshot.discardedCount)
    }

    /**
     * Read the proxy's content-free status document and return its `dropped`
     * counter, or `null` when no status file is configured or it cannot be read.
     *
     * Only the integer counter is consumed; the document carries no payload,
     * event id, path, or capability.
     */
    private fun readDroppedTelemetryCount(): Int? {
        val path = statusFile ?: return null
        return try {
            if (!Files.exists(path)) return null
            val parsed = parseCanonicalJson(Files.readString(path, StandardCharsets.UTF_8))
            ((parsed as? Map<*, *>)?.get("dropped") as? Number)?.toInt()
        } catch (_: Exception) {
            null
        }
    }

    /** Typed runtime setup outcome; a failure blocks activation. */
    private sealed interface RuntimeSetup {
        /** No resolver injected: runtime resolution is not part of this build (tests). */
        object Skipped : RuntimeSetup

        /** The packaged runtime resolved and the ACP entry was registered. */
        data class Ready(
            val capabilityFile: Path?,
            /** The proxy's content-free delivery status document path, if any. */
            val statusFile: Path? = null,
            /** The written inference credential file for a gateway-bound agent, if any. */
            val inferenceCredential: InferenceCredentialHandle? = null,
        ) : RuntimeSetup

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
     * Resolve the packaged proxy runtime, install the packaged agent, and register
     * the proxy as the ACP host entry.
     *
     * The proxy is resolved proxy-only; the agent for a PACKAGED distribution is
     * the archive [packagedAgentInstaller] prepared for the assignment (verified
     * cache or exact release download), checked against the bootstrap manifest's
     * pinned `agent_release.artifact_digest` before any byte is written.
     *
     * Fail-closed: any typed resolution failure, an agent archive whose digest
     * does not match the bootstrap pin, or an ACP registration failure
     * yields [RuntimeSetup.Failed] so no launch happens. When no resolver is
     * injected (pure tests / builds without a packaged runtime), setup is skipped
     * so existing behaviour is preserved.
     *
     * The registered entry points the proxy at [ipc]'s loopback endpoint and hands
     * it [SpoolIpcServer.capability] (the LOCAL IPC capability), never the signed
     * server session capability.
     *
     * @param validManifest the validated bootstrap manifest whose pinned artifact
     * digest the resolved agent must match.
     * @param policy the frozen resolved privacy policy (with its digest) the
     * proxy must enforce; it is written owner-only beside the capability and
     * pinned by `--telemetry-policy-digest`.
     */
    private fun prepareProxyRuntime(
        ipc: SpoolIpcServer?,
        validManifest: BootstrapManifest,
        policy: PrivacyPolicy,
        agentRunId: String?,
        preparedAgent: PackagedAgentInstall.Ready? = null,
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
                        AgentDistributionMode.PACKAGED -> packagedAgentPlan(runtime, validManifest, preparedAgent)
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
                // Freeze the exact telemetry policy the session will stamp on
                // its events. The file sits beside the one-time capability
                // (owner-only) and only its path travels on the command line.
                val telemetryPolicyFile = policyFileFor(validManifest.enrollmentId)
                if (telemetryPolicyFile == null) {
                    return runtimeSetupFailure("the frozen telemetry policy path could not be resolved")
                }
                val policyWrite = writeFrozenTelemetryPolicy(telemetryPolicyFile, policy)
                if (policyWrite.isFailure) {
                    return runtimeSetupFailure(
                        policyWrite.exceptionOrNull()?.message
                            ?: "the frozen telemetry policy could not be written",
                    )
                }
                // The proxy writes a content-free drop-counter document beside
                // the frozen policy; the participant status surface reads it so
                // local telemetry loss is never silent. The proxy creates it.
                val telemetryStatusFile = statusFileFor(validManifest.enrollmentId)
                // A gateway-bound agent receives the study AI credential through
                // an owner-only file written before the entry exists (a launch
                // can never race an absent credential); only its path travels.
                val credentialPlan = ready.inferenceCredential
                val credentialFile =
                    if (credentialPlan != null) {
                        val file =
                            inferenceCredentialFileFor(validManifest.enrollmentId)
                                ?: return runtimeSetupFailure("the study AI credential file path could not be resolved")
                        val written = writeInferenceCredentialFile(file, credentialPlan.envKey, credentialPlan.bearer)
                        if (written.isFailure) {
                            return runtimeSetupFailure(
                                "the study AI credential could not be written: " +
                                    (written.exceptionOrNull()?.message ?: "unexpected error"),
                            )
                        }
                        file
                    } else {
                        null
                    }
                val credentialHandle =
                    if (credentialFile != null && credentialPlan != null) {
                        InferenceCredentialHandle(credentialFile, credentialPlan.envKey)
                    } else {
                        null
                    }
                val environment =
                    mapOf(
                        "CODE4ME_RESEARCH_PROXY" to runtime.proxyDigest,
                        // Canonical research attribution for a managed agent
                        // running under a study (ISSUE-02). The proxy forwards
                        // these to its child; the server re-validates them.
                        "CODE4ME_RESEARCH_ENROLLMENT_ID" to validManifest.enrollmentId,
                        "CODE4ME_RESEARCH_SESSION_ID" to
                            validManifest.researchSession.researchSessionId,
                    )
                // Load the registry's directory into VFS first, so AI Assistant
                // observes a first-time create as well as a change.
                me.code4me.services.agent.AcpRegistryVfs.beforeWrite(registration.registryLocation)
                val result =
                    try {
                        // Research-only, manifest-driven registration. The
                        // non-research hard-coded Codex/Goose paths in
                        // AcpManager.kt / LocalProxyServer.kt are a separate
                        // boundary and are intentionally not touched here.
                        registration.register(
                            resolved = runtime,
                            agentArgv = ready.argv,
                            // A dev (source) proxy runtime carries no packaged
                            // agent; the entry then pins none.
                            digestFallbackToAgentArgv = ready.argv.isNotEmpty(),
                            spoolEndpoint = spoolEndpoint,
                            capabilityFile = capability,
                            env = environment + agentEnvProvider(),
                            agentDigest = ready.digest,
                            capabilityValue = ipc?.capability,
                            adapterId = validManifest.agentRelease.adapterId?.takeIf { it.isNotBlank() },
                            adapterVersion = validManifest.agentRelease.adapterVersion,
                            agentRunId = agentRunId,
                            policyFile = telemetryPolicyFile,
                            policyDigest = policy.policyDigest,
                            statusFile = telemetryStatusFile,
                            agentEnv = ready.agentEnv,
                            inferenceCredentialFile = credentialFile,
                        )
                    } catch (exception: Exception) {
                        Result.failure(exception)
                    }
                me.code4me.services.agent.AcpRegistryVfs.afterWrite(registration.registryLocation)
                if (result.isFailure) {
                    AcpHostRegistration.cleanupCapabilityFile(capability)
                    AcpHostRegistration.cleanupInferenceCredentialFile(credentialFile)
                    runtimeSetupFailure(
                        result.exceptionOrNull()?.message ?: "the research proxy ACP entry could not be registered",
                    )
                } else {
                    RuntimeSetup.Ready(capability, telemetryStatusFile, credentialHandle)
                }
            }
        }
    }

    /**
     * PACKAGED agent contract: the archive prepared for the assignment must match
     * the bootstrap manifest's pinned archive digest before any byte is written. A
     * missing/mismatched artifact is terminal; PATH is never consulted. The
     * preparation's release (adapter digest included) already equals this
     * manifest's release ([me.code4me.research.bootstrap.AgentPreparation.matches]).
     */
    private fun packagedAgentPlan(
        runtime: ResolvedProxyRuntime,
        validManifest: BootstrapManifest,
        preparedAgent: PackagedAgentInstall.Ready?,
    ): AgentPlan {
        val release = validManifest.agentRelease
        // A development (source) proxy runtime carries no packaged agent; keeping
        // the historical dev behavior means only the proxy runs.
        if (runtime.development) return AgentPlan.Ready(argv = emptyList(), digest = null)
        val install =
            try {
                preparedAgent ?: packagedAgentInstaller.install(release.normalizedArtifactDigest.orEmpty())
            } catch (exception: Exception) {
                return AgentPlan.Failed(
                    StudyBlockReason.RUNTIME_UNAVAILABLE,
                    exception.message ?: "the packaged agent could not be installed",
                )
            }
        val ready =
            when (install) {
                is PackagedAgentInstall.Blocked ->
                    return AgentPlan.Failed(StudyBlockReason.RUNTIME_UNAVAILABLE, install.detail)
                is PackagedAgentInstall.Ready -> install
            }
        return AgentPlan.Ready(argv = ready.argv, digest = ready.digest)
    }

    /**
     * BYOA agent contract: resolve the participant-installed executable from the
     * release metadata, a configured settings command, or the documented
     * discovery order. A missing agent blocks with `AGENT_NOT_FOUND`; there is no
     * silent fallback to another executable.
     */
    private fun byoaAgentPlan(validManifest: BootstrapManifest): AgentPlan {
        val release = validManifest.agentRelease
        val profile = validManifest.agentProfile
        val gateway = validManifest.inferenceGateway
        // Version skew fails closed: a study Goose must never run on the
        // participant's own provider key. A pre-budget server issues a Goose
        // manifest without the gateway block, so the release identity alone
        // decides, regardless of what the release binds. Codex and the
        // packaged runtime never reach this check.
        if (gateway == null && isGooseRelease(release)) {
            return AgentPlan.Failed(StudyBlockReason.RUNTIME_UNAVAILABLE, GOOSE_GATEWAY_REQUIRED_DETAIL)
        }
        // Fail closed in both directions: a manifest that carries the research
        // inference gateway needs a release able to deliver the credential, and
        // a release that binds one must never launch an agent that would fall
        // back to the participant's own provider settings.
        credentialBindingViolation(release.configBindings, gatewayPresent = gateway != null)?.let { violation ->
            return AgentPlan.Failed(StudyBlockReason.RUNTIME_UNAVAILABLE, violation)
        }
        val runtime =
            if (gateway != null) {
                gatewayRuntimeValues(validManifest, gateway).getOrElse { failure ->
                    return AgentPlan.Failed(
                        StudyBlockReason.RUNTIME_UNAVAILABLE,
                        failure.message ?: "the study AI gateway could not be configured",
                    )
                }
            } else {
                null
            }
        // Defensive parity with server-side study creation: a profile or gateway
        // field the release does not translate must never silently not govern
        // the agent.
        if (profile != null || runtime != null) {
            val missing = missingByoaBindings(release.configBindings, profile, runtime)
            if (missing.isNotEmpty()) {
                return AgentPlan.Failed(
                    StudyBlockReason.RUNTIME_UNAVAILABLE,
                    "the release declares no configuration translation for " +
                        missing.joinToString(", ") +
                        "; refusing to launch an agent whose study configuration would not govern it",
                )
            }
        }
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
        val mapping = applyByoaConfiguration(release.configBindings, profile, runtime)
        val credential =
            if (gateway != null) {
                val envKey =
                    mapping.credentialEnvKey
                        ?: return AgentPlan.Failed(
                            StudyBlockReason.RUNTIME_UNAVAILABLE,
                            "the release binds no environment variable for the study AI credential; refusing to launch",
                        )
                val bearer =
                    validManifest.inferenceBearer()
                        ?: return AgentPlan.Failed(
                            StudyBlockReason.RUNTIME_UNAVAILABLE,
                            "the study manifest carries no usable inference capability; refusing to launch",
                        )
                InferenceCredentialPlan(envKey, bearer)
            } else {
                null
            }
        return when (resolution) {
            is ByoaAgentResolution.NotFound ->
                AgentPlan.Failed(StudyBlockReason.AGENT_NOT_FOUND, resolution.detail)
            is ByoaAgentResolution.Resolved ->
                AgentPlan.Ready(
                    argv = resolution.argv + mapping.args,
                    digest = resolution.identity.digest,
                    agentEnv = mapping.env,
                    inferenceCredential = credential,
                )
        }
    }

    /**
     * The non-secret gateway values for a gateway-bound BYOA agent. The host is
     * the origin this plugin bootstrapped from (never a manifest value, so a
     * manifest cannot redirect prompts) and the state directory is plugin-owned
     * and owner-only, so the participant's own agent configuration (for example
     * `~/.config/goose`) can never bypass the gateway.
     */
    private fun gatewayRuntimeValues(
        validManifest: BootstrapManifest,
        gateway: InferenceGatewayRef,
    ): Result<ByoaRuntimeValues> =
        runCatching {
            val origin =
                serverOrigin()
                    ?: error(
                        "the research server origin is unknown, so the study AI gateway cannot be configured; " +
                            "check the Code4Me server settings and reconnect",
                    )
            val stateDir =
                agentStateDirFor(validManifest.enrollmentId)
                    ?: error("the agent state directory could not be created under the research runtime root")
            ByoaRuntimeValues(
                host = origin,
                basePath = gateway.basePath,
                providerKind = gateway.providerKind,
                stateDir = stateDir.toString(),
            )
        }

    /** `scheme://host[:port]` of the configured research server, or `null` when unknown. */
    private fun serverOrigin(): String? {
        val base = safeValue { serverBaseUrlProvider() } ?: return null
        val uri =
            try {
                URI(base.trim())
            } catch (_: Exception) {
                return null
            }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        val authority = if (uri.port != -1) "$host:${uri.port}" else host
        return "$scheme://$authority"
    }

    /**
     * One typed agent-resolution outcome for a resolved proxy runtime.
     *
     * A [Ready] plan with an empty [argv] means the dev (source) runtime runs the
     * proxy without a packaged agent; [digest] is then `null` too.
     */
    private sealed interface AgentPlan {
        data class Ready(
            val argv: List<String>,
            val digest: String?,
            val agentEnv: Map<String, String> = emptyMap(),
            /** The study AI credential a gateway-bound agent receives, if any. */
            val inferenceCredential: InferenceCredentialPlan? = null,
        ) : AgentPlan

        data class Failed(val reason: StudyBlockReason, val detail: String) : AgentPlan
    }

    /**
     * The credential a gateway-bound agent receives: the env key the release
     * binds and the bearer derived from the manifest. Not a data class and
     * redacted in [toString], so it can never leak through a log or a message.
     */
    private class InferenceCredentialPlan(
        val envKey: String,
        val bearer: String,
    ) {
        override fun toString(): String = "InferenceCredentialPlan(envKey=$envKey, bearer=<redacted>)"
    }

    /** The written inference credential file and the env key it names. */
    private data class InferenceCredentialHandle(
        val file: Path,
        val envKey: String,
    )

    /**
     * Resolve the stable capability file for [enrollmentId] in this execution
     * context.
     *
     * A configured provider path wins. Otherwise the path is **stable per
     * (enrollment, context)** under the research root, never a fresh temp file,
     * because the ACP entry is persistent and the AI Assistant may launch the
     * proxy repeatedly: every launch must read the same file the plugin rewrote
     * on activation. It is context-scoped like the inference credential file
     * (D-03): two open projects of one enrollment each hold their own IPC
     * capability, so one window's proxy never presents the other window's token.
     * The plugin owns the file and deletes it only on teardown.
     */
    private fun capabilityFileFor(enrollmentId: String): Path? =
        try {
            capabilityFilePathProvider()?.toAbsolutePath()?.normalize()
                ?: capabilityRootProvider()
                    .resolve(
                        "$CAPABILITY_FILE_PREFIX${opaqueSessionKey(sessionKey(enrollmentId))}$CAPABILITY_FILE_EXTENSION",
                    )
        } catch (_: Exception) {
            null
        }

    /**
     * Resolve the frozen telemetry policy file for [enrollmentId].
     *
     * It lives beside the one-time capability in the same owner-only root and is
     * rewritten on every activation. The proxy reads it but never deletes it.
     */
    private fun policyFileFor(enrollmentId: String): Path? =
        try {
            val capability = capabilityFileFor(enrollmentId)
            val root =
                capability?.toAbsolutePath()?.normalize()?.parent
                    ?: capabilityRootProvider().toAbsolutePath().normalize()
            root.resolve("$POLICY_FILE_PREFIX${opaqueSessionKey(enrollmentId)}$POLICY_FILE_EXTENSION")
        } catch (_: Exception) {
            null
        }

    /**
     * Resolve the proxy's content-free delivery status document for
     * [enrollmentId] in this execution context.
     *
     * A configured provider path wins. Otherwise it sits beside the frozen
     * telemetry policy (same owner-only root) and is keyed by the non-reversible
     * [opaqueSessionKey] of the (enrollment, context) pair, never the raw
     * enrollment id, so two windows' proxies never double-count into one
     * document (D-03/D-05). The proxy owns the file (it writes and rewrites
     * it); the plugin only reads it.
     */
    private fun statusFileFor(enrollmentId: String): Path? =
        try {
            statusFilePathProvider()?.toAbsolutePath()?.normalize()
                ?: run {
                    val capability = capabilityFileFor(enrollmentId)
                    val root =
                        capability?.toAbsolutePath()?.normalize()?.parent
                            ?: capabilityRootProvider().toAbsolutePath().normalize()
                    root.resolve("$STATUS_FILE_PREFIX${opaqueSessionKey(sessionKey(enrollmentId))}$STATUS_FILE_EXTENSION")
                }
        } catch (_: Exception) {
            null
        }

    /** The owner-only root the launch files for [enrollmentId] live in (beside the capability). */
    private fun runtimeRootFor(enrollmentId: String): Path =
        capabilityFileFor(enrollmentId)?.toAbsolutePath()?.normalize()?.parent
            ?: capabilityRootProvider().toAbsolutePath().normalize()

    /**
     * The plugin-owned state directory a gateway-bound agent is pointed at
     * (`state_dir`, for Goose `GOOSE_PATH_ROOT`): created owner-only under the
     * research runtime root, keyed by the non-reversible enrollment key. It
     * isolates the agent's own configuration from the participant's home so
     * their provider settings can never bypass the gateway. `null` on failure.
     */
    private fun agentStateDirFor(enrollmentId: String): Path? =
        try {
            val directory = runtimeRootFor(enrollmentId).resolve("$AGENT_STATE_DIR_PREFIX${opaqueSessionKey(enrollmentId)}")
            Files.createDirectories(directory)
            try {
                Files.setPosixFilePermissions(
                    directory,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                )
            } catch (_: UnsupportedOperationException) {
                // Windows and non-POSIX filesystems: the ACL keeps the directory user-scoped.
            }
            directory
        } catch (_: Exception) {
            null
        }

    /**
     * The stable, plugin-owned inference credential file for [enrollmentId] in
     * this execution context. Context-scoped (unlike the shared state
     * directory): each window rewrites and deletes only its own file, so closing
     * one window never strands a launch registered by another window of the
     * same enrollment. The proxy reads it and never deletes it.
     */
    private fun inferenceCredentialFileFor(enrollmentId: String): Path? =
        try {
            runtimeRootFor(enrollmentId).resolve(
                "$INFERENCE_CREDENTIAL_FILE_PREFIX${opaqueSessionKey(sessionKey(enrollmentId))}$INFERENCE_CREDENTIAL_FILE_EXTENSION",
            )
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
                    StudyBlockReason.POLICY_INVALID,
                    StudyBlockReason.AI_ASSISTANT_MISSING,
                    StudyBlockReason.AI_ASSISTANT_OUTDATED,
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
            // D-1: the enrollment/UI is the consent authority. Mirror the
            // server-provided flag; absent means false (fail closed).
            consentActive = validManifest.policies.telemetry?.consentActive ?: false,
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
        return blockReasonFor(result.rejection)
    }

    private fun blockReasonFor(rejection: BootstrapRejection?): StudyBlockReason =
        when (rejection) {
            BootstrapRejection.CONSENT_REQUIRED -> StudyBlockReason.CONSENT_REQUIRED
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
            // A temporary operator pause, never a revocation (D-04).
            BootstrapRejection.KILL_SWITCH_ENGAGED -> StudyBlockReason.KILL_SWITCH_ENGAGED
            BootstrapRejection.ENROLLMENT_NOT_FOUND,
            BootstrapRejection.ENROLLMENT_NOT_ACTIVE,
            BootstrapRejection.INELIGIBLE,
            BootstrapRejection.STUDY_NOT_OPEN,
            BootstrapRejection.STUDY_CLOSED,
            BootstrapRejection.STUDY_STOPPED,
            BootstrapRejection.STUDY_MISMATCH,
            BootstrapRejection.ASSIGNMENT_MISMATCH,
            BootstrapRejection.UNKNOWN,
            null,
            -> StudyBlockReason.REVOKED
        }

    companion object {
        const val DEFAULT_RESUME_GRACE_MS: Long = 120_000L
        const val DEFAULT_IDLE_TIMEOUT_MS: Long = 600_000L

        /** How long a closing project keeps its spool IPC endpoint for in-flight proxies. */
        const val IPC_CLOSE_GRACE_MS: Long = 5_000L

        /** Upper bound of the single delivery attempt [stop] makes before closing the uploader. */
        const val STOP_DRAIN_TIMEOUT_MS: Long = 3_000L

        /**
         * How long refresh authentication may keep failing after the held
         * capability expired before `AUTHENTICATION_REQUIRED` is shown.
         */
        const val AUTH_RETRY_BLOCK_AFTER_MS: Long = 30L * 60_000L

        /** Participant text for [StudyBlockReason.AUTHENTICATION_REQUIRED]. */
        const val AUTHENTICATION_REQUIRED_DETAIL: String = "Sign in again to continue the study"

        private const val IDLE_TIMEOUT_REASON = "idle timeout"

        /** Agent events arrive per streamed chunk; the session marker needs far fewer writes. */
        private const val AGENT_ACTIVITY_THROTTLE_MS: Long = 1_000L

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
        private const val ACTIVITY_PATH = "/api/research/sessions/activity"

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_CONFLICT = 409

        private const val REVOKED_CODE = "REVOKED"
        private const val ENROLLMENT_NOT_ACTIVE_CODE = "ENROLLMENT_NOT_ACTIVE"
        private const val STUDY_STOPPED_CODE = "STUDY_STOPPED"
        private const val KILL_SWITCH_CODE = "KILL_SWITCH_ENGAGED"
        private const val SESSION_TERMINAL_CODE = "SESSION_TERMINAL"

        /**
         * The study declares no usable session policy, so session create and
         * heartbeat are refused non-retryably until a researcher fixes it
         * (ISSUE-05). `SESSION_POLICY_INVALID` is the sibling study-creation
         * code for the same contract.
         */
        private const val POLICY_MISSING_CODE = "POLICY_MISSING"
        private const val SESSION_POLICY_INVALID_CODE = "SESSION_POLICY_INVALID"

        private const val SERVER_STATE_ENDED = "ended"
        private const val SERVER_STATE_REVOKED = "revoked"

        /** Stable, plugin-owned capability file name under the research root. */
        private const val CAPABILITY_FILE_PREFIX = "capability-"
        private const val CAPABILITY_FILE_EXTENSION = ".txt"

        /** Stable, plugin-owned frozen telemetry policy file name. */
        private const val POLICY_FILE_PREFIX = "telemetry-policy-"
        private const val POLICY_FILE_EXTENSION = ".json"

        /** Stable, proxy-owned content-free delivery status file name. */
        private const val STATUS_FILE_PREFIX = "telemetry-status-"
        private const val STATUS_FILE_EXTENSION = ".json"

        /** Stable, plugin-owned inference credential file name (gateway-bound agents). */
        private const val INFERENCE_CREDENTIAL_FILE_PREFIX = "inference-credential-"
        private const val INFERENCE_CREDENTIAL_FILE_EXTENSION = ".json"

        /** Plugin-owned, owner-only agent state directory name (gateway-bound agents). */
        private const val AGENT_STATE_DIR_PREFIX = "agent-state-"

        /** The logical Goose identity, as the BYOA resolver's package lookup spells it. */
        private const val GOOSE_AGENT_IDENTITY = "goose"

        /** Participant-readable refusal for a Goose manifest that carries no inference gateway. */
        internal const val GOOSE_GATEWAY_REQUIRED_DETAIL =
            "this study's Goose arm needs the research inference gateway; the server did not issue one — " +
                "update the server/plugin"

        /**
         * Whether a BYOA release identifies Goose, by the same identity the
         * resolver uses: `agent_id`, the logical `agent_package`, or the
         * `agent_command` executable name, normalized (trimmed, lowercase,
         * without a `.exe` suffix) like the resolver's package lookup.
         */
        internal fun isGooseRelease(release: AgentReleaseRef): Boolean {
            if (!release.isByoa) return false
            val names =
                listOfNotNull(
                    release.agentId,
                    release.agentPackage,
                    release.agentCommand?.substringAfterLast('/')?.substringAfterLast('\\'),
                )
            return names.any { it.trim().lowercase().removeSuffix(".exe") == GOOSE_AGENT_IDENTITY }
        }

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

        /** Logger for the production uploader's diagnostics (a companion cannot use the instance logger). */
        private val uploaderLog: Logger by lazy { Logger.getInstance(ResearchSessionManager::class.java) }

        private fun defaultUploader(context: SpoolUploaderContext): SpoolDelivery =
            SpoolUploader(
                spool = context.spool,
                serverBaseUrl = context.serverBaseUrl,
                sessionCapability = context.sessionCapability,
                clientInstanceId = context.clientInstanceId,
                httpClient = context.httpClient,
                // Discards and delivery stops must reach idea.log: the uploader's
                // diagnostics carry counts and typed reasons, never ids or payloads.
                diagnostics = { message -> uploaderLog.warn(message) },
                researchSessionId = context.researchSessionId,
            )
    }
}
