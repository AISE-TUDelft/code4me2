package me.code4me.research.spool

import me.code4me.research.telemetry.CanonicalEvent
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.spool.SpoolRecord
import java.util.UUID

/**
 * One upload request (Issue 09, `TelemetryBatchRequestV1`).
 *
 * `events` are already privacy-filtered canonical envelopes and `sessionCapability`
 * is the short-lived, server-verifiable proof of enrollment/revision/session
 * scope. The optional `previousAckCursor` lets the server drop already-received
 * ids without a round trip.
 */
data class TelemetryBatchRequestV1(
    val batchId: String,
    val protocolVersion: String,
    val telemetrySchemaVersion: String,
    val sessionCapability: String,
    val events: List<CanonicalEvent>,
    val clientInstanceId: String,
    val previousAckCursor: String? = null,
) {
    init {
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        require(sessionCapability.isNotBlank()) { "sessionCapability must not be blank" }
        require(clientInstanceId.isNotBlank()) { "clientInstanceId must not be blank" }
    }

    val size: Int
        get() = events.size

    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "batch_id" to batchId,
            "protocol_version" to protocolVersion,
            "telemetry_schema_version" to telemetrySchemaVersion,
            "session_capability" to sessionCapability,
            "client_instance_id" to clientInstanceId,
            "previous_ack_cursor" to previousAckCursor,
            "events" to events.map { it.toCanonicalMap() },
        )

    fun toCanonicalJson(): String = canonicalJson(toCanonicalMap())
}

/**
 * Groups pending spool records into bounded batches (Issue 09).
 *
 * Batches are bounded by both event count and canonical byte size so a single
 * oversized record cannot be split and a request cannot grow without limit.
 */
class TelemetryBatchBuilder(
    private val protocolVersion: String = "1",
    private val telemetrySchemaVersion: String = "1",
    private val maxEventsPerBatch: Int = 100,
    private val maxBytesPerBatch: Int = 1_000_000,
    private val batchIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    init {
        require(maxEventsPerBatch > 0) { "maxEventsPerBatch must be positive" }
        require(maxBytesPerBatch > 0) { "maxBytesPerBatch must be positive" }
    }

    /** Build one bounded batch from [records]; requires at least one record. */
    fun build(
        records: List<SpoolRecord>,
        sessionCapability: String,
        clientInstanceId: String,
        previousAckCursor: String? = null,
    ): TelemetryBatchRequestV1 {
        require(records.isNotEmpty()) { "Cannot build an empty telemetry batch" }
        val selected = ArrayList<CanonicalEvent>()
        var bytes = 0
        for (record in records) {
            if (selected.size >= maxEventsPerBatch) break
            val eventBytes = record.canonicalJson.toByteArray(Charsets.UTF_8).size
            if (selected.isNotEmpty() && bytes + eventBytes > maxBytesPerBatch) break
            selected.add(record.event)
            bytes += eventBytes
        }
        return TelemetryBatchRequestV1(
            batchId = batchIdFactory(),
            protocolVersion = protocolVersion,
            telemetrySchemaVersion = telemetrySchemaVersion,
            sessionCapability = sessionCapability,
            events = selected,
            clientInstanceId = clientInstanceId,
            previousAckCursor = previousAckCursor,
        )
    }

    /** Partition [records] into as many bounded batches as needed. */
    fun buildBatches(
        records: List<SpoolRecord>,
        sessionCapability: String,
        clientInstanceId: String,
        previousAckCursor: String? = null,
    ): List<TelemetryBatchRequestV1> {
        if (records.isEmpty()) return emptyList()
        val batches = ArrayList<TelemetryBatchRequestV1>()
        var index = 0
        while (index < records.size) {
            val batch = build(records.subList(index, records.size), sessionCapability, clientInstanceId, previousAckCursor)
            batches.add(batch)
            index += batch.size
        }
        return batches
    }
}
