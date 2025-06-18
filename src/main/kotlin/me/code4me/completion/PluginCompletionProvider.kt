package me.code4me.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.util.IconLoader
import com.intellij.util.ProcessingContext
import me.code4me.chatWindow.components.chatDisplayPanel.components.ChatBubble
import me.code4me.services.app.getAppService
import me.code4me.services.modules.manager.getModuleManager
import me.code4me.services.modules.model.CompletionModel
import me.code4me.services.state.getAuthState
import me.code4me.utils.completion.prioritize
import me.code4me.utils.record.aggregateByType
import me.code4me.utils.record.toMap
import me.code4me.utils.services.state.getBooleanPreference

class PluginCompletionProvider : CompletionProvider<CompletionParameters>() {
    companion object {
        private val CHAT_ICON = IconLoader.getIcon("/icons/pluginIcon_chatSize.svg", ChatBubble::class.java)
    }

    private val LOG = thisLogger()

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        results: CompletionResultSet,
    ) {
        // check if the user is authenticated and if inline completions are enabled
        val isAuthenticated = getAuthState().isAuthenticated()
        if (!isAuthenticated) {
            LOG.warn("User is not authenticated. Cannot provide inline completions.")
            return
        }
        val wantsInlineCompletions =
            getBooleanPreference(
                "CompletionModel",
                CompletionModel.Companion.COMPLETION_INLINE_KEY,
                false,
            )
        if (wantsInlineCompletions) {
            LOG.warn("Inline completions are disabled in preferences. Skipping completion provider.")
            return
        }

        try {
            runBlockingCancellable {
                // Get the project from parameters
                val project = parameters.originalFile.project

                // Collect editor data within a read action
                val editorData =
                    readAction {
                        // Get the editor from parameters
                        val editor = parameters.editor
                        val document = editor.document
                        val psiFile = parameters.originalFile

                        // Create a mock InlineCompletionRequest similar to ChatIOManager
                        val mockRequest =
                            InlineCompletionRequest(
                                event =
                                    InlineCompletionEvent.DirectCall(
                                        editor = editor,
                                        caret = editor.caretModel.primaryCaret,
                                        context = null,
                                    ),
                                file = psiFile,
                                editor = editor,
                                document = document,
                                startOffset = parameters.offset,
                                endOffset = parameters.offset,
                                lookupElement = null,
                            )

                        // Get the module manager for the current project
                        val moduleManager = getModuleManager()

                        // Collect data from all registered modules
                        moduleManager.collectData(mockRequest)
                    }

                // Process the collected data (this doesn't need read access)
                val aggregatedData =
                    editorData
                        .aggregateByType()
                        .mapValues { (_, values) -> values.toMap() }

                // TODO: update to pass \n as a stop sequence when the API supports it
                // Call AppService to get inline completion
                val completionResponse =
                    getAppService()
                        .getInlineCompletion(
                            aggregatedData,
                            project,
                            listOf("\n"),
                        ) // Stop sequences can be adjusted as needed)
                // inline completions for dropdown suggestions should be single line, so we can use "\n" as a stop sequence

                // Process the response and add completions to results
                if (completionResponse != null) {
                    completionResponse.completions.forEachIndexed { index, completion ->
                        val element =
                            LookupElementBuilder
                                .create("code4me_completion_$index")
                                .withPresentableText(completion.completion)
                                .withIcon(CHAT_ICON)
                                .withTypeText("Code4Me V2")
                                .withInsertHandler { context, item ->
                                    // Insert the completion text at the current position
                                    val document = context.document
                                    val startOffset = context.startOffset
                                    val endOffset = context.tailOffset

                                    document.replaceString(startOffset, endOffset, completion.completion)
                                    context.editor.caretModel.moveToOffset(startOffset + completion.completion.length)
                                }

                        results.addElement(element.prioritize())
                    }
                } else {
                    // Fallback completion if service call fails
                    val fallbackElement =
                        LookupElementBuilder
                            .create("Code4Me V2 Plugin Completion")
                            .withPresentableText("Code4Me V2 Error: No completions found")
                            .withTypeText("Code4Me V2")

                    results.addElement(fallbackElement.prioritize())
                }
            }
        } catch (e: Exception) {
            LOG.error("Error getting completions from AppService", e)

            // Add fallback completion on error
            val errorElement =
                LookupElementBuilder
                    .create("Code4Me V2 Plugin Completion (Error)")
                    .withPresentableText("Code4Me V2 Error: ${e.message ?: "Unknown error"}")
                    .withTypeText("Code4Me V2")

            results.addElement(errorElement.prioritize())
        }
    }
}
