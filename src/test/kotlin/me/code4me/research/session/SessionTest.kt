package me.code4me.research.session

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.code4me.research.IdSequence
import me.code4me.research.bootstrap.BootstrapEnvironment
import me.code4me.research.bootstrap.BootstrapManifest
import me.code4me.research.bootstrap.BootstrapRejection
import me.code4me.research.bootstrap.BootstrapTransport
import me.code4me.research.bootstrap.BootstrapTransportResult
import me.code4me.research.bootstrap.EnrollmentDiscovery
import me.code4me.research.bootstrap.HttpBootstrapTransport
import me.code4me.research.bootstrap.VALID_ARTIFACT_DIGEST
import me.code4me.research.bootstrap.VALID_NOW
import me.code4me.research.bootstrap.compatibility
import me.code4me.research.bootstrap.compatibility as manifestCompatibility
import me.code4me.research.bootstrap.manifestJson
import me.code4me.research.bootstrap.uniqueResearchSessionId
import me.code4me.research.builder
import me.code4me.research.ide.IdeActivitySignal
import me.code4me.research.ide.IdeActivitySource
import me.code4me.research.ide.IntellijIdeActivitySource
import me.code4me.research.proxy.AcpHostRegistration
import me.code4me.research.proxy.AgentDiscoverySource
import me.code4me.research.proxy.ByoaAgentResolution
import me.code4me.research.proxy.ByoaAgentResolver
import me.code4me.research.proxy.ByoaAgentSpec
import me.code4me.research.proxy.ObservedAgentIdentity
import me.code4me.research.proxy.PackagedProxyRuntimeResolver
import me.code4me.research.proxy.ProxyLaunchRequest
import me.code4me.research.proxy.ProxyLaunchSpec
import me.code4me.research.proxy.ProxyRuntimeError
import me.code4me.research.proxy.ProxyRuntimeErrorCode
import me.code4me.research.proxy.ProxyRuntimeResolution
import me.code4me.research.proxy.ProxyRuntimeResolver
import me.code4me.research.proxy.ResolvedProxyRuntime
import me.code4me.research.runtime.ContentHasher
import me.code4me.research.session.SpoolDeliveryState
import me.code4me.research.session.StudyBlockReason
import me.code4me.research.session.StudyComponentState
import me.code4me.research.spool.DurableSpool
import me.code4me.research.spool.ResearchSpoolIpcServer
import me.code4me.research.spool.SpoolDelivery
import me.code4me.research.spool.SpoolIpcServer
import me.code4me.research.spool.SpoolUploaderContext
import me.code4me.research.spool.SpoolUploaderState
import me.code4me.research.telemetry.CanonicalEventTypes
import me.code4me.research.telemetry.EventSource
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.parseCanonicalJson
import me.code4me.services.config.models.ServerConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// --------------------------------------------------------------------------
// ParticipantStudyStateV1Test.kt
// --------------------------------------------------------------------------

class ParticipantStudyStateV1Test {
    @Test
    fun `default state is unavailable and never collectable`() {
        val state = ParticipantStudyStateV1()

        assertEquals(StudyComponentState.UNAVAILABLE, state.consentState)
        assertEquals(StudyComponentState.UNAVAILABLE, state.compatibilityState)
        assertEquals(StudyComponentState.UNAVAILABLE, state.sessionState)
        assertFalse(state.isCollecting)
        assertFalse(state.canLaunch)
    }

    @Test
    fun `available components with a manifest are collectable and launchable`() {
        val state =
            ParticipantStudyStateV1(
                enrollmentId = "enrollment-1",
                studyId = "study-1",
                assignmentId = "assignment-1",
                profileDigest = "profile-digest-1",
                consentState = StudyComponentState.AVAILABLE,
                compatibilityState = StudyComponentState.AVAILABLE,
                sessionState = StudyComponentState.AVAILABLE,
                manifestExpiry = "2026-01-01T01:00:00Z",
                manifestDigest = "digest",
            )

        assertTrue(state.isCollecting)
        assertTrue(state.canLaunch)
    }

    @Test
    fun `a blocked state carries a typed reason and is not collectable`() {
        val state =
            ParticipantStudyStateV1(
                consentState = StudyComponentState.BLOCKED,
                blockReason = StudyBlockReason.REVOKED,
                blockReasonDetail = "enrollment is not active",
            )

        assertFalse(state.isCollecting)
        assertFalse(state.canLaunch)
        assertEquals(StudyBlockReason.REVOKED, state.blockReason)
    }

    @Test
    fun `component states are exactly the five participant states`() {
        assertEquals(
            setOf("AVAILABLE", "UNAVAILABLE", "PAUSED", "BLOCKED", "FAILED"),
            StudyComponentState.entries.map { it.value }.toSet(),
        )
        assertEquals(StudyComponentState.AVAILABLE, StudyComponentState.fromWire("available"))
        assertEquals(StudyComponentState.FAILED, StudyComponentState.fromWire("FAILED"))
        assertEquals(null, StudyComponentState.fromWire("ENABLED"))
    }

    @Test
    fun `state exposes no account or participant identity fields`() {
        val identityTokens = listOf("user", "email", "account", "participant", "person", "device")
        val fieldNames =
            ParticipantStudyStateV1::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .map { it.name.lowercase() }

        assertTrue(fieldNames.isNotEmpty())
        fieldNames.forEach { name ->
            identityTokens.forEach { token ->
                assertFalse(name.contains(token), "field '$name' leaks identity token '$token'")
            }
        }
    }

    @Test
    fun `canonical json carries no identity`() {
        val state =
            ParticipantStudyStateV1(
                enrollmentId = "enrollment-1",
                studyId = "study-1",
                assignmentId = "assignment-1",
                profileDigest = "profile-digest-1",
                consentState = StudyComponentState.PAUSED,
                blockReason = StudyBlockReason.REVOKED,
            )

        val json = state.toCanonicalJson()
        listOf("user", "email", "account", "participant").forEach { token ->
            assertFalse(json.contains(token), json)
        }
        assertTrue(json.contains("\"consent_state\":\"PAUSED\""), json)
        assertTrue(json.contains("\"block_reason\":\"REVOKED\""), json)
    }
}

// --------------------------------------------------------------------------
// ResearchSessionManagerTest.kt
// --------------------------------------------------------------------------

class ResearchSessionManagerTest {
    private lateinit var root: Path

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("research-session-manager-test")
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private class MutableClock(private var epochMs: Long, private var instant: Instant) {
        fun nowMs(): Long = epochMs

        fun nowInstant(): Instant = instant

        fun advanceMs(millis: Long) {
            epochMs += millis
            instant = instant.plusMillis(millis)
        }
    }

    private class FakeIdeSource : IdeActivitySource {
        private var callback: ((IdeActivitySignal) -> Unit)? = null

        val isSubscribed: Boolean
            get() = callback != null

        override fun onActivity(callback: (IdeActivitySignal) -> Unit) {
            val firstSubscriber = this.callback == null
            this.callback = callback
            // Mirror the real source: a project-scoped source reports the open
            // project when it is first observed.
            if (firstSubscriber) {
                push(IdeActivitySignal(IntellijIdeActivitySource.PROJECT_OPENED, PROJECT_KEY))
            }
        }

        /**
         * Mirrors the real source: a collector/subscriber failure is contained and
         * never escapes into the caller (IDE) thread.
         */
        fun push(signal: IdeActivitySignal) {
            callback?.let { runCatching { it(signal) } }
        }
    }

    private fun newClock(): MutableClock = MutableClock(VALID_NOW.toEpochMilli(), VALID_NOW)

    private fun validTransport(
        researchSessionId: String = uniqueResearchSessionId(),
    ): BootstrapTransport =
        BootstrapTransport { _, _ ->
            BootstrapTransportResult.Success(manifestJson(researchSessionId = researchSessionId))
        }

    @Test
    fun `opaque execution context ids are stable and distinct per project`() {
        val a = ResearchSessionManager.opaqueContextId("project-a")
        val b = ResearchSessionManager.opaqueContextId("project-b")

        assertEquals(a, ResearchSessionManager.opaqueContextId("project-a"))
        assertNotEquals(a, b)
        assertTrue(a.startsWith("ctx-"))
        // Opaque: the raw project key never appears.
        assertFalse(a.contains("project-a"))
    }

    private fun manager(
        transport: BootstrapTransport,
        clock: MutableClock = newClock(),
        store: ResearchSessionStore = InMemoryResearchSessionStore(),
        source: IdeActivitySource? = null,
        spoolProvider: (String) -> DurableSpool = { enrollmentId -> DurableSpool(root.resolve(enrollmentId)) },
        launcher: ProxyLauncher = ProxyLauncher { _ -> ProxyHandle { } },
        projectKey: String = "project-under-test",
    ): ResearchSessionManager =
        ResearchSessionManager(
            projectKey = projectKey,
            transport = transport,
            compatibility = manifestCompatibility(),
            spoolProvider = spoolProvider,
            source = source,
            proxyLauncher = launcher,
            sessionStore = store,
            clock = clock::nowMs,
            instantClock = clock::nowInstant,
            sessionIdFactory = { "session-${java.util.UUID.randomUUID()}" },
            runIdFactory = { "run-${java.util.UUID.randomUUID()}" },
        )

    private fun activity(signalProjectKey: String = "project-under-test"): IdeActivitySignal =
        IdeActivitySignal(
            kind = "opened",
            projectKey = signalProjectKey,
            metadata = mapOf("file_extension" to "kt"),
        )

    private fun proxyRequest(): ProxyLaunchRequest =
        ProxyLaunchRequest(
            runtimeRoot = root.resolve("runtime"),
            executableRelativePath = "bin/code4me-proxy",
            expectedArtifactDigest = ARTIFACT_DIGEST,
            actualArtifactDigest = ARTIFACT_DIGEST,
            researchSessionId = "placeholder",
            telemetryPolicyDigest = "policy-digest",
            workspace = root.resolve("workspace"),
            ipcCapability = "ipc-capability",
        )

    // ------------------------------------------------------------------
    // Activation gating
    // ------------------------------------------------------------------

    @Test
    fun `revoked manifest blocks with no collectors and no proxy`() {
        val source = FakeIdeSource()
        var launched = false
        val manager =
            manager(
                transport = BootstrapTransport { _, _ -> BootstrapTransportResult.Revoked("withdrawn") },
                source = source,
                launcher =
                    ProxyLauncher { _ ->
                        launched = true
                        ProxyHandle { }
                    },
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.REVOKED, (result as ResearchActivationResult.Blocked).reason)
        assertFalse(source.isSubscribed)
        assertFalse(manager.isCollecting)
        assertFalse(manager.isActive)
        val state = manager.state()
        assertEquals(StudyComponentState.BLOCKED, state.consentState)
        assertFalse(state.isCollecting)
        assertEquals(StudyBlockReason.REVOKED, state.blockReason)

        val proxy = manager.launchProxy(proxyRequest())
        assertTrue(proxy is ProxyLaunchResult.Rejected)
        assertFalse(launched)
    }

    @Test
    fun `typed server rejections keep their own block reason instead of collapsing to withdrawn`() {
        fun blockedFor(transport: BootstrapTransport) =
            manager(transport, source = FakeIdeSource()).activate("enrollment-1") as ResearchActivationResult.Blocked

        val compatibility =
            blockedFor(
                BootstrapTransport { _, _ ->
                    BootstrapTransportResult.Revoked("compatibility missing", BootstrapRejection.COMPATIBILITY_MISSING)
                },
            )
        assertEquals(StudyBlockReason.INCOMPATIBLE_ENVIRONMENT, compatibility.reason)
        assertEquals(BootstrapRejection.COMPATIBILITY_MISSING, compatibility.rejection)

        val notActive =
            blockedFor(
                BootstrapTransport { _, _ ->
                    BootstrapTransportResult.Revoked("enrollment is WITHDRAWN", BootstrapRejection.ENROLLMENT_NOT_ACTIVE)
                },
            )
        assertEquals(BootstrapRejection.ENROLLMENT_NOT_ACTIVE, notActive.rejection)

        val revoked =
            blockedFor(
                BootstrapTransport { _, _ ->
                    BootstrapTransportResult.Revoked("revoked", BootstrapRejection.REVOKED)
                },
            )
        assertEquals(StudyBlockReason.REVOKED, revoked.reason)
        assertEquals(BootstrapRejection.REVOKED, revoked.rejection)

        val notAuthenticated =
            blockedFor(
                BootstrapTransport { _, _ ->
                    BootstrapTransportResult.Failure(
                        "not signed in",
                        retryable = false,
                        rejection = BootstrapRejection.NOT_AUTHENTICATED,
                    )
                },
            )
        assertEquals(StudyBlockReason.UNKNOWN, notAuthenticated.reason)
        assertEquals(BootstrapRejection.NOT_AUTHENTICATED, notAuthenticated.rejection)
    }

