package me.code4me.research.runtime

import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.runtime.isLowercaseSha256

/**
 * Capability-aware agent conformance contract (Issue 11).
 *
 * A conformance case is available only when its prerequisite capability is
 * established by evidence. A capability that is explicitly unavailable yields
 * [ConformanceStatus.UNSUPPORTED]; a capability whose state is unknown yields
 * [ConformanceStatus.UNKNOWN]. Neither is ever reported as a pass.
 *
 * This module is pure JVM: no IntelliJ imports, no network, no process spawn.
 */

/** Terminal status of one conformance case or of a whole receipt. */
enum class ConformanceStatus(val value: String) {
    PASS("PASS"),
    FAIL("FAIL"),
    UNSUPPORTED("UNSUPPORTED"),
    UNKNOWN("UNKNOWN"),
    BLOCKED("BLOCKED"),
    ;

    /** Only [PASS] supports an advertised behavior. */
    val supportsAdvertisedBehavior: Boolean
        get() = this == PASS

    companion object {
        fun fromWire(value: String?): ConformanceStatus? = entries.firstOrNull { it.value == value?.trim()?.uppercase() }

        /**
         * Aggregate severity: concrete failure outranks blocked, unknown, and
         * unsupported; everything passes only when nothing else is present.
         */
        fun overall(statuses: Collection<ConformanceStatus>): ConformanceStatus {
            if (statuses.isEmpty()) return UNKNOWN
            return when {
                FAIL in statuses -> FAIL
                BLOCKED in statuses -> BLOCKED
                UNKNOWN in statuses -> UNKNOWN
                UNSUPPORTED in statuses -> UNSUPPORTED
                else -> PASS
            }
        }
    }
}

/**
 * Issue-04 capability state vocabulary. A capability is never a bare boolean:
 * declared support, observed support, explicit unavailability, partial support,
 * and a broken implementation all retain distinct meaning.
 */
enum class CapabilityState(val value: String) {
    UNKNOWN("UNKNOWN"),
    DECLARED("DECLARED"),
    OBSERVED("OBSERVED"),
    UNAVAILABLE("UNAVAILABLE"),
    PARTIAL("PARTIAL"),
    BROKEN("BROKEN"),
    ;

    companion object {
        fun fromWire(value: String?): CapabilityState? = entries.firstOrNull { it.value == value?.trim()?.uppercase() }
    }
}

/** Which side of the snapshot a predicate reads. */
enum class CapabilityChannel(val value: String) {
    DECLARED("declared"),
    OBSERVED("observed"),
}

/**
 * One capability observation.
 *
 * @property state declared/observed/unavailable/partial/broken/unknown.
 * @property value optional non-boolean value (for example an ACP protocol id).
 * @property fidelity exact/normalized/inferred when known.
 * @property source observation channel when known.
 * @property limitations known reasons the observation is incomplete.
 */
data class CapabilityFact(
    val state: CapabilityState,
    val value: String? = null,
    val fidelity: String? = null,
    val source: String? = null,
    val limitations: List<String> = emptyList(),
)

/** Declared and observed facts for one capability. Either side may be absent. */
data class CapabilitySnapshotEntry(
    val capability: String,
    val declared: CapabilityFact? = null,
    val observed: CapabilityFact? = null,
) {
    init {
        require(capability.isNotBlank()) { "capability must not be blank" }
    }
}

/** Reads capability facts from an Issue-04-style snapshot. */
interface CapabilityLookup {
    /** The declared/observed facts for [capability], or `null` when not present. */
    fun lookup(capability: String): CapabilitySnapshotEntry?

    companion object {
        /** A lookup backed by an in-memory list of entries (test/fixture friendly). */
        fun of(entries: List<CapabilitySnapshotEntry>): CapabilityLookup {
            val byName = entries.associateBy { it.capability }
            return object : CapabilityLookup {
                override fun lookup(capability: String): CapabilitySnapshotEntry? = byName[capability]
            }
        }

        /** A lookup with no data: every predicate evaluates to UNKNOWN. */
        fun empty(): CapabilityLookup =
            object : CapabilityLookup {
                override fun lookup(capability: String): CapabilitySnapshotEntry? = null
            }
    }
}

/** Result of evaluating a [CapabilityPredicate]. */
enum class PredicateOutcome {
    /** The capability is present in the required channel/state. */
    ESTABLISHED,

    /** The capability is explicitly unavailable/partial/broken. */
    NOT_ESTABLISHED,

    /** The capability has no usable evidence. */
    UNKNOWN,
}

/**
 * A required capability + state, evaluated against a [CapabilityLookup].
 *
 * @property channel declared or observed evidence.
 * @property requiredState the state the selected channel must show; defaults to
 *   the channel's natural state ([CapabilityState.OBSERVED] for [CapabilityChannel.OBSERVED],
 *   [CapabilityState.DECLARED] for [CapabilityChannel.DECLARED]).
 * @property requiredValue optional exact value the fact must carry.
 */
data class CapabilityPredicate(
    val capability: String,
    val channel: CapabilityChannel = CapabilityChannel.OBSERVED,
    val requiredState: CapabilityState? = null,
    val requiredValue: String? = null,
) {
    init {
        require(capability.isNotBlank()) { "capability must not be blank" }
    }

    /** Evaluate this predicate against [lookup]. */
    fun evaluate(lookup: CapabilityLookup): PredicateOutcome {
        val entry = lookup.lookup(capability) ?: return PredicateOutcome.UNKNOWN
        val fact =
            when (channel) {
                CapabilityChannel.DECLARED -> entry.declared
                CapabilityChannel.OBSERVED -> entry.observed
            } ?: return PredicateOutcome.UNKNOWN
        return when (fact.state) {
            CapabilityState.UNKNOWN -> PredicateOutcome.UNKNOWN
            CapabilityState.UNAVAILABLE, CapabilityState.PARTIAL, CapabilityState.BROKEN -> PredicateOutcome.NOT_ESTABLISHED
            CapabilityState.DECLARED, CapabilityState.OBSERVED -> {
                val expected =
                    requiredState
                        ?: if (channel == CapabilityChannel.OBSERVED) {
                            CapabilityState.OBSERVED
                        } else {
                            CapabilityState.DECLARED
                        }
                when {
                    fact.state != expected -> PredicateOutcome.NOT_ESTABLISHED
                    requiredValue != null && fact.value != requiredValue -> PredicateOutcome.NOT_ESTABLISHED
                    else -> PredicateOutcome.ESTABLISHED
                }
            }
        }
    }
}

/**
 * Cleanup outcome. Cleanup is always recorded for every case, even when the
 * case never ran; [succeeded] is vacuously true when nothing needed cleanup.
 */
data class CleanupRecord(
    val attempted: Boolean,
    val succeeded: Boolean,
    val detail: String? = null,
) {
    companion object {
        /** A case that did not run and therefore needed no cleanup. */
        fun notRequired(detail: String? = "case not run"): CleanupRecord = CleanupRecord(false, true, detail)

        /** Cleanup ran and succeeded. */
        fun cleaned(detail: String? = null): CleanupRecord = CleanupRecord(true, true, detail)

        /** Cleanup ran but failed. */
        fun failed(detail: String): CleanupRecord = CleanupRecord(true, false, detail)
    }
}

/** A measured performance bound that must hold for a case to pass. */
data class PerformanceBounds(
    val metric: String,
    val limitMs: Long,
    val observedMs: Long,
) {
    init {
        require(metric.isNotBlank()) { "metric must not be blank" }
        require(limitMs >= 0) { "limitMs must not be negative" }
    }

    val met: Boolean
        get() = observedMs <= limitMs
}

/**
 * One conformance case definition plus its (runner-populated) result.
 *
 * @property caseId stable case identity.
 * @property prerequisite capability predicate that must be established to run.
 * @property setupFixtureId fixture this case applies to.
 * @property actionSteps ordered scenario steps.
 * @property expectedObservations host/agent observations the action should yield.
 * @property cleanupAssertion what must be true after cleanup.
 * @property required whether a PASS is required to support the advertised behavior.
 * @property status result status; defaults to UNKNOWN before the runner runs.
 * @property observations what was actually observed.
 * @property cleanup always-present cleanup record.
 * @property performance measured performance bounds.
 * @property evidenceDigests digest-addressed evidence produced by the case.
 */
