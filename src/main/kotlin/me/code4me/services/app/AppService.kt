package me.code4me.services.app

import com.intellij.openapi.components.Service
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
 * This service is initialized when the plugin starts and serves as the entry point
 * for the plugin's functionality. It can be used to coordinate other services
 * and components in the application.
 */
@Service
class AppService {
    private val configService = getConfig()
    private val serverConfig = configService.getServerConfig()

    // API base URL - should be configured from settings or environment
    private val apiBaseUrl = "${serverConfig?.host}:${serverConfig?.port}${serverConfig?.contextPath}"

    // API clients using the cookie-aware client's OkHttpClient
    private val authApi = AuthenticateApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val userApi = UserApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val createUserApi = CreateUserApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val completionApi = CompletionApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())

    /**
     * Initializes the AppService.
     *
     * This initialization block is executed when the service is first created.
     */
    init {
        println("AppService initialized with API base URL: $apiBaseUrl")
    }

    // ========= Authentication Methods =========

    /**
     * Stores the authentication response in the AuthState.
     *
     * @param response The authentication response containing the user information
     */
    private fun storeAuthenticationResponse(response: AuthenticateUserPostResponse) {
        val authSettings = getAuthState()

        // Get the session token from cookies
        val sessionToken = CookieAwareApiClient.getSessionToken()

        // Store the token (either from cookies or from response message as fallback)
        authSettings.setToken(sessionToken ?: response.message)

        // Store user information
        authSettings.setUserName(response.user.name)
        authSettings.setUserEmail(response.user.email)

        println("Authentication data stored in AuthState")
    }

    /**
     * Authenticates a user with email and password.
     *
     * @param email The user's email address
     * @param password The user's password
     * @return The authentication response if successful
     * @throws IOException If there's a network error
     * @throws ClientException If there's a client error (e.g., invalid credentials)
     * @throws ServerException If there's a server error
     */
    fun authenticateUser(
        email: String,
        password: String,
    ): AuthenticateUserPostResponse {
        val userToAuthenticate =
            UserToAuthenticate(
                email = email,
                password = password,
                provider = Provider.google,
                // Default provider
                token = "",
                // Empty token for password authentication
            )

        return try {
            val response = authApi.authenticateUserApiUserAuthenticatePost(userToAuthenticate)
            // Store the token and user information in AuthState
            storeAuthenticationResponse(response)
            response
        } catch (e: Exception) {
            println("Authentication error: ${e.message}")
            throw e
        }
    }

    /**
     * Authenticates a user with OAuth.
     *
     * @param email The user's email address
     * @param token The OAuth token
     * @param provider The OAuth provider (default is Google)
     * @return The authentication response if successful
     * @throws IOException If there's a network error
     * @throws ClientException If there's a client error (e.g., invalid token)
     * @throws ServerException If there's a server error
     */
    fun authenticateUserWithOAuth(
        email: String,
        token: String,
        provider: Provider = Provider.google,
    ): AuthenticateUserPostResponse {
        val userToAuthenticate =
            UserToAuthenticate(
                email = email,
                password = "",
                // Empty password for OAuth authentication
                provider = provider,
                token = token,
            )

        return try {
            val response = authApi.authenticateUserApiUserAuthenticatePost(userToAuthenticate)
            // Store the token and user information in AuthState
            storeAuthenticationResponse(response)
            response
        } catch (e: Exception) {
            // Log the error
            println("OAuth authentication error: ${e.message}")
            throw e
        }
    }

    /**
     * Creates a new user.
     *
     * @param email The user's email address
     * @param name The user's full name
     * @param password The user's password
     * @param token Optional JWT token for authentication
     * @param provider The OAuth provider (default is Google)
     * @return The response from creating the user
     * @throws IOException If there's a network error
     * @throws ClientException If there's a client error (e.g., user already exists)
     * @throws ServerException If there's a server error
     */
    fun createUser(
        email: String,
        name: String,
        password: String,
        token: String = "",
        provider: Provider = Provider.google,
    ): CreateUserPostResponse {
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
            println("User created successfully: ${response.message}")
            response
        } catch (e: Exception) {
            println("Error creating user: ${e.message}")
            throw e
        }
    }

    /**
     * Deletes the current user.
     *
     * @param deleteUserData Whether to delete the user's data (default is false)
     * @throws IOException If there's a network error
     * @throws ClientException If there's a client error (e.g., user not found)
     * @throws ServerException If there's a server error
     */
    fun deleteUser(deleteUserData: Boolean = false) {
        // With cookie-based authentication, we don't need to explicitly pass the token
        // as it will be included in the request cookies automatically
        try {
            userApi.deleteUserApiUserDeleteDelete(deleteUserData)
            // Clear the authentication state after successful deletion
            getAuthState().clearUserData()
            // Clear cookies
            CookieAwareApiClient.clearCookies()
            println("User deleted successfully")
        } catch (e: Exception) {
            println("Error deleting user: ${e.message}")
            throw e
        }
    }

    /**
     * Logs out the current user by clearing cookies and auth state.
     */
    fun logout() {
        // Clear cookies
        CookieAwareApiClient.clearCookies()
        // Clear auth state
        getAuthState().clearUserData()
        println("User logged out successfully")
    }

    // ============ Completion Methods ============
    fun getInlineCompletion(aggregatedCollectedData: Map<Record.Type, Map<String, Any>>): CompletionResponseData? {
        val requestCompletion =
            RequestCompletion(
                modelIds = listOf(1),
                context = aggregatedCollectedData.get(Record.Type.CONTEXT) ?: emptyMap(),
                telemetry = aggregatedCollectedData.get(Record.Type.TELEMETRY) ?: emptyMap(),
            )

        try {
            val response = completionApi.requestCompletionApiCompletionRequestPost(requestCompletion)
            println("Inline completion response: ${response.message}")
            return response.data
        } catch (e: Exception) {
            println("Error getting inline completion: ${e.message}")
            return null
        }
    }
}
