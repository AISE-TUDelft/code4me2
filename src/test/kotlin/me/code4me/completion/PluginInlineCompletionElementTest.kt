package me.code4me.completion

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Pins the metadata contract of a single ghost-text element.
 *
 * The element carries the completion id, the model name and the meta-query id
 * that the telemetry modules read back after insertion; if any of those are
 * dropped or reordered the analytics lose the ability to attribute a completion
 * to the model/query that produced it.
 */
class PluginInlineCompletionElementTest {
    @Test
    fun `carries the completion metadata used for telemetry`() {
        val metaQueryId = UUID.randomUUID()
        val element =
            PluginInlineCompletionElement(
                text = "fun main() {}",
                completionId = 42L,
                completionModel = "deepseek-coder-1.3b",
                metaQueryId = metaQueryId,
            )

        assertEquals("fun main() {}", element.text)
        assertEquals(42L, element.completionId)
        assertEquals("deepseek-coder-1.3b", element.completionModel)
        assertEquals(metaQueryId, element.metaQueryId)
    }

    @Test
    fun `toPresentable renders the text as gray inline text`() {
        val element =
            PluginInlineCompletionElement(
                text = "val answer = 42",
                completionId = 1L,
                completionModel = "starcoder2-3b",
                metaQueryId = null,
            )

        assertTrue(element.toPresentable() is InlineCompletionGrayTextElement.Presentable)
    }

    @Test
    fun `toPresentable is repeatable`() {
        val element =
            PluginInlineCompletionElement(
                text = "println(\"hi\")",
                completionId = 2L,
                completionModel = "model",
                metaQueryId = null,
            )

        assertTrue(element.toPresentable() is InlineCompletionGrayTextElement.Presentable)
        assertTrue(element.toPresentable() is InlineCompletionGrayTextElement.Presentable)
    }
}
