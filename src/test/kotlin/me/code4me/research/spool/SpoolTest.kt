package me.code4me.research.spool

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import me.code4me.research.IdSequence
import me.code4me.research.builder
import me.code4me.research.fixedClock
import me.code4me.research.spool.DurableSpool
import me.code4me.research.spool.RetryBackoff
import me.code4me.research.spool.SpoolRecord
import me.code4me.research.telemetry.CanonicalEvent
import me.code4me.research.telemetry.CanonicalEventBuilder
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
import org.junit.jupiter.api.Test

// --------------------------------------------------------------------------
// BatchAckTest.kt
// --------------------------------------------------------------------------

class BatchAckTest {
    private fun records(
        directory: java.nio.file.Path,
        count: Int,
    ): Pair<DurableSpool, List<SpoolRecord>> {
        val spool = DurableSpool(directory)
        val builder =
            CanonicalEventBuilder(
                emitterId = "acp-proxy",
                source = EventSource.ACP,
                normalizerVersion = "generic-acp-v1",
                clock = fixedClock("clock-acp", 1L),
                eventIdFactory = IdSequence("evt")::next,
            )
        val appended = (1..count).map { spool.append(builder.build(eventType = CanonicalEventTypes.TOOL_STARTED)) }
        return spool to appended
    }

    @Test
    fun `a null acknowledgement is never treated as accepted`() {
        val directory = Files.createTempDirectory("batch-null-ack")
        val (spool, appended) = records(directory, 2)
        val handler = BatchAckHandler(spool)

        val result = handler.apply(null)

        assertEquals(0, result.acknowledgedCount)
        assertTrue(result.acknowledgedIds.isEmpty())
        assertEquals(appended.map { it.eventId }, spool.pending().map { it.eventId })
    }

    @Test
    fun `accepted and duplicate ids are deleted while other dispositions are retained`() {
        val directory = Files.createTempDirectory("batch-ack")
        val (spool, appended) = records(directory, 4)
        val handler = BatchAckHandler(spool)
        val ack =
            TelemetryBatchAckV1(
                receiptId = "receipt-1",
                serverTime = "2026-01-01T00:00:01Z",
                accepted = listOf(appended[0].eventId),
                duplicate = listOf(appended[1].eventId),
                rejected = listOf(appended[3].eventId),
                retryable = listOf(appended[2].eventId),
            )

        val result = handler.apply(ack)

        assertEquals(2, result.acknowledgedCount)
        assertEquals(setOf(appended[0].eventId, appended[1].eventId), result.acknowledgedIds.toSet())
        assertEquals(listOf(appended[3].eventId), result.rejectedIds)
        assertEquals(listOf(appended[2].eventId), result.retryableIds)
        // Accepted/duplicate are removed; retryable and rejected stay spooled.
        assertEquals(
            listOf(appended[2].eventId, appended[3].eventId),
            spool.pending().map { it.eventId },
        )
    }

    @Test
    fun `empty acknowledgement removes nothing`() {
        val directory = Files.createTempDirectory("batch-empty-ack")
        val (spool, appended) = records(directory, 1)
        val handler = BatchAckHandler(spool)

        val result = handler.apply(TelemetryBatchAckV1(receiptId = "receipt-1", serverTime = "now"))

        assertEquals(0, result.acknowledgedCount)
        assertEquals(appended.map { it.eventId }, spool.pending().map { it.eventId })
    }

