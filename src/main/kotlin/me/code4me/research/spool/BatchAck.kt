package me.code4me.research.spool

import me.code4me.research.telemetry.parseCanonicalJson
import me.code4me.research.spool.DurableSpool

/**
 * One durable ingestion response (Issue 09, `TelemetryBatchAckV1`).
 *
 * Event ids are grouped by disposition. `reasons` carries typed, permanent
 * reasons for [rejected]; `retryHints` may carry a server-suggested delay for
 * [retryable] ids, in **milliseconds** (the wire `retry_hint` is in seconds and
 * is converted on parse, so a "retry in 30 s" is never read as 30 ms). A
 * missing acknowledgement is never treated as success: see
 * [BatchAckHandler.apply].
 */
data class TelemetryBatchAckV1(
    val receiptId: String,
    val serverTime: String,
    val accepted: List<String> = emptyList(),
    val duplicate: List<String> = emptyList(),
    val rejected: List<String> = emptyList(),
    val retryable: List<String> = emptyList(),
    val reasons: Map<String, String> = emptyMap(),
    val retryHints: Map<String, Long> = emptyMap(),
) {
    init {
        require(receiptId.isNotBlank()) { "receiptId must not be blank" }
    }

    /** Ids the server durably holds and the spool may delete: accepted + duplicate. */
    fun acknowledgedIds(): List<String> = (accepted + duplicate).distinct()

    val isEmpty: Boolean
        get() = accepted.isEmpty() && duplicate.isEmpty() && rejected.isEmpty() && retryable.isEmpty()

    companion object {
        /** Permanent, revocation-class reasons that must stop further uploads. */
        val REVOCATION_REASONS: Set<String> =
            setOf("REVOKED", "ENROLLMENT_NOT_ACTIVE", "SESSION_TERMINAL", "STUDY_STOPPED")

        /**
         * Parse the server's `TelemetryBatchAckV1` wire JSON.
         *
         * The server groups per-event [EventAck] objects (`event_id`,
         * `disposition`, `reason`, `retry_hint`, `stored_digest`); this maps them
         * onto the client's id lists plus [reasons]/[retryHints]. Unknown keys are
         * ignored. Throws when the payload is not a usable acknowledgement, so the
         * caller treats it as a retryable transport outcome rather than deleting
         * anything.
         */
        fun fromWireText(text: String): TelemetryBatchAckV1 {
            val parsed = parseCanonicalJson(text)
            require(parsed is Map<*, *>) { "acknowledgement must be a JSON object" }
            return fromWireMap(stringKeyed(parsed))
        }

        /** Parse an already-decoded acknowledgement map. */
        fun fromWireMap(map: Map<String, Any?>): TelemetryBatchAckV1 {
            val receiptId = map["receipt_id"] as? String
            require(!receiptId.isNullOrBlank()) { "acknowledgement is missing receipt_id" }
            val serverTime = map["server_time"] as? String ?: ""
            val topLevelHint = (map["retry_hint"] as? Number)?.let(::hintSecondsToMillis)
            return TelemetryBatchAckV1(
                receiptId = receiptId,
                serverTime = serverTime,
                accepted = idsOf(map["accepted"]),
                duplicate = idsOf(map["duplicate"]),
                rejected = idsOf(map["rejected"]),
                retryable = idsOf(map["retryable"]),
                reasons = reasonsOf(map["rejected"]),
                retryHints = retryHintsOf(map["retryable"], topLevelHint),
            )
        }

        private fun stringKeyed(map: Map<*, *>): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>(map.size)
            for ((key, value) in map) {
                result[key?.toString() ?: return emptyMap()] = value
            }
            return result
        }

        private fun idsOf(value: Any?): List<String> =
            (value as? List<*>)?.mapNotNull { item ->
                when (item) {
                    is String -> item
                    is Map<*, *> -> item["event_id"] as? String
                    else -> null
                }
            } ?: emptyList()

        private fun reasonsOf(value: Any?): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            (value as? List<*>)?.forEach { item ->
                if (item is Map<*, *>) {
                    val id = item["event_id"] as? String
                    val reason = item["reason"] as? String
                    if (id != null && reason != null) result[id] = reason
                }
            }
            return result
        }

        private fun retryHintsOf(
            value: Any?,
            topLevelHint: Long?,
        ): Map<String, Long> {
            val result = LinkedHashMap<String, Long>()
            (value as? List<*>)?.forEach { item ->
                if (item is Map<*, *>) {
                    val id = item["event_id"] as? String
                    val hint = (item["retry_hint"] as? Number)?.let(::hintSecondsToMillis) ?: topLevelHint
                    if (id != null && hint != null) result[id] = hint
                }
            }
            return result
        }

        /** The server's `retry_hint` is whole seconds (`DEFAULT_RETRY_HINT_SECONDS`). */
        private fun hintSecondsToMillis(seconds: Number): Long = (seconds.toDouble() * 1_000.0).toLong()
    }
}

