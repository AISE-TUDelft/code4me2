package me.code4me.completion

import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.openapi.diagnostic.Logger
import me.code4me.api.generated.model.CompletionItem

class PluginInlineCompletionSuggestion(
    private val completionItem: List<CompletionItem>,
    private val requestId: Long,
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
                        "DeepSeekCoder",
                    )
                }
        }
        return variants!!
    }
}
