package me.code4me.research.session

import me.code4me.research.bootstrap.BootstrapTransport
import me.code4me.research.bootstrap.BootstrapTransportResult
import me.code4me.research.bootstrap.VALID_NOW
import me.code4me.research.bootstrap.compatibility
import me.code4me.research.bootstrap.manifestJson
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ResearchSessionManagerLoginRaceTest {
    @Test
    fun `stop during bootstrap prevents late activation from restarting resources`() {
        val bootstrapEntered = CountDownLatch(1)
        val releaseBootstrap = CountDownLatch(1)
        val transport =
            BootstrapTransport { _, _ ->
                bootstrapEntered.countDown()
                assertTrue(releaseBootstrap.await(5, TimeUnit.SECONDS), "test bootstrap was not released")
                BootstrapTransportResult.Success(manifestJson())
            }
        val manager =
            ResearchSessionManager(
                projectKey = "login-race-project",
                transport = transport,
                compatibility = compatibility(),
                clock = { VALID_NOW.toEpochMilli() },
                instantClock = { VALID_NOW },
            )
        val activation = AtomicReference<ResearchActivationResult>()
        val activationThread = Thread { activation.set(manager.activate("enrollment-1")) }

        activationThread.start()
        assertTrue(bootstrapEntered.await(5, TimeUnit.SECONDS), "activation did not enter bootstrap")
        manager.stop()
        releaseBootstrap.countDown()
        activationThread.join(5_000L)

        assertFalse(activationThread.isAlive, "activation did not finish")
        assertTrue(activation.get() is ResearchActivationResult.Blocked)
        assertFalse(manager.isActive)
        assertFalse(manager.isCollecting)
    }
}