/**
 * Capped exponential backoff with jitter.
 *
 * Deterministic for tests: inject [jitterSource]. The returned delay is in
 * `[full/2, full]`, where `full` doubles each attempt and is capped at
 * [maxDelayMs].
 */
class RetryBackoff(
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 60_000,
    val jitterSource: () -> Double = { Math.random() },
) {
    init {
        require(baseDelayMs > 0) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be at least baseDelayMs" }
    }

    /** Delay for [attempt] (1-based). */
    fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1" }
        var full = baseDelayMs
        var remaining = attempt - 1
        while (remaining > 0 && full < maxDelayMs) {
            full = if (full > maxDelayMs / 2) maxDelayMs else full * 2
            remaining--
        }
        full = full.coerceAtMost(maxDelayMs)
        val half = full / 2
        val jitter = jitterSource().coerceIn(0.0, 1.0)
        return (half + (jitter * (full - half)).toLong()).coerceIn(1L, maxDelayMs)
    }
}

/** What [BatchAckHandler.apply] did to the spool and retry bookkeeping. */
data class AckApplicationResult(
    val acknowledgedIds: List<String>,
    val rejectedIds: List<String>,
    val retryableIds: List<String>,
    val acknowledgedCount: Int,
    val retryDelaysMs: Map<String, Long>,
)

/**
 * Applies server acknowledgements to a [DurableSpool].
 *
 * Invariants:
 * - a `null`/missing ACK acknowledges nothing and leaves the spool intact;
 * - only `accepted` + `duplicate` ids are handed to [DurableSpool.acknowledge];
 * - `rejected` ids are permanent and are neither retried nor deleted here;
 * - `retryable` ids stay spooled and receive capped exponential backoff.
 */
class BatchAckHandler(
    private val spool: DurableSpool,
    private val backoff: RetryBackoff = RetryBackoff(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val attempts = LinkedHashMap<String, Int>()
    private val nextAttemptAtEpochMs = LinkedHashMap<String, Long>()

    @Synchronized
    fun apply(ack: TelemetryBatchAckV1?): AckApplicationResult {
        if (ack == null) {
            // Timeout/network failure: never assume acceptance.
            return AckApplicationResult(emptyList(), emptyList(), emptyList(), 0, emptyMap())
        }
        val acknowledged = ack.acknowledgedIds()
        val acknowledgedCount = spool.acknowledge(acknowledged)
        acknowledged.forEach {
            attempts.remove(it)
            nextAttemptAtEpochMs.remove(it)
        }
        ack.rejected.distinct().forEach {
            attempts.remove(it)
            nextAttemptAtEpochMs.remove(it)
        }
        val delays = LinkedHashMap<String, Long>()
        ack.retryable.distinct().forEach { id ->
            if (id in acknowledged) return@forEach
            val attempt = (attempts[id] ?: 0) + 1
            attempts[id] = attempt
            val delay = ack.retryHints[id]?.coerceAtLeast(1L) ?: backoff.delayForAttempt(attempt)
            delays[id] = delay
            nextAttemptAtEpochMs[id] = clock() + delay
        }
        return AckApplicationResult(
            acknowledgedIds = acknowledged,
            rejectedIds = ack.rejected.distinct(),
            retryableIds = ack.retryable.distinct(),
            acknowledgedCount = acknowledgedCount,
            retryDelaysMs = delays,
        )
    }

    /** Number of recorded retry attempts for [eventId] (0 if never retryable). */
    @Synchronized
    fun attemptFor(eventId: String): Int = attempts[eventId] ?: 0

    /** Epoch millis after which [eventId] may be retried, or `null` if not retryable. */
    @Synchronized
    fun nextAttemptAt(eventId: String): Long? = nextAttemptAtEpochMs[eventId]

    /** True when [eventId] is not waiting on a backoff window. */
    @Synchronized
    fun isReady(
        eventId: String,
        atEpochMs: Long = clock(),
    ): Boolean = (nextAttemptAtEpochMs[eventId] ?: 0L) <= atEpochMs
}
