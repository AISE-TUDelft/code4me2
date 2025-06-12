package me.code4me.actions

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger

class TriggerInlineCompletionAction : AnAction() {
    private val logger = Logger.getInstance("TriggerInlineCompletionAction")

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.getData(CommonDataKeys.PROJECT) ?: return

        logger.info("Manually triggering inline completion")

        // Create an inline completion event and trigger completion
        val event =
            InlineCompletionEvent.DirectCall(
                editor,
                editor.caretModel.currentCaret,
                e.dataContext,
            )

        // Use InlineCompletion utility to get the handler for this editor and invoke the event
        val handler = InlineCompletion.getHandlerOrNull(editor)
        if (handler != null) {
            handler.invokeEvent(event)
        } else {
            logger.warn("No inline completion handler found for editor")
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabledAndVisible = editor != null && project != null
    }
}
