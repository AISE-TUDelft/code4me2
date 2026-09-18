package me.code4me.research.bootstrap

import com.intellij.openapi.diagnostic.thisLogger
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.parseCanonicalJson
import me.code4me.research.telemetry.sha256Hex
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** Raised when a bootstrap manifest document cannot be parsed into the typed model. */
class ManifestParseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Stable machine-readable manifest validation reason. */
enum class ManifestValidationReason {
    OK,
    SCHEMA_VERSION_UNSUPPORTED,
    SECRET_DETECTED,
    ABSOLUTE_PATH_DETECTED,
    DIGEST_MISMATCH,
    INVALID_ARTIFACT_DIGEST,
    MALFORMED,
    EXPIRED,
    NEAR_EXPIRY,
    NOT_YET_VALID,
    WRONG_AUDIENCE,
    SCOPE_MISSING,
    INCOMPATIBLE_PLUGIN,
}

/** Typed result of validating a manifest against the local environment. */
data class ManifestValidation(
    val valid: Boolean,
    val reason: ManifestValidationReason,
    val message: String? = null,
    val field: String? = null,
) {
    companion object {
        fun ok(): ManifestValidation = ManifestValidation(valid = true, reason = ManifestValidationReason.OK)

        fun reject(
            reason: ManifestValidationReason,
            message: String,
            field: String? = null,
        ): ManifestValidation = ManifestValidation(valid = false, reason = reason, message = message, field = field)
    }
}

/** The compatibility tuple the client evaluates a manifest against. */
data class PluginCompatibility(
    val pluginVersion: String,
    val expectedAudience: String,
    val supportedManifestVersions: Set<String> = setOf("1"),
    val requiredScopes: Set<String> = setOf("telemetry:write"),
    /** When non-empty, an adapter version outside the set is incompatible. */
    val supportedAdapterVersions: Set<String> = emptySet(),
) {
    companion object {
        const val MANIFEST_VERSION = "1"
        const val SCOPE_TELEMETRY_WRITE = "telemetry:write"
    }
}

/** Opaque research-session descriptor embedded in a manifest. */
data class ResearchSessionDescriptor(
    val researchSessionId: String,
    val openedAt: String,
) {
    init {
        require(researchSessionId.isNotBlank()) { "researchSessionId must not be blank" }
    }
}

/** The assignment projection embedded in a manifest. */
data class ManifestAssignment(
    val assignmentId: String,
    val strategy: String,
) {
    init {
        require(assignmentId.isNotBlank()) { "assignmentId must not be blank" }
    }
}

/**
 * How the agent pinned by a study condition is distributed to the participant.
 *
 * [PACKAGED] is the default: the agent is a digest-pinned artifact shipped inside
 * the plugin runtime, so the manifest carries an `artifact_digest` and resolution
 * is fail-closed against it. [BYOA_EXTERNAL] is participant-installed (bring your
 * own agent): the agent is resolved from an explicit release command, a configured
 * path, or a documented discovery order, and its observed identity is recorded
 * instead of a pre-pinned digest.
 */
enum class AgentDistributionMode(val wireValue: String) {
    PACKAGED("PACKAGED"),
    BYOA_EXTERNAL("BYOA_EXTERNAL"),
    ;

    val isByoa: Boolean
        get() = this == BYOA_EXTERNAL

    companion object {
        /** Parse the wire value; unknown/absent values default to [PACKAGED]. */
        fun fromWire(value: String?): AgentDistributionMode =
            entries.firstOrNull { it.wireValue == value?.trim()?.uppercase() } ?: PACKAGED
    }
}

/**
 * The pinned agent distribution projection embedded in the manifest's
 * `agent_release` object.
 *
 * The backend models a study condition's *distribution* (`AgentProfile`) and
 * freezes an exact pin at publication; the bootstrap manifest projects that pin
 * here. For [AgentDistributionMode.PACKAGED] the immutable `release_id` and
 * `artifact_digest` (the server-selected artifact for the participant's
 * host platform) are the launch contract. For
 * [AgentDistributionMode.BYOA_EXTERNAL] the artifact digest is absent: the
 * participant-installed agent is resolved from [agentCommand]/[agentCommandArgs]
 * (explicit release identity) or [agentPackage] (a documented discovery order),
 * and the observed executable is recorded at launch time.
 */
