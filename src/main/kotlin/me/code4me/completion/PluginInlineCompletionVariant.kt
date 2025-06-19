package me.code4me.completion

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.openapi.util.UserDataHolderBase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/**
 * Inline completion variant that wraps a single AI-generated completion for IntelliJ display.
 *
 * Converts Code4Me completion data into IntelliJ's completion variant format, creating
 * the appropriate completion element with tracking metadata for telemetry and analytics.
 *
 * @param completionText The AI-generated completion text to display
 * @param requestId Unique identifier for tracking this completion request
 * @param completionModel Name of the AI model that generated this completion
 * @param metaQueryId Optional query ID for linking to the original request
 */
class PluginInlineCompletionVariant(
    private val completionText: String,
    private val requestId: Long,
    private val completionModel: String,
    private val metaQueryId: UUID?,
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
                    metaQueryId = metaQueryId,
                ),
            )
}
