package me.code4me.toolWindow.managers

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager

class ChatIOManager {
    /**
     * Returns a list of open editor files in the current project.
     */
    fun getOpenEditorFiles(): List<String> {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return emptyList()
        val openFiles = FileEditorManager.getInstance(project).openFiles
        return openFiles.map { it.name }
    }
    // TODO Add search in project files for user to choose from (like open editor files)

    /**
     * Gets AI response based on the input and context
     * TODO: Implement actual AI response logic
     */
    fun getAIResponse(
        query: String,
        useWeb: Boolean,
        selectedFiles: List<String>,
    ): String {
        return "Query: $query | Web: $useWeb | Files: ${selectedFiles.joinToString()}"
    }
}
