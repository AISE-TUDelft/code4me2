package me.code4me.research.spool

import me.code4me.research.telemetry.CanonicalEvent
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.sha256Hex

/**
 * One durable spool line (Issue 07, `SpoolRecordV1`).
 *
 * The line is newline-delimited canonical JSON. [canonicalJson] is the exact
 * sanitized event bytes and [digest] is their SHA-256; the server can reproduce
 * both. [createdAtEpochMs] is the local append time used for age/quota
 * reporting.
 */
data class SpoolRecord(
    val eventId: String,
    val emitterId: String,
    val emitterSequence: Long,
    val digest: String,
    val canonicalJson: String,
    val createdAtEpochMs: Long,
    val event: CanonicalEvent,
) {
    init {
        require(eventId == event.eventId) { "SpoolRecord eventId must match the event" }
        require(digest == sha256Hex(canonicalJson)) { "SpoolRecord digest must match its canonical JSON" }
    }

    /** Canonical JSON map for this line (also the append format). */
    fun toCanonicalMap(): Map<String, Any?> =
        linkedMapOf(
            "record_version" to RECORD_VERSION,
            "event_id" to eventId,
            "emitter_id" to emitterId,
            "emitter_sequence" to emitterSequence,
            "event_digest" to digest,
            "created_at_ms" to createdAtEpochMs,
            "event" to event.toCanonicalMap(),
        )

    /** The single-line record text written to the spool. */
    fun toCanonicalJson(): String = canonicalJson(toCanonicalMap())

    companion object {
        const val RECORD_VERSION = "1"

        /** Wrap a freshly built event into a durable record. */
        fun of(
            event: CanonicalEvent,
            createdAtEpochMs: Long,
        ): SpoolRecord {
            val json = event.toCanonicalJson()
            return SpoolRecord(
                eventId = event.eventId,
                emitterId = event.emitterId,
                emitterSequence = event.emitterSequence,
                digest = sha256Hex(json),
                canonicalJson = json,
                createdAtEpochMs = createdAtEpochMs,
                event = event,
            )
        }

        /**
         * Rehydrate one stored record. Throws when the line is malformed or its
         * embedded digest does not match the re-canonicalized event, so the spool
         * can quarantine a corrupt tail instead of trusting it.
         */
        @Suppress("UNCHECKED_CAST")
        fun fromCanonicalMap(map: Map<String, Any?>): SpoolRecord {
            val eventMap =
                map["event"] as? Map<String, Any?>
                    ?: throw IllegalArgumentException("Spool record is missing 'event'")
            val event = CanonicalEvent.fromCanonicalMap(eventMap)
            val json = event.toCanonicalJson()
            val digest = sha256Hex(json)
            val storedDigest = map["event_digest"] as? String
            require(storedDigest == null || storedDigest == digest) {
                "Spool record digest mismatch for event ${event.eventId}"
            }
            return SpoolRecord(
                eventId = event.eventId,
                emitterId = event.emitterId,
                emitterSequence = event.emitterSequence,
                digest = digest,
                canonicalJson = json,
                createdAtEpochMs = (map["created_at_ms"] as? Number)?.toLong() ?: 0L,
                event = event,
            )
        }
    }
}
