package me.code4me.research.session

import me.code4me.research.telemetry.canonicalJson

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

/** Typed, non-identifying participant block reason. */
enum class StudyBlockReason(val value: String) {
    MANIFEST_EXPIRED("MANIFEST_EXPIRED"),
    MANIFEST_INVALID("MANIFEST_INVALID"),
    INCOMPATIBLE_ENVIRONMENT("INCOMPATIBLE_ENVIRONMENT"),
    REVOKED("REVOKED"),
    SESSION_ENDED("SESSION_ENDED"),
    RUNTIME_UNAVAILABLE("RUNTIME_UNAVAILABLE"),
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
 * @property consentState participant-visible consent state.
 * @property compatibilityState environment/plugin compatibility state.
 * @property sessionState research-session state as seen by the participant.
 * @property manifestExpiry ISO-8601 manifest expiry, when a manifest is held.
 * @property manifestDigest canonical manifest digest, when a manifest is held.
 * @property blockReason typed reason collection/launch is blocked.
 * @property deliveryState participant-safe spool-uploader posture (Gap 3).
 */
data class ParticipantStudyStateV1(
    val enrollmentId: String? = null,
    val studyId: String? = null,
    val consentState: StudyComponentState = StudyComponentState.UNAVAILABLE,
    val compatibilityState: StudyComponentState = StudyComponentState.UNAVAILABLE,
    val sessionState: StudyComponentState = StudyComponentState.UNAVAILABLE,
    val manifestExpiry: String? = null,
    val manifestDigest: String? = null,
    val blockReason: StudyBlockReason? = null,
    val blockReasonDetail: String? = null,
    val deliveryState: SpoolDeliveryState = SpoolDeliveryState.UNAVAILABLE,
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

    /** The canonical map form, used for diagnostics and status surfaces. */
    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "enrollment_id" to enrollmentId,
            "study_id" to studyId,
            "consent_state" to consentState.value,
            "compatibility_state" to compatibilityState.value,
            "session_state" to sessionState.value,
            "manifest_expiry" to manifestExpiry,
            "manifest_digest" to manifestDigest,
            "block_reason" to blockReason?.value,
            "block_reason_detail" to blockReasonDetail,
            "delivery_state" to deliveryState.value,
        )

    /** Deterministic JSON for the state surface. */
    fun toCanonicalJson(): String = canonicalJson(toCanonicalMap())
}
