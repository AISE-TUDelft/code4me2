package me.code4me.completion

import com.intellij.codeInsight.inline.completion.DefaultInlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import me.code4me.services.modules.manager.getModuleManager

class PluginInlineCompletionInsertHandler : InlineCompletionInsertHandler {
    override fun afterInsertion(
        environment: InlineCompletionInsertEnvironment,
        elements: List<InlineCompletionElement>,
    ) {
        // TODO : Implement the afterInsertion logic, for now only calling the super method
        DefaultInlineCompletionInsertHandler.INSTANCE.afterInsertion(environment, elements)

        val moduleManager = getModuleManager(environment.editor.project!!)
        val aggregatedCollectedData =
            moduleManager
                .afterInsertion(
                    environment,
                    elements,
                )
    }
}
