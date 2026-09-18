package me.code4me.research.bootstrap

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.code4me.api.wrapper.CookieAwareApiClient
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/**
 * The outcome of resolving a participant join code against the research server.
 *
 * Every branch is participant-safe: it carries an opaque enrollment id (only
 * when the signed-in account actually owns an active enrollment) or a short,
 * non-secret message. No server free-text or account data is surfaced.
 */
sealed interface JoinCodeResolution {
    /** The caller owns an active enrollment for the code's revision. */
    data class ActiveEnrollment(val enrollmentId: String) : JoinCodeResolution

    /** The code resolved, but the caller has no active enrollment for it yet. */
    data object EnrollmentRequired : JoinCodeResolution

    /** The caller already has an active enrollment in a different study. */
    data class AlreadyEnrolled(val studyId: String) : JoinCodeResolution

    /** The code/server refused the request; [message] is participant-safe. */
    data class Rejected(val message: String) : JoinCodeResolution

    /** The server could not be reached; the caller may retry later. */
    data class Unavailable(val message: String) : JoinCodeResolution
}

/** Typed outcome of redeeming a join code for the signed-in participant. */
sealed interface JoinEnrollResolution {
    /** The caller now holds an enrollment for the code's revision. */
    data class Enrolled(val enrollmentId: String, val reused: Boolean) : JoinEnrollResolution

    /** The code/server refused the redemption; [message] is participant-safe. */
    data class Rejected(val message: String) : JoinEnrollResolution

    /** The server could not be reached; the caller may retry later. */
    data class Unavailable(val message: String) : JoinEnrollResolution
}

/**
 * The signed-in account's server-side enrollment state, independent of any
 * project-local hint (Issue 03 E09). Discovered at login/startup from
 * `GET /api/research/participants/me`.
 */
sealed interface EnrollmentDiscovery {
    /** The account has an ACTIVE enrollment; [enrollmentId] is its id. */
    data class Active(val enrollmentId: String) : EnrollmentDiscovery

    /** The account's enrollment is terminal (withdrawn/completed). */
    data class Terminal(val status: String) : EnrollmentDiscovery

    /** The account has no enrollment on this server. */
    data object None : EnrollmentDiscovery

    /** The server could not be reached or answered unexpectedly; retry later. */
    data class Unavailable(val message: String) : EnrollmentDiscovery
}

/** One membership row from `GET /api/research/participants/me`. */
internal data class ResolvedEnrollment(
    val enrollmentId: String,
    val studyId: String,
    val revisionId: String,
    val status: String,
) {
    val isActive: Boolean get() = status.equals("ACTIVE", ignoreCase = true)
}

/**
 * Parse the own-enrollment projection. Never throws: a malformed document yields
 * an empty list (no enrollment), never a fabricated membership.
 */
