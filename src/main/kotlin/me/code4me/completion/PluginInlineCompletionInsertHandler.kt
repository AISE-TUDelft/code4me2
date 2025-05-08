package me.code4me.completion

import com.intellij.codeInsight.inline.completion.DefaultInlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement

class PluginInlineCompletionInsertHandler : InlineCompletionInsertHandler {
    override fun afterInsertion(
        environment: InlineCompletionInsertEnvironment,
        elements: List<InlineCompletionElement>
    ) {
        // TODO : Implement the afterInsertion logic, for now only calling the super method
        DefaultInlineCompletionInsertHandler.INSTANCE.afterInsertion(environment, elements)
    }
}