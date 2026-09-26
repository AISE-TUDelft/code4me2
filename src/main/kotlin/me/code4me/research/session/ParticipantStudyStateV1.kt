package me.code4me.research.session

import me.code4me.research.telemetry.canonicalJson
import kotlin.math.roundToInt

/**
 * Explicit participant-facing component state (Issue 10).
 *
 * These five states are the only participant-visible research states. `null`
 * activity is never interpreted as "no activity": absence of a measurement is
 * [UNAVAILABLE], not a zero/false.
 */
enum class StudyComponentState(val value: String) {
    AVAILABLE("AVAILABLE"),
    UNAVAILABLE("UNAVAILABLE"),
    PAUSED("PAUSED"),
    BLOCKED("BLOCKED"),
    FAILED("FAILED"),
    ;

    companion object {
        fun fromWire(value: String?): StudyComponentState? = entries.firstOrNull { it.value == value?.trim()?.uppercase() }
    }
}

/**
 * Participant-safe local-delivery state of the research spool uploader (Gap 3).
 *
 * It never carries the upload URL, capability, batch ids, event ids, or raw
 * server error text: only the coarse delivery posture a participant may see.
 * `SPOOL_FULL` mirrors the durable spool's `spool_quota_exceeded` indicator;
 * `RECOVERING` means a retryable transport failure scheduled a backoff; and
 * `REVOKED` means the server refused the session capability (or revoked the
 * enrollment), so no unacknowledged record was deleted.
 */
enum class SpoolDeliveryState(val value: String) {
    /** No uploader is attached to this session (no backend target configured). */
    UNAVAILABLE("UNAVAILABLE"),

    /** The uploader is running and the last attempt was clean. */
    ACTIVE("ACTIVE"),

    /** A retryable delivery failure scheduled a backoff. */
    RECOVERING("RECOVERING"),

    /** The local spool exceeded its quota; only acknowledged records compact. */
    SPOOL_FULL("SPOOL_FULL"),

    /** The server refused the session capability; delivery stopped, data retained. */
    REVOKED("REVOKED"),
}

/**
 * The participant's advisory AI budget as the server reports it on session
 * create/heartbeat/activity responses (`budget`), for a metered arm.
 *
 * Amounts are integer micro-USD; the surface only ever shows a percentage.
 * It is **advisory**: the server enforces the budget on every relay call, so
 * an exhausted budget never blocks collection or launch here — it only drives
 * the non-terminal status presentations. It carries no model, price, profile
 * or account information.
 */
data class InferenceBudgetState(
    val unit: String = "micro_usd",
    val limitMicroUsd: Long,
    val consumedMicroUsd: Long,
    val reservedMicroUsd: Long,
    val remainingMicroUsd: Long,
    val fractionUsed: Double,
    val warningFraction: Double,
    val warning: Boolean,
    val exhausted: Boolean,
    val exhaustedAt: String? = null,
    val asOf: String? = null,
) {
    /** Whole-percent usage for the status surface, clamped to 0..100. */
    val percentUsed: Int
        get() = (fractionUsed * 100.0).roundToInt().coerceIn(0, 100)

    /** Numbers and flags only: never a timestamp, model, or account detail. */
    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "limit_micro_usd" to limitMicroUsd,
            "consumed_micro_usd" to consumedMicroUsd,
            "reserved_micro_usd" to reservedMicroUsd,
            "remaining_micro_usd" to remainingMicroUsd,
            "fraction_used" to fractionUsed,
            "warning_fraction" to warningFraction,
            "warning" to warning,
            "exhausted" to exhausted,
        )

    companion object {
        /**
         * Parse the wire `budget` object. `null` for a `null`/absent block (an
         * unmetered arm) or anything without the three integer amounts; the
         * derived fields fall back to the server's own definitions when absent.
         */
        fun fromWire(value: Any?): InferenceBudgetState? {
            val map = value as? Map<*, *> ?: return null
            val limit = (map["limit"] as? Number)?.toLong() ?: return null
            val consumed = (map["consumed"] as? Number)?.toLong() ?: return null
            val reserved = (map["reserved"] as? Number)?.toLong() ?: return null
            val available = limit - consumed - reserved
            val remaining = (map["remaining"] as? Number)?.toLong() ?: available.coerceAtLeast(0L)
            val fractionUsed =
                (map["fraction_used"] as? Number)?.toDouble()
                    ?: if (limit <= 0L) 1.0 else ((consumed + reserved).toDouble() / limit.toDouble()).coerceAtMost(1.0)
            val warningFraction = (map["warning_fraction"] as? Number)?.toDouble() ?: 0.0
            val exhausted = (map["exhausted"] as? Boolean) ?: (map["exhausted_at"] != null || available <= 0L)
            val warning = (map["warning"] as? Boolean) ?: (exhausted || fractionUsed >= warningFraction)
            return InferenceBudgetState(
                unit = (map["unit"] as? String)?.takeIf { it.isNotBlank() } ?: "micro_usd",
                limitMicroUsd = limit,
                consumedMicroUsd = consumed,
                reservedMicroUsd = reserved,
                remainingMicroUsd = remaining,
                fractionUsed = fractionUsed,
                warningFraction = warningFraction,
                warning = warning,
                exhausted = exhausted,
                exhaustedAt = map["exhausted_at"] as? String,
                asOf = map["as_of"] as? String,
            )
        }
    }
}