internal fun parseEnrollmentEntries(
    body: String,
    json: Json = Json { ignoreUnknownKeys = true },
): List<ResolvedEnrollment> =
    try {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val entries = root["enrollments"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        entries.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            ResolvedEnrollment(
                enrollmentId = (item["enrollment_id"] as? JsonPrimitive)?.content.orEmpty(),
                studyId = (item["study_id"] as? JsonPrimitive)?.content.orEmpty(),
                revisionId = (item["study_revision_id"] as? JsonPrimitive)?.content.orEmpty(),
                status = (item["status"] as? JsonPrimitive)?.content.orEmpty(),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

/**
 * The membership classification for discovered enrollments: the ACTIVE
 * enrollment wins; otherwise a terminal (WITHDRAWN/COMPLETED) enrollment is
 * reported; otherwise the account has none.
 */
internal fun classifyEnrollmentDiscovery(
    enrollments: List<ResolvedEnrollment>,
): EnrollmentDiscovery {
    val active = enrollments.firstOrNull { it.isActive }
    if (active != null) return EnrollmentDiscovery.Active(active.enrollmentId)
    val terminal =
        enrollments.firstOrNull {
            it.status.equals("COMPLETED", ignoreCase = true)
        }
    return if (terminal != null) {
        EnrollmentDiscovery.Terminal(terminal.status.uppercase())
    } else {
        EnrollmentDiscovery.None
    }
}

/**
 * Resolves a short study join code for the signed-in participant (Issue 10).
 *
 * Flow:
 * 1. `GET {base}/api/research/join/{code}` (authenticated) verifies the code
 *    exists and yields the study/revision it points at.
 * 2. `GET {base}/api/research/participants/me` lists the caller's own
 *    enrollments (the server only ever returns the caller's own projections).
 * 3. The two are matched by revision (falling back to study): an active
 *    enrollment for the code's revision resolves to its `enrollment_id`; a
 *    non-active one, or none at all, means the code must be redeemed on the web
 *    first; an active enrollment in a *different* study is reported separately.
 *
 * Never throws: a network/parse failure becomes [JoinCodeResolution.Unavailable]
 * or [JoinCodeResolution.Rejected] so the caller can show a safe notification.
 *
 * @property baseUrl backend origin, with or without a trailing slash.
 * @property httpClient injected OkHttp factory (defaults to the cookie-aware shared client).
 * @property json parser used for the small response projections.
 */
class ResearchJoinCodeResolver(
    baseUrl: String,
    private val httpClient: Call.Factory = CookieAwareApiClient.sharedOkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val log = thisLogger()

    private val origin: String = baseUrl.trim().trimEnd('/')

    /** Resolve [joinCode] for the signed-in account. */
    fun resolve(joinCode: String): JoinCodeResolution {
        val code = joinCode.trim()
        if (code.isEmpty()) {
            return JoinCodeResolution.Rejected("Enter the join code from your study invitation.")
        }
        val resolved =
            when (val response = get("$JOIN_PATH/${encode(code)}")) {
                null -> return JoinCodeResolution.Unavailable(SERVER_UNREACHABLE)
                else -> response
            }
        when {
            resolved.isAuthFailure ->
                return JoinCodeResolution.Rejected(
                    "You are not signed in to the research server. Sign in to Code4Me and try again.",
                )
            resolved.status == HTTP_NOT_FOUND ->
                return JoinCodeResolution.Rejected(
                    "That join code was not found. Check the code and try again.",
                )
            resolved.status !in 200..299 ->
                return if (resolved.isServerError) {
                    JoinCodeResolution.Unavailable(SERVER_UNREACHABLE)
                } else {
                    JoinCodeResolution.Rejected("That join code could not be resolved.")
                }
        }
        val resolvedCode = parseResolvedCode(resolved.body)
            ?: return JoinCodeResolution.Unavailable("The research server returned an unexpected response.")

        val enrollmentsResponse =
            when (val response = get(PARTICIPANTS_ME_PATH)) {
                null -> return JoinCodeResolution.Unavailable(SERVER_UNREACHABLE)
                else -> response
            }
        when {
            enrollmentsResponse.isAuthFailure ->
                return JoinCodeResolution.Rejected(
                    "You are not signed in to the research server. Sign in to Code4Me and try again.",
                )
            enrollmentsResponse.status == HTTP_NOT_FOUND ||
                enrollmentsResponse.status == HTTP_METHOD_NOT_ALLOWED -> {
                // The server predates the own-enrollment endpoint. There is no
                // known enrollment, so the safe branch is "redeem on the web".
                return JoinCodeResolution.EnrollmentRequired
            }
            enrollmentsResponse.status !in 200..299 ->
                return if (enrollmentsResponse.isServerError) {
                    JoinCodeResolution.Unavailable(SERVER_UNREACHABLE)
                } else {
                    JoinCodeResolution.Rejected("Your enrollment status could not be loaded.")
                }
        }
        val enrollments = parseEnrollments(enrollmentsResponse.body)

        // Prefer an exact revision match (the join code is revision-bound); only
        // fall back to study identity when the revision is unknown.
        val matching =
            resolvedCode.revisionId
                .takeIf { it.isNotBlank() }
                ?.let { revisionId -> enrollments.firstOrNull { it.revisionId == revisionId } }
                ?: resolvedCode.studyId
                    .takeIf { it.isNotBlank() }
                    ?.let { studyId -> enrollments.firstOrNull { it.studyId == studyId } }
        if (matching != null) {
            return if (matching.isActive) {
                JoinCodeResolution.ActiveEnrollment(matching.enrollmentId)
            } else {
                JoinCodeResolution.EnrollmentRequired
            }
        }
        val otherActive =
            enrollments.firstOrNull { enrollment ->
                enrollment.isActive &&
                    (resolvedCode.studyId.isBlank() || enrollment.studyId != resolvedCode.studyId)
            }
        if (otherActive != null) {
            return JoinCodeResolution.AlreadyEnrolled(otherActive.studyId)
        }
        return JoinCodeResolution.EnrollmentRequired
    }

    /**
     * Discover the signed-in account's current enrollment from the server.
     *
     * This is the membership authority (never a project-local id): the account's
     * `ACTIVE` enrollment wins; a terminal enrollment is reported as such so the
     * caller can block; no enrollment is [EnrollmentDiscovery.None]. Never throws.
     */
    fun discover(): EnrollmentDiscovery {
        val response =
            get(PARTICIPANTS_ME_PATH) ?: return EnrollmentDiscovery.Unavailable(SERVER_UNREACHABLE)
        return when {
            response.isAuthFailure ->
                EnrollmentDiscovery.Unavailable(
                    "You are not signed in to the research server. Sign in to Code4Me and try again.",
                )
            response.status == HTTP_NOT_FOUND || response.status == HTTP_METHOD_NOT_ALLOWED ->
                // The server predates the own-enrollment endpoint: no membership
                // can be discovered, so report none rather than guessing.
                EnrollmentDiscovery.None
            response.status !in 200..299 ->
                EnrollmentDiscovery.Unavailable("Your enrollment status could not be loaded.")
            else -> classifyEnrollmentDiscovery(parseEnrollmentEntries(response.body, json))
        }
    }

    /**
     * Redeem [joinCode] for the signed-in participant, accepting the revision's
     * consent document.
     *
     * `POST {base}/api/research/join {join_code, accept_consent: true}` is
     * idempotent: re-posting a code the account already redeemed reuses the
     * existing active enrollment instead of minting a second one
     * ([JoinEnrollResolution.Enrolled.reused]). Never throws.
     */
    fun redeem(joinCode: String): JoinEnrollResolution {
        val code = joinCode.trim()
        if (code.isEmpty()) {
            return JoinEnrollResolution.Rejected("Enter the join code from your study invitation.")
        }
        val payload =
            buildJsonObject {
                put("join_code", code)
                put("accept_consent", true)
            }
        val body = json.encodeToString(JsonObject.serializer(), payload)
        val response = post(JOIN_PATH, body) ?: return JoinEnrollResolution.Unavailable(SERVER_UNREACHABLE)
        return when {
            response.isAuthFailure ->
                JoinEnrollResolution.Rejected(
                    "You are not signed in to the research server. Sign in to Code4Me and try again.",
                )
            response.status == HTTP_NOT_FOUND ->
                JoinEnrollResolution.Rejected("That join code was not found. Check the code and try again.")
            response.status == HTTP_CONFLICT ->
                JoinEnrollResolution.Rejected(
                    "That study could not be joined with this code. Check your enrollment status and try again.",
                )
            response.status !in 200..299 ->
                if (response.isServerError) {
                    JoinEnrollResolution.Unavailable(SERVER_UNREACHABLE)
                } else {
                    JoinEnrollResolution.Rejected("That join code could not be redeemed.")
                }
            else -> {
                val resolved = parseEnrollment(response.body)
                if (resolved == null) {
                    JoinEnrollResolution.Unavailable("The research server returned an unexpected response.")
                } else {
                    JoinEnrollResolution.Enrolled(resolved.first, resolved.second)
                }
            }
        }
    }

    private data class ResolvedCode(
        val studyId: String,
        val revisionId: String,
    )

    private data class HttpResponse(
        val status: Int,
        val body: String,
    ) {
        val isAuthFailure: Boolean get() = status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN

        val isServerError: Boolean get() = status >= 500
    }

    private fun get(path: String): HttpResponse? =
        try {
            val request =
                Request
                    .Builder()
                    .url(origin + path)
                    .get()
                    .header("Accept", "application/json")
                    .build()
            httpClient.newCall(request).execute().use { response ->
                HttpResponse(response.code, response.body?.string().orEmpty())
            }
        } catch (exception: IOException) {
            // The join code is a shared secret-ish token: never log the path.
            log.info("Join-code request failed: ${exception.message ?: "network error"}")
            null
        } catch (_: Exception) {
            null
        }

    private fun post(path: String, body: String): HttpResponse? =
        try {
            val request =
                Request
                    .Builder()
                    .url(origin + path)
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json")
                    .build()
            httpClient.newCall(request).execute().use { response ->
                HttpResponse(response.code, response.body?.string().orEmpty())
            }
        } catch (exception: IOException) {
            // The join code is a shared secret-ish token: never log the path/body.
            log.info("Join-code enrollment failed: ${exception.message ?: "network error"}")
            null
        } catch (_: Exception) {
            null
        }

    private fun parseResolvedCode(body: String): ResolvedCode? =
        try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return null
            val study = root["study"] as? JsonObject
            val revision = root["revision"] as? JsonObject
            ResolvedCode(
                studyId = (study?.get("study_id") as? JsonPrimitive)?.content.orEmpty(),
                revisionId = (revision?.get("revision_id") as? JsonPrimitive)?.content.orEmpty(),
            )
        } catch (_: Exception) {
            null
        }

    private fun parseEnrollments(body: String): List<ResolvedEnrollment> =
        parseEnrollmentEntries(body, json)


    private fun parseEnrollment(body: String): Pair<String, Boolean>? =
        try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return null
            val enrollmentId = root.text("enrollment_id")
            if (enrollmentId.isBlank()) return null
            enrollmentId to ((root["reused"] as? JsonPrimitive)?.content == "true")
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val JOIN_PATH = "/api/research/join"
        const val PARTICIPANTS_ME_PATH = "/api/research/participants/me"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_CONFLICT = 409
        const val SERVER_UNREACHABLE = "The research server could not be reached. Please try again later."
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