    @Test
    fun `expired manifest blocks instead of starting a session`() {
        val transport =
            BootstrapTransport { _, _ ->
                BootstrapTransportResult.Success(
                    manifestJson(overrides = mapOf("expires_at" to "2026-01-01T00:10:00Z")),
                )
            }

        val result = manager(transport, source = FakeIdeSource()).activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.MANIFEST_EXPIRED, (result as ResearchActivationResult.Blocked).reason)
    }

    @Test
    fun `invalid manifest digest blocks instead of starting a session`() {
        val transport =
            BootstrapTransport { _, _ ->
                BootstrapTransportResult.Success(manifestJson(digestOverride = "0".repeat(64)))
            }

        val result = manager(transport, source = FakeIdeSource()).activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.MANIFEST_INVALID, (result as ResearchActivationResult.Blocked).reason)
    }

    @Test
    fun `successful activation activates collectors and first activity runs the session`() {
        val source = FakeIdeSource()
        val spool = DurableSpool(root.resolve("spool"))
        val manager = manager(validTransport(), source = source, spoolProvider = { spool })

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Activated)
        assertEquals(manager.currentSession?.sessionId, (result as ResearchActivationResult.Activated).sessionId)
        assertTrue(source.isSubscribed)
        assertTrue(manager.isCollecting)
        // Activation alone does not start the activity session.
        assertEquals(StudyComponentState.UNAVAILABLE, manager.state().sessionState)

        source.push(activity())

        assertEquals(SessionState.RUNNING, manager.currentSession?.state)
        assertEquals(StudyComponentState.AVAILABLE, manager.state().sessionState)
        assertTrue(manager.state().isCollecting)
        val records = spool.pending()
        // A project.opened lifecycle observation is spooled for review but is not
        // a qualifying activity, so it does not start the session.
        assertTrue(records.any { it.event.unknownEventType == IntellijIdeActivitySource.PROJECT_OPENED })
        val activityRecords = records.filter { it.event.unknownEventType == null }
        assertEquals(1, activityRecords.size)
        assertEquals("kt", activityRecords.single().event.payload["file_extension"])
    }

    // ------------------------------------------------------------------
    // Restart / resume policy
    // ------------------------------------------------------------------

    @Test
    fun `restart within grace reopens the same session`() {
        val store = InMemoryResearchSessionStore()
        val clock = newClock()
        val firstSource = FakeIdeSource()
        val sessionId = "session-within-grace"
        val first = manager(validTransport(sessionId), clock = clock, store = store, source = firstSource)
        val activation = first.activate("enrollment-1") as ResearchActivationResult.Activated
        firstSource.push(activity())
        assertEquals(SessionState.RUNNING, first.currentSession?.state)

        first.stop()

        val second = manager(validTransport(sessionId), clock = clock, store = store, source = FakeIdeSource())
        val resumed = second.activate("enrollment-1") as ResearchActivationResult.Activated

        assertTrue(resumed.resumedExistingSession)
        assertEquals(activation.sessionId, resumed.sessionId)
        assertEquals(activation.sessionId, second.currentSession?.sessionId)
    }

    @Test
    fun `restart after grace starts a new session`() {
        val store = InMemoryResearchSessionStore()
        val clock = newClock()
        val firstSource = FakeIdeSource()
        val first = manager(validTransport("session-before-grace"), clock = clock, store = store, source = firstSource)
        val activation = first.activate("enrollment-1") as ResearchActivationResult.Activated
        firstSource.push(activity())
        first.stop()

        // Manifest policy: resume_grace_seconds = 120.
        clock.advanceMs(120_001L)

        val second = manager(validTransport("session-after-grace"), clock = clock, store = store, source = FakeIdeSource())
        val resumed = second.activate("enrollment-1") as ResearchActivationResult.Activated

        assertFalse(resumed.resumedExistingSession)
        assertNotEquals(activation.sessionId, resumed.sessionId)
    }

    @Test
    fun `restart within grace reopens the same session from a file-backed store`() {
        val sessionRoot = root.resolve("durable-sessions")
        val clock = newClock()
        val sessionId = "session-durable-within-grace"
        val firstSource = FakeIdeSource()
        val first =
            manager(
                validTransport(sessionId),
                clock = clock,
                store = FileResearchSessionStore(sessionRoot),
                source = firstSource,
            )
        val activation = first.activate("enrollment-1") as ResearchActivationResult.Activated
        firstSource.push(activity())
        assertEquals(SessionState.RUNNING, first.currentSession?.state)
        first.stop()

        // A fresh store over the same directory models an IDE restart: the
        // session document written before the restart is what reopens it.
        val second =
            manager(
                validTransport(sessionId),
                clock = clock,
                store = FileResearchSessionStore(sessionRoot),
                source = FakeIdeSource(),
            )
        val resumed = second.activate("enrollment-1") as ResearchActivationResult.Activated

        assertTrue(resumed.resumedExistingSession)
        assertEquals(activation.sessionId, resumed.sessionId)
        assertEquals(activation.sessionId, second.currentSession?.sessionId)
    }

    // ------------------------------------------------------------------
    // Agent runs
    // ------------------------------------------------------------------

    @Test
    fun `agent crash ends the run but not the session`() {
        val source = FakeIdeSource()
        val manager = manager(validTransport(), source = source)
        manager.activate("enrollment-1")
        source.push(activity())
        val sessionId = manager.currentSession?.sessionId

        val started = manager.agentStarted("release-1")
        assertTrue(started is AgentRunResult.Started)

        val ended = manager.agentCrashed()
        assertTrue(ended is AgentRunResult.Ended)
        assertEquals(AgentRunOutcome.CRASHED, (ended as AgentRunResult.Ended).run.outcome)
        assertTrue(ended.run.isTerminal)
        assertEquals(sessionId, manager.currentSession?.sessionId)
        assertEquals(SessionState.RUNNING, manager.currentSession?.state)
        assertTrue(manager.isCollecting)
    }

    // ------------------------------------------------------------------
    // Revocation
    // ------------------------------------------------------------------

    @Test
    fun `revocation stops collection and emits nothing further`() {
        val source = FakeIdeSource()
        val spool = DurableSpool(root.resolve("revoke-spool"))
        val manager = manager(validTransport(), source = source, spoolProvider = { spool })
        manager.activate("enrollment-1")
        source.push(activity())
        val emittedBeforeRevocation = spool.pending().size
        assertTrue(emittedBeforeRevocation >= 1)

        val result = manager.onRevoked()

        assertEquals(SessionState.REVOKED, (result as ResearchSessionResult.Applied).sessionState)
        assertFalse(manager.isCollecting)
        assertFalse(manager.isActive)
        assertEquals(StudyComponentState.BLOCKED, manager.state().consentState)
        assertEquals(StudyBlockReason.REVOKED, manager.state().blockReason)

        source.push(activity())
        assertEquals(emittedBeforeRevocation, spool.pending().size)
    }

    
    // ------------------------------------------------------------------
    // Stop / idle / offline
    // ------------------------------------------------------------------

    @Test
    fun `stop is terminal for the manager and suspends a live session`() {
        val source = FakeIdeSource()
        val spool = DurableSpool(root.resolve("stop-spool"))
        val manager = manager(validTransport(), source = source, spoolProvider = { spool })
        manager.activate("enrollment-1")
        source.push(activity())

        val pendingBeforeStop = spool.pending().size
        val stop = manager.stop() as ResearchStopResult.Stopped

        assertEquals(SessionState.SUSPENDED, stop.finalSessionState)
        assertEquals(pendingBeforeStop, stop.pendingSpoolRecords)
        assertNotNull(stop.spoolStats)
        assertFalse(manager.isActive)
        assertEquals(StudyComponentState.PAUSED, manager.state().sessionState)

        source.push(activity())
        assertEquals(pendingBeforeStop, spool.pending().size)

        val reactivation = manager.activate("enrollment-1")
        assertTrue(reactivation is ResearchActivationResult.Blocked)
    }

    @Test
    fun `stop before activation is safe and reports no session`() {
        val manager = manager(validTransport())

        val stop = manager.stop() as ResearchStopResult.Stopped

        assertNull(stop.finalSessionState)
        assertEquals(0, stop.pendingSpoolRecords)
        assertNull(stop.spoolStats)
        assertEquals(StudyComponentState.UNAVAILABLE, manager.state().sessionState)
    }

    @Test
    fun `idle timeout ends the session and stops collection`() {
        val source = FakeIdeSource()
        val clock = newClock()
        val manager = manager(validTransport(), clock = clock, source = source)
        manager.activate("enrollment-1")
        source.push(activity())

        // Manifest policy: idle_timeout_seconds = 600.
        clock.advanceMs(600_001L)

        val heartbeat = manager.heartbeat()

        assertEquals(SessionState.ENDED, (heartbeat as ResearchSessionResult.Applied).sessionState)
        assertFalse(manager.isCollecting)
        assertEquals(StudyBlockReason.SESSION_ENDED, manager.state().blockReason)
    }

    @Test
    fun `network loss goes offline and heartbeat recovers`() {
        val source = FakeIdeSource()
        val manager = manager(validTransport(), source = source)
        manager.activate("enrollment-1")
        source.push(activity())

        val offline = manager.onNetworkUnavailable()
        assertEquals(SessionState.OFFLINE, (offline as ResearchSessionResult.Applied).sessionState)

        val recovered = manager.heartbeat()
        assertEquals(SessionState.RUNNING, (recovered as ResearchSessionResult.Applied).sessionState)
        assertTrue(manager.isCollecting)
    }

    @Test
    fun `explicit close ends the session terminally`() {
        val source = FakeIdeSource()
        val manager = manager(validTransport(), source = source)
        manager.activate("enrollment-1")
        source.push(activity())

        val closed = manager.close()

        assertEquals(SessionState.ENDED, (closed as ResearchSessionResult.Applied).sessionState)
        assertEquals(StudyBlockReason.SESSION_ENDED, manager.state().blockReason)
        assertFalse(manager.isCollecting)
    }

    // ------------------------------------------------------------------
    // Failure containment
    // ------------------------------------------------------------------

    @Test
    fun `throwing transport returns a typed failure and does not throw`() {
        val manager = manager(BootstrapTransport { _, _ -> throw IllegalStateException("transport boom") })

        val result = assertDoesNotThrow<ResearchActivationResult> { manager.activate("enrollment-1") }

        assertTrue(result is ResearchActivationResult.Failed)
        assertFalse(manager.isActive)
    }

    @Test
    fun `spool initialization failure returns a typed failure and does not throw`() {
        val notADirectory = root.resolve("not-a-directory")
        Files.writeString(notADirectory, "x")
        val manager = manager(validTransport(), spoolProvider = { DurableSpool(notADirectory) })

        val result = assertDoesNotThrow<ResearchActivationResult> { manager.activate("enrollment-1") }

        assertTrue(result is ResearchActivationResult.Failed)
        assertFalse(manager.isActive)
    }

    // ------------------------------------------------------------------
    // Project isolation, proxy, content
    // ------------------------------------------------------------------

    @Test
    fun `two managers never cross attribute events or sessions`() {
        val sourceA = FakeIdeSource()
        val sourceB = FakeIdeSource()
        val spoolA = DurableSpool(root.resolve("project-a"))
        val spoolB = DurableSpool(root.resolve("project-b"))
        val managerA =
            manager(
                validTransport("session-a"),
                source = sourceA,
                spoolProvider = { spoolA },
                store = InMemoryResearchSessionStore(),
                projectKey = "project-a",
            )
        val managerB =
            manager(
                validTransport("session-b"),
                source = sourceB,
                spoolProvider = { spoolB },
                store = InMemoryResearchSessionStore(),
                projectKey = "project-b",
            )

        val activationA = managerA.activate("enrollment-a") as ResearchActivationResult.Activated
        val activationB = managerB.activate("enrollment-b") as ResearchActivationResult.Activated
        sourceA.push(activity("project-a"))
        sourceB.push(activity("project-b"))

        val eventA = spoolA.pending().single { it.event.unknownEventType == null }.event
        val eventB = spoolB.pending().single { it.event.unknownEventType == null }.event

        assertEquals(activationA.sessionId, eventA.researchSessionId)
        assertEquals(activationB.sessionId, eventB.researchSessionId)
        assertNotEquals(activationA.sessionId, activationB.sessionId)
        assertNotEquals(eventA.emitterId, eventB.emitterId)
        assertNotEquals(eventA.researchSessionId, eventB.researchSessionId)
    }

    @Test
    fun `proxy launch requires a validated active session and tears down on stop`() {
        val source = FakeIdeSource()
        var launchedSpec: ProxyLaunchSpec? = null
        var proxyStopped = false
        val manager =
            manager(
                validTransport(),
                source = source,
                launcher =
                    ProxyLauncher { spec ->
                        launchedSpec = spec
                        ProxyHandle { proxyStopped = true }
                    },
            )

        // Before a validated activation, no launch may happen.
        assertTrue(manager.launchProxy(proxyRequest()) is ProxyLaunchResult.Rejected)

        manager.activate("enrollment-1")
        val result = manager.launchProxy(proxyRequest())

        assertTrue(result is ProxyLaunchResult.Running)
        assertNotNull(launchedSpec)
        assertEquals(manager.currentSession?.sessionId, launchedSpec?.researchSessionId)

        manager.stop()
        assertTrue(proxyStopped)
    }

    @Test
    fun `source text canaries never reach the spool`() {
        val source = FakeIdeSource()
        val spool = DurableSpool(root.resolve("canary-spool"))
        val manager = manager(validTransport(), source = source, spoolProvider = { spool })
        manager.activate("enrollment-1")

        val canary = "CANARY_SOURCE_TEXT val secret = \"hunter2\""
        // Rejected by the metadata allowlist before it can be emitted.
        source.push(IdeActivitySignal(kind = "changed", projectKey = "project-under-test", metadata = mapOf("editor_text" to canary)))
        source.push(IdeActivitySignal(kind = "changed", projectKey = "project-under-test", metadata = mapOf("language" to canary)))
        // A legitimate metadata-only observation still flows.
        source.push(activity())

        val records = spool.pending()
        val activityRecords = records.filter { it.event.unknownEventType == null }
        assertEquals(1, activityRecords.size)
        records.forEach { record ->
            assertFalse(record.canonicalJson.contains(canary))
            assertFalse(record.canonicalJson.contains("hunter2"))
            assertTrue(
                record.event.payload.keys.all { it == "file_extension" || it == "language" || it == "action_category" || it == "count" },
            )
        }
    }

    private companion object {
        const val ARTIFACT_DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val PROJECT_KEY = "project-under-test"
    }
}

// --------------------------------------------------------------------------
// ResearchSessionRuntimeWiringTest.kt
// --------------------------------------------------------------------------

/**
 * Runtime wiring: activation must fail closed with `RUNTIME_UNAVAILABLE` when the
 * packaged proxy cannot be resolved, has no bundled agent, or resolves an agent
 * whose digest does not match the bootstrap manifest's pinned artifact. A fully
 * resolved runtime must register exactly one removable ACP entry whose
 * `--agent-digest` is the manifest pin.
 */
