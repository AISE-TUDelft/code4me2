package me.code4me.chatWindow.components.managers

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import me.code4me.services.app.AppService
import me.code4me.services.modules.manager.getModuleManager
import me.code4me.utils.record.aggregateByType
import me.code4me.utils.record.toMap

class ChatIOManager {
    /**
     * Gets AI response based on the input and context
     * Uses the module manager to collect data from registered modules
     * and passes it to the AppService for AI response generation
     */
    fun getAIResponse(
        query: String,
        useWeb: Boolean,
        selectedFiles: List<String>,
        selectedModel: String?,
        project: Project,
    ): String {
        // Get the current editor
        val editor = FileEditorManager.getInstance(project).selectedTextEditor

        if (editor != null) {
            // Get the document from the editor
            val document = editor.document

            // Get the PsiFile from the editor's virtual file
            val psiFile = PsiManager.getInstance(project).findFile(editor.virtualFile)

            // Only proceed if we have a valid PsiFile
            if (psiFile != null) {
                // Create a mock InlineCompletionRequest
                val mockRequest = InlineCompletionRequest(
                    event = InlineCompletionEvent.DirectCall(
                        editor = editor,
                        caret = editor.caretModel.primaryCaret,
                        context = null
                    ),
                    file = psiFile,
                    editor = editor,
                    document = document,
                    startOffset = editor.caretModel.primaryCaret.offset,
                    endOffset = editor.caretModel.primaryCaret.offset,
                    lookupElement = null
                )

                // Get the module manager for the current project
                val moduleManager = getModuleManager(project)

                // Collect data from all registered modules
                val collectedData = moduleManager.collectData(mockRequest)

                // Process the collected data
                val aggregatedData = collectedData
                    .aggregateByType()
                    .mapValues { (_, values) -> values.toMap() }

                return "AI response for query: $query, useWeb: $useWeb, selectedFiles: ${selectedFiles.joinToString(", ")}, selectedModel: $selectedModel\n" +
                        "Collected Data: ${aggregatedData.entries.joinToString("\n") { "${it.key}: ${it.value}" }}"
            }
        }

        // Fallback response if no editor is available or no completions were generated
        return "AI response for query: $query, useWeb: $useWeb, selectedFiles: ${selectedFiles.joinToString(", ")}, selectedModel: $selectedModel"
    }
}
