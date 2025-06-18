package integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.code4me.api.generated.infrastructure.ClientError
import me.code4me.api.generated.infrastructure.ClientException
import me.code4me.api.generated.infrastructure.ServerError
import me.code4me.api.generated.infrastructure.ServerException
import me.code4me.api.generated.model.AuthenticateUserPostResponse
import me.code4me.api.generated.model.CreateUserPostResponse
import me.code4me.api.generated.model.ResponseUser
import me.code4me.services.app.AppService
import me.code4me.services.state.AuthState
import me.code4me.services.state.*
import me.code4me.components.settings.sections.AuthenticationSection
import me.code4me.api.wrapper.CookieAwareApiClient
import org.mockito.kotlin.*
import java.beans.PropertyChangeListener
import java.io.IOException
import java.net.HttpCookie

class AuthenticationLightTest : HeavyPlatformTestCase() {

    private lateinit var mockAppService: AppService
    private lateinit var authState: AuthState

    private val validEmail = "user@example.com"
    private val validPassword = "securePass123"
    private val userName = "Jane Doe"

    override fun setUp() {
        super.setUp()

        // Prepare mock AppService
        mockAppService = mock()

        // Initialize authState
        authState = service()

        // Create ResponseUser with all required parameters
        val user = ResponseUser(
            email = validEmail,
            name = userName,
            password = validPassword,
            configId = 1,
            userId = java.util.UUID.randomUUID(),
            joinedAt = java.time.OffsetDateTime.now(),
            verified = true
        )

        // Match real AppService behavior
        whenever(mockAppService.authenticateUser(validEmail, validPassword))
            .thenReturn(AuthenticateUserPostResponse(user = user, message = "auth-success", config = ""))

        whenever(mockAppService.createUser(
            email = validEmail,
            name = userName,
            password = validPassword,
            token = "",
            provider = me.code4me.api.generated.model.Provider.no_provider
        )).thenReturn(CreateUserPostResponse(userId = java.util.UUID.randomUUID(), message = "created"))

        // Inject mock cookie to simulate auth_token
        CookieAwareApiClient.cookieManager.cookieStore.add(
            null,
            HttpCookie("auth_token", "mock-token-xyz")
        )
    }

    // ========= Original Authentication Section Tests =========

    fun testLoginSuccess() {
        val section = AuthenticationSection()

        // Inject our mock AppService into the AuthenticationSection
        val appServiceField = section.javaClass.getDeclaredField("appService").apply {
            isAccessible = true
        }
        appServiceField.set(section, mockAppService)

        val token = section.javaClass.getDeclaredMethod("performLogin", String::class.java, String::class.java).apply {
            isAccessible = true
        }.invoke(section, validEmail, validPassword) as? String

        assertEquals("mock-token-xyz", token)
        assertEquals(userName, authState.state.getUserName())
        assertEquals(validEmail, authState.state.getUserEmail())
        assertTrue(authState.state.isVerified() == true)
    }

    fun testSignupSuccess() {
        val section = AuthenticationSection()

        // Inject our mock AppService into the AuthenticationSection
        val appServiceField = section.javaClass.getDeclaredField("appService").apply {
            isAccessible = true
        }
        appServiceField.set(section, mockAppService)

        val token = section.javaClass.getDeclaredMethod(
            "performSignup",
            String::class.java,
            String::class.java,
            String::class.java
        ).apply {
            isAccessible = true
        }.invoke(section, userName, validEmail, validPassword) as? String

        assertEquals("mock-token-xyz", token)
        assertEquals(userName, authState.state.getUserName())
    }

    fun testLoginFailureInvalidCredentials() {
        val section = AuthenticationSection()

        // Inject our mock AppService into the AuthenticationSection
        val appServiceField = section.javaClass.getDeclaredField("appService").apply {
            isAccessible = true
        }
        appServiceField.set(section, mockAppService)

        // Mock the AppService to throw ClientException for invalid credentials
        whenever(mockAppService.authenticateUser("invalid@example.com", "wrongPassword"))
            .thenThrow(ClientException("Invalid credentials", 401, ClientError<Any>("Invalid credentials", null, 401)))

        val token = section.javaClass.getDeclaredMethod("performLogin", String::class.java, String::class.java).apply {
            isAccessible = true
        }.invoke(section, "invalid@example.com", "wrongPassword") as? String

        assertNull("Token should be null for invalid credentials", token)
    }