class ResearchSessionRuntimeWiringTest {
    private lateinit var root: Path

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("research-runtime-wiring")
    }

    @Test
    fun `a failed resolution blocks activation with RUNTIME_UNAVAILABLE and writes no ACP entry`() {
        val registry = root.resolve("acp.json")
        val failing =
            ProxyRuntimeResolver {
                ProxyRuntimeResolution.Failed(
                    ProxyRuntimeError(ProxyRuntimeErrorCode.ARTIFACT_MISSING, "packaged proxy is missing"),
                )
            }
        val manager = manager(resolver = failing, registration = AcpHostRegistration(registry))

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.RUNTIME_UNAVAILABLE, (result as ResearchActivationResult.Blocked).reason)
        assertFalse(manager.isActive)
        assertFalse(Files.exists(registry), "no ACP entry may be written when the runtime cannot be resolved")
    }

    @Test
    fun `a resolved runtime without an agent blocks activation and writes no ACP entry`() {
        val registry = root.resolve("acp.json")
        val runtime = runtimeWithoutAgent()
        val registration = AcpHostRegistration(registry)
        val manager = manager(resolver = ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(runtime) }, registration = registration)

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.RUNTIME_UNAVAILABLE, (result as ResearchActivationResult.Blocked).reason)
        assertFalse(registration.hasEntry())
        assertFalse(Files.exists(registry), "an agent-less runtime must not register an ACP entry")
    }

    @Test
    fun `a resolved runtime whose agent digest matches the manifest pin activates and pins the ACP entry`() {
        val registry = root.resolve("acp.json")
        val capabilityFile = root.resolve("capability.txt")
        val runtime = runtimeWithAgent(VALID_ARTIFACT_DIGEST)
        val registration = AcpHostRegistration(registry)
        val manager =
            manager(
                resolver = ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(runtime) },
                registration = registration,
                capabilityFile = capabilityFile,
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Activated)
        assertTrue(manager.isActive)
        assertTrue(registration.hasEntry(), "the ACP entry must be registered after activation")
        assertEquals(
            VALID_ARTIFACT_DIGEST,
            registeredAgentDigest(registry),
            "the ACP entry must pin exactly the manifest artifact digest",
        )

        manager.stop()

        assertFalse(registration.hasEntry(), "the ACP entry must be removed on stop")
        assertFalse(Files.exists(capabilityFile), "the one-time capability file must be removed on teardown")
    }

    @Test
    fun `an agent digest that does not match the manifest pin blocks with RUNTIME_UNAVAILABLE`() {
        val registry = root.resolve("acp.json")
        val capabilityFile = root.resolve("capability.txt")
        // The manifest pins VALID_ARTIFACT_DIGEST; the resolved agent is different.
        val runtime = runtimeWithAgent("cd".repeat(32))
        val registration = AcpHostRegistration(registry)
        val manager =
            manager(
                resolver = ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(runtime) },
                registration = registration,
                capabilityFile = capabilityFile,
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.RUNTIME_UNAVAILABLE, (result as ResearchActivationResult.Blocked).reason)
        assertFalse(manager.isActive)
        assertFalse(registration.hasEntry(), "a mismatched agent must not register an ACP entry")
        assertFalse(Files.exists(registry), "a mismatched agent must write no ACP entry")
        assertFalse(Files.exists(capabilityFile), "a mismatched agent must leave no capability file behind")
    }

    @Test
    fun `a sha256 prefixed manifest digest normalizes against the resolved bare digest`() {
        val registry = root.resolve("acp.json")
        val capabilityFile = root.resolve("capability.txt")
        val runtime = runtimeWithAgent(VALID_ARTIFACT_DIGEST)
        val registration = AcpHostRegistration(registry)
        val manager =
            manager(
                resolver = ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(runtime) },
                registration = registration,
                capabilityFile = capabilityFile,
                manifest = manifestWithArtifactDigest("sha256:$VALID_ARTIFACT_DIGEST"),
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Activated, "sha256: is display convention, not a mismatch")
        assertEquals(VALID_ARTIFACT_DIGEST, registeredAgentDigest(registry))
    }

    @Test
    fun `a resolved runtime with an agent registers an ACP entry and removes it on stop`() {
        val registry = root.resolve("acp.json")
        val runtime = runtimeWithAgent(VALID_ARTIFACT_DIGEST)
        val registration = AcpHostRegistration(registry)
        val capabilityFile = root.resolve("capability.txt")
        val manager =
            manager(
                resolver = ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(runtime) },
                registration = registration,
                capabilityFile = capabilityFile,
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Activated)
        assertTrue(manager.isActive)
        assertTrue(registration.hasEntry(), "the ACP entry must be registered after activation")
        assertTrue(Files.exists(registry))
        assertTrue(Files.exists(capabilityFile), "the one-time capability file is written for the proxy")
        assertTrue(
            Files.readString(registry).contains("--agent-cmd"),
            "the registered entry must pin the packaged agent",
        )

        manager.stop()

        assertFalse(registration.hasEntry(), "the ACP entry must be removed on stop")
        assertFalse(Files.exists(capabilityFile), "the one-time capability file must be removed on teardown")
    }

    @Test
    fun `a BYOA agent resolved from metadata activates and pins its observed digest`() {
        val registry = root.resolve("acp-byoa.json")
        val capabilityFile = root.resolve("capability-byoa.txt")
        val agent = root.resolve("byoa/goose")
        Files.createDirectories(agent.parent)
        Files.writeString(agent, "goose-binary")
        agent.toFile().setExecutable(true, false)
        val observedDigest = ContentHasher.STREAMING.sha256(agent)
        val byoa =
            ByoaAgentResolver {
                ByoaAgentResolution.Resolved(
                    identity =
                        ObservedAgentIdentity(
                            executable = agent,
                            digest = observedDigest,
                            version = "goose 1.0.0",
                            source = AgentDiscoverySource.RELEASE_COMMAND,
                        ),
                    argv = listOf(agent.toString(), "acp"),
                )
            }
        val registration = AcpHostRegistration(registry)
        val manager =
            manager(
                resolver = ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(runtimeWithoutAgent()) },
                registration = registration,
                capabilityFile = capabilityFile,
                // The release pins a portable command name; the participant host
                // (or the settings override) supplies the absolute path.
                manifest = manifestWithByoaRelease(command = "goose"),
                byoaResolver = byoa,
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Activated, (result as? ResearchActivationResult.Blocked)?.detail)
        assertTrue(registration.hasEntry())
        // The observed BYOA digest is recorded, not a pre-pinned artifact digest.
        assertEquals(observedDigest, registeredAgentDigest(registry))
        val args = registeredArgs(registry)
        assertEquals("--agent-cmd", args[args.size - 3], "--agent-cmd is a REMAINDER and must be last")
        assertEquals(agent.toString(), args[args.size - 2])
        assertEquals("acp", args.last())

        manager.stop()
        assertFalse(registration.hasEntry())
    }

    @Test
    fun `a BYOA agent that is not installed blocks with AGENT_NOT_FOUND and writes no ACP entry`() {
        val registry = root.resolve("acp-byoa-missing.json")
        val registration = AcpHostRegistration(registry)
        val manager =
            manager(
                resolver = ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(runtimeWithoutAgent()) },
                registration = registration,
                manifest = manifestWithByoaRelease(command = "goose"),
                byoaResolver = ByoaAgentResolver { ByoaAgentResolution.NotFound("goose is not installed") },
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.AGENT_NOT_FOUND, (result as ResearchActivationResult.Blocked).reason)
        assertFalse(manager.isActive)
        assertFalse(registration.hasEntry(), "an unresolved BYOA agent must not register an ACP entry")
        assertFalse(Files.exists(registry), "an unresolved BYOA agent must write no ACP entry")
    }

    @Test
    fun `a PACKAGED distribution resolves the host-platform artifact and pins its digest`() {
        val registry = root.resolve("acp-packaged-host.json")
        val capabilityFile = root.resolve("capability-packaged-host.txt")
        val runtimeRoot = root.resolve("runtime-packaged-host")
        val (_, agent) = writePackagedRuntime(runtimeRoot, "macos", "aarch64")
        val agentDigest = ContentHasher.STREAMING.sha256(agent)
        val resolver = PackagedProxyRuntimeResolver(explodedRoot = runtimeRoot, os = "macos", arch = "aarch64")
        val registration = AcpHostRegistration(registry)
        val manager =
            manager(
                resolver = resolver,
                registration = registration,
                capabilityFile = capabilityFile,
                // The server pinned the host-platform artifact digest.
                manifest = manifestWithArtifactDigest(agentDigest),
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Activated, (result as? ResearchActivationResult.Blocked)?.detail)
        assertEquals(
            agentDigest,
            registeredAgentDigest(registry),
            "the ACP entry must pin the resolved host-platform artifact digest",
        )
        val args = registeredArgs(registry)
        // `--agent-cmd` is an argparse REMAINDER and carries the resolved agent argv.
        assertEquals(AcpHostRegistration.AGENT_CMD_FLAG, args[args.size - 2])
        assertEquals(agent.toString(), args.last())

        manager.stop()
    }

    @Test
    fun `a PACKAGED distribution on an unsupported host platform fails closed`() {
        val registry = root.resolve("acp-packaged-unsupported.json")
        val runtimeRoot = root.resolve("runtime-packaged-other")
        // The bundled runtime only ships a linux-x64 artifact; this host resolves
        // as macos-aarch64, so no artifact may be selected.
        writePackagedRuntime(runtimeRoot, "linux", "x64")
        val resolver = PackagedProxyRuntimeResolver(explodedRoot = runtimeRoot, os = "macos", arch = "aarch64")
        val registration = AcpHostRegistration(registry)
        val manager =
            manager(
                resolver = resolver,
                registration = registration,
                manifest = manifestWithArtifactDigest(VALID_ARTIFACT_DIGEST),
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.RUNTIME_UNAVAILABLE, (result as ResearchActivationResult.Blocked).reason)
        assertFalse(manager.isActive)
        assertFalse(registration.hasEntry(), "an unsupported host platform must not register an ACP entry")
        assertFalse(Files.exists(registry), "an unsupported host platform must write no ACP entry")
    }

    @Test
    fun `a BYOA distribution launches from the pinned identity with no digest`() {
        val registry = root.resolve("acp-byoa-identity.json")
        val capabilityFile = root.resolve("capability-byoa-identity.txt")
        val agent = root.resolve("byoa-identity/goose")
        Files.createDirectories(agent.parent)
        Files.writeString(agent, "goose-binary")
        agent.toFile().setExecutable(true, false)
        val observedDigest = ContentHasher.STREAMING.sha256(agent)
        var capturedSpec: ByoaAgentSpec? = null
        val byoa =
            ByoaAgentResolver { spec ->
                capturedSpec = spec
                ByoaAgentResolution.Resolved(
                    identity =
                        ObservedAgentIdentity(
                            executable = agent,
                            digest = observedDigest,
                            version = "goose 2.0.0",
                            source = AgentDiscoverySource.RELEASE_COMMAND,
                        ),
                    argv = listOf(agent.toString(), "acp"),
                )
            }
        val byoaManifest = manifestWithByoaRelease(command = "goose")
        val parsed = BootstrapManifest.parse(byoaManifest)
        assertTrue(parsed.agentRelease.isByoa)
        assertTrue(parsed.agentRelease.releaseId.isBlank(), "a BYOA distribution with no registered release pins no release id")
        assertNull(parsed.agentRelease.normalizedArtifactDigest, "a BYOA distribution pins no artifact digest")

        val registration = AcpHostRegistration(registry)
        val manager =
            manager(
                resolver = ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(runtimeWithoutAgent()) },
                registration = registration,
                capabilityFile = capabilityFile,
                manifest = byoaManifest,
                byoaResolver = byoa,
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Activated, (result as? ResearchActivationResult.Blocked)?.detail)
        // The pinned identity reached the resolver; no manifest digest was invented.
        assertEquals("goose", capturedSpec?.command)
        assertEquals(listOf("acp"), capturedSpec?.commandArgs)
        assertEquals("goose", capturedSpec?.agentPackage)
        // The ACP entry pins the locally observed identity, with `--agent-cmd` last.
        assertEquals(observedDigest, registeredAgentDigest(registry))
        val args = registeredArgs(registry)
        assertEquals(AcpHostRegistration.AGENT_CMD_FLAG, args[args.size - 3])
        assertEquals(agent.toString(), args[args.size - 2])
        assertEquals("acp", args.last())

        manager.stop()
    }

    /**
     * Write an exploded runtime whose `proxy-manifest.json` declares exactly one
     * platform artifact (plus a bundled agent) and return the agent path.
     */
    private fun writePackagedRuntime(
        runtimeRoot: Path,
        os: String,
        arch: String,
    ): Pair<Path, Path> {
        val proxy = runtimeRoot.resolve("bin/telemetry-acp-proxy")
        Files.createDirectories(proxy.parent)
        Files.writeString(proxy, "proxy-binary")
        val agent = runtimeRoot.resolve("agents/$os-$arch/code4me-agent")
        Files.createDirectories(agent.parent)
        Files.writeString(agent, "agent-binary")
        val relativeAgent = "agents/$os-$arch/code4me-agent"
        val json =
            """{"schema_version":"1","platforms":[{"os":"$os","arch":"$arch","self_contained":true,""" +
                """"entrypoint":["bin/telemetry-acp-proxy"],""" +
                """"files":[{"path":"bin/telemetry-acp-proxy","sha256":"${ContentHasher.STREAMING.sha256(proxy)}",""" +
                """"size":${Files.size(proxy)},"executable":true}],""" +
                """"agent":{"entrypoint":["$relativeAgent"],"digest":"${ContentHasher.STREAMING.sha256(agent)}",""" +
                """"files":[{"path":"$relativeAgent","sha256":"${ContentHasher.STREAMING.sha256(agent)}",""" +
                """"size":${Files.size(agent)},"executable":true}]}}]}"""
        Files.writeString(runtimeRoot.resolve("proxy-manifest.json"), json)
        return proxy to agent
    }

    private fun runtimeWithoutAgent(): ResolvedProxyRuntime {
        val proxyExecutable = root.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        return ResolvedProxyRuntime(
            runtimeRoot = root.resolve("runtime"),
            proxyArgv = listOf(proxyExecutable.toString(), "--stdio"),
            proxyDigest = "ab".repeat(32),
        )
    }

    private fun runtimeWithAgent(agentDigest: String): ResolvedProxyRuntime {
        val proxyExecutable = root.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        val agentExecutable = root.resolve("runtime/agents/macos-aarch64/code4me-agent")
        Files.createDirectories(agentExecutable.parent)
        Files.writeString(agentExecutable, "agent-binary")
        return ResolvedProxyRuntime(
            runtimeRoot = root.resolve("runtime"),
            proxyArgv = listOf(proxyExecutable.toString(), "--stdio"),
            proxyDigest = "ab".repeat(32),
            agentArgv = listOf(agentExecutable.toString()),
            agentDigest = agentDigest,
        )
    }

    /** The `--agent-digest` value recorded for this component's ACP entry. */
    private fun registeredAgentDigest(registry: Path): String? {
        val root = Json.parseToJsonElement(Files.readString(registry)).jsonObject
        val args =
            root["agent_servers"]
                ?.jsonObject
                ?.get(AcpHostRegistration.DEFAULT_ENTRY_NAME)
                ?.jsonObject
                ?.get("args")
                ?.jsonArray
                ?: return null
        val flat = args.map { it.jsonPrimitive.content }
        val index = flat.indexOf(AcpHostRegistration.AGENT_DIGEST_FLAG)
        return flat.getOrNull(index + 1)
    }

    private fun manifestWithArtifactDigest(digest: String): String =
        manifestJson(
            overrides =
                mapOf(
                    "agent_release" to
                        linkedMapOf<String, Any?>(
                            "agent_id" to "codex-acp",
                            "release_id" to "release-1",
                            "version" to "1.2.3",
                            "artifact_digest" to digest,
                            "adapter_version" to "codex-v1",
                        ),
                ),
        )

    private fun manifestWithByoaRelease(command: String): String =
        manifestJson(
            overrides =
                mapOf(
                    "agent_release" to
                        linkedMapOf<String, Any?>(
                            "agent_id" to "goose",
                            // A BYOA distribution with no registered release freezes
                            // no release id; the backend projects an empty string.
                            "release_id" to "",
                            "distribution_mode" to "BYOA_EXTERNAL",
                            "agent_command" to command,
                            "agent_command_args" to listOf("acp"),
                            "agent_package" to "goose",
                        ),
                ),
        )

    /** Every argument of this component's ACP entry, in order. */
    private fun registeredArgs(registry: Path): List<String> {
        val root = Json.parseToJsonElement(Files.readString(registry)).jsonObject
        return root["agent_servers"]
            ?.jsonObject
            ?.get(AcpHostRegistration.DEFAULT_ENTRY_NAME)
            ?.jsonObject
            ?.get("args")
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()
    }

    private fun manager(
        resolver: ProxyRuntimeResolver?,
        registration: AcpHostRegistration?,
        capabilityFile: Path? = null,
        manifest: String = manifestJson(),
        byoaResolver: ByoaAgentResolver? = null,
    ): ResearchSessionManager =
        ResearchSessionManager(
            projectKey = "project-under-test",
            transport = BootstrapTransport { _, _ -> BootstrapTransportResult.Success(manifest) },
            compatibility = compatibility(),
            spoolProvider = { enrollmentId -> DurableSpool(root.resolve(enrollmentId)) },
            proxyRuntimeResolver = resolver,
            acpHostRegistration = registration,
            capabilityFilePathProvider = { capabilityFile },
            byoaAgentResolver = byoaResolver ?: ByoaAgentResolver.DEFAULT,
            sessionStore = InMemoryResearchSessionStore(),
            clock = { VALID_NOW.toEpochMilli() },
            instantClock = { VALID_NOW },
            sessionIdFactory = { "session-1" },
            runIdFactory = { "run-1" },
        )
}

