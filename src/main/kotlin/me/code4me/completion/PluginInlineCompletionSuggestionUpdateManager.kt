package me.code4me.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestionUpdateManager
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant

class PluginInlineCompletionSuggestionUpdateManager(
    private val baseManager: InlineCompletionSuggestionUpdateManager,
) : InlineCompletionSuggestionUpdateManager {
    override fun update(
        event: InlineCompletionEvent,
        variant: InlineCompletionVariant.Snapshot,
    ): InlineCompletionSuggestionUpdateManager.UpdateResult {
        // for now just call the base manager
        // TODO: update this to actually handle the changes and return an updated version of the completions
        return baseManager.update(event, variant)
    }
}