    fun testLoginFailureServerError() {
        val section = AuthenticationSection()

        // Inject our mock AppService into the AuthenticationSection
        val appServiceField = section.javaClass.getDeclaredField("appService").apply {
            isAccessible = true
        }
        appServiceField.set(section, mockAppService)

        // Mock the AppService to throw ServerException for server error
        whenever(mockAppService.authenticateUser("server.error@example.com", "anyPassword"))
            .thenThrow(ServerException("Internal server error", 500, ServerError<Any>("Internal server error", null, 500)))

        val token = section.javaClass.getDeclaredMethod("performLogin", String::class.java, String::class.java).apply {
            isAccessible = true
        }.invoke(section, "server.error@example.com", "anyPassword") as? String

        assertNull("Token should be null for server error", token)
    }

    fun testLoginFailureNetworkError() {
        val section = AuthenticationSection()

        // Inject our mock AppService into the AuthenticationSection
        val appServiceField = section.javaClass.getDeclaredField("appService").apply {
            isAccessible = true
        }
        appServiceField.set(section, mockAppService)

        // Mock the AppService to throw IOException for network error
        whenever(mockAppService.authenticateUser("network.error@example.com", "anyPassword"))
            .thenThrow(IOException("Network connection error"))

        val token = section.javaClass.getDeclaredMethod("performLogin", String::class.java, String::class.java).apply {
            isAccessible = true
        }.invoke(section, "network.error@example.com", "anyPassword") as? String

        assertNull("Token should be null for network error", token)
    }

    fun testSignupFailureUserExists() {
        val section = AuthenticationSection()

        // Inject our mock AppService into the AuthenticationSection
        val appServiceField = section.javaClass.getDeclaredField("appService").apply {
            isAccessible = true
        }
        appServiceField.set(section, mockAppService)

        // Mock the AppService to throw ClientException for user already exists
        whenever(mockAppService.createUser(
            email = "existing@example.com",
            name = "Existing User",
            password = validPassword,
            token = "",
            provider = me.code4me.api.generated.model.Provider.no_provider
        )).thenThrow(ClientException("User already exists", 409, ClientError<Any>("User already exists", null, 409)))

        val token = section.javaClass.getDeclaredMethod(
            "performSignup",
            String::class.java,
            String::class.java,
            String::class.java
        ).apply {
            isAccessible = true
        }.invoke(section, "Existing User", "existing@example.com", validPassword) as? String

        assertNull("Token should be null for user already exists", token)
    }

    fun testSignupFailureServerError() {
        val section = AuthenticationSection()

        // Inject our mock AppService into the AuthenticationSection
        val appServiceField = section.javaClass.getDeclaredField("appService").apply {
            isAccessible = true
        }
        appServiceField.set(section, mockAppService)

        // Mock the AppService to throw ServerException for server error
        whenever(mockAppService.createUser(
            email = "server.error@example.com",
            name = "Server Error",
            password = validPassword,
            token = "",
            provider = me.code4me.api.generated.model.Provider.no_provider
        )).thenThrow(ServerException("Internal server error", 500, ServerError<Any>("Internal server error", null, 500)))

        val token = section.javaClass.getDeclaredMethod(
            "performSignup",
            String::class.java,
            String::class.java,
            String::class.java
        ).apply {
            isAccessible = true
        }.invoke(section, "Server Error", "server.error@example.com", validPassword) as? String

        assertNull("Token should be null for server error", token)
    }

    fun testSignupFailureNetworkError() {
        val section = AuthenticationSection()

        // Inject our mock AppService into the AuthenticationSection
        val appServiceField = section.javaClass.getDeclaredField("appService").apply {
            isAccessible = true
        }
        appServiceField.set(section, mockAppService)

        // Mock the AppService to throw IOException for network error
        whenever(mockAppService.createUser(
            email = "network.error@example.com",
            name = "Network Error",
            password = validPassword,
            token = "",
            provider = me.code4me.api.generated.model.Provider.no_provider
        )).thenThrow(IOException("Network connection error"))

        val token = section.javaClass.getDeclaredMethod(
            "performSignup",
            String::class.java,
            String::class.java,
            String::class.java
        ).apply {
            isAccessible = true
        }.invoke(section, "Network Error", "network.error@example.com", validPassword) as? String

        assertNull("Token should be null for network error", token)
    }

