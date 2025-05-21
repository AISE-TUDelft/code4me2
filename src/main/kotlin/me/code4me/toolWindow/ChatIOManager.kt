package me.code4me.toolWindow

class ChatIOManager {
    /**
     * Returns a list of open editor files in the current project.
     */
    fun getOpenEditorFiles(): List<String> {
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull() ?: return emptyList()
        val openFiles = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles
        return openFiles.map { it.name }
    }

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
