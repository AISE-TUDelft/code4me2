package me.code4me.services.state

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*

const val AUTH_STATE_NAME = "me.code4me.state.auth"

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
        private fun createCredentialAttributes(key: String): CredentialAttributes {
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
    }
}


class AuthSettings: BaseState() {
    fun getToken(): String? {
        return AuthState.getAuthToken("authToken")
    }

    fun setToken(token: String) {
        AuthState.setAuthToken("authToken", token)
    }
}