// --------------------------------------------------------------------------
// ResearchSessionServiceTransportTest.kt
// --------------------------------------------------------------------------

/**
 * Pure-JVM tests for the participant transport wiring in [ResearchSessionService].
 *
 * No IntelliJ platform service is started: only the pure companion factories are
 * exercised, which is exactly the logic that decides between the configured HTTP
 * transport and the retryable stub.
 */
class ResearchSessionServiceTransportTest {
    private val environment = { BootstrapEnvironment(os = "linux", arch = "x86_64") }

    @Test
    fun `without a base url the transport stays on the retryable stub`() {
        val result = ResearchSessionService.participantTransport(null, environment).fetch("enrollment-1", "ctx-test")

        val failure = result as BootstrapTransportResult.Failure
        assertTrue(failure.retryable)
        assertTrue(failure.message.contains("not configured"), failure.message)
    }

    @Test
    fun `a blank base url also keeps the stub`() {
        val result = ResearchSessionService.participantTransport("   ", environment).fetch("enrollment-1", "ctx-test")

        assertTrue((result as BootstrapTransportResult.Failure).retryable)
    }

    @Test
    fun `a configured base url returns the http transport`() {
        val transport = ResearchSessionService.participantTransport("http://localhost:9", environment)

        assertTrue(transport is HttpBootstrapTransport)
    }

    @Test
    fun `resolveBaseUrl returns null without server config`() {
        assertNull(ResearchSessionService.resolveBaseUrl(null))
    }

    @Test
    fun `resolveBaseUrl adds the scheme and port for a bare host`() {
        val server = ServerConfig(host = "localhost", port = 8008, contextPath = "", timeout = 5000)

        assertEquals("http://localhost:8008", ResearchSessionService.resolveBaseUrl(server))
    }

    @Test
    fun `resolveBaseUrl keeps an explicit port and appends the context path`() {
        val server = ServerConfig(host = "http://localhost:9000", port = 80, contextPath = "api", timeout = 5000)

        assertEquals("http://localhost:9000/api", ResearchSessionService.resolveBaseUrl(server))
    }

    @Test
    fun `resolveBaseUrl does not duplicate an existing host path`() {
        val server = ServerConfig(host = "https://api.example.com/v2", port = 0, contextPath = "ignored", timeout = 5000)

        assertEquals("https://api.example.com/v2", ResearchSessionService.resolveBaseUrl(server))
    }

    @Test
    fun `resolveBaseUrl prefers the acp runtime base url`() {
        val server =
            ServerConfig(
                host = "http://localhost:8008",
                port = 8008,
                contextPath = "",
                timeout = 5000,
                acpRuntimeBaseUrl = "http://host.docker.internal:9000/",
            )

        assertEquals("http://host.docker.internal:9000", ResearchSessionService.resolveBaseUrl(server))
    }
}

// --------------------------------------------------------------------------
// ResearchSessionSpoolWiringTest.kt
// --------------------------------------------------------------------------

/**
 * Session-lifecycle wiring for the Gap 2/3 spool components.
 *
 * Activation must start the loopback IPC server over the session spool, register
 * the ACP entry against that endpoint with the LOCAL IPC capability, and start an
 * uploader; every teardown path must close both, unregister, and delete the
 * capability file. Failures stay typed and never corrupt other state.
 */
class ResearchSessionSpoolWiringTest {
    private lateinit var root: Path
    private lateinit var registry: Path
    private lateinit var capabilityFile: Path

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("research-spool-wiring")
        registry = root.resolve("acp.json")
        capabilityFile = root.resolve("capability.txt")
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeDelivery(
        private val snapshot: SpoolUploaderState = SpoolUploaderState(),
        private val startThrows: Boolean = false,
        private val closeThrows: Boolean = false,
    ) : SpoolDelivery {
        var started = false
        var closed = false

        override fun start() {
            if (startThrows) throw IllegalStateException("uploader start failed")
            started = true
        }

        override fun close() {
            if (closeThrows) throw IllegalStateException("uploader close failed")
            closed = true
        }

        override val isRunning: Boolean
            get() = started && !closed

        override fun state(): SpoolUploaderState = snapshot
    }

    private class FakeIpcServer : SpoolIpcServer {
        override val endpointUrl: String = "http://127.0.0.1:1/spool"
        override val capability: String = "ipc-capability"
        var closed = false

        override fun close() {
            closed = true
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun resolvedRuntime(): ResolvedProxyRuntime {
        val proxyExecutable = root.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        Files.writeString(proxyExecutable, "proxy-binary")
        val agentExecutable = root.resolve("runtime/agents/macos-aarch64/code4me-agent")
        Files.createDirectories(agentExecutable.parent)
        Files.writeString(agentExecutable, "agent-binary")
        return ResolvedProxyRuntime(
            runtimeRoot = root.resolve("runtime"),
            proxyArgv = listOf(proxyExecutable.toString(), "--stdio"),
            proxyDigest = "ab".repeat(32),
            agentArgv = listOf(agentExecutable.toString()),
            agentDigest = VALID_ARTIFACT_DIGEST,
        )
    }

    private fun manager(
        spool: DurableSpool,
        ipcServerFactory: ((DurableSpool) -> SpoolIpcServer)? = null,
        uploaderFactory: ((SpoolUploaderContext) -> SpoolDelivery)? = null,
        serverBaseUrl: String? = "http://localhost:9",
        resolver: ProxyRuntimeResolver? = ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(resolvedRuntime()) },
    ): ResearchSessionManager {
        val registration = AcpHostRegistration(registry)
        return ResearchSessionManager(
            projectKey = "project-under-test",
            transport = BootstrapTransport { _, _ -> BootstrapTransportResult.Success(manifestJson()) },
            compatibility = compatibility(),
            spoolProvider = { spool },
            proxyRuntimeResolver = resolver,
            acpHostRegistration = registration,
            capabilityFilePathProvider = { capabilityFile },
            serverBaseUrlProvider = { serverBaseUrl },
            ipcServerFactory = ipcServerFactory ?: { target -> ResearchSpoolIpcServer(target) },
            uploaderFactory = uploaderFactory ?: { _ -> FakeDelivery() },
            sessionStore = InMemoryResearchSessionStore(),
            clock = { VALID_NOW.toEpochMilli() },
            instantClock = { VALID_NOW },
            sessionIdFactory = { "session-1" },
            runIdFactory = { "run-1" },
        )
    }

    /**
     * A manager with no configured capability path, so the default stable
     * per-enrollment path under [capabilityRoot] is exercised.
     */
    private fun managerWithDefaultCapabilityRoot(
        spool: DurableSpool,
        capabilityRoot: Path,
        ipcServerFactory: ((DurableSpool) -> SpoolIpcServer)? = null,
    ): ResearchSessionManager =
        ResearchSessionManager(
            projectKey = "project-under-test",
            transport = BootstrapTransport { _, _ -> BootstrapTransportResult.Success(manifestJson()) },
            compatibility = compatibility(),
            spoolProvider = { spool },
            proxyRuntimeResolver = ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(resolvedRuntime()) },
            acpHostRegistration = AcpHostRegistration(registry),
            capabilityFilePathProvider = { null },
            capabilityRootProvider = { capabilityRoot },
            serverBaseUrlProvider = { null },
            ipcServerFactory = ipcServerFactory ?: { target -> ResearchSpoolIpcServer(target) },
            uploaderFactory = { _ -> FakeDelivery() },
            sessionStore = InMemoryResearchSessionStore(),
            clock = { VALID_NOW.toEpochMilli() },
            instantClock = { VALID_NOW },
            sessionIdFactory = { "session-1" },
            runIdFactory = { "run-1" },
        )

    private fun registryArgs(): List<String> {
        val document = parseCanonicalJson(Files.readString(registry)) as Map<*, *>
        val servers = document["agent_servers"] as Map<*, *>
        val entry = servers[AcpHostRegistration.DEFAULT_ENTRY_NAME] as Map<*, *>
        return (entry["args"] as List<*>).map { it as String }
    }

    /** The `env` map recorded for this component's ACP entry. */
    private fun registryEnv(): Map<*, *> {
        val document = parseCanonicalJson(Files.readString(registry)) as Map<*, *>
        val servers = document["agent_servers"] as Map<*, *>
        val entry = servers[AcpHostRegistration.DEFAULT_ENTRY_NAME] as Map<*, *>
        return entry["env"] as Map<*, *>
    }

