package me.code4me.services.state

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import me.code4me.services.state.AuthState.Companion.getAuthToken
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

/**
 * Service identifier for authentication state management.
 */
const val AUTH_STATE_NAME = "me.code4me.state.authentication"

/**
 * Property keys for authentication-related data.
 */
const val TOKEN_PROPERTY = "authToken"
const val USER_NAME_PROPERTY = "userName"
const val USER_EMAIL_PROPERTY = "userEmail"
const val IS_VERIFIED_PROPERTY = "isVerified"

/**
 * Helper function to access the current authentication settings.
 *
 * @return The current [AuthSettings] instance from the service
 */
fun getAuthState(): AuthSettings {
    return service<AuthState>().state
}

/**
 * Application service responsible for managing user authentication state and secure credential storage.
 *
 * This service provides:
 * - Secure storage of authentication tokens using IntelliJ Platform's credential store
 * - Management of user profile information (name, email)
 * - Property change notifications for reactive UI updates
 * - Thread-safe access to authentication data
 * - Integration with IntelliJ Platform's security infrastructure
 *
 * All sensitive data (tokens, user information) is stored securely using the platform's
 * [PasswordSafe] service, which integrates with the operating system's credential store.
 *
 * Thread Safety: This service is thread-safe and can be accessed from any thread.
 *
 * @since 1.0.0
 */
@Service
@State(
    name = AUTH_STATE_NAME,
    storages = [Storage("code4me-authentication.xml")],
)
class AuthState : SimplePersistentStateComponent<AuthSettings>(AuthSettings()) {
    companion object {
        private val LOG = thisLogger()

        /**
         * Creates credential attributes for secure storage operations.
         *
         * This method generates platform-specific credential attributes that integrate
         * with the operating system's secure storage mechanisms (Keychain on macOS,
         * Credential Manager on Windows, etc.).
         *
         * @param key The unique identifier for the credential
         * @return Configured [CredentialAttributes] for secure storage operations
         */
        internal fun createCredentialAttributes(key: String): CredentialAttributes {
            return CredentialAttributes(
                serviceName = generateServiceName(AUTH_STATE_NAME, key),
                userName = null,
            )
        }

        /**
         * Retrieves an authentication token from secure storage.
         *
         * @param key The unique identifier for the token
         * @return The stored token, or null if not found or inaccessible
         */
        fun getAuthToken(key: String): String? {
            return try {
                PasswordSafe.instance.getPassword(createCredentialAttributes(key))
            } catch (e: Exception) {
                LOG.warn("Failed to retrieve authentication token for key: $key", e)
                null
            }
        }

        /**
         * Stores an authentication token in secure storage.
         *
         * The token is encrypted and stored using the platform's secure credential store,
         * ensuring it persists across IDE sessions while maintaining security.
         *
         * @param key The unique identifier for the token
         * @param token The authentication token to store
         * @throws IllegalArgumentException if the token is empty or blank
         */
        fun setAuthToken(
            key: String,
            token: String,
        ) {
            require(token.isNotBlank()) { "Authentication token cannot be blank" }

            try {
                PasswordSafe.instance.setPassword(createCredentialAttributes(key), token)
                LOG.debug("Authentication token stored successfully for key: $key")
            } catch (e: Exception) {
                LOG.error("Failed to store authentication token for key: $key", e)
                throw e
            }
        }

        /**
         * Retrieves user information from secure storage.
         *
         * @param key The unique identifier for the user information
         * @return The stored information, or null if not found or inaccessible
         */
        fun getUserInfo(key: String): String? {
            return try {
                PasswordSafe.instance.getPassword(createCredentialAttributes(key))
            } catch (e: Exception) {
                LOG.warn("Failed to retrieve user information for key: $key", e)
                null
            }
        }

        /**
         * Stores user information in secure storage.
         *
         * @param key The unique identifier for the information
         * @param value The information to store
         * @throws IllegalArgumentException if the value is empty or blank
         */
        fun setUserInfo(
            key: String,
            value: String,
        ) {
            require(value.isNotBlank()) { "User information value cannot be blank" }

            try {
                PasswordSafe.instance.setPassword(createCredentialAttributes(key), value)
                LOG.debug("User information stored successfully for key: $key")
            } catch (e: Exception) {
                LOG.error("Failed to store user information for key: $key", e)
                throw e
            }
        }

        /**
         * Removes stored data from secure storage.
         *
         * @param key The unique identifier for the data to remove
         */
        fun removeSecureData(key: String) {
            try {
                PasswordSafe.instance.setPassword(createCredentialAttributes(key), null)
                LOG.debug("Secure data removed successfully for key: $key")
            } catch (e: Exception) {
                LOG.warn("Failed to remove secure data for key: $key", e)
            }
        }

        /**
         * Checks if the user is verified.
         *
         * @return True if the user is verified, false otherwise
         */
        fun isUserVerified(): Boolean {
            return getAuthToken(IS_VERIFIED_PROPERTY)?.toBoolean() ?: false
        }

        /**
         * Sets the verification status of the user.
         *
         * @param isVerified True if the user is verified, false otherwise
         */
        fun setUserVerified(isVerified: Boolean?) {
            try {
                setAuthToken(IS_VERIFIED_PROPERTY, isVerified.toString())
                LOG.debug("User verification status updated successfully: $isVerified")
            } catch (e: Exception) {
                LOG.error("Failed to set user verification status", e)
                throw e
            }
        }
    }
}

