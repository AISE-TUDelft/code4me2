package me.code4me.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CustomLocalServerReceiverTest {
    private lateinit var receiver: CustomLocalServerReceiver

    @Rule
    @JvmField
    val globalTimeout = Timeout.seconds(30) // 30 seconds timeout for all tests

    @Before
    fun setUp() {
        // Create a receiver with a random available port
        val availablePort = findAvailablePort()
        receiver =
            CustomLocalServerReceiver.Builder()
                .setPort(availablePort)
                .setHost("localhost")
                .setCallbackPath("/callback")
                .setTimeout(5000) // 5 seconds timeout for tests
                .build()
    }

    @After
    fun tearDown() {
        try {
            receiver.stop()
        } catch (e: Exception) {
            // Ignore exceptions during cleanup
        }
    }

    @Test
    fun testGetRedirectUri() {
        val uri = receiver.getRedirectUri()
        assertTrue(uri.startsWith("http://localhost:"))
        assertTrue(uri.endsWith("/callback"))
    }

    @Test
    fun testWaitForCodeSuccess() {
        // Start a thread to send a request with a code
        val codeValue = "test_auth_code"
        val latch = CountDownLatch(1)
        val exception = AtomicReference<Exception?>(null)

        Thread {
            try {
                // Give the server time to start
                Thread.sleep(500)

                // Get the redirect URI and send a request with a code
                val redirectUri = receiver.getRedirectUri()
                val url = URL("$redirectUri?code=$codeValue")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                // Read the response
                val responseCode = connection.responseCode
                assertEquals(200, responseCode)

                connection.disconnect()
            } catch (e: Exception) {
                exception.set(e)
            } finally {
                latch.countDown()
            }
        }.start()

        // Wait for the code
        val code = receiver.waitForCode()

        // Wait for the request thread to complete
        latch.await(5, TimeUnit.SECONDS)

        // Check for exceptions in the request thread
        assertNull("Exception in request thread: ${exception.get()}", exception.get())

        // Verify the code
        assertEquals(codeValue, code)
    }

    @Test
    fun testWaitForCodeTimeout() {
        // Create a receiver with a very short timeout
        val shortTimeoutReceiver =
            CustomLocalServerReceiver.Builder()
                .setPort(findAvailablePort())
                .setHost("localhost")
                .setCallbackPath("/callback")
                .setTimeout(100) // 100ms timeout
                .build()

        try {
            try {
                // This should timeout and throw an IOException
                shortTimeoutReceiver.waitForCode()
                fail("Expected IOException to be thrown")
            } catch (e: IOException) {
                // Expected exception
            }
        } finally {
            shortTimeoutReceiver.stop()
        }
    }

    @Test
    fun testServerCreation() {
        // This test verifies that the receiver can be created with custom parameters
        val port = findAvailablePort()
        val host = "localhost"
        val path = "/custom-callback"
        val timeout = 2000

        val receiver =
            CustomLocalServerReceiver.Builder()
                .setPort(port)
                .setHost(host)
                .setCallbackPath(path)
                .setTimeout(timeout)
                .build()

        // Verify the redirect URI is constructed correctly
        val redirectUri = receiver.getRedirectUri()
        assertEquals("http://$host:$port$path", redirectUri)
    }

    /**
     * Finds an available port by creating a ServerSocket with port 0 (which assigns a random available port)
     * and then closing it.
     */
    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }
}
