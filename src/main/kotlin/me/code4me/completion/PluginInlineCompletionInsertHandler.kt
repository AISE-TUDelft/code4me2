package me.code4me.completion

import com.intellij.codeInsight.inline.completion.DefaultInlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import me.code4me.services.modules.manager.getModuleManager
/**
 * Custom insertion handler for Code4Me inline completions that extends default behavior with telemetry.
 *
 * Handles the post-insertion process when users accept inline completions, delegating to
 * IntelliJ's default handler while also collecting analytics data through the module system
 * for tracking completion usage and effectiveness.
 */
class PluginInlineCompletionInsertHandler : InlineCompletionInsertHandler {
    override fun afterInsertion(
        environment: InlineCompletionInsertEnvironment,
        elements: List<InlineCompletionElement>,
    ) {
        // TODO : Implement the afterInsertion logic, for now only calling the super method
        DefaultInlineCompletionInsertHandler.INSTANCE.afterInsertion(environment, elements)

        val moduleManager = getModuleManager()
        val aggregatedCollectedData =
            moduleManager
                .afterInsertion(
                    environment,
                    elements,
                )
    }
}
