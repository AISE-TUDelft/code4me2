package me.code4me.chatWindow.components.managers

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import me.code4me.api.generated.model.BehavioralTelemetryData
import me.code4me.api.generated.model.ContextData
import me.code4me.api.generated.model.ContextualTelemetryData
import me.code4me.api.generated.model.RequestChatCompletion
import me.code4me.chatWindow.components.utils.ChatConverter
import me.code4me.chatWindow.components.utils.TitleResponsePair
import me.code4me.services.app.getAppService
import me.code4me.services.config.getConfig
import me.code4me.services.modules.manager.getModuleManager
import me.code4me.services.project.getProjectTokenService
import me.code4me.services.state.getAuthState
import me.code4me.utils.api.activateOrCreateProject
import me.code4me.utils.api.mapsTo
import me.code4me.utils.notification.showErrorNotification
import me.code4me.utils.record.Record
import me.code4me.utils.record.aggregateByType
import me.code4me.utils.record.toMap
import java.io.File

class ChatIOManager {
    val LOG = thisLogger()

    /**
     * Gets AI response based on the input and context
     * Uses the module manager to collect data from registered modules
     * and passes it to the AppService for AI response generation
     */
    suspend fun getAIResponse(
        useWeb: Boolean,
        selectedFiles: List<String>,
        selectedModel: String?,
        chatId: String? = null,
        previousMessages: List<Pair<String, String>> = emptyList(),
        project: Project,
    ): TitleResponsePair {
        // make sure that the project is activated for the system and also that the user is authenticated
        if (!getAuthState().isAuthenticated()) {
            LOG.error("User is not authenticated. Cannot proceed with AI response generation.")
            return TitleResponsePair(
                "Error: User not authenticated",
                emptyList(),
            )
        }

        val tokService = getProjectTokenService(project)

        if (tokService.getProjectToken() == null || (tokService.hasProjectToken() && !tokService.isActivated())) {
            activateOrCreateProject(
                project,
                LOG,
            )
        }

        // Collect editor data within a read action
        val editorData =
            readAction {
                // Get the current editor
                var editor = FileEditorManager.getInstance(project).selectedTextEditor

                if (editor != null) {
                    // Get the document from the editor
                    val document = editor.document

                    // Get the PsiFile from the editor's virtual file
                    val psiFile = PsiManager.getInstance(project).findFile(editor.virtualFile)

                    // Only proceed if we have a valid PsiFile
                    if (psiFile != null) {
                        // Create a mock InlineCompletionRequest
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
                                startOffset = editor.caretModel.primaryCaret.offset,
                                endOffset = editor.caretModel.primaryCaret.offset,
                                lookupElement = null,
                            )

                        // Get the module manager for the current project
                        val moduleManager = getModuleManager()

                        // Collect data from all registered modules
                        val collectedData = moduleManager.collectData(mockRequest)

                        // Return the collected data
                        collectedData
                    } else {
                        null
                    }
                } else {
                    // show the user a notification that no active editor was found
                    project.showErrorNotification(
                        "No active editor found",
                        "Please open a file in the editor to use AI features.",
                    )

                    null
                }
            }

        // Check if we successfully collected data
        if (editorData == null) {
            LOG.error("No active editor found in the project.")
            return TitleResponsePair(
                "Error: No active editor",
                emptyList(),
            )
        }

        // Process the collected data (this doesn't need read access)
        val aggregatedData =
            editorData
                .aggregateByType()
                .mapValues { (_, values) -> values.toMap() }

        // model preferences
        val modelPrefs = aggregatedData[Record.Type.MODEL]
        val modelId =
            getConfig().getModelsConfiguration()
                ?.getModelIdByName(selectedModel ?: modelPrefs?.get("preferredCompletionModel")?.toString() ?: "default")
        val systemPrompt = modelPrefs?.get("systemPrompt")?.toString() ?: "You are a helpful programming assistant."

        // construct the chat history
        val systemPromptPair = Pair(ChatConverter.SYSTEM_SENDER, systemPrompt)
        val chatHistory =
            (listOf(systemPromptPair) + previousMessages).let {
                ChatConverter.toApiMessages(it)
            }

        val contextMap = (aggregatedData[Record.Type.CONTEXT] ?: emptyMap()).toMutableMap()

        val basePath = project.basePath
        val relativeFiles =
            selectedFiles.mapNotNull { absolutePath ->
                basePath?.let { bp ->
                    if (absolutePath.startsWith(bp)) {
                        absolutePath.removePrefix(bp).removePrefix(File.separator)
                    } else {
                        // Skip files outside project
                        null
                    }
                }
            }

        contextMap["context_files"] = relativeFiles
        val context = contextMap.mapsTo(ContextData::class.java)

        val behavioralTelemetry =
            (aggregatedData[Record.Type.BEHAVIORAL_TELEMETRY] ?: emptyMap())
                .mapsTo<BehavioralTelemetryData>(
                    BehavioralTelemetryData::class.java,
                )
        val contextualTelemetry =
            (aggregatedData[Record.Type.CONTEXTUAL_TELEMETRY] ?: emptyMap())
                .mapsTo<ContextualTelemetryData>(
                    ContextualTelemetryData::class.java,
                )

        // create the request necessary for the AppService
        val request =
            RequestChatCompletion(
                modelIds = listOfNotNull(modelId),
                chatId = chatId?.let { java.util.UUID.fromString(it) } ?: java.util.UUID.randomUUID(),
                messages = chatHistory,
                context = context,
                contextualTelemetry = contextualTelemetry,
                behavioralTelemetry = behavioralTelemetry,
                webEnabled = useWeb,
            )

        // call the AppService to get the AI response
        val appService = getAppService()
        val response =
            appService.requestChatCompletion(
                request,
                project,
            )

        val newChatTitle = response.title
        return if (response.history.isNotEmpty()) {
            TitleResponsePair(
                newChatTitle,
                response.history.first().assistantResponses.map { it.completion },
            )
        } else {
            TitleResponsePair(newChatTitle, emptyList())
        }
    }
}
