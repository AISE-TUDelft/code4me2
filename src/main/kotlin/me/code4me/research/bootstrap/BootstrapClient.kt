package me.code4me.research.bootstrap

import java.time.Instant

/**
 * Participant-side bootstrap transport (Issue 05 / Issue 10).
 *
 * The plugin implements this later with HTTP; this module never performs network
 * I/O, so the client logic is fully testable with a fake transport.
 */
fun interface BootstrapTransport {
    /**
     * Request the current manifest for [enrollmentId] in execution context
     * [contextId]. Never blocks on UI state. [contextId] is an opaque, stable
     * per project/window identifier; identical values reuse the same session.
     */
    fun fetch(enrollmentId: String, contextId: String): BootstrapTransportResult
}

/** Typed transport outcome for one manifest request. */
sealed interface BootstrapTransportResult {
    /** A manifest document was returned (success or not-modified-with-body). */
    data class Success(val manifestJson: String) : BootstrapTransportResult

    /** The enrollment/environment was revoked; no manifest may be launched. */
    data class Revoked(
        val reason: String,
        val rejection: BootstrapRejection = BootstrapRejection.UNKNOWN,
    ) : BootstrapTransportResult

    /** Transport/network failure. [retryable] distinguishes transient from terminal. */
    data class Failure(
        val message: String,
        val retryable: Boolean = true,
        val rejection: BootstrapRejection? = null,
    ) : BootstrapTransportResult
}

/**
 * Typed, non-secret reason the research server rejected a bootstrap request.
 *
 * The members mirror the server's stable machine-readable `detail.code` tokens.
 * The transport maps HTTP status and `detail.code`/`detail` onto one of these so
 * a participant notification can surface the *real* reason instead of collapsing
 * every 4xx into a generic "withdrawn". Carrying no free-text server body keeps
 * account/enrollment identifiers and other internals out of the UI.
 */
enum class BootstrapRejection {
    /** 404, or an enrollment owned by a different account (no ownership leak). */
    ENROLLMENT_NOT_FOUND,

    /** 401: the participant is not authenticated with the research server. */
    NOT_AUTHENTICATED,

    /** 403: authenticated, but not permitted for this enrollment/action. */
    NOT_PERMITTED,

    /** The study requires a compatibility receipt this build did not present. */
    COMPATIBILITY_MISSING,

    /** The enrollment exists but is not ACTIVE (withdrawn/completed/ineligible). */
    ENROLLMENT_NOT_ACTIVE,

    /** Consent must be accepted before collection may start. */

    /** The enrollment/session was revoked. */
    REVOKED,

    /** The participant is not eligible for this study. */
    INELIGIBLE,

    /** The study revision is not published/open for enrollment. */
    REVISION_NOT_PUBLISHED,

    /** The study window has not opened for enrollment yet. */
    STUDY_NOT_OPEN,

    /** The study window has closed; no new enrollment is accepted. */
    STUDY_CLOSED,

    /** The enrollment is not bound to the requested study revision. */
    REVISION_MISMATCH,

    /** The sticky assignment does not belong to this enrollment/revision. */
    ASSIGNMENT_MISMATCH,

    /** The pinned agent release is not qualified by conformance evidence. */
    RELEASE_NOT_QUALIFIED,

    /** The pinned agent release is not registered on the research server. */
    RELEASE_NOT_FOUND,

    /** The release publishes no usable artifact for this participant platform. */
    ARTIFACT_UNAVAILABLE,

    /** The agent artifact does not match the release pinned by the assignment. */
    ARTIFACT_MISMATCH,

    /** The participant environment is incompatible with the study. */
    INCOMPATIBLE_ENVIRONMENT,

    /** The presented research-session capability is invalid. */
    CAPABILITY_INVALID,

    /** The server has no bootstrap signing secret configured. */
    SIGNING_SECRET_MISSING,

    /** An operator kill switch is engaged. */
    KILL_SWITCH_ENGAGED,

    /** A 4xx the client could not classify. */
    UNKNOWN,
    ;