    @Test
    fun `retryable ids stay spooled and receive capped exponential backoff`() {
        val directory = Files.createTempDirectory("batch-retry")
        val (spool, appended) = records(directory, 1)
        val eventId = appended.first().eventId
        var now = 10_000L
        val handler =
            BatchAckHandler(
                spool = spool,
                backoff = RetryBackoff(baseDelayMs = 1_000L, maxDelayMs = 8_000L, jitterSource = { 1.0 }),
                clock = { now },
            )
        val ack =
            TelemetryBatchAckV1(
                receiptId = "receipt-1",
                serverTime = "now",
                retryable = listOf(eventId),
            )

        val first = handler.apply(ack)
        assertEquals(1_000L, first.retryDelaysMs[eventId])
        assertEquals(1, handler.attemptFor(eventId))
        assertEquals(11_000L, handler.nextAttemptAt(eventId))
        assertEquals(listOf(eventId), spool.pending().map { it.eventId })

        now = 11_000L
        assertTrue(handler.isReady(eventId))
        val second = handler.apply(ack)
        assertEquals(2_000L, second.retryDelaysMs[eventId])
        assertEquals(2, handler.attemptFor(eventId))
        assertEquals(13_000L, handler.nextAttemptAt(eventId))
        assertFalse(handler.isReady(eventId, 12_999L))
        assertTrue(handler.isReady(eventId, 13_000L))
    }

    @Test
    fun `backoff is deterministic for an injected jitter source`() {
        val fullJitter = RetryBackoff(baseDelayMs = 1_000L, maxDelayMs = 8_000L, jitterSource = { 1.0 })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), (1..5).map { fullJitter.delayForAttempt(it) })

        val noJitter = RetryBackoff(baseDelayMs = 1_000L, maxDelayMs = 8_000L, jitterSource = { 0.0 })
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 4_000L), (1..5).map { noJitter.delayForAttempt(it) })
    }

    @Test
    fun `batch builder groups records into bounded batches`() {
        val directory = Files.createTempDirectory("batch-build")
        val (_, appended) = records(directory, 5)
        val builder = TelemetryBatchBuilder(maxEventsPerBatch = 2, batchIdFactory = IdSequence("batch")::next)

        val single = builder.build(appended, sessionCapability = "capability", clientInstanceId = "client-1")
        assertEquals(2, single.size)
        assertEquals(listOf("batch-1"), listOf(single.batchId))
        assertEquals("1", single.protocolVersion)
        assertEquals("1", single.telemetrySchemaVersion)
        assertEquals("capability", single.sessionCapability)
        assertNull(single.previousAckCursor)

        val batches = builder.buildBatches(appended, sessionCapability = "capability", clientInstanceId = "client-1")
        assertEquals(listOf(2, 2, 1), batches.map { it.size })
        assertEquals(appended.map { it.eventId }, batches.flatMap { batch -> batch.events.map { it.eventId } })
    }
}

// --------------------------------------------------------------------------
// DurableSpoolTest.kt
// --------------------------------------------------------------------------

class DurableSpoolTest {
    private fun eventBuilder(): CanonicalEventBuilder =
        CanonicalEventBuilder(
            emitterId = "ide-emitter",
            source = EventSource.IDE,
            normalizerVersion = "generic-v1",
            clock = fixedClock("clock-ide", 1L),
            eventIdFactory = IdSequence("evt")::next,
        )

    private fun events(count: Int): List<CanonicalEvent> {
        val builder = eventBuilder()
        return (1..count).map { builder.build(eventType = CanonicalEventTypes.IDE_FILE_OPENED) }
    }

    @Test
    fun `append then pending then acknowledge`() {
        val directory = Files.createTempDirectory("spool-basic")
        val spool = DurableSpool(directory)
        val (first, second, third) = events(3)

        spool.append(first)
        spool.append(second)
        spool.append(third)

        assertEquals(listOf(first.eventId, second.eventId, third.eventId), spool.pending().map { it.eventId })

        assertEquals(1, spool.acknowledge(listOf(second.eventId)))
        assertEquals(0, spool.acknowledge(listOf(second.eventId)))
        assertEquals(listOf(first.eventId, third.eventId), spool.pending().map { it.eventId })
        assertEquals(2, spool.stats().pendingCount)
    }

