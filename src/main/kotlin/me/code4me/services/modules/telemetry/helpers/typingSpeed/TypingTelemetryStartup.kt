package me.code4me.services.modules.telemetry.helpers.typingSpeed

import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class TypingTelemetryStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Replace default typed handler with ours
        val originalHandler = TypedAction.getInstance().rawHandler
        TypedAction.getInstance().setupRawHandler(TypingSpeedHandler(originalHandler, project))
        println("[TypingTelemetryStartup] TypingSpeedHandler registered.")
    }
}