/**
 * Data class representing user authentication settings and state management.
 *
 * This class provides a high-level interface for managing authentication data:
 * - Authentication token management with automatic encryption
 * - User profile information storage and retrieval
 * - Property change event notifications for UI reactivity
 * - Secure data cleanup during logout operations
 *
 * The class extends [BaseState] to integrate with the IntelliJ Platform's persistence
 * mechanism while using secure storage for sensitive data.
 *
 * @since 1.0.0
 */

@OptIn(DelicateCoroutinesApi::class)
class AuthSettings : BaseState() {
    companion object {
        private val LOG = thisLogger()
    }

    /**
     * Property change support for notifying UI components of authentication state changes.
     */
    private val propertyChangeSupport = PropertyChangeSupport(this)

    // Cache for user information to avoid repeated secure storage access
    @Volatile
    private var cachedUserName: String? = null

    @Volatile
    private var cachedUserEmail: String? = null

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var isVerified: Boolean? = false

    init {
        GlobalScope.launch(Dispatchers.IO) {
            // Initialize the cache in a background thread
            initializeCache()
        }
    }

    /**
     * Initializes the cache by loading user information from secure storage.
     * This should be called once during service initialization.
     */
    private fun initializeCache() {
        synchronized(this) {
            cachedUserName = AuthState.getUserInfo(USER_NAME_PROPERTY)
            cachedUserEmail = AuthState.getUserInfo(USER_EMAIL_PROPERTY)
            cachedToken = AuthState.getAuthToken(TOKEN_PROPERTY)
            LOG.debug("User information cache initialized")
        }
    }

    /**
     * Retrieves the user's authentication token.
     *
     * @return The current authentication token, or null if not authenticated
     */
    fun getToken(): String? {
        return cachedToken
    }

    /**
     * Sets the user's authentication token and notifies listeners.
     *
     * This method stores the token securely and fires a property change event
     * to notify UI components of the authentication state change.
     *
     * @param token The authentication token to store
     * @throws IllegalArgumentException if the token is blank
     */
    fun setToken(token: String) {
        require(token.isNotBlank()) { "Authentication token cannot be blank" }

        val oldToken = getToken()
        try {
            AuthState.setAuthToken(TOKEN_PROPERTY, token)
            propertyChangeSupport.firePropertyChange(TOKEN_PROPERTY, oldToken, token)
            cachedToken = token
            LOG.debug("Authentication token updated successfully")
        } catch (e: Exception) {
            LOG.error("Failed to set authentication token", e)
            throw e
        }
    }

    /**
     * Retrieves the user's display name from cache.
     *
     * @return The user's name, or null if not set
     */
    fun getUserName(): String? {
        return cachedUserName
    }

    /**
     * Sets the user's display name and updates cache.
     *
     * @param name The user's display name
     * @throws IllegalArgumentException if the name is blank
     */
    fun setUserName(name: String) {
        require(name.isNotBlank()) { "User name cannot be blank" }

        val oldName = cachedUserName
        try {
            AuthState.setUserInfo(USER_NAME_PROPERTY, name)
            cachedUserName = name
            propertyChangeSupport.firePropertyChange(USER_NAME_PROPERTY, oldName, name)
            LOG.debug("User name updated successfully")
        } catch (e: Exception) {
            LOG.error("Failed to set user name", e)
            throw e
        }
    }

