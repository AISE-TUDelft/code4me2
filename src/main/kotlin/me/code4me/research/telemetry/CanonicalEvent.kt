package me.code4me.research.telemetry

/**
 * Immutable canonical telemetry envelope (Issue 06, `CanonicalEventV1`).
 *
 * The shape mirrors the server `research.telemetry.models.CanonicalEventV1`
 * exactly: the JSON produced by [toCanonicalMap] uses the same field names and
 * the same nested `correlations` / `metrics` / `privacy` / `provenance` /
 * `coverage` objects, so the server can reproduce the canonical bytes and the
 * SHA-256 digest.
 *
 * Design rules encoded here:
 * - The envelope is immutable; corrections are new events, never edits.
 * - `usage_tokens` is nullable. `null` + [CoverageState.UNAVAILABLE] means "the
 *   source does not expose it" and is never coerced to `0`; an observed `0`
 *   stays `0` with [CoverageState.AVAILABLE].
 * - [emitterSequence] is a strictly increasing per-emitter counter, allocated by
 *   [CanonicalEventBuilder]. There is no fabricated global order.
 * - Global ordering is a query projection over `occurredAt` / `monotonicNs`,
 *   never a stored fact.
 * - [payload] holds only privacy-filtered content; the privacy filter runs
 *   before the event reaches a spool, log, retry queue, or network.
 *
 * @property eventId globally unique, immutable UUID string.
 * @property schemaVersion canonical schema version, currently `"1"`.
 * @property eventType a [CanonicalEventTypes] concept or
 * [CanonicalEventTypes.UNKNOWN_SOURCE_EVENT].
 * @property source which observation channel emitted the event.
 * @property occurredAt ISO-8601 UTC wall-clock timestamp.
 * @property monotonicNs per-emitter monotonic clock reading, when provided.
 * @property coverage event-level coverage state.
 * @property privacy per-event policy actions applied by the filter.
 */
