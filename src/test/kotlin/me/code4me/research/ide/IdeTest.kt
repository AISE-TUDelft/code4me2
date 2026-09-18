package me.code4me.research.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.util.UUID
import me.code4me.research.IdSequence
import me.code4me.research.fixedClock
import me.code4me.research.fixedWallClock
import me.code4me.research.spool.DurableSpool
import me.code4me.research.telemetry.CanonicalEvent
import me.code4me.research.telemetry.CanonicalEventBuilder
import me.code4me.research.telemetry.CanonicalEventTypes
import me.code4me.research.telemetry.CoverageState
import me.code4me.research.telemetry.EventSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// --------------------------------------------------------------------------
// IdeActivityCollectorTest.kt
// --------------------------------------------------------------------------

class IdeActivityCollectorTest {
    private class FakeIdeSource : IdeActivitySource {
        private var callback: ((IdeActivitySignal) -> Unit)? = null

        val isSubscribed: Boolean
            get() = callback != null

        override fun onActivity(callback: (IdeActivitySignal) -> Unit) {
            this.callback = callback
        }

        fun push(signal: IdeActivitySignal) {
            callback?.invoke(signal)
        }
    }

    private class RecordingSink : CanonicalEventSink {
        val events = mutableListOf<CanonicalEvent>()

        override fun emit(event: CanonicalEvent) {
            events.add(event)
        }
    }

    private fun scope(): IdeCollectionScope =
        IdeCollectionScope(
            researchSessionId = "session-1",
            studyId = "study-1",
            enrollmentId = "enrollment-1",
            manifestDigest = "manifest-digest",
        )

    private fun collector(
        source: FakeIdeSource,
        sink: RecordingSink,
    ): IdeActivityCollector =
        IdeActivityCollector(
            source = source,
            sink = sink,
            clock = fixedClock("clock-ide", 42L),
            wallClock = fixedWallClock(),
            eventIdFactory = IdSequence("ide")::next,
        )

    @Test
    fun `emits nothing before an active session`() {
        val source = FakeIdeSource()
        val sink = RecordingSink()
        val collector = collector(source, sink)
        collector.start()
        assertTrue(source.isSubscribed)

        source.push(IdeActivitySignal(kind = "opened", projectKey = "project-a"))

        assertTrue(sink.events.isEmpty())
        assertFalse(collector.isActive)
    }

    @Test
    fun `pre-activation signals are dropped, never buffered`() {
        val source = FakeIdeSource()
        val sink = RecordingSink()
        val collector = collector(source, sink)
        collector.start()

        repeat(3) { source.push(IdeActivitySignal(kind = "changed", projectKey = "project-a")) }

        collector.activate(scope())
        source.push(IdeActivitySignal(kind = "changed", projectKey = "project-a"))

        assertEquals(1, sink.events.size)
    }

    @Test
    fun `after activation emits metadata-only events with increasing sequence`() {
        val source = FakeIdeSource()
        val sink = RecordingSink()
        val collector = collector(source, sink)
        collector.start()
        collector.activate(scope())

        source.push(
            IdeActivitySignal(
                kind = "changed",
                projectKey = "project-a",
                metadata =
                    linkedMapOf(
                        "file_extension" to "kt",
                        "language" to "Kotlin",
                        "action_category" to "edit",
                        "count" to 2,
                    ),
            ),
        )
        source.push(IdeActivitySignal(kind = "saved", projectKey = "project-a"))
        source.push(IdeActivitySignal(kind = "opened", projectKey = "project-a"))

        assertEquals(3, sink.events.size)
        assertEquals(listOf(1L, 2L, 3L), sink.events.map { it.emitterSequence })
        assertEquals(CanonicalEventTypes.IDE_DOCUMENT_CHANGED, sink.events[0].eventType)
        assertEquals(CanonicalEventTypes.IDE_FILE_SAVED, sink.events[1].eventType)
        assertEquals(CanonicalEventTypes.IDE_FILE_OPENED, sink.events[2].eventType)
        assertEquals(setOf("file_extension", "language", "action_category", "count"), sink.events[0].payload.keys)
        assertEquals("kt", sink.events[0].payload["file_extension"])
        assertEquals("session-1", sink.events[0].researchSessionId)
        assertEquals("study-1", sink.events[0].studyId)
        assertEquals("enrollment-1", sink.events[0].enrollmentId)
        assertEquals(CoverageState.AVAILABLE, sink.events[0].coverage.state)
        assertTrue(sink.events.flatMap { it.payload.keys }.none { it.contains("content") || it.contains("text") })
    }

