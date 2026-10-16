package me.code4me.research.proxy

import me.code4me.research.telemetry.PrivacyPolicy
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.parseCanonicalJsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * The frozen study privacy policy as the shared server/proxy JSON model
 * (Issue 09).
 *
 * Field names and value shapes match `research.telemetry.privacy.PrivacyPolicy`
 * exactly (the model is `extra="forbid"`): uppercase field classes, a boolean
 * `content_allowed`/`consent_active`, the lowercase `code_metadata_mode`, and
 * the `policy_digest` that pins the document. The registration never inlines
 * the policy on the command line, only the path.
 */
internal fun telemetryPolicyJson(policy: PrivacyPolicy): String =
    canonicalJson(
        linkedMapOf(
            "allowed_field_classes" to policy.allowedFieldClasses.map { it.value }.sorted(),
            "blocked_field_classes" to policy.blockedFieldClasses.map { it.value }.sorted(),
            "content_allowed" to policy.contentAllowed,
            "consent_active" to policy.consentActive,
            "code_metadata_mode" to policy.codeMetadataMode.name.lowercase(),
            "policy_digest" to policy.policyDigest,
        ),
    )

/**
 * Write the frozen policy to [path] owner-only and fail closed unless the file
 * reads back verbatim with the same `policy_digest`.
 *
 * The proxy independently compares the `--telemetry-policy-digest` flag with
 * this document, so a write that does not round-trip must block activation
 * instead of registering a launch that would exit with a usage error.
 */
internal fun writeFrozenTelemetryPolicy(
    path: Path,
    policy: PrivacyPolicy,
): Result<Unit> =
    runCatching {
        val digest =
            requireNotNull(policy.policyDigest) {
                "the resolved telemetry policy has no digest to freeze"
            }
        val document = telemetryPolicyJson(policy)
        writeOwnerOnlyFile(path, document)
        val reloaded = Files.readString(path.toAbsolutePath().normalize())
        require(reloaded == document) {
            "the frozen telemetry policy could not be read back verbatim"
        }
        val parsed = parseCanonicalJsonObject(reloaded)
        require(parsed["policy_digest"] == digest) {
            "the frozen telemetry policy digest did not round-trip"
        }
    }
