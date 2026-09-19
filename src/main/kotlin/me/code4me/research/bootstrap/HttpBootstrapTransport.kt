package me.code4me.research.bootstrap

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.code4me.api.wrapper.CookieAwareApiClient
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/** The non-identifying host tuple a participant reports to the bootstrap API. */
data class BootstrapEnvironment(
    val os: String? = null,
    val arch: String? = null,
    val ideBuild: String? = null,
    val pluginVersion: String? = null,
    val hostKind: String? = null,
)

/**
 * HTTP [BootstrapTransport] for the participant bootstrap API (Issue 05 / Issue 10).
 *
 * It POSTs the enrollment id plus a non-identifying environment report to
 * `POST {baseUrl}/api/research/bootstrap/research-sessions` and returns the
 * server's signed manifest after an authenticated `/verify` exchange checks
 * its HMAC and current session. The request must run against an **authenticated
 * client**: [CookieAwareApiClient.sharedOkHttpClient] is the default, which
 * attaches the `auth_token` cookie. The transport never inspects editor content
 * and never throws out of [fetch]: every network/stream/parse failure is mapped
 * to a typed [BootstrapTransportResult].
 *
 * Response mapping:
 * - `2xx` with a `manifest` object -> [BootstrapTransportResult.Success];
 * - `2xx` without a parseable `manifest` object -> non-retryable
 *   [BootstrapTransportResult.Failure];
 * - `401`/`403` -> non-retryable [BootstrapTransportResult.Failure]
 *   (the participant is not authenticated);
 * - any other `4xx` (e.g. `404`, `409`, `410`, `422`) ->
 *   [BootstrapTransportResult.Revoked] (the enrollment is not usable);
 * - `5xx`, timeouts, and I/O errors -> retryable
 *   [BootstrapTransportResult.Failure].
 *
 * @property baseUrl backend origin, with or without a trailing slash.
 * @property httpClient injected OkHttp factory (defaults to the cookie-aware shared client).
 * @property environment host tuple provider; must never read editor/project content.
 * @property json parser used to build the request and extract the manifest object.
 */
