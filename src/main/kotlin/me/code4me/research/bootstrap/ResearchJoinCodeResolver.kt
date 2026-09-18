package me.code4me.research.bootstrap

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.code4me.api.wrapper.CookieAwareApiClient
import okhttp3.Call
import okhttp3.Request
import java.io.IOException

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
            it.status.equals("REVOKED", ignoreCase = true) ||
            it.status.equals("STUDY_STOPPED", ignoreCase = true)
        }
    return if (terminal != null) {
        EnrollmentDiscovery.Terminal(terminal.status.uppercase())
    } else {
        EnrollmentDiscovery.None
    }
}

/** Post-enrollment membership discovery for the signed-in participant. */
class ResearchJoinCodeResolver(
    baseUrl: String,
    private val httpClient: Call.Factory = CookieAwareApiClient.sharedOkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val log = thisLogger()

    private val origin: String = baseUrl.trim().trimEnd('/')

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

    private companion object {
        const val PARTICIPANTS_ME_PATH = "/api/research/participants/me"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val SERVER_UNREACHABLE = "The research server could not be reached. Please try again later."
    }
}