    @Test
    fun `a duplicate event id is delivered once and acknowledged once`() {
        val directory = Files.createTempDirectory("spool-duplicate")
        val spool = DurableSpool(directory)
        val event = eventBuilder().build(eventType = CanonicalEventTypes.IDE_FILE_OPENED)

        // The same id is durably appended twice (at-least-once producer).
        spool.append(event)
        spool.append(event)

        // Delivery is exactly-once even though the log holds the id twice.
        assertEquals(listOf(event.eventId), spool.pending().map { it.eventId })
        assertEquals(1, spool.acknowledge(listOf(event.eventId)))
        assertEquals(0, spool.acknowledge(listOf(event.eventId)))
        assertTrue(spool.pending().isEmpty())

        // Compaction removes every copy of the acknowledged id and re-delivers none.
        assertEquals(0, spool.compact().pendingCount)
        assertTrue(spool.pending().isEmpty())
    }

    @Test
    fun `reopen after restart returns exactly the un-acknowledged records once each`() {
        val directory = Files.createTempDirectory("spool-restart")
        val (first, second, third) = events(3)
        val spool = DurableSpool(directory)
        spool.append(first)
        spool.append(second)
        spool.append(third)
        spool.acknowledge(listOf(second.eventId))

        val reopened = DurableSpool(directory)
        val pending = reopened.pending()
        assertEquals(setOf(first.eventId, third.eventId), pending.map { it.eventId }.toSet())
        assertEquals(2, pending.size)
        // Re-reading must not duplicate or resurrect acknowledged records.
        assertEquals(2, reopened.pending().size)
    }

    @Test
    fun `truncated or corrupt tail is quarantined and prior records survive`() {
        val directory = Files.createTempDirectory("spool-corrupt")
        val (first, second) = events(2)
        val spool = DurableSpool(directory)
        spool.append(first)
        spool.append(second)
        Files.write(
            directory.resolve("spool.log"),
            """{"record_version":"1","event_id":""".toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND,
        )

        val reopened = DurableSpool(directory)
        assertEquals(listOf(first.eventId, second.eventId), reopened.pending().map { it.eventId })
        val quarantined = quarantineFiles(directory)
        assertFalse(quarantined.isEmpty())
        val quarantinedText = Files.readAllBytes(quarantined.first()).toString(StandardCharsets.UTF_8)
        assertTrue(quarantinedText.contains("record_version"))
    }

    @Test
    fun `quota compaction removes only acknowledged records`() {
        val directory = Files.createTempDirectory("spool-quota")
        val (first, second, third) = events(3)
        val spool = DurableSpool(directory, maxRecords = 1)
        spool.append(first)
        spool.append(second)
        spool.append(third)
        spool.acknowledge(listOf(first.eventId))

        val stats = spool.compact()

        assertEquals(listOf(second.eventId, third.eventId), spool.pending().map { it.eventId })
        assertEquals(2, stats.pendingCount)
        assertTrue(stats.quotaExceeded)
        assertTrue(stats.indicators.contains(DurableSpool.QUOTA_INDICATOR))
        assertTrue(Files.exists(directory.resolve(DurableSpool.QUOTA_INDICATOR)))
    }

    @Test
    fun `quota indicator clears once back under the limit`() {
        val directory = Files.createTempDirectory("spool-quota-clear")
        val (first, second) = events(2)
        val spool = DurableSpool(directory, maxRecords = 1)
        spool.append(first)
        spool.append(second)
        assertTrue(spool.stats().quotaExceeded)

        spool.acknowledge(listOf(first.eventId))
        val stats = spool.compact()

        assertFalse(stats.quotaExceeded)
        assertTrue(stats.indicators.isEmpty())
        assertFalse(Files.exists(directory.resolve(DurableSpool.QUOTA_INDICATOR)))
    }

    @Test
    fun `stats report oldest pending age and acked count`() {
        val directory = Files.createTempDirectory("spool-stats")
        val (first, second) = events(2)
        var now = 1_000L
        val spool = DurableSpool(directory, clock = { now })
        spool.append(first)
        now = 1_600L
        spool.append(second)
        spool.acknowledge(listOf(first.eventId))
        now = 2_000L

        val stats = spool.stats()
        assertEquals(1, stats.pendingCount)
        assertEquals(1, stats.ackedCount)
        assertEquals(400L, stats.oldestPendingAgeMs)
    }