data class AgentReleaseRef(
    val agentId: String,
    /**
     * The pinned release id. A PACKAGED distribution always carries one; a BYOA
     * distribution with no registered release legitimately projects an empty
     * value, so it is not required at parse time.
     */
    val releaseId: String,
    /**
     * Informational release version. The server's `agent_release` projection pins
     * the immutable `release_id` and the `artifact_digest`; the version label is
     * not part of the launch contract and is not emitted by the current backend,
     * so it defaults to empty rather than making an otherwise valid manifest
     * unparseable.
     */
    val version: String = "",
    val artifactDigest: String = "",
    val adapterVersion: String? = null,
    val distributionMode: AgentDistributionMode = AgentDistributionMode.PACKAGED,
    /**
     * BYOA only: the explicit agent executable the distribution pins (an
     * executable name or a path). `null` when resolution should follow the
     * documented discovery order.
     */
    val agentCommand: String? = null,
    /** BYOA only: argument array appended to [agentCommand]; never a shell string. */
    val agentCommandArgs: List<String> = emptyList(),
    /**
     * BYOA only: the logical participant-installed package (for example `goose`
     * or `codex`) used for discovery when no explicit [agentCommand] is pinned.
     */
    val agentPackage: String? = null,
) {
    /**
     * The bare lowercase hex artifact digest. The server carries the digest as
     * `sha256:<64 hex>` (a display convention); this normalizes it so it can be
     * compared with a resolver's bare-hex `agentDigest`. `null` when malformed.
     */
    val normalizedArtifactDigest: String?
        get() = normalizeSha256Hex(artifactDigest)

    /** True when this release pins a participant-installed (BYOA) agent. */
    val isByoa: Boolean
        get() = distributionMode.isByoa
}

/** Telemetry field-class allowance projected into a manifest. */
data class ManifestTelemetryPolicy(
    val allowedFieldClasses: List<String> = emptyList(),
    val contentCapture: Boolean = false,
)

/** Retention policy projected into a manifest. */
data class ManifestPrivacyPolicy(
    val retentionAction: String,
    val retentionDays: Long? = null,
)

/** Session timing policy projected into a manifest. */
data class ManifestSessionPolicy(
    val idleTimeoutSeconds: Long? = null,
    val resumeGraceSeconds: Long? = null,
    val heartbeatSeconds: Long? = null,
)

/** The policy set; absent members mean inherited/disabled. */
data class ManifestPolicies(
    val telemetry: ManifestTelemetryPolicy? = null,
    val privacy: ManifestPrivacyPolicy? = null,
    val session: ManifestSessionPolicy? = null,
)

/** Short-lived, scoped session capability already issued by the server. */
data class SessionCapabilityRef(
    val capabilityId: String,
    val audience: String,
    val scope: List<String>,
    val issuedAt: String,
    val expiresAt: String,
) {
    init {
        require(capabilityId.isNotBlank()) { "capabilityId must not be blank" }
    }
}

/**
 * Immutable, secret-free `BootstrapManifestV1` as consumed by the participant
 * client (Issue 05 / Issue 10).
 *
 * The model carries exactly the launch contract: pinned study, sticky
 * assignment, pinned agent release, policy set, compatibility receipt reference,
 * and a scoped session capability. It never carries account identity, provider
 * credentials, raw consent, or arbitrary launch commands.
 *
 * [raw] is the exact parsed JSON document. It is retained so the client can
 * recompute the manifest digest and scan the *complete* received document for
 * embedded secrets or absolute paths, including keys the typed model ignores.
 */
