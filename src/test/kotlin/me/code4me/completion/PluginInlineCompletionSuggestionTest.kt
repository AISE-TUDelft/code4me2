package me.code4me.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.code4me.api.generated.model.ResponseCompletionResponseDataCompletionsInner
import me.code4me.services.config.ConfigService
import me.code4me.services.config.models.ModelConfig
import me.code4me.services.config.models.ModelsConfiguration
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.UUID

/**
 * Pins the mapping from an API completion response to platform variants:
 * every returned completion becomes a variant, the model id from the response is
 * resolved through the configured model list, and the mapped variants are
 * computed lazily and cached for the lifetime of the suggestion.
 */
class PluginInlineCompletionSuggestionTest : BasePlatformTestCase() {
    private val modelConfig =
        ModelConfig(
            id = 17,
            name = "deepseek-coder-1.3b",
            isChatModel = false,
            isDefault = true,
        )
    private lateinit var originalConfigService: ConfigService

    override fun setUp() {
        super.setUp()
        val configService = mock<ConfigService>()
        whenever(configService.getModelsConfiguration()).thenReturn(
            ModelsConfiguration(
                availableModels = listOf(modelConfig),
                systemPrompt = "test prompt",
            ),
        )
        originalConfigService = replaceApplicationService(ConfigService::class.java, configService)
    }

    override fun tearDown() {
        try {
            if (::originalConfigService.isInitialized) {
                restoreApplicationService(ConfigService::class.java, originalConfigService)
            }
        } finally {
            super.tearDown()
        }
    }

    fun testMapsEveryCompletionToAVariantWithTheConfiguredModelName() {
        val suggestion =
            PluginInlineCompletionSuggestion(
                completionItem =
                    listOf(
                        completionItem(completion = "println(1)"),
                        completionItem(completion = "println(2)"),
                    ),
                requestId = 9L,
                metaQueryId = metaQueryId,
            )

        val variants = runBlocking { suggestion.getVariants() }

        assertEquals(2, variants.size)
        val elements = variants.flatMap { variant -> runBlocking { variant.elements.toList() } }
        val mapped = elements.filterIsInstance<PluginInlineCompletionElement>()
        assertEquals(2, mapped.size)
        assertEquals(listOf("println(1)", "println(2)"), mapped.map { it.text })
        assertTrue(mapped.all { it.completionModel == modelConfig.name })
        assertTrue(mapped.all { it.completionId == 9L })
        assertTrue(mapped.all { it.metaQueryId == metaQueryId })
    }

    fun testVariantsAreComputedOnceAndCached() {
        val suggestion =
            PluginInlineCompletionSuggestion(
                completionItem = listOf(completionItem(completion = "val x = 1")),
                requestId = 3L,
                metaQueryId = null,
            )

        val first = runBlocking { suggestion.getVariants() }
        val second = runBlocking { suggestion.getVariants() }

        assertSame(first, second)
    }

    fun testEmptyApiResponseProducesNoVariants() {
        val suggestion =
            PluginInlineCompletionSuggestion(
                completionItem = emptyList(),
                requestId = 1L,
                metaQueryId = null,
            )

        assertTrue(runBlocking { suggestion.getVariants() }.isEmpty())
    }

    private fun completionItem(completion: String) =
        ResponseCompletionResponseDataCompletionsInner(
            modelId = modelConfig.id,
            modelName = modelConfig.name,
            completion = completion,
            generationTime = 12,
            confidence = BigDecimal.ONE,
        )

    private companion object {
        val metaQueryId: UUID = UUID.randomUUID()
    }
}
