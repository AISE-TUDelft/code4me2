package me.code4me.research.spool

import me.code4me.research.telemetry.CanonicalEvent
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.parseCanonicalJson
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Snapshot of spool state, used for participant-safe status and quota decisions. */
data class SpoolStats(
    val totalCount: Int,
    val pendingCount: Int,
    val ackedCount: Int,
    val oldestPendingAgeMs: Long?,
    val retainedBytes: Long,
    val quotaExceeded: Boolean,
    val indicators: List<String> = emptyList(),
)

/**
 * Append-only, crash-safe local spool of sanitized canonical events (Issue 07).
 *
 * The spool has no JDBC dependency: each event is one line of canonical JSON in
 * `spool.log`, flushed and `fsync`ed on append. Acknowledgements are tracked in
 * a separate `ack.log` cursor, and only acknowledged records are ever removed.
 *
 * Delivery contract:
 * - [append] persists before any transport attempt.
 * - [pending] returns exactly the un-acknowledged records, once each, and never
 *   an already-acknowledged one.
 * - [acknowledge] is only ever called with server-accepted/duplicate ids.
 * - a re-opened [DurableSpool] over the same directory sees the same pending set.
 *
 * Crash safety:
 * - a corrupt line is quarantined to `quarantine-*.bin` while every valid record
 *   (including ones after the bad line) is preserved and the log is rewritten
 *   durably.
 * - quota pressure compacts only acknowledged records and emits the
 *   `spool_quota_exceeded` indicator; un-acknowledged behavioral data is never
 *   silently discarded.
 *
 * The spool is bounded by default ([DEFAULT_MAX_BYTES]); durability comes from
 * that bound plus prompt upload, not from a persistent path (product decision
 * D-4).
 *
 * @property directory local spool directory; created if missing.
 * @property maxBytes retained-byte quota before compaction/diagnostics.
 * @property maxRecords retained-record quota before compaction/diagnostics.
 * @property clock local epoch-millis clock, injectable for deterministic tests.
 */