class HttpBootstrapTransport(
    baseUrl: String,
    private val httpClient: Call.Factory = CookieAwareApiClient.sharedOkHttpClient,
    private val environment: () -> BootstrapEnvironment,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : BootstrapTransport {
    private val log = thisLogger()

    private val endpoint: String = baseUrl.trim().trimEnd('/') + RESEARCH_SESSIONS_PATH
    private val verificationEndpoint: String = baseUrl.trim().trimEnd('/') + "/api/research/bootstrap/verify"

    override fun fetch(
        enrollmentId: String,
        contextId: String,
    ): BootstrapTransportResult =
        try {
            val request =
                Request
                    .Builder()
                    .url(endpoint)
                    .post(buildRequestPayload(enrollmentId, contextId).toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json")
                    .build()
            val result = httpClient.newCall(request).execute().use { response -> mapResponse(response) }
            if (result is BootstrapTransportResult.Success) verify(result, enrollmentId, contextId) else result
        } catch (exception: IOException) {
            BootstrapTransportResult.Failure(
                message = "Bootstrap request failed: ${exception.message ?: "network error"}",
                retryable = true,
            )
        } catch (exception: Exception) {
            BootstrapTransportResult.Failure(
                message = "Bootstrap request failed: ${exception.message ?: "unexpected error"}",
                retryable = false,
            )
        }

    private fun verify(
        result: BootstrapTransportResult.Success,
        enrollmentId: String,
        contextId: String,
    ): BootstrapTransportResult {
        val manifest = json.parseToJsonElement(result.manifestJson) as JsonObject
        val digest = manifest.textOrNull("manifest_digest")
        if (digest.isNullOrBlank() || manifest.textOrNull("enrollment_id") != enrollmentId) {
            return BootstrapTransportResult.Failure("Manifest identity is invalid.", retryable = false)
        }
        val payload = buildJsonObject {
            put("manifest", manifest)
            put("enrollment_id", enrollmentId)
            put("context_id", contextId)
        }
        val request = Request.Builder()
            .url(verificationEndpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                BootstrapTransportResult.Failure(
                    "Manifest verification failed with HTTP ${response.code}.",
                    retryable = response.code >= 500,
                )
            } else {
                val proof = json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                if (proof?.get("verified") == JsonPrimitive(true) &&
                    proof.textOrNull("manifest_digest") == digest &&
                    proof.textOrNull("enrollment_id") == enrollmentId &&
                    proof.textOrNull("context_id") == contextId
                ) {
                    result
                } else {
                    BootstrapTransportResult.Failure("Manifest verification response did not match the request.", retryable = false)
                }
            }
        }
    }

    private fun buildRequestPayload(
        enrollmentId: String,
        contextId: String,
    ): JsonElement {
        val reported =
            try {
                environment()
            } catch (_: Exception) {
                BootstrapEnvironment()
            }
        val environmentObject =
            buildJsonObject {
                reported.os?.takeIf { it.isNotBlank() }?.let { put("os", it) }
                reported.arch?.takeIf { it.isNotBlank() }?.let { put("arch", it) }
                reported.ideBuild?.takeIf { it.isNotBlank() }?.let { put("ide_build", it) }
                reported.pluginVersion?.takeIf { it.isNotBlank() }?.let { put("plugin_version", it) }
                reported.hostKind?.takeIf { it.isNotBlank() }?.let { put("host_kind", it) }
            }
        return buildJsonObject {
            put("enrollment_id", enrollmentId)
            // Opaque execution context (project/window): enables simultaneous
            // windows while keeping duplicate creation idempotent per context.
            put("context_id", contextId)
            put("environment", environmentObject)
        }
    }

    private fun mapResponse(response: Response): BootstrapTransportResult {
        val body = response.body?.string().orEmpty()
        val code = response.code
        return when {
            response.isSuccessful -> {
                log.info("Bootstrap request to $RESEARCH_SESSIONS_PATH succeeded with HTTP $code.")
                mapSuccess(body)
            }
            code == HTTP_UNAUTHORIZED -> {
                log.warn("Bootstrap request to $RESEARCH_SESSIONS_PATH was not authenticated (HTTP $code).")
                BootstrapTransportResult.Failure(
                    "Not authenticated with the research server.",
                    retryable = false,
                    rejection = BootstrapRejection.NOT_AUTHENTICATED,
                )
            }
            code == HTTP_FORBIDDEN -> {
                log.warn("Bootstrap request to $RESEARCH_SESSIONS_PATH was not permitted (HTTP $code).")
                BootstrapTransportResult.Failure(
                    "Not permitted to bootstrap this enrollment.",
                    retryable = false,
                    rejection = BootstrapRejection.NOT_PERMITTED,
                )
            }
            code == HTTP_NOT_FOUND -> {
                val reason = reasonFrom(body, response)
                log.warn("Bootstrap request to $RESEARCH_SESSIONS_PATH found no enrollment (HTTP $code): $reason")
                BootstrapTransportResult.Revoked(reason, BootstrapRejection.ENROLLMENT_NOT_FOUND)
            }
            code == HTTP_CONFLICT -> {
                val reason = reasonFrom(body, response)
                log.warn("Bootstrap request to $RESEARCH_SESSIONS_PATH was rejected with HTTP $code: $reason")
                BootstrapTransportResult.Revoked(reason, rejectionFrom(body))
            }
            code in 400..499 -> {
                val reason = reasonFrom(body, response)
                log.warn("Bootstrap request to $RESEARCH_SESSIONS_PATH was rejected with HTTP $code: $reason")
                BootstrapTransportResult.Revoked(reason, rejectionFrom(body))
            }
            code >= 500 -> {
                log.warn("Bootstrap request to $RESEARCH_SESSIONS_PATH failed with HTTP $code.")
                BootstrapTransportResult.Failure(
                    message = "Bootstrap request failed with HTTP $code.",
                    retryable = true,
                )
            }
            else -> {
                log.warn("Bootstrap request to $RESEARCH_SESSIONS_PATH returned unexpected HTTP $code.")
                BootstrapTransportResult.Failure(
                    message = "Unexpected bootstrap response with HTTP $code.",
                    retryable = false,
                )
            }
        }
    }

    private fun mapSuccess(body: String): BootstrapTransportResult {
        val manifest = extractManifest(body)
        return if (manifest != null) {
            BootstrapTransportResult.Success(json.encodeToString(JsonElement.serializer(), manifest))
        } else {
            BootstrapTransportResult.Failure("Bootstrap response did not contain a manifest.", retryable = false)
        }
    }

    private fun extractManifest(body: String): JsonObject? =
        try {
            val root = json.parseToJsonElement(body)
            (root as? JsonObject)?.get("manifest") as? JsonObject
        } catch (_: Exception) {
            null
        }

    /** A short, non-secret reason from the server's typed `detail` (or its status). */
    private fun reasonFrom(
        body: String,
        response: Response,
    ): String {
        val fallback = response.message.ifBlank { "HTTP ${response.code}" }
        return try {
            val root = json.parseToJsonElement(body)
            when (val detail = (root as? JsonObject)?.get("detail")) {
                is JsonPrimitive -> detail.content.ifBlank { fallback }
                is JsonObject ->
                    detail.textOrNull("message")
                        ?: detail.textOrNull("reason")
                        ?: detail.textOrNull("code")
                        ?: fallback
                else -> fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    /**
     * The typed rejection token from the server's `detail`.
     *
     * The server returns `{"detail": {"code": "...", ...}}` for typed refusals
     * and a bare `{"detail": "..."}` string for legacy ones; a bare string that
     * happens to name a known code is still recognised. Anything else is
     * [BootstrapRejection.UNKNOWN], never a guess.
     */
    private fun rejectionFrom(body: String): BootstrapRejection =
        BootstrapRejection.fromCode(codeFrom(body))

    private fun codeFrom(body: String): String? =
        try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return null
            when (val detail = root["detail"]) {
                is JsonObject -> detail.textOrNull("code") ?: detail.textOrNull("reason")
                is JsonPrimitive -> detail.content
                else -> (root["code"] as? JsonPrimitive)?.content
            }
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.textOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private companion object {
        const val RESEARCH_SESSIONS_PATH = "/api/research/bootstrap/research-sessions"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
