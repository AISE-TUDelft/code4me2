
package me.code4me.services.app

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import me.code4me.api.generated.api.AuthenticateApi
import me.code4me.api.generated.api.CompletionApi
import me.code4me.api.generated.api.CreateUserApi
import me.code4me.api.generated.api.UserApi
import me.code4me.api.generated.infrastructure.ClientException
import me.code4me.api.generated.infrastructure.ServerException
import me.code4me.api.generated.model.AuthenticateUserPostResponse
import me.code4me.api.generated.model.CompletionResponseData
import me.code4me.api.generated.model.CreateUserPostResponse
import me.code4me.api.generated.model.Provider
import me.code4me.api.generated.model.RequestCompletion
import me.code4me.api.generated.model.UserToAuthenticate
import me.code4me.api.generated.model.UserToCreate
import me.code4me.api.wrapper.CookieAwareApiClient
import me.code4me.services.config.getConfig
import me.code4me.services.state.getAuthState
import me.code4me.utils.record.Record
import java.io.IOException

/**
 * Main application service for the Code4Me plugin.
 *
 * This service acts as the central orchestrator for all API operations including user authentication,
 * account management, and code completion requests. It manages cookie-based session authentication
 * and provides a unified interface for interacting with the Code4Me backend services.
 *
 * The service is automatically initialized when the plugin starts and remains available throughout
 * the plugin's lifecycle. All API clients are configured to use cookie-based authentication for
 * seamless session management.
 *
 * @since 1.0.0
 * @see com.intellij.openapi.components.Service
 */
@Service
class AppService {
    companion object {
        private val LOG = thisLogger()

        /** Default model ID used for completion requests */
        private const val DEFAULT_MODEL_ID = 1
    }

    private val configService = getConfig()
    private val serverConfig = configService.getServerConfig()

    /**
     * The base URL for all API requests, constructed from server configuration.
     * Format: "host:port/contextPath"
     */
    private val apiBaseUrl = "${serverConfig?.host}:${serverConfig?.port}${serverConfig?.contextPath}"

