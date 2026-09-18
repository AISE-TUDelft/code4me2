package me.code4me.research.ide

import me.code4me.research.telemetry.CanonicalEvent
import me.code4me.research.telemetry.CanonicalEventBuilder
import me.code4me.research.telemetry.CanonicalEventTypes
import me.code4me.research.telemetry.CanonicalFidelity
import me.code4me.research.telemetry.Coverage
import me.code4me.research.telemetry.CoverageState
import me.code4me.research.telemetry.FieldClass
import me.code4me.research.telemetry.LocalClock
import me.code4me.research.telemetry.SequenceAllocator
import java.time.Instant

/** Public IDE activity categories that produce canonical observations. */
enum class IdeActivityType(val canonicalType: String) {
    FILE_OPENED(CanonicalEventTypes.IDE_FILE_OPENED),
    FILE_SAVED(CanonicalEventTypes.IDE_FILE_SAVED),
    DOCUMENT_CHANGED(CanonicalEventTypes.IDE_DOCUMENT_CHANGED),
    FILE_CLOSED(CanonicalEventTypes.IDE_FILE_CLOSED),
    RUN_EXECUTED(CanonicalEventTypes.IDE_RUN_EXECUTED),

    /** A source kind with no documented canonical mapping (preserved for review). */
    UNKNOWN(CanonicalEventTypes.UNKNOWN_SOURCE_EVENT),
}

/**
 * The only payload keys an IDE collector may emit (Issue 10).
 *
 * Keys are metadata-only: file extension, language, action category, and a
 * bounded count. The list is intentionally closed; anything else, including
 * editor text, source content, prompts, diffs, or command output, is rejected.
 */
enum class IdePayloadKey(val key: String, val fieldClass: FieldClass) {
    FILE_EXTENSION("file_extension", FieldClass.CODE_METADATA),
    LANGUAGE("language", FieldClass.CODE_METADATA),
    ACTION_CATEGORY("action_category", FieldClass.BEHAVIORAL),
    COUNT("count", FieldClass.SYSTEM),
    ;

    companion object {
        private val byKey: Map<String, IdePayloadKey> = entries.associateBy { it.key }

        val allowedKeys: Set<String> = byKey.keys

        fun fromKey(key: String): IdePayloadKey? = byKey[key]
    }
}

/** Raised when an IDE collector attempts to emit a non-allowlisted field. */
class IdePayloadNotAllowedException(
    val key: String,
    reason: String,
) : IllegalArgumentException("IDE payload key '$key' is not allowed: $reason")

/**
 * One metadata-only IDE observation (Issue 10, `IdeActivityEventV1`).
 *
 * The payload is guaranteed to use only [IdePayloadKey] entries with bounded
 * values. There is deliberately no raw text field: content cannot be carried.
 */
data class IdeActivityEvent(
    val projectId: String,
    val emitterId: String,
    val emitterSequence: Long,
    val occurredAt: String,
    val monotonicNs: Long,
    val activityType: IdeActivityType,
    val payload: Map<String, Any?>,
) {
    init {
        require(projectId.isNotBlank()) { "projectId must not be blank" }
        require(emitterId.isNotBlank()) { "emitterId must not be blank" }
        require(emitterSequence >= 1) { "emitterSequence must start at 1" }
        val forbidden = payload.keys - IdePayloadKey.allowedKeys
        require(forbidden.isEmpty()) { "IDE payload contains non-allowlisted keys: $forbidden" }
    }
}

/**
 * Builds metadata-only [IdeActivityEvent]s for one project emitter.
 *
 * Callers can use the typed [build] API (compile-time allowlist) or
 * [buildFromRawKeys] for untrusted string keys, which rejects anything that is
 * not on the [IdePayloadKey] allowlist. Values are bounded so that even an
 * allowlisted slot cannot smuggle arbitrary text.
 */
