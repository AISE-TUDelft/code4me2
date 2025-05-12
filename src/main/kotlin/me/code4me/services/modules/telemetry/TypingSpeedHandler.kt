package me.code4me.services.modules.telemetry

import com.intellij.codeInsight.template.impl.editorActions.TypedActionHandlerBase

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service

class TypingSpeedHandler(
    private val originalHandler: TypedActionHandler?,
    private val project: Project
) : TypedActionHandlerBase(originalHandler) {

    override fun execute(editor: Editor, charTyped: Char, dataContext: DataContext) {
        if (!charTyped.isISOControl()) {
            val typingSpeedService = project.service<TypingSpeedService>()
            typingSpeedService.recordCharTyped()
        }

        originalHandler?.execute(editor, charTyped, dataContext)
    }
}