    private fun argAfter(
        args: List<String>,
        flag: String,
    ): String? {
        val index = args.indexOf(flag)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    private fun postToIpc(
        endpoint: String,
        capability: String,
        eventJson: Map<String, Any?>,
    ): HttpResponse<String> {
        val body =
            canonicalJson(
                linkedMapOf(
                    "schema_version" to "1",
                    "proxy_digest" to "ab".repeat(32),
                    "emitter_id" to "acp-proxy",
                    "events" to listOf(eventJson),
                ),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header(ResearchSpoolIpcServer.CAPABILITY_HEADER, capability)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            .send(request, HttpResponse.BodyHandlers.ofString())
    }

    // ------------------------------------------------------------------
    // Activation
    // ------------------------------------------------------------------

    @Test
    fun `activation starts the IPC server over the spool, registers the ACP entry, and starts the uploader`() {
        val spool = DurableSpool(root.resolve("spool"))
        val captured = ArrayList<SpoolUploaderContext>()
        val manager =
            manager(
                spool,
                uploaderFactory = { context ->
                    captured.add(context)
                    FakeDelivery()
                },
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Activated)
        assertTrue(manager.isActive)

        // The registered entry points at the live IPC endpoint and the capability
        // file the proxy will read.
        val args = registryArgs()
        val endpoint = argAfter(args, AcpHostRegistration.SPOOL_ENDPOINT_FLAG)
        assertNotNull(endpoint)
        assertTrue(endpoint!!.startsWith("http://127.0.0.1:") && endpoint.endsWith("/spool"), endpoint)
        assertEquals(capabilityFile.toAbsolutePath().normalize().toString(), argAfter(args, AcpHostRegistration.CAPABILITY_FILE_FLAG))
        assertTrue(Files.exists(capabilityFile))

        // The capability file holds the LOCAL IPC capability: a POST authenticated
        // with it must durably append to the session spool.
        val capability = Files.readString(capabilityFile).trim()
        assertTrue(capability.isNotBlank())
        assertNotEquals("capability-1", capability, "the file must hold the LOCAL IPC capability, not the server capability")
        // The capability also travels in the entry env, so the entry is
        // self-sufficient even after the fallback file is removed.
        assertEquals(
            capability,
            registryEnv()[AcpHostRegistration.CAPABILITY_ENV_VAR],
            "the entry env must carry the LOCAL IPC capability",
        )
        val event = builder(emitterId = "acp-proxy", eventIds = IdSequence("evt")).build(eventType = CanonicalEventTypes.TOOL_STARTED)

        val response = postToIpc(endpoint, capability, event.toCanonicalMap())

        assertEquals(200, response.statusCode(), response.body())
        assertTrue(spool.pending().any { it.eventId == event.eventId })

        // The uploader was constructed from the validated manifest capability and
        // a stable client instance id.
        assertEquals(1, captured.size)
        assertEquals("http://localhost:9", captured.single().serverBaseUrl)
        assertEquals("capability-1", captured.single().sessionCapability["capability_id"])
        assertTrue(captured.single().clientInstanceId.isNotBlank())
        assertEquals(
            ResearchSessionManager.opaqueClientInstanceId("enrollment-1"),
            captured.single().clientInstanceId,
        )
    }

    @Test
    fun `teardown closes the uploader and IPC server, unregisters, and deletes the capability file`() {
        val spool = DurableSpool(root.resolve("spool"))
        val delivery = FakeDelivery()
        val ipc = FakeIpcServer()
        val manager = manager(spool, ipcServerFactory = { ipc }, uploaderFactory = { delivery })
        manager.activate("enrollment-1")
        assertTrue(delivery.started)
        assertTrue(Files.exists(capabilityFile))

        manager.stop()

        assertTrue(delivery.closed)
        assertTrue(ipc.closed)
        assertFalse(AcpHostRegistration(registry).hasEntry())
        assertFalse(Files.exists(capabilityFile))
    }

    @Test
    fun `a new activation tears down the previous session runtime first`() {
        val spool = DurableSpool(root.resolve("spool"))
        val deliveries = ArrayList<FakeDelivery>()
        val ipcs = ArrayList<FakeIpcServer>()
        val manager =
            manager(
                spool,
                ipcServerFactory = { FakeIpcServer().also { ipcs.add(it) } },
                uploaderFactory = { FakeDelivery().also { deliveries.add(it) } },
            )
        manager.activate("enrollment-1")
        assertTrue(deliveries.single().started)

        // A second activation (same manager) must not leave the old runtime running.
        manager.activate("enrollment-1")

        assertEquals(2, deliveries.size)
        assertTrue(deliveries[0].closed)
        assertTrue(ipcs[0].closed)
        assertFalse(deliveries[1].closed)
    }

    @Test
    fun `the default capability file path is stable per enrollment and is removed on teardown`() {
        val spool = DurableSpool(root.resolve("spool"))
        val capabilityRoot = root.resolve("capability-root")
        val manager =
            managerWithDefaultCapabilityRoot(
                spool,
                capabilityRoot,
                ipcServerFactory = { FakeIpcServer() },
            )

        assertTrue(manager.activate("enrollment-1") is ResearchActivationResult.Activated)
        val firstPath = argAfter(registryArgs(), AcpHostRegistration.CAPABILITY_FILE_FLAG)
        assertNotNull(firstPath)
        val writtenFile = Path.of(firstPath!!)
        assertEquals(
            capabilityRoot.resolve("capability-${ResearchSessionManager.opaqueSessionKey("enrollment-1")}.txt"),
            writtenFile,
            "the default capability file must be stable per enrollment, not a fresh temp file",
        )
        assertTrue(Files.exists(writtenFile), "the capability file is written for the proxy")
        assertEquals("ipc-capability", Files.readString(writtenFile).trim())

        // A second activation for the same enrollment must reuse the exact same
        // path (rewriting the token) instead of minting a new random temp file.
        assertTrue(manager.activate("enrollment-1") is ResearchActivationResult.Activated)
        assertEquals(
            firstPath,
            argAfter(registryArgs(), AcpHostRegistration.CAPABILITY_FILE_FLAG),
            "the capability file path must not change across activations",
        )
        assertTrue(Files.exists(writtenFile), "the capability file must persist after registration")

        manager.stop()

        assertFalse(Files.exists(writtenFile), "teardown removes the capability file")
    }

    // ------------------------------------------------------------------
    // Fail-closed
    // ------------------------------------------------------------------

    @Test
    fun `a stale ACP entry is unregistered even when this instance never registered it`() {
        // Simulate an entry left behind by a crashed/earlier instance: the
        // plugin must never leave an entry that outlives its capability.
        Files.writeString(
            registry,
            """{"agent_servers":{"${AcpHostRegistration.DEFAULT_ENTRY_NAME}":{"command":"old"}}}""",
        )
        val spool = DurableSpool(root.resolve("spool"))
        val manager =
            manager(
                spool,
                resolver =
                    ProxyRuntimeResolver {
                        ProxyRuntimeResolution.Failed(ProxyRuntimeError(ProxyRuntimeErrorCode.ARTIFACT_MISSING, "missing"))
                    },
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertFalse(AcpHostRegistration(registry).hasEntry(), "a stale entry must not survive a teardown")
    }

    @Test
    fun `an IPC server that cannot start blocks activation`() {
        val spool = DurableSpool(root.resolve("spool"))
        val delivery = FakeDelivery()
        val manager =
            manager(
                spool,
                ipcServerFactory = { throw IllegalStateException("loopback bind failed") },
                uploaderFactory = { delivery },
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.RUNTIME_UNAVAILABLE, (result as ResearchActivationResult.Blocked).reason)
        assertFalse(manager.isActive)
        assertFalse(delivery.started)
        assertFalse(AcpHostRegistration(registry).hasEntry())
        assertFalse(Files.exists(capabilityFile))
    }

    @Test
    fun `an uploader that cannot start blocks activation and cleans up the ACP entry`() {
        val spool = DurableSpool(root.resolve("spool"))
        val ipc = FakeIpcServer()
        val manager =
            manager(
                spool,
                ipcServerFactory = { ipc },
                uploaderFactory = { throw IllegalStateException("uploader init failed") },
            )

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.RUNTIME_UNAVAILABLE, (result as ResearchActivationResult.Blocked).reason)
        assertFalse(manager.isActive)
        assertTrue(ipc.closed)
        assertFalse(AcpHostRegistration(registry).hasEntry(), "a failed activation must not leave an ACP entry")
        assertFalse(Files.exists(capabilityFile), "a failed activation must not leave a capability file")
    }

    @Test
    fun `a stuck or throwy uploader never escapes activate or stop`() {
        val spool = DurableSpool(root.resolve("spool"))
        val throwing = manager(spool, uploaderFactory = { FakeDelivery(startThrows = true) })
        val result = assertDoesNotThrow<ResearchActivationResult> { throwing.activate("enrollment-1") }
        assertTrue(result is ResearchActivationResult.Blocked)
        assertFalse(throwing.isActive)

        val closeThrows = manager(spool, uploaderFactory = { FakeDelivery(closeThrows = true) })
        assertTrue(closeThrows.activate("enrollment-1") is ResearchActivationResult.Activated)
        assertDoesNotThrow { closeThrows.stop() }
    }

    @Test
    fun `the participant state surfaces the uploader posture without ids or secrets`() {
        val spool = DurableSpool(root.resolve("spool"))
        val manager = manager(spool, uploaderFactory = { FakeDelivery(SpoolUploaderState(spoolFull = true, pendingCount = 3)) })
        manager.activate("enrollment-1")

        val state = manager.state()

        assertEquals(SpoolDeliveryState.SPOOL_FULL, state.deliveryState)
        assertFalse(state.toCanonicalJson().contains("http://localhost:9"))
        assertFalse(state.toCanonicalJson().contains("capability"))
    }
}

// --------------------------------------------------------------------------
// SessionStateMachineTest.kt
// --------------------------------------------------------------------------

class SessionStateMachineTest {
    private val machine = SessionStateMachine(resumeGraceMs = 60_000L, idleTimeoutMs = 300_000L)

    private fun newSession() = ResearchSession(sessionId = "session-1", enrollmentId = "enrollment-1")

    @Test
    fun `legal transitions are allowed and illegal ones rejected`() {
        val session = newSession()
        assertTrue(machine.canTransition(SessionState.NOT_STARTED, SessionState.RUNNING))
        assertTrue(machine.canTransition(SessionState.RUNNING, SessionState.SUSPENDED))
        assertTrue(machine.canTransition(SessionState.OFFLINE, SessionState.RUNNING))
        assertFalse(machine.canTransition(SessionState.NOT_STARTED, SessionState.SUSPENDED))
        assertFalse(machine.canTransition(SessionState.ENDED, SessionState.RUNNING))

        assertThrows(IllegalSessionTransitionException::class.java) {
            machine.transition(session, SessionState.SUSPENDED, 100L)
        }
    }

    @Test
    fun `first qualifying activity starts the session`() {
        val started = machine.onQualifyingActivity(newSession(), 1_000L)
        assertEquals(SessionState.RUNNING, started.state)
        assertEquals(1_000L, started.openedAtEpochMs)
        assertEquals(1_000L, started.lastActivityEpochMs)
    }

    @Test
    fun `resume within grace reopens the same session`() {
        val running = machine.onQualifyingActivity(newSession(), 1_000L)
        val suspended = machine.suspend(running, 2_000L)

        val resumed = machine.resume(suspended, 2_000L + 60_000L)

        assertTrue(resumed.reopened)
        assertEquals(ResumeOutcome.REOPENED_SAME_SESSION, resumed.outcome)
        assertEquals("session-1", resumed.session.sessionId)
        assertEquals(SessionState.RUNNING, resumed.session.state)
        assertEquals(1, resumed.session.resumeGeneration)
        assertEquals(suspended.openedAtEpochMs, resumed.session.openedAtEpochMs)
    }

    @Test
    fun `resume after grace ends the prior session`() {
        val running = machine.onQualifyingActivity(newSession(), 1_000L)
        val suspended = machine.suspend(running, 2_000L)

        val resumed = machine.resume(suspended, 2_000L + 60_000L + 1L)

        assertFalse(resumed.reopened)
        assertEquals(ResumeOutcome.SESSION_ENDED_GRACE_EXPIRED, resumed.outcome)
        assertEquals(SessionState.ENDED, resumed.session.state)
        assertEquals(SessionTerminalReason.RESUME_GRACE_EXPIRED, resumed.session.closeReason)
    }

    @Test
    fun `idle expiry ends a running session`() {
        val running = machine.onQualifyingActivity(newSession(), 1_000L)
        assertEquals(SessionState.RUNNING, machine.expireIfIdle(running, 1_000L + 300_000L).state)

        val expired = machine.expireIfIdle(running, 1_000L + 300_000L + 1L)
        assertEquals(SessionState.ENDED, expired.state)
        assertEquals(SessionTerminalReason.IDLE_TIMEOUT, expired.closeReason)
    }

    @Test
    fun `agent crash ends the run but not the session`() {
        val running = machine.onQualifyingActivity(newSession(), 1_000L)
        val run =
            AgentRun.start(
                runId = "run-1",
                session = running,
                agentReleaseId = "agent-release-1",
                startedAtEpochMs = 1_500L,
            )

        val result = machine.onAgentRunCrashed(run, running, 9_000L)

        assertTrue(result.run.isTerminal)
        assertEquals(AgentRunOutcome.CRASHED, result.run.outcome)
        assertEquals(SessionState.RUNNING, result.session.state)
        assertEquals(running.sessionId, result.session.sessionId)

        // A restart inside the same session creates a distinct run identity.
        val restarted =
            AgentRun.start(
                runId = "run-2",
                session = result.session,
                agentReleaseId = "agent-release-1",
                startedAtEpochMs = 9_100L,
            )
        assertEquals(running.sessionId, restarted.researchSessionId)
        assertTrue(restarted.runId != restarted.researchSessionId)
    }

    @Test
    fun `revocation is terminal`() {
        val running = machine.onQualifyingActivity(newSession(), 1_000L)
        val revoked = machine.revoke(running, 5_000L)
        assertEquals(SessionState.REVOKED, revoked.state)
        assertEquals(SessionTerminalReason.REVOKED, revoked.closeReason)
        assertTrue(revoked.isTerminal)

        val resumeAttempt = machine.resume(revoked, 6_000L)
        assertEquals(ResumeOutcome.REJECTED_TERMINAL, resumeAttempt.outcome)
        assertThrows(IllegalSessionTransitionException::class.java) {
            machine.transition(revoked, SessionState.RUNNING, 7_000L)
        }
    }

    @Test
    fun `session and run identities are never interchangeable`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentRun(
                runId = "same-id",
                researchSessionId = "same-id",
                startedAtEpochMs = 1L,
            )
        }
    }

    @Test
    fun `offline and recovery keep the session alive`() {
        val running = machine.onQualifyingActivity(newSession(), 1_000L)
        val offline = machine.goOffline(running, 2_000L)
        assertEquals(SessionState.OFFLINE, offline.state)

        val recovered = machine.recover(offline, 3_000L)
        assertEquals(SessionState.RUNNING, recovered.state)
        assertFalse(recovered.isTerminal)
    }
}

// --------------------------------------------------------------------------
// FileResearchSessionStoreTest.kt
// --------------------------------------------------------------------------

/**
 * Durable, file-backed session store contract: one canonical-JSON document per
 * enrollment under an opaque key, atomic writes, and a read that never throws.
 */
class FileResearchSessionStoreTest {
    private lateinit var root: Path

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("file-session-store")
    }

    private fun sampleSession(): ResearchSession =
        ResearchSession(
            sessionId = "session-1",
            enrollmentId = "enrollment-1",
            studyId = "study-1",
            assignmentId = "assignment-1",
            profileDigest = "profile-digest-1",
            state = SessionState.RUNNING,
            openedAtEpochMs = 1_000L,
            lastActivityEpochMs = 2_000L,
            resumeGeneration = 3,
        )

    private fun onlyDocument(): Path =
        Files.newDirectoryStream(root).use { stream -> stream.single { it.fileName.toString().endsWith(".json") } }

    @Test
    fun `save then load round trips every field`() {
        val store = FileResearchSessionStore(root)
        store.save(sampleSession())

        assertEquals(sampleSession(), store.load("enrollment-1"))
    }

    @Test
    fun `a terminal session round trips its close reason`() {
        val store = FileResearchSessionStore(root)
        val ended =
            sampleSession().copy(
                state = SessionState.ENDED,
                closedAtEpochMs = 3_000L,
                closeReason = SessionTerminalReason.IDLE_TIMEOUT,
            )
        store.save(ended)

        assertEquals(ended, store.load("enrollment-1"))
    }

    @Test
    fun `a missing document loads as no stored session`() {
        assertNull(FileResearchSessionStore(root).load("enrollment-1"))
    }

    @Test
    fun `a corrupt document loads as no stored session and never throws`() {
        val store = FileResearchSessionStore(root)
        store.save(sampleSession())
        Files.writeString(onlyDocument(), "{ not json")

        val loaded = assertDoesNotThrow<ResearchSession?> { store.load("enrollment-1") }

        assertNull(loaded)
    }

    @Test
    fun `an unknown state loads as no stored session`() {
        val store = FileResearchSessionStore(root)
        store.save(sampleSession())
        Files.writeString(
            onlyDocument(),
            canonicalJson(linkedMapOf("session_id" to "session-1", "state" to "bogus")),
        )

        assertNull(store.load("enrollment-1"))
    }

    @Test
    fun `the document name is an opaque key and never the enrollment id`() {
        val store = FileResearchSessionStore(root)
        store.save(sampleSession())

        val name = Files.newDirectoryStream(root).use { it.single().fileName.toString() }

        assertTrue(name.startsWith("session-"), name)
        assertTrue(name.endsWith(".json"), name)
        assertFalse(name.contains("enrollment-1"), name)
        assertTrue(name.contains(ResearchSessionManager.opaqueSessionKey("enrollment-1")), name)
    }

    @Test
    fun `clear removes the stored document`() {
        val store = FileResearchSessionStore(root)
        store.save(sampleSession())
        assertNotNull(store.load("enrollment-1"))

        store.clear("enrollment-1")

        assertNull(store.load("enrollment-1"))
    }

    @Test
    fun `save leaves exactly one document and no temporary file behind`() {
        val store = FileResearchSessionStore(root)
        store.save(sampleSession())
        store.save(sampleSession().copy(state = SessionState.SUSPENDED))

        val files = Files.newDirectoryStream(root).use { it.toList() }

        assertEquals(1, files.size, files.toString())
        assertFalse(files.single().fileName.toString().endsWith(".tmp"))
    }

    @Test
    fun `two execution contexts never share or overwrite session state`() {
        val store = FileResearchSessionStore(root)
        val contextA = sampleSession().copy(sessionId = "session-a", contextId = "ctx-a")
        val contextB = sampleSession().copy(sessionId = "session-b", contextId = "ctx-b")

        store.save(contextA)
        store.save(contextB)

        assertEquals("session-a", store.load("enrollment-1::ctx-a")?.sessionId)
        assertEquals("session-b", store.load("enrollment-1::ctx-b")?.sessionId)
        // No context-less document: the legacy per-enrollment lookup is empty.
        assertNull(store.load("enrollment-1"))
        assertEquals(2, Files.newDirectoryStream(root).use { it.toList() }.size)
    }

    @Test
    fun `clearing one context leaves the other context's document intact`() {
        val store = FileResearchSessionStore(root)
        store.save(sampleSession().copy(sessionId = "session-a", contextId = "ctx-a"))
        store.save(sampleSession().copy(sessionId = "session-b", contextId = "ctx-b"))

        store.clear("enrollment-1::ctx-a")

        assertNull(store.load("enrollment-1::ctx-a"))
        assertEquals("session-b", store.load("enrollment-1::ctx-b")?.sessionId)
    }

    @Test
    fun `a session round trips its execution context`() {
        val store = FileResearchSessionStore(root)
        store.save(sampleSession().copy(contextId = "ctx-a"))

        assertEquals("ctx-a", store.load("enrollment-1::ctx-a")?.contextId)
    }
}

