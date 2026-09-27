package me.code4me.research.session

import com.intellij.openapi.project.Project
import me.code4me.research.IdSequence
import me.code4me.research.actions.ResearchEnrollmentSettings
import me.code4me.research.bootstrap.BootstrapRejection
import me.code4me.research.bootstrap.BootstrapTransport
import me.code4me.research.bootstrap.BootstrapTransportResult
import me.code4me.research.bootstrap.EnrollmentDiscovery
import me.code4me.research.bootstrap.VALID_ARTIFACT_DIGEST
import me.code4me.research.bootstrap.VALID_NOW
import me.code4me.research.bootstrap.compatibility
import me.code4me.research.bootstrap.manifestJson
import me.code4me.research.builder
import me.code4me.research.ide.IdeActivitySignal
import me.code4me.research.ide.IdeActivitySource
import me.code4me.research.lifecycle.HostPreflightResult
import me.code4me.research.proxy.AcpHostRegistration
import me.code4me.research.proxy.PackagedAgentInstall
import me.code4me.research.proxy.PackagedAgentInstaller
import me.code4me.research.proxy.ProxyRuntimeResolution
import me.code4me.research.proxy.ProxyRuntimeResolver
import me.code4me.research.proxy.ResolvedProxyRuntime
import me.code4me.research.spool.DurableSpool
import me.code4me.research.spool.ResearchSpoolIpcServer
import me.code4me.research.spool.SpoolDelivery
import me.code4me.research.spool.SpoolEventContext
import me.code4me.research.spool.SpoolIpcServer
import me.code4me.research.spool.SpoolUploadResult
import me.code4me.research.spool.SpoolUploaderState
import me.code4me.research.telemetry.CanonicalEvent
import me.code4me.research.telemetry.CanonicalEventTypes
import me.code4me.research.telemetry.EventSource
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.parseCanonicalJson
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockito.kotlin.mock
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Lifecycle blockers of the 2026-09-26 production-readiness review, D-01..D-04
 * and A-03: a participant never has to act to keep collecting.
 *
 * Every test runs the full runtime seam over recording fakes: a real
 * [DurableSpool], a real ACP registry document, a fake IPC server whose
 * rebinds are observable, a fake delivery whose lifecycle is observable, a
 * scripted bootstrap transport and a recording session HTTP client.
 */
