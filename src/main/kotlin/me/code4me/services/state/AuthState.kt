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
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

/**
 * Constant defining the name of the authentication state component.
 * Used for service identification and storage.
 */
const val AUTH_STATE_NAME = "me.code4me.state.auth"

/**
 * Constant for the authentication token property key.
 */
const val TOKEN_PROPERTY = "authToken"

/**
 * Constant for the user name property key.
 */
const val USER_NAME_PROPERTY = "userName"

/**
 * Constant for the user email property key.
 */
const val USER_EMAIL_PROPERTY = "userEmail"

/**
 * Helper function to access the current authentication settings.
 *
 * @return The current [AuthSettings] instance from the service.
 */
fun getAuthState(): AuthSettings {
    return service<AuthState>().state
}

/**
 * Service responsible for managing and persisting user authentication state.
 *
 * This service handles secure storage of authentication tokens and user information
 * using the IntelliJ platform's credential store.
 *
 * The state is stored in an XML file defined in the [Storage] annotation.
 */
@Service
@State(
    name = AUTH_STATE_NAME,
    storages = [Storage("code4me-auth.xml")],
)
class AuthState : SimplePersistentStateComponent<AuthSettings>(AuthSettings()) {
    companion object {
        /**
         * Creates credential attributes for secure storage.
         *
         * @param key The key to use for the credential.
         * @return A [CredentialAttributes] object for the specified key.
         */
        internal fun createCredentialAttributes(key: String): CredentialAttributes {
            return CredentialAttributes(generateServiceName(AUTH_STATE_NAME, key))
        }

        /**
         * Retrieves the authentication token from secure storage.
         *
         * @param key The key under which the token is stored.
         * @return The authentication token, or null if not found.
         */
        fun getAuthToken(key: String): String? {
            return PasswordSafe.instance.getPassword(createCredentialAttributes(key))
        }

        /**
         * Stores the authentication token in secure storage.
         *
         * @param key The key under which to store the token.
         * @param token The authentication token to store.
         * @throws IllegalArgumentException if the token is empty.
         */
        fun setAuthToken(
            key: String,
            token: String,
        ) {
            require(token.isNotEmpty()) { "The provided token cannot be blank" }
            PasswordSafe.instance.setPassword(createCredentialAttributes(key), token)
        }

        /**
         * Retrieves user information from secure storage.
         *
         * @param key The key under which the information is stored.
         * @return The user information, or null if not found.
         */
        fun getUserInfo(key: String): String? {
            return PasswordSafe.instance.getPassword(createCredentialAttributes(key))
        }

        /**
         * Stores user information in secure storage.
         *
         * @param key The key under which to store the information.
         * @param value The information to store.
         * @throws IllegalArgumentException if the value is empty.
         */
        fun setUserInfo(
            key: String,
            value: String,
        ) {
            require(value.isNotEmpty()) { "The provided value cannot be blank" }
            PasswordSafe.instance.setPassword(createCredentialAttributes(key), value)
        }
    }
}

/**
 * Class representing user authentication settings and state.
 *
 * This class provides methods to:
 * - Get and set authentication tokens
 * - Get and set user information (name, email)
 * - Clear user data
 * - Manage property change listeners for UI updates
 *
 * It extends [BaseState] to support persistence through the IntelliJ platform's
 * state persistence mechanism.
 */
class AuthSettings : BaseState() {
    /**
     * Support for property change events to notify listeners when authentication state changes.
     */
    private val propertyChangeSupport = PropertyChangeSupport(this)

    /**
     * Retrieves the user's authentication token.
     *
     * @return The authentication token, or null if not set.
     */
    fun getToken(): String? {
        return AuthState.getAuthToken(TOKEN_PROPERTY)
    }

    /**
     * Sets the user's authentication token and notifies listeners of the change.
     *
     * @param token The authentication token to set.
     */
    fun setToken(token: String) {
        val oldToken = getToken()
        AuthState.setAuthToken(TOKEN_PROPERTY, token)
        propertyChangeSupport.firePropertyChange(TOKEN_PROPERTY, oldToken, token)
    }

    /**
     * Retrieves the user's name.
     *
     * @return The user's name, or null if not set.
     */
    fun getUserName(): String? {
        return AuthState.getUserInfo(USER_NAME_PROPERTY)
    }

    /**
     * Sets the user's name and notifies listeners of the change.
     *
     * @param name The user's name to set.
     */
    fun setUserName(name: String) {
        val oldName = getUserName()
        AuthState.setUserInfo(USER_NAME_PROPERTY, name)
        propertyChangeSupport.firePropertyChange(USER_NAME_PROPERTY, oldName, name)
    }

    /**
     * Retrieves the user's email.
     *
     * @return The user's email, or null if not set.
     */
    fun getUserEmail(): String? {
        return AuthState.getUserInfo(USER_EMAIL_PROPERTY)
    }

    /**
     * Sets the user's email and notifies listeners of the change.
     *
     * @param email The user's email to set.
     */
    fun setUserEmail(email: String) {
        val oldEmail = getUserEmail()
        AuthState.setUserInfo(USER_EMAIL_PROPERTY, email)
        propertyChangeSupport.firePropertyChange(USER_EMAIL_PROPERTY, oldEmail, email)
    }

    /**
     * Clears all user data (token, name, email) and notifies listeners of the changes.
     *
     * This method is typically used during logout or when resetting the application state.
     */
    fun clearUserData() {
        val oldToken = getToken()
        val oldName = getUserName()
        val oldEmail = getUserEmail()

        // Clear all user data
        if (oldToken != null) {
            PasswordSafe.instance.setPassword(AuthState.createCredentialAttributes(TOKEN_PROPERTY), null)
            propertyChangeSupport.firePropertyChange(TOKEN_PROPERTY, oldToken, null)
        }

        if (oldName != null) {
            PasswordSafe.instance.setPassword(AuthState.createCredentialAttributes(USER_NAME_PROPERTY), null)
            propertyChangeSupport.firePropertyChange(USER_NAME_PROPERTY, oldName, null)
        }

        if (oldEmail != null) {
            PasswordSafe.instance.setPassword(AuthState.createCredentialAttributes(USER_EMAIL_PROPERTY), null)
            propertyChangeSupport.firePropertyChange(USER_EMAIL_PROPERTY, oldEmail, null)
        }
    }

    /**
     * Adds a property change listener to be notified of authentication state changes.
     *
     * @param listener The listener to add.
     */
    fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(listener)
    }

    /**
     * Removes a property change listener.
     *
     * @param listener The listener to remove.
     */
    fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(listener)
    }
}