class DurableSpool(
    val directory: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxRecords: Int = Int.MAX_VALUE,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val spoolFile: Path = directory.resolve("spool.log")
    private val ackFile: Path = directory.resolve("ack.log")

    /**
     * True when an acknowledgement/discard landed since the last compaction, so
     * compacting on append can actually shrink the log. Over quota with nothing
     * acknowledged, every append would otherwise re-read the whole file.
     */
    @Volatile private var ackedSinceCompaction = true

    init {
        Files.createDirectories(directory)
    }

    /**
     * Quarantine this spool's directory so its pending records can never be
     * uploaded under a different account (logout/account switch, Issue 03 F13).
     * Best-effort and never throws; the directory is renamed aside atomically
     * where the filesystem allows.
     */
    @Synchronized
    fun quarantine(): Path? =
        try {
            if (!Files.exists(directory)) {
                null
            } else {
                val base = directory.resolveSibling("${directory.fileName}-quarantine-${clock()}")
                var candidate = base
                var counter = 1
                while (Files.exists(candidate)) {
                    candidate = directory.resolveSibling("${base.fileName}-$counter")
                    counter++
                }
                try {
                    Files.move(directory, candidate, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(directory, candidate)
                }
                candidate
            }
        } catch (_: Exception) {
            null
        }

    /** Append one sanitized event durably. Returns the created record. */
    @Synchronized
    fun append(event: CanonicalEvent): SpoolRecord {
        val record = SpoolRecord.of(event, clock())
        appendLine(spoolFile, record.toCanonicalJson())
        enforceQuota()
        return record
    }

    /** The first [limit] un-acknowledged records, once each, in append order. */
    @Synchronized
    fun pending(limit: Int = Int.MAX_VALUE): List<SpoolRecord> {
        if (limit <= 0) return emptyList()
        val records = readRecords()
        val acked = readAckedIds()
        val seen = LinkedHashSet<String>()
        val result = ArrayList<SpoolRecord>()
        for (record in records) {
            if (record.eventId in acked) continue
            if (!seen.add(record.eventId)) continue
            result.add(record)
            if (result.size >= limit) break
        }
        return result
    }

    /**
     * Mark [eventIds] as accepted by the server. Returns the number of newly
     * acknowledged ids. Acknowledgement must only follow a durable server ACK.
     */
    @Synchronized
    fun acknowledge(eventIds: Collection<String>): Int {
        val existing = readAckedIds()
        val added = eventIds.filter { it.isNotBlank() && it !in existing }
        if (added.isNotEmpty()) {
            val builder = StringBuilder()
            added.forEach { builder.append(it).append('\n') }
            appendText(ackFile, builder.toString())
            ackedSinceCompaction = true
        }
        return added.size
    }

    /**
     * Which of [eventIds] already exist in the spool (pending, acknowledged, or
     * permanently discarded). Used by the local IPC server to answer `duplicate`
     * without appending the same event twice.
     */
    @Synchronized
    fun existingEventIds(eventIds: Collection<String>): Set<String> {
        val wanted = eventIds.filter { it.isNotBlank() }.toHashSet()
        if (wanted.isEmpty()) return emptySet()
        val found = LinkedHashSet<String>()
        for (record in readRecords()) {
            if (record.eventId in wanted) found.add(record.eventId)
        }
        for (id in wanted) {
            if (id in readAckedIds()) found.add(id)
        }
        return found
    }

    /**
     * Remove permanently rejected [eventIds] so they are never retried. Unlike
     * [acknowledge], a rejection is not a durable server acceptance: the caller
     * has already emitted a local diagnostic and decided the event is not worth
     * retrying. Returns the number of newly removed ids.
     */
    @Synchronized
    fun discard(eventIds: Collection<String>): Int {
        val existing = readAckedIds()
        val added = eventIds.filter { it.isNotBlank() && it !in existing }
        if (added.isNotEmpty()) {
            val builder = StringBuilder()
            added.forEach { builder.append(it).append('\n') }
            appendText(ackFile, builder.toString())
            ackedSinceCompaction = true
        }
        return added.size
    }

    /** Current spool statistics, including pending count/age and quota state. */
    @Synchronized
    fun stats(): SpoolStats {
        val records = readRecords()
        val acked = readAckedIds()
        val pending = dedupePending(records, acked)
        val retainedBytes = if (Files.exists(spoolFile)) Files.size(spoolFile) else 0L
        val overQuota = retainedBytes > maxBytes || pending.size > maxRecords
        val now = clock()
        return SpoolStats(
            totalCount = records.size,
            pendingCount = pending.size,
            ackedCount = records.count { it.eventId in acked },
            oldestPendingAgeMs = pending.minOfOrNull { (now - it.createdAtEpochMs).coerceAtLeast(0L) },
            retainedBytes = retainedBytes,
            quotaExceeded = overQuota,
            indicators = if (overQuota) listOf(QUOTA_INDICATOR) else emptyList(),
        )
    }

    /** Remove acknowledged records (and only those), then report quota state. */
    @Synchronized
    fun compact(): SpoolStats {
        compactAcknowledged()
        enforceQuota(compactFirst = false)
        return stats()
    }

    private fun enforceQuota(compactFirst: Boolean = true) {
        if (compactFirst && ackedSinceCompaction && isOverQuota()) compactAcknowledged()
        // The indicator is a diagnostic artifact; it is never a reason to drop
        // un-acknowledged behavioral data.
        if (isOverQuota()) writeIndicator() else clearIndicator()
    }

    private fun indicatorFile(): Path = directory.resolve(QUOTA_INDICATOR)

    private fun writeIndicator() {
        val file = indicatorFile()
        if (Files.exists(file)) return
        val state = stats()
        val payload =
            canonicalJson(
                linkedMapOf(
                    "indicator" to QUOTA_INDICATOR,
                    "pending_count" to state.pendingCount,
                    "retained_bytes" to state.retainedBytes,
                ),
            )
        writeTextDurably(file, payload)
    }

    private fun clearIndicator() {
        Files.deleteIfExists(indicatorFile())
    }

    private fun isOverQuota(): Boolean {
        val retainedBytes = if (Files.exists(spoolFile)) Files.size(spoolFile) else 0L
        if (retainedBytes > maxBytes) return true
        return if (maxRecords == Int.MAX_VALUE) false else pendingRecords().size > maxRecords
    }

    private fun pendingRecords(): List<SpoolRecord> = dedupePending(readRecords(), readAckedIds())

    private fun dedupePending(
        records: List<SpoolRecord>,
        acked: Set<String>,
    ): List<SpoolRecord> {
        val seen = LinkedHashSet<String>()
        val result = ArrayList<SpoolRecord>()
        for (record in records) {
            if (record.eventId in acked) continue
            if (seen.add(record.eventId)) result.add(record)
        }
        return result
    }

    private fun compactAcknowledged() {
        ackedSinceCompaction = false
        val records = readRecords()
        if (records.isEmpty()) {
            truncateFile(ackFile)
            return
        }
        val acked = readAckedIds()
        val keep = dedupePending(records, acked)
        if (keep.size == records.size) {
            // Nothing to remove: no acknowledged record is still in the log. Any
            // ack cursor entries refer to already-discarded events and are stale.
            if (acked.isNotEmpty()) truncateFile(ackFile)
            return
        }
        val retained = keep.joinToString(separator = "") { it.toCanonicalJson() + "\n" }
        val temporary = directory.resolve("spool.log.tmp")
        writeTextDurably(temporary, retained)
        moveAtomically(temporary, spoolFile)
        // Every acknowledged record has just been removed, so the cursor can be
        // reset without re-delivering anything.
        truncateFile(ackFile)
    }

    private fun readAckedIds(): Set<String> {
        if (!Files.exists(ackFile)) return emptySet()
        return Files.readAllLines(ackFile, StandardCharsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * Read every valid record. Unparseable/blank lines are quarantined to
     * `quarantine-*.bin`, but parsing continues so valid records after a corrupt
     * line are still delivered. When anything was quarantined the log is
     * rewritten durably with only the valid lines (temp + atomic move) so the
     * corruption is not re-encountered.
     */
    private fun readRecords(): List<SpoolRecord> {
        if (!Files.exists(spoolFile)) return emptyList()
        val bytes = Files.readAllBytes(spoolFile)
        val records = ArrayList<SpoolRecord>()
        val valid = StringBuilder()
        val corrupt = ByteArrayOutputStream()
        var lineStart = 0
        var index = 0
        while (index < bytes.size) {
            if (bytes[index].toInt() == NEWLINE) {
                val line = String(bytes, lineStart, index - lineStart, StandardCharsets.UTF_8)
                val record = parseRecordOrNull(line)
                if (record == null) {
                    corrupt.write(bytes, lineStart, index - lineStart)
                    corrupt.write(NEWLINE)
                } else {
                    records.add(record)
                    valid.append(line).append('\n')
                }
                lineStart = index + 1
            }
            index++
        }
        var repairedTail = false
        if (lineStart < bytes.size) {
            // Final line was never terminated: a crash mid-append.
            val line = String(bytes, lineStart, bytes.size - lineStart, StandardCharsets.UTF_8)
            val record = parseRecordOrNull(line)
            if (record == null) {
                corrupt.write(bytes, lineStart, bytes.size - lineStart)
            } else {
                records.add(record)
                valid.append(line).append('\n')
                // Rewrite once with the terminator, or the next append glues
                // its line onto this one and both become unparseable.
                repairedTail = true
            }
        }
        if (corrupt.size() > 0) quarantineBytes(corrupt.toByteArray())
        if (corrupt.size() > 0 || repairedTail) rewriteSpool(valid.toString())
        return records
    }

    private fun parseRecordOrNull(line: String): SpoolRecord? {
        if (line.isBlank()) return null
        return try {
            val parsed = parseCanonicalJson(line)
            if (parsed !is Map<*, *>) return null
            @Suppress("UNCHECKED_CAST")
            SpoolRecord.fromCanonicalMap(parsed as Map<String, Any?>)
        } catch (_: Exception) {
            null
        }
    }

    private fun quarantineBytes(bad: ByteArray) {
        if (bad.isEmpty()) return
        val quarantine = uniqueQuarantinePath()
        Files.write(quarantine, bad, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        forceFile(quarantine)
    }

    private fun rewriteSpool(text: String) {
        val temporary = directory.resolve("spool.log.tmp")
        writeTextDurably(temporary, text)
        moveAtomically(temporary, spoolFile)
    }

    private fun uniqueQuarantinePath(): Path {
        val base = directory.resolve("quarantine-${clock()}")
        var candidate = base.resolveSibling("${base.fileName}.bin")
        var counter = 1
        while (Files.exists(candidate)) {
            candidate = base.resolveSibling("${base.fileName}-$counter.bin")
            counter++
        }
        return candidate
    }

    private fun appendLine(
        file: Path,
        line: String,
    ) {
        FileOutputStream(file.toFile(), true).use { output ->
            output.write(line.toByteArray(StandardCharsets.UTF_8))
            output.write(NEWLINE)
            output.flush()
            output.fd.sync()
        }
    }

    private fun appendText(
        file: Path,
        text: String,
    ) {
        FileOutputStream(file.toFile(), true).use { output ->
            output.write(text.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun writeTextDurably(
        file: Path,
        text: String,
    ) {
        FileOutputStream(file.toFile(), false).use { output ->
            output.write(text.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun forceFile(file: Path) {
        FileOutputStream(file.toFile(), true).use { output ->
            output.flush()
            output.fd.sync()
        }
    }

    private fun truncateFile(
        file: Path,
        size: Long = 0L,
    ) {
        if (!Files.exists(file)) {
            Files.createFile(file)
        }
        FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
            channel.truncate(size)
            channel.force(true)
        }
    }

    private fun moveAtomically(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val QUOTA_INDICATOR = "spool_quota_exceeded"
        const val DEFAULT_MAX_BYTES: Long = 128L * 1024 * 1024
        private const val NEWLINE: Int = '\n'.code
    }
}
