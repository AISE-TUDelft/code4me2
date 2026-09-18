package me.code4me.research.telemetry

import me.code4me.research.IdSequence
import me.code4me.research.fixedClock
import me.code4me.research.telemetry.CoverageState
import me.code4me.research.telemetry.FieldClass
import me.code4me.research.telemetry.PolicyAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// --------------------------------------------------------------------------
// CanonicalEventBuilderTest.kt
// --------------------------------------------------------------------------

class CanonicalEventBuilderTest {
    @Test
    fun `every build allocates a unique event id`() {
        val builder =
            CanonicalEventBuilder(
                emitterId = "ide-1",
                source = EventSource.IDE,
                normalizerVersion = "generic-v1",
                allocator = SequenceAllocator(),
                clock = fixedClock("clock-1", 1L),
                eventIdFactory = IdSequence("evt")::next,
            )

        val first = builder.build(eventType = CanonicalEventTypes.IDE_FILE_OPENED)
        val second = builder.build(eventType = CanonicalEventTypes.IDE_FILE_OPENED)

        assertNotEquals(first.eventId, second.eventId)
        assertEquals("1", first.schemaVersion)
        assertEquals(EventSource.IDE, first.source)
    }

    @Test
    fun `sequence strictly increases per emitter`() {
        val builder =
            CanonicalEventBuilder(
                emitterId = "ide-1",
                source = EventSource.IDE,
                normalizerVersion = "generic-v1",
                clock = fixedClock("clock-1", 1L),
                eventIdFactory = IdSequence("evt")::next,
            )

        val sequences = (1..5).map { builder.build(eventType = CanonicalEventTypes.IDE_FILE_OPENED).emitterSequence }

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), sequences)
    }

    @Test
    fun `two emitters sharing an allocator never share a counter`() {
        val allocator = SequenceAllocator()
        val firstBuilder =
            CanonicalEventBuilder(
                emitterId = "emitter-a",
                source = EventSource.ACP,
                normalizerVersion = "generic-v1",
                allocator = allocator,
                clock = fixedClock("clock-a", 1L),
                eventIdFactory = IdSequence("a")::next,
            )
        val secondBuilder =
            CanonicalEventBuilder(
                emitterId = "emitter-b",
                source = EventSource.IDE,
                normalizerVersion = "generic-v1",
                allocator = allocator,
                clock = fixedClock("clock-b", 1L),
                eventIdFactory = IdSequence("b")::next,
            )

        val interleaved =
            listOf(
                firstBuilder.build(eventType = "tool.started").emitterSequence,
                secondBuilder.build(eventType = "ide.file.opened").emitterSequence,
                firstBuilder.build(eventType = "tool.completed").emitterSequence,
                secondBuilder.build(eventType = "ide.file.saved").emitterSequence,
            )

        assertEquals(listOf(1L, 1L, 2L, 2L), interleaved)
        assertEquals(2L, allocator.current("emitter-a"))
        assertEquals(2L, allocator.current("emitter-b"))
    }

    @Test
    fun `digest matches the canonical bytes`() {
        val builder =
            CanonicalEventBuilder(
                emitterId = "acp-proxy",
                source = EventSource.ACP,
                normalizerVersion = "generic-acp-v1",
                adapterVersion = "codex-v1",
                clock = fixedClock("clock-acp", 5L),
                eventIdFactory = IdSequence("acp")::next,
            )

        val event =
            builder.build(
                eventType = CanonicalEventTypes.TOOL_STARTED,
                payload = linkedMapOf("tool_name" to "read_file", "sequence" to 3L),
                fidelity = CanonicalFidelity.NORMALIZED,
            )

        assertEquals(sha256Hex(event.toCanonicalJson()), builder.digest(event))
        assertEquals(CanonicalFidelity.NORMALIZED, event.provenance.fidelity)
        assertEquals("codex-v1", event.provenance.adapterVersion)
    }

    @Test
    fun `local latency works only within one monotonic clock`() {
        val clockA = fixedClock("clock-a", 1_000L)
        val clockB = fixedClock("clock-b", 1_500L)
        val builder =
            CanonicalEventBuilder(
                emitterId = "acp-proxy",
                source = EventSource.ACP,
                normalizerVersion = "generic-v1",
                clock = clockA,
                eventIdFactory = IdSequence("acp")::next,
            )

        val start = MonotonicTimestamp(clockA.clockId, 1_000L)
        val end = MonotonicTimestamp(clockA.clockId, 4_500L)
        assertEquals(3_500L, builder.recordLocalLatencyNs(start, end))

        // Cross-process style subtraction is rejected, not silently allowed.
        val foreign = MonotonicTimestamp(clockB.clockId, 2_000L)
        assertThrows(IllegalArgumentException::class.java) { builder.recordLocalLatencyNs(start, foreign) }
        assertThrows(IllegalArgumentException::class.java) {
            builder.recordLocalLatencyNs(end, MonotonicTimestamp(clockA.clockId, 999L))
        }
    }

    @Test
    fun `event carries provenance and correlations`() {
        val builder =
            CanonicalEventBuilder(
                emitterId = "ide-1",
                source = EventSource.IDE,
                normalizerVersion = "ide-collector-v1",
                clock = fixedClock("clock-1", 1L),
                eventIdFactory = IdSequence("evt")::next,
            )

        val event =
            builder.build(
                eventType = CanonicalEventTypes.IDE_DOCUMENT_CHANGED,
                researchSessionId = "session-1",
                agentRunId = "run-1",
                correlations = Correlations(turnId = "turn-1", correlationId = "corr-1"),
                fidelity = CanonicalFidelity.EXACT,
                sourceEventId = "source-1",
                evidenceDigest = "evidence-1",
            )

        assertEquals(EventSource.IDE, event.provenance.source)
        assertEquals(EventSource.IDE, event.source)
        assertEquals("source-1", event.provenance.sourceEventId)
        assertEquals("evidence-1", event.provenance.evidenceDigest)
        assertEquals(CanonicalFidelity.EXACT, event.provenance.fidelity)
        assertEquals("turn-1", event.correlations.turnId)
        assertEquals("corr-1", event.correlations.correlationId)
    }

    @Test
    fun `monotonic ns is nullable and defaults to the emitter clock`() {
        val builder =
            CanonicalEventBuilder(
                emitterId = "ide-1",
                source = EventSource.IDE,
                normalizerVersion = "generic-v1",
                clock = fixedClock("clock-1", 1_234L),
                eventIdFactory = IdSequence("evt")::next,
            )

        assertEquals(1_234L, builder.build(eventType = CanonicalEventTypes.IDE_FILE_SAVED).monotonicNs)
        assertEquals(42L, builder.build(eventType = CanonicalEventTypes.IDE_FILE_SAVED, monotonicNs = 42L).monotonicNs)

        // The field is explicitly nullable: an emitted event may carry no reading.
        val withoutMonotonic = builder.build(eventType = CanonicalEventTypes.IDE_FILE_SAVED).copy(monotonicNs = null)
        assertNull(withoutMonotonic.monotonicNs)
        assertTrue(withoutMonotonic.toCanonicalJson().contains("\"monotonic_ns\":null"))
        assertNull(CanonicalEvent.fromCanonicalJson(withoutMonotonic.toCanonicalJson()).monotonicNs)
    }

    @Test
    fun `canonical json uses stable key ordering`() {
        val builder =
            CanonicalEventBuilder(
                emitterId = "ide-1",
                source = EventSource.IDE,
                normalizerVersion = "generic-v1",
                clock = fixedClock("clock-1", 1L),
                eventIdFactory = { "fixed-id" },
            )
        val event = builder.build(eventType = CanonicalEventTypes.IDE_FILE_SAVED, payload = mapOf("a" to 1, "b" to 2))

        assertTrue(event.toCanonicalJson().startsWith("""{"agent_run_id":null,"correlations":{"""))
    }
}

// --------------------------------------------------------------------------
// CanonicalEventSerializationTest.kt
// --------------------------------------------------------------------------

class CanonicalEventSerializationTest {
    private fun builder(): CanonicalEventBuilder =
        CanonicalEventBuilder(
            emitterId = "acp-proxy",
            source = EventSource.ACP,
            normalizerVersion = "generic-acp-v1",
            clock = fixedClock("clock-acp", 500L),
            eventIdFactory = IdSequence("evt")::next,
        )

    @Test
    fun `envelope and nested objects use the server field names`() {
        val event = builder().build(eventType = CanonicalEventTypes.AGENT_MESSAGE_STARTED)
        val parsed = parseCanonicalJson(event.toCanonicalJson()) as Map<*, *>

        assertEquals(
            setOf(
                "event_id",
                "schema_version",
                "event_type",
                "source",
                "study_id",
                "enrollment_id",
                "research_session_id",
                "agent_run_id",
                "occurred_at",
                "monotonic_ns",
                "emitter_id",
                "emitter_sequence",
                "correlations",
                "lifecycle_state",
                "payload",
                "metrics",
                "privacy",
                "provenance",
                "coverage",
                "unknown_event_type",
                "unknown_source",
                "unknown_lifecycle_state",
            ),
            parsed.keys,
        )

        val correlations = parsed["correlations"] as Map<*, *>
        assertEquals(
            setOf("turn_id", "tool_call_id", "permission_id", "edit_id", "correlation_id"),
            correlations.keys,
        )

        val metrics = parsed["metrics"] as Map<*, *>
        assertEquals(setOf("usage_tokens", "usage_capability", "latency_ms", "counts"), metrics.keys)
        val usageCapability = metrics["usage_capability"] as Map<*, *>
        assertEquals(setOf("state", "reason", "capability"), usageCapability.keys)

        val privacy = parsed["privacy"] as Map<*, *>
        assertEquals(
            setOf("policy_digest", "actions", "redacted_fields", "blocked", "block_reason", "field_classes"),
            privacy.keys,
        )

        val provenance = parsed["provenance"] as Map<*, *>
        assertEquals(
            setOf("source", "source_event_id", "normalizer_version", "adapter_version", "fidelity", "evidence_digest"),
            provenance.keys,
        )

        val coverage = parsed["coverage"] as Map<*, *>
        assertEquals(setOf("state", "reason", "capability"), coverage.keys)
    }