    @Test
    fun `source content keys are rejected and nothing is emitted`() {
        val source = FakeIdeSource()
        val sink = RecordingSink()
        val collector = collector(source, sink)
        collector.start()
        collector.activate(scope())

        val forbiddenKeys = listOf("content", "text", "source_code", "prompt", "diff", "stdout")
        forbiddenKeys.forEach { key ->
            assertThrows(IdePayloadNotAllowedException::class.java) {
                source.push(
                    IdeActivitySignal(
                        kind = "changed",
                        projectKey = "project-a",
                        metadata = mapOf(key to "secret editor content"),
                    ),
                )
            }
        }
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `two projects get distinct opaque contexts and no cross attribution`() {
        val source = FakeIdeSource()
        val sink = RecordingSink()
        val collector = collector(source, sink)
        collector.start()
        collector.activate(scope())

        source.push(IdeActivitySignal(kind = "opened", projectKey = "project-a"))
        source.push(IdeActivitySignal(kind = "opened", projectKey = "project-b"))
        source.push(IdeActivitySignal(kind = "saved", projectKey = "project-a"))

        val contextA = collector.contextIdFor("project-a")
        val contextB = collector.contextIdFor("project-b")
        assertNotEquals(contextA, contextB)
        assertFalse(contextA.contains("project-a"))
        assertFalse(contextB.contains("project-b"))
        assertNotEquals(sink.events[0].emitterId, sink.events[1].emitterId)
        assertEquals(sink.events[0].emitterId, sink.events[2].emitterId)
        // Each project emitter allocates its own sequence, starting at 1.
        assertEquals(1L, sink.events[0].emitterSequence)
        assertEquals(1L, sink.events[1].emitterSequence)
        assertEquals(2L, sink.events[2].emitterSequence)
    }

    @Test
    fun `deactivation stops emission without buffering`() {
        val source = FakeIdeSource()
        val sink = RecordingSink()
        val collector = collector(source, sink)
        collector.start()
        collector.activate(scope())

        source.push(IdeActivitySignal(kind = "opened", projectKey = "project-a"))
        collector.deactivate()
        source.push(IdeActivitySignal(kind = "opened", projectKey = "project-a"))

        assertEquals(1, sink.events.size)

        collector.activate(scope())
        source.push(IdeActivitySignal(kind = "opened", projectKey = "project-a"))
        assertEquals(2, sink.events.size)
    }

    @Test
    fun `unknown kinds are preserved as unknown_source_event for review`() {
        val source = FakeIdeSource()
        val sink = RecordingSink()
        val collector = collector(source, sink)
        collector.start()
        collector.activate(scope())

        source.push(IdeActivitySignal(kind = "renamed", projectKey = "project-a", metadata = mapOf("count" to 1)))

        assertEquals(1, sink.events.size)
        val event = sink.events.single()
        assertEquals(CanonicalEventTypes.UNKNOWN_SOURCE_EVENT, event.eventType)
        assertEquals("renamed", event.unknownEventType)
        assertEquals(CoverageState.NEEDS_REVIEW, event.coverage.state)
    }

    @Test
    fun `stop prevents further emission`() {
        val source = FakeIdeSource()
        val sink = RecordingSink()
        val collector = collector(source, sink)
        collector.start()
        collector.activate(scope())
        collector.stop()

        source.push(IdeActivitySignal(kind = "opened", projectKey = "project-a"))

        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `source-text canary never appears in a serialized IDE event`() {
        val source = FakeIdeSource()
        val sink = RecordingSink()
        val collector = collector(source, sink)
        collector.start()
        collector.activate(scope())

        // A synthetic canary shaped like captured editor source text.
        val canary = "CANARY_SOURCE_TEXT_2f8a1c_DO_NOT_LEAK val secret = \"hunter2\""

        // 1. A canary under a forbidden content key is rejected before any emission.
        assertThrows(IdePayloadNotAllowedException::class.java) {
            source.push(
                IdeActivitySignal(
                    kind = "changed",
                    projectKey = "project-a",
                    metadata = linkedMapOf("editor_text" to canary, "file_extension" to "kt"),
                ),
            )
        }

        // 2. A canary smuggled into an allowlisted slot is rejected by value bounds.
        assertThrows(IdePayloadNotAllowedException::class.java) {
            source.push(
                IdeActivitySignal(
                    kind = "changed",
                    projectKey = "project-a",
                    metadata = linkedMapOf("language" to canary),
                ),
            )
        }

        // 3. Legitimate metadata serializes with no canary substring anywhere.
        source.push(
            IdeActivitySignal(
                kind = "saved",
                projectKey = "project-a",
                metadata = linkedMapOf("file_extension" to "kt", "language" to "kotlin", "count" to 1),
            ),
        )
        source.push(
            IdeActivitySignal(
                kind = "opened",
                projectKey = "project-a",
                metadata = linkedMapOf("file_extension" to "java"),
            ),
        )

        assertTrue(sink.events.isNotEmpty())
        sink.events.forEach { event ->
            val json = event.toCanonicalJson()
            assertFalse(json.contains(canary), "IDE event leaked editor source text: $json")
            assertFalse(json.contains("CANARY"), "IDE event leaked a content canary: $json")
            assertFalse(json.contains("hunter2"), "IDE event leaked a secret-looking value: $json")
        }
    }
}

// --------------------------------------------------------------------------
// IdeActivityEventTest.kt
// --------------------------------------------------------------------------

class IdeActivityEventTest {
    private fun ideBuilder(): IdeActivityEventBuilder =
        IdeActivityEventBuilder(
            projectId = "project-local-opaque-1",
            emitterId = "ide-project-emitter",
            clock = fixedClock("clock-ide", 42L),
            wallClock = fixedWallClock(),
        )

    @Test
    fun `typed build accepts only allowlisted metadata keys`() {
        val event =
            ideBuilder().build(
                activityType = IdeActivityType.FILE_OPENED,
                metadata =
                    linkedMapOf(
                        IdePayloadKey.FILE_EXTENSION to "KT",
                        IdePayloadKey.LANGUAGE to "Kotlin",
                        IdePayloadKey.ACTION_CATEGORY to "open",
                        IdePayloadKey.COUNT to 3,
                    ),
            )

        assertEquals(setOf("file_extension", "language", "action_category", "count"), event.payload.keys)
        assertEquals("kt", event.payload["file_extension"])
        assertEquals("kotlin", event.payload["language"])
        assertEquals("OPEN", event.payload["action_category"])
        assertEquals(3L, event.payload["count"])
        assertEquals("project-local-opaque-1", event.projectId)
        assertEquals(1L, event.emitterSequence)
    }

    @Test
    fun `emitter sequence strictly increases`() {
        val builder = ideBuilder()
        val sequences =
            (1..3).map {
                builder.build(IdeActivityType.DOCUMENT_CHANGED).emitterSequence
            }
        assertEquals(listOf(1L, 2L, 3L), sequences)
    }

    @Test
    fun `raw keys on the allowlist are accepted`() {
        val event =
            ideBuilder().buildFromRawKeys(
                activityType = IdeActivityType.FILE_SAVED,
                raw =
                    linkedMapOf(
                        "file_extension" to "java",
                        "action_category" to "save",
                        "count" to 1L,
                    ),
            )

        assertEquals(linkedMapOf<String, Any?>("file_extension" to "java", "action_category" to "SAVE", "count" to 1L), event.payload)
    }

    @Test
    fun `source and content keys are rejected`() {
        val forbiddenKeys =
            listOf(
                "source_code",
                "source",
                "editor_text",
                "document_text",
                "prompt",
                "diff",
                "diff_text",
                "command_output",
                "stdout",
                "reasoning",
                "content",
                "raw_payload",
            )

        forbiddenKeys.forEach { key ->
            val builder = ideBuilder()
            assertThrows(IdePayloadNotAllowedException::class.java) {
                builder.buildFromRawKeys(
                    activityType = IdeActivityType.DOCUMENT_CHANGED,
                    raw = linkedMapOf(key to "secret editor content"),
                )
            }
        }
    }

    @Test
    fun `allowlisted slots still bound their values`() {
        val builder = ideBuilder()
        assertThrows(IdePayloadNotAllowedException::class.java) {
            builder.buildFromRawKeys(IdeActivityType.FILE_OPENED, linkedMapOf("file_extension" to "kt source code"))
        }
        assertThrows(IdePayloadNotAllowedException::class.java) {
            builder.buildFromRawKeys(IdeActivityType.FILE_OPENED, linkedMapOf("file_extension" to "abcdefghijklmnopq"))
        }
        assertThrows(IdePayloadNotAllowedException::class.java) {
            builder.buildFromRawKeys(IdeActivityType.FILE_OPENED, linkedMapOf("language" to "kotlin script"))
        }
        assertThrows(IdePayloadNotAllowedException::class.java) {
            builder.buildFromRawKeys(IdeActivityType.FILE_OPENED, linkedMapOf("action_category" to "EXFILTRATE"))
        }
        assertThrows(IdePayloadNotAllowedException::class.java) {
            builder.buildFromRawKeys(IdeActivityType.FILE_OPENED, linkedMapOf("count" to 1_000_001))
        }
        assertThrows(IdePayloadNotAllowedException::class.java) {
            builder.buildFromRawKeys(IdeActivityType.FILE_OPENED, linkedMapOf("count" to 1.5))
        }
    }

    @Test
    fun `observation maps to a canonical metadata-only event`() {
        val ide = ideBuilder()
        val observation =
            ide.build(
                activityType = IdeActivityType.FILE_SAVED,
                metadata = linkedMapOf(IdePayloadKey.FILE_EXTENSION to "kt", IdePayloadKey.COUNT to 2),
            )
        val canonicalBuilder =
            CanonicalEventBuilder(
                emitterId = "ide-project-emitter",
                source = EventSource.IDE,
                normalizerVersion = "ide-collector-v1",
                allocator = me.code4me.research.telemetry.SequenceAllocator(),
                clock = fixedClock("clock-ide", 42L),
                eventIdFactory = IdSequence("ide")::next,
                wallClock = fixedWallClock(),
            )

        val canonical = ide.toCanonicalEvent(observation, canonicalBuilder)

        assertEquals("ide.file.saved", canonical.eventType)
        assertEquals(EventSource.IDE, canonical.provenance.source)
        assertEquals(observation.payload, canonical.payload)
        assertEquals(observation.emitterSequence, canonical.emitterSequence)
        assertEquals(observation.monotonicNs, canonical.monotonicNs)
        assertTrue(canonical.payload.keys.none { it.contains("source") || it.contains("text") || it.contains("prompt") })
    }
}

// --------------------------------------------------------------------------
// IntellijIdeActivityIntegrationTest.kt
// --------------------------------------------------------------------------

/**
 * Light-platform integration test for Issue 10.
 *
 * It wires the **real** public listener source [IntellijIdeActivitySource] into
 * the pure [IdeActivityCollector] and a [DurableSpool], drives real editor
 * events, and asserts that the emitted spool records are metadata-only: the
 * synthetic source-text canary never appears, and only allowlisted payload keys
 * cross the boundary. This is the closest machine-checkable approximation of the
 * ZIP/real-IDE fixture without spawning a full IDE from a ZIP.
 */
class IntellijIdeActivityIntegrationTest : BasePlatformTestCase() {
    fun testRealPublicListenersFeedMetadataOnlySpoolRecords() {
        val spool = DurableSpool(Files.createTempDirectory("code4me-research-ide-integration"))
        val source = IntellijIdeActivitySource(project)
        val collector = IdeActivityCollector(source, CanonicalEventSink { spool.append(it) })
        val canary = "CANARY-IDE-INTEGRATION-${UUID.randomUUID()}"

        try {
            collector.start()
            collector.activate(
                IdeCollectionScope(
                    researchSessionId = "session-integration",
                    studyId = "study-1",
                    enrollmentId = "enr-1",
                    manifestDigest = "sha256:" + "a".repeat(64),
                ),
            )

            myFixture.configureByText("CanaryIntegration.txt", "val secret = \"$canary\"\n")
            myFixture.type("x")

            val records = spool.pending()
            assertTrue("expected at least one metadata record", records.isNotEmpty())
            records.forEach { record ->
                assertFalse("source text canary leaked into the spool", record.canonicalJson.contains(canary))
                assertTrue(
                    "unexpected payload key in ${record.event.payload.keys}",
                    record.event.payload.keys.all { it in ALLOWED_PAYLOAD_KEYS },
                )
            }
        } finally {
            collector.stop()
            source.dispose()
        }
    }

    private companion object {
        val ALLOWED_PAYLOAD_KEYS = setOf("file_extension", "language", "action_category", "count")
    }
}

// --------------------------------------------------------------------------
// IntellijIdeActivityMapperTest.kt
// --------------------------------------------------------------------------

/**
 * Pure coverage for the IntelliJ listener -> [IdeActivitySignal] mapping.
 *
 * The source listeners themselves need a running platform; everything that
 * decides *what metadata crosses the boundary* lives here and is exercised
 * without IntelliJ types.
 */
class IntellijIdeActivityMapperTest {
    @Test
    fun `file signal keeps only extension and language metadata`() {
        val signal = IntellijIdeActivityMapper.fileSignal("opened", "project-a", "kt", "Kotlin")

        assertEquals("opened", signal.kind)
        assertEquals("project-a", signal.projectKey)
        assertEquals(setOf("file_extension", "language"), signal.metadata.keys)
        assertEquals("kt", signal.metadata["file_extension"])
        assertEquals("kotlin", signal.metadata["language"])
    }

    @Test
    fun `unsafe extension and language values are omitted, never coerced`() {
        val canary = "CANARY_SOURCE_TEXT hello world"
        val signal = IntellijIdeActivityMapper.fileSignal("changed", "project-a", canary, canary)

        assertTrue(signal.metadata.isEmpty())
    }

    @Test
    fun `document signal carries a bounded count and no fragment`() {
        val signal = IntellijIdeActivityMapper.documentSignal("project-a", "java", "Java", Int.MAX_VALUE)

        assertEquals("changed", signal.kind)
        assertEquals(IntellijIdeActivityMapper.MAX_COUNT, signal.metadata["count"])
        assertEquals(setOf("file_extension", "language", "count"), signal.metadata.keys)
    }

    @Test
    fun `run signal maps the executor to a bounded action category`() {
        assertEquals(
            "DEBUG",
            IntellijIdeActivityMapper.runSignal("project-a", "Debug").metadata["action_category"],
        )
        assertEquals(
            "RUN",
            IntellijIdeActivityMapper.runSignal("project-a", "Run").metadata["action_category"],
        )
        assertEquals(
            "RUN",
            IntellijIdeActivityMapper.runSignal("project-a", null).metadata["action_category"],
        )
    }

    @Test
    fun `mapper output flows through the collector with no content keys`() {
        var callback: ((IdeActivitySignal) -> Unit)? = null
        val source =
            IdeActivitySource { activityCallback ->
                callback = activityCallback
            }
        val events = mutableListOf<CanonicalEvent>()
        val collector = IdeActivityCollector(source, CanonicalEventSink { events.add(it) })
        collector.start()
        collector.activate(IdeCollectionScope(researchSessionId = "session-1"))

        callback?.invoke(IntellijIdeActivityMapper.fileSignal("opened", "project-a", "kt", "Kotlin"))
        callback?.invoke(IntellijIdeActivityMapper.documentSignal("project-a", "kt", "Kotlin", 12))
        callback?.invoke(IntellijIdeActivityMapper.fileSignal("closed", "project-a", "kt", "Kotlin"))
        callback?.invoke(IntellijIdeActivityMapper.runSignal("project-a", "Debug"))
        callback?.invoke(IntellijIdeActivityMapper.lifecycleSignal("project.opened", "project-a"))

        assertEquals(5, events.size)
        assertEquals(CanonicalEventTypes.IDE_FILE_OPENED, events[0].eventType)
        assertEquals(
            setOf("file_extension", "language"),
            events[0].payload.keys,
        )
        assertEquals(CanonicalEventTypes.IDE_DOCUMENT_CHANGED, events[1].eventType)
        assertEquals(
            setOf("file_extension", "language", "count"),
            events[1].payload.keys,
        )
        assertEquals(IdeActivityType.FILE_CLOSED.canonicalType, events[2].eventType)
        assertEquals(IdeActivityType.RUN_EXECUTED.canonicalType, events[3].eventType)
        assertEquals(setOf("action_category"), events[3].payload.keys)
        assertEquals("DEBUG", events[3].payload["action_category"])
        // An unrecognized lifecycle kind is preserved for review, not silently mapped.
        assertEquals("project.opened", events[4].unknownEventType)
        assertEquals(CanonicalEventTypes.UNKNOWN_SOURCE_EVENT, events[4].eventType)
        events.forEach { event ->
            assertFalse(event.payload.keys.any { it.contains("content") || it.contains("text") })
        }
    }
}

// --------------------------------------------------------------------------
// IntellijIdeActivitySourceTest.kt
// --------------------------------------------------------------------------

/**
 * Light-platform smoke test for the public listener source.
 *
 * It verifies that the source can attach its public listeners to a real project,
 * reports the project lifecycle it observes, and disposes cleanly. The metadata
 * mapping itself is covered without the platform in
 * [IntellijIdeActivityMapperTest].
 */
class IntellijIdeActivitySourceTest : BasePlatformTestCase() {
    fun testSourceAttachesReportsLifecycleAndDisposesCleanly() {
        val source = IntellijIdeActivitySource(project)
        val received = mutableListOf<IdeActivitySignal>()
        source.onActivity { received.add(it) }

        source.dispose()

        assertEquals(
            listOf(IntellijIdeActivitySource.PROJECT_OPENED, IntellijIdeActivitySource.PROJECT_CLOSED),
            received.map { it.kind },
        )
    }
}
