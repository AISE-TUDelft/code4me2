package me.code4me.services.app

import com.sun.net.httpserver.HttpServer
import me.code4me.services.state.AuthSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AppServiceDataErasureTest {
    @Test
    fun `confirmed erase sends the captured token, parses the counts and cleans up without signing out`() {
        val authState = AuthSettings(null, null, "token") { }
        var sentToken: String? = null
        var localDataCleared = false

        val erased =
            eraseCollectedDataAndClearLocalData(
                authState,
                authState.tokenGeneration(),
                { authToken ->
                    sentToken = authToken
                    RawHttpResponse(200, ERASE_RESPONSE)
                },
                listOf({ localDataCleared = true }),
            )

        assertEquals("token", sentToken)
        assertEquals(
            ErasedDataCounts(queries = 12, chats = 3, agentRuns = 2, studyEnrollments = 1, studyEvents = 40),
            erased,
        )
        assertTrue(localDataCleared)
        assertEquals("token", authState.getToken())
    }

    @Test
    fun `confirmed erase without reported counts still runs the cleanup`() {
        for (body in listOf("", "{}", """{"erased":null}""", "not json")) {
            val authState = AuthSettings(null, null, "token") { }
            var localDataCleared = false

            val erased =
                eraseCollectedDataAndClearLocalData(
                    authState,
                    authState.tokenGeneration(),
                    { RawHttpResponse(200, body) },
                    listOf({ localDataCleared = true }),
                )

            assertNull(erased, "body: $body")
            assertTrue(localDataCleared, "body: $body")
        }
    }

    @Test
    fun `stale UI erase does not send a request`() {
        val authState = AuthSettings(null, null, "old-token") { }
        val generation = authState.tokenGeneration()
        authState.setToken("new-token")
        var requestSent = false
        var localDataCleared = false

        assertThrows(AuthenticationChangedException::class.java) {
            eraseCollectedDataAndClearLocalData(
                authState,
                generation,
                {
                    requestSent = true
                    RawHttpResponse(200, ERASE_RESPONSE)
                },
                listOf({ localDataCleared = true }),
            )
        }
        assertFalse(requestSent)
        assertFalse(localDataCleared)
        assertEquals("new-token", authState.getToken())
    }

    @Test
    fun `an erase without a signed-in account sends nothing and reports not signed in`() {
        val authState = AuthSettings(null, null, null) { }
        var requestSent = false
        var localDataCleared = false

        val error =
            assertThrows(DataErasureException::class.java) {
                eraseCollectedDataAndClearLocalData(
                    authState,
                    authState.tokenGeneration(),
                    {
                        requestSent = true
                        RawHttpResponse(200, ERASE_RESPONSE)
                    },
                    listOf({ localDataCleared = true }),
                )
            }

        assertEquals(401, error.statusCode)
        assertFalse(requestSent)
        assertFalse(localDataCleared)
    }

    @Test
    fun `refused erase throws a typed error and cleans up nothing`() {
        val refusals =
            listOf(
                RawHttpResponse(401, """{"detail":"Authentication required"}""") to "Authentication required",
                RawHttpResponse(500, """{"detail":{"code":"ERASE_FAILED","message":"Erase failed"}}""") to "Erase failed",
                RawHttpResponse(502, "Bad Gateway") to null,
            )
        for ((refusal, serverMessage) in refusals) {
            val authState = AuthSettings(null, null, "token") { }
            var localDataCleared = false

            val error =
                assertThrows(DataErasureException::class.java) {
                    eraseCollectedDataAndClearLocalData(
                        authState,
                        authState.tokenGeneration(),
                        { refusal },
                        listOf({ localDataCleared = true }),
                    )
                }

            assertEquals(refusal.code, error.statusCode)
            assertEquals(serverMessage, error.serverMessage)
            assertFalse(localDataCleared, "HTTP ${refusal.code}")
            assertEquals("token", authState.getToken())
        }
    }

    @Test
    fun `network failure propagates and cleans up nothing`() {
        val authState = AuthSettings(null, null, "token") { }
        var localDataCleared = false

        assertThrows(IOException::class.java) {
            eraseCollectedDataAndClearLocalData(
                authState,
                authState.tokenGeneration(),
                { throw IOException("connection refused") },
                listOf({ localDataCleared = true }),
            )
        }
        assertFalse(localDataCleared)
        assertEquals("token", authState.getToken())
    }

    @Test
    fun `the token lock is free while the erase request is in flight`() {
        val authState = AuthSettings(null, null, "token") { }
        var lockFreeDuringRequest = false

        eraseCollectedDataAndClearLocalData(
            authState,
            authState.tokenGeneration(),
            {
                // EDT code such as sign-out takes the same lock; it must not wait for the request.
                val lockTaken = AtomicBoolean(false)
                val other = Thread { authState.withTokenLock { lockTaken.set(true) } }
                other.start()
                other.join(2_000)
                lockFreeDuringRequest = lockTaken.get()
                RawHttpResponse(200, ERASE_RESPONSE)
            },
            emptyList(),
        )

        assertTrue(lockFreeDuringRequest)
    }

    @Test
    fun `a sign-in during the request cannot redirect it and skips the cleanup`() {
        var persistedToken: String? = null
        val authState = AuthSettings(null, null, "old-token") { persistedToken = it }
        var sentToken: String? = null
        var loginFinishedDuringRequest = false
        var localDataCleared = false

        val erased =
            eraseCollectedDataAndClearLocalData(
                authState,
                authState.tokenGeneration(),
                { authToken ->
                    val login = Thread { authState.setToken("new-token") }
                    login.start()
                    login.join(2_000)
                    loginFinishedDuringRequest = !login.isAlive
                    sentToken = authToken
                    RawHttpResponse(200, ERASE_RESPONSE)
                },
                listOf({ localDataCleared = true }),
            )

        assertTrue(loginFinishedDuringRequest)
        assertEquals("old-token", sentToken)
        assertEquals(12L, erased?.queries)
        assertFalse(localDataCleared)
        assertEquals("new-token", authState.getToken())
        assertEquals("new-token", persistedToken)
    }

    @Test
    fun `a sign-in change during the cleanup skips every later step`() {
        val authState = AuthSettings(null, null, "old-token") { }
        val stepsRun = mutableListOf<String>()

        eraseCollectedDataAndClearLocalData(
            authState,
            authState.tokenGeneration(),
            { RawHttpResponse(200, ERASE_RESPONSE) },
            listOf(
                { stepsRun += "preferences" },
                {
                    stepsRun += "research"
                    authState.setToken("new-token")
                },
                { stepsRun += "chats" },
                { stepsRun += "preference sync" },
            ),
        )

        assertEquals(listOf("preferences", "research"), stepsRun)
    }

    @Test
    fun `a failing cleanup step is reported and the remaining steps still run`() {
        val authState = AuthSettings(null, null, "token") { }
        val failures = mutableListOf<Throwable>()
        val stepsRun = mutableListOf<String>()

        val erased =
            eraseCollectedDataAndClearLocalData(
                authState,
                authState.tokenGeneration(),
                { RawHttpResponse(200, ERASE_RESPONSE) },
                listOf(
                    { throw IllegalStateException("research teardown failed") },
                    { stepsRun += "preference sync" },
                ),
            ) { failures += it }

        assertEquals(12L, erased?.queries)
        assertEquals(listOf("preference sync"), stepsRun)
        assertEquals(listOf("research teardown failed"), failures.map { it.message })
    }

    @Test
    fun `the erase request carries exactly one cookie, the captured auth token`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val method = AtomicReference<String?>()
        val cookies = AtomicReference<List<String>?>()
        server.createContext("/api/user/privacy/erase") { exchange ->
            method.set(exchange.requestMethod)
            cookies.set(exchange.requestHeaders["Cookie"])
            val body = ERASE_RESPONSE.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val response = sendDataErase("http://127.0.0.1:${server.address.port}", "captured-token")

            assertEquals(200, response.code)
            assertEquals(ERASE_RESPONSE, response.body)
            assertEquals("POST", method.get())
            assertEquals(listOf("auth_token=captured-token"), cookies.get())
        } finally {
            server.stop(0)
        }
        // No interceptor may add the live token, and a large erase gets a long read timeout.
        assertTrue(dataEraseHttpClient.interceptors.isEmpty())
        assertEquals(60_000, dataEraseHttpClient.readTimeoutMillis)
    }

    @Test
    fun `local cleanup runs after the token lock is released`() {
        val authState = AuthSettings(null, null, "token") { }
        var lockFreeDuringCleanup = false

        eraseCollectedDataAndClearLocalData(
            authState,
            authState.tokenGeneration(),
            { RawHttpResponse(200, ERASE_RESPONSE) },
            listOf(
                {
                    val lockTaken = AtomicBoolean(false)
                    val other = Thread { authState.withTokenLock { lockTaken.set(true) } }
                    other.start()
                    other.join(2_000)
                    lockFreeDuringCleanup = lockTaken.get()
                },
            ),
        )

        assertTrue(lockFreeDuringCleanup)
    }

    private companion object {
        const val ERASE_RESPONSE =
            """{"erased":{"queries":12,"chats":3,"agent_runs":2,"study_enrollments":1,"study_events":40},""" +
                """"status":{"data_collection":{"enabled":false,"opted_out_at":"2026-09-27T10:00:00Z"}}}"""
    }
}
