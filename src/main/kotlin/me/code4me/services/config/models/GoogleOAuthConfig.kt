package me.code4me.services.config.models

import com.typesafe.config.Config

/**
 * Data class representing Google OAuth configuration.
 *
 * @property clientId The Google OAuth client ID.
 */
data class GoogleOAuthConfig(
    val clientId: String,
) {
    companion object {
        fun fromConfig(config: Config): GoogleOAuthConfig {
            return GoogleOAuthConfig(
                clientId = config.getString("clientId"),
            )
        }
    }
}
