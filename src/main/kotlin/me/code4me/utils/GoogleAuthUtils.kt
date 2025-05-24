package me.code4me.utils

import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl
import com.google.api.client.auth.oauth2.AuthorizationCodeTokenRequest
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.auth.oauth2.TokenResponse
import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import me.code4me.services.config.getConfig
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections

/**
 * Utility class for handling Google OAuth authentication.
 */
object GoogleAuthUtils {
    private val JSON_FACTORY: JsonFactory = GsonFactory.getDefaultInstance()
    private val SCOPES = Collections.singletonList("https://www.googleapis.com/auth/userinfo.email")
    private const val TOKENS_DIRECTORY_PATH = "tokens"
    private const val AUTH_URI = "https://accounts.google.com/o/oauth2/auth"
    private const val TOKEN_URI = "https://oauth2.googleapis.com/token"
    private const val REDIRECT_URI = "http://localhost:8899/Callback"
    private const val CODE_VERIFIER_LENGTH = 128
    private const val CODE_CHALLENGE_METHOD = "S256"

    /**
     * Creates an authorized Credential object.
     *
     * @return An authorized Credential object.
     * @throws java.io.IOException If the credentials.json file cannot be found.
     */
    @Throws(IOException::class)
    fun getCredentials(): Credential {
        // Get the client ID from the configuration
        val configService = getConfig()
        val googleOAuthConfig =
            configService.getGoogleOAuthConfig()
                ?: throw IOException("Google OAuth configuration not found")

        val clientId = googleOAuthConfig.clientId

        // Use the startAuthFlow method to get the credentials
        return startAuthFlow(clientId)
    }

    /**
     * Starts the Google OAuth flow with PKCE and returns the credentials.
     * This method handles the entire OAuth flow, including opening the browser
     * and waiting for the user to complete the authentication.
     *
     * @param clientId The Google OAuth client ID.
     * @return The authorized Credential object.
     * @throws IOException If there's an error during the OAuth flow.
     */
    @Throws(IOException::class)
    fun startAuthFlow(clientId: String): Credential {
        val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
        val receiver =
            LocalServerReceiver.Builder()
                .setPort(8899)
                .setCallbackPath("Callback")
                .build()

        // Generate code verifier and challenge for PKCE
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)

        // Use custom PKCE-enabled authorization flow
        return PKCEAuthorizationCodeInstalledApp(
            httpTransport,
            JSON_FACTORY,
            clientId,
            codeVerifier,
            codeChallenge,
            receiver,
        ).authorize("user")
    }

    /**
     * Generates a random code verifier for PKCE.
     *
     * @return A random string to be used as the code verifier.
     */
    private fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val bytes = ByteArray(CODE_VERIFIER_LENGTH)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Generates a code challenge from the code verifier using SHA-256 hashing.
     *
     * @param codeVerifier The code verifier to generate the challenge from.
     * @return The code challenge string.
     */
    private fun generateCodeChallenge(codeVerifier: String): String {
        val bytes = codeVerifier.toByteArray(Charsets.US_ASCII)
        val messageDigest = MessageDigest.getInstance("SHA-256")
        messageDigest.update(bytes, 0, bytes.size)
        val digest = messageDigest.digest()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Custom implementation of AuthorizationCodeInstalledApp that supports PKCE.
     */
    private class PKCEAuthorizationCodeInstalledApp(
        private val httpTransport: HttpTransport,
        private val jsonFactory: JsonFactory,
        private val clientId: String,
        private val codeVerifier: String,
        private val codeChallenge: String,
        private val receiver: VerificationCodeReceiver,
    ) {
        /**
         * Authorizes the installed application to access user's protected data.
         *
         * @param userId User ID or {@code null} if not using a persisted credential store
         * @return Credential containing the access and refresh tokens
         */
        @Throws(IOException::class)
        fun authorize(userId: String): Credential {
            try {
                // Open browser with authorization URL that includes the code challenge
                val authorizationUrl =
                    AuthorizationCodeRequestUrl(AUTH_URI, clientId)
                        .setRedirectUri(REDIRECT_URI)
                        .setScopes(SCOPES)
                        .set("code_challenge", codeChallenge)
                        .set("code_challenge_method", CODE_CHALLENGE_METHOD)
                        .set("access_type", "offline")

                // Open browser
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI(authorizationUrl.build()))
                } else {
                    println("Please open the following URL in your browser:")
                    println(authorizationUrl.build())
                }

                // Receive authorization code
                println("Waiting for authorization code...")
                val code = receiver.waitForCode()

                // Request access token using the authorization code and code verifier
                val tokenRequest =
                    AuthorizationCodeTokenRequest(
                        httpTransport,
                        jsonFactory,
                        GenericUrl(TOKEN_URI),
                        code,
                    )
                tokenRequest.redirectUri = REDIRECT_URI
                tokenRequest.set("client_id", clientId)
                tokenRequest.set("code_verifier", codeVerifier)
                val tokenResponse = tokenRequest.execute()

                // Create and return credential
                return createCredentialWithAccessTokenOnly(
                    httpTransport,
                    jsonFactory,
                    tokenResponse,
                    userId,
                )
            } finally {
                receiver.stop()
            }
        }

        /**
         * Creates a credential with just the access token, since we don't have a client secret.
         */
        private fun createCredentialWithAccessTokenOnly(
            transport: HttpTransport,
            jsonFactory: JsonFactory,
            tokenResponse: TokenResponse,
            userId: String,
        ): Credential {
            // Create a credential with the access token
            val credential =
                GoogleCredential.Builder()
                    .setTransport(transport)
                    .setJsonFactory(jsonFactory)
                    .build()

            // Set the tokens
            credential.accessToken = tokenResponse.accessToken
            credential.refreshToken = tokenResponse.refreshToken
            credential.expiresInSeconds = tokenResponse.expiresInSeconds

            return credential
        }
    }
}
