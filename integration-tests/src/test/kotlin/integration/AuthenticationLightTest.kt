package integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
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
import me.code4me.components.settings.sections.AuthenticationSection
import me.code4me.api.wrapper.CookieAwareApiClient
import org.mockito.kotlin.*
import java.io.IOException
import java.net.HttpCookie

class AuthenticationLightTest : BasePlatformTestCase() {

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
}