    /**
     * Retrieves the user's email address from cache.
     *
     * @return The user's email, or null if not set
     */
    fun getUserEmail(): String? {
        return cachedUserEmail
    }

    /**
     * Sets the user's email address and updates cache.
     *
     * @param email The user's email address
     * @throws IllegalArgumentException if the email is blank
     */
    fun setUserEmail(email: String) {
        require(email.isNotBlank()) { "User email cannot be blank" }

        val oldEmail = cachedUserEmail
        try {
            AuthState.setUserInfo(USER_EMAIL_PROPERTY, email)
            cachedUserEmail = email
            propertyChangeSupport.firePropertyChange(USER_EMAIL_PROPERTY, oldEmail, email)
            LOG.debug("User email updated successfully")
        } catch (e: Exception) {
            LOG.error("Failed to set user email", e)
            throw e
        }
    }

    /**
     * Determines if the user is currently authenticated.
     *
     * @return True if a valid authentication token exists, false otherwise
     */
    fun isAuthenticated(): Boolean {
        return !getToken().isNullOrBlank()
    }

    /**
     * Checks if the user is verified.
     *
     * @return True if the user is verified, false otherwise
     */
    fun isVerified(): Boolean? {
        return isVerified
    }

    fun setVerified(verified: Boolean?) {
        val oldVerified = isVerified
        isVerified = verified
        AuthState.setUserVerified(verified)
        propertyChangeSupport.firePropertyChange(IS_VERIFIED_PROPERTY, oldVerified, verified)
        LOG.debug("User verification status updated: $verified")
    }

    /**
     * Clears all user authentication data, cache, and notifies listeners.
     */
    fun clearUserData() {
        val oldToken = cachedToken
        val oldName = cachedUserName
        val oldEmail = cachedUserEmail
        val oldVerified = isVerified

        try {
            // Clear all secure data
            if (oldToken != null) {
                AuthState.removeSecureData(TOKEN_PROPERTY)
                cachedToken = null
                propertyChangeSupport.firePropertyChange(TOKEN_PROPERTY, oldToken, null)
            }

            if (oldName != null) {
                AuthState.removeSecureData(USER_NAME_PROPERTY)
                cachedUserName = null
                propertyChangeSupport.firePropertyChange(USER_NAME_PROPERTY, oldName, null)
            }

            if (oldEmail != null) {
                AuthState.removeSecureData(USER_EMAIL_PROPERTY)
                cachedUserEmail = null
                propertyChangeSupport.firePropertyChange(USER_EMAIL_PROPERTY, oldEmail, null)
            }

            if (oldVerified != null) {
                AuthState.removeSecureData(IS_VERIFIED_PROPERTY)
                isVerified = false
                propertyChangeSupport.firePropertyChange(IS_VERIFIED_PROPERTY, oldVerified, false)
            }

            LOG.info("User authentication data cleared successfully")
        } catch (e: Exception) {
            LOG.error("Failed to clear user data completely", e)
        }
    }

    /**
     * Registers a property change listener for authentication state changes.
     *
     * Listeners will be notified when:
     * - Authentication token changes (login/logout)
     * - User name is updated
     * - User email is updated
     *
     * @param listener The listener to register
     */
    fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(listener)
        LOG.debug("Property change listener registered")
    }

    /**
     * Unregisters a property change listener.
     *
     * @param listener The listener to remove
     */
    fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(listener)
        LOG.debug("Property change listener removed")
    }

    /**
     * Registers a property change listener for a specific property.
     *
     * @param propertyName The name of the property to listen for
     * @param listener The listener to register
     */
    fun addPropertyChangeListener(
        propertyName: String,
        listener: PropertyChangeListener,
    ) {
        propertyChangeSupport.addPropertyChangeListener(propertyName, listener)
        LOG.debug("Property change listener registered for property: $propertyName")
    }

    /**
     * Unregisters a property change listener for a specific property.
     *
     * @param propertyName The name of the property
     * @param listener The listener to remove
     */
    fun removePropertyChangeListener(
        propertyName: String,
        listener: PropertyChangeListener,
    ) {
        propertyChangeSupport.removePropertyChangeListener(propertyName, listener)
        LOG.debug("Property change listener removed for property: $propertyName")
    }
}