    companion object {
        /**
         * Server spellings that differ from the enum member name. The protocol
         * validator calls the same condition `AGENT_RELEASE_NOT_QUALIFIED`,
         * while ``BootstrapReasonCode`` uses ``RELEASE_NOT_QUALIFIED``.
         */
        private val ALIASES = mapOf("AGENT_RELEASE_NOT_QUALIFIED" to BootstrapRejection.RELEASE_NOT_QUALIFIED)

        /** Map a server `detail.code`/`reason` token to a typed rejection. */
        fun fromCode(code: String?): BootstrapRejection {
            val normalized = code?.trim()?.uppercase().orEmpty()
            if (normalized.isEmpty()) return UNKNOWN
            return entries.firstOrNull { it.name == normalized }
                ?: ALIASES[normalized]
                ?: UNKNOWN
        }
    }
}

/**
 * A participant-safe explanation of a typed rejection.
 *
 * It is deliberately built from the typed code only, never the server's
 * free-text body, so no account, enrollment, or study identifier can leak into
 * a notification. `UNKNOWN` yields an empty string so a caller can fall back to
 * a generic message.
 */
val BootstrapRejection.participantMessage: String
    get() =
        when (this) {
            BootstrapRejection.ENROLLMENT_NOT_FOUND ->
                "No research enrollment was found for this account. Use the join code from your " +
                    "invitation, or accept the study consent on the Code4Me website first. " +
                    "(reason: ENROLLMENT_NOT_FOUND)"
            BootstrapRejection.NOT_AUTHENTICATED ->
                "You are not signed in to the research server. Sign in to Code4Me and try again. " +
                    "(reason: NOT_AUTHENTICATED)"
            BootstrapRejection.NOT_PERMITTED ->
                "Your account is not permitted to join this study. (reason: NOT_PERMITTED)"
            BootstrapRejection.COMPATIBILITY_MISSING ->
                "This study needs a compatibility check this plugin has not completed. Update the " +
                    "Code4Me plugin or contact the researcher. (reason: COMPATIBILITY_MISSING)"
            BootstrapRejection.ENROLLMENT_NOT_ACTIVE ->
                "Your research enrollment is not active. Open the study link to enroll again, or " +
                    "check your enrollment status. (reason: ENROLLMENT_NOT_ACTIVE)"
            BootstrapRejection.REVOKED ->
                "Your research enrollment was revoked. Contact the researcher if you believe this " +
                    "is a mistake. (reason: REVOKED)"
            BootstrapRejection.INELIGIBLE ->
                "You are not eligible for this study. (reason: INELIGIBLE)"
            BootstrapRejection.REVISION_NOT_PUBLISHED ->
                "This study revision is not currently open for enrollment. " +
                    "(reason: REVISION_NOT_PUBLISHED)"
            BootstrapRejection.STUDY_NOT_OPEN ->
                "This study has not opened for enrollment yet. Try again later. " +
                    "(reason: STUDY_NOT_OPEN)"
            BootstrapRejection.STUDY_CLOSED ->
                "This study has closed and is no longer accepting participants. " +
                    "(reason: STUDY_CLOSED)"
            BootstrapRejection.REVISION_MISMATCH ->
                "This enrollment is bound to a different study revision. Contact the researcher. " +
                    "(reason: REVISION_MISMATCH)"
            BootstrapRejection.ASSIGNMENT_MISMATCH ->
                "Your study assignment does not match the current revision. Contact the researcher. " +
                    "(reason: ASSIGNMENT_MISMATCH)"
            BootstrapRejection.RELEASE_NOT_QUALIFIED ->
                "The agent release pinned by this study has not been qualified. Contact the researcher. " +
                    "(reason: RELEASE_NOT_QUALIFIED)"
            BootstrapRejection.RELEASE_NOT_FOUND ->
                "The agent release pinned by this study is not registered on the server. Contact the researcher. " +
                    "(reason: RELEASE_NOT_FOUND)"
            BootstrapRejection.ARTIFACT_UNAVAILABLE ->
                "The agent artifact for your platform is not available. Contact the researcher. " +
                    "(reason: ARTIFACT_UNAVAILABLE)"
            BootstrapRejection.ARTIFACT_MISMATCH ->
                "The agent installed for this study does not match the pinned release. Reinstall the " +
                    "agent or contact the researcher. (reason: ARTIFACT_MISMATCH)"
            BootstrapRejection.INCOMPATIBLE_ENVIRONMENT ->
                "Your environment is not compatible with this study. Update the Code4Me plugin or " +
                    "contact the researcher. (reason: INCOMPATIBLE_ENVIRONMENT)"
            BootstrapRejection.CAPABILITY_INVALID ->
                "This session's research capability is invalid. Sign in again and retry. " +
                    "(reason: CAPABILITY_INVALID)"
            BootstrapRejection.SIGNING_SECRET_MISSING ->
                "The research server cannot issue session credentials right now. Try again later. " +
                    "(reason: SIGNING_SECRET_MISSING)"
            BootstrapRejection.KILL_SWITCH_ENGAGED ->
                "The study is temporarily paused by the research team. Try again later. " +
                    "(reason: KILL_SWITCH_ENGAGED)"
            BootstrapRejection.UNKNOWN -> ""
        }

