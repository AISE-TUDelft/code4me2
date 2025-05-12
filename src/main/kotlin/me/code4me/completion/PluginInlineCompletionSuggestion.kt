package me.code4me.completion

import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant

class PluginInlineCompletionSuggestion(
    private val completionText: String,
    private val requestId: Long,
) : InlineCompletionSuggestion {
    private var variants: List<InlineCompletionVariant>? = null

    override suspend fun getVariants(): List<InlineCompletionVariant> {
        if (variants == null) {
            variants =
                listOf(
                    PluginInlineCompletionVariant(
                        "some random testing text",
                        requestId,
                        "some random model",
                    ),
                )
        }
        return variants!!
    }
}