    private fun quarantineFiles(directory: Path): List<Path> =
        Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().startsWith("quarantine-") }.toList()
        }
}

// --------------------------------------------------------------------------
// ResearchSpoolIpcServerTest.kt
// --------------------------------------------------------------------------

/**
 * Loopback contract tests for [ResearchSpoolIpcServer] (Gap 2).
 *
 * Every case uses a real ephemeral loopback socket and a temp-dir spool so the
 * auth, durability, duplicate, size, and schema semantics are exercised over the
 * wire rather than through an in-process shortcut.
 */
class ResearchSpoolIpcServerTest {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private fun events(count: Int): List<CanonicalEvent> {
        val builder = builder(emitterId = "acp-proxy")
        return (1..count).map {
            builder.build(eventType = CanonicalEventTypes.TOOL_STARTED, payload = mapOf("tool" to "t$it"))
        }
    }

    private fun body(
        events: List<CanonicalEvent>,
        schemaVersion: String = ResearchSpoolIpcServer.SCHEMA_VERSION,
    ): String =
        canonicalJson(
            linkedMapOf(
                "schema_version" to schemaVersion,
                "proxy_digest" to "ab".repeat(32),
                "emitter_id" to "acp-proxy",
                "events" to events.map { it.toCanonicalMap() },
            ),
        )

    private fun post(
        endpoint: String,
        body: String,
        capability: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(endpoint))
        if (capability != null) request.header(ResearchSpoolIpcServer.CAPABILITY_HEADER, capability)
        return client.send(
            request
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: HttpResponse<String>): Map<String, Any?> = parseCanonicalJson(response.body()) as Map<String, Any?>