data class CanonicalEvent(
    val eventId: String,
    val schemaVersion: String = "1",
    val eventType: String,
    val source: EventSource,
    val studyId: String? = null,
    val enrollmentId: String? = null,
    val researchSessionId: String? = null,
    val agentRunId: String? = null,
    val occurredAt: String,
    val monotonicNs: Long? = null,
    val emitterId: String,
    val emitterSequence: Long,
    val correlations: Correlations = Correlations(),
    val lifecycleState: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
    val metrics: EventMetrics = EventMetrics(),
    val privacy: PrivacySummary = PrivacySummary(),
    val provenance: Provenance,
    val coverage: Coverage = Coverage(),
    val unknownEventType: String? = null,
    val unknownSource: String? = null,
    val unknownLifecycleState: String? = null,
) {
    init {
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(eventType.isNotBlank()) { "eventType must not be blank" }
        require(emitterId.isNotBlank()) { "emitterId must not be blank" }
        require(emitterSequence >= 1) { "emitterSequence must start at 1" }
    }

    /**
     * The canonical map form of this event. Keys are sorted by [canonicalJson],
     * so the returned text is stable. Null metrics are preserved explicitly: a
     * missing `usage_tokens` is serialized as JSON `null`, never dropped and
     * never `0`.
     */
    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "event_id" to eventId,
            "schema_version" to schemaVersion,
            "event_type" to eventType,
            "source" to source.value,
            "study_id" to studyId,
            "enrollment_id" to enrollmentId,
            "research_session_id" to researchSessionId,
            "agent_run_id" to agentRunId,
            "occurred_at" to occurredAt,
            "monotonic_ns" to monotonicNs,
            "emitter_id" to emitterId,
            "emitter_sequence" to emitterSequence,
            "correlations" to correlations.toCanonicalMap(),
            "lifecycle_state" to lifecycleState,
            "payload" to payload,
            "metrics" to metrics.toCanonicalMap(),
            "privacy" to privacy.toCanonicalMap(),
            "provenance" to provenance.toCanonicalMap(),
            "coverage" to coverage.toCanonicalMap(),
            "unknown_event_type" to unknownEventType,
            "unknown_source" to unknownSource,
            "unknown_lifecycle_state" to unknownLifecycleState,
        )

    /** Canonical JSON bytes; the digest source that the server can reproduce. */
    fun toCanonicalJson(): String = canonicalJson(toCanonicalMap())

    /** Lowercase hex SHA-256 of [toCanonicalJson]. */
    fun digest(): String = sha256Hex(toCanonicalJson())

    companion object {
        /** Rehydrate an event from [toCanonicalMap] output (used by the durable spool). */
        @Suppress("UNCHECKED_CAST")
        fun fromCanonicalMap(map: Map<String, Any?>): CanonicalEvent {
            val correlations = (map["correlations"] as? Map<String, Any?>) ?: emptyMap()
            val metrics = (map["metrics"] as? Map<String, Any?>) ?: emptyMap()
            val privacy = (map["privacy"] as? Map<String, Any?>) ?: emptyMap()
            val provenanceMap =
                map["provenance"] as? Map<String, Any?>
                    ?: throw IllegalArgumentException("Canonical event is missing provenance")
            return CanonicalEvent(
                eventId = map["event_id"] as? String ?: throw IllegalArgumentException("Missing event_id"),
                schemaVersion = map["schema_version"] as? String ?: "1",
                eventType = map["event_type"] as? String ?: throw IllegalArgumentException("Missing event_type"),
                source =
                    EventSource.fromWire(map["source"] as? String)
                        ?: throw IllegalArgumentException("Unknown event source: ${map["source"]}"),
                studyId = map["study_id"] as? String,
                enrollmentId = map["enrollment_id"] as? String,
                researchSessionId = map["research_session_id"] as? String,
                agentRunId = map["agent_run_id"] as? String,
                occurredAt = map["occurred_at"] as? String ?: throw IllegalArgumentException("Missing occurred_at"),
                monotonicNs = (map["monotonic_ns"] as? Number)?.toLong(),
                emitterId = map["emitter_id"] as? String ?: throw IllegalArgumentException("Missing emitter_id"),
                emitterSequence =
                    (map["emitter_sequence"] as? Number)?.toLong()
                        ?: throw IllegalArgumentException("Missing emitter_sequence"),
                correlations = Correlations.fromCanonicalMap(correlations),
                lifecycleState = map["lifecycle_state"] as? String,
                payload = (map["payload"] as? Map<String, Any?>) ?: emptyMap(),
                metrics = EventMetrics.fromCanonicalMap(metrics),
                privacy = PrivacySummary.fromCanonicalMap(privacy),
                provenance = Provenance.fromCanonicalMap(provenanceMap),
                coverage =
                    (map["coverage"] as? Map<String, Any?>)?.let { Coverage.fromCanonicalMap(it) }
                        ?: Coverage(),
                unknownEventType = map["unknown_event_type"] as? String,
                unknownSource = map["unknown_source"] as? String,
                unknownLifecycleState = map["unknown_lifecycle_state"] as? String,
            )
        }

        /** Rehydrate from canonical JSON text. */
        fun fromCanonicalJson(text: String): CanonicalEvent {
            val parsed = parseCanonicalJson(text)
            require(parsed is Map<*, *>) { "Canonical event JSON must be an object" }
            return fromCanonicalMap(Cast.castToStringKeyedMap(parsed))
        }
    }
}

