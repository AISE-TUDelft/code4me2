package me.code4me.completion

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Pins how a single API completion is turned into the platform variant that the
 * IDE renders as ghost text: exactly one element, carrying the completion text
 * and the tracking metadata.
 */
class PluginInlineCompletionVariantTest {
    @Test
    fun `emits exactly one element carrying the completion and its metadata`() =
        runBlocking {
            val metaQueryId = UUID.randomUUID()
            val variant =
                PluginInlineCompletionVariant(
                    completionText = "println(\"hi\")",
                    requestId = 7L,
                    completionModel = "starcoder2-3b",
                    metaQueryId = metaQueryId,
                )

            val elements = variant.elements.toList()

            assertEquals(1, elements.size)
            val element = elements.single()
            assertTrue(element is PluginInlineCompletionElement)
            element as PluginInlineCompletionElement
            assertEquals("println(\"hi\")", element.text)
            assertEquals(7L, element.completionId)
            assertEquals("starcoder2-3b", element.completionModel)
            assertEquals(metaQueryId, element.metaQueryId)
        }

    @Test
    fun `provides a data holder for the platform`() {
        val variant =
            PluginInlineCompletionVariant(
                completionText = "x",
                requestId = 1L,
                completionModel = "model",
                metaQueryId = null,
            )

        assertNotNull(variant.data)
    }
}
