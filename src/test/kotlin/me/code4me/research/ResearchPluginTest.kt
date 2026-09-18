package me.code4me.research

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import me.code4me.research.telemetry.CanonicalEvent
import me.code4me.research.telemetry.CanonicalEventBuilder
import me.code4me.research.telemetry.EventSource
import me.code4me.research.telemetry.LocalClock
import me.code4me.research.telemetry.SequenceAllocator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// --------------------------------------------------------------------------
// ResearchPluginRegistrationTest.kt
// --------------------------------------------------------------------------

/**
 * Guards the plugin descriptor wiring for the research participant services.
 *
 * Service registration is a machine-checkable contract: the descriptor must
 * declare both research services as project services so the platform (and not a
 * convenience lookup) owns their lifecycle and disposes them on project close.
 *
 * The descriptor is read from the project tree rather than the test classpath
 * because every IntelliJ distribution jar also ships a `META-INF/plugin.xml`,
 * which makes `/META-INF/plugin.xml` classloading ambiguous.
 */
class ResearchPluginRegistrationTest {
    private fun pluginXml(): String {
        val candidates =
            listOf(
                Path.of("src/main/resources/META-INF/plugin.xml"),
                Path.of("build/resources/main/META-INF/plugin.xml"),
            )
        val path =
            candidates.firstOrNull { Files.exists(it) }
                ?: error("Could not locate plugin.xml from ${Path.of("").toAbsolutePath()}")
        return Files.readString(path)
    }

    @Test
    fun `ide activity source is registered as a project service`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<projectService[^>]*serviceImplementation=\"me\\.code4me\\.research\\.ide\\.IntellijIdeActivitySource\"",
            ).containsMatchIn(xml),
            "IntellijIdeActivitySource must be registered as a projectService",
        )
    }

    @Test
    fun `research session service is registered as a project service`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<projectService[^>]*serviceImplementation=\"me\\.code4me\\.research\\.session\\.ResearchSessionService\"",
            ).containsMatchIn(xml),
            "ResearchSessionService must be registered as a projectService",
        )
    }
}

// --------------------------------------------------------------------------
// ResearchTestFixtures.kt
// --------------------------------------------------------------------------

/** Deterministic, thread-unsafe id source for tests. */
internal class IdSequence(private val prefix: String = "event") {
    private var counter = 0L

    fun next(): String = "$prefix-${++counter}"
}

/** A monotonic clock returning a constant reading, keyed by [clockId]. */
internal fun fixedClock(
    clockId: String,
    valueNs: Long,
): LocalClock = LocalClock(clockId) { valueNs }

/** A wall clock pinned to [instant]. */
internal fun fixedWallClock(instant: String = "2026-01-01T00:00:00Z"): () -> Instant = { Instant.parse(instant) }

/** Build a deterministic canonical event builder for tests. */
internal fun builder(
    emitterId: String,
    source: EventSource = EventSource.IDE,
    normalizerVersion: String = "generic-v1",
    allocator: SequenceAllocator = SequenceAllocator(),
    clockId: String = "clock-$emitterId",
    monotonicValue: Long = 1_000L,
    eventIds: IdSequence = IdSequence("event"),
): CanonicalEventBuilder =
    CanonicalEventBuilder(
        emitterId = emitterId,
        source = source,
        normalizerVersion = normalizerVersion,
        allocator = allocator,
        clock = fixedClock(clockId, monotonicValue),
        eventIdFactory = { eventIds.next() },
        wallClock = fixedWallClock(),
    )

/** A minimal event for spool/batch/privacy tests. */
internal fun sampleEvent(
    builder: CanonicalEventBuilder,
    eventType: String = "tool.started",
    payload: Map<String, Any?> = emptyMap(),
): CanonicalEvent = builder.build(eventType = eventType, payload = payload)