    @Test
    fun `envelope emits no revision keys`() {
        val event = builder().build(eventType = CanonicalEventTypes.AGENT_MESSAGE_STARTED)
        val parsed = parseCanonicalJson(event.toCanonicalJson()) as Map<*, *>

        assertFalse(parsed.containsKey("revision_id"), parsed.keys.toString())
        assertFalse(parsed.containsKey("study_revision_id"), parsed.keys.toString())
    }

    @Test
    fun `canonical event type vocabulary mirrors the server enum`() {
        assertEquals(
            setOf(
                "interaction.started",
                "interaction.completed",
                "agent.message.started",
                "agent.message.completed",
                "tool.created",
                "tool.started",
                "tool.completed",
                "tool.failed",
                "permission.requested",
                "permission.decided",
                "plan.updated",
                "usage.updated",
                "ide.document.changed",
                "ide.file.opened",
                "ide.file.saved",
                "ide.file.closed",
                "ide.run.executed",
                "system.agent.crashed",
                "system.proxy.error",
                "agent.error",
                "unknown_source_event",
            ),
            CanonicalEventTypes.all,
        )
    }

    @Test
    fun `missing usage tokens stay null with unavailable coverage`() {
        val unavailable =
            Coverage(
                state = CoverageState.UNAVAILABLE,
                reason = "agent does not expose token usage",
                capability = "usage_tokens",
            )
        val event =
            builder().build(
                eventType = CanonicalEventTypes.AGENT_MESSAGE_STARTED,
                metrics = EventMetrics(usageTokens = null, usageCapability = unavailable),
                coverage = unavailable,
            )

        val json = event.toCanonicalJson()
        assertTrue(json.contains("\"usage_tokens\":null"), json)
        assertTrue(json.contains("\"state\":\"UNAVAILABLE\""), json)
        assertFalse(json.contains("\"usage_tokens\":0"), json)

        val rehydrated = CanonicalEvent.fromCanonicalJson(json)
        assertNull(rehydrated.metrics.usageTokens)
        assertEquals(CoverageState.UNAVAILABLE, rehydrated.metrics.usageCapability.state)
        assertEquals("agent does not expose token usage", rehydrated.metrics.usageCapability.reason)
        assertEquals("usage_tokens", rehydrated.metrics.usageCapability.capability)
        assertEquals(CoverageState.UNAVAILABLE, rehydrated.coverage.state)
    }

