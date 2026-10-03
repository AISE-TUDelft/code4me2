package me.code4me.research.session

import me.code4me.research.bootstrap.BootstrapTransport
import me.code4me.research.bootstrap.BootstrapTransportResult
import me.code4me.research.bootstrap.BootstrapRejection
import me.code4me.research.bootstrap.VALID_NOW
import me.code4me.research.bootstrap.compatibility
import me.code4me.research.bootstrap.manifestJson
import me.code4me.research.proxy.PackagedAgentInstaller
import me.code4me.research.proxy.PackagedAgentInstall
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.parseCanonicalJson
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ResearchSessionManagerLoginRaceTest {
    @Test
    fun `changed assignment after preparation never executes the previously selected agent`() {
        val document = parseCanonicalJson(manifestJson()) as Map<*, *>
        val assignment = (document["assignment"] as Map<*, *>).mapKeys { it.key.toString() } + ("assignment_id" to "old-assignment")
        val metadata = canonicalJson(document.filterKeys { it in listOf("enrollment_id", "study_id", "agent_release") } + ("assignment" to assignment))
        var validations = 0
        val manager = ResearchSessionManager(
            projectKey = "changed-assignment", compatibility = compatibility(),
            transport = object : BootstrapTransport {
                override fun prepare(enrollmentId: String, contextId: String) = BootstrapTransportResult.Success(metadata)
                override fun fetch(enrollmentId: String, contextId: String) = BootstrapTransportResult.Success(manifestJson())
            },
            packagedAgentInstaller = object : PackagedAgentInstaller {
                override fun install(pin: String) = PackagedAgentInstall.Ready(listOf("/agent", "--managed"), "a".repeat(64))
                override fun validate(ready: PackagedAgentInstall.Ready): String? { validations++; return null }
            },
            clock = { VALID_NOW.toEpochMilli() }, instantClock = { VALID_NOW },
        )
        assertTrue(manager.activate("enrollment-1") is ResearchActivationResult.Retryable)
        assertTrue(validations == 0 && !manager.isActive)
        manager.stop()
    }

    @Test
    fun `account change during preparation prevents session creation and native execution`() {
        var current = true
        val document = parseCanonicalJson(manifestJson()) as Map<*, *>
        val metadata = canonicalJson(document.filterKeys { it in listOf("enrollment_id", "study_id", "assignment", "agent_release") })
        val manager = ResearchSessionManager(
            projectKey = "account-changed", compatibility = compatibility(),
            transport = object : BootstrapTransport {
                override fun prepare(enrollmentId: String, contextId: String) = BootstrapTransportResult.Success(metadata)
                override fun fetch(enrollmentId: String, contextId: String): BootstrapTransportResult = error("stale account must not bootstrap")
            },
            packagedAgentInstaller = PackagedAgentInstaller {
                current = false
                PackagedAgentInstall.Ready(listOf("/agent", "--managed"), "a".repeat(64))
            },
        )
        assertTrue(manager.activate("enrollment-1", isCurrent = { current }) is ResearchActivationResult.Blocked)
        assertFalse(manager.isActive)
        manager.stop()
    }

    @Test
    fun `preparation refuses missing consent without describing enrollment as revoked`() {
        val manager = ResearchSessionManager(
            projectKey = "missing-consent", compatibility = compatibility(),
            transport = object : BootstrapTransport {
                override fun prepare(enrollmentId: String, contextId: String) = BootstrapTransportResult.Revoked(
                    "Accept the study consent first.", BootstrapRejection.fromCode("CONSENT_REQUIRED"),
                )
                override fun fetch(enrollmentId: String, contextId: String): BootstrapTransportResult = error("must not open a session")
            },
            packagedAgentInstaller = PackagedAgentInstaller { error("must not install before consent") },
        )
        val result = manager.activate("enrollment-1") as ResearchActivationResult.Blocked
        assertTrue(result.reason.value == "CONSENT_REQUIRED")
        assertTrue(manager.state().blockReason?.value == "CONSENT_REQUIRED")
    }

    @Test
    fun `cancelled preparation stays cancelled until explicit retry`() {
        var installs = 0
        var bootstraps = 0
        val document = parseCanonicalJson(manifestJson()) as Map<*, *>
        val metadata = canonicalJson(document.filterKeys { it in listOf("enrollment_id", "study_id", "assignment", "agent_release") })
        val manager = ResearchSessionManager(
            projectKey = "cancelled-preparation", compatibility = compatibility(),
            transport = object : BootstrapTransport {
                override fun prepare(enrollmentId: String, contextId: String) = BootstrapTransportResult.Success(metadata)
                override fun fetch(enrollmentId: String, contextId: String): BootstrapTransportResult {
                    bootstraps++
                    return BootstrapTransportResult.Success(manifestJson())
                }
            },
            packagedAgentInstaller = PackagedAgentInstaller {
                installs++
                PackagedAgentInstall.Ready(listOf("/agent", "--managed"), "a".repeat(64))
            },
            clock = { VALID_NOW.toEpochMilli() }, instantClock = { VALID_NOW },
        )
        manager.cancelPreparation()
        repeat(2) {
            val result = manager.activate("enrollment-1") as ResearchActivationResult.Blocked
            assertTrue(result.reason == StudyBlockReason.PREPARATION_CANCELLED)
        }
        assertTrue(installs == 0 && bootstraps == 0)
        manager.retryPreparation()
        assertTrue(manager.activate("enrollment-1") is ResearchActivationResult.Activated)
        assertTrue(installs == 1 && bootstraps == 1)
        manager.stop()
    }

    @Test
    fun `incompatible agent protocol is blocked before download or session creation`() {
        val document = parseCanonicalJson(manifestJson()) as Map<*, *>
        val release = (document["agent_release"] as Map<*, *>).mapKeys { it.key.toString() } + ("min_protocol_version" to "2")
        val metadata = canonicalJson(document.filterKeys { it in listOf("enrollment_id", "study_id", "assignment") } + ("agent_release" to release))
        var installs = 0
        var bootstraps = 0
        val manager = ResearchSessionManager(
            projectKey = "incompatible-preparation", compatibility = compatibility(),
            transport = object : BootstrapTransport {
                override fun prepare(enrollmentId: String, contextId: String) = BootstrapTransportResult.Success(metadata)
                override fun fetch(enrollmentId: String, contextId: String): BootstrapTransportResult {
                    bootstraps++
                    return BootstrapTransportResult.Success(manifestJson())
                }
            },
            packagedAgentInstaller = PackagedAgentInstaller { installs++; PackagedAgentInstall.Blocked("must not install") },
        )
        val result = manager.activate("enrollment-1") as ResearchActivationResult.Blocked
        assertTrue(result.reason == StudyBlockReason.INCOMPATIBLE_ENVIRONMENT)
        assertTrue(installs == 0 && bootstraps == 0)
    }

    @Test
    fun `stop during preparation returns without opening a session or activating late`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var bootstraps = 0
        val transport = object : BootstrapTransport {
            override fun prepare(enrollmentId: String, contextId: String): BootstrapTransportResult {
                val manifest = parseCanonicalJson(manifestJson()) as Map<*, *>
                return BootstrapTransportResult.Success(canonicalJson(manifest.filterKeys {
                    it in listOf("enrollment_id", "study_id", "assignment", "agent_release")
                }))
            }
            override fun fetch(enrollmentId: String, contextId: String): BootstrapTransportResult {
                bootstraps++
                return BootstrapTransportResult.Success(manifestJson())
            }
        }
        val manager = ResearchSessionManager(
            projectKey = "preparation-race", transport = transport, compatibility = compatibility(),
            packagedAgentInstaller = PackagedAgentInstaller {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                PackagedAgentInstall.Ready(listOf("/agent", "--managed"), "a".repeat(64))
            },
            clock = { VALID_NOW.toEpochMilli() }, instantClock = { VALID_NOW },
        )
        val result = AtomicReference<ResearchActivationResult>()
        val activation = Thread { result.set(manager.activate("enrollment-1")) }
        activation.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS), "preparation never started")
        manager.stop()
        release.countDown()
        activation.join(5_000)
        assertFalse(activation.isAlive)
        assertTrue(result.get() is ResearchActivationResult.Blocked)
        assertTrue(bootstraps == 0)
        assertFalse(manager.isActive)
    }

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