class IdeActivityEventBuilder(
    val projectId: String,
    val emitterId: String,
    val allocator: SequenceAllocator = SequenceAllocator(),
    val clock: LocalClock = LocalClock("emitter:$emitterId"),
    private val wallClock: () -> Instant = { Instant.now() },
) {
    init {
        require(projectId.isNotBlank()) { "projectId must not be blank" }
        require(emitterId.isNotBlank()) { "emitterId must not be blank" }
    }

    /** Build from typed, allowlisted keys only. */
    fun build(
        activityType: IdeActivityType,
        metadata: Map<IdePayloadKey, Any?> = emptyMap(),
    ): IdeActivityEvent {
        val payload = LinkedHashMap<String, Any?>()
        for ((key, value) in metadata) {
            if (value == null) continue
            payload[key.key] = sanitize(key, value)
        }
        return assemble(activityType, payload)
    }

    /**
     * Build from untrusted string keys, rejecting any key not on the allowlist.
     *
     * @throws IdePayloadNotAllowedException when a key or value is not permitted.
     */
    fun buildFromRawKeys(
        activityType: IdeActivityType,
        raw: Map<String, Any?>,
    ): IdeActivityEvent {
        val payload = LinkedHashMap<String, Any?>()
        for ((key, value) in raw) {
            val allowed =
                IdePayloadKey.fromKey(key)
                    ?: throw IdePayloadNotAllowedException(key, "not an allowlisted IDE metadata key")
            if (value == null) continue
            payload[key] = sanitize(allowed, value)
        }
        return assemble(activityType, payload)
    }

    /** Convert an observation into its canonical envelope without re-allocating ids. */
    fun toCanonicalEvent(
        event: IdeActivityEvent,
        canonical: CanonicalEventBuilder,
        studyId: String? = null,
        revisionId: String? = null,
        enrollmentId: String? = null,
        researchSessionId: String? = null,
        agentRunId: String? = null,
        coverage: Coverage = Coverage(state = CoverageState.AVAILABLE),
        unknownEventType: String? = null,
        unknownSource: String? = null,
        unknownLifecycleState: String? = null,
    ): CanonicalEvent {
        require(canonical.emitterId == event.emitterId) {
            "Canonical builder emitter '${canonical.emitterId}' does not match IDE emitter '${event.emitterId}'"
        }
        return canonical.build(
            eventType = event.activityType.canonicalType,
            payload = event.payload,
            coverage = coverage,
            fidelity = CanonicalFidelity.EXACT,
            studyId = studyId,
            revisionId = revisionId,
            enrollmentId = enrollmentId,
            researchSessionId = researchSessionId,
            agentRunId = agentRunId,
            unknownEventType = unknownEventType,
            unknownSource = unknownSource,
            unknownLifecycleState = unknownLifecycleState,
            occurredAt = event.occurredAt,
            monotonicNs = event.monotonicNs,
            emitterSequence = event.emitterSequence,
        )
    }

    private fun assemble(
        activityType: IdeActivityType,
        payload: Map<String, Any?>,
    ): IdeActivityEvent {
        val timestamp = clock.now()
        return IdeActivityEvent(
            projectId = projectId,
            emitterId = emitterId,
            emitterSequence = allocator.next(emitterId),
            occurredAt = wallClock().toString(),
            monotonicNs = timestamp.valueNs,
            activityType = activityType,
            payload = payload,
        )
    }

    private fun sanitize(
        key: IdePayloadKey,
        value: Any,
    ): Any =
        when (key) {
            IdePayloadKey.FILE_EXTENSION -> sanitizeExtension(key, value)
            IdePayloadKey.LANGUAGE -> sanitizeLanguage(key, value)
            IdePayloadKey.ACTION_CATEGORY -> sanitizeActionCategory(key, value)
            IdePayloadKey.COUNT -> sanitizeCount(key, value)
        }

    private fun sanitizeExtension(
        key: IdePayloadKey,
        value: Any,
    ): String {
        val text =
            value as? String
                ?: throw IdePayloadNotAllowedException(key.key, "expected a file-extension string")
        val extension = text.removePrefix(".").lowercase()
        if (!EXTENSION_PATTERN.matches(extension)) {
            throw IdePayloadNotAllowedException(key.key, "extension must be alphanumeric (max $MAX_EXTENSION_LENGTH chars)")
        }
        return extension
    }

    private fun sanitizeLanguage(
        key: IdePayloadKey,
        value: Any,
    ): String {
        val text =
            value as? String
                ?: throw IdePayloadNotAllowedException(key.key, "expected a language identifier string")
        val language = text.lowercase()
        if (!LANGUAGE_PATTERN.matches(language)) {
            throw IdePayloadNotAllowedException(key.key, "language must be a short identifier")
        }
        return language
    }

    private fun sanitizeActionCategory(
        key: IdePayloadKey,
        value: Any,
    ): String {
        val text =
            value as? String
                ?: throw IdePayloadNotAllowedException(key.key, "expected an action-category string")
        val category = text.uppercase()
        if (category !in ACTION_CATEGORIES) {
            throw IdePayloadNotAllowedException(key.key, "unknown action category '$text'")
        }
        return category
    }

    private fun sanitizeCount(
        key: IdePayloadKey,
        value: Any,
    ): Long {
        val number =
            value as? Number
                ?: throw IdePayloadNotAllowedException(key.key, "count must be a whole number")
        val asLong = number.toLong()
        if (asLong.toDouble() != number.toDouble() || asLong < 0 || asLong > MAX_COUNT) {
            throw IdePayloadNotAllowedException(key.key, "count must be a whole number in 0..$MAX_COUNT")
        }
        return asLong
    }

    private companion object {
        const val MAX_EXTENSION_LENGTH = 16
        const val MAX_COUNT = 1_000_000L
        val EXTENSION_PATTERN = Regex("^[a-z0-9]{1,$MAX_EXTENSION_LENGTH}$")
        val LANGUAGE_PATTERN = Regex("^[a-z0-9+#_-]{1,32}$")
        val ACTION_CATEGORIES =
            setOf("OPEN", "SAVE", "CLOSE", "EDIT", "NAVIGATE", "RUN", "DEBUG", "TEST", "BUILD", "REFACTOR", "OTHER")
    }
}