    @Test
    fun `observed zero usage is preserved as zero with available coverage`() {
        val event =
            builder().build(
                eventType = CanonicalEventTypes.AGENT_MESSAGE_STARTED,
                metrics =
                    EventMetrics(
                        usageTokens = 0L,
                        usageCapability = Coverage(CoverageState.AVAILABLE),
                    ),
            )

        val json = event.toCanonicalJson()
        assertTrue(json.contains("\"usage_tokens\":0"), json)

        val rehydrated = CanonicalEvent.fromCanonicalJson(json)
        assertEquals(0L, rehydrated.metrics.usageTokens)
        assertEquals(CoverageState.AVAILABLE, rehydrated.metrics.usageCapability.state)
    }

    @Test
    fun `coverage enum mirrors the server wire values exactly`() {
        assertEquals(
            setOf("AVAILABLE", "UNAVAILABLE", "UNKNOWN", "PARTIAL", "NEEDS_REVIEW"),
            CoverageState.entries.map { it.value }.toSet(),
        )
        assertEquals(CoverageState.AVAILABLE, CoverageState.fromWire("available"))
        assertEquals(CoverageState.NEEDS_REVIEW, CoverageState.fromWire("NEEDS_REVIEW"))
        assertNull(CoverageState.fromWire("PRESENT"))
        assertNull(CoverageState.fromWire("nonsense"))
    }

    @Test
    fun `fidelity wire values are lowercase`() {
        assertEquals(setOf("exact", "normalized", "inferred"), CanonicalFidelity.entries.map { it.value }.toSet())
        assertEquals(CanonicalFidelity.NORMALIZED, CanonicalFidelity.fromWire("normalized"))
        assertEquals(CanonicalFidelity.EXACT, CanonicalFidelity.fromWire("exact"))
        assertNull(CanonicalFidelity.fromWire("other"))
    }

    @Test
    fun `full envelope round trips including provenance and privacy`() {
        val event =
            builder().build(
                eventType = CanonicalEventTypes.TOOL_STARTED,
                payload = linkedMapOf("tool_name" to "read_file", "file_extension" to "kt"),
                metrics =
                    EventMetrics(
                        usageTokens = null,
                        usageCapability = Coverage(CoverageState.UNAVAILABLE),
                        latencyMs = 12L,
                        counts = linkedMapOf("tools" to 1L),
                    ),
                coverage = Coverage(CoverageState.PARTIAL, reason = "tool result truncated"),
                privacy =
                    PrivacySummary(
                        policyDigest = "policy-digest",
                        actions = linkedMapOf("HASH" to 1L, "DROP" to 2L),
                        redactedFields = listOf("payload.file_path"),
                        blocked = false,
                        fieldClasses = linkedMapOf("CODE_METADATA" to 1L, "SECRET" to 2L),
                    ),
                fidelity = CanonicalFidelity.NORMALIZED,
                studyId = "study-1",
                enrollmentId = "enrollment-1",
                researchSessionId = "session-1",
                agentRunId = "run-1",
                correlations =
                    Correlations(
                        turnId = "turn-1",
                        toolCallId = "tool-1",
                        permissionId = "permission-1",
                        editId = "edit-1",
                        correlationId = "corr-1",
                    ),
                lifecycleState = "started",
                sourceEventId = "source-1",
                evidenceDigest = "digest-1",
            )

        val rehydrated = CanonicalEvent.fromCanonicalJson(event.toCanonicalJson())
        assertEquals(event, rehydrated)
        assertEquals(event.toCanonicalJson(), rehydrated.toCanonicalJson())
        assertEquals(event.digest(), rehydrated.digest())
    }