class SessionRotationTest {
    private lateinit var root: Path
    private lateinit var registry: Path
    private lateinit var runtimeRoot: Path
    private val runs = AtomicInteger()

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("research-session-rotation")
        registry = root.resolve("acp.json")
        runtimeRoot = root.resolve("runtime-root")
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

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
    }

    /** A delivery whose adoption, drain and close order are observable. */
    private class FakeDelivery(
        @Volatile var revoked: Boolean = false,
        private val discardedCount: Int = 0,
    ) : SpoolDelivery {
        val adoptedCapabilities = CopyOnWriteArrayList<Map<String, Any?>>()
        val lifecycle = CopyOnWriteArrayList<String>()

        @Volatile var started = false

        @Volatile var closed = false

        override fun start() {
            started = true
            lifecycle += "start"
        }

        override fun close() {
            closed = true
            lifecycle += "close"
        }

        override val isRunning: Boolean
            get() = started && !closed

        override fun updateCapability(capability: Map<String, Any?>): Boolean {
            adoptedCapabilities.add(capability)
            if (capability.isEmpty()) return false
            revoked = false
            return true
        }

        override fun drainOnce(): SpoolUploadResult? {
            lifecycle += "drain"
            return SpoolUploadResult(attempted = true)
        }

        override fun state(): SpoolUploaderState = SpoolUploaderState(revoked = revoked, discardedCount = discardedCount)
    }

    private class FakeIpcServer(
        override val capability: String = "ipc-capability",
    ) : SpoolIpcServer {
        override val endpointUrl: String = "http://127.0.0.1:1/spool"
        val rebinds = CopyOnWriteArrayList<SpoolEventContext>()

        @Volatile var closed = false

        override fun rebind(context: SpoolEventContext) {
            rebinds += context
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeIdeSource : IdeActivitySource {
        private var callback: ((IdeActivitySignal) -> Unit)? = null

        override fun onActivity(callback: (IdeActivitySignal) -> Unit) {
            this.callback = callback
        }

        fun push(signal: IdeActivitySignal) {
            callback?.let { runCatching { it(signal) } }
        }
    }

    /** Answers the scripted results in order and then repeats the last one; more steps may be appended. */
    private class ScriptedTransport(vararg script: BootstrapTransportResult) : BootstrapTransport {
        val steps = ArrayDeque(script.toList())
        private var last: BootstrapTransportResult = script.last()
        val fetches = AtomicInteger()

        override fun fetch(
            enrollmentId: String,
            contextId: String,
        ): BootstrapTransportResult {
            fetches.incrementAndGet()
            val next = steps.removeFirstOrNull() ?: return last
            last = next
            return next
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

    private fun createBody(): String =
        canonicalJson(
            linkedMapOf(
                "created" to true,
                "session" to linkedMapOf("state" to "not_started"),
                "next_actions" to listOf("report_activity"),
                "heartbeat_seconds" to 30L,
            ),
        )

    private fun heartbeatBody(state: String = "running"): String =
        canonicalJson(
            linkedMapOf(
                "session" to linkedMapOf("state" to state),
                "next_actions" to listOf("heartbeat"),
                "heartbeat_seconds" to 30L,
            ),
        )

    private fun terminalBody(code: String): String =
        canonicalJson(linkedMapOf("detail" to linkedMapOf("code" to code, "message" to "typed answer")))

    /**
     * The ordinary happy server: create 201 NOT_STARTED, heartbeat/activity 200
     * RUNNING. [override] may answer a request first (a scripted refusal).
     */
    private fun sessionsResponder(override: (Request) -> Response? = { null }): (Request) -> Response =
        { request ->
            override(request)
                ?: when {
                    request.url.encodedPath.endsWith("/heartbeat") -> jsonResponse(request, 200, heartbeatBody())
                    request.url.encodedPath.endsWith("/activity") -> jsonResponse(request, 200, heartbeatBody())
                    else -> jsonResponse(request, 201, createBody())
                }
        }

    private fun manifest(
        capabilityId: String,
        expiresAt: String,
        sessionId: String,
    ): BootstrapTransportResult =
        BootstrapTransportResult.Success(
            manifestJson(
                researchSessionId = sessionId,
                overrides =
                    mapOf(
                        "session_capability" to
                            linkedMapOf<String, Any?>(
                                "capability_id" to capabilityId,
                                "audience" to "research-runtime",
                                "scope" to listOf("telemetry:write", "session:heartbeat", "session:close"),
                                "issued_at" to "2026-01-01T00:00:00Z",
                                "expires_at" to expiresAt,
                            ),
                    ),
            ),
        )

    private fun resolvedRuntime(): ResolvedProxyRuntime {
        val proxyExecutable = root.resolve("runtime/bin/telemetry-acp-proxy")
        Files.createDirectories(proxyExecutable.parent)
        if (!Files.exists(proxyExecutable)) Files.writeString(proxyExecutable, "proxy-binary")
        return ResolvedProxyRuntime(
            runtimeRoot = root.resolve("runtime"),
            proxyArgv = listOf(proxyExecutable.toString(), "--stdio"),
            proxyDigest = "ab".repeat(32),
        )
    }

    private fun fakeAgentInstaller(): PackagedAgentInstaller =
        PackagedAgentInstaller { _ ->
            PackagedAgentInstall.Ready(
                argv = listOf(root.resolve("runtime/agents/macos-aarch64/code4me-agent").toString(), "--managed"),
                digest = VALID_ARTIFACT_DIGEST,
            )
        }

    private fun activity(projectKey: String = PROJECT_KEY): IdeActivitySignal =
        IdeActivitySignal(kind = "opened", projectKey = projectKey, metadata = mapOf("file_extension" to "kt"))

    private fun bodyOf(request: Request): Map<*, *> {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return parseCanonicalJson(buffer.readUtf8()) as Map<*, *>
    }

    private fun capabilityFileFor(manager: ResearchSessionManager): Path =
        runtimeRoot.resolve("capability-${ResearchSessionManager.opaqueSessionKey(storeKey(manager))}.txt")

    private fun storeKey(manager: ResearchSessionManager): String =
        ResearchSessionManager.sessionStoreKey("enrollment-1", manager.contextId)!!

    private fun contextEntryFor(projectKey: String): String =
        AcpHostRegistration.contextEntryName(ResearchSessionManager.opaqueContextId(projectKey))

    /** The registered proxy entry's argv, read back from the real registry document. */
    private fun registryArgs(entryName: String): List<String> {
        val document = parseCanonicalJson(Files.readString(registry)) as Map<*, *>
        val servers = document["agent_servers"] as Map<*, *>
        val entry = servers[entryName] as Map<*, *>
        return (entry["args"] as List<*>).map { it as String }
    }

    private fun argAfter(
        args: List<String>,
        flag: String,
    ): String? {
        val index = args.indexOf(flag)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    /** POST [events] to a live IPC endpoint the way the proxy does (schema 1, capability header). */
    private fun postToIpc(
        endpoint: String,
        capability: String,
        events: List<CanonicalEvent>,
    ): HttpResponse<String> {
        val body =
            canonicalJson(
                linkedMapOf(
                    "schema_version" to ResearchSpoolIpcServer.SCHEMA_VERSION,
                    "proxy_digest" to "ab".repeat(32),
                    "emitter_id" to "acp-proxy",
                    "events" to events.map { it.toCanonicalMap() },
                ),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header(ResearchSpoolIpcServer.CAPABILITY_HEADER, capability)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun acpEvent(
        runId: String?,
        ids: String,
    ): CanonicalEvent =
        builder(emitterId = "acp-proxy", source = EventSource.ACP, eventIds = IdSequence(ids))
            .build(eventType = CanonicalEventTypes.TOOL_STARTED, agentRunId = runId)

    /** A service over one harness's manager, as the IDE would build it. */
    private fun serviceOver(h: Harness): ResearchSessionService =
        ResearchSessionService(
            mock<Project>(),
            discoveryOverride = { EnrollmentDiscovery.Active("enrollment-1") },
            managerFactoryOverride = { h.manager },
            enrollmentSettingsOverride = ResearchEnrollmentSettings(),
        )

    private class Harness(
        val http: RecordingHttp,
        val transport: ScriptedTransport,
        val delivery: FakeDelivery,
        val scheduler: FakeScheduler,
        val source: FakeIdeSource,
        /** The observable IPC fake, or `null` when the production [ResearchSpoolIpcServer] is in use. */
        val ipc: FakeIpcServer?,
        val spool: DurableSpool,
        val store: ResearchSessionStore,
        val epochMs: AtomicLong,
        val instant: AtomicReference<Instant>,
        val manager: ResearchSessionManager,
    ) {
        fun creates(): Int = http.requests.count { it.url.encodedPath == "/api/research/sessions/" }

        fun heartbeats(): List<Request> = http.requests.filter { it.url.encodedPath.endsWith("/heartbeat") }
    }

    private fun harness(
        transport: ScriptedTransport,
        responder: (Request) -> Response = sessionsResponder(),
        delivery: FakeDelivery = FakeDelivery(),
        store: ResearchSessionStore = InMemoryResearchSessionStore(),
        discovery: (() -> EnrollmentDiscovery)? = null,
        reauthenticate: (() -> Boolean)? = null,
        authRetryBlockAfterMs: Long = ResearchSessionManager.AUTH_RETRY_BLOCK_AFTER_MS,
        hostPreflight: (() -> HostPreflightResult)? = null,
        projectKey: String = PROJECT_KEY,
        ipc: FakeIpcServer? = FakeIpcServer(),
        spool: DurableSpool = DurableSpool(root.resolve("spool-$projectKey")),
        registration: AcpHostRegistration = AcpHostRegistration(registry),
    ): Harness {
        val http = RecordingHttp(responder)
        val scheduler = FakeScheduler()
        val source = FakeIdeSource()
        val epochMs = AtomicLong(VALID_NOW.toEpochMilli())
        val instant = AtomicReference(VALID_NOW)
        val manager =
            ResearchSessionManager(
                projectKey = projectKey,
                transport = transport,
                compatibility = compatibility(),
                spoolProvider = { spool },
                source = source,
                proxyRuntimeResolver = ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(resolvedRuntime()) },
                acpHostRegistration = registration,
                packagedAgentInstaller = fakeAgentInstaller(),
                capabilityFilePathProvider = { null },
                capabilityRootProvider = { runtimeRoot },
                serverBaseUrlProvider = { "http://localhost:8008" },
                httpClient = http,
                // A null fake selects the production loopback IPC server.
                ipcServerFactory = ipc?.let { server -> { _: DurableSpool -> server } },
                uploaderFactory = { delivery },
                sessionStore = store,
                clock = { epochMs.get() },
                instantClock = { instant.get() },
                sessionIdFactory = { "local-session" },
                runIdFactory = { "run-${runs.incrementAndGet()}" },
                maintenanceScheduler = scheduler,
                enrollmentDiscoveryProvider = discovery,
                hostPreflightProvider = hostPreflight,
                reauthenticate = reauthenticate,
                authRetryBlockAfterMs = authRetryBlockAfterMs,
            )
        return Harness(http, transport, delivery, scheduler, source, ipc, spool, store, epochMs, instant, manager)
    }

    /** Activate and start the session with one qualifying activity; returns the first session id. */
    private fun Harness.activateRunning(): String {
        val activation = manager.activate("enrollment-1")
        assertTrue(activation is ResearchActivationResult.Activated, activation.toString())
        source.push(activity())
        assertEquals(SessionState.RUNNING, manager.currentSession?.state)
        return (activation as ResearchActivationResult.Activated).sessionId
    }

    private fun Harness.assertRuntimeAlive(message: String) {
        assertTrue(manager.isActive, "$message: manager active")
        assertTrue(manager.isCollecting, "$message: collector attached")
        assertTrue(AcpHostRegistration(registry).hasEntry(), "$message: ACP entry present")
        assertFalse(ipc?.closed == true, "$message: IPC server open")
        assertFalse(delivery.closed, "$message: uploader open")
        assertTrue(Files.exists(capabilityFileFor(manager)), "$message: capability file present")
        assertTrue(scheduler.cancelled.isEmpty(), "$message: maintenance loop never cancelled")
    }

    // ------------------------------------------------------------------
    // D-01: session rotation instead of runtime teardown
    // ------------------------------------------------------------------

    @Test
    fun `an idle maintenance tick rotates the session and keeps the whole runtime`() {
        val transport =
            ScriptedTransport(
                manifest("capability-1", "2026-01-01T01:00:00Z", "session-1"),
                manifest("capability-2", "2026-01-01T02:00:00Z", "session-2"),
            )
        val h = harness(transport)
        assertEquals("session-1", h.activateRunning())
        val registryBefore = Files.readString(registry)
        val createsBefore = h.creates()
        val runBefore = h.manager.currentAgentRun
        assertNotNull(runBefore)
        assertEquals("session-1", h.spool.pending().last().event.researchSessionId)

        // Manifest policy: idle_timeout_seconds = 600.
        h.epochMs.addAndGet(600_001L)
        val tick = assertDoesNotThrow<ResearchMaintenanceResult> { h.manager.performMaintenance() }

        assertTrue(tick is ResearchMaintenanceResult.Rotated, tick.toString())
        assertEquals("session-2", (tick as ResearchMaintenanceResult.Rotated).sessionId)
        assertEquals(2, transport.fetches.get(), "the idle end re-bootstraps exactly once")
        h.assertRuntimeAlive("after an idle rotation")
        assertEquals(registryBefore, Files.readString(registry), "a rotation never re-registers the ACP entry")
        assertEquals(createsBefore + 1, h.creates(), "the fresh session is opened on the server")
        assertEquals("session-2", bodyOf(h.heartbeats().last())["research_session_id"])
        assertEquals(SessionState.NOT_STARTED, h.manager.currentSession?.state)
        assertEquals("session-2", h.manager.currentSession?.sessionId)
        assertNull(h.manager.state().blockReason, "a rotated session is not 'ended' for the participant")
        assertTrue(h.manager.state().holdsStudyContext)

        // The IPC context follows the new session but keeps the ACTIVATION run:
        // the frozen ACP entry hands that run id to the proxy, which stamps it
        // on every event, so rebinding to another run would refuse them all.
        val rebind = h.ipc!!.rebinds.single()
        assertEquals("session-2", rebind.researchSessionId)
        assertEquals("enrollment-1", rebind.enrollmentId)
        assertEquals(runBefore!!.runId, rebind.agentRunId, "the IPC keeps the activation run id")
        assertEquals(runBefore, h.manager.currentAgentRun, "the activation run is neither ended nor replaced")
        assertEquals("capability-2", h.delivery.adoptedCapabilities.last()["capability_id"])

        // Later IDE activity is attributed to the NEW session and starts it.
        h.source.push(activity())
        assertEquals("session-2", h.spool.pending().last().event.researchSessionId)
        assertEquals(SessionState.RUNNING, h.manager.currentSession?.state)
        assertEquals("session-2", h.store.load(storeKey(h.manager))?.sessionId)
    }

    @Test
    fun `a server session end on heartbeat rotates instead of tearing down`() {
        // Both spellings of a server-side end: the `ended` state on a 200 and a
        // 409 SESSION_TERMINAL.
        val answers =
            listOf<(Request) -> Response>(
                { request -> jsonResponse(request, 200, heartbeatBody(state = "ended")) },
                { request -> jsonResponse(request, 409, terminalBody("SESSION_TERMINAL")) },
            )
        answers.forEachIndexed { index, ended ->
            var serverEnded = false
            val transport =
                ScriptedTransport(
                    manifest("capability-1", "2026-01-01T01:00:00Z", "session-1"),
                    manifest("capability-2", "2026-01-01T02:00:00Z", "session-2"),
                )
            val h =
                harness(
                    transport,
                    projectKey = "project-ended-$index",
                    responder =
                        sessionsResponder { request ->
                            if (serverEnded && request.url.encodedPath.endsWith("/heartbeat")) ended(request) else null
                        },
                    registration = AcpHostRegistration(registry, entryName = "entry-$index"),
                )
            h.activateRunning()
            val createsBefore = h.creates()
            serverEnded = true

            val tick = assertDoesNotThrow<ResearchMaintenanceResult> { h.manager.performMaintenance() }

            // The fresh session's own heartbeat is answered by the same fake, so
            // it is ended too and left pending for the next tick: what matters
            // is that the runtime survived and a fresh session was opened.
            assertTrue(tick is ResearchMaintenanceResult.Rotated, "case $index: $tick")
            assertEquals("session-2", (tick as ResearchMaintenanceResult.Rotated).sessionId, "case $index")
            assertEquals(2, transport.fetches.get(), "case $index: the server end re-bootstraps exactly once")
            assertEquals(createsBefore + 1, h.creates(), "case $index: the fresh session is opened on the server")
            assertTrue(h.manager.isActive, "case $index")
            assertTrue(h.manager.isCollecting, "case $index")
            assertTrue(AcpHostRegistration(registry, entryName = "entry-$index").hasEntry(), "case $index")
            assertFalse(h.delivery.closed, "case $index: the uploader is never closed by a session end")
            assertFalse(h.ipc!!.closed, "case $index")
            assertEquals("session-2", h.ipc!!.rebinds.single().researchSessionId, "case $index")
            assertTrue(h.scheduler.cancelled.isEmpty(), "case $index: the loop keeps running")
            assertEquals("session-2", h.manager.currentSession?.sessionId, "case $index")

            // When the fake also ended the fresh session's first heartbeat (the 409
            // case), the pending rotation must not reopen session-2 under its own
            // id; a real server mints a new one, so the script offers session-3.
            transport.steps.addLast(manifest("capability-3", "2026-01-01T03:00:00Z", "session-3"))
            serverEnded = false
            val next = h.manager.performMaintenance()
            assertTrue(
                next is ResearchMaintenanceResult.Maintained || next is ResearchMaintenanceResult.Rotated,
                "case $index: later ticks keep heartbeating: $next",
            )
            assertNull(h.manager.state().blockReason, "case $index")
            assertTrue(h.manager.isActive, "case $index")
        }
    }

    @Test
    fun `a rotation with a retryable transport keeps the runtime and retries on the next tick`() {
        val transport =
            ScriptedTransport(
                manifest("capability-1", "2026-01-01T01:00:00Z", "session-1"),
                BootstrapTransportResult.Failure("backend down", retryable = true),
                BootstrapTransportResult.Failure("backend down", retryable = true),
                manifest("capability-2", "2026-01-01T02:00:00Z", "session-2"),
            )
        val h = harness(transport)
        h.activateRunning()
        val createsBefore = h.creates()

        h.epochMs.addAndGet(600_001L)
        val first = assertDoesNotThrow<ResearchMaintenanceResult> { h.manager.performMaintenance() }

        assertTrue(first is ResearchMaintenanceResult.Retryable, first.toString())
        assertEquals(2, transport.fetches.get())
        h.assertRuntimeAlive("while a rotation is pending")
        assertEquals(SessionState.ENDED, h.manager.currentSession?.state, "the old session ended locally")
        val pending = h.manager.state()
        assertEquals(StudyBlockReason.SESSION_ROTATING, pending.blockReason, "the participant sees 'reconnecting'")
        assertEquals(StudyComponentState.UNAVAILABLE, pending.sessionState)
        assertTrue(pending.holdsStudyContext, "the study still owns the project while reconnecting")
        assertEquals(createsBefore, h.creates(), "no server session can be opened without a manifest")

        // INV2: events captured meanwhile keep the OLD scope/session id and are
        // spooled (the server accepts them within its grace window), never dropped.
        val spooledBefore = h.spool.pending().size
        h.source.push(activity())
        assertEquals(spooledBefore + 1, h.spool.pending().size, "events during a pending rotation are spooled")
        assertEquals("session-1", h.spool.pending().last().event.researchSessionId)
        assertEquals(SessionState.ENDED, h.manager.currentSession?.state, "an ended session is never advanced")

        val second = h.manager.performMaintenance()
        assertTrue(second is ResearchMaintenanceResult.Retryable, second.toString())
        assertEquals(3, transport.fetches.get(), "every tick retries the rotation")
        h.assertRuntimeAlive("after a second failed rotation")

        val third = h.manager.performMaintenance()
        assertTrue(third is ResearchMaintenanceResult.Rotated, third.toString())
        assertEquals("session-2", (third as ResearchMaintenanceResult.Rotated).sessionId)
        assertEquals(4, transport.fetches.get())
        assertEquals(createsBefore + 1, h.creates())
        assertNull(h.manager.state().blockReason)
        h.assertRuntimeAlive("after the rotation completed")
        assertEquals("session-2", h.ipc!!.rebinds.single().researchSessionId)
    }

    @Test
    fun `a rotation handed the ended session's own id waits for the server to close it`() {
        // The server's activity marker trails the local one by up to one
        // heartbeat, so the first re-bootstrap after a local idle end may still
        // name the session we just ended. It is never reopened under its own id.
        val transport =
            ScriptedTransport(
                manifest("capability-1", "2026-01-01T01:00:00Z", "session-1"),
                manifest("capability-1b", "2026-01-01T01:30:00Z", "session-1"),
                manifest("capability-2", "2026-01-01T02:00:00Z", "session-2"),
            )
        val h = harness(transport)
        h.activateRunning()
        val createsBefore = h.creates()
        val run = h.manager.currentAgentRun

        h.epochMs.addAndGet(600_001L)
        val first = assertDoesNotThrow<ResearchMaintenanceResult> { h.manager.performMaintenance() }

        assertTrue(first is ResearchMaintenanceResult.Retryable, first.toString())
        assertEquals(2, transport.fetches.get())
        assertEquals("session-1", h.manager.currentSession?.sessionId)
        assertEquals(SessionState.ENDED, h.manager.currentSession?.state, "the ended session is not reopened")
        assertEquals(SessionState.ENDED, h.store.load(storeKey(h.manager))?.state, "the persisted ENDED document is kept")
        assertEquals(StudyBlockReason.SESSION_ROTATING, h.manager.state().blockReason, "the rotation stays pending")
        assertEquals(createsBefore, h.creates(), "no POST /sessions/ for the ended id")
        assertTrue(h.ipc!!.rebinds.isEmpty(), "the IPC context is untouched")
        assertEquals(run, h.manager.currentAgentRun, "the run is untouched")
        assertEquals(listOf("capability-1"), h.delivery.adoptedCapabilities.map { it["capability_id"] })
        h.assertRuntimeAlive("while waiting for the server to close the ended session")

        val second = h.manager.performMaintenance()

        assertTrue(second is ResearchMaintenanceResult.Rotated, second.toString())
        assertEquals("session-2", (second as ResearchMaintenanceResult.Rotated).sessionId)
        assertEquals(3, transport.fetches.get())
        assertEquals(createsBefore + 1, h.creates())
        assertEquals("session-2", h.ipc!!.rebinds.single().researchSessionId)
        assertEquals("capability-2", h.delivery.adoptedCapabilities.last()["capability_id"])
        assertNull(h.manager.state().blockReason)
    }

    @Test
    fun `after a rotation the live IPC still accepts ACP events stamped with the activation run id`() {
        // Production IPC server (no fake): the proxy launched from the frozen ACP
        // entry stamps the ACTIVATION run id on every event it posts, before and
        // after a rotation. Those events must bind to the NEW session.
        val transport =
            ScriptedTransport(
                manifest("capability-1", "2026-01-01T01:00:00Z", "session-1"),
                manifest("capability-2", "2026-01-01T02:00:00Z", "session-2"),
            )
        val h = harness(transport, ipc = null)
        try {
            h.activateRunning()
            val runId = h.manager.currentAgentRun!!.runId
            val args = registryArgs(AcpHostRegistration.DEFAULT_ENTRY_NAME)
            val endpoint = argAfter(args, AcpHostRegistration.SPOOL_ENDPOINT_FLAG)
            assertNotNull(endpoint)
            assertEquals(runId, argAfter(args, AcpHostRegistration.AGENT_RUN_ID_FLAG), "the entry carries the activation run")
            val ipcCapability = Files.readString(capabilityFileFor(h.manager)).trim()

            h.epochMs.addAndGet(600_001L)
            val tick = h.manager.performMaintenance()

            assertTrue(tick is ResearchMaintenanceResult.Rotated, tick.toString())
            assertEquals("session-2", (tick as ResearchMaintenanceResult.Rotated).sessionId)
            assertEquals(runId, h.manager.currentAgentRun?.runId, "the activation run survives the rotation")

            val acp = acpEvent(runId, "acp-after-rotation")
            val response = postToIpc(endpoint!!, ipcCapability, listOf(acp))

            assertEquals(200, response.statusCode(), response.body())
            val ack = parseCanonicalJson(response.body()) as Map<*, *>
            assertEquals(listOf(acp.eventId), ack["accepted"], response.body())
            assertTrue((ack["rejected"] as List<*>).isEmpty(), response.body())
            val stored = h.spool.pending().single { it.eventId == acp.eventId }.event
            assertEquals("session-2", stored.researchSessionId, "bound to the rotated session")
            assertEquals(runId, stored.agentRunId, "bound to the activation run")
            assertEquals("enrollment-1", stored.enrollmentId)
            assertEquals("study-1", stored.studyId)

            // An IDE-source event through the same endpoint belongs to the session,
            // not to the run (TA-04).
            val ide =
                builder(emitterId = "ide-probe", source = EventSource.IDE, eventIds = IdSequence("ide-after-rotation"))
                    .build(eventType = CanonicalEventTypes.TOOL_STARTED)
            val ideResponse = postToIpc(endpoint, ipcCapability, listOf(ide))

            assertEquals(listOf(ide.eventId), (parseCanonicalJson(ideResponse.body()) as Map<*, *>)["accepted"], ideResponse.body())
            val storedIde = h.spool.pending().single { it.eventId == ide.eventId }.event
            assertNull(storedIde.agentRunId)
            assertEquals("session-2", storedIde.researchSessionId)
        } finally {
            h.manager.stop(drain = false)
        }
    }

    @Test
    fun `the public heartbeat and idle checks rotate too`() {
        val transport =
            ScriptedTransport(
                manifest("capability-1", "2026-01-01T01:00:00Z", "session-1"),
                manifest("capability-2", "2026-01-01T02:00:00Z", "session-2"),
                manifest("capability-3", "2026-01-01T03:00:00Z", "session-3"),
            )
        val h = harness(transport)
        h.activateRunning()

        h.epochMs.addAndGet(600_001L)
        val heartbeat = h.manager.heartbeat()

        assertEquals(SessionState.NOT_STARTED, (heartbeat as ResearchSessionResult.Applied).sessionState)
        assertEquals("session-2", h.manager.currentSession?.sessionId)
        h.assertRuntimeAlive("after heartbeat() rotated")

        h.source.push(activity())
        h.epochMs.addAndGet(600_001L)
        val idle = h.manager.checkIdle()

        assertEquals(SessionState.NOT_STARTED, (idle as ResearchSessionResult.Applied).sessionState)
        assertEquals("session-3", h.manager.currentSession?.sessionId)
        assertEquals(3, transport.fetches.get())
        h.assertRuntimeAlive("after checkIdle() rotated")
    }

    // ------------------------------------------------------------------
    // Refresh authentication (D-01): re-acquire the sign-in, stay retryable
    // ------------------------------------------------------------------

    @Test
    fun `a refresh refused as not authenticated re-acquires the sign-in and retries in the same tick`() {
        val reauths = AtomicInteger()
        val transport =
            ScriptedTransport(
                // Inside the 120 s refresh margin at VALID_NOW (00:30).
                manifest("capability-1", "2026-01-01T00:31:00Z", "session-1"),
                BootstrapTransportResult.Failure("not signed in", retryable = false, rejection = BootstrapRejection.NOT_AUTHENTICATED),
                manifest("capability-2", "2026-01-01T02:00:00Z", "session-1"),
            )
        val h = harness(transport, reauthenticate = { reauths.incrementAndGet() > 0 })
        h.activateRunning()

        val tick = h.manager.performMaintenance()

        assertTrue(tick is ResearchMaintenanceResult.Maintained, tick.toString())
        assertTrue((tick as ResearchMaintenanceResult.Maintained).capabilityRefreshed)
        assertEquals(1, reauths.get(), "the sign-in is renewed once")
        assertEquals(3, transport.fetches.get(), "the acquire is retried in the same tick after renewing")
        assertEquals("capability-2", h.delivery.adoptedCapabilities.last()["capability_id"])
        assertNull(h.manager.state().blockReason)
        h.assertRuntimeAlive("after an in-tick re-authentication")
    }

    @Test
    fun `a refresh that stays unauthenticated is retryable and asks for a sign-in only after the capability expired for long enough`() {
        val transport =
            ScriptedTransport(
                manifest("capability-1", "2026-01-01T00:31:00Z", "session-1"),
                BootstrapTransportResult.Failure("not signed in", retryable = false, rejection = BootstrapRejection.NOT_AUTHENTICATED),
            )
        val h = harness(transport, reauthenticate = { false }, authRetryBlockAfterMs = 60_000L)
        h.activateRunning()

        // Held capability still valid: an authentication refusal is just retryable.
        val first = h.manager.performMaintenance()
        assertTrue(first is ResearchMaintenanceResult.Maintained, first.toString())
        assertNull(h.manager.state().blockReason)
        h.assertRuntimeAlive("after the first refused refresh")

        // Capability expired, but the refusal has not lasted long enough yet.
        h.instant.set(Instant.parse("2026-01-01T00:32:00Z"))
        h.epochMs.addAndGet(30_000L)
        assertDoesNotThrow<ResearchMaintenanceResult> { h.manager.performMaintenance() }
        assertNull(h.manager.state().blockReason, "no sign-in prompt before the retry window elapsed")

        h.epochMs.addAndGet(30_001L)
        assertDoesNotThrow<ResearchMaintenanceResult> { h.manager.performMaintenance() }

        val blocked = h.manager.state()
        assertEquals(StudyBlockReason.AUTHENTICATION_REQUIRED, blocked.blockReason)
        assertEquals(ResearchSessionManager.AUTHENTICATION_REQUIRED_DETAIL, blocked.blockReasonDetail)
        h.assertRuntimeAlive("while a sign-in is required (the runtime stays registered)")

        // A later successful refresh (the participant signed in) clears it.
        transport.steps.addLast(manifest("capability-2", "2026-01-01T02:00:00Z", "session-1"))
        val recovered = h.manager.performMaintenance()

        assertTrue(recovered is ResearchMaintenanceResult.Maintained, recovered.toString())
        assertNull(h.manager.state().blockReason)
        assertEquals("capability-2", h.delivery.adoptedCapabilities.last()["capability_id"])
    }

    // ------------------------------------------------------------------
    // D-04: the kill switch is a pause; REVOKED only when confirmed
    // ------------------------------------------------------------------

    @Test
    fun `a kill-switch answer pauses without teardown and resumes on the next accepted heartbeat`() {
        listOf(409, 403).forEachIndexed { index, status ->
            var engaged = false
            val transport = ScriptedTransport(manifest("capability-1", "2026-01-01T01:00:00Z", "session-1"))
            val h =
                harness(
                    transport,
                    projectKey = "project-kill-$index",
                    responder =
                        sessionsResponder { request ->
                            if (engaged && request.url.encodedPath.endsWith("/heartbeat")) {
                                jsonResponse(request, status, terminalBody("KILL_SWITCH_ENGAGED"))
                            } else {
                                null
                            }
                        },
                    registration = AcpHostRegistration(registry, entryName = "entry-kill-$index"),
                )
            h.activateRunning()
            engaged = true

            val paused = assertDoesNotThrow<ResearchMaintenanceResult> { h.manager.performMaintenance() }

            assertTrue(paused is ResearchMaintenanceResult.Paused, "HTTP $status: $paused")
            assertEquals(StudyBlockReason.KILL_SWITCH_ENGAGED, (paused as ResearchMaintenanceResult.Paused).reason)
            val state = h.manager.state()
            assertEquals(StudyBlockReason.KILL_SWITCH_ENGAGED, state.blockReason, "HTTP $status")
            assertEquals(StudyComponentState.AVAILABLE, state.sessionState, "HTTP $status: the session state is unchanged")
            assertEquals(SessionState.RUNNING, h.manager.currentSession?.state, "HTTP $status")
            assertTrue(h.manager.isActive, "HTTP $status")
            assertTrue(h.manager.isCollecting, "HTTP $status")
            assertTrue(AcpHostRegistration(registry, entryName = "entry-kill-$index").hasEntry(), "HTTP $status")
            assertFalse(h.delivery.closed, "HTTP $status: a pause never closes the uploader")
            assertTrue(h.scheduler.cancelled.isEmpty(), "HTTP $status: the loop keeps retrying at its cadence")
            assertEquals(1, transport.fetches.get(), "HTTP $status: a pause never re-bootstraps")
            assertEquals(SessionState.RUNNING, h.store.load(storeKey(h.manager))?.state, "HTTP $status: nothing terminal is persisted")

            // Still paused on the next tick, then lifted by the first 2xx.
            assertTrue(h.manager.performMaintenance() is ResearchMaintenanceResult.Paused, "HTTP $status")
            engaged = false
            val resumed = h.manager.performMaintenance()

            assertTrue(resumed is ResearchMaintenanceResult.Maintained, "HTTP $status: $resumed")
            assertNull(h.manager.state().blockReason, "HTTP $status: the pause clears on the next accepted heartbeat")
        }
    }

    @Test
    fun `a kill-switch bootstrap refusal during refresh pauses instead of revoking`() {
        val transport =
            ScriptedTransport(
                manifest("capability-1", "2026-01-01T00:31:00Z", "session-1"),
                BootstrapTransportResult.Revoked("paused by the study team", BootstrapRejection.KILL_SWITCH_ENGAGED),
            )
        val h = harness(transport)
        h.activateRunning()

        val paused = h.manager.performMaintenance()

        assertTrue(paused is ResearchMaintenanceResult.Paused, paused.toString())
        assertEquals(StudyBlockReason.KILL_SWITCH_ENGAGED, h.manager.state().blockReason)
        h.assertRuntimeAlive("while the kill switch refuses bootstrap")
        assertEquals(SessionState.RUNNING, h.store.load(storeKey(h.manager))?.state, "never persisted as REVOKED")

        transport.steps.addLast(manifest("capability-2", "2026-01-01T02:00:00Z", "session-1"))
        val resumed = h.manager.performMaintenance()

        assertTrue(resumed is ResearchMaintenanceResult.Maintained, resumed.toString())
        assertNull(h.manager.state().blockReason)
    }

    @Test
    fun `a kill-switch refusal on activation is a typed block the reconciler retries`() {
        val transport = ScriptedTransport(BootstrapTransportResult.Revoked("paused", BootstrapRejection.KILL_SWITCH_ENGAGED))
        val h = harness(transport)

        val result = h.manager.activate("enrollment-1")

        assertTrue(result is ResearchActivationResult.Blocked)
        assertEquals(StudyBlockReason.KILL_SWITCH_ENGAGED, (result as ResearchActivationResult.Blocked).reason)
        assertEquals(StudyBlockReason.KILL_SWITCH_ENGAGED, h.manager.state().blockReason)
        assertTrue(ResearchReconciliationResult.StudyOwned(result).shouldRetry, "a pause lifts on its own: retry later")
        assertNull(h.store.load(storeKey(h.manager)), "nothing is persisted for a paused activation")
    }

    @Test
    fun `a revocation is persisted only when discovery confirms the enrollment is over`() {
        // `confirm` is what participants/me answers when the revocation is
        // re-checked; `null` means no discovery is wired (bootstrap authority).
        data class Case(val code: String, val confirm: EnrollmentDiscovery?, val persisted: SessionState)
        val cases =
            listOf(
                Case("REVOKED", EnrollmentDiscovery.Terminal("REVOKED"), SessionState.REVOKED),
                Case("ENROLLMENT_NOT_ACTIVE", EnrollmentDiscovery.None, SessionState.REVOKED),
                Case("REVOKED", null, SessionState.REVOKED),
                // The server contradicts itself (or the answer was an operator
                // action): block in memory, suspend, re-check next time.
                Case("REVOKED", EnrollmentDiscovery.Active("enrollment-1"), SessionState.SUSPENDED),
                Case("REVOKED", EnrollmentDiscovery.Unavailable("down"), SessionState.SUSPENDED),
                Case("STUDY_STOPPED", EnrollmentDiscovery.Terminal("STOPPED"), SessionState.SUSPENDED),
                Case("STUDY_STOPPED", null, SessionState.SUSPENDED),
            )
        cases.forEachIndexed { index, case ->
            var refused = false
            // Discovery says Active while activating; it switches to the case's
            // answer once the server refuses the heartbeat.
            val membership = AtomicReference<EnrollmentDiscovery>(EnrollmentDiscovery.Active("enrollment-1"))
            val transport = ScriptedTransport(manifest("capability-1", "2026-01-01T01:00:00Z", "session-1"))
            val h =
                harness(
                    transport,
                    projectKey = "project-revoke-$index",
                    discovery = case.confirm?.let { { membership.get() } },
                    responder =
                        sessionsResponder { request ->
                            if (refused && request.url.encodedPath.endsWith("/heartbeat")) {
                                jsonResponse(request, 403, terminalBody(case.code))
                            } else {
                                null
                            }
                        },
                    registration = AcpHostRegistration(registry, entryName = "entry-revoke-$index"),
                )
            h.activateRunning()
            case.confirm?.let { membership.set(it) }
            refused = true

            val tick = assertDoesNotThrow<ResearchMaintenanceResult> { h.manager.performMaintenance() }

            assertTrue(tick is ResearchMaintenanceResult.Ended, "$case: $tick")
            assertEquals(StudyBlockReason.REVOKED, (tick as ResearchMaintenanceResult.Ended).reason, "$case")
            assertEquals(StudyBlockReason.REVOKED, h.manager.state().blockReason, "$case: blocked in memory")
            assertFalse(h.manager.isActive, "$case: a revocation-class answer stops the runtime")
            assertTrue(h.delivery.closed, "$case")
            assertEquals(case.persisted, h.store.load(storeKey(h.manager))?.state, "$case: persisted state")
        }
    }

    @Test
    fun `a bootstrap refusal other than REVOKED or ENROLLMENT_NOT_ACTIVE never persists REVOKED`() {
        // No discovery wired (bootstrap is the authority): a closed study still
        // maps to the typed REVOKED block, but only the two revocation codes may
        // persist a REVOKED session document.
        val transport =
            ScriptedTransport(
                manifest("capability-1", "2026-01-01T00:31:00Z", "session-1"),
                BootstrapTransportResult.Revoked("the study is closed", BootstrapRejection.STUDY_CLOSED),
            )
        val h = harness(transport)
        h.activateRunning()

        val tick = assertDoesNotThrow<ResearchMaintenanceResult> { h.manager.performMaintenance() }

        assertTrue(tick is ResearchMaintenanceResult.Ended, tick.toString())
        assertEquals(StudyBlockReason.REVOKED, (tick as ResearchMaintenanceResult.Ended).reason)
        assertEquals(StudyBlockReason.REVOKED, h.manager.state().blockReason, "blocked in memory")
        assertFalse(h.manager.isActive)
        assertTrue(h.delivery.closed)
        assertEquals(SessionState.SUSPENDED, h.manager.currentSession?.state, "suspended, never REVOKED")
        assertEquals(SessionState.SUSPENDED, h.store.load(storeKey(h.manager))?.state, "the next activation re-checks the server")
    }

    @Test
    fun `a stale REVOKED document is cleared when discovery reports the enrollment active`() {
        val transport = ScriptedTransport(manifest("capability-1", "2026-01-01T01:00:00Z", "session-1"))
        val store = InMemoryResearchSessionStore()
        val contextId = ResearchSessionManager.opaqueContextId(PROJECT_KEY)
        store.save(
            ResearchSession(
                sessionId = "stale-session",
                enrollmentId = "enrollment-1",
                contextId = contextId,
                state = SessionState.REVOKED,
                closedAtEpochMs = VALID_NOW.toEpochMilli() - 1_000L,
                closeReason = SessionTerminalReason.REVOKED,
            ),
        )

        // Without discovery the stored revocation keeps blocking (bootstrap authority only).
        val blocked = harness(transport, store = store, projectKey = PROJECT_KEY).manager.activate("enrollment-1")
        assertTrue(blocked is ResearchActivationResult.Blocked, blocked.toString())
        assertEquals(StudyBlockReason.REVOKED, (blocked as ResearchActivationResult.Blocked).reason)

        // With the server saying Active, the document is stale: cleared, fresh session.
        val h = harness(transport, store = store, discovery = { EnrollmentDiscovery.Active("enrollment-1") })
        val activation = h.manager.activate("enrollment-1")

        assertTrue(activation is ResearchActivationResult.Activated, activation.toString())
        assertEquals("session-1", (activation as ResearchActivationResult.Activated).sessionId)
        assertEquals("session-1", store.load(storeKey(h.manager))?.sessionId, "the stale document was replaced")
        h.assertRuntimeAlive("after a stale revocation was cleared")
    }

    // ------------------------------------------------------------------
    // D-02: stop() ships the tail; discards are surfaced
    // ------------------------------------------------------------------

    @Test
    fun `stop drains the spool once before closing the uploader`() {
        val transport = ScriptedTransport(manifest("capability-1", "2026-01-01T01:00:00Z", "session-1"))
        val h = harness(transport)
        h.activateRunning()
        assertTrue(h.spool.pending().isNotEmpty(), "there is a tail to ship")

        val stop = h.manager.stop() as ResearchStopResult.Stopped

        assertEquals(listOf("start", "drain", "close"), h.delivery.lifecycle, "exactly one drain, before close")
        assertEquals(SessionState.SUSPENDED, stop.finalSessionState)
        assertFalse(h.manager.isActive)

        // Idempotent: a second stop never drains or closes again.
        h.manager.stop()
        assertEquals(listOf("start", "drain", "close"), h.delivery.lifecycle)
    }

    @Test
    fun `erase and sign-out stop without draining while a plain project close drains once`() {
        // A privacy erase must never upload the records it is about to delete,
        // and a sign-out quarantines the spool instead of shipping it; only a
        // plain project close / IDE shutdown ships the tail first.
        val erase =
            harness(
                ScriptedTransport(manifest("capability-e", "2026-01-01T01:00:00Z", "session-e")),
                projectKey = "project-erase",
                registration = AcpHostRegistration(registry, entryName = "entry-erase"),
            )
        val eraseService = serviceOver(erase)
        eraseService.manager()
        erase.activateRunning()
        assertTrue(erase.spool.pending().isNotEmpty(), "there is a tail that must NOT be uploaded")

        assertTrue(eraseService.onErase())

        assertEquals(listOf("start", "close"), erase.delivery.lifecycle, "a privacy erase never drains first")
        assertFalse(erase.manager.isActive)

        val logout =
            harness(
                ScriptedTransport(manifest("capability-l", "2026-01-01T01:00:00Z", "session-l")),
                projectKey = "project-logout",
                registration = AcpHostRegistration(registry, entryName = "entry-logout"),
            )
        val logoutService = serviceOver(logout)
        logoutService.manager()
        logout.activateRunning()

        logoutService.onLogout()

        assertEquals(listOf("start", "close"), logout.delivery.lifecycle, "a sign-out quarantines without uploading")

        val close =
            harness(
                ScriptedTransport(manifest("capability-c", "2026-01-01T01:00:00Z", "session-c")),
                projectKey = "project-close",
                registration = AcpHostRegistration(registry, entryName = "entry-close"),
            )
        val closeService = serviceOver(close)
        closeService.manager()
        close.activateRunning()

        closeService.dispose()

        assertEquals(listOf("start", "drain", "close"), close.delivery.lifecycle, "a plain project close ships the tail once")
    }

    @Test
    fun `the participant state surfaces the uploader's discard count`() {
        val transport = ScriptedTransport(manifest("capability-1", "2026-01-01T01:00:00Z", "session-1"))
        val h = harness(transport, delivery = FakeDelivery(discardedCount = 2))
        h.activateRunning()

        val state = h.manager.state()

        assertEquals(2, state.discardedEventCount)
        assertTrue(state.toCanonicalJson().contains("\"discarded_event_count\":2"), state.toCanonicalJson())
    }

    // ------------------------------------------------------------------
    // D-03: one capability file per (enrollment, context)
    // ------------------------------------------------------------------

    @Test
    fun `two contexts of one enrollment write different capability files`() {
        val a =
            harness(
                ScriptedTransport(manifest("capability-a", "2026-01-01T01:00:00Z", "session-a")),
                projectKey = "project-a",
                ipc = FakeIpcServer("ipc-a"),
                registration = AcpHostRegistration(registry, entryName = contextEntryFor("project-a")),
            )
        val b =
            harness(
                ScriptedTransport(manifest("capability-b", "2026-01-01T01:00:00Z", "session-b")),
                projectKey = "project-b",
                ipc = FakeIpcServer("ipc-b"),
                registration = AcpHostRegistration(registry, entryName = contextEntryFor("project-b")),
            )

        assertTrue(a.manager.activate("enrollment-1") is ResearchActivationResult.Activated)
        assertTrue(b.manager.activate("enrollment-1") is ResearchActivationResult.Activated)

        val fileA = capabilityFileFor(a.manager)
        val fileB = capabilityFileFor(b.manager)
        assertTrue(fileA != fileB, "each window owns its own capability file")
        assertEquals("ipc-a", Files.readString(fileA).trim())
        assertEquals("ipc-b", Files.readString(fileB).trim(), "window B never overwrote window A's token")
        val capabilityFiles =
            Files.list(runtimeRoot).use { stream ->
                stream.map { it.fileName.toString() }.filter { it.startsWith("capability-") && it.endsWith(".txt") }.toList()
            }
        assertEquals(2, capabilityFiles.size, capabilityFiles.toString())

        // Closing one window removes only its own file.
        a.manager.stop()
        assertFalse(Files.exists(fileA))
        assertTrue(Files.exists(fileB), "window B keeps its capability file")
    }

    // ------------------------------------------------------------------
    // A-03: AI Assistant preflight
    // ------------------------------------------------------------------

    @Test
    fun `a failed host preflight blocks activation before any bootstrap request`() {
        listOf(StudyBlockReason.AI_ASSISTANT_MISSING, StudyBlockReason.AI_ASSISTANT_OUTDATED).forEachIndexed { index, reason ->
            val transport = ScriptedTransport(manifest("capability-1", "2026-01-01T01:00:00Z", "session-1"))
            val h =
                harness(
                    transport,
                    projectKey = "project-preflight-$index",
                    hostPreflight = { HostPreflightResult.Blocked(reason, "JetBrains AI Assistant 262.8665.344 or newer is required") },
                    registration = AcpHostRegistration(registry, entryName = "entry-preflight-$index"),
                )

            val result = h.manager.activate("enrollment-1")

            assertTrue(result is ResearchActivationResult.Blocked, "$reason: $result")
            assertEquals(reason, (result as ResearchActivationResult.Blocked).reason)
            assertTrue(result.detail!!.contains("262.8665.344"), "$reason: the detail names the minimum version")
            assertEquals(0, transport.fetches.get(), "$reason: no bootstrap request is made")
            assertEquals(0, h.http.requests.size, "$reason: no session request is made")
            val state = h.manager.state()
            assertEquals(reason, state.blockReason)
            assertEquals(StudyComponentState.BLOCKED, state.compatibilityState, "$reason: an environment block")
            assertFalse(h.manager.isActive)
            assertFalse(AcpHostRegistration(registry, entryName = "entry-preflight-$index").hasEntry(), "$reason: no ACP entry")
            assertFalse(ResearchReconciliationResult.StudyOwned(result).shouldRetry, "$reason: needs the participant, no retry storm")
        }
    }

    @Test
    fun `a passing host preflight activates normally`() {
        val transport = ScriptedTransport(manifest("capability-1", "2026-01-01T01:00:00Z", "session-1"))
        val h = harness(transport, hostPreflight = { HostPreflightResult.Ok("262.10315.125") })

        assertTrue(h.manager.activate("enrollment-1") is ResearchActivationResult.Activated)
        assertEquals(1, transport.fetches.get())
        h.assertRuntimeAlive("after a passing preflight")
    }

    private companion object {
        const val PROJECT_KEY = "project-rotation"
    }
}
