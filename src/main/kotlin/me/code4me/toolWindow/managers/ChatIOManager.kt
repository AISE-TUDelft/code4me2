package me.code4me.toolWindow.managers

class ChatIOManager {
    /**
     * Gets AI response based on the input and context
     * TODO: Implement actual AI response logic
     */
    fun getAIResponse(
        query: String,
        useWeb: Boolean,
        selectedFiles: List<String>,
        selectedModel: String?,
    ): String {
        return "Query: $query | Web: $useWeb | Files: ${selectedFiles.joinToString()} | Model: $selectedModel"
    }
}
