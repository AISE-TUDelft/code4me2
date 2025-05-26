package me.code4me.services.modules.telemetry.helpers.typingSpeed

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.project.Project

/**
 * This class is responsible for handling the typing speed telemetry.
 * It replaces the default typed action handler with a custom one that records typing speed.
 *
 * @param originalHandler The original typed action handler to be replaced.
 * @param project The current project instance.
 */
class TypingSpeedHandler(
    private val originalHandler: TypedActionHandler?,
    private val project: Project,
) : TypedActionHandler {
    override fun execute(
        editor: Editor,
        charTyped: Char,
        dataContext: DataContext,
    ) {
        if (!charTyped.isISOControl()) {
            val typingSpeedService = project.service<TypingSpeedService>()
            typingSpeedService.recordCharTyped()
            println("[TypingSpeedHandler] Char typed: $charTyped")
        }

        originalHandler?.execute(editor, charTyped, dataContext)
    }
}
