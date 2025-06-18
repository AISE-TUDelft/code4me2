package me.code4me.completion

import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.openapi.diagnostic.Logger
import me.code4me.api.generated.model.ResponseCompletionResponseDataCompletionsInner
import me.code4me.services.config.getConfig
import java.util.UUID

/**
 * Inline completion suggestion that wraps AI-generated completions from the Code4Me API.
 *
 * Converts API response data into IntelliJ's completion variant format, handling lazy
 * initialization of variants, performance logging, and model name resolution from configuration.
 *
 * @param completionItem List of completion data from the API response
 * @param requestId Unique identifier for tracking this completion request
 * @param metaQueryId Optional query ID for linking to the original request
 */
class PluginInlineCompletionSuggestion(
    private val completionItem: List<ResponseCompletionResponseDataCompletionsInner>,
    private val requestId: Long,
    private val metaQueryId: UUID?,
) : InlineCompletionSuggestion {
    private var variants: List<InlineCompletionVariant>? = null
    private val logger = Logger.getInstance(PluginInlineCompletionSuggestion::class.java)

    override suspend fun getVariants(): List<InlineCompletionVariant> {
        if (variants == null) {
            completionItem.forEach {
                // Log the time taken for the completion to be generated
                logger.info("Completion time = ${it.generationTime} ms")
            }

            variants =
                completionItem.map {
                    PluginInlineCompletionVariant(
                        it.completion,
                        requestId,
                        getConfig().getModelsConfiguration()?.modelNameById(it.modelId) ?: "Unknown Model",
                        metaQueryId,
                    )
                }
        }
        return variants!!
    }
}