data class BootstrapManifest(
    val manifestVersion: String,
    val manifestDigest: String,
    val issuer: String,
    val audience: String,
    val issuedAt: String,
    val expiresAt: String,
    val studyId: String,
    val enrollmentId: String,
    val researchSession: ResearchSessionDescriptor,
    val assignment: ManifestAssignment,
    val agentRelease: AgentReleaseRef,
    val policies: ManifestPolicies,
    val compatibilityReceiptRef: String? = null,
    val sessionCapability: SessionCapabilityRef,
    val signature: String? = null,
    val raw: Map<String, Any?> = emptyMap(),
) {
    private val log = thisLogger()

    /**
     * The canonical mapping covered by [manifestDigest]. Mirrors the server signer:
     * `manifest_digest` and `signature` are excluded so the digest is not
     * self-referential.
     */
    fun digestPayload(): Map<String, Any?> = raw.filterKeys { it != "manifest_digest" && it != "signature" }

    /** SHA-256 of the canonical digest payload, recomputed from the received bytes. */
    fun computedDigest(): String = computeManifestDigest(raw)

    /**
     * The signed `session_capability` object as received (including `signature`
     * and `revocation_epoch` when the server included them). Falls back to the
     * typed [SessionCapabilityRef] projection when the raw document is unavailable
     * (for example a programmatically constructed manifest in a test).
     */
    fun sessionCapabilityObject(): Map<String, Any?> {
        val received = raw["session_capability"]
        if (received is Map<*, *>) {
            val result = LinkedHashMap<String, Any?>(received.size)
            for ((key, value) in received) {
                result[key?.toString() ?: continue] = value
            }
            return result
        }
        return linkedMapOf(
            "capability_id" to sessionCapability.capabilityId,
            "audience" to sessionCapability.audience,
            "scope" to sessionCapability.scope,
            "issued_at" to sessionCapability.issuedAt,
            "expires_at" to sessionCapability.expiresAt,
        )
    }

    /** True when the stored digest matches the recomputed canonical bytes. */
    fun digestMatches(): Boolean = manifestDigest.isNotEmpty() && computedDigest() == manifestDigest

    /**
     * Validate this manifest against [expectedPluginCompatibility] at [now].
     *
     * Checks (short-circuiting, most-severe-first):
     * schema/version, embedded secret and absolute-path scan, canonical digest,
     * artifact-digest shape, expiry (server-decided, with a conservative
     * near-expiry window), audience/scope, and adapter compatibility.
     *
     * [clockSkew] tolerates a manifest whose `issued_at` is slightly ahead of the
     * client's [now]. The client samples `now` before the HTTP request while the
     * server stamps `issued_at`/`generated_at` after it, so a freshly fetched
     * manifest can legitimately be a few milliseconds in the future. Only the
     * not-yet-valid check is relaxed; expiry, near-expiry, and
     * issued-after-expires are unaffected.
     */
    fun validate(
        now: Instant,
        expectedPluginCompatibility: PluginCompatibility,
        nearExpiryWindow: Duration = DEFAULT_NEAR_EXPIRY_WINDOW,
        clockSkew: Duration = DEFAULT_CLOCK_SKEW,
    ): ManifestValidation {
        if (manifestVersion !in expectedPluginCompatibility.supportedManifestVersions) {
            return ManifestValidation.reject(
                ManifestValidationReason.SCHEMA_VERSION_UNSUPPORTED,
                "manifest version '$manifestVersion' is not supported",
                field = "manifest_version",
            )
        }

        when (val finding = scanForSecretsAndPaths(raw, "")) {
            is ScanFinding.Secret ->
                return ManifestValidation.reject(
                    ManifestValidationReason.SECRET_DETECTED,
                    "manifest contains secret material at ${finding.path}",
                    field = finding.path,
                )
            is ScanFinding.AbsolutePath ->
                return ManifestValidation.reject(
                    ManifestValidationReason.ABSOLUTE_PATH_DETECTED,
                    "manifest contains an absolute local path at ${finding.path}",
                    field = finding.path,
                )
            null -> Unit
        }

        if (!isLowercaseHex64(manifestDigest)) {
            return ManifestValidation.reject(
                ManifestValidationReason.DIGEST_MISMATCH,
                "manifest digest is missing or not 64 lowercase hex characters",
                field = "manifest_digest",
            )
        }
        val recomputed = computedDigest()
        if (recomputed != manifestDigest) {
            log.warn(
                "Bootstrap manifest validation failed (field=manifest_digest, " +
                    "reason=${ManifestValidationReason.DIGEST_MISMATCH}): " +
                    "expected=$manifestDigest recomputed=$recomputed",
            )
            return ManifestValidation.reject(
                ManifestValidationReason.DIGEST_MISMATCH,
                "manifest content does not match its digest",
                field = "manifest_digest",
            )
        }

        if (agentRelease.distributionMode.isByoa) {
            // A participant-installed (BYOA) agent is not digest-pinned up front;
            // it must still carry an identity to resolve (an explicit command or a
            // logical package the discovery order understands).
            if (agentRelease.agentCommand.isNullOrBlank() && agentRelease.agentPackage.isNullOrBlank()) {
                return ManifestValidation.reject(
                    ManifestValidationReason.MALFORMED,
                    "a BYOA distribution must declare a command or an agent package",
                    field = "agent_release",
                )
            }
        } else if (agentRelease.releaseId.isBlank() || agentRelease.agentId.isBlank()) {
            // A PACKAGED distribution pins a registry release; a blank release id
            // is unlaunchable and must never be accepted.
            return ManifestValidation.reject(
                ManifestValidationReason.MALFORMED,
                "a PACKAGED distribution must pin a non-blank release_id",
                field = "agent_release.release_id",
            )
        } else if (normalizeSha256Hex(agentRelease.artifactDigest) == null) {
            return ManifestValidation.reject(
                ManifestValidationReason.INVALID_ARTIFACT_DIGEST,
                "artifact digest must be 64 lowercase hex, optionally prefixed with 'sha256:'",
                field = "agent_release.artifact_digest",
            )
        }

        val issued =
            parseInstant(issuedAt)
                ?: return ManifestValidation.reject(
                    ManifestValidationReason.MALFORMED,
                    "issued_at is not a valid ISO-8601 timestamp",
                    field = "issued_at",
                )
        val expires =
            parseInstant(expiresAt)
                ?: return ManifestValidation.reject(
                    ManifestValidationReason.MALFORMED,
                    "expires_at is not a valid ISO-8601 timestamp",
                    field = "expires_at",
                )
        if (issued.isAfter(expires)) {
            return ManifestValidation.reject(
                ManifestValidationReason.MALFORMED,
                "issued_at is after expires_at",
                field = "issued_at",
            )
        }
        if (now.plus(clockSkew).isBefore(issued)) {
            return ManifestValidation.reject(
                ManifestValidationReason.NOT_YET_VALID,
                "manifest is not valid yet",
                field = "issued_at",
            )
        }
        val remaining = Duration.between(now, expires)
        if (remaining.isZero || remaining.isNegative) {
            return ManifestValidation.reject(
                ManifestValidationReason.EXPIRED,
                "manifest has expired",
                field = "expires_at",
            )
        }
        if (remaining <= nearExpiryWindow) {
            log.warn(
                "Bootstrap manifest validation failed (field=expires_at, " +
                    "reason=${ManifestValidationReason.NEAR_EXPIRY}): remaining=$remaining window=$nearExpiryWindow",
            )
            return ManifestValidation.reject(
                ManifestValidationReason.NEAR_EXPIRY,
                "manifest expires within the conservative near-expiry window",
                field = "expires_at",
            )
        }

        if (audience != expectedPluginCompatibility.expectedAudience) {
            log.warn(
                "Bootstrap manifest validation failed (field=audience, " +
                    "reason=${ManifestValidationReason.WRONG_AUDIENCE}): " +
                    "manifest='$audience' expected='${expectedPluginCompatibility.expectedAudience}'",
            )
            return ManifestValidation.reject(
                ManifestValidationReason.WRONG_AUDIENCE,
                "manifest audience '$audience' does not match '${expectedPluginCompatibility.expectedAudience}'",
                field = "audience",
            )
        }
        if (sessionCapability.audience != expectedPluginCompatibility.expectedAudience) {
            log.warn(
                "Bootstrap manifest validation failed (field=session_capability.audience, " +
                    "reason=${ManifestValidationReason.WRONG_AUDIENCE}): " +
                    "manifest='${sessionCapability.audience}' expected='${expectedPluginCompatibility.expectedAudience}'",
            )
            return ManifestValidation.reject(
                ManifestValidationReason.WRONG_AUDIENCE,
                "session capability audience '${sessionCapability.audience}' does not match " +
                    "'${expectedPluginCompatibility.expectedAudience}'",
                field = "session_capability.audience",
            )
        }

        val missingScopes = expectedPluginCompatibility.requiredScopes - sessionCapability.scope.toSet()
        if (missingScopes.isNotEmpty()) {
            log.warn(
                "Bootstrap manifest validation failed (field=session_capability.scope, " +
                    "reason=${ManifestValidationReason.SCOPE_MISSING}): missing=$missingScopes",
            )
            return ManifestValidation.reject(
                ManifestValidationReason.SCOPE_MISSING,
                "session capability is missing required scope $missingScopes",
                field = "session_capability.scope",
            )
        }

        val adapterVersion = agentRelease.adapterVersion
        if (
            expectedPluginCompatibility.supportedAdapterVersions.isNotEmpty() &&
            adapterVersion != null &&
            adapterVersion !in expectedPluginCompatibility.supportedAdapterVersions
        ) {
            return ManifestValidation.reject(
                ManifestValidationReason.INCOMPATIBLE_PLUGIN,
                "agent adapter version '$adapterVersion' is not supported",
                field = "agent_release.adapter_version",
            )
        }

        return ManifestValidation.ok()
    }

    /** Canonical map form of the typed fields (diagnostics / re-serialization). */
    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "manifest_version" to manifestVersion,
            "manifest_digest" to manifestDigest,
            "issuer" to issuer,
            "audience" to audience,
            "issued_at" to issuedAt,
            "expires_at" to expiresAt,
            "study_id" to studyId,
            "enrollment_id" to enrollmentId,
            "research_session" to
                linkedMapOf(
                    "research_session_id" to researchSession.researchSessionId,
                    "opened_at" to researchSession.openedAt,
                ),
            "assignment" to
                linkedMapOf(
                    "assignment_id" to assignment.assignmentId,
                    "strategy" to assignment.strategy,
                ),
            "agent_release" to
                linkedMapOf(
                    "agent_id" to agentRelease.agentId,
                    "release_id" to agentRelease.releaseId,
                    "artifact_digest" to agentRelease.artifactDigest,
                    "adapter_version" to agentRelease.adapterVersion,
                    "distribution_mode" to agentRelease.distributionMode.wireValue,
                    "agent_command" to agentRelease.agentCommand,
                    "agent_command_args" to agentRelease.agentCommandArgs,
                    "agent_package" to agentRelease.agentPackage,
                ),
            "policies" to
                linkedMapOf(
                    "telemetry" to
                        policies.telemetry?.let {
                            linkedMapOf(
                                "allowed_field_classes" to it.allowedFieldClasses,
                                "content_capture" to it.contentCapture,
                            )
                        },
                    "privacy" to
                        policies.privacy?.let {
                            linkedMapOf(
                                "retention_action" to it.retentionAction,
                                "retention_days" to it.retentionDays,
                            )
                        },
                    "session" to
                        policies.session?.let {
                            linkedMapOf(
                                "idle_timeout_seconds" to it.idleTimeoutSeconds,
                                "resume_grace_seconds" to it.resumeGraceSeconds,
                                "heartbeat_seconds" to it.heartbeatSeconds,
                            )
                        },
                ),
            "compatibility_receipt_ref" to compatibilityReceiptRef,
            "session_capability" to
                linkedMapOf(
                    "capability_id" to sessionCapability.capabilityId,
                    "audience" to sessionCapability.audience,
                    "scope" to sessionCapability.scope,
                    "issued_at" to sessionCapability.issuedAt,
                    "expires_at" to sessionCapability.expiresAt,
                ),
            "signature" to signature,
        )

    companion object {
        /** Conservative client window: refuse to launch a manifest about to expire. */
        val DEFAULT_NEAR_EXPIRY_WINDOW: Duration = Duration.ofSeconds(30)

        /**
         * Tolerated skew for the not-yet-valid check. The client samples `now`
         * before issuing its HTTP request, while the server stamps
         * `issued_at`/`generated_at` once it handles the request, so a freshly
         * fetched manifest can be a few milliseconds ahead of the client. This
         * allowance only affects [validate]'s not-yet-valid check.
         */
        val DEFAULT_CLOCK_SKEW: Duration = Duration.ofMinutes(2)

        /**
         * Parse a `BootstrapManifestV1` document.
         *
         * Throws [ManifestParseException] for malformed JSON or a missing required
         * field. Unknown/extra keys are retained in [raw] (and scanned by
         * [validate]); they are never silently trusted.
         */
        fun parse(json: String): BootstrapManifest {
            val parsed =
                try {
                    parseCanonicalJson(json)
                } catch (exception: Exception) {
                    throw ManifestParseException("Manifest is not valid JSON", exception)
                }
            if (parsed !is Map<*, *>) {
                throw ManifestParseException("Manifest JSON must be an object")
            }
            val map = stringKeyedMap(parsed)
            val researchSession = requiredObject(map, "research_session")
            val assignment = requiredObject(map, "assignment")
            val agentRelease = requiredObject(map, "agent_release")
            val capability = requiredObject(map, "session_capability")
            val policies = (map["policies"] as? Map<*, *>)?.let { stringKeyedMap(it) } ?: emptyMap()
            return BootstrapManifest(
                manifestVersion = requiredString(map, "manifest_version"),
                manifestDigest = requiredString(map, "manifest_digest"),
                issuer =
                    (map["issuer"] as? String)?.takeIf { it.isNotBlank() }
                        ?: "code4me-research",
                audience =
                    (map["audience"] as? String)?.takeIf { it.isNotBlank() }
                        ?: requiredString(capability, "audience"),
                issuedAt =
                    (map["issued_at"] as? String)
                        ?: (map["generated_at"] as? String)
                        ?: requiredString(capability, "issued_at"),
                expiresAt = (map["expires_at"] as? String) ?: requiredString(capability, "expires_at"),
                studyId = requiredString(map, "study_id"),
                enrollmentId = requiredString(map, "enrollment_id"),
                researchSession =
                    ResearchSessionDescriptor(
                        researchSessionId = requiredString(researchSession, "research_session_id"),
                        openedAt = requiredString(researchSession, "opened_at"),
                    ),
                assignment =
                    ManifestAssignment(
                        assignmentId = requiredString(assignment, "assignment_id"),
                        strategy = requiredString(assignment, "strategy"),
                    ),
                agentRelease =
                    AgentReleaseRef(
                        // `agent_id`/`release_id` are always present on the wire, but
                        // a BYOA distribution with no registered release projects an
                        // empty `release_id`; per-mode requirements are enforced in
                        // `validate`, not at parse time.
                        agentId = (agentRelease["agent_id"] as? String).orEmpty(),
                        releaseId = (agentRelease["release_id"] as? String).orEmpty(),
                        version = (agentRelease["version"] as? String).orEmpty(),
                        // PACKAGED requires a digest (enforced in `validate`); a
                        // BYOA distribution legitimately carries none, so parsing is
                        // tolerant and the per-mode check is explicit.
                        artifactDigest = (agentRelease["artifact_digest"] as? String).orEmpty(),
                        adapterVersion = agentRelease["adapter_version"] as? String,
                        distributionMode = AgentDistributionMode.fromWire(agentRelease["distribution_mode"] as? String),
                        // The backend emits the canonical `agent_*` names; the
                        // unprefixed aliases are accepted for older snapshots.
                        agentCommand = (agentRelease["agent_command"] ?: agentRelease["command"]) as? String,
                        agentCommandArgs =
                            (
                                (agentRelease["agent_command_args"] ?: agentRelease["command_args"] ?: agentRelease["agent_args"])
                                    as? List<*>
                            )?.mapNotNull { it as? String }
                                .orEmpty(),
                        agentPackage = (agentRelease["agent_package"] ?: agentRelease["package"]) as? String,
                    ),
                policies = parsePolicies(policies),
                compatibilityReceiptRef = map["compatibility_receipt_ref"] as? String,
                sessionCapability =
                    SessionCapabilityRef(
                        capabilityId = requiredString(capability, "capability_id"),
                        audience = requiredString(capability, "audience"),
                        scope = (capability["scope"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                        issuedAt = requiredString(capability, "issued_at"),
                        expiresAt = requiredString(capability, "expires_at"),
                    ),
                signature = map["signature"] as? String,
                raw = map,
            )
        }

        /**
         * SHA-256 of the canonical digest payload: the whole document minus
         * `manifest_digest` and `signature`. Mirrors the server signer exactly.
         */
        fun computeManifestDigest(document: Map<String, Any?>): String =
            sha256Hex(
                canonicalJson(
                    document.filterKeys { it != "manifest_digest" && it != "signature" },
                ),
            )

        private fun parsePolicies(map: Map<String, Any?>): ManifestPolicies {
            fun objectAt(preferred: String, fallback: String): Map<String, Any?>? =
                ((map[preferred] ?: map[fallback]) as? Map<*, *>)?.let { stringKeyedMap(it) }

            val telemetry = objectAt("telemetry_policy", "telemetry")
            val privacy = objectAt("privacy_policy", "privacy")
            val session = objectAt("session_policy", "session")
            return ManifestPolicies(
                telemetry =
                    telemetry?.let {
                        ManifestTelemetryPolicy(
                            allowedFieldClasses =
                                (it["allowed_field_classes"] as? List<*>)?.mapNotNull { cls -> cls as? String }
                                    ?: emptyList(),
                            contentCapture = it["content_capture"] as? Boolean ?: false,
                        )
                    },
                privacy =
                    privacy?.let {
                        ManifestPrivacyPolicy(
                            retentionAction = it["retention_action"] as? String ?: "",
                            retentionDays = (it["retention_days"] as? Number)?.toLong(),
                        )
                    },
                session =
                    session?.let {
                        ManifestSessionPolicy(
                            idleTimeoutSeconds = (it["idle_timeout_seconds"] as? Number)?.toLong(),
                            resumeGraceSeconds = (it["resume_grace_seconds"] as? Number)?.toLong(),
                            heartbeatSeconds = (it["heartbeat_seconds"] as? Number)?.toLong(),
                        )
                    },
            )
        }
    }
}

private const val HEX64_PATTERN = "^[0-9a-f]{64}$"
private val HEX64 = Regex(HEX64_PATTERN)
private const val SHA256_PREFIX = "sha256:"

internal fun isLowercaseHex64(value: String?): Boolean = value != null && HEX64.matches(value)

/**
 * The bare lowercase hex of a `sha256:<hex>` (or bare hex) digest, or `null`
 * when the value is absent, blank, or not a sha256. Mirrors the server's
 * `normalize_sha256`: the `sha256:` prefix is display convention, never part of
 * the digest identity, so a manifest pin and a resolver digest compare equal
 * regardless of which form each uses.
 */
internal fun normalizeSha256Hex(value: String?): String? {
    val text = value?.trim()?.lowercase() ?: return null
    val stripped = if (text.startsWith(SHA256_PREFIX)) text.substring(SHA256_PREFIX.length) else text
    return if (HEX64.matches(stripped)) stripped else null
}

internal fun parseInstant(value: String): Instant? {
    return try {
        Instant.parse(value)
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(value).toInstant()
        } catch (_: Exception) {
            null
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun stringKeyedMap(value: Map<*, *>): Map<String, Any?> {
    val result = LinkedHashMap<String, Any?>(value.size)
    for ((key, item) in value) {
        val name = key?.toString()
        if (name != null) result[name] = item
    }
    return result
}

private fun requiredString(
    map: Map<String, Any?>,
    key: String,
): String {
    val value = map[key]
    if (value !is String || value.isEmpty()) {
        throw ManifestParseException("Manifest field '$key' is required and must be a non-empty string")
    }
    return value
}

private fun requiredObject(
    map: Map<String, Any?>,
    key: String,
): Map<String, Any?> {
    val value = map[key]
    if (value !is Map<*, *>) {
        throw ManifestParseException("Manifest field '$key' is required and must be an object")
    }
    return stringKeyedMap(value)
}

// ---------------------------------------------------------------------------
// Secret / absolute-path scanning
// ---------------------------------------------------------------------------

private sealed interface ScanFinding {
    val path: String

    data class Secret(override val path: String) : ScanFinding

    data class AbsolutePath(override val path: String) : ScanFinding
}

private val SECRET_KEY_SUBSTRINGS =
    listOf(
        "api_key",
        "apikey",
        "secret",
        "password",
        "passwd",
        "credential",
        "private_key",
        "access_key",
        "client_secret",
        "auth_token",
        "session_token",
        "refresh_token",
        "authorization",
        "bearer",
        "user_id",
        "userid",
        "email",
        "provider_key",
        "provider_secret",
        "token",
    )

private val SECRET_VALUE_PATTERNS =
    listOf(
        Regex("\\bsk-[A-Za-z0-9_-]{8,}"),
        Regex("\\bghp_[A-Za-z0-9]{8,}"),
        Regex("\\bAKIA[0-9A-Z]{12,}"),
        Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{4,}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
    )

private val ABSOLUTE_PATH_PATTERNS =
    listOf(
        Regex("^/.*"),
        Regex("^[A-Za-z]:[\\\\/].*"),
        Regex("^\\\\\\\\[^\\\\]+\\\\.*"),
    )

internal fun isSecretManifestKey(key: String): Boolean {
    val normalized = key.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    if (normalized.isEmpty()) return false
    if (normalized == "token" || normalized == "auth") return true
    return SECRET_KEY_SUBSTRINGS.any { it in normalized }
}

internal fun looksSecretManifestValue(value: String): Boolean = SECRET_VALUE_PATTERNS.any { it.containsMatchIn(value) }

internal fun isAbsoluteLocalPath(value: String): Boolean = ABSOLUTE_PATH_PATTERNS.any { it.matches(value) }

private fun scanForSecretsAndPaths(
    value: Any?,
    path: String,
): ScanFinding? {
    return when (value) {
        is Map<*, *> -> {
            for ((rawKey, item) in value) {
                val key = rawKey?.toString() ?: continue
                val childPath = if (path.isEmpty()) key else "$path.$key"
                if (isSecretManifestKey(key)) return ScanFinding.Secret(childPath)
                scanForSecretsAndPaths(item, childPath)?.let { return it }
            }
            null
        }
        is List<*> -> {
            value.forEachIndexed { index, item ->
                scanForSecretsAndPaths(item, "$path[$index]")?.let { return it }
            }
            null
        }
        is String -> {
            when {
                looksSecretManifestValue(value) -> ScanFinding.Secret(path)
                isAbsoluteLocalPath(value) -> ScanFinding.AbsolutePath(path)
                else -> null
            }
        }
        else -> null
    }
}
