package me.code4me.research.spool

import me.code4me.research.spool.RetryBackoff
import me.code4me.research.spool.TelemetryBatchAckV1
import me.code4me.research.telemetry.canonicalJson
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/**
 * A session-owned delivery component whose lifetime the manager owns.
 *
 * [start] begins background delivery (if any); [close] stops it and is
 * idempotent. Implementations must never throw out of either method.
 */
interface SpoolDelivery {
    /** Begin automatic delivery; idempotent. */
    fun start()

    /** Stop automatic delivery and release resources; idempotent. */
    fun close()

    /** Whether automatic delivery is currently running. */
    val isRunning: Boolean

    /**
     * Adopt a refreshed session capability for future deliveries.
     *
     * A capability is short-lived, so a `401`/`403` is usually just an expired
     * credential rather than a revocation. Adopting a freshly bootstrapped
     * capability must therefore clear any prior revoked state so delivery
     * resumes without an IDE restart. Returns `true` when the capability was
     * adopted; the default is a best-effort no-op for deliveries that do not
     * authenticate with a session capability.
     */
    fun updateCapability(capability: Map<String, Any?>): Boolean = false

    /**
     * One bounded best-effort delivery pass used by the pre-withdrawal flush.
     * Returns `null` for deliveries that do not support a manual pass; never
     * throws and never blocks indefinitely (the caller imposes the timeout).
     */
    fun drainOnce(): SpoolUploadResult? = null

    /**
     * Participant-safe snapshot of the current delivery state. Never exposes the
     * upload URL, capability, event ids, or raw server error text.
     */
    fun state(): SpoolUploaderState = SpoolUploaderState()
}

/** Everything the manager must supply to construct a spool uploader. */
data class SpoolUploaderContext(
    val spool: DurableSpool,
    val serverBaseUrl: String,
    val sessionCapability: Map<String, Any?>,
    val clientInstanceId: String,
    val httpClient: Call.Factory,
)

/** Participant-safe snapshot of the uploader's delivery state. */
data class SpoolUploaderState(
    val revoked: Boolean = false,
    val spoolFull: Boolean = false,
    val nextAttemptAtEpochMs: Long? = null,
    val lastError: String? = null,
    val pendingCount: Int = 0,
    val lastUploadAtEpochMs: Long? = null,
)

/** Outcome of one [SpoolUploader.uploadOnce] call. */
data class SpoolUploadResult(
    val attempted: Boolean = false,
    val acknowledged: Int = 0,
    val rejected: Int = 0,
    val discarded: Int = 0,
    val retryable: Int = 0,
    val revoked: Boolean = false,
    val deferred: Boolean = false,
    val retryAtEpochMs: Long? = null,
    val backoffMs: Long? = null,
    val pendingCount: Int = 0,
    val error: String? = null,
)

/**
 * Local spool uploader (Gap 3).
 *
 * It takes up to [maxEventsPerBatch] pending records, builds a
 * `TelemetryBatchRequestV1` JSON body (with the signed `session_capability`
 * object from the validated manifest), POSTs it to
 * `{serverBaseUrl}/api/research/telemetry/batches` through the injected OkHttp
 * [Call.Factory], and applies the returned `TelemetryBatchAckV1`:
 *
 * - only `accepted` + `duplicate` ids are acknowledged/deleted from the spool;
 * - permanently `rejected` ids are dropped after a local diagnostic so they are
 *   never retried forever;
 * - `retryable` ids, transport failures, and `5xx` retain everything and retry
 *   with capped exponential backoff + jitter ([RetryBackoff]);
 * - a `401`/`403` or a `REVOKED`/`ENROLLMENT_NOT_ACTIVE`/`STUDY_STOPPED`
 *   disposition stops uploads and deletes nothing unacknowledged.
 *
 * Restart recovery is purely from persisted spool state: there is no in-memory
 * delivery cursor. `previous_ack_cursor` is omitted (null) because a deterministic
 * cursor is not persisted.
 */
