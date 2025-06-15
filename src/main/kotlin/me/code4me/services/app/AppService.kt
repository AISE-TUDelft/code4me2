package me.code4me.services.app

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.code4me.api.generated.api.AuthenticationApi
import me.code4me.api.generated.api.ChatApi
import me.code4me.api.generated.api.CompletionApi
import me.code4me.api.generated.api.DeactivateSessionApi
import me.code4me.api.generated.api.MultiFileContextApi
import me.code4me.api.generated.api.ProjectApi
import me.code4me.api.generated.api.SessionApi
import me.code4me.api.generated.api.UserApi
import me.code4me.api.generated.api.UserVerificationApi
import me.code4me.api.generated.infrastructure.ClientException
import me.code4me.api.generated.infrastructure.ServerException
import me.code4me.api.generated.model.AcquireSessionGetResponse
import me.code4me.api.generated.model.ActivateProject
import me.code4me.api.generated.model.ActivateProjectPostResponse
import me.code4me.api.generated.model.AuthenticateUserPostResponse
import me.code4me.api.generated.model.BehavioralTelemetryData
import me.code4me.api.generated.model.ChatHistoryResponse
import me.code4me.api.generated.model.ChatHistoryResponsePage
import me.code4me.api.generated.model.ContextData
import me.code4me.api.generated.model.ContextualTelemetryData
import me.code4me.api.generated.model.CreateProject
import me.code4me.api.generated.model.CreateProjectPostResponse
import me.code4me.api.generated.model.CreateUserPostResponse
import me.code4me.api.generated.model.DeleteChatSuccessResponse
import me.code4me.api.generated.model.FeedbackCompletion
import me.code4me.api.generated.model.FileContextChangeData
import me.code4me.api.generated.model.Provider
import me.code4me.api.generated.model.RequestChatCompletion
import me.code4me.api.generated.model.RequestCompletion
import me.code4me.api.generated.model.ResponseCompletionResponseData
import me.code4me.api.generated.model.UpdateMultiFileContext
import me.code4me.api.generated.model.UpdateUser
import me.code4me.api.generated.model.UpdateUserPutResponse
import me.code4me.api.generated.model.UserToAuthenticate
import me.code4me.api.generated.model.UserToCreate
import me.code4me.api.wrapper.CookieAwareApiClient
import me.code4me.services.config.ConfigService
import me.code4me.services.config.getConfig
import me.code4me.services.modules.manager.getModuleManager
import me.code4me.services.project.getProjectMultiFileContextService
import me.code4me.services.project.getProjectTokenService
import me.code4me.services.state.getAuthState
import me.code4me.services.state.getPrefState
import me.code4me.utils.api.fromSerializableMap
import me.code4me.utils.api.mapsTo
import me.code4me.utils.api.toSerializableMap
import me.code4me.utils.record.Record
import toApiModel
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