    private fun stringList(
        map: Map<String, Any?>,
        key: String,
    ): List<String> = (map[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    private fun withServer(
        spool: DurableSpool,
        maxBodyBytes: Int = ResearchSpoolIpcServer.DEFAULT_MAX_BODY_BYTES,
        capability: String = "cap-test",
        block: (ResearchSpoolIpcServer) -> Unit,
    ) {
        val server = ResearchSpoolIpcServer(spool, capability, maxBodyBytes = maxBodyBytes)
        try {
            block(server)
        } finally {
            server.close()
        }
    }

    private fun tempSpool(): DurableSpool = DurableSpool(Files.createTempDirectory("spool-ipc"))

    @Test
    fun `missing capability is rejected with 401 and appends nothing`() {
        val spool = tempSpool()
        withServer(spool) { server ->
            val sent = events(1)

            val response = post(server.endpointUrl, body(sent))

            assertEquals(401, response.statusCode())
            assertTrue(spool.pending().isEmpty())
        }
    }

    @Test
    fun `wrong capability is rejected with 401 and appends nothing`() {
        val spool = tempSpool()
        withServer(spool) { server ->
            val sent = events(1)

            val response = post(server.endpointUrl, body(sent), capability = "cap-wrong")

            assertEquals(401, response.statusCode())
            assertTrue(spool.pending().isEmpty())
        }
    }

    @Test
    fun `valid capability durably appends and reports accepted`() {
        val spool = tempSpool()
        withServer(spool) { server ->
            val sent = events(1)

            val response = post(server.endpointUrl, body(sent), capability = server.capability)

            assertEquals(200, response.statusCode())
            assertEquals(listOf(sent.single().eventId), stringList(json(response), "accepted"))
            assertEquals(listOf(sent.single().eventId), spool.pending().map { it.eventId })
        }
    }

    @Test
    fun `a replayed event id is a duplicate and is stored once`() {
        val spool = tempSpool()
        withServer(spool) { server ->
            val sent = events(1)

            val first = post(server.endpointUrl, body(sent), capability = server.capability)
            val second = post(server.endpointUrl, body(sent), capability = server.capability)

            assertEquals(listOf(sent.single().eventId), stringList(json(first), "accepted"))
            assertEquals(listOf(sent.single().eventId), stringList(json(second), "duplicate"))
            assertTrue(stringList(json(second), "accepted").isEmpty())
            assertEquals(1, spool.stats().totalCount, "a duplicate must never be appended twice")
            assertEquals(listOf(sent.single().eventId), spool.pending().map { it.eventId })
        }
    }

    @Test
    fun `malformed json is a 400`() {
        val spool = tempSpool()
        withServer(spool) { server ->
            val response = post(server.endpointUrl, "{not json", capability = server.capability)

            assertEquals(400, response.statusCode())
            assertTrue(spool.pending().isEmpty())
        }
    }

    @Test
    fun `an oversized body is a 413 and is not appended`() {
        val spool = tempSpool()
        withServer(spool, maxBodyBytes = 64) { server ->
            val response = post(server.endpointUrl, body(events(1), schemaVersion = "1"), capability = server.capability)

            assertEquals(413, response.statusCode())
            assertTrue(spool.pending().isEmpty())
        }
    }

    @Test
    fun `an unsupported schema version is a 400`() {
        val spool = tempSpool()
        withServer(spool) { server ->
            val response = post(server.endpointUrl, body(events(1), schemaVersion = "2"), capability = server.capability)

            assertEquals(400, response.statusCode())
            assertTrue(spool.pending().isEmpty())
        }
    }

    @Test
    fun `a mix of valid and invalid events is accepted and rejected`() {
        val spool = tempSpool()
        withServer(spool) { server ->
            val valid = events(1).single()
            val invalid = linkedMapOf<String, Any?>("event_id" to "broken", "event_type" to "tool.started")
            val payload =
                canonicalJson(
                    linkedMapOf(
                        "schema_version" to ResearchSpoolIpcServer.SCHEMA_VERSION,
                        "events" to listOf(valid.toCanonicalMap(), invalid),
                    ),
                )

            val response = post(server.endpointUrl, payload, capability = server.capability)

            assertEquals(200, response.statusCode())
            val parsed = json(response)
            assertEquals(listOf(valid.eventId), stringList(parsed, "accepted"))
            assertEquals(1, (parsed["rejected"] as? List<*>)?.size)
            assertEquals(listOf(valid.eventId), spool.pending().map { it.eventId })
        }
    }

    @Test
    fun `close is idempotent`() {
        val spool = tempSpool()
        val server = ResearchSpoolIpcServer(spool, "cap-close")

        server.close()
        server.close()
    }
}

// --------------------------------------------------------------------------
// SpoolUploaderTest.kt
// --------------------------------------------------------------------------

/**
 * Delivery-contract tests for [SpoolUploader] (Gap 3).
 *
 * A fake [Call.Factory] returns canned HTTP responses over a real temp-dir
 * [DurableSpool], so acknowledgement, rejection, retention, backoff, revocation,
 * quota, and restart-recovery semantics are exercised without any server.
 */
class SpoolUploaderTest {
    private class FakeCallFactory(
        private val responder: (Request) -> Response,
    ) : Call.Factory {
        val requests = ArrayList<Request>()

        override fun newCall(request: Request): Call = FakeCall(request, responder, requests)

        private class FakeCall(
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

            override fun clone(): Call = FakeCall(request, responder, requests)
        }
    }

    private fun events(count: Int): List<CanonicalEvent> {
        val builder = builder(emitterId = "acp-proxy", eventIds = IdSequence("evt"))
        return (1..count).map { builder.build(eventType = CanonicalEventTypes.TOOL_STARTED, payload = mapOf("index" to it)) }
    }

    private fun tempSpool(maxRecords: Int = Int.MAX_VALUE): DurableSpool =
        DurableSpool(Files.createTempDirectory("spool-uploader"), maxRecords = maxRecords)

    private fun bodyOf(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun ack(
        accepted: List<String> = emptyList(),
        duplicate: List<String> = emptyList(),
        rejected: List<Pair<String, String>> = emptyList(),
        retryable: List<String> = emptyList(),
    ): String =
        canonicalJson(
            linkedMapOf(
                "receipt_id" to "receipt-1",
                "server_time" to "2026-01-01T00:00:00Z",
                "accepted" to accepted,
                "duplicate" to duplicate,
                "rejected" to rejected.map { (id, reason) -> linkedMapOf("event_id" to id, "reason" to reason) },
                "retryable" to retryable,
            ),
        )

    private fun response(
        code: Int,
        body: String,
    ): Response =
        Response
            .Builder()
            .request(Request.Builder().url("http://localhost:8008/api/research/telemetry/batches").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun uploader(
        spool: DurableSpool,
        clock: () -> Long = { 1_000L },
        diagnostics: (String) -> Unit = {},
        retryBackoff: RetryBackoff = RetryBackoff(baseDelayMs = 100, maxDelayMs = 400, jitterSource = { 0.0 }),
        responder: (Request) -> Response,
    ): Pair<SpoolUploader, FakeCallFactory> {
        val factory = FakeCallFactory(responder)
        val uploader =
            SpoolUploader(
                spool = spool,
                serverBaseUrl = "http://localhost:8008",
                sessionCapability = linkedMapOf("capability_id" to "cap-1"),
                clientInstanceId = "client-1",
                httpClient = factory,
                backoff = retryBackoff,
                clock = clock,
                diagnostics = diagnostics,
            )
        return uploader to factory
    }

    @Test
    fun `success deletes only accepted and duplicate ids`() {
        val spool = tempSpool()
        val (first, second, third) = events(3)
        spool.append(first)
        spool.append(second)
        spool.append(third)
        val (uploader, _) =
            uploader(spool) { response(200, ack(accepted = listOf(first.eventId), duplicate = listOf(second.eventId))) }

        val result = uploader.uploadOnce()

        assertTrue(result.attempted)
        assertEquals(2, result.acknowledged)
        assertEquals(0, result.rejected)
        assertEquals(0, result.retryable)
        assertEquals(listOf(third.eventId), spool.pending().map { it.eventId })
    }

    @Test
    fun `a partial ack deletes only the acknowledged ids and retains retryable ones`() {
        val spool = tempSpool()
        val (first, second, third) = events(3)
        spool.append(first)
        spool.append(second)
        spool.append(third)
        val (uploader, _) =
            uploader(spool) { response(200, ack(accepted = listOf(first.eventId), retryable = listOf(second.eventId, third.eventId))) }

        val result = uploader.uploadOnce()

        assertEquals(1, result.acknowledged)
        assertEquals(2, result.retryable)
        assertEquals(setOf(second.eventId, third.eventId), spool.pending().map { it.eventId }.toSet())
    }

    @Test
    fun `a permanent rejection is discarded after a diagnostic`() {
        val spool = tempSpool()
        val (first, second) = events(2)
        spool.append(first)
        spool.append(second)
        val diagnostics = ArrayList<String>()
        val (uploader, _) =
            uploader(spool, diagnostics = diagnostics::add) { response(200, ack(rejected = listOf(second.eventId to "SCHEMA_INVALID"))) }

        val result = uploader.uploadOnce()

        assertEquals(1, result.rejected)
        assertEquals(1, result.discarded)
        assertTrue(diagnostics.any { it.contains("permanently rejected") }, diagnostics.toString())
        assertEquals(listOf(first.eventId), spool.pending().map { it.eventId })
    }

    @Test
    fun `a 5xx retains everything and schedules a backoff that defers the next attempt`() {
        val spool = tempSpool()
        val sent = events(2)
        sent.forEach { spool.append(it) }
        var now = 1_000L
        val (uploader, factory) = uploader(spool, clock = { now }) { response(503, "") }

        val result = uploader.uploadOnce()

        assertTrue(result.attempted)
        assertEquals(2, result.retryable)
        assertEquals(50L, result.backoffMs)
        assertEquals(now + result.backoffMs!!, result.retryAtEpochMs)
        assertEquals(result.retryAtEpochMs, uploader.state().nextAttemptAtEpochMs)
        assertEquals(2, spool.pending().size)
        assertEquals(1, factory.requests.size)

        val deferred = uploader.uploadOnce()

        assertTrue(deferred.deferred)
        assertFalse(deferred.attempted)
        assertEquals(1, factory.requests.size, "a deferred attempt must not hit the network")
    }

    @Test
    fun `an IOException retains everything and schedules a backoff`() {
        val spool = tempSpool()
        events(2).forEach { spool.append(it) }
        val (uploader, factory) = uploader(spool) { throw IOException("connection reset") }

        val result = uploader.uploadOnce()

        assertEquals(2, result.retryable)
        assertNotNull(result.retryAtEpochMs)
        assertEquals(2, spool.pending().size)
        assertEquals(1, factory.requests.size)
    }

    @Test
    fun `repeated uploads of the same batch never delete before an ack`() {
        val spool = tempSpool()
        val sent = events(1).single()
        spool.append(sent)
        var now = 1_000L
        val (uploader, _) = uploader(spool, clock = { now }) { response(500, "") }

        repeat(3) {
            val result = uploader.uploadOnce()
            assertEquals(0, result.acknowledged)
            assertEquals(listOf(sent.eventId), spool.pending().map { it.eventId })
            now += 1_000L
        }
    }

    @Test
    fun `401 stops uploads and deletes nothing unacknowledged`() {
        val spool = tempSpool()
        events(2).forEach { spool.append(it) }
        val (uploader, factory) = uploader(spool) { response(401, "") }

        val result = uploader.uploadOnce()

        assertTrue(result.revoked)
        assertEquals(2, spool.pending().size)
        assertTrue(uploader.state().revoked)

        val after = uploader.uploadOnce()

        assertTrue(after.revoked)
        assertFalse(after.attempted)
        assertEquals(1, factory.requests.size, "a revoked uploader must not keep retrying")
        assertEquals(2, spool.pending().size)
    }

    @Test
    fun `403 stops uploads and deletes nothing unacknowledged`() {
        val spool = tempSpool()
        events(2).forEach { spool.append(it) }
        val (uploader, factory) = uploader(spool) { response(403, "") }

        val result = uploader.uploadOnce()

        assertTrue(result.revoked)
        assertEquals(2, spool.pending().size)
        assertEquals(1, factory.requests.size)
    }

    @Test
    fun `an enrollment-not-active rejection stops uploads and deletes nothing unacknowledged`() {
        val spool = tempSpool()
        val (first, second) = events(2)
        spool.append(first)
        spool.append(second)
        val (uploader, factory) =
            uploader(spool) { response(200, ack(rejected = listOf(first.eventId to "ENROLLMENT_NOT_ACTIVE"))) }

        val result = uploader.uploadOnce()

        assertTrue(result.revoked)
        assertEquals(0, result.acknowledged)
        assertEquals(0, result.rejected, "a revocation-class rejection is never discarded as permanent")
        assertEquals(0, result.discarded)
        assertEquals(2, spool.pending().size, "revocation must never delete unacknowledged data")
        assertTrue(uploader.state().revoked)
        val request = factory.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/research/telemetry/batches", request.url.encodedPath)
    }

    @Test
    fun `a stopped-study rejection stops polling and is never discarded`() {
        val spool = tempSpool()
        val (first, second) = events(2)
        spool.append(first)
        spool.append(second)
        val (uploader, factory) =
            uploader(spool) { response(200, ack(rejected = listOf(first.eventId to "STUDY_STOPPED"))) }

        val result = uploader.uploadOnce()

        assertTrue(result.attempted)
        assertTrue(result.revoked)
        assertEquals(0, result.acknowledged)
        assertEquals(0, result.rejected, "a terminal-study rejection is never discarded as permanent")
        assertEquals(0, result.discarded)
        assertEquals(2, spool.pending().size, "a stopped study must never delete unacknowledged data")
        assertTrue(uploader.state().revoked)

        val request = factory.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/research/telemetry/batches", request.url.encodedPath)

        val after = uploader.uploadOnce()

        assertTrue(after.revoked)
        assertFalse(after.attempted)
        assertEquals(1, factory.requests.size, "a stopped-study uploader must not keep polling")
        assertEquals(2, spool.pending().size)
    }

    @Test
    fun `a revocation reason stops uploads and deletes nothing unacknowledged`() {
        val spool = tempSpool()
        val (first, second) = events(2)
        spool.append(first)
        spool.append(second)
        val (uploader, _) =
            uploader(spool) { response(200, ack(rejected = listOf(first.eventId to "REVOKED"))) }

        val result = uploader.uploadOnce()

        assertTrue(result.revoked)
        assertEquals(0, result.acknowledged)
        assertEquals(2, spool.pending().size, "revocation must never delete unacknowledged data")
        assertTrue(uploader.state().revoked)
    }

    @Test
    fun `state reports spool quota as full`() {
        val spool = tempSpool(maxRecords = 1)
        events(2).forEach { spool.append(it) }
        val (uploader, _) = uploader(spool) { response(500, "") }

        uploader.uploadOnce()

        assertTrue(uploader.state().spoolFull)
        assertEquals(2, uploader.state().pendingCount)
    }

    @Test
    fun `a refreshed capability clears a prior revoked state and delivery resumes with it`() {
        val spool = tempSpool()
        val (first, second) = events(2)
        spool.append(first)
        spool.append(second)
        var now = 1_000L
        val seenCapabilities = ArrayList<String?>()
        val (uploader, factory) =
            uploader(spool, clock = { now }) { request ->
                val body = parseCanonicalJson(bodyOf(request)) as Map<*, *>
                val capability = (body["session_capability"] as? Map<*, *>)?.get("capability_id") as? String
                seenCapabilities.add(capability)
                if (seenCapabilities.size == 1) {
                    response(401, "")
                } else {
                    response(200, ack(accepted = listOf(first.eventId, second.eventId)))
                }
            }

        val refused = uploader.uploadOnce()
        assertTrue(refused.revoked)
        assertTrue(uploader.state().revoked)
        assertEquals(2, spool.pending().size, "a refusal must delete nothing")

        val adopted = uploader.updateCapability(linkedMapOf("capability_id" to "cap-2"))

        assertTrue(adopted)
        assertFalse(uploader.state().revoked, "a refreshed capability must clear the revoked state")

        val resumed = uploader.uploadOnce()

        assertTrue(resumed.acknowledged == 2)
        assertEquals(2, factory.requests.size)
        assertEquals(listOf("cap-1", "cap-2"), seenCapabilities)
        assertTrue(spool.pending().isEmpty())
    }

    @Test
    fun `an empty capability is refused without clearing a revocation`() {
        val spool = tempSpool()
        events(1).forEach { spool.append(it) }
        val (uploader, _) = uploader(spool) { response(403, "") }
        assertTrue(uploader.uploadOnce().revoked)

        val adopted = uploader.updateCapability(emptyMap())

        assertFalse(adopted)
        assertTrue(uploader.state().revoked, "an unusable capability must not lift a revocation")
    }

    @Test
    fun `a new uploader over the same spool directory resumes pending records`() {
        val directory = Files.createTempDirectory("spool-uploader-restart")
        val (first, second) = events(2)
        val firstSpool = DurableSpool(directory)
        firstSpool.append(first)
        firstSpool.append(second)
        val (offline, _) = uploader(firstSpool) { throw IOException("backend down") }
        assertEquals(2, offline.uploadOnce().retryable)

        val reopened = DurableSpool(directory)
        assertEquals(2, reopened.pending().size, "pending records must survive a restart")
        val (recovered, _) = uploader(reopened) { response(200, ack(accepted = listOf(first.eventId, second.eventId))) }

        val result = recovered.uploadOnce()

        assertEquals(2, result.acknowledged)
        assertTrue(reopened.pending().isEmpty())
    }
}