// --------------------------------------------------------------------------
// ResearchSessionExposureTest.kt
// --------------------------------------------------------------------------

/**
 * Exposure receipts: the first proxy-origin canonical event of a session records
 * the condition exposure through
 * `POST {serverBaseUrl}/api/research/bootstrap/exposures`; a fail-closed runtime
 * startup records a single NON-exposure instead. The call is idempotent per
 * `(session, agent run)`, bounded on failure, and carries no content.
 */
class ResearchSessionExposureTest {
    private lateinit var root: Path
    private lateinit var registry: Path
    private lateinit var capabilityFile: Path

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("research-exposure")
        registry = root.resolve("acp.json")
        capabilityFile = root.resolve("capability.txt")
    }

    private class RecordingHttp(
        private val statusProvider: (Request) -> Int = { 201 },
    ) : Call.Factory {
        val requests = CopyOnWriteArrayList<Request>()

        override fun newCall(request: Request): Call = RecordingCall(request, statusProvider, requests)

        private class RecordingCall(
            private val request: Request,
            private val statusProvider: (Request) -> Int,
            private val requests: MutableList<Request>,
        ) : Call {
            override fun request(): Request = request

            override fun execute(): Response {
                requests.add(request)
                return Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusProvider(request))
                    .message("test")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }

            override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException("async not used")

            override fun cancel() = Unit

            override fun isExecuted(): Boolean = false

            override fun isCanceled(): Boolean = false

            override fun timeout(): Timeout = Timeout.NONE

            override fun clone(): Call = RecordingCall(request, statusProvider, requests)
        }
    }

    private class NoopDelivery : SpoolDelivery {
        override fun start() = Unit

        override fun close() = Unit

        override val isRunning: Boolean
            get() = true
    }

    private fun resolvedRuntime(): ResolvedProxyRuntime =
        ResolvedProxyRuntime(
            runtimeRoot = root.resolve("runtime"),
            proxyArgv = listOf(root.resolve("runtime/bin/telemetry-acp-proxy").toString(), "--stdio"),
            proxyDigest = "ab".repeat(32),
            agentArgv = listOf(root.resolve("runtime/agents/macos-aarch64/code4me-agent").toString()),
            agentDigest = VALID_ARTIFACT_DIGEST,
        )

    private fun manager(
        http: Call.Factory,
        resolver: ProxyRuntimeResolver = ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(resolvedRuntime()) },
        serverBaseUrl: String? = "http://localhost:8008",
        manifestOverride: String = manifestJson(),
        byoaResolver: ByoaAgentResolver = ByoaAgentResolver.DEFAULT,
        transport: BootstrapTransport = BootstrapTransport { _, _ -> BootstrapTransportResult.Success(manifestOverride) },
    ): ResearchSessionManager =
        ResearchSessionManager(
            projectKey = "project-under-test",
            transport = transport,
            compatibility = compatibility(),
            spoolProvider = { enrollmentId -> DurableSpool(root.resolve("spool-$enrollmentId")) },
            proxyRuntimeResolver = resolver,
            acpHostRegistration = AcpHostRegistration(registry),
            capabilityFilePathProvider = { capabilityFile },
            byoaAgentResolver = byoaResolver,
            serverBaseUrlProvider = { serverBaseUrl },
            environmentProvider = {
                BootstrapEnvironment(
                    os = "macos",
                    arch = "aarch64",
                    ideBuild = "IU-261.0",
                    pluginVersion = "0.0.1",
                    hostKind = "IntelliJ IDEA",
                )
            },
            httpClient = http,
            uploaderFactory = { NoopDelivery() },
            sessionStore = InMemoryResearchSessionStore(),
            clock = { VALID_NOW.toEpochMilli() },
            instantClock = { VALID_NOW },
            sessionIdFactory = { "session-1" },
            runIdFactory = { "run-1" },
        )

    private fun exposureRequests(http: RecordingHttp): List<Request> =
        http.requests.filter { it.url.encodedPath == "/api/research/bootstrap/exposures" }

    private fun bodyOf(request: Request): Map<*, *> {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return parseCanonicalJson(buffer.readUtf8()) as Map<*, *>
    }

    private fun registryArgs(): List<String> {
        val document = parseCanonicalJson(Files.readString(registry)) as Map<*, *>
        val servers = document["agent_servers"] as Map<*, *>
        val entry = servers[AcpHostRegistration.DEFAULT_ENTRY_NAME] as Map<*, *>
        return (entry["args"] as List<*>).map { it as String }
    }

    private fun argAfter(
        args: List<String>,
        flag: String,
    ): String? {
        val index = args.indexOf(flag)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    private fun postToIpc(
        endpoint: String,
        capability: String,
        eventJson: Map<String, Any?>,
    ): Int {
        val body =
            canonicalJson(
                linkedMapOf(
                    "schema_version" to "1",
                    "proxy_digest" to "ab".repeat(32),
                    "emitter_id" to "acp-proxy",
                    "events" to listOf(eventJson),
                ),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header(ResearchSpoolIpcServer.CAPABILITY_HEADER, capability)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            .send(request, HttpResponse.BodyHandlers.ofString())
            .statusCode()
    }

    private fun activatedManager(http: RecordingHttp): Pair<ResearchSessionManager, Pair<String, String>> {
        val manager = manager(http)
        manager.activate("enrollment-1")
        val endpoint = argAfter(registryArgs(), AcpHostRegistration.SPOOL_ENDPOINT_FLAG)!!
        val capability = Files.readString(capabilityFile).trim()
        return manager to (endpoint to capability)
    }

    @Test
    fun `the first proxy-origin event records one exposure receipt`() {
        val http = RecordingHttp()
        val (_, ipc) = activatedManager(http)
        val events = builder(emitterId = "acp-proxy", source = EventSource.ACP, eventIds = IdSequence("evt"))

        val first = events.build(eventType = CanonicalEventTypes.TOOL_STARTED)
        assertEquals(200, postToIpc(ipc.first, ipc.second, first.toCanonicalMap()))

        val receipts = exposureRequests(http)
        assertEquals(1, receipts.size)
        val body = bodyOf(receipts.single())
        assertEquals("enrollment-1", body["enrollment_id"])
        assertEquals("assignment-1", body["assignment_id"])
        assertEquals("STARTED", body["outcome"])
        assertEquals("release-1", body["agent_release_id"])
        assertEquals(VALID_ARTIFACT_DIGEST, body["artifact_digest"])
        assertEquals("codex-v1", body["adapter_version"])
        // A packaged exposure reports the mode and the path-free observed identity.
        assertEquals("PACKAGED", body["distribution_mode"])
        val observed = body["observed_configuration"] as Map<*, *>
        assertEquals("PACKAGED", observed["distribution_mode"])
        assertEquals(VALID_ARTIFACT_DIGEST, observed["observed_agent_digest"])
        assertEquals("1.2.3", observed["observed_agent_version"])
        assertNotNull(body["capability"])
        val environment = body["environment"] as Map<*, *>
        assertEquals("macos", environment["os"])
        assertEquals("aarch64", environment["arch"])
        // The idempotency key is derived, never a raw session/run identifier.
        val key = body["idempotency_key"] as String
        assertFalse(key.contains("session-1"), key)
        assertFalse(key.contains("run-1"), key)

        // A second qualifying event for the same run is idempotent.
        val second = events.build(eventType = CanonicalEventTypes.TOOL_STARTED)
        assertEquals(200, postToIpc(ipc.first, ipc.second, second.toCanonicalMap()))
        assertEquals(1, exposureRequests(http).size, "the receipt is idempotent per session/run")
    }

    private fun byoaManifestJson(): String =
        manifestJson(
            overrides =
                mapOf(
                    "agent_release" to
                        linkedMapOf<String, Any?>(
                            "agent_id" to "goose",
                            "release_id" to "",
                            "distribution_mode" to "BYOA_EXTERNAL",
                            "agent_command" to "goose",
                            "agent_command_args" to listOf("acp"),
                            "agent_package" to "goose",
                        ),
                ),
        )

    @Test
    fun `a BYOA session exposes its distribution mode and observed identity`() {
        val http = RecordingHttp()
        val agent = root.resolve("byoa/goose")
        Files.createDirectories(agent.parent)
        Files.writeString(agent, "goose-binary")
        agent.toFile().setExecutable(true, false)
        val observedDigest = "cd".repeat(32)
        val byoa =
            ByoaAgentResolver {
                ByoaAgentResolution.Resolved(
                    identity =
                        ObservedAgentIdentity(
                            executable = agent,
                            digest = observedDigest,
                            version = "goose 3.1.4",
                            source = AgentDiscoverySource.CONFIGURED,
                        ),
                    argv = listOf(agent.toString()),
                )
            }
        val proxyOnly =
            ResolvedProxyRuntime(
                runtimeRoot = root.resolve("runtime"),
                proxyArgv = listOf(root.resolve("runtime/bin/telemetry-acp-proxy").toString(), "--stdio"),
                proxyDigest = "ab".repeat(32),
            )
        val manager =
            manager(
                http,
                resolver = ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(proxyOnly) },
                manifestOverride = byoaManifestJson(),
                byoaResolver = byoa,
            )
        manager.activate("enrollment-1")
        val endpoint = argAfter(registryArgs(), AcpHostRegistration.SPOOL_ENDPOINT_FLAG)!!
        val capability = Files.readString(capabilityFile).trim()
        val events = builder(emitterId = "acp-proxy", source = EventSource.ACP, eventIds = IdSequence("byoa"))
        val event = events.build(eventType = CanonicalEventTypes.TOOL_STARTED)
        assertEquals(200, postToIpc(endpoint, capability, event.toCanonicalMap()))

        val receipts = exposureRequests(http)
        assertEquals(1, receipts.size)
        val body = bodyOf(receipts.single())
        assertEquals("BYOA_EXTERNAL", body["distribution_mode"])
        val observed = body["observed_configuration"] as Map<*, *>
        assertEquals("BYOA_EXTERNAL", observed["distribution_mode"])
        assertEquals(observedDigest, observed["observed_agent_digest"])
        assertEquals("goose 3.1.4", observed["observed_agent_version"])
        assertFalse(body.toString().contains(agent.toString()), "the local agent path must never be exposed")
    }

    @Test
    fun `a runtime that fails to start records one non-exposure`() {
        val http = RecordingHttp()
        val failing =
            ProxyRuntimeResolver {
                ProxyRuntimeResolution.Failed(ProxyRuntimeError(ProxyRuntimeErrorCode.ARTIFACT_MISSING, "missing"))
            }
        val manager = manager(http, resolver = failing)

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.RUNTIME_UNAVAILABLE, (result as ResearchActivationResult.Blocked).reason)
        val receipts = exposureRequests(http)
        assertEquals(1, receipts.size)
        assertEquals("RUNTIME_UNAVAILABLE", bodyOf(receipts.single())["outcome"])
    }

    @Test
    fun `no exposure is sent before a validated manifest exists`() {
        val http = RecordingHttp()
        val manager = manager(http, transport = BootstrapTransport { _, _ -> BootstrapTransportResult.Revoked("withdrawn") })

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(0, exposureRequests(http).size)
    }

    @Test
    fun `a failing receipt is retried on later events but bounded`() {
        val http = RecordingHttp(statusProvider = { 500 })
        val (_, ipc) = activatedManager(http)
        val events = builder(emitterId = "acp-proxy", source = EventSource.ACP, eventIds = IdSequence("evt"))

        repeat(5) {
            val event = events.build(eventType = CanonicalEventTypes.TOOL_STARTED)
            assertEquals(200, postToIpc(ipc.first, ipc.second, event.toCanonicalMap()))
        }

        assertEquals(3, exposureRequests(http).size, "retries are bounded")
    }
}

// --------------------------------------------------------------------------
// ResearchSessionMaintenanceTest.kt
// --------------------------------------------------------------------------

/**
 * Server-session lifecycle wiring.
 *
 * Activation must open (or reuse) the server session through
 * `POST /api/research/sessions/` and then heartbeat it at the server-declared
 * cadence. Before the short-lived capability expires, maintenance must
 * re-bootstrap and hand the fresh capability to the already-running uploader so
 * delivery resumes without an IDE restart. Every transport failure is swallowed
 * and a terminal server answer stops the runtime for good.
 */
class ResearchSessionMaintenanceTest {
    private lateinit var root: Path

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("research-session-maintenance")
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** A scheduler that records every cadence and exposes the latest task. */
    private class FakeScheduler : MaintenanceScheduler {
        val scheduled = ArrayList<Pair<Long, () -> Unit>>()
        val cancelled = ArrayList<Int>()

        override fun schedule(
            periodMs: Long,
            task: () -> Unit,
        ): MaintenanceHandle {
            val index = scheduled.size
            scheduled.add(periodMs to task)
            return MaintenanceHandle { cancelled.add(index) }
        }

        val periods: List<Long>
            get() = scheduled.map { it.first }

        fun latestTask(): (() -> Unit)? = scheduled.lastOrNull()?.second
    }

    /** A delivery whose capability adoption and revoked state are observable. */
    private class FakeDelivery(
        @Volatile var revoked: Boolean = false,
    ) : SpoolDelivery {
        val adoptedCapabilities = ArrayList<Map<String, Any?>>()
        var started = false
        var closed = false

        override fun start() {
            started = true
        }

        override fun close() {
            closed = true
        }

        override val isRunning: Boolean
            get() = started && !closed

        override fun updateCapability(capability: Map<String, Any?>): Boolean {
            adoptedCapabilities.add(capability)
            if (capability.isEmpty()) return false
            revoked = false
            return true
        }

        override fun state(): SpoolUploaderState = SpoolUploaderState(revoked = revoked)
    }

    private class SequenceTransport(private val manifests: List<String>) : BootstrapTransport {
        val fetches = AtomicInteger()
        val requestedEnrollments = ArrayList<String>()

        override fun fetch(enrollmentId: String, contextId: String): BootstrapTransportResult {
            requestedEnrollments.add(enrollmentId)
            val index = fetches.getAndIncrement().coerceAtMost(manifests.size - 1)
            return BootstrapTransportResult.Success(manifests[index])
        }
    }

