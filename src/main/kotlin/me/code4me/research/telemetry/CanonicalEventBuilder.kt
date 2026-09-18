package me.code4me.research.telemetry

import java.time.Instant
import java.util.UUID

/**
 * A reading taken from a specific monotonic clock.
 *
 * Latency is only meaningful between two readings of the *same* clock inside
 * one process; the [clockId] makes cross-process subtraction impossible instead
 * of merely discouraged.
 */
data class MonotonicTimestamp(val clockId: String, val valueNs: Long)

/**
 * A named monotonic clock. `System.nanoTime()` is per-JVM and must never be
 * compared against another process's readings, so each clock carries an id.
 */
class LocalClock(
    val clockId: String,
    private val source: () -> Long = { System.nanoTime() },
) {
    init {
        require(clockId.isNotBlank()) { "clockId must not be blank" }
    }

    fun now(): MonotonicTimestamp = MonotonicTimestamp(clockId, source())
}

/**
 * Strictly increasing sequence allocator. Counters are independent per emitter:
 * two emitters sharing one allocator never share a counter and never observe a
 * global order. Thread-safe so several collectors can share one instance.
 */
class SequenceAllocator(private val start: Long = 1L) {
    private val lastAllocated = HashMap<String, Long>()

    @Synchronized
    fun next(emitterId: String): Long {
        require(emitterId.isNotBlank()) { "emitterId must not be blank" }
        val next = (lastAllocated[emitterId] ?: (start - 1L)) + 1L
        lastAllocated[emitterId] = next
        return next
    }

    @Synchronized
    fun current(emitterId: String): Long = lastAllocated[emitterId] ?: (start - 1L)
}

/**
 * Builds immutable [CanonicalEvent] envelopes for one emitter.
 *
 * The builder owns exactly two things sources must not invent:
 * - a fresh, immutable `event_id`; and
 * - a strictly increasing `emitter_sequence` for its emitter.
 *
 * It computes the canonical SHA-256 of an event and it deliberately exposes no
 * wall-clock-difference or cross-process latency API. Latency must be measured
 * with [recordLocalLatencyNs] from two [MonotonicTimestamp] readings of the
 * same clock.
 */
class CanonicalEventBuilder(
    val emitterId: String,
    val source: EventSource,
    val normalizerVersion: String,
    val adapterVersion: String? = null,
    val allocator: SequenceAllocator = SequenceAllocator(),
    val clock: LocalClock = LocalClock("emitter:$emitterId"),
    private val eventIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val wallClock: () -> Instant = { Instant.now() },
) {
    init {
        require(emitterId.isNotBlank()) { "emitterId must not be blank" }
        require(normalizerVersion.isNotBlank()) { "normalizerVersion must not be blank" }
    }

    /**
     * Build one canonical event with a fresh id and an allocated per-emitter
     * sequence, unless explicit values are supplied (used when a caller already
     * allocated them, for example the IDE activity builder).
     */
    fun build(
        eventType: String,
        payload: Map<String, Any?> = emptyMap(),
        metrics: EventMetrics = EventMetrics(),
        coverage: Coverage = Coverage(),
        privacy: PrivacySummary = PrivacySummary(),
        fidelity: CanonicalFidelity = CanonicalFidelity.NORMALIZED,
        studyId: String? = null,
        enrollmentId: String? = null,
        researchSessionId: String? = null,
        agentRunId: String? = null,
        correlations: Correlations = Correlations(),
        lifecycleState: String? = null,
        unknownEventType: String? = null,
        unknownSource: String? = null,
        unknownLifecycleState: String? = null,
        sourceEventId: String? = null,
        evidenceDigest: String? = null,
        occurredAt: String? = null,
        monotonicNs: Long? = null,
        emitterSequence: Long? = null,
        eventId: String? = null,
    ): CanonicalEvent {
        val resolvedEventId = eventId ?: eventIdFactory()
        val resolvedSequence = emitterSequence ?: allocator.next(emitterId)
        val resolvedMonotonic = monotonicNs ?: clock.now().valueNs
        return CanonicalEvent(
            eventId = resolvedEventId,
            eventType = eventType,
            source = source,
            studyId = studyId,
            enrollmentId = enrollmentId,
            researchSessionId = researchSessionId,
            agentRunId = agentRunId,
            occurredAt = occurredAt ?: wallClock().toString(),
            monotonicNs = resolvedMonotonic,
            emitterId = emitterId,
            emitterSequence = resolvedSequence,
            correlations = correlations,
            lifecycleState = lifecycleState,
            payload = payload,
            metrics = metrics,
            coverage = coverage,
            privacy = privacy,
            provenance =
                Provenance(
                    source = source,
                    sourceEventId = sourceEventId,
                    normalizerVersion = normalizerVersion,
                    adapterVersion = adapterVersion,
                    fidelity = fidelity,
                    evidenceDigest = evidenceDigest,
                ),
            unknownEventType = unknownEventType,
            unknownSource = unknownSource,
            unknownLifecycleState = unknownLifecycleState,
        )
    }

    /** Lowercase hex SHA-256 of the event's canonical JSON bytes. */
    fun digest(event: CanonicalEvent): String = sha256Hex(event.toCanonicalJson())

    /**
     * Local latency between two readings of the *same* monotonic clock.
     *
     * @throws IllegalArgumentException when the readings come from different
     * clocks (cross-process latency) or run backwards.
     */
    fun recordLocalLatencyNs(
        startNs: MonotonicTimestamp,
        endNs: MonotonicTimestamp,
    ): Long {
        require(startNs.clockId == endNs.clockId) {
            "Latency requires readings from one monotonic clock, got '${startNs.clockId}' and '${endNs.clockId}'"
        }
        require(endNs.valueNs >= startNs.valueNs) {
            "Monotonic clock '${startNs.clockId}' moved backwards"
        }
        return endNs.valueNs - startNs.valueNs
    }
}
