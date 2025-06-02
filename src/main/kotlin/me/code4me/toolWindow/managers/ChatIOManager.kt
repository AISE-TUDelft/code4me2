package me.code4me.toolWindow.managers

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile

class ChatIOManager {
    /**
     * Returns a list of open editor files in the current project.
     */
    fun getOpenEditorFiles(): List<VirtualFile> {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return emptyList()
        return FileEditorManager.getInstance(project).openFiles.toList()
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
