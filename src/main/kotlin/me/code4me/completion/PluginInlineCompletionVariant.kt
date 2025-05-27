package me.code4me.completion

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.openapi.util.UserDataHolderBase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class PluginInlineCompletionVariant(
    private val completionText: String,
    private val requestId: Long,
    private val completionModel: String,
) : InlineCompletionVariant {
    override val data: UserDataHolderBase
        get() = UserDataHolderBase()
    override val elements: Flow<InlineCompletionElement>
        get() =
            flowOf(
                PluginInlineCompletionElement(
                    completionText,
                    requestId,
                    completionModel,
                ),
            )
}
