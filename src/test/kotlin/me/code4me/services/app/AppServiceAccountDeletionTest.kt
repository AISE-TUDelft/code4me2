package me.code4me.services.app

import me.code4me.services.state.AuthSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AppServiceAccountDeletionTest {
    @Test
    fun `new login during deletion preserves its session`() {
        for (replacement in listOf("new-token", "old-token")) {
            var persistedToken: String? = null
            val authState = AuthSettings(null, null, "old-token") { persistedToken = it }
            val generation = authState.tokenGeneration()
            var cookiesCleared = false

            val cleared = deleteAccountAndClearLocalSession(
                authState,
                generation,
                { authState.setToken(replacement) },
                { cookiesCleared = true },
            )

            assertFalse(cleared)
            assertFalse(cookiesCleared)
            assertEquals(replacement, authState.getToken())
            assertEquals(replacement, persistedToken)
        }
    }

    @Test
    fun `stale UI deletion does not send a request`() {
        val authState = AuthSettings(null, null, "old-token") { }
        val generation = authState.tokenGeneration()
        authState.setToken("new-token")
        var requestSent = false
        var localDataCleared = false

        assertThrows(IllegalStateException::class.java) {
            deleteAccountAndClearLocalSession(
                authState,
                generation,
                { requestSent = true },
                { },
                { localDataCleared = true },
            )
        }
        assertFalse(requestSent)
        assertFalse(localDataCleared)
        assertEquals("new-token", authState.getToken())
    }

    @Test
    fun `concurrent login cannot change the token sent by deletion`() {
        val authState = AuthSettings(null, null, "old-token") { }
        val generation = authState.tokenGeneration()
        val loginAttempted = CountDownLatch(1)
        val loginFinished = CountDownLatch(1)
        val observedToken = AtomicReference<String?>()
        lateinit var loginThread: Thread

        val cleared = deleteAccountAndClearLocalSession(
            authState,
            generation,
            {
                loginThread = Thread {
                    loginAttempted.countDown()
                    authState.setToken("new-token")
                    loginFinished.countDown()
                }.also { it.start() }
                assertTrue(loginAttempted.await(2, TimeUnit.SECONDS))
                assertTrue(waitUntilBlocked(loginThread))
                observedToken.set(authState.getToken())
            },
            { },
            { },
        )

        loginThread.join(2_000)
        assertTrue(cleared)
        assertTrue(loginFinished.await(2, TimeUnit.SECONDS))
        assertEquals("old-token", observedToken.get())
        assertEquals("new-token", authState.getToken())
    }

    private fun waitUntilBlocked(thread: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (thread.state == Thread.State.BLOCKED) return true
            Thread.yield()
        }
        return false
    }
}