    private class MaintenanceHttp(
        private val responder: (Request) -> Response,
    ) : Call.Factory {
        val requests = CopyOnWriteArrayList<Request>()

        override fun newCall(request: Request): Call = MaintenanceCall(request, responder, requests)

        private class MaintenanceCall(
            private val request: Request,
            private val responder: (Request) -> Response,
            private val requests: MutableList<Request>,
        ) : Call {
            override fun request(): Request = request

            override fun execute(): Response {
                requests.add(request)
                return responder(request)
            }

            override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException("async not used")

            override fun cancel() = Unit

            override fun isExecuted(): Boolean = false

            override fun isCanceled(): Boolean = false

            override fun timeout(): Timeout = Timeout.NONE

            override fun clone(): Call = MaintenanceCall(request, responder, requests)
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun jsonResponse(
        request: Request,
        code: Int,
        body: String,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun createBody(heartbeatSeconds: Long): String =
        canonicalJson(
            linkedMapOf(
                "created" to true,
                "session" to linkedMapOf("state" to "not_started"),
                "next_actions" to listOf("report_activity"),
                "heartbeat_seconds" to heartbeatSeconds,
            ),
        )

    private fun heartbeatBody(
        heartbeatSeconds: Long,
        state: String = "running",
    ): String =
        canonicalJson(
            linkedMapOf(
                "session" to linkedMapOf("state" to state),
                "next_actions" to listOf("heartbeat"),
                "heartbeat_seconds" to heartbeatSeconds,
            ),
        )

    private fun terminalBody(code: String): String =
        canonicalJson(
            linkedMapOf(
                "detail" to linkedMapOf("code" to code, "message" to "terminal"),
            ),
        )

    private fun sessionsHttp(heartbeatSeconds: () -> Long): MaintenanceHttp =
        MaintenanceHttp { request ->
            if (request.url.encodedPath.endsWith("/heartbeat")) {
                jsonResponse(request, 200, heartbeatBody(heartbeatSeconds()))
            } else {
                jsonResponse(request, 201, createBody(heartbeatSeconds()))
            }
        }

    private fun manifestWithCapability(
        capabilityId: String,
        expiresAt: String,
        sessionId: String,
        manifestExpiresAt: String? = null,
    ): String =
        manifestJson(
            researchSessionId = sessionId,
            overrides =
                buildMap {
                    put(
                        "session_capability",
                        linkedMapOf<String, Any?>(
                            "capability_id" to capabilityId,
                            "audience" to "research-runtime",
                            "scope" to listOf("telemetry:write", "session:heartbeat", "session:close"),
                            "issued_at" to "2026-01-01T00:00:00Z",
                            "expires_at" to expiresAt,
                        ),
                    )
                    manifestExpiresAt?.let { put("expires_at", it) }
                },
        )

    private fun bodyOf(request: Request): Map<*, *> {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return parseCanonicalJson(buffer.readUtf8()) as Map<*, *>
    }

    /** A transport whose single manifest carries a capability valid for hours. */
    private fun singleManifestTransport(): BootstrapTransport =
        SequenceTransport(listOf(manifestWithCapability("capability-1", "2026-01-01T01:00:00Z", "session-1")))

    private fun manager(
        http: Call.Factory,
        transport: BootstrapTransport,
        delivery: SpoolDelivery,
        scheduler: MaintenanceScheduler,
        discovery: (() -> EnrollmentDiscovery)? = null,
    ): ResearchSessionManager =
        ResearchSessionManager(
            projectKey = "project-under-test",
            transport = transport,
            compatibility = compatibility(),
            spoolProvider = { DurableSpool(root.resolve("spool")) },
            serverBaseUrlProvider = { "http://localhost:8008" },
            httpClient = http,
            uploaderFactory = { delivery },
            maintenanceScheduler = scheduler,
            sessionStore = InMemoryResearchSessionStore(),
            clock = { VALID_NOW.toEpochMilli() },
            instantClock = { VALID_NOW },
            sessionIdFactory = { "session-1" },
            runIdFactory = { "run-1" },
            enrollmentDiscoveryProvider = discovery,
        )

    // ------------------------------------------------------------------
    // Activation establishes the server session and cadence
    // ------------------------------------------------------------------

    @Test
    fun `activation opens the server session and schedules heartbeats at the server cadence`() {
        val http = sessionsHttp { 30L }
        val scheduler = FakeScheduler()
        val manager = manager(http, singleManifestTransport(), FakeDelivery(), scheduler)

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Activated)
        val paths = http.requests.map { it.url.encodedPath }
        assertTrue(paths.contains("/api/research/sessions/"), paths.toString())
        // The session opens NOT_STARTED, so a report-activity heartbeat moves it
        // to RUNNING immediately.
        assertTrue(paths.contains("/api/research/sessions/heartbeat"), paths.toString())
        val create = http.requests.first { it.url.encodedPath == "/api/research/sessions/" }
        val createBody = bodyOf(create)
        assertEquals("enrollment-1", createBody["enrollment_id"])
        assertEquals("study-1", createBody["study_id"])
        assertEquals(30_000L, scheduler.periods.single())
        assertNotNull(scheduler.latestTask())
    }

    @Test
    fun `heartbeats run at the cadence the server returns and follow a cadence change`() {
        val seconds = AtomicLong(30L)
        val http = sessionsHttp { seconds.get() }
        val scheduler = FakeScheduler()
        val manager = manager(http, singleManifestTransport(), FakeDelivery(), scheduler)
        manager.activate("enrollment-1")
        val heartbeatsBefore = http.requests.count { it.url.encodedPath.endsWith("/heartbeat") }

        seconds.set(45L)
        scheduler.latestTask()?.invoke()

        assertTrue(http.requests.count { it.url.encodedPath.endsWith("/heartbeat") } > heartbeatsBefore)
        assertEquals(listOf(30_000L, 45_000L), scheduler.periods, "a server cadence change must reschedule the loop")
    }

    // ------------------------------------------------------------------
    // Capability refresh before expiry
    // ------------------------------------------------------------------

    @Test
    fun `near-expiry maintenance re-bootstraps and the running uploader adopts the fresh capability`() {
        val http = sessionsHttp { 30L }
        val scheduler = FakeScheduler()
        val delivery = FakeDelivery(revoked = true)
        val transport =
            SequenceTransport(
                listOf(
                    manifestWithCapability("capability-1", "2026-01-01T00:31:00Z", "session-1"),
                    manifestWithCapability("capability-2", "2026-01-01T02:00:00Z", "session-1"),
                ),
            )
        val manager = manager(http, transport, delivery, scheduler)
        assertTrue(manager.activate("enrollment-1") is ResearchActivationResult.Activated)
        assertEquals(SpoolDeliveryState.REVOKED, manager.state().deliveryState)

        val maintenance = manager.performMaintenance()

        assertTrue(maintenance is ResearchMaintenanceResult.Maintained)
        assertTrue((maintenance as ResearchMaintenanceResult.Maintained).capabilityRefreshed)
        assertEquals(2, transport.fetches.get(), "the re-bootstrap must run before the capability expires")
        val adopted = delivery.adoptedCapabilities.single()
        assertEquals("capability-2", adopted["capability_id"])
        assertFalse(delivery.revoked, "adopting a fresh capability must clear the revoked state")
        assertEquals(SpoolDeliveryState.ACTIVE, manager.state().deliveryState)
    }

    // ------------------------------------------------------------------
    // Fail-soft
    // ------------------------------------------------------------------

    @Test
    fun `session transport failures are swallowed and leave local state intact`() {
        val http = MaintenanceHttp { throw IOException("backend down") }
        val scheduler = FakeScheduler()
        val delivery = FakeDelivery()
        val manager = manager(http, singleManifestTransport(), delivery, scheduler)

        val activation = assertDoesNotThrow<ResearchActivationResult> { manager.activate("enrollment-1") }

        assertTrue(activation is ResearchActivationResult.Activated)
        assertTrue(manager.isActive)
        assertEquals(30_000L, scheduler.periods.single(), "the manifest cadence still schedules maintenance")

        val maintenance = assertDoesNotThrow<ResearchMaintenanceResult> { manager.performMaintenance() }

        assertTrue(maintenance is ResearchMaintenanceResult.Retryable)
        assertTrue(manager.isActive)
        assertFalse(manager.currentSession?.isTerminal ?: true)
        assertFalse(delivery.closed)
        assertTrue(delivery.adoptedCapabilities.isEmpty(), "a failed tick must not push a bogus capability")
    }

    @Test
    fun `an expired-capability heartbeat triggers a refresh instead of a terminal stop`() {
        var expired = false
        val http =
            MaintenanceHttp { request ->
                when {
                    request.url.encodedPath.endsWith("/heartbeat") && expired ->
                        jsonResponse(request, 403, terminalBody("CAPABILITY_INVALID"))
                    request.url.encodedPath.endsWith("/heartbeat") ->
                        jsonResponse(request, 200, heartbeatBody(30L))
                    else ->
                        jsonResponse(request, 201, createBody(30L))
                }
            }
        val scheduler = FakeScheduler()
        val delivery = FakeDelivery()
        val transport =
            SequenceTransport(
                listOf(
                    manifestWithCapability("capability-1", "2026-01-01T01:00:00Z", "session-1"),
                    manifestWithCapability("capability-2", "2026-01-01T02:00:00Z", "session-1"),
                ),
            )
        val manager = manager(http, transport, delivery, scheduler)
        manager.activate("enrollment-1")
        expired = true

        val maintenance = manager.performMaintenance()

        assertTrue(maintenance is ResearchMaintenanceResult.Maintained)
        assertEquals("capability-2", delivery.adoptedCapabilities.single()["capability_id"])
        assertTrue(manager.isActive, "an expired capability is recoverable, not terminal")
    }

    // ------------------------------------------------------------------
    // Terminal semantics
    // ------------------------------------------------------------------

    @Test
    fun `a terminal heartbeat ends the session and is never resurrected`() {
        var terminal = false
        val http =
            MaintenanceHttp { request ->
                when {
                    request.url.encodedPath.endsWith("/heartbeat") && terminal ->
                        jsonResponse(request, 409, terminalBody("SESSION_TERMINAL"))
                    request.url.encodedPath.endsWith("/heartbeat") ->
                        jsonResponse(request, 200, heartbeatBody(30L))
                    else ->
                        jsonResponse(request, 201, createBody(30L))
                }
            }
        val scheduler = FakeScheduler()
        val delivery = FakeDelivery()
        val transport = SequenceTransport(listOf(manifestWithCapability("capability-1", "2026-01-01T01:00:00Z", "session-1")))
        val manager = manager(http, transport, delivery, scheduler)
        manager.activate("enrollment-1")
        terminal = true

        val maintenance = assertDoesNotThrow<ResearchMaintenanceResult> { manager.performMaintenance() }

        assertTrue(maintenance is ResearchMaintenanceResult.Ended)
        assertEquals(StudyBlockReason.SESSION_ENDED, (maintenance as ResearchMaintenanceResult.Ended).reason)
        assertFalse(manager.isActive)
        assertEquals(SessionState.ENDED, manager.currentSession?.state)
        assertEquals(StudyBlockReason.SESSION_ENDED, manager.state().blockReason)
        assertTrue(scheduler.cancelled.isNotEmpty(), "the maintenance loop must stop")
    }

    @Test
    fun `a server revocation stops delivery and never resurrects the session`() {
        var revoked = false
        val http =
            MaintenanceHttp { request ->
                when {
                    request.url.encodedPath.endsWith("/heartbeat") && revoked ->
                        jsonResponse(request, 403, terminalBody("REVOKED"))
                    request.url.encodedPath.endsWith("/heartbeat") ->
                        jsonResponse(request, 200, heartbeatBody(30L))
                    else ->
                        jsonResponse(request, 201, createBody(30L))
                }
            }
        val scheduler = FakeScheduler()
        val delivery = FakeDelivery()
        val transport = SequenceTransport(listOf(manifestWithCapability("capability-1", "2026-01-01T01:00:00Z", "session-1")))
        val manager = manager(http, transport, delivery, scheduler)
        manager.activate("enrollment-1")
        revoked = true

        val maintenance = assertDoesNotThrow<ResearchMaintenanceResult> { manager.performMaintenance() }

        assertTrue(maintenance is ResearchMaintenanceResult.Ended)
        assertEquals(StudyBlockReason.REVOKED, (maintenance as ResearchMaintenanceResult.Ended).reason)
        assertFalse(manager.isActive)
        assertEquals(SessionState.REVOKED, manager.currentSession?.state)
        assertTrue(delivery.closed, "revocation must tear the uploader down")
        assertTrue(scheduler.cancelled.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // Enrollment discovery gates activation (plan 05.5 step 1)
    // ------------------------------------------------------------------

    @Test
    fun `a stopped-study discovery blocks activation before any bootstrap or session request`() {
        val http = sessionsHttp { 30L }
        val scheduler = FakeScheduler()
        val transport =
            SequenceTransport(listOf(manifestWithCapability("capability-1", "2026-01-01T01:00:00Z", "session-1")))
        val manager =
            manager(http, transport, FakeDelivery(), scheduler, discovery = { EnrollmentDiscovery.Terminal("STUDY_STOPPED") })

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.REVOKED, (result as ResearchActivationResult.Blocked).reason)
        assertTrue(result.detail?.contains("study_stopped") == true, result.detail)
        assertEquals(0, transport.fetches.get(), "a terminal enrollment must never reach bootstrap")
        assertTrue(http.requests.isEmpty(), "a terminal enrollment must never reach the session endpoints")
        assertFalse(manager.isActive)
        assertEquals(StudyBlockReason.REVOKED, manager.state().blockReason)
    }

    @Test
    fun `a revoked discovery blocks activation before any bootstrap or session request`() {
        val http = sessionsHttp { 30L }
        val scheduler = FakeScheduler()
        val transport =
            SequenceTransport(listOf(manifestWithCapability("capability-1", "2026-01-01T01:00:00Z", "session-1")))
        val manager =
            manager(http, transport, FakeDelivery(), scheduler, discovery = { EnrollmentDiscovery.Terminal("REVOKED") })

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.REVOKED, (result as ResearchActivationResult.Blocked).reason)
        assertEquals(0, transport.fetches.get(), "a terminal enrollment must never reach bootstrap")
        assertTrue(http.requests.isEmpty(), "a terminal enrollment must never reach the session endpoints")
        assertFalse(manager.isActive)
    }

    @Test
    fun `an active discovery proceeds to bootstrap and the session endpoints`() {
        val http = sessionsHttp { 30L }
        val scheduler = FakeScheduler()
        val transport =
            SequenceTransport(listOf(manifestWithCapability("capability-1", "2026-01-01T01:00:00Z", "session-1")))
        val manager =
            manager(http, transport, FakeDelivery(), scheduler, discovery = { EnrollmentDiscovery.Active("enrollment-1") })

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Activated)
        assertEquals(1, transport.fetches.get())
        val paths = http.requests.map { it.url.encodedPath }
        assertTrue(paths.contains("/api/research/sessions/"), paths.toString())
        assertTrue(paths.contains("/api/research/sessions/heartbeat"), paths.toString())
    }

    @Test
    fun `an unavailable discovery fails soft to the bootstrap authority`() {
        val http = sessionsHttp { 30L }
        val scheduler = FakeScheduler()
        val transport =
            SequenceTransport(listOf(manifestWithCapability("capability-1", "2026-01-01T01:00:00Z", "session-1")))
        val manager =
            manager(http, transport, FakeDelivery(), scheduler, discovery = { EnrollmentDiscovery.Unavailable("backend down") })

        val result = manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Activated, "discovery must never block when the server cannot be reached")
        assertEquals(1, transport.fetches.get())
    }

