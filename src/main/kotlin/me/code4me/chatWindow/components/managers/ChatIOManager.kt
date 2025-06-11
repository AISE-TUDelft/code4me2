package me.code4me.chatWindow.components.managers

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

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
//        file: PsiFile?,
//        editor: Editor,
//        document: Document,
        // here we have to create a dummy inlineCompletionRequest to be able to call collect data
//        val mockRequest = InlineCompletionRequest(
//            event = InlineCompletionEvent.DirectCall(
//                editor = editor,
//                caret = editor.caretModel.primaryCaret,
//                context = null
//            ),
//            file = editor.project., // include the open file
//            editor = editor, // include the editor for whch the completion was called
//            document = null, // include the document for which the completion was called
//            startOffset = editor.caretModel.primaryCaret.offset, // get the caret position in the current editor
//            endOffset = document.textLength - editor.caretModel.primaryCaret.offset, // get the caret position in the current editor
//            lookupElement = null, // include the lookup element if available
//
//        );

        return "AI response for query: $query, useWeb: $useWeb, selectedFiles: ${selectedFiles.joinToString(", ")}, selectedModel: $selectedModel"

    }
}