/** Typed, non-identifying participant block reason. */
enum class StudyBlockReason(val value: String) {
    MANIFEST_EXPIRED("MANIFEST_EXPIRED"),
    MANIFEST_INVALID("MANIFEST_INVALID"),
    INCOMPATIBLE_ENVIRONMENT("INCOMPATIBLE_ENVIRONMENT"),
    REVOKED("REVOKED"),
    SESSION_ENDED("SESSION_ENDED"),
    RUNTIME_UNAVAILABLE("RUNTIME_UNAVAILABLE"),
    /**
     * The study does not declare a usable session policy, so the server refuses
     * to open or maintain the authoritative session (ISSUE-05). The block is
     * terminal for this activation: retrying cannot succeed until a researcher
     * fixes the study's session policy.
     */
    POLICY_INVALID("POLICY_INVALID"),
    /**
     * A BYOA (`BYOA_EXTERNAL`) agent could not be resolved on this participant
     * host. The participant must install it (or point the settings at it); the
     * plugin never falls back silently to another executable.
     */
    AGENT_NOT_FOUND("AGENT_NOT_FOUND"),
    TRANSPORT_FAILED("TRANSPORT_FAILED"),
    UNKNOWN("UNKNOWN"),
}

/**
 * Non-identifying participant study state (Issue 10, `ParticipantStudyStateV1`).
 *
 * The state deliberately carries no account, user, e-mail, device, or provider
 * field: it identifies the enrollment/study/session only, plus the manifest
 * digest/expiry that the launch decision is based on. Blocked states always
 * carry a typed [blockReason].
 *
 * @property enrollmentId opaque enrollment reference, never a participant id.
 * @property studyId opaque study reference.
 * @property assignmentId sticky study assignment reference.
 * @property agentProfileId selected agent profile reference.
 * @property profileDigest immutable assigned profile configuration digest.
 * @property consentState participant-visible consent state.
 * @property compatibilityState environment/plugin compatibility state.
 * @property sessionState research-session state as seen by the participant.
 * @property manifestExpiry ISO-8601 manifest expiry, when a manifest is held.
 * @property manifestDigest canonical manifest digest, when a manifest is held.
 * @property blockReason typed reason collection/launch is blocked.
 * @property deliveryState participant-safe spool-uploader posture (Gap 3).
 * @property droppedTelemetryCount locally dropped proxy telemetry events as
 * reported by the proxy's content-free status document, or `null` when unknown.
 * Absence of a measurement is never a zero: the surface must not claim full
 * coverage it cannot prove.
 * @property inferenceBudget the advisory AI budget the server last reported for
 * this session, or `null` (unmetered arm, or not reported yet). It never sets a
 * block reason and never changes [isCollecting]/[canLaunch].
 */
data class ParticipantStudyStateV1(
    val enrollmentId: String? = null,
    val studyId: String? = null,
    val assignmentId: String? = null,
    val agentProfileId: String? = null,
    val profileDigest: String? = null,
    val consentState: StudyComponentState = StudyComponentState.UNAVAILABLE,
    val compatibilityState: StudyComponentState = StudyComponentState.UNAVAILABLE,
    val sessionState: StudyComponentState = StudyComponentState.UNAVAILABLE,
    val manifestExpiry: String? = null,
    val manifestDigest: String? = null,
    val blockReason: StudyBlockReason? = null,
    val blockReasonDetail: String? = null,
    val deliveryState: SpoolDeliveryState = SpoolDeliveryState.UNAVAILABLE,
    val droppedTelemetryCount: Int? = null,
    val inferenceBudget: InferenceBudgetState? = null,
) {
    /** True only when every component is available and nothing blocks. */
    val isCollecting: Boolean
        get() =
            blockReason == null &&
                consentState == StudyComponentState.AVAILABLE &&
                compatibilityState == StudyComponentState.AVAILABLE &&
                sessionState == StudyComponentState.AVAILABLE

    /** True when a launch may be attempted (collecting and a manifest is held). */
    val canLaunch: Boolean
        get() = isCollecting && manifestDigest != null && manifestExpiry != null

    /**
     * True when a study context owns this project, even if collection is not
     * currently active (a held session/manifest, a live collector, or a typed
     * block). It decides whether the direct managed "Code4Me Agent" entry may be
     * used at all, so a blocked-but-enrolled participant is still routed to the
     * authoritative research entry (ISSUE-18).
     */
    val holdsStudyContext: Boolean
        get() = enrollmentId != null || manifestDigest != null || blockReason != null || isCollecting

    /** The canonical map form, used for diagnostics and status surfaces. */
    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "enrollment_id" to enrollmentId,
            "study_id" to studyId,
            "assignment_id" to assignmentId,
            "agent_profile_id" to agentProfileId,
            "profile_digest" to profileDigest,
            "consent_state" to consentState.value,
            "compatibility_state" to compatibilityState.value,
            "session_state" to sessionState.value,
            "manifest_expiry" to manifestExpiry,
            "manifest_digest" to manifestDigest,
            "block_reason" to blockReason?.value,
            "block_reason_detail" to blockReasonDetail,
            "delivery_state" to deliveryState.value,
            "dropped_telemetry_count" to droppedTelemetryCount,
            "inference_budget" to inferenceBudget?.toCanonicalMap(),
        )

    /** Deterministic JSON for the state surface. */
    fun toCanonicalJson(): String = canonicalJson(toCanonicalMap())
}
