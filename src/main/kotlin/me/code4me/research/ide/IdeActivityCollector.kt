package me.code4me.research.ide

import me.code4me.research.telemetry.CanonicalEvent
import me.code4me.research.telemetry.CanonicalEventBuilder
import me.code4me.research.telemetry.Coverage
import me.code4me.research.telemetry.CoverageState
import me.code4me.research.telemetry.EventSource
import me.code4me.research.telemetry.LocalClock
import me.code4me.research.telemetry.sha256Hex
import java.time.Instant
import java.util.UUID

/** Receives canonical events emitted by a collector (spool/filter boundary). */
fun interface CanonicalEventSink {
    fun emit(event: CanonicalEvent)
}

/**
 * The active, manifest-scoped collection context.
 *
 * A collector must not emit or buffer anything until it has an active scope:
 * unscoped IDE content has no study/assignment/session attribution and must be
 * dropped.
 */
data class IdeCollectionScope(
    val researchSessionId: String,
    val studyId: String? = null,
    val enrollmentId: String? = null,
    val assignmentId: String? = null,
    val profileDigest: String? = null,
    val manifestDigest: String? = null,
) {
    init {
        require(researchSessionId.isNotBlank()) { "researchSessionId must not be blank" }
    }
}

/** Documented IDE source kinds and their canonical activity mapping. */
enum class IdeActivityKind(val wire: String, val activityType: IdeActivityType) {
    OPENED("opened", IdeActivityType.FILE_OPENED),
    CHANGED("changed", IdeActivityType.DOCUMENT_CHANGED),
    SAVED("saved", IdeActivityType.FILE_SAVED),
    CLOSED("closed", IdeActivityType.FILE_CLOSED),
    RUN_EXECUTED("run.executed", IdeActivityType.RUN_EXECUTED),
    ;

    companion object {
        fun fromWire(raw: String?): IdeActivityKind? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
    }
}

/** Deterministic, non-reversible project context id (never the raw path). */
fun opaqueIdeContextId(projectKey: String): String = "ide-" + sha256Hex("code4me.ide.context.v1:$projectKey").take(32)

/**
 * Source-agnostic IDE activity collector (Issue 10).
 *
 * Responsibilities and guarantees:
 * - it emits nothing before [activate] (no unscoped buffering), and drops any
 *   signal that arrives while inactive;
 * - it maps signals through [IdeActivityEventBuilder], so only allowlisted
 *   metadata keys survive and content/text/prompt/diff keys are rejected;
 * - it allocates a strictly increasing per-emitter sequence per project;
 * - it derives a distinct opaque context/emitter per project, so events from two
 *   projects are never cross-attributed;
 * - it forwards finished canonical events to a [CanonicalEventSink].
 *
 * Pure JVM: it never imports the IntelliJ platform and never touches the network.
 */
class IdeActivityCollector(
    private val source: IdeActivitySource,
    private val sink: CanonicalEventSink,
    private val normalizerVersion: String = "ide-collector-v1",
    private val contextIdFactory: (String) -> String = ::opaqueIdeContextId,
    private val emitterIdFactory: (String) -> String = { contextId -> "ide:$contextId" },
    private val clock: LocalClock = LocalClock("ide-collector"),
    private val wallClock: () -> Instant = { Instant.now() },
    private val eventIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private class ProjectContext(
        val contextId: String,
        val eventBuilder: IdeActivityEventBuilder,
        val canonicalBuilder: CanonicalEventBuilder,
    )

    private val lock = Any()
    private var scope: IdeCollectionScope? = null
    private var started = false
    private val projects = HashMap<String, ProjectContext>()

    /** True while a manifest-scoped session is active and signals may be emitted. */
    val isActive: Boolean
        get() = synchronized(lock) { scope != null }

    /** Subscribe to the source. Signals are still dropped until [activate]. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        source.onActivity { signal -> onSignal(signal) }
    }

    /** Stop forwarding signals (unscoped signals continue to be dropped). */
    fun stop() {
        synchronized(lock) { started = false }
    }

    /** Activate collection for [scope]; only now may events be emitted. */
    fun activate(scope: IdeCollectionScope) {
        synchronized(lock) { this.scope = scope }
    }

    /** Deactivate collection (for example IDE close, expiry, or revocation). */
    fun deactivate() {
        synchronized(lock) { scope = null }
    }

    /** The opaque context id the collector assigns to [projectKey]. */
    fun contextIdFor(projectKey: String): String = contextIdFactory(projectKey)

    private fun onSignal(signal: IdeActivitySignal) {
        val active = synchronized(lock) { if (started) scope else null } ?: return
        val kind = IdeActivityKind.fromWire(signal.kind)
        val context =
            synchronized(lock) {
                projects.getOrPut(signal.projectKey) { newProjectContext(signal.projectKey) }
            }

        // Untrusted keys are rejected here; content/text keys never reach a payload.
        val observation =
            context.eventBuilder.buildFromRawKeys(
                activityType = kind?.activityType ?: IdeActivityType.UNKNOWN,
                raw = signal.metadata,
            )

        val canonical =
            context.eventBuilder.toCanonicalEvent(
                event = observation,
                canonical = context.canonicalBuilder,
                studyId = active.studyId,
                enrollmentId = active.enrollmentId,
                researchSessionId = active.researchSessionId,
                coverage =
                    kind?.let { Coverage(CoverageState.AVAILABLE) }
                        ?: Coverage(
                            state = CoverageState.NEEDS_REVIEW,
                            reason = "unrecognized IDE activity kind preserved for review",
                        ),
                unknownEventType = if (kind == null) signal.kind else null,
            )

        sink.emit(canonical)
    }

    private fun newProjectContext(projectKey: String): ProjectContext {
        val contextId = contextIdFactory(projectKey)
        val emitterId = emitterIdFactory(contextId)
        return ProjectContext(
            contextId = contextId,
            eventBuilder =
                IdeActivityEventBuilder(
                    projectId = contextId,
                    emitterId = emitterId,
                    clock = clock,
                    wallClock = wallClock,
                ),
            canonicalBuilder =
                CanonicalEventBuilder(
                    emitterId = emitterId,
                    source = EventSource.IDE,
                    normalizerVersion = normalizerVersion,
                    clock = clock,
                    eventIdFactory = eventIdFactory,
                    wallClock = wallClock,
                ),
        )
    }
}