fun getAppService(): AppService {
    return service<AppService>()
}

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
        private const val DEFAULT_MODEL_ID = 1

        // Chat-specific timeout configurations
        private const val CHAT_CONNECT_TIMEOUT_SECONDS = 30L
        private const val CHAT_READ_TIMEOUT_SECONDS = 300L // 5 minutes for chat completions
        private const val CHAT_WRITE_TIMEOUT_SECONDS = 60L
    }

    private val configService = getConfig()
    private val serverConfig = configService.getServerConfig()
    private var sessionToken: String? = null
    private val apiBaseUrl = "${serverConfig?.host}:${serverConfig?.port}${serverConfig?.contextPath}"

    // Create a custom OkHttpClient for chat operations with extended timeouts
    private val chatHttpClient =
        CookieAwareApiClient.createClientWithCookieHandler()
            .newBuilder()
            .connectTimeout(CHAT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(CHAT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CHAT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true) // Enable automatic retry on connection failure
            .build()

    // Standard API clients with default timeouts
    private val authApi = AuthenticationApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val userApi = UserApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val completionApi = CompletionApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val sessionApi = SessionApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val projectApi = ProjectApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val userVerificationApi = UserVerificationApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val deactivateSessionApi =
        DeactivateSessionApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val multiFileContextApi =
        MultiFileContextApi(apiBaseUrl, CookieAwareApiClient.createClientWithCookieHandler())
    private val fileSnapshotCache = mutableMapOf<String, String>()
    val fileHashes = mutableMapOf<String, Int>()

    // Chat API with extended timeout configuration
    private val chatApi = ChatApi(apiBaseUrl, chatHttpClient)

    val currentGenerationProject = AtomicReference<Project?>(null)

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
        val sessionToken = CookieAwareApiClient.getAuthToken()
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
                provider = Provider.no_provider,
                token = "",
            )

        return try {
            val response = authApi.authenticateUserApiUserAuthenticatePost(userToAuthenticate)

            if (!response.user.preference.isNullOrEmpty()) {
                LOG.info("User preferences found, updating preference state")
                getPrefState().fromSerializableMap(response.user.preference!!)
            } else {
                LOG.info("No user preferences found, using default preference state")
            }

            val configService = ConfigService.fromConfigString(response.config)
            val instantiatedModules = configService.instantiateModules()
            LOG.info("Modules instantiated successfully: ${instantiatedModules.size} modules")

            // Get the ModuleManager for this project
            val moduleManager = getModuleManager()
            // Store the instantiated modules in the ModuleManager
            moduleManager.storeModules(instantiatedModules)

            // Initialize all enabled modules
            moduleManager.initializeModules()
            thisLogger().info("Modules initialized successfully.")

            // Execute module initialization on a background thread to avoid blocking the UI
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    // after that make sure that the current state of the preferences is updated
                    updateUser(
                        UpdateUser(
                            preference = getPrefState().toSerializableMap(),
                        ),
                    )
                } catch (e: Exception) {
                    thisLogger().error("Failed to initialize modules", e)
                }
            }

            // for all of the open projects, we need to set their project token service activated to false
            ProjectManager.getInstance().openProjects.forEach { project ->
                // get the project token service for the project
                val projectTokenService = getProjectTokenService(project)
                // set the activated state to false
                projectTokenService.setActivated(false)
            }

            // the reason this is moved so far down is because there is a change listener
            // on the auth state values (so the token, user name, and email)
            // that will update the UI components when the values change
            // and we want to make sure that the modules are initialized before we store the auth state
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

    // ============ Session Methods ============

    /**
     * Stores the session response data in the application's auth state.
     *
     * This method extracts the session token from the response and stores it in the auth state
     * for use throughout the application session. It also handles cookie-based session management.
     *
     * @param response The session response containing session token and message
     */
    private fun storeSessionResponse(response: AcquireSessionGetResponse) {
        sessionToken = CookieAwareApiClient.getSessionToken()
        if (sessionToken.isNullOrBlank()) {
            LOG.warn("Session token is null or blank, using response message instead")
            sessionToken = response.sessionToken
        }
        LOG.info("Session data stored successfully: ${response.message}")
    }

    /**
     * Acquires or creates a session token using the provided auth token.
     *
     * This method requests a session from the Code4Me backend using an authentication token.
     * If no session is currently associated with the auth token, a new session will be created
     * and stored in the backend. The session token is automatically stored locally for
     * subsequent API requests.
     *
     * @param authToken The authentication token used to acquire the session (optional, defaults to "auth_token")
     * @return [AcquireSessionGetResponse] containing the session token and success message
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the auth token is invalid or expired (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     * @throws IllegalArgumentException If the auth token is blank when provided
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun acquireSession(authToken: String? = "auth_token"): AcquireSessionGetResponse {
        authToken?.let { token ->
            require(token.isNotBlank()) { "Auth token cannot be blank" }
        }

        return try {
            val response = sessionApi.acquireSessionApiSessionAcquireGet(authToken)
            storeSessionResponse(response)
            LOG.info("Session acquired successfully with auth token")
            response
        } catch (e: Exception) {
            LOG.warn("Failed to acquire session with auth token", e)
            throw e
        }
    }

    /**
     * Acquires a session using the currently stored authentication token.
     *
     * This is a convenience method that uses the authentication token stored in the local
     * auth state to acquire a session. If no auth token is stored locally, it will fall back
     * to using the default auth token.
     *
     * @return [AcquireSessionGetResponse] containing the session token and success message
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the stored auth token is invalid or expired (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun acquireSessionWithStoredToken(): AcquireSessionGetResponse {
        val authSettings = getAuthState()
        val storedToken = authSettings.getToken()

        return if (storedToken?.isNotBlank() ?: false) {
            acquireSession(storedToken)
        } else {
            LOG.info("No stored auth token found, using default token for session acquisition")
            acquireSession()
        }
    }

    /**
     * Refreshes the current session by acquiring a new session token.
     *
     * This method is useful when the current session may have expired or when you want to
     * ensure you have a fresh session token. It uses the currently stored authentication
     * token to acquire a new session.
     *
     * @return [AcquireSessionGetResponse] containing the new session token and success message
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the stored auth token is invalid or expired (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun refreshSession(): AcquireSessionGetResponse {
        LOG.info("Refreshing session token")
        return acquireSessionWithStoredToken()
    }

    /**
     * Checks if a valid session is currently available.
     *
     * This method verifies if there's a session token stored in the auth state,
     * indicating that a session has been established.
     *
     * @return true if a session token is available, false otherwise
     */
    fun hasValidSession(): Boolean {
        val authSettings = getAuthState()
        val hasSession = authSettings.getToken()?.isNotBlank()
        LOG.debug("Session validity check: $hasSession")
        return hasSession == true
    }

    /**
     * Deactivates the current user session.
     *
     * This method sends a request to the Code4Me backend to deactivate the current session.
     * It uses the stored authentication token to validate the request. Upon successful deactivation,
     * the local session state is cleared.
     *
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the session token is invalid or deactivation fails (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun deactivateSession() {
        try {
            val response = deactivateSessionApi.deactivateSessionApiSessionDeactivatePut()
            LOG.info("Session deactivated successfully: ${response.message}")
        } catch (e: Exception) {
            LOG.warn("Failed to deactivate session", e)
            throw e
        }
    }

    // ============ Project Management Methods ============

    /**
     * Stores the project creation response data locally.
     *
     * This method extracts the project token from the response and stores it locally
     * for use in subsequent project-related operations.
     *
     * @param response The project creation response containing project token and details
     */
    private fun storeProjectResponse(
        project: Project,
        response: CreateProjectPostResponse,
    ) {
        getProjectTokenService(project).setProjectToken(response.projectToken)
        LOG.info("Project data stored successfully: ${response.message}")
    }

    /**
     * Creates a new project using the provided project details.
     *
     * This method creates a new project in the Code4Me backend using the current session token.
     * The session token is validated before creating the project. Upon successful creation,
     * the project token is automatically stored locally for subsequent operations.
     *
     * @param createProject The project creation details including name, description, and configuration
     * @param authToken The authentication token (optional, defaults to "auth_token")
     * @return [CreateProjectPostResponse] containing the project token and creation details
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the session token is invalid or project creation fails (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     * @throws IllegalArgumentException If the createProject parameter is invalid
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun createProject(
        createProject: CreateProject,
        project: Project,
    ): CreateProjectPostResponse {
        return try {
            val response = projectApi.createProjectApiProjectCreatePost(createProject)
            storeProjectResponse(project, response)
            LOG.info("Project created successfully: $createProject")
            response
        } catch (e: Exception) {
            LOG.warn("Failed to create project: $createProject", e)
            throw e
        }
    }

    /**
     * Creates a new project using the currently stored authentication token.
     *
     * This is a convenience method that uses the authentication token stored in the local
     * auth state to create a project. If no auth token is stored locally, it will fall back
     * to using the default auth token.
     *
     * @param createProject The project creation details including name, description, and configuration
     * @return [CreateProjectPostResponse] containing the project token and creation details
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the stored auth token is invalid or project creation fails (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     * @throws IllegalArgumentException If the createProject parameter is invalid
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun createProjectWithStoredToken(
        createProject: CreateProject,
        project: Project,
    ): CreateProjectPostResponse? {
        return createProject(createProject, project)
    }

    /**
     * Activates an existing project using the provided project details.
     *
     * This method activates a project by validating the provided auth token and either
     * fetching the project from the database to Redis or updating its expiration time if
     * it already exists in Redis. Upon successful activation, any updated project information
     * is stored locally.
     *
     * @param activateProject The project activation details including project identifier
     * @param authToken The authentication token (optional, defaults to "auth_token")
     * @return [ActivateProjectPostResponse] containing activation confirmation and project details
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the auth token is invalid or project activation fails (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     * @throws IllegalArgumentException If the activateProject parameter is invalid
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun activateProject(activateProject: ActivateProject): ActivateProjectPostResponse {
        return try {
            val response = projectApi.activateProjectApiProjectActivatePut(activateProject)
            LOG.info("Project activated successfully: $activateProject")
            response
        } catch (e: Exception) {
            LOG.warn("Failed to activate project: $activateProject", e)
            throw e
        }
    }

    // ============ User Management Methods ============

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
        provider: Provider = Provider.no_provider,
    ): CreateUserPostResponse {
        require(email.isNotBlank()) { "Email cannot be blank" }
        require(name.isNotBlank()) { "Name cannot be blank" }

        val userToCreate =
            UserToCreate(
                email = email,
                name = name,
                password = password,
                token = token,
                // Assuming configId is always 1 - this means the default configuration
                configId = 1,
                provider = provider,
            )

        return try {
            val response = userApi.createUserApiUserCreatePost(userToCreate)
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
     * Clears all local session data including cookies and authentication state.
     * This is a utility method used by both logout and deleteUser operations.
     */
    private fun clearLocalSession() {
        CookieAwareApiClient.clearCookies()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                getAuthState().clearUserData()
                LOG.info("User data cleared successfully during sign out")
            } catch (e: Exception) {
                LOG.error("Failed to clear user data during sign out", e)
            }
        }
    }

    // Add this new method to replace the existing updateUserName method

    /**
     * Updates the current user's information.
     *
     * This method updates the user's information in the Code4Me system based on the provided UpdateUser object.
     * It can handle updates to name, email, password, and other user properties. Upon successful update,
     * the local user information is also updated to reflect the changes.
     *
     * @param updateUser The UpdateUser object containing the fields to be updated
     * @return [UpdateUserPutResponse] containing the update result and any relevant messages
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the update fails due to client-side issues (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     * @throws IllegalArgumentException If all fields in updateUser are null or empty
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun updateUser(updateUser: UpdateUser): UpdateUserPutResponse {
        // Validate that at least one field is provided for update
        val hasValidField =
            listOf(
                updateUser.name,
                updateUser.email,
                updateUser.password,
                updateUser.previousPassword,
                updateUser.preference,
                updateUser.configId,
                updateUser.verified,
            ).any { it != null && (it !is String || it.isNotBlank()) }

        require(hasValidField) { "At least one field must be provided for update" }

        return try {
            val response = userApi.updateUserApiUserUpdatePut(updateUser)

            // Update local user information if name or email was changed
            updateUser.name?.takeIf { it.isNotBlank() }?.let { newName ->
                getAuthState().setUserName(newName)
            }
            updateUser.email?.takeIf { it.isNotBlank() }?.let { newEmail ->
                getAuthState().setUserEmail(newEmail)
            }
            LOG.info("User information updated successfully")
            response
        } catch (e: Exception) {
            LOG.warn("Failed to update user information", e)
            throw e
        }
    }

    /**
     * Retrieves the currently authenticated user using the stored auth token.
     *
     * @return GetUserGetResponse containing the current user's information
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the request fails due to client-side issues (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun getCurrentUser(): GetUserGetResponse {
        try {
            val response = userApi.getUserFromAuthTokenApiUserGetGet()
            LOG.info("Current user retrieved successfully: ${response.user.email}")
            return response
        } catch (e: Exception) {
            LOG.warn("Failed to retrieve current user", e)
            throw e
        }
    }

    /**
     * request a reset of the user's password.
     * This method sends a password reset request to the Code4Me backend.
     *
     * @return true if the request was successful, false otherwise
     */
    fun requestPasswordReset(email: String): Boolean {
        require(email.isNotBlank()) { "Email cannot be blank" }

        try {
            val response = userApi.requestPasswordResetApiUserResetPasswordRequestPost(email)
            LOG.info("Password reset requested successfully for user: $email")
            return true
        } catch (e: Exception) {
            LOG.warn("Failed to request password reset for user: $email", e)
            return false
        }
    }

    // ============ User Verification Methods ============

    fun isUserVerified(): Boolean {
        // check if the user is verified by querying the user verification API
        try {
            val response = userVerificationApi.checkVerificationApiUserVerifyCheckGet()
            LOG.info("User verification status retrieved successfully: $response")
            return true
        } catch (e: Exception) {
            LOG.warn("Failed to check user verification status", e)
            return false
        }
    }

    fun resendVerificationEmail(): Boolean {
        // resend the verification email by calling the user verification API
        try {
            val response = userVerificationApi.resendVerificationEmailApiUserVerifyResendPost()
            LOG.info("Verification email resent successfully")
            response.toString().contains("true", ignoreCase = true).also { isSuccess ->
                if (isSuccess) {
                    LOG.info("Verification email sent successfully")
                } else {
                    LOG.warn("Failed to send verification email")
                }
            }
            return true
        } catch (e: Exception) {
            LOG.warn("Failed to resend verification email", e)
            return false
        }
    }

    // ============ Chat Methods ============

    /**
     * Requests a chat completion based on provided messages.
     *
     * This method sends a chat completion request to the Code4Me backend using the current
     * session and project tokens. The request contains all the history of the chat to ensure
     * completions are based on the latest state, even if the user has modified previous messages.
     *
     * @param requestChatCompletion The chat completion request containing messages and configuration
     * @param sessionToken The session token (optional, uses stored token if not provided)
     * @param projectToken The project token (optional, uses stored token if not provided)
     * @return [ChatHistoryResponse] containing the chat completion and updated history
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the tokens are invalid or request fails (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     * @throws IllegalArgumentException If the requestChatCompletion parameter is invalid
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun requestChatCompletion(
        requestChatCompletion: RequestChatCompletion,
        project: Project,
    ): ChatHistoryResponse {
        require(requestChatCompletion.messages.isNotEmpty()) { "Messages cannot be empty" }

        currentGenerationProject.set(project)

        return try {
            val response =
                chatApi.requestChatCompletionApiChatRequestPost(
                    requestChatCompletion = requestChatCompletion,
                )
            LOG.info("Chat completion requested successfully")
            response
        } catch (e: Exception) {
            LOG.warn("Chat completion request failed", e)
            throw e
        } finally {
            currentGenerationProject.set(null)
        }
    }

    /**
     * Retrieves chat history for a specific page.
     *
     * This method fetches the chat history from the Code4Me backend using pagination.
     * It validates user access through session and project tokens before returning the history.
     *
     * @param pageNumber The page number to retrieve (0-based)
     * @param sessionToken The session token (optional, uses stored token if not provided)
     * @param projectToken The project token (optional, uses stored token if not provided)
     * @return [ChatHistoryResponsePage] containing the chat history for the requested page
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the tokens are invalid or request fails (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     * @throws IllegalArgumentException If the pageNumber is invalid
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun getChatHistory(
        pageNumber: Int,
        project: Project,
    ): ChatHistoryResponsePage {
        require(pageNumber >= 0) { "Page number must be non-negative" }

        currentGenerationProject.set(project)

        return try {
            val response =
                chatApi.getChatHistoryApiChatGetPageNumberGet(
                    pageNumber = pageNumber,
                )
            LOG.info("Chat history retrieved successfully for page: $pageNumber")
            response
        } catch (e: Exception) {
            LOG.warn("Failed to retrieve chat history for page: $pageNumber", e)
            throw e
        } finally {
            currentGenerationProject.set(null)
        }
    }

    /**
     * Deletes a specific chat by its ID.
     *
     * This method deletes a chat conversation from the Code4Me backend. It validates that the user
     * has access to the chat through their session and project tokens before performing the deletion.
     *
     * @param chatId The unique identifier of the chat to delete
     * @param sessionToken The session token (optional, uses stored token if not provided)
     * @param projectToken The project token (optional, uses stored token if not provided)
     * @return [DeleteChatSuccessResponse] containing confirmation of the deletion
     * @throws IOException If there's a network connectivity issue
     * @throws ClientException If the tokens are invalid or chat access is denied (4xx errors)
     * @throws ServerException If the server encounters an internal error (5xx errors)
     * @throws IllegalArgumentException If the chatId is invalid
     */
    @Throws(IOException::class, ClientException::class, ServerException::class)
    fun deleteChat(
        chatId: java.util.UUID,
        project: Project,
    ): DeleteChatSuccessResponse {
        require(chatId.toString().isNotBlank()) { "Chat ID cannot be blank" }

        currentGenerationProject.set(project)

        return try {
            val response =
                chatApi.deleteChatApiChatDeleteChatIdDelete(
                    chatId = chatId,
                )
            LOG.info("Chat deleted successfully: $chatId")
            response
        } catch (e: Exception) {
            LOG.warn("Failed to delete chat: $chatId", e)
            throw e
        } finally {
            currentGenerationProject.set(null)
        }
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
     *        Expected to contain entries for [Record.Type.CONTEXT], [Record.Type.BEHAVIORAL_TELEMETRY] and [Record.Type.CONTEXTUAL_TELEMETRY]
     * @return [CompletionResponseData] containing the generated completions, or null if the request fails
     * @throws IllegalArgumentException If the aggregated data is malformed
     *
     * @see Record.Type.CONTEXT
     * @see Record.Type.BEHAVIORAL_TELEMETRY
     * @see Record.type.CONTEXTUAL_TELEMETRY
     */
    suspend fun getInlineCompletion(
        aggregatedCollectedData: Map<Record.Type, Map<String, Any>>,
        project: Project,
    ): ResponseCompletionResponseData? =
        withContext(Dispatchers.IO) {
            require(aggregatedCollectedData.isNotEmpty()) { "Aggregated data cannot be empty" }

            currentGenerationProject.set(project)
            val modelId =
                getConfig().getModelsConfiguration()
                    ?.getModelIdByName(
                        aggregatedCollectedData[Record.Type.MODEL]?.get("preferredCompletionModel")?.toString() ?: "default",
                    )

            val rawContext = aggregatedCollectedData[Record.Type.CONTEXT]?.toMutableMap() ?: error("Missing CONTEXT")
            val context = rawContext.mapsTo<ContextData>(ContextData::class.java)

            val multiFileDiffs: MutableMap<String, List<FileContextChangeData>> = mutableMapOf()

            val relativePaths =
                (rawContext["multi_file_context.paths"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

            val basePath = project.basePath ?: return@withContext null
            val contextService = getProjectMultiFileContextService(project)
            val changedFiles = mutableMapOf<String, String>()
            withContext(Dispatchers.Default) {
                for (relPath in relativePaths) {
                    val fullPath = "$basePath${File.separator}$relPath".replace("/", File.separator)
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: continue
                    val newText =
                        ApplicationManager.getApplication().runReadAction<String?> {
                            FileDocumentManager.getInstance().getDocument(virtualFile)?.text
                        } ?: continue
                    contextService.saveInitialSnapshotIfMissing(fullPath, newText)

                    val changes = contextService.updateFileContent(fullPath, newText)
                    if (changes.isNotEmpty()) {
                        multiFileDiffs[relPath] = changes.map { it.toApiModel() }
                        changedFiles[fullPath] = newText
                    }
                }
            }

            if (multiFileDiffs.isNotEmpty()) {
                val update = UpdateMultiFileContext(contextUpdates = multiFileDiffs)
                val sent = sendMultiFileContextUpdate(update)

                if (sent) {
                    // update local cache once the server confirms the update
                    changedFiles.forEach { (path, text) ->
                        contextService.writeCache(path, text)
                    }
                } else {
                    LOG.warn("Failed to send multi-file context updates")
                }
            }

            rawContext["context_files"] = relativePaths
            val requestCompletion =
                RequestCompletion(
                    modelIds = listOfNotNull(modelId),
                    context = rawContext.mapsTo(ContextData::class.java),
                    behavioralTelemetry =
                        (aggregatedCollectedData[Record.Type.BEHAVIORAL_TELEMETRY] ?: emptyMap())
                            .mapsTo<BehavioralTelemetryData>(
                                BehavioralTelemetryData::class.java,
                            ),
                    contextualTelemetry =
                        (aggregatedCollectedData[Record.Type.CONTEXTUAL_TELEMETRY] ?: emptyMap())
                            .mapsTo<ContextualTelemetryData>(
                                ContextualTelemetryData::class.java,
                            ),
                )

            try {
                val response = completionApi.requestCompletionApiCompletionRequestPost(requestCompletion)
                LOG.debug("Inline completion request successful")
                response.data
            } catch (e: Exception) {
                LOG.warn("Failed to get inline completion", e)
                null
            } finally {
                currentGenerationProject.set(null)
            }
        }

    fun sendMultiFileContextUpdate(update: UpdateMultiFileContext): Boolean {
        return try {
            multiFileContextApi.updateMultiFileContextApiCompletionMultiFileContextUpdatePost(update)
            LOG.debug("Multi-file context update sent successfully.")
            true
        } catch (e: Exception) {
            LOG.warn("Failed to send multi-file context update", e)
            false
        }
    }

    /**
     * Submits feedback for a completion, including ground truth data.
     *
     * This method sends feedback about a completion to the Code4Me backend, including
     * whether the completion was accepted and the ground truth data (the actual code
     * that was inserted or modified).
     *
     * @param metaQueryId The unique identifier of the completion query
     * @param modelId The ID of the model that generated the completion
     * @param wasAccepted Whether the completion was accepted by the user
     * @param groundTruth The ground truth data (the actual code that was inserted or modified)
     * @param project The project context
     * @return [CompletionFeedbackPostResponse] containing the server's response, or null if the request fails
     */
    fun submitCompletionFeedback(
        metaQueryId: java.util.UUID,
        modelId: Int,
        wasAccepted: Boolean,
        groundTruth: String?,
        project: Project,
    ): me.code4me.api.generated.model.CompletionFeedbackPostResponse? {
        // set the current project for generation
        currentGenerationProject.set(project)

        val feedbackCompletion =
            FeedbackCompletion(
                metaQueryId = metaQueryId,
                modelId = modelId,
                wasAccepted = wasAccepted,
                groundTruth = groundTruth,
            )

        return try {
            val response = completionApi.submitCompletionFeedbackApiCompletionFeedbackPost(feedbackCompletion)
            LOG.debug("Completion feedback submitted successfully")
            response
        } catch (e: Exception) {
            LOG.warn("Failed to submit completion feedback", e)
            null
        } finally {
            currentGenerationProject.set(null)
        }
    }
}
