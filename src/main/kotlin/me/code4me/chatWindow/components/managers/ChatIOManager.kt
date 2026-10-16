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
import me.code4me.services.state.getAuthState
import me.code4me.utils.api.activateOrCreateProject
import me.code4me.utils.api.mapsTo
import me.code4me.utils.notification.showErrorNotification
import me.code4me.utils.record.Record
import me.code4me.utils.record.aggregateByType
import me.code4me.utils.record.toMap
import java.io.File

/**
 * Manager for handling chat input and AI response
 */
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

        // Activate unconditionally. `isActivated()` is a purely local flag: it stays true across a
        // re-login even though the new server-side session has no project activated, so gating on
        // it made every chat in that session fail. Re-activating is idempotent server-side.
        activateOrCreateProject(project, LOG)

        // Collect editor data within a read action

        val editorData =
            readAction {
                var editor = FileEditorManager.getInstance(project).selectedTextEditor

                if (editor != null) {
                    val document = editor.document
                    val virtualFile = editor.virtualFile

                    // Check if file is empty or has minimal content
                    val isEmpty = document.textLength == 0
                    val isMinimalContent = document.textLength < 10

                    val psiFile = virtualFile?.let { PsiManager.getInstance(project).findFile(it) }

                    if (isEmpty || psiFile == null) {
                        LOG.info("Handling empty or problematic file: ${virtualFile?.name}")

                        if (psiFile != null) {
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
                                    startOffset = 0,
                                    endOffset = 0,
                                    lookupElement = null,
                                )

                            val moduleManager = getModuleManager()

                            try {
                                val collectedData = moduleManager.collectData(mockRequest)

                                // If no meaningful data was collected, create minimal context
                                if (collectedData.isEmpty() ||
                                    collectedData.none { it.type == Record.Type.CONTEXT }
                                ) {
                                    // Create minimal context record for empty files
                                    val minimalContext =
                                        Record(Record.Type.CONTEXT).apply {
                                            put(Record.key<String>("file_name"), virtualFile?.name ?: "untitled")
                                            put(Record.key<String>("file_path"), virtualFile?.path ?: "")
                                            put(Record.key<String>("prefix"), "")
                                            put(Record.key<String>("suffix"), "")
                                            put(Record.key<Boolean>("is_empty_file"), true)
                                        }

                                    return@readAction listOf(minimalContext)
                                }

                                return@readAction collectedData
                            } catch (e: Exception) {
                                LOG.warn("Failed to collect data for empty file, using fallback", e)
                            }
                        }

                        // Fallback for null PsiFile or failed module collection
                        val fallbackContext =
                            Record(Record.Type.CONTEXT).apply {
                                put(Record.key<String>("file_name"), virtualFile?.name ?: "untitled")
                                put(Record.key<String>("file_path"), virtualFile?.path ?: "")
                                put(Record.key<String>("prefix"), "")
                                put(Record.key<String>("suffix"), "")
                                put(Record.key<Boolean>("is_empty_file"), true)
                            }

                        return@readAction listOf(fallbackContext)
                    } else {
                        // Normal processing for non-empty files
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

                        val moduleManager = getModuleManager()
                        moduleManager.collectData(mockRequest)
                    }
                } else {
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
        val modelsConfiguration = getConfig().getModelsConfiguration()
        // This is the chat path, so it must read preferredChatModel — reading
        // preferredCompletionModel here sent the user's completion model to the chat endpoint.
        // "default" resolves to the first default model of either kind, so fall back explicitly
        // to the default *chat* model when the named lookup misses.
        val modelId =
            modelsConfiguration
                ?.getModelIdByName(selectedModel ?: modelPrefs?.get("preferredChatModel")?.toString() ?: "default")
                ?: modelsConfiguration?.getDefaultChatModelId()
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
            sanitizeNumericValues(
                aggregatedData[Record.Type.BEHAVIORAL_TELEMETRY]?.toMap() ?: emptyMap(),
            ).mapsTo<BehavioralTelemetryData>(
                BehavioralTelemetryData::class.java,
            )

        val contextualTelemetry =
            sanitizeNumericValues(
                aggregatedData[Record.Type.CONTEXTUAL_TELEMETRY]?.toMap() ?: emptyMap(),
            ).mapsTo<ContextualTelemetryData>(
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

    /**
     * Sanitizes a map by replacing NaN and infinite values with safe defaults
     */
    private fun sanitizeNumericValues(data: Map<String, Any>): Map<String, Any> {
        return data.mapValues { (_, value) ->
            when (value) {
                is Double -> {
                    when {
                        value.isNaN() -> 0.0
                        value.isInfinite() -> if (value > 0) Double.MAX_VALUE else -Double.MAX_VALUE
                        else -> value
                    }
                }
                is Float -> {
                    when {
                        value.isNaN() -> 0.0f
                        value.isInfinite() -> if (value > 0) Float.MAX_VALUE else -Float.MAX_VALUE
                        else -> value
                    }
                }
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    sanitizeNumericValues(value as Map<String, Any>)
                }
                is List<*> -> {
                    value.map { item ->
                        when (item) {
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                sanitizeNumericValues(item as Map<String, Any>)
                            }
                            is Double -> {
                                when {
                                    item.isNaN() -> 0.0
                                    item.isInfinite() -> if (item > 0) Double.MAX_VALUE else -Double.MAX_VALUE
                                    else -> item
                                }
                            }
                            is Float -> {
                                when {
                                    item.isNaN() -> 0.0f
                                    item.isInfinite() -> if (item > 0) Float.MAX_VALUE else -Float.MAX_VALUE
                                    else -> item
                                }
                            }
                            else -> item
                        }
                    }
                }
                else -> value
            }
        }
    }
}
