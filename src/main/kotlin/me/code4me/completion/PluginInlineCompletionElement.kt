package me.code4me.completion

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import java.time.OffsetDateTime
import java.util.UUID
/**
 * Inline completion element that represents a single AI-generated code suggestion with tracking metadata.
 *
 * Extends IntelliJ's InlineCompletionElement to provide Code4Me-specific completion data
 * including completion IDs, model information, presentation tracking, and telemetry data
 * for analytics and performance monitoring.
 *
 * @param text The completion text to be inserted
 * @param completionId Unique identifier for this completion
 * @param completionModel Name of the AI model that generated this completion
 * @param createdAt Timestamp when the completion was created (defaults to current time)
 * @param originalCompletion Original completion text before any modifications
 * @param presentedAt Set of timestamps when this completion was shown to the user
 * @param timesUpdated Counter for how many times this completion has been updated
 * @param metaQueryId Optional query ID for linking to the original request
 */
class PluginInlineCompletionElement(
    override val text: String,
    val completionId: Long,
    val completionModel: String,
    private var createdAt: Long = System.currentTimeMillis(),
    private var originalCompletion: String = text,
    private var presentedAt: MutableSet<OffsetDateTime> = mutableSetOf(),
    private var timesUpdated: Int = 0,
    internal val metaQueryId: UUID?,
) : InlineCompletionElement {
    override fun toPresentable(): InlineCompletionElement.Presentable {
        presentedAt.add(OffsetDateTime.now())
        return InlineCompletionGrayTextElement.Presentable(this)
    }
}