    // ------------------------------------------------------------------
    // Terminal study and refresh-failure maintenance
    // ------------------------------------------------------------------

    @Test
    fun `a stopped-study heartbeat ends the session as revoked and never resurrects it`() {
        var stopped = false
        val http =
            MaintenanceHttp { request ->
                when {
                    request.url.encodedPath.endsWith("/heartbeat") && stopped ->
                        jsonResponse(request, 409, terminalBody("STUDY_STOPPED"))
                    request.url.encodedPath.endsWith("/heartbeat") ->
                        jsonResponse(request, 200, heartbeatBody(30L))
                    else ->
                        jsonResponse(request, 201, createBody(30L))
                }
            }
        val scheduler = FakeScheduler()
        val delivery = FakeDelivery()
        val transport = SequenceTransport(listOf(manifestWithCapability("capability-1", "2026-01-01T01:00:00Z", "session-1")))
        val manager = manager(http, transport, delivery, scheduler)
        manager.activate("enrollment-1")
        val sessionId = manager.currentSession?.sessionId
        assertNotNull(sessionId)
        stopped = true

        val maintenance = assertDoesNotThrow<ResearchMaintenanceResult> { manager.performMaintenance() }

        assertTrue(maintenance is ResearchMaintenanceResult.Ended)
        assertEquals(StudyBlockReason.REVOKED, (maintenance as ResearchMaintenanceResult.Ended).reason)
        val heartbeat = http.requests.last { it.url.encodedPath.endsWith("/heartbeat") }
        assertEquals("POST", heartbeat.method)
        assertEquals("/api/research/sessions/heartbeat", heartbeat.url.encodedPath)
        assertEquals(sessionId, bodyOf(heartbeat)["research_session_id"])
        assertFalse(manager.isActive)
        assertEquals(SessionState.REVOKED, manager.currentSession?.state)
        assertTrue(delivery.closed, "a stopped study must tear the uploader down")
        assertTrue(scheduler.cancelled.isNotEmpty())
    }

    @Test
    fun `an expired capability whose refresh fails is retryable and keeps the session`() {
        val fetches = AtomicInteger()
        val transport =
            BootstrapTransport { _, _ ->
                if (fetches.getAndIncrement() == 0) {
                    BootstrapTransportResult.Success(manifestWithCapability("capability-1", "2026-01-01T01:00:00Z", "session-1"))
                } else {
                    BootstrapTransportResult.Failure("backend down", retryable = true)
                }
            }
        var expired = false
        val http =
            MaintenanceHttp { request ->
                when {
                    request.url.encodedPath.endsWith("/heartbeat") && expired ->
                        jsonResponse(request, 401, terminalBody("CAPABILITY_INVALID"))
                    request.url.encodedPath.endsWith("/heartbeat") ->
                        jsonResponse(request, 200, heartbeatBody(30L))
                    else ->
                        jsonResponse(request, 201, createBody(30L))
                }
            }
        val scheduler = FakeScheduler()
        val delivery = FakeDelivery()
        val manager = manager(http, transport, delivery, scheduler)
        manager.activate("enrollment-1")
        expired = true

        val maintenance = assertDoesNotThrow<ResearchMaintenanceResult> { manager.performMaintenance() }

        assertTrue(maintenance is ResearchMaintenanceResult.Retryable, "a failed re-bootstrap must not end the session")
        assertEquals(2, fetches.get(), "the stale capability must trigger exactly one forced refresh")
        assertTrue(delivery.adoptedCapabilities.isEmpty(), "a failed refresh must not push a bogus capability")
        assertTrue(manager.isActive)
        assertFalse(manager.currentSession?.isTerminal ?: true)
        assertFalse(delivery.closed)
        val heartbeat = http.requests.last { it.url.encodedPath.endsWith("/heartbeat") }
        assertEquals("/api/research/sessions/heartbeat", heartbeat.url.encodedPath)
        assertEquals("session-1", bodyOf(heartbeat)["research_session_id"])
    }

    @Test
    fun `a malformed refresh manifest ends maintenance without adopting anything`() {
        val http = sessionsHttp { 30L }
        val scheduler = FakeScheduler()
        val delivery = FakeDelivery()
        val transport =
            SequenceTransport(
                listOf(
                    manifestWithCapability("capability-1", "2026-01-01T00:31:00Z", "session-1"),
                    "{not json",
                ),
            )
        val manager = manager(http, transport, delivery, scheduler)
        assertTrue(manager.activate("enrollment-1") is ResearchActivationResult.Activated)

        val maintenance = assertDoesNotThrow<ResearchMaintenanceResult> { manager.performMaintenance() }

        assertTrue(maintenance is ResearchMaintenanceResult.Ended)
        assertEquals(StudyBlockReason.MANIFEST_INVALID, (maintenance as ResearchMaintenanceResult.Ended).reason)
        assertEquals(2, transport.fetches.get(), "the near-expiry capability must trigger a re-bootstrap")
        assertTrue(delivery.adoptedCapabilities.isEmpty(), "a malformed manifest must never reach the uploader")
        assertFalse(manager.isActive)
        assertTrue(delivery.closed)
    }

    @Test
    fun `an expired refresh manifest is not adopted and the session stays alive`() {
        val http = sessionsHttp { 30L }
        val scheduler = FakeScheduler()
        val delivery = FakeDelivery()
        val transport =
            SequenceTransport(
                listOf(
                    manifestWithCapability("capability-1", "2026-01-01T00:31:00Z", "session-1"),
                    manifestWithCapability("capability-2", "2026-01-01T00:10:00Z", "session-2", manifestExpiresAt = "2026-01-01T00:10:00Z"),
                ),
            )
        val manager = manager(http, transport, delivery, scheduler)
        assertTrue(manager.activate("enrollment-1") is ResearchActivationResult.Activated)

        val maintenance = assertDoesNotThrow<ResearchMaintenanceResult> { manager.performMaintenance() }

        assertTrue(maintenance is ResearchMaintenanceResult.Maintained)
        assertFalse((maintenance as ResearchMaintenanceResult.Maintained).capabilityRefreshed)
        assertEquals(2, transport.fetches.get(), "the near-expiry capability must trigger a re-bootstrap")
        assertTrue(delivery.adoptedCapabilities.isEmpty(), "an expired manifest must never reach the uploader")
        assertTrue(manager.isActive)
    }
}

// --------------------------------------------------------------------------
// ResearchSessionBootstrapSessionTelemetryTest.kt
// --------------------------------------------------------------------------

/**
 * Post-enrollment end-to-end lifecycle over recording fakes (no real ACP host).
 *
 * A pre-enrolled active participant bootstraps, opens (or reuses) the server
 * session, heartbeats it, and delivers one spooled telemetry batch that the
 * fake server accepts. Every hop asserts its exact method/path/body plus the
 * resulting state transition.
 */
class ResearchSessionBootstrapSessionTelemetryTest {
    private lateinit var root: Path

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("research-session-e2e")
    }

    private class FakeSource : IdeActivitySource {
        private var callback: ((IdeActivitySignal) -> Unit)? = null

        override fun onActivity(callback: (IdeActivitySignal) -> Unit) {
            this.callback = callback
        }

        fun push(signal: IdeActivitySignal) {
            callback?.let { runCatching { it(signal) } }
        }
    }

    private class RecordingHttp(
        private val responder: (Request) -> Response,
    ) : Call.Factory {
        val requests = CopyOnWriteArrayList<Request>()

        override fun newCall(request: Request): Call = RecordingCall(request, responder, requests)

        private class RecordingCall(
            private val request: Request,
            private val responder: (Request) -> Response,
            private val requests: MutableList<Request>,
        ) : Call {
            override fun request(): Request = request

            override fun execute(): Response {
                requests.add(request)
                return responder(request)
            }

            override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException("async not used")

            override fun cancel() = Unit

            override fun isExecuted(): Boolean = false

            override fun isCanceled(): Boolean = false

            override fun timeout(): Timeout = Timeout.NONE

            override fun clone(): Call = RecordingCall(request, responder, requests)
        }
    }

    private fun jsonResponse(
        request: Request,
        code: Int,
        body: String,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun bodyOf(request: Request): Map<*, *> {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return parseCanonicalJson(buffer.readUtf8()) as Map<*, *>
    }

    private fun acceptAllAck(request: Request): Response {
        val body = bodyOf(request)
        val ids =
            (body["events"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("event_id") as? String }
                ?: emptyList()
        return jsonResponse(
            request,
            200,
            canonicalJson(
                linkedMapOf(
                    "receipt_id" to "receipt-e2e",
                    "server_time" to "2026-01-01T00:35:00Z",
                    "accepted" to ids,
                    "duplicate" to emptyList<String>(),
                    "rejected" to emptyList<String>(),
                    "retryable" to emptyList<String>(),
                ),
            ),
        )
    }

    private fun manager(
        http: Call.Factory,
        source: IdeActivitySource,
        spool: DurableSpool,
        bootstrapCalls: MutableList<Pair<String, String>> = mutableListOf(),
    ): ResearchSessionManager =
        ResearchSessionManager(
            projectKey = "project-e2e",
            transport =
                BootstrapTransport { enrollmentId, contextId ->
                    bootstrapCalls.add(enrollmentId to contextId)
                    BootstrapTransportResult.Success(manifestJson(researchSessionId = "session-e2e"))
                },
            compatibility = compatibility(),
            spoolProvider = { spool },
            source = source,
            serverBaseUrlProvider = { "http://127.0.0.1:9" },
            httpClient = http,
            sessionStore = InMemoryResearchSessionStore(),
            clock = { VALID_NOW.toEpochMilli() },
            instantClock = { VALID_NOW },
            enrollmentDiscoveryProvider = { EnrollmentDiscovery.Active("enrollment-1") },
        )

    @Test
    fun `an enrolled participant bootstraps, opens, heartbeats and delivers telemetry`() {
        val source = FakeSource()
        val spool = DurableSpool(root.resolve("spool-e2e"))
        val http =
            RecordingHttp { request ->
                when {
                    request.url.encodedPath == "/api/research/telemetry/batches" -> acceptAllAck(request)
                    request.url.encodedPath.endsWith("/heartbeat") ->
                        jsonResponse(
                            request,
                            200,
                            canonicalJson(
                                linkedMapOf(
                                    "session" to linkedMapOf("state" to "running"),
                                    "next_actions" to listOf("heartbeat"),
                                    "heartbeat_seconds" to 30L,
                                ),
                            ),
                        )
                    else ->
                        jsonResponse(
                            request,
                            201,
                            canonicalJson(
                                linkedMapOf(
                                    "created" to true,
                                    "session" to linkedMapOf("state" to "not_started"),
                                    "next_actions" to listOf("report_activity"),
                                    "heartbeat_seconds" to 30L,
                                ),
                            ),
                        )
                }
            }
        val bootstrapCalls = mutableListOf<Pair<String, String>>()
        val manager = manager(http, source, spool, bootstrapCalls)
        try {
            val activation = manager.activate("enrollment-1")

            assertTrue(activation is ResearchActivationResult.Activated)
            assertEquals("session-e2e", (activation as ResearchActivationResult.Activated).sessionId)
            assertTrue(activation.manifestDigest.isNotBlank())
            assertTrue(manager.isActive)

            // Bootstrap request shape: the enrollment is fetched exactly once for
            // this project's opaque execution context, and nothing revision-shaped
            // rides along in the request identity.
            assertEquals(listOf("enrollment-1" to ResearchSessionManager.opaqueContextId("project-e2e")), bootstrapCalls)

            val create = http.requests.single { it.url.encodedPath == "/api/research/sessions/" }
            assertEquals("POST", create.method)
            val createBody = bodyOf(create)
            assertEquals("enrollment-1", createBody["enrollment_id"])
            assertEquals("study-1", createBody["study_id"])
            assertFalse(createBody.containsKey("revision_id"), createBody.keys.toString())
            assertFalse(createBody.containsKey("study_revision_id"), createBody.keys.toString())
            assertTrue((createBody["capability"] as? Map<*, *>)?.isNotEmpty() == true)

            val heartbeat = http.requests.first { it.url.encodedPath == "/api/research/sessions/heartbeat" }
            assertEquals("POST", heartbeat.method)
            assertEquals("session-e2e", bodyOf(heartbeat)["research_session_id"])

            source.push(
                IdeActivitySignal(
                    kind = "opened",
                    projectKey = "project-e2e",
                    metadata = mapOf("file_extension" to "kt"),
                ),
            )
            assertEquals(SessionState.RUNNING, manager.currentSession?.state)

            manager.flushBounded(5_000L)

            val batches = http.requests.filter { it.url.encodedPath == "/api/research/telemetry/batches" }
            assertTrue(batches.isNotEmpty(), "the spooled event must be delivered")
            val batchBody = bodyOf(batches.first())
            assertEquals("POST", batches.first().method)
            val events = batchBody["events"] as? List<*> ?: emptyList<Any>()
            assertTrue(events.isNotEmpty())
            assertTrue((batchBody["session_capability"] as? Map<*, *>)?.isNotEmpty() == true)
            // Telemetry provenance is study/enrollment/session identity only.
            val firstEvent = events.first() as? Map<*, *> ?: emptyMap<Any, Any>()
            assertFalse(firstEvent.containsKey("revision_id"), firstEvent.keys.toString())
            assertFalse(firstEvent.containsKey("study_revision_id"), firstEvent.keys.toString())
            assertEquals("study-1", firstEvent["study_id"])
            assertEquals("enrollment-1", firstEvent["enrollment_id"])
            assertEquals("session-e2e", firstEvent["research_session_id"])
            assertTrue(spool.pending().isEmpty(), "the accepted batch must drain the spool")

            val maintenance = manager.performMaintenance()

            assertTrue(maintenance is ResearchMaintenanceResult.Maintained)
            assertTrue(manager.isActive)
            assertEquals(SessionState.RUNNING, manager.currentSession?.state)
        } finally {
            manager.stop()
        }
    }
}