    @Test
    fun `unknown inputs are preserved and never reclassified`() {
        val event =
            builder().build(
                eventType = CanonicalEventTypes.UNKNOWN_SOURCE_EVENT,
                unknownEventType = "vendor.magic",
                unknownSource = "vendor-x",
                unknownLifecycleState = "reticulating",
                coverage = Coverage(CoverageState.NEEDS_REVIEW, reason = "unrecognized input preserved for review"),
            )

        val rehydrated = CanonicalEvent.fromCanonicalJson(event.toCanonicalJson())
        assertEquals(CanonicalEventTypes.UNKNOWN_SOURCE_EVENT, rehydrated.eventType)
        assertEquals("vendor.magic", rehydrated.unknownEventType)
        assertEquals("vendor-x", rehydrated.unknownSource)
        assertEquals("reticulating", rehydrated.unknownLifecycleState)
        assertEquals(CoverageState.NEEDS_REVIEW, rehydrated.coverage.state)
    }
}

// --------------------------------------------------------------------------
// CanonicalJsonTest.kt
// --------------------------------------------------------------------------

class CanonicalJsonTest {
    @Test
    fun `map key order does not affect canonical output`() {
        val first = linkedMapOf<String, Any?>("b" to 1, "a" to 2, "c" to 3)
        val second = linkedMapOf<String, Any?>("c" to 3, "b" to 1, "a" to 2)

        assertEquals("""{"a":2,"b":1,"c":3}""", canonicalJson(first))
        assertEquals(canonicalJson(first), canonicalJson(second))
    }

    @Test
    fun `nested containers are sorted and compact`() {
        val value =
            linkedMapOf<String, Any?>(
                "z" to linkedMapOf<String, Any?>("b" to true, "a" to null),
                "list" to listOf(3, 1, 2),
            )

        assertEquals("""{"list":[3,1,2],"z":{"a":null,"b":true}}""", canonicalJson(value))
        assertTrue(canonicalJson(value).none { it == ' ' })
    }

    @Test
    fun `escaping matches python json semantics`() {
        val raw = "a\"b\\c\nd\te\r"
        val expected = "\"a\\\"b\\\\c\\nd\\te\\r\""
        assertEquals(expected, canonicalJson(raw))
    }

    @Test
    fun `control characters use lowercase unicode escapes`() {
        assertEquals("\"\\u0001\"", canonicalJson("\u0001"))
        assertEquals("\"\\b\\f\"", canonicalJson("\b\u000C"))
    }

    @Test
    fun `non ascii text is preserved as utf-8`() {
        assertEquals("\"café ☕\"", canonicalJson("café ☕"))
    }

