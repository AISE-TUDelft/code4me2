package me.code4me.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestionUpdateManager
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant

/**
 * Custom suggestion update manager for Code4Me inline completions with future extensibility.
 *
 * Currently delegates to the base IntelliJ suggestion update manager while providing
 * a foundation for implementing custom update logic specific to Code4Me's completion
 * behavior and telemetry requirements.
 *
 * @param baseManager The default IntelliJ suggestion update manager to delegate to
 */
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
