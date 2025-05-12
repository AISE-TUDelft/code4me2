package me.code4me.completion

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import java.time.OffsetDateTime

class PluginInlineCompletionElement(
    override val text: String,
    val completionId: Long,
    val completionModel: String,
    private var createdAt: Long = System.currentTimeMillis(),
    private var originalCompletion: String = text,
    private var presentedAt: MutableSet<OffsetDateTime> = mutableSetOf(),
    private var timesUpdated: Int = 0,
) : InlineCompletionElement {
    override fun toPresentable(): InlineCompletionElement.Presentable {
        presentedAt.add(OffsetDateTime.now())
        return InlineCompletionGrayTextElement.Presentable(this)
    }
}