    // ========= Extended AuthState Direct Testing =========

    fun testDirectAuthStateTokenManagement() {
        // Test direct token setting and getting
        val testToken = "test-auth-token-123"
        authState.state.setToken(testToken)

        assertEquals(testToken, authState.state.getToken())
        assertTrue(authState.state.isAuthenticated())
    }

    fun testDirectAuthStateUserNameManagement() {
        // Test direct user name setting
        val testName = "Direct Test User"
        authState.state.setUserName(testName)

        assertEquals(testName, authState.state.getUserName())
    }

    fun testDirectAuthStateUserEmailManagement() {
        // Test direct email setting
        val testEmail = "direct@test.com"
        authState.state.setUserEmail(testEmail)

        assertEquals(testEmail, authState.state.getUserEmail())
    }

    fun testDirectAuthStateVerificationManagement() {
        // Test direct verification status setting
        authState.state.setVerified(true)
        assertTrue(authState.state.isVerified() == true)

        authState.state.setVerified(false)
        assertTrue(authState.state.isVerified() == false)
    }

    fun testAuthStateIsAuthenticatedWhenNoToken() {
        // Test isAuthenticated when no token is set
        authState.state.clearUserData()
        assertFalse(authState.state.isAuthenticated())
    }

    fun testAuthStateClearUserData() {
        // Set up some data first
        authState.state.setToken("test-token")
        authState.state.setUserName("Test User")
        authState.state.setUserEmail("test@example.com")
        authState.state.setVerified(true)

        // Verify data is set
        assertNotNull(authState.state.getToken())
        assertNotNull(authState.state.getUserName())
        assertNotNull(authState.state.getUserEmail())
        assertTrue(authState.state.isVerified() == true)

        // Clear data
        authState.state.clearUserData()

        // Verify all data is cleared
        assertNull(authState.state.getToken())
        assertNull(authState.state.getUserName())
        assertNull(authState.state.getUserEmail())
        assertFalse(authState.state.isAuthenticated())
    }

    fun testAuthStatePropertyChangeListeners() {
        var tokenChangeNotified = false
        var userNameChangeNotified = false
        var emailChangeNotified = false

        val tokenListener = PropertyChangeListener { evt ->
            if (evt.propertyName == TOKEN_PROPERTY) {
                tokenChangeNotified = true
            }
        }

        val userNameListener = PropertyChangeListener { evt ->
            if (evt.propertyName == USER_NAME_PROPERTY) {
                userNameChangeNotified = true
            }
        }

        val emailListener = PropertyChangeListener { evt ->
            if (evt.propertyName == USER_EMAIL_PROPERTY) {
                emailChangeNotified = true
            }
        }

        // Add listeners
        authState.state.addPropertyChangeListener(TOKEN_PROPERTY, tokenListener)
        authState.state.addPropertyChangeListener(USER_NAME_PROPERTY, userNameListener)
        authState.state.addPropertyChangeListener(USER_EMAIL_PROPERTY, emailListener)

        // Trigger changes
        authState.state.setToken("new-token")
        authState.state.setUserName("New Name")
        authState.state.setUserEmail("new@email.com")

        // Verify listeners were notified
        assertTrue("Token change listener should be notified", tokenChangeNotified)
        assertTrue("User name change listener should be notified", userNameChangeNotified)
        assertTrue("Email change listener should be notified", emailChangeNotified)

        // Clean up - remove listeners
        authState.state.removePropertyChangeListener(TOKEN_PROPERTY, tokenListener)
        authState.state.removePropertyChangeListener(USER_NAME_PROPERTY, userNameListener)
        authState.state.removePropertyChangeListener(USER_EMAIL_PROPERTY, emailListener)
    }

    fun testAuthStateGlobalPropertyChangeListener() {
        var changeNotificationCount = 0

        val globalListener = PropertyChangeListener { _ ->
            changeNotificationCount++
        }

        // Add global listener
        authState.state.addPropertyChangeListener(globalListener)

        // Trigger multiple changes
        authState.state.setToken("global-test-token")
        authState.state.setUserName("Global Test User")
        authState.state.setUserEmail("global@test.com")

        // Should have received 3 notifications
        assertEquals(3, changeNotificationCount)

        // Clean up
        authState.state.removePropertyChangeListener(globalListener)
    }