class SpoolUploader(
    private val spool: DurableSpool,
    serverBaseUrl: String,
    sessionCapability: Map<String, Any?>,
    private val clientInstanceId: String,
    private val httpClient: Call.Factory,
    private val maxEventsPerBatch: Int = DEFAULT_MAX_EVENTS_PER_BATCH,
    private val backoff: RetryBackoff = RetryBackoff(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val diagnostics: (String) -> Unit = {},
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val batchIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val sleep: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) : SpoolDelivery {
    init {
        require(serverBaseUrl.isNotBlank()) { "serverBaseUrl must not be blank" }
        require(clientInstanceId.isNotBlank()) { "clientInstanceId must not be blank" }
        require(maxEventsPerBatch > 0) { "maxEventsPerBatch must be positive" }
        require(pollIntervalMs > 0) { "pollIntervalMs must be positive" }
    }

    private val endpoint: String = serverBaseUrl.trim().trimEnd('/') + BATCHES_PATH

    private val lock = Any()

    /**
     * The capability used for every future batch. Mutable because a capability
     * expires long before a session does: the manager hands a re-bootstrapped
     * capability to the already-running uploader through [updateCapability]
     * instead of tearing the uploader down and rebuilding it.
     */
    @Volatile private var currentCapability: Map<String, Any?> = sessionCapability

    @Volatile private var revoked = false

    @Volatile private var running = false

    @Volatile private var worker: Thread? = null

    @Volatile private var lastError: String? = null

    @Volatile private var nextAttemptAtEpochMs: Long? = null

    @Volatile private var lastBackoffMs: Long? = null

    @Volatile private var spoolFull = false

    @Volatile private var lastUploadAtEpochMs: Long? = null

    private var attempts = 0

    override val isRunning: Boolean
        get() = running

    /**
     * Adopt [capability] for future batches and clear a prior revoked state.
     *
     * An expired capability is the common cause of a `401`/`403`, so a refresh
     * must be able to lift the revocation the uploader recorded for it: the
     * worker thread keeps polling and resumes delivery on the next cycle.
     * Never throws; a blank capability is refused without mutating state.
     */
    override fun updateCapability(capability: Map<String, Any?>): Boolean {
        if (capability.isEmpty()) return false
        synchronized(lock) {
            currentCapability = capability
            val wasRevoked = revoked
            revoked = false
            lastError = null
            resetBackoff()
            if (wasRevoked) {
                diagnostics("proxy: telemetry capability refreshed; resuming delivery")
            }
        }
        return true
    }

    /** Participant-safe snapshot of the delivery state. */
    override fun state(): SpoolUploaderState =
        SpoolUploaderState(
            revoked = revoked,
            spoolFull = spoolFull,
            nextAttemptAtEpochMs = nextAttemptAtEpochMs,
            lastError = lastError,
            pendingCount = pendingCount(),
            lastUploadAtEpochMs = lastUploadAtEpochMs,
        )

    /**
     * Attempt exactly one upload pass. Never throws: every failure is returned as
     * a typed [SpoolUploadResult] and leaves the spool intact for a later retry.
     */
    fun uploadOnce(): SpoolUploadResult =
        synchronized(lock) {
            if (revoked) {
                return@synchronized SpoolUploadResult(attempted = false, revoked = true, pendingCount = pendingCount())
            }
            refreshQuota()
            val now = clock()
            val readyAt = nextAttemptAtEpochMs
            if (readyAt != null && readyAt > now) {
                return@synchronized SpoolUploadResult(
                    attempted = false,
                    deferred = true,
                    retryAtEpochMs = readyAt,
                    pendingCount = pendingCount(),
                )
            }
            val records =
                try {
                    spool.pending(maxEventsPerBatch)
                } catch (exception: Exception) {
                    return@synchronized failure("spool read failed: ${exception.message}")
                }
            if (records.isEmpty()) {
                lastError = null
                return@synchronized SpoolUploadResult(attempted = false, pendingCount = 0)
            }
            val body = canonicalJson(buildBatch(records))
            when (val transport = execute(body)) {
                is Transport.Ack -> applyAck(transport.ack, records)
                is Transport.Revoked -> {
                    markRevoked(transport.detail)
                    SpoolUploadResult(
                        attempted = true,
                        revoked = true,
                        pendingCount = pendingCount(),
                        error = transport.detail,
                    )
                }
                is Transport.Retryable -> {
                    val delay = scheduleBackoff()
                    SpoolUploadResult(
                        attempted = true,
                        retryable = records.size,
                        retryAtEpochMs = nextAttemptAtEpochMs,
                        backoffMs = delay,
                        pendingCount = pendingCount(),
                        error = transport.detail,
                    )
                }
            }
        }

    override fun drainOnce(): SpoolUploadResult? = uploadOnce()

    override fun start() {
        if (running) return
        running = true
        val thread =
            Thread(
                {
                    while (running) {
                        val result =
                            try {
                                uploadOnce()
                            } catch (_: Exception) {
                                null
                            }
                        if (!running) break
                        val delay = delayBeforeNextCycle(result)
                        try {
                            sleep(delay)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                },
                THREAD_NAME,
            )
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    override fun close() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun delayBeforeNextCycle(result: SpoolUploadResult?): Long {
        val retryAt = result?.retryAtEpochMs
        val remaining = retryAt?.let { (it - clock()).coerceAtLeast(0L) }
        return when {
            remaining != null && remaining > 0 -> remaining
            else -> pollIntervalMs
        }.coerceAtLeast(1L)
    }

    private fun buildBatch(records: List<SpoolRecord>): Map<String, Any?> =
        linkedMapOf(
            "batch_id" to batchIdFactory(),
            "protocol_version" to PROTOCOL_VERSION,
            "telemetry_schema_version" to TELEMETRY_SCHEMA_VERSION,
            "session_capability" to currentCapability,
            "client_instance_id" to clientInstanceId,
            "previous_ack_cursor" to null,
            "events" to records.map { it.event.toCanonicalMap() },
        )

    private fun applyAck(
        ack: TelemetryBatchAckV1,
        records: List<SpoolRecord>,
    ): SpoolUploadResult {
        val acknowledged = ack.acknowledgedIds()
        val revokedRejected = ack.rejected.filter { ack.reasons[it] in TelemetryBatchAckV1.REVOCATION_REASONS }
        val permanentRejected = ack.rejected.filterNot { it in revokedRejected }
        val acknowledgedCount =
            try {
                spool.acknowledge(acknowledged)
            } catch (exception: Exception) {
                return failure("spool acknowledge failed: ${exception.message}")
            }
        var discarded = 0
        if (permanentRejected.isNotEmpty()) {
            diagnostics("proxy: server permanently rejected ${permanentRejected.size} event(s); dropping them")
            discarded =
                try {
                    spool.discard(permanentRejected)
                } catch (exception: Exception) {
                    return failure("spool discard failed: ${exception.message}")
                }
        }
        if (revokedRejected.isNotEmpty()) {
            // Revocation: never delete the revoked (unacknowledged) data.
            markRevoked("enrollment revoked by the server")
        }
        if (ack.retryable.isNotEmpty() && acknowledgedCount == 0) {
            scheduleBackoff()
        } else {
            resetBackoff()
        }
        runCatching { spool.compact() }
        lastUploadAtEpochMs = clock()
        lastError = null
        refreshQuota()
        return SpoolUploadResult(
            attempted = true,
            acknowledged = acknowledgedCount,
            rejected = permanentRejected.size,
            discarded = discarded,
            retryable = ack.retryable.size,
            revoked = revoked,
            retryAtEpochMs = nextAttemptAtEpochMs,
            pendingCount = pendingCount(),
        )
    }

    private sealed interface Transport {
        data class Ack(val ack: TelemetryBatchAckV1) : Transport

        data class Retryable(val detail: String) : Transport

        data class Revoked(val detail: String) : Transport
    }

    private fun execute(body: String): Transport =
        try {
            val request =
                Request
                    .Builder()
                    .url(endpoint)
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json")
                    .build()
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string().orEmpty()
                when {
                    code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN ->
                        Transport.Revoked("server refused the session capability (HTTP $code)")
                    response.isSuccessful -> {
                        val ack = runCatching { TelemetryBatchAckV1.fromWireText(responseBody) }.getOrNull()
                        if (ack == null) {
                            Transport.Retryable("telemetry acknowledgement was not parseable (HTTP $code)")
                        } else {
                            Transport.Ack(ack)
                        }
                    }
                    else -> Transport.Retryable("telemetry upload failed with HTTP $code")
                }
            }
        } catch (exception: IOException) {
            Transport.Retryable("telemetry upload failed: ${exception.message ?: "network error"}")
        } catch (exception: Exception) {
            Transport.Retryable("telemetry upload failed: ${exception.message ?: "unexpected error"}")
        }

    private fun markRevoked(detail: String) {
        revoked = true
        lastError = detail
        nextAttemptAtEpochMs = null
        diagnostics("proxy: telemetry upload stopped: $detail")
    }

    private fun scheduleBackoff(): Long {
        val delay = backoff.delayForAttempt(attempts + 1)
        attempts += 1
        lastBackoffMs = delay
        lastError = lastError ?: "telemetry upload will be retried"
        nextAttemptAtEpochMs = clock() + delay
        return delay
    }

    private fun resetBackoff() {
        attempts = 0
        lastBackoffMs = null
        nextAttemptAtEpochMs = null
    }

    private fun refreshQuota() {
        spoolFull =
            try {
                spool.stats().quotaExceeded
            } catch (_: Exception) {
                spoolFull
            }
    }

    private fun pendingCount(): Int =
        try {
            spool.stats().pendingCount
        } catch (_: Exception) {
            0
        }

    private fun failure(detail: String): SpoolUploadResult {
        lastError = detail
        return SpoolUploadResult(attempted = false, pendingCount = pendingCount(), error = detail)
    }

    companion object {
        const val PROTOCOL_VERSION: String = "1"
        const val TELEMETRY_SCHEMA_VERSION: String = "1"
        const val BATCHES_PATH: String = "/api/research/telemetry/batches"
        const val DEFAULT_MAX_EVENTS_PER_BATCH: Int = 100
        const val DEFAULT_POLL_INTERVAL_MS: Long = 5_000L

        private const val THREAD_NAME = "code4me-research-spool-uploader"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
