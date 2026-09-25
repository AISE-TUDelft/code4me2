package me.code4me

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guards the plugin descriptor wiring for the original Code4Me surfaces.
 *
 * The agentic work added a lot to `plugin.xml`; this test makes sure the
 * pre-existing completion and chat surfaces stay registered, so a future
 * descriptor edit cannot silently drop them.
 *
 * The descriptor is read from the project tree rather than the test classpath
 * because every IntelliJ distribution jar also ships a `META-INF/plugin.xml`,
 * which makes `/META-INF/plugin.xml` classloading ambiguous.
 */
class OriginalPluginSurfaceRegistrationTest {
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
    fun `inline completion provider is registered`() {
        assertTrue(
            Regex(
                "<inline\\.completion\\.provider[^>]*implementation=\"me\\.code4me\\.completion\\.PluginInlineCompletionProvider\"",
            ).containsMatchIn(pluginXml()),
            "the ghost-text inline completion provider must stay registered",
        )
    }

    @Test
    fun `completion contributor is registered`() {
        assertTrue(
            Regex(
                "<completion\\.contributor[^>]*implementationClass=\"me\\.code4me\\.completion\\.PluginCompletionContributor\"",
            ).containsMatchIn(pluginXml()),
            "the dropdown completion contributor must stay registered",
        )
    }

    @Test
    fun `chat tool window is registered`() {
        assertTrue(
            Regex(
                "<toolWindow[^>]*factoryClass=\"me\\.code4me\\.chatWindow\\.ChatWindowFactory\"",
            ).containsMatchIn(pluginXml()),
            "the Code4Me V2 chat tool window must stay registered",
        )
        assertTrue(
            Regex(
                "<projectService[^>]*serviceImplementation=\"me\\.code4me\\.chatWindow\\.components\\.persistence\\.ChatWindowStateService\"",
            ).containsMatchIn(pluginXml()),
            "the chat window state service must stay registered",
        )
    }

    @Test
    fun `manual inline completion trigger action is registered`() {
        assertTrue(
            Regex(
                "<action[^>]*id=\"me\\.code4me\\.actions\\.TriggerInlineCompletionAction\"[^>]*class=\"me\\.code4me\\.actions\\.TriggerInlineCompletionAction\"",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(pluginXml()),
            "the manual inline completion trigger action must stay registered",
        )
    }
}