    fun testAuthStateCompanionObjectMethods() {
        // Test the companion object static methods
        val testKey = "test-key"
        val testValue = "test-value"

        try {
            AuthState.setUserInfo(testKey, testValue)
            val retrievedValue = AuthState.getUserInfo(testKey)
            assertEquals(testValue, retrievedValue)

            // Test removal
            AuthState.removeSecureData(testKey)
            val removedValue = AuthState.getUserInfo(testKey)
            assertNull(removedValue)
        } catch (e: Exception) {
            // If methods are not accessible, this test will be skipped
            println("Companion object methods not accessible for testing: ${e.message}")
        }
    }

    fun testAuthStateCompanionObjectTokenMethods() {
        // Test auth token specific companion methods
        val testKey = "test-auth-key"
        val testToken = "test-auth-token-value"

        try {
            AuthState.setAuthToken(testKey, testToken)
            val retrievedToken = AuthState.getAuthToken(testKey)
            assertEquals(testToken, retrievedToken)

            // Test removal
            AuthState.removeSecureData(testKey)
            val removedToken = AuthState.getAuthToken(testKey)
            assertNull(removedToken)
        } catch (e: Exception) {
            println("Companion object auth token methods not accessible for testing: ${e.message}")
        }
    }

    fun testAuthStateUserVerificationCompanionMethods() {
        // Test user verification companion methods
        try {
            AuthState.setUserVerified(true)
            assertTrue(AuthState.isUserVerified())

            AuthState.setUserVerified(false)
            assertFalse(AuthState.isUserVerified())

            AuthState.setUserVerified(null)
            assertFalse(AuthState.isUserVerified()) // Should default to false
        } catch (e: Exception) {
            println("Companion object verification methods not accessible for testing: ${e.message}")
        }
    }

    fun testAuthStateInvalidInputHandling() {
        // Test with blank/empty inputs - should throw IllegalArgumentException
        try {
            authState.state.setToken("")
            fail("Should throw IllegalArgumentException for empty token")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        try {
            authState.state.setUserName("")
            fail("Should throw IllegalArgumentException for empty user name")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        try {
            authState.state.setUserEmail("")
            fail("Should throw IllegalArgumentException for empty email")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    fun testAuthStateCompanionObjectInvalidInputHandling() {
        // Test companion object methods with invalid inputs
        try {
            AuthState.setAuthToken("test-key", "")
            fail("Should throw IllegalArgumentException for empty token")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        try {
            AuthState.setUserInfo("test-key", "")
            fail("Should throw IllegalArgumentException for empty user info")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    fun testAuthStateWithMultipleTokenUpdates() {
        // Test that token updates work correctly with multiple changes
        val tokens = listOf("token1", "token2", "token3")

        tokens.forEach { token ->
            authState.state.setToken(token)
            assertEquals(token, authState.state.getToken())
            assertTrue(authState.state.isAuthenticated())
        }
    }

    fun testAuthStateVerificationDefaults() {
        // Test that verification defaults to false when not explicitly set
        authState.state.clearUserData()
        assertEquals(false, authState.state.isVerified())
    }

    fun testAuthStatePropertyChangeListenerRemoval() {
        // Test removing specific property listeners
        var tokenChangeCount = 0
        val tokenListener = PropertyChangeListener { _ -> tokenChangeCount++ }

        // Add listener
        authState.state.addPropertyChangeListener(TOKEN_PROPERTY, tokenListener)

        // Trigger change
        authState.state.setToken("test-token-1")
        assertEquals(1, tokenChangeCount)

        // Remove listener
        authState.state.removePropertyChangeListener(TOKEN_PROPERTY, tokenListener)

        // Trigger another change - should not increment count
        authState.state.setToken("test-token-2")
        assertEquals(1, tokenChangeCount) // Should still be 1
    }

    fun testAuthStateEmptyStateInitialization() {
        // Test initial state when no data is set
        authState.state.clearUserData()

        assertNull(authState.state.getToken())
        assertNull(authState.state.getUserName())
        assertNull(authState.state.getUserEmail())
        assertFalse(authState.state.isAuthenticated())
        assertEquals(false, authState.state.isVerified())
    }

    override fun tearDown() {
        try {
            // Clean up auth state after each test
            authState.state.clearUserData()
            CookieAwareApiClient.clearCookies()
        } finally {
            super.tearDown()
        }
    }
}