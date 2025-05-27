package me.code4me.completion

import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import me.code4me.api.generated.model.CompletionItem

class PluginInlineCompletionSuggestion(
    private val completionItem: List<CompletionItem>,
    private val requestId: Long,
) : InlineCompletionSuggestion {
    private var variants: List<InlineCompletionVariant>? = null

    override suspend fun getVariants(): List<InlineCompletionVariant> {
        if (variants == null) {
            variants =
                completionItem.map {
                    PluginInlineCompletionVariant(
                        it.completion,
                        requestId,
                        "DeepSeekCoder"
                    )
                }
        }
        return variants!!
    }
}