    @Test
    fun `sha256 hex matches the known vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc"),
        )
        assertEquals(sha256Hex("abc"), sha256Hex("abc".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `digest is stable across map insertion order`() {
        val first = linkedMapOf<String, Any?>("b" to "two", "a" to linkedMapOf("y" to 1, "x" to 2))
        val second = linkedMapOf<String, Any?>("a" to linkedMapOf("x" to 2, "y" to 1), "b" to "two")

        val firstDigest = sha256Hex(canonicalJsonBytes(first))
        val secondDigest = sha256Hex(canonicalJsonBytes(second))
        assertEquals(firstDigest, secondDigest)
        assertNotEquals(firstDigest, sha256Hex(canonicalJsonBytes(linkedMapOf("a" to 1))))
    }

    @Test
    fun `numbers and booleans serialize deterministically`() {
        assertEquals(
            "{\"big\":9223372036854775807,\"flag\":false,\"small\":1}",
            canonicalJson(
                linkedMapOf<String, Any?>("small" to 1, "big" to Long.MAX_VALUE, "flag" to false),
            ),
        )
    }

    @Test
    fun `parse round trips nested canonical structures`() {
        val value =
            linkedMapOf<String, Any?>(
                "text" to "hi\nthere",
                "count" to 42L,
                "flag" to true,
                "list" to listOf("a", 1L, null),
                "nested" to linkedMapOf<String, Any?>("k" to "v"),
            )
        val parsed = parseCanonicalJson(canonicalJson(value))
        assertEquals(value, parsed)
    }
}

// --------------------------------------------------------------------------
// PrivacyFilterTest.kt
// --------------------------------------------------------------------------

class PrivacyFilterTest {
    private val defaultFilter = PrivacyFilter(PrivacyPolicy.default())

    @Test
    fun `nested secrets are removed entirely`() {
        val payload =
            linkedMapOf<String, Any?>(
                "tool" to
                    linkedMapOf<String, Any?>(
                        "name" to "read_file",
                        "api_key" to "super-secret-value",
                        "session_token" to "abc123",
                    ),
                "message" to "hello",
            )

        val result = defaultFilter.filter(payload)

        assertFalse(result.sanitizedPayload.containsKey("api_key"))
        assertTrue(result.sanitizedPayload.toString().contains("hello"))
        val tool = result.sanitizedPayload["tool"] as Map<*, *>
        assertEquals("read_file", tool["name"])
        assertFalse(tool.containsKey("api_key"))
        assertFalse(tool.containsKey("session_token"))
        assertFalse(result.sanitizedPayload.toString().contains("super-secret-value"))
        assertTrue((result.actionsCounts[PolicyAction.DROP] ?: 0) >= 2)
        assertEquals(CoverageState.UNKNOWN, result.coverage["tool.api_key"])
    }

    @Test
    fun `inline secret shapes are removed even under allowed keys`() {
        val payload =
            linkedMapOf<String, Any?>(
                "message" to "authorization: Bearer abcdef1234567890",
                "version" to "sk-abcdefghijklmnop",
                "note" to "token ghp_abcdefghijklmnop",
                "cloud" to "AKIAABCDEFGHIJKLMNOP",
            )

        val result = defaultFilter.filter(payload)

        assertEquals(emptyMap<String, Any?>(), result.sanitizedPayload)
        assertTrue((result.actionsCounts[PolicyAction.DROP] ?: 0) >= 4)
        assertTrue((result.fieldClassCounts[FieldClass.SECRET] ?: 0) >= 4)
    }

    @Test
    fun `secret classification is always dropped even when content is allowed`() {
        val permissivePolicy =
            PrivacyPolicy(
                allowedFieldClasses = setOf(FieldClass.SYSTEM, FieldClass.BEHAVIORAL, FieldClass.CODE_METADATA, FieldClass.CONTENT),
                contentAllowed = true,
                consentActive = true,
                codeMetadataMode = CodeMetadataMode.ALLOW,
            )
        val filter = PrivacyFilter(permissivePolicy)
        val payload =
            linkedMapOf<String, Any?>(
                "content" to "a legitimate message",
                "password" to "hunter2",
            )

        val result = filter.filter(payload)

        assertEquals("a legitimate message", result.sanitizedPayload["content"])
        assertFalse(result.sanitizedPayload.containsKey("password"))
        assertTrue((result.actionsCounts[PolicyAction.DROP] ?: 0) >= 1)
    }

    @Test
    fun `content is redacted unless allowed and consented`() {
        val contentPayload = linkedMapOf<String, Any?>("prompt" to "the original prompt text")

        val noConsentPolicy = PrivacyPolicy(contentAllowed = true, consentActive = false)
        val noConsent = PrivacyFilter(noConsentPolicy).filter(contentPayload)
        assertEquals(REDACTED_MARKER, noConsent.sanitizedPayload["prompt"])

        val notAllowed = defaultFilter.filter(contentPayload)
        assertEquals(REDACTED_MARKER, notAllowed.sanitizedPayload["prompt"])

        val allowed =
            PrivacyFilter(PrivacyPolicy(contentAllowed = true, consentActive = true)).filter(contentPayload)
        assertEquals("the original prompt text", allowed.sanitizedPayload["prompt"])
    }

    @Test
    fun `unknown classification fails closed`() {
        val payload = linkedMapOf<String, Any?>("flibbertigibbet" to "unrecognized value", "message" to "kept")

        val result = defaultFilter.filter(payload)

        assertFalse(result.sanitizedPayload.containsKey("flibbertigibbet"))
        assertEquals("kept", result.sanitizedPayload["message"])
        assertEquals(CoverageState.UNKNOWN, result.coverage["flibbertigibbet"])
        assertTrue((result.actionsCounts[PolicyAction.DROP] ?: 0) >= 1)
    }

    @Test
    fun `activity-shaped free text is not persisted by default`() {
        // The canonical server classifier has no "activity" behavioral token, so
        // a free-text value under that key must fail closed here too.
        val payload = linkedMapOf<String, Any?>("activity" to "user opened SecretFile.kt and typed a prompt")

        val result = defaultFilter.filter(payload)

        assertFalse(result.sanitizedPayload.containsKey("activity"))
        assertEquals(CoverageState.UNKNOWN, result.coverage["activity"])
        assertTrue((result.actionsCounts[PolicyAction.DROP] ?: 0) >= 1)
        assertFalse(result.sanitizedPayload.toString().contains("SecretFile"))
    }

    @Test
    fun `code metadata is hashed by default and allowed when configured`() {
        val payload = linkedMapOf<String, Any?>("file_path" to "/home/participant/secret-project/Main.kt")

        val hashed = defaultFilter.filter(payload)
        val hashedValue = hashed.sanitizedPayload["file_path"] as String
        assertTrue(hashedValue.startsWith("sha256:"))
        assertFalse(hashedValue.contains("participant"))

        val allowed =
            PrivacyFilter(PrivacyPolicy(codeMetadataMode = CodeMetadataMode.ALLOW)).filter(payload)
        assertEquals("/home/participant/secret-project/Main.kt", allowed.sanitizedPayload["file_path"])
    }

    @Test
    fun `blocked field class rejects the whole payload`() {
        val policy = PrivacyPolicy(blockedFieldClasses = setOf(FieldClass.SYSTEM))
        val result = PrivacyFilter(policy).filter(linkedMapOf("version" to "1", "message" to "x"))

        assertTrue(result.blocked)
        assertEquals(emptyMap<String, Any?>(), result.sanitizedPayload)
        assertTrue(result.blockReason!!.contains("blocked"))
    }

    @Test
    fun `input is never mutated`() {
        val nested = linkedMapOf<String, Any?>("name" to "read_file", "api_key" to "secret-value")
        val payload = linkedMapOf<String, Any?>("tool" to nested, "prompt" to "text")

        val result = defaultFilter.filter(payload)

        assertEquals(setOf("name", "api_key"), nested.keys)
        assertEquals("secret-value", nested["api_key"])
        assertEquals(setOf("tool", "prompt"), payload.keys)
        assertNotSame(nested, result.sanitizedPayload["tool"])
    }

    @Test
    fun `secret keys cover the denylist without flagging usage tokens`() {
        assertTrue(FieldClassifier.isSecretKey("password"))
        assertTrue(FieldClassifier.isSecretKey("session_token"))
        assertTrue(FieldClassifier.isSecretKey("Authorization"))
        assertTrue(FieldClassifier.isSecretKey("private_key"))
        assertFalse(FieldClassifier.isSecretKey("usage_tokens"))
        assertFalse(FieldClassifier.isSecretKey("token_count"))
    }

    @Test
    fun `policy digest is stable and content policy is rejected when secret is allowed`() {
        val policy = PrivacyPolicy.default()
        assertEquals(policy.computedDigest(), policy.withComputedDigest().policyDigest)

        // Guard rail: SECRET can never be part of the allowed set.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            PrivacyPolicy(allowedFieldClasses = setOf(FieldClass.SYSTEM, FieldClass.SECRET))
        }
    }
}