/** Injectable in-memory cache of validated manifests, keyed by enrollment id. */
interface ManifestCache {
    fun get(enrollmentId: String): BootstrapManifest?

    fun put(
        enrollmentId: String,
        manifest: BootstrapManifest,
    )

    fun invalidate(enrollmentId: String)
}

/** The default, process-local cache. Never persists to disk. */
class InMemoryManifestCache : ManifestCache {
    private val entries = HashMap<String, BootstrapManifest>()

    @Synchronized
    override fun get(enrollmentId: String): BootstrapManifest? = entries[enrollmentId]

    @Synchronized
    override fun put(
        enrollmentId: String,
        manifest: BootstrapManifest,
    ) {
        entries[enrollmentId] = manifest
    }

    @Synchronized
    override fun invalidate(enrollmentId: String) {
        entries.remove(enrollmentId)
    }
}

/** Terminal disposition of a bootstrap acquire request. */
enum class BootstrapStatus {
    /** A validated manifest is available (fresh fetch or valid cache hit). */
    OK,

    /** No launchable manifest right now; re-request later. */
    REFRESH_REQUIRED,

    /** A manifest can never be launched (revoked, invalid, incompatible, revoked consent). */
    BLOCKED,

    /** Transient failure; retry with backoff. */
    RETRYABLE,
}

/**
 * Typed result of [BootstrapClient.acquire].
 *
 * @property status terminal disposition.
 * @property manifest the validated manifest, only for [BootstrapStatus.OK].
 * @property reason machine-readable/blocking reason, when present.
 * @property validationReason the manifest validation reason, when validation ran.
 * @property fromCache true when a still-valid cached manifest was reused.
 */
data class BootstrapResult(
    val status: BootstrapStatus,
    val manifest: BootstrapManifest? = null,
    val reason: String? = null,
    val validationReason: ManifestValidationReason? = null,
    val fromCache: Boolean = false,
    /** Typed server rejection, when the block came from the transport. */
    val rejection: BootstrapRejection? = null,
) {
    /** True only for a launchable result. */
    val canLaunch: Boolean
        get() = status == BootstrapStatus.OK && manifest != null
}

/**
 * Requests, validates, and reuses a bootstrap manifest (Issue 05 / Issue 10).
 *
 * Behavior:
 * - a still-valid cached manifest is returned without any transport call;
 * - an expired/near-expiry cached manifest triggers a refresh;
 * - a fresh manifest is validated before it is cached or returned;
 * - revocation and non-expiry validation failures are terminal [BootstrapStatus.BLOCKED]
 *   and clear the cache so no launch can use them;
 * - a transport failure is a typed [BootstrapStatus.RETRYABLE] (or `BLOCKED`
 *   when the transport reports it is not retryable).
 *
 * The client never launches a process; it only produces the validated manifest
 * that a launcher may consume.
 *
 * @property transport injected transport (HTTP in the plugin, fake in tests).
 * @property compatibility local plugin/environment compatibility tuple.
 * @property cache injectable in-memory cache.
 * @property clock wall clock, injectable for deterministic expiry tests.
 * @property nearExpiryWindow conservative client-side expiry margin.
 */
