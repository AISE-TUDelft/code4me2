package me.code4me.api.wrapper

import me.code4me.api.generated.infrastructure.ApiClient
import me.code4me.services.app.getAppService
import me.code4me.services.project.getProjectTokenService
import me.code4me.services.state.getAuthState
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI

/**
 * An extension of ApiClient that handles cookies automatically.
 *
 * This client captures cookies from responses and includes them in subsequent requests.
 * It's particularly useful for authentication flows that use cookies for session management.
 */
class CookieAwareApiClient(
    baseUrl: String,
    client: Call.Factory = createClientWithCookieHandler(),
) : ApiClient(baseUrl, client) {
    companion object {
        val cookieManager: CookieManager by lazy {
            CookieManager().apply {
                setCookiePolicy(CookiePolicy.ACCEPT_ALL)
            }
        }

        /**
         * Process-wide shared OkHttp client for raw requests (e.g. the local agent proxy and the
         * agent REST endpoints) that need direct streaming access to response bodies. Reuses the
         * same cookie interceptor so session/auth cookies ride along automatically.
         */
        val sharedOkHttpClient: OkHttpClient by lazy { createClientWithCookieHandler() }

        /**
         * Creates an OkHttpClient with a cookie handler interceptor.
         *
         * The interceptor captures cookies from responses and adds them to subsequent requests.
         */
        fun createClientWithCookieHandler(): OkHttpClient {
            return OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val originalRequest = chain.request()

                    // Add cookies to request if available
                    val requestWithCookies = addCookiesToRequest(originalRequest)

                    val originalResponse = chain.proceed(requestWithCookies)

                    // Get cookies from response
                    val cookies = originalResponse.headers("Set-Cookie")

                    // Store cookies if needed
                    cookies.forEach { cookie ->
                        val httpCookies = HttpCookie.parse(cookie)
                        httpCookies.forEach { httpCookie ->
                            cookieManager.cookieStore.add(URI(originalRequest.url.toString()), httpCookie)
                        }
                    }

                    originalResponse
                }
                .build()
        }

        /**
         * Adds cookies to a request if available for the request's URL.
         *
         * @param request The original request
         * @return A new request with cookies added, or the original request if no cookies are available
         */
        private fun addCookiesToRequest(request: Request): Request {
            val url = request.url.toString()
            val cookieParts = mutableListOf<String>()

            // Attach the auth token straight from AuthSettings rather than routing it through the
            // cookie store. HttpCookie instances synthesised locally carry no domain/path, so
            // cookieStore.get(uri) can return an empty list even right after adding one — which
            // silently produced unauthenticated requests.
            val authToken = getAuthState().getToken()
            if (!authToken.isNullOrBlank()) {
                cookieParts.add("auth_token=$authToken")
            }

            // Any other cookies the server actually set for this URI still apply.
            cookieManager.cookieStore.get(URI(url)).forEach { cookie ->
                if (cookie.name != "auth_token" || authToken.isNullOrBlank()) {
                    cookieParts.add("${cookie.name}=${cookie.value}")
                }
            }

            // add the project token cookie if it exists
            if (getAppService().currentGenerationProject.get() != null) {
                val projectToken =
                    getProjectTokenService(
                        getAppService().currentGenerationProject.get()!!,
                    ).getProjectToken()
                if (projectToken != null) {
                    cookieParts.add("project_token=$projectToken")
                }
            }

            return request.newBuilder()
                .addHeader("Cookie", cookieParts.joinToString("; "))
                .build()
        }

        /**
         * Gets a cookie value by name.
         *
         * @param name The name of the cookie
         * @return The value of the cookie, or null if not found
         */
        fun getCookie(name: String): String? {
            return cookieManager.cookieStore.cookies
                .firstOrNull { it.name == name }
                ?.value
        }

        /**
         * Clears all cookies from the cookie store.
         */
        fun clearCookies() {
            cookieManager.cookieStore.removeAll()
        }

        /**
         * Gets the session token from cookies.
         *
         * @return The session token, or null if not found
         */
        fun getSessionToken(): String? {
            return getCookie("session_token")
        }

        /**
         * Gets the authentication token from cookies.
         *
         * @return The authentication token, or null if not found
         */
        fun getAuthToken(): String? {
            return getCookie("auth_token")
        }
    }
}
