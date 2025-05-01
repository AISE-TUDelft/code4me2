package me.code4me.services.state

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

const val AUTH_STATE_NAME = "me.code4me.state.auth"
const val TOKEN_PROPERTY = "authToken"
const val USER_NAME_PROPERTY = "userName"
const val USER_EMAIL_PROPERTY = "userEmail"

fun getAuthState(): AuthSettings {
    return service<AuthState>().state
}

@Service
@State(
    name = AUTH_STATE_NAME,
    storages = [Storage("code4me-auth.xml")]
)
class AuthState : SimplePersistentStateComponent<AuthSettings>(AuthSettings()) {
    companion object {
        internal fun createCredentialAttributes(key: String): CredentialAttributes {
            return CredentialAttributes(generateServiceName(AUTH_STATE_NAME, key))
        }

        // store the auth token for the user
        fun getAuthToken(key: String): String? {
            return PasswordSafe.instance.getPassword(createCredentialAttributes(key))
        }

        fun setAuthToken(key: String, token: String) {
            require(token.isNotEmpty()) {"The provided token cannot be blank"}
            PasswordSafe.instance.setPassword(createCredentialAttributes(key), token)
        }

        // store user information securely
        fun getUserInfo(key: String): String? {
            return PasswordSafe.instance.getPassword(createCredentialAttributes(key))
        }

        fun setUserInfo(key: String, value: String) {
            require(value.isNotEmpty()) {"The provided value cannot be blank"}
            PasswordSafe.instance.setPassword(createCredentialAttributes(key), value)
        }
    }
}


class AuthSettings: BaseState() {
    private val propertyChangeSupport = PropertyChangeSupport(this)

    fun getToken(): String? {
        return AuthState.getAuthToken(TOKEN_PROPERTY)
    }

    fun setToken(token: String) {
        val oldToken = getToken()
        AuthState.setAuthToken(TOKEN_PROPERTY, token)
        propertyChangeSupport.firePropertyChange(TOKEN_PROPERTY, oldToken, token)
    }

    fun getUserName(): String? {
        return AuthState.getUserInfo(USER_NAME_PROPERTY)
    }

    fun setUserName(name: String) {
        val oldName = getUserName()
        AuthState.setUserInfo(USER_NAME_PROPERTY, name)
        propertyChangeSupport.firePropertyChange(USER_NAME_PROPERTY, oldName, name)
    }

    fun getUserEmail(): String? {
        return AuthState.getUserInfo(USER_EMAIL_PROPERTY)
    }

    fun setUserEmail(email: String) {
        val oldEmail = getUserEmail()
        AuthState.setUserInfo(USER_EMAIL_PROPERTY, email)
        propertyChangeSupport.firePropertyChange(USER_EMAIL_PROPERTY, oldEmail, email)
    }

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


    fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(listener)
    }

    fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(listener)
    }
}