data class ConformanceCaseV1(
    val caseId: String,
    val prerequisite: CapabilityPredicate? = null,
    val setupFixtureId: String,
    val actionSteps: List<String> = emptyList(),
    val expectedObservations: List<String> = emptyList(),
    val cleanupAssertion: String = "",
    val required: Boolean = true,
    val status: ConformanceStatus = ConformanceStatus.UNKNOWN,
    val observations: List<String> = emptyList(),
    val cleanup: CleanupRecord = CleanupRecord.notRequired(),
    val performance: List<PerformanceBounds> = emptyList(),
    val evidenceDigests: List<String> = emptyList(),
) {
    init {
        require(caseId.isNotBlank()) { "caseId must not be blank" }
        require(setupFixtureId.isNotBlank()) { "setupFixtureId must not be blank" }
        evidenceDigests.forEach {
            require(isLowercaseSha256(it)) { "evidence digest must be 64 lowercase hex characters" }
        }
    }

    /** True when every measured performance bound held. */
    val performanceWithinBounds: Boolean
        get() = performance.all { it.met }

    /** True when this case is a required, passing case. */
    val isRequiredPass: Boolean
        get() = required && status == ConformanceStatus.PASS

    fun withResult(
        status: ConformanceStatus,
        observations: List<String>,
        cleanup: CleanupRecord,
        performance: List<PerformanceBounds>,
        evidenceDigests: List<String>,
    ): ConformanceCaseV1 =
        copy(
            status = status,
            observations = observations,
            cleanup = cleanup,
            performance = performance,
            evidenceDigests = evidenceDigests,
        )

    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "case_id" to caseId,
            "setup_fixture_id" to setupFixtureId,
            "prerequisite" to
                prerequisite?.let {
                    linkedMapOf(
                        "capability" to it.capability,
                        "channel" to it.channel.value,
                        "required_state" to it.requiredState?.value,
                        "required_value" to it.requiredValue,
                    )
                },
            "action_steps" to actionSteps,
            "expected_observations" to expectedObservations,
            "cleanup_assertion" to cleanupAssertion,
            "required" to required,
            "status" to status.value,
            "observations" to observations,
            "cleanup" to
                linkedMapOf(
                    "attempted" to cleanup.attempted,
                    "succeeded" to cleanup.succeeded,
                    "detail" to cleanup.detail,
                ),
            "performance" to
                performance.map {
                    linkedMapOf("metric" to it.metric, "limit_ms" to it.limitMs, "observed_ms" to it.observedMs)
                },
            "evidence_digests" to evidenceDigests,
        )
}

/**
 * A qualification receipt binding case results to the exact artifact, adapter,
 * host, plugin, ACP protocol, and fixture digests they were observed against.
 *
 * @property fixtureDigests fixture id -> digest of the staged fixture.
 */
data class ConformanceReceiptV1(
    val receiptId: String,
    val artifactDigest: String,
    val adapterId: String,
    val adapterVersion: String,
    val hostVersion: String,
    val pluginVersion: String,
    val acpProtocolVersion: String,
    val fixtureDigests: Map<String, String>,
    val cases: List<ConformanceCaseV1>,
    val overallStatus: ConformanceStatus,
    val generatedAtEpochMs: Long,
    val hostPlatform: String? = null,
) {
    init {
        require(receiptId.isNotBlank()) { "receiptId must not be blank" }
        require(isLowercaseSha256(artifactDigest)) { "artifactDigest must be 64 lowercase hex characters" }
        require(adapterId.isNotBlank()) { "adapterId must not be blank" }
        require(adapterVersion.isNotBlank()) { "adapterVersion must not be blank" }
        require(hostVersion.isNotBlank()) { "hostVersion must not be blank" }
        require(pluginVersion.isNotBlank()) { "pluginVersion must not be blank" }
        require(acpProtocolVersion.isNotBlank()) { "acpProtocolVersion must not be blank" }
        fixtureDigests.forEach { (fixtureId, digest) ->
            require(fixtureId.isNotBlank()) { "fixture id must not be blank" }
            require(isLowercaseSha256(digest)) { "fixture '$fixtureId' digest must be 64 lowercase hex characters" }
        }
    }

    fun case(caseId: String): ConformanceCaseV1? = cases.firstOrNull { it.caseId == caseId }

    /**
     * True only when every required case is [ConformanceStatus.PASS].
     *
     * When [requiredCaseIds] is supplied, exactly those cases must exist and
     * pass; an unknown required id makes the claim false rather than ignored.
     */
    fun advertisedBehaviorSupported(requiredCaseIds: Collection<String>? = null): Boolean {
        val requiredCases =
            if (requiredCaseIds == null) {
                cases.filter { it.required }
            } else {
                val requested = requiredCaseIds.toSet()
                val found = cases.filter { it.caseId in requested }
                if (found.size != requested.size) return false
                found
            }
        if (requiredCases.isEmpty()) return false
        return requiredCases.all { it.status == ConformanceStatus.PASS }
    }

    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "receipt_id" to receiptId,
            "artifact_digest" to artifactDigest,
            "adapter_id" to adapterId,
            "adapter_version" to adapterVersion,
            "host_version" to hostVersion,
            "plugin_version" to pluginVersion,
            "acp_protocol_version" to acpProtocolVersion,
            "host_platform" to hostPlatform,
            "fixture_digests" to fixtureDigests,
            "cases" to cases.map { it.toCanonicalMap() },
            "overall_status" to overallStatus.value,
            "generated_at_epoch_ms" to generatedAtEpochMs,
        )

    fun toCanonicalJson(): String = canonicalJson(toCanonicalMap())
}