/** Opaque correlation handles that tie related events together. */
data class Correlations(
    val turnId: String? = null,
    val toolCallId: String? = null,
    val permissionId: String? = null,
    val editId: String? = null,
    val correlationId: String? = null,
) {
    /** The canonical map representation used for hashing and transport. */
    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "turn_id" to turnId,
            "tool_call_id" to toolCallId,
            "permission_id" to permissionId,
            "edit_id" to editId,
            "correlation_id" to correlationId,
        )

    companion object {
        fun fromCanonicalMap(map: Map<String, Any?>): Correlations =
            Correlations(
                turnId = map["turn_id"] as? String,
                toolCallId = map["tool_call_id"] as? String,
                permissionId = map["permission_id"] as? String,
                editId = map["edit_id"] as? String,
                correlationId = map["correlation_id"] as? String,
            )
    }
}

/**
 * Numeric measurements attached to an event.
 *
 * [usageTokens] is `null` when unexposed and is paired with [usageCapability]:
 * an observed zero is `0L` with [CoverageState.AVAILABLE], while an unexposed
 * count is `null` with [CoverageState.UNAVAILABLE].
 */
data class EventMetrics(
    val usageTokens: Long? = null,
    val usageCapability: Coverage = Coverage(),
    val latencyMs: Long? = null,
    val counts: Map<String, Long> = emptyMap(),
) {
    /** The canonical map representation used for hashing and transport. */
    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "usage_tokens" to usageTokens,
            "usage_capability" to usageCapability.toCanonicalMap(),
            "latency_ms" to latencyMs,
            "counts" to counts,
        )

    companion object {
        fun fromCanonicalMap(map: Map<String, Any?>): EventMetrics {
            val capability =
                (map["usage_capability"] as? Map<String, Any?>)?.let {
                    Coverage.fromCanonicalMap(it)
                } ?: Coverage()
            val rawCounts = map["counts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val counts = LinkedHashMap<String, Long>()
            for ((key, value) in rawCounts) {
                val name = key?.toString()
                val count = (value as? Number)?.toLong()
                if (name != null && count != null) counts[name] = count
            }
            return EventMetrics(
                usageTokens = (map["usage_tokens"] as? Number)?.toLong(),
                usageCapability = capability,
                latencyMs = (map["latency_ms"] as? Number)?.toLong(),
                counts = counts,
            )
        }
    }
}

/**
 * What the privacy filter did to one event.
 *
 * [actions] is keyed by [PolicyAction.value] and [fieldClasses] by
 * [FieldClass.value], matching the server `PrivacySummary` exactly.
 */
data class PrivacySummary(
    val policyDigest: String? = null,
    val actions: Map<String, Long> = emptyMap(),
    val redactedFields: List<String> = emptyList(),
    val blocked: Boolean = false,
    val blockReason: String? = null,
    val fieldClasses: Map<String, Long> = emptyMap(),
) {
    /** The canonical map representation used for hashing and transport. */
    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "policy_digest" to policyDigest,
            "actions" to actions,
            "redacted_fields" to redactedFields,
            "blocked" to blocked,
            "block_reason" to blockReason,
            "field_classes" to fieldClasses,
        )

    companion object {
        fun fromCanonicalMap(map: Map<String, Any?>): PrivacySummary {
            val redacted = (map["redacted_fields"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            return PrivacySummary(
                policyDigest = map["policy_digest"] as? String,
                actions = intMap(map["actions"]),
                redactedFields = redacted,
                blocked = map["blocked"] as? Boolean ?: false,
                blockReason = map["block_reason"] as? String,
                fieldClasses = intMap(map["field_classes"]),
            )
        }

        private fun intMap(value: Any?): Map<String, Long> {
            val raw = value as? Map<*, *> ?: return emptyMap()
            val result = LinkedHashMap<String, Long>()
            for ((key, item) in raw) {
                val name = key?.toString()
                val count = (item as? Number)?.toLong()
                if (name != null && count != null) result[name] = count
            }
            return result
        }
    }
}

/** Internal cast helper that keeps unchecked casts in one auditable place. */
internal object Cast {
    @Suppress("UNCHECKED_CAST")
    fun castToStringKeyedMap(value: Map<*, *>): Map<String, Any?> = value as Map<String, Any?>
}
