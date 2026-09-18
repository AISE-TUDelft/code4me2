package me.code4me.research.telemetry

import me.code4me.research.telemetry.FieldClass
import me.code4me.research.telemetry.PolicyAction
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.sha256Hex

/** How code-metadata fields are persisted once the policy allows them. */
enum class CodeMetadataMode {
    /** Persist a SHA-256 of the value (default, minimizes re-identification). */
    HASH,

    /** Persist the raw value. */
    ALLOW,
}

/**
 * Resolved field policy for one study revision plus consent state (Issue 06).
 *
 * This is the participant-machine policy, not the study definition: it is
 * derived from the published revision policy and the participant's active
 * consent. `SECRET` is deliberately impossible to allow here; the filter drops
 * it regardless of any other setting.
 *
 * @property allowedFieldClasses classes that may persist when present.
 * @property contentAllowed revision policy allows content capture.
 * @property consentActive participant consent is currently active.
 * @property blockedFieldClasses classes that reject the whole event.
 * @property codeMetadataMode hash or allow for [FieldClass.CODE_METADATA].
 * @property policyDigest digest of the resolved policy, if computed.
 */
data class PrivacyPolicy(
    val allowedFieldClasses: Set<FieldClass> =
        setOf(FieldClass.SYSTEM, FieldClass.BEHAVIORAL, FieldClass.CODE_METADATA),
    val contentAllowed: Boolean = false,
    val consentActive: Boolean = false,
    val blockedFieldClasses: Set<FieldClass> = emptySet(),
    val codeMetadataMode: CodeMetadataMode = CodeMetadataMode.HASH,
    val policyDigest: String? = null,
) {
    init {
        require(FieldClass.SECRET !in allowedFieldClasses) {
            "SECRET can never be an allowed field class"
        }
    }

    /** Terminal action for [fieldClass]; `null` means the field was not classifiable. */
    fun actionFor(fieldClass: FieldClass?): PolicyAction {
        // SECRET is always removed, even if a caller tries to allow or block it.
        if (fieldClass == FieldClass.SECRET) return PolicyAction.DROP
        if (fieldClass == null) return PolicyAction.DROP // fail closed on unknown classification
        if (fieldClass in blockedFieldClasses) return PolicyAction.BLOCK
        if (fieldClass == FieldClass.CONTENT) {
            return if (contentAllowed && consentActive) PolicyAction.ALLOW else PolicyAction.REDACT
        }
        if (fieldClass == FieldClass.CODE_METADATA) {
            return if (codeMetadataMode == CodeMetadataMode.HASH) PolicyAction.HASH else PolicyAction.ALLOW
        }
        return if (fieldClass in allowedFieldClasses) PolicyAction.ALLOW else PolicyAction.DROP
    }

    /** Digest that pins this resolved policy for provenance/audit. */
    fun computedDigest(): String =
        sha256Hex(
            canonicalJson(
                linkedMapOf(
                    "allowed" to allowedFieldClasses.map { it.value }.sorted(),
                    "blocked" to blockedFieldClasses.map { it.value }.sorted(),
                    "content_allowed" to contentAllowed,
                    "consent_active" to consentActive,
                    "code_metadata_mode" to codeMetadataMode.name.lowercase(),
                ),
            ),
        )

    /** A copy of this policy with [policyDigest] filled in. */
    fun withComputedDigest(): PrivacyPolicy = copy(policyDigest = computedDigest())

    companion object {
        /** The deny-by-default policy: metadata only, no content capture. */
        fun default(): PrivacyPolicy = PrivacyPolicy()
    }
}
