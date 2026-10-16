package me.code4me.research.telemetry

/**
 * Closed vocabularies for the canonical telemetry schema (Issue 06).
 *
 * Every member is part of a persisted/exported contract, so members are
 * additive only: renaming or removing one is a breaking change for events
 * already stored by the research backend. The wire values mirror the server's
 * `research.telemetry.enums` exactly.
 */

/** Which observation channel emitted the source event. */
enum class EventSource(val value: String) {
    ACP("acp"),
    IDE("ide"),
    LEGACY("legacy"),
    ;

    companion object {
        /** Parse a wire value; returns `null` for unrecognized input (never fabricates). */
        fun fromWire(value: String?): EventSource? = entries.firstOrNull { it.value == value?.trim()?.lowercase() }
    }
}

/**
 * How faithfully a canonical field represents its source observation.
 *
 * The wire values are lowercase, matching the server `CanonicalFidelity`
 * vocabulary. This is deliberately distinct from the uppercase receipt
 * fidelity used by the capability gate; they must not be conflated.
 */
enum class CanonicalFidelity(val value: String) {
    EXACT("exact"),
    NORMALIZED("normalized"),
    INFERRED("inferred"),
    ;

    companion object {
        fun fromWire(value: String?): CanonicalFidelity? = entries.firstOrNull { it.value == value?.trim()?.lowercase() }
    }
}

/** Backwards-compatible alias for [CanonicalFidelity]. */
typealias Fidelity = CanonicalFidelity

/** Data classification that decides whether a field may persist. */
enum class FieldClass(val value: String) {
    SYSTEM("SYSTEM"),
    BEHAVIORAL("BEHAVIORAL"),
    CODE_METADATA("CODE_METADATA"),
    CONTENT("CONTENT"),
    SECRET("SECRET"),
    ;

    companion object {
        fun fromWire(value: String?): FieldClass? = entries.firstOrNull { it.value == value?.trim()?.uppercase() }
    }
}

/** Terminal privacy decision for one field (or the whole event). */
enum class PolicyAction(val value: String) {
    ALLOW("ALLOW"),
    REDACT("REDACT"),
    HASH("HASH"),
    DROP("DROP"),
    BLOCK("BLOCK"),
    ;

    companion object {
        fun fromWire(value: String?): PolicyAction? = entries.firstOrNull { it.value == value?.trim()?.uppercase() }
    }
}

/**
 * Whether a measurement/observation is present, absent, or disputed.
 *
 * The five wire values mirror the server `CoverageState` exactly. [AVAILABLE]
 * means observed, [UNAVAILABLE] means the source does not expose it, and
 * [UNKNOWN] means it was not observed; neither is zero or false.
 */
enum class CoverageState(val value: String) {
    AVAILABLE("AVAILABLE"),
    UNAVAILABLE("UNAVAILABLE"),
    UNKNOWN("UNKNOWN"),
    PARTIAL("PARTIAL"),
    NEEDS_REVIEW("NEEDS_REVIEW"),
    ;

    companion object {
        /** Parse a wire token; `null` for unrecognized input (never fabricates). */
        fun fromWire(value: String?): CoverageState? {
            val normalized = value?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.value == normalized }
        }
    }
}

/**
 * Coverage of one measured/observed field or capability.
 *
 * Mirrors the server `Coverage` model: `state` plus an optional human-readable
 * `reason` and an optional `capability` name.
 */
data class Coverage(
    val state: CoverageState = CoverageState.UNKNOWN,
    val reason: String? = null,
    val capability: String? = null,
) {
    /** The canonical map representation used for hashing and transport. */
    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "state" to state.value,
            "reason" to reason,
            "capability" to capability,
        )

    companion object {
        fun fromCanonicalMap(map: Map<String, Any?>): Coverage {
            val state =
                CoverageState.fromWire(map["state"] as? String)
                    ?: throw IllegalArgumentException("Unknown coverage state: ${map["state"]}")
            return Coverage(
                state = state,
                reason = map["reason"] as? String,
                capability = map["capability"] as? String,
            )
        }
    }
}

/**
 * Where a canonical event came from and how faithfully it was derived.
 *
 * @property source which channel produced the observation.
 * @property sourceEventId the emitter-local id of the raw observation, if any.
 * @property normalizerVersion the generic normalizer that produced this shape.
 * @property adapterVersion the optional vendor adapter that enriched it.
 * @property fidelity exact / normalized / inferred.
 * @property evidenceDigest a digest of the preserved source evidence, if any.
 */
data class Provenance(
    val source: EventSource,
    val sourceEventId: String? = null,
    val normalizerVersion: String,
    val adapterVersion: String? = null,
    val fidelity: CanonicalFidelity,
    val evidenceDigest: String? = null,
) {
    init {
        require(normalizerVersion.isNotBlank()) { "normalizerVersion must not be blank" }
    }

    /** The canonical map representation used for hashing and transport. */
    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "source" to source.value,
            "source_event_id" to sourceEventId,
            "normalizer_version" to normalizerVersion,
            "adapter_version" to adapterVersion,
            "fidelity" to fidelity.value,
            "evidence_digest" to evidenceDigest,
        )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromCanonicalMap(map: Map<String, Any?>): Provenance {
            val source =
                EventSource.fromWire(map["source"] as? String)
                    ?: throw IllegalArgumentException("Unknown provenance source: ${map["source"]}")
            val fidelity =
                CanonicalFidelity.fromWire(map["fidelity"] as? String)
                    ?: throw IllegalArgumentException("Unknown provenance fidelity: ${map["fidelity"]}")
            return Provenance(
                source = source,
                sourceEventId = map["source_event_id"] as? String,
                normalizerVersion =
                    map["normalizer_version"] as? String
                        ?: throw IllegalArgumentException("Missing normalizer_version"),
                adapterVersion = map["adapter_version"] as? String,
                fidelity = fidelity,
                evidenceDigest = map["evidence_digest"] as? String,
            )
        }
    }
}
