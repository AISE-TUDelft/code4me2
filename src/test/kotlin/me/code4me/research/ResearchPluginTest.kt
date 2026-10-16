package me.code4me.research

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import me.code4me.research.telemetry.CanonicalEvent
import me.code4me.research.telemetry.CanonicalEventBuilder
import me.code4me.research.telemetry.EventSource
import me.code4me.research.telemetry.LocalClock
import me.code4me.research.telemetry.SequenceAllocator
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `research runtime settings is registered as a project service`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<projectService[^>]*serviceImplementation=\"me\\.code4me\\.research\\.session\\.ResearchRuntimeSettings\"",
            ).containsMatchIn(xml),
            "ResearchRuntimeSettings must be registered as a projectService",
        )
    }

    @Test
    fun `research enrollment settings is registered as a project service`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<projectService[^>]*serviceImplementation=\"me\\.code4me\\.research\\.actions\\.ResearchEnrollmentSettings\"",
            ).containsMatchIn(xml),
            "ResearchEnrollmentSettings must be registered as a projectService",
        )
    }

    @Test
    fun `research activation startup activity is registered`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<postStartupActivity[^>]*implementation=\"me\\.code4me\\.research\\.lifecycle\\.ResearchActivationStartupActivity\"",
            ).containsMatchIn(xml),
            "ResearchActivationStartupActivity must be registered as a postStartupActivity",
        )
    }

    @Test
    fun `research status bar widget factory is registered`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<statusBarWidgetFactory[^>]*implementation=\"me\\.code4me\\.research\\.status\\.ResearchStatusBarWidgetFactory\"",
            ).containsMatchIn(xml),
            "ResearchStatusBarWidgetFactory must be registered as a statusBarWidgetFactory",
        )
    }

    @Test
    fun `research editor notification provider is registered`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<editorNotificationProvider[^>]*implementation=\"me\\.code4me\\.research\\.status\\.ResearchEditorNotificationProvider\"",
            ).containsMatchIn(xml),
            "ResearchEditorNotificationProvider must be registered as an editorNotificationProvider",
        )
    }
}

// --------------------------------------------------------------------------
// RegisteredSurfaceInventoryTest.kt (ISSUE-007)
// --------------------------------------------------------------------------

/**
 * Pins the full registered-surface inventory (ISSUE-007): every plugin.xml
 * implementation registration counted by the tag-agnostic enumeration
 * (grep -cE 'implementation=|implementationClass=|serviceImplementation=|
 * factoryClass=|<action |instance=' == 17) resolves to an intended,
 * necessary surface. The research rows are pinned behaviorally by their
 * package suites; the chat, lifecycle, settings, action and completion rows
 * are pinned here by registration so a removed or renamed surface fails.
 */
class RegisteredSurfaceInventoryTest {
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

    private fun countRegistrations(xml: String): Int =
        Regex("implementation=|implementationClass=|serviceImplementation=|factoryClass=|<action |instance=").findAll(xml).count()

    @Test
    fun `implementation registration count matches the tag-agnostic enumeration`() {
        assertEquals(17, countRegistrations(pluginXml()))
    }

    @Test
    fun `chat tool window factory is registered`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<toolWindow[^>]*factoryClass=\"me\\.code4me\\.chatWindow\\.ChatWindowFactory\"",
            ).containsMatchIn(xml),
            "ChatWindowFactory must be registered as a toolWindow factoryClass",
        )
    }

    @Test
    fun `chat window state service is registered as a project service`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<projectService[^>]*serviceImplementation=\"me\\.code4me\\.chatWindow\\.components\\.persistence\\.ChatWindowStateService\"",
            ).containsMatchIn(xml),
            "ChatWindowStateService must be registered as a projectService",
        )
    }

    @Test
    fun `plugin startup activity is registered`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<postStartupActivity[^>]*implementation=\"me\\.code4me\\.lifecycle\\.PluginStartupActivity\"",
            ).containsMatchIn(xml),
            "PluginStartupActivity must be registered as a postStartupActivity",
        )
    }

    @Test
    fun `inline completion provider is registered`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<inline\\.completion\\.provider[^>]*implementation=\"me\\.code4me\\.completion\\.PluginInlineCompletionProvider\"",
            ).containsMatchIn(xml),
            "PluginInlineCompletionProvider must be registered as an inline.completion.provider",
        )
    }

    @Test
    fun `completion contributor is registered`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<completion\\.contributor[^>]*implementationClass=\"me\\.code4me\\.completion\\.PluginCompletionContributor\"",
            ).containsMatchIn(xml),
            "PluginCompletionContributor must be registered as a completion.contributor",
        )
    }

    @Test
    fun `trigger inline completion action is registered`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<action[^>]*id=\"me\\.code4me\\.actions\\.TriggerInlineCompletionAction\"",
            ).containsMatchIn(xml),
            "TriggerInlineCompletionAction must be registered as an action",
        )
    }

    @Test
    fun `prepare acp agent session action is registered`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<action[^>]*id=\"me\\.code4me\\.actions\\.PrepareAcpAgentSessionAction\"",
            ).containsMatchIn(xml),
            "PrepareAcpAgentSessionAction must be registered as an action",
        )
    }

    @Test
    fun `settings configurables are registered`() {
        val xml = pluginXml()
        assertTrue(
            Regex(
                "<applicationConfigurable[^>]*instance=\"me\\.code4me\\.settings\\.Code4MeConfigurable\"",
            ).containsMatchIn(xml),
            "Code4MeConfigurable must be registered as an applicationConfigurable",
        )
        assertTrue(
            Regex(
                "<applicationConfigurable[^>]*instance=\"me\\.code4me\\.settings\\.ConfigurationConfigurable\"",
            ).containsMatchIn(xml),
            "ConfigurationConfigurable must be registered as an applicationConfigurable",
        )
        assertTrue(
            Regex(
                "<applicationConfigurable[^>]*instance=\"me\\.code4me\\.settings\\.UserConfigurable\"",
            ).containsMatchIn(xml),
            "UserConfigurable must be registered as an applicationConfigurable",
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