    // API clients using the cookie-aware client's OkHttpClient for automatic session management
    private val authApi = AuthenticateApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val userApi = UserApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val createUserApi = CreateUserApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val completionApi = CompletionApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())

    init {
        LOG.info("AppService initialized with API base URL: $apiBaseUrl")
    }

    // ========= Authentication Methods =========

    /**
     * Stores the authentication response data in the application's auth state.
     *
     * This method extracts the session token from cookies and user information from the response,
     * then persists them to the auth state for use throughout the application session.
     *
     * @param response The authentication response containing user data and session information
     */
    private fun storeAuthenticationResponse(response: AuthenticateUserPostResponse) {
        val authSettings = getAuthState()

        // Prioritize session token from cookies over response message
        val sessionToken = CookieAwareApiClient.getSessionToken()
        authSettings.setToken(sessionToken ?: response.message)

        // Store user profile information
        authSettings.setUserName(response.user.name)
        authSettings.setUserEmail(response.user.email)

        LOG.info("Authentication data stored successfully for user: ${response.user.email}")
    }

    /**
     * Authenticates a user using email and password credentials.
     *
     * This method performs traditional username/password authentication against the Code4Me backend.
     * Upon successful authentication, the user's session information is automatically stored
     * and cookies are managed for subsequent API requests.
     *
     * @param email The user's email address (must be a valid email format)
     * @param password The user's password
     * @return [AuthenticateUserPostResponse] containing user information and session details
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If authentication fails due to invalid credentials (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     * @throws IllegalArgumentException If email or password parameters are invalid
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun authenticateUser(
        email: String,
        password: String,
    ): AuthenticateUserPostResponse {
        require(email.isNotBlank()) { "Email cannot be blank" }
        require(password.isNotBlank()) { "Password cannot be blank" }

        val userToAuthenticate =
            UserToAuthenticate(
                email = email,
                password = password,
                provider = Provider.google,
                token = "",
            )

        return try {
            val response = authApi.authenticateUserApiUserAuthenticatePost(userToAuthenticate)
            storeAuthenticationResponse(response)
            LOG.info("User authenticated successfully: $email")
            response
        } catch (e: Exception) {
            LOG.warn("Authentication failed for user: $email", e)
            throw e
        }
    }

    /**
     * Authenticates a user using OAuth token-based authentication.
     *
     * This method handles OAuth authentication flow, typically used for social login providers
     * like Google. The OAuth token should be obtained from the respective provider's authentication
     * flow before calling this method.
     *
     * @param email The user's email address associated with the OAuth account
     * @param token The OAuth access token obtained from the provider
     * @param provider The OAuth provider used for authentication (defaults to Google)
     * @return [AuthenticateUserPostResponse] containing user information and session details
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the OAuth token is invalid or expired (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     * @throws IllegalArgumentException If email or token parameters are invalid
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun authenticateUserWithOAuth(
        email: String,
        token: String,
        provider: Provider = Provider.google,
    ): AuthenticateUserPostResponse {
        require(email.isNotBlank()) { "Email cannot be blank" }
        require(token.isNotBlank()) { "OAuth token cannot be blank" }

        val userToAuthenticate =
            UserToAuthenticate(
                email = email,
                password = "",
                provider = provider,
                token = token,
            )

        return try {
            val response = authApi.authenticateUserApiUserAuthenticatePost(userToAuthenticate)
            storeAuthenticationResponse(response)
            LOG.info("OAuth authentication successful for user: $email with provider: $provider")
            response
        } catch (e: Exception) {
            LOG.warn("OAuth authentication failed for user: $email with provider: $provider", e)
            throw e
        }
    }

    /**
     * Creates a new user account in the Code4Me system.
     *
     * This method registers a new user with the provided credentials. The user can be created
     * either with traditional password authentication or with OAuth provider credentials.
     * After successful creation, the user will need to authenticate separately to establish a session.
     *
     * @param email The user's email address (must be unique in the system)
     * @param name The user's full display name
     * @param password The user's password (for traditional auth) or empty string for OAuth
     * @param token Optional OAuth token if registering via OAuth provider
     * @param provider The authentication provider (defaults to Google)
     * @return [CreateUserPostResponse] containing the registration result and any relevant messages
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If user already exists or validation fails (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     * @throws IllegalArgumentException If required parameters are invalid
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun createUser(
        email: String,
        name: String,
        password: String,
        token: String = "",
        provider: Provider = Provider.google,
    ): CreateUserPostResponse {
        require(email.isNotBlank()) { "Email cannot be blank" }
        require(name.isNotBlank()) { "Name cannot be blank" }

        val userToCreate =
            UserToCreate(
                email = email,
                name = name,
                password = password,
                token = token,
                provider = provider,
            )

        return try {
            val response = createUserApi.createUserApiUserCreatePost(userToCreate)
            LOG.info("User created successfully: $email")
            response
        } catch (e: Exception) {
            LOG.warn("Failed to create user: $email", e)
            throw e
        }
    }

    /**
     * Deletes the currently authenticated user's account.
     *
     * This operation permanently removes the user's account from the system. If [deleteUserData]
     * is true, all associated user data will also be permanently deleted. After successful deletion,
     * the local session is automatically cleared.
     *
     * **Warning**: This operation is irreversible. All user data will be permanently lost if
     * [deleteUserData] is set to true.
     *
     * @param deleteUserData Whether to permanently delete all user data (defaults to false)
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the user is not authenticated or deletion fails (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun deleteUser(deleteUserData: Boolean = false) {
        try {
            userApi.deleteUserApiUserDeleteDelete(deleteUserData)

            // Clean up local session data
            clearLocalSession()
            LOG.info("User account deleted successfully")
        } catch (e: Exception) {
            LOG.warn("Failed to delete user account", e)
            throw e
        }
    }

    /**
     * Logs out the current user by clearing the local session.
     *
     * This method clears all locally stored authentication data including cookies and auth state.
     * The user will need to authenticate again to access protected resources.
     */
    fun logout() {
        clearLocalSession()
        LOG.info("User logged out successfully")
    }

    /**
     * Clears all local session data including cookies and authentication state.
     * This is a utility method used by both logout and deleteUser operations.
     */
    private fun clearLocalSession() {
        CookieAwareApiClient.clearCookies()
        getAuthState().clearUserData()
    }

    // ============ Completion Methods ============

    /**
     * Requests inline code completions based on the provided context and telemetry data.
     *
     * This method sends context information and telemetry data to the Code4Me backend to generate
     * intelligent code completions. The aggregated data should contain both contextual information
     * about the current code being edited and telemetry data about the user's coding patterns.
     *
     * @param aggregatedCollectedData A map containing context and telemetry data organized by record type.
     *        Expected to contain entries for [Record.Type.CONTEXT] and [Record.Type.TELEMETRY]
     * @return [CompletionResponseData] containing the generated completions, or null if the request fails
     * @throws IllegalArgumentException If the aggregated data is malformed
     *
     * @see Record.Type.CONTEXT
     * @see Record.Type.TELEMETRY
     */
    fun getInlineCompletion(aggregatedCollectedData: Map<Record.Type, Map<String, Any>>): CompletionResponseData? {
        require(aggregatedCollectedData.isNotEmpty()) { "Aggregated data cannot be empty" }

        val requestCompletion =
            RequestCompletion(
                modelIds = listOf(DEFAULT_MODEL_ID),
                context = aggregatedCollectedData[Record.Type.CONTEXT] ?: emptyMap(),
                telemetry = aggregatedCollectedData[Record.Type.TELEMETRY] ?: emptyMap(),
            )

        return try {
            val response = completionApi.requestCompletionApiCompletionRequestPost(requestCompletion)
            LOG.debug("Inline completion request successful")
            response.data
        } catch (e: Exception) {
            LOG.warn("Failed to get inline completion", e)
            null
        }
    }
}