class BootstrapClient(
    private val transport: BootstrapTransport,
    private val compatibility: PluginCompatibility,
    private val cache: ManifestCache = InMemoryManifestCache(),
    private val clock: () -> Instant = { Instant.now() },
    private val nearExpiryWindow: java.time.Duration = BootstrapManifest.DEFAULT_NEAR_EXPIRY_WINDOW,
    /**
     * Opaque, stable execution-context id for this client (one IntelliJ project
     * window). Sent with every bootstrap request so the server opens (or reuses)
     * this context's session while sharing the enrollment/assignment. Defaults to
     * a stable non-blank placeholder for callers that do not scope by context.
     */
    val contextId: String = "default",
) {
    /** Cache key scoped to the execution context; contexts never share a session. */
    private fun cacheKey(
        enrollmentId: String,
        contextId: String,
    ): String = "$enrollmentId|$contextId"

    /** Acquire a validated manifest for [enrollmentId] in execution context [contextId]. */
    fun acquire(
        enrollmentId: String,
        contextId: String = this.contextId,
    ): BootstrapResult {
        require(enrollmentId.isNotBlank()) { "enrollmentId must not be blank" }
        require(contextId.isNotBlank()) { "contextId must not be blank" }
        val now = clock()
        val key = cacheKey(enrollmentId, contextId)

        val cached = cache.get(key)
        if (cached != null) {
            val cachedValidation = cached.validate(now, compatibility, nearExpiryWindow)
            if (cachedValidation.valid) {
                return BootstrapResult(
                    status = BootstrapStatus.OK,
                    manifest = cached,
                    fromCache = true,
                )
            }
            if (cachedValidation.reason != ManifestValidationReason.EXPIRED &&
                cachedValidation.reason != ManifestValidationReason.NEAR_EXPIRY
            ) {
                // Cached manifest is invalid for a non-expiry reason: block it.
                cache.invalidate(key)
                return BootstrapResult(
                    status = BootstrapStatus.BLOCKED,
                    reason = cachedValidation.message,
                    validationReason = cachedValidation.reason,
                )
            }
            // Expired/near-expiry cache entries are refreshed below.
        }

        return when (val transportResult = transport.fetch(enrollmentId, contextId)) {
            is BootstrapTransportResult.Success -> handleSuccess(key, transportResult.manifestJson, now)
            is BootstrapTransportResult.Revoked -> {
                cache.invalidate(key)
                BootstrapResult(
                    status = BootstrapStatus.BLOCKED,
                    reason = transportResult.reason,
                    rejection = transportResult.rejection,
                )
            }
            is BootstrapTransportResult.Failure ->
                if (transportResult.retryable) {
                    BootstrapResult(
                        status = BootstrapStatus.RETRYABLE,
                        reason = transportResult.message,
                        rejection = transportResult.rejection,
                    )
                } else {
                    BootstrapResult(
                        status = BootstrapStatus.BLOCKED,
                        reason = transportResult.message,
                        rejection = transportResult.rejection,
                    )
                }
        }
    }

    /** The currently cached manifest for [enrollmentId] in this context, if any. */
    fun cached(enrollmentId: String): BootstrapManifest? = cache.get(cacheKey(enrollmentId, contextId))

    /** Drop any cached manifest for [enrollmentId] in this context (for example on withdrawal). */
    fun invalidate(enrollmentId: String) {
        cache.invalidate(cacheKey(enrollmentId, contextId))
    }

    private fun handleSuccess(
        cacheKey: String,
        manifestJson: String,
        now: Instant,
    ): BootstrapResult {
        val manifest =
            try {
                BootstrapManifest.parse(manifestJson)
            } catch (exception: IllegalArgumentException) {
                // ManifestParseException is an IllegalArgumentException; anything else
                // here is also a malformed document, never a launchable manifest.
                return BootstrapResult(
                    status = BootstrapStatus.BLOCKED,
                    reason = "manifest is malformed: ${exception.message}",
                    validationReason = ManifestValidationReason.MALFORMED,
                )
            }

        val validation = manifest.validate(now, compatibility, nearExpiryWindow)
        return if (validation.valid) {
            cache.put(cacheKey, manifest)
            BootstrapResult(status = BootstrapStatus.OK, manifest = manifest)
        } else {
            when (validation.reason) {
                ManifestValidationReason.EXPIRED, ManifestValidationReason.NEAR_EXPIRY ->
                    BootstrapResult(
                        status = BootstrapStatus.REFRESH_REQUIRED,
                        manifest = manifest,
                        reason = validation.message,
                        validationReason = validation.reason,
                    )
                else -> {
                    cache.invalidate(cacheKey)
                    BootstrapResult(
                        status = BootstrapStatus.BLOCKED,
                        reason = validation.message,
                        validationReason = validation.reason,
                    )
                }
            }
        }
    }
}
