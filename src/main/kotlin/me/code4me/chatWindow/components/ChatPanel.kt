package me.code4me.chatWindow.components

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.code4me.chatWindow.components.chatDisplayPanel.ChatDisplayPanel
import me.code4me.chatWindow.components.historyPanel.HistoryPanel
import me.code4me.chatWindow.components.inputPanel.InputPanel
import me.code4me.chatWindow.components.managers.ChatIOManager
import me.code4me.chatWindow.components.managers.ChatSessionManager
import me.code4me.chatWindow.components.managers.ChatViewManager
import me.code4me.chatWindow.components.persistence.ChatWindowStateService
import me.code4me.chatWindow.components.topBarPanel.TopBarPanel
import me.code4me.services.config.getConfig
import me.code4me.services.project.getProjectChatService
import java.awt.BorderLayout
import javax.swing.Timer

class ChatPanel : JBPanel<ChatPanel>(BorderLayout()) {
    companion object {
        private const val USER_NAME = "You"
        private const val AI_NAME = "Code4Me V2"
    }

    private val ioManager = ChatIOManager()
    private var sessionManager: ChatSessionManager? = null
    private val selectedFiles = mutableSetOf<VirtualFile>()
    private var welcomeShown = true
    private var useWeb = false
    private var project: Project? = null
    private var stateService: ChatWindowStateService? = null

    private lateinit var inputPanel: InputPanel
    private lateinit var topBarPanel: TopBarPanel
    private lateinit var chatDisplayPanel: ChatDisplayPanel
    private lateinit var historyPanel: HistoryPanel
    private val viewManager = ChatViewManager()
    private val uiScope = CoroutineScope(Dispatchers.Default)
    private val aiScope = CoroutineScope(Dispatchers.IO)
    private var aiJob: Job? = null
    private var regenerateJob: Job? = null
    private var loadingTimer: Timer? = null
    private var editIndex: Int? = null
    private var isEditing = false

    init {
        setupPanelLayout()
        restoreLastSession()
        loadModelsFromConfig()
    }

    private fun setupPanelLayout() {
        border = JBUI.Borders.empty()
        project = ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) return

        // Initialize the state service
        stateService = ChatWindowStateService.getInstance(project!!)

        // Initialize the session manager with the repository
        val chatRepository = getProjectChatService(project!!)
        sessionManager = ChatSessionManager(chatRepository)

        inputPanel = createInputPanel(project!!)
        inputPanel.setOnStop { cancelGeneration() }
        chatDisplayPanel = ChatDisplayPanel(project!!)
        chatDisplayPanel.onRestore = {
            refreshChatDisplay()
            chatDisplayPanel.forceRefresh()
        }
        chatDisplayPanel.onRegenerateFromIndex = { index -> regenerateFromIndex(index) }
        chatDisplayPanel.onEditUserMessage = { index, message ->
            enterEditMode(index, message)
        }
        topBarPanel = createTopBarPanel()
        historyPanel = createHistoryPanel()

        val mainChatArea = createMainChatArea()
        viewManager.setViews(mainChatArea, historyPanel)
        add(viewManager.getContainer(), BorderLayout.CENTER)
    }

    private fun createInputPanel(project: Project) =
        InputPanel(
            project = project,
            onSend = ::sendMessage,
            onWebToggle = { enabled -> useWeb = enabled },
            onFileClose = ::removeSelectedFile,
            onFileSelected = ::addSelectedFile,
        )

    private fun createTopBarPanel() =
        TopBarPanel(
            sessionManager!!,
            onSessionSwitched = {
                saveCurrentSession()
                refreshChatDisplay()
            },
            onNewChatCreated = {
                saveCurrentSession()
                resetToWelcome()
            },
            onHistoryClicked = {
                saveCurrentSession()
                viewManager.showHistoryPanel()
            },
            onTitleRenamed = { historyPanel.refresh() },
        )

    private fun createHistoryPanel() =
        HistoryPanel(sessionManager!!) {
            ApplicationManager.getApplication().invokeLater {
                topBarPanel.updateTitle()
                saveCurrentSession()
                refreshChatDisplay()
                viewManager.showChatPanel()
            }
        }

    private fun createMainChatArea() =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(topBarPanel, BorderLayout.NORTH)
            add(chatDisplayPanel, BorderLayout.CENTER)
            add(inputPanel, BorderLayout.SOUTH)
        }

    private fun saveCurrentSession() {
        sessionManager?.currentSession?.let { session ->
            stateService?.setLastSessionId(session.id.toString())
            sessionManager?.chatRepository?.saveChat(session)
        }
    }

    private fun restoreLastSession() {
        val lastSessionId = stateService?.getLastSessionId()
        if (lastSessionId != null && sessionManager != null) {
            // Try to find and restore the last session
            val session = sessionManager!!.getAllSessions().find { it.id.toString() == lastSessionId }
            if (session != null) {
                sessionManager!!.switchToSession(session)
                welcomeShown = session.messages.isEmpty()
                // Force refresh to prevent the green background issue
                ApplicationManager.getApplication().invokeLater {
                    refreshChatDisplay()
                    chatDisplayPanel.forceRefresh()
                }
                return
            }
        }
        // If no valid last session, initialize with default
        initializeChatHistory()
    }

    private fun addSelectedFile(file: VirtualFile) {
        if (selectedFiles.add(file)) {
            inputPanel.addFileTab(file)
        }
    }

    private fun removeSelectedFile(file: VirtualFile) {
        if (selectedFiles.remove(file)) {
            inputPanel.removeFileTab(file)
        }
    }

    private fun resetToWelcome() {
        welcomeShown = true
        clearSelectedFiles()
    }

    private fun clearSelectedFiles() {
        val filesToRemove = selectedFiles.toList()
        selectedFiles.clear()
        filesToRemove.forEach { inputPanel.removeFileTab(it) }
    }

    private fun initializeChatHistory() {
        sessionManager?.currentSession?.messages?.clear()
        sessionManager?.let { manager ->
            manager.chatRepository.saveChat(manager.currentSession)
        }
        refreshChatDisplay()
    }

    private fun sendMessage() {
        val message = inputPanel.inputText.trim()
        if (message.isEmpty() || sessionManager == null) return

        if (isEditing) {
            val index = editIndex ?: return
            val messages = sessionManager!!.currentSession.messages
            if (editIndex != null && editIndex!! < messages.size) {
                val retained = messages.take(editIndex!!)
                messages.clear()
                messages.addAll(retained)
            }
            exitEditMode()
        }

        if (welcomeShown) {
            welcomeShown = false
            sessionManager!!.currentSession.messages.removeIf { (sender, _) -> sender.isEmpty() }
            sessionManager!!.chatRepository.saveChat(sessionManager!!.currentSession)
            refreshChatDisplay()
        }
        inputPanel.setGeneratingState(true)

        val selectedModel = inputPanel.getSelectedModel()
        appendMessage(USER_NAME, message)
        inputPanel.clearInput()
        chatDisplayPanel.scrollToBottomOnUserAction()

        // Save session after sending message
        saveCurrentSession()

        // Add a loading message
        appendMessage(AI_NAME, "Generating.")

        // Create a timer to update the loading indicator
        val loadingPatterns = arrayOf("Generating.", "Generating..", "Generating...")
        var patternIndex = 0
        loadingTimer?.stop() // stop any previous
        loadingTimer =
            Timer(300) {
                val loadingText = loadingPatterns[patternIndex]
                chatDisplayPanel.updateLastBubbleText(loadingText)
                patternIndex = (patternIndex + 1) % loadingPatterns.size
            }
        loadingTimer?.start()

        // Process AI response in background
        aiJob =
            aiScope.launch {
                val aiResponse = processAIResponse(message, selectedModel)

                if (!isActive) return@launch

                ApplicationManager.getApplication().invokeLater {
                    loadingTimer?.stop()
                    inputPanel.setGeneratingState(false)
                    aiJob = null

                    if (aiResponse.title.isNotBlank() && sessionManager?.currentSession?.title != aiResponse.title) {
                        sessionManager?.currentSession?.title = aiResponse.title
                        topBarPanel.updateTitle()
                    }

                    if (aiResponse.responses.isNotEmpty()) {
                        updateLastMessage(aiResponse.responses.first())
                        refreshChatDisplay()
                        sessionManager?.chatRepository?.saveChat(sessionManager!!.currentSession)
                    } else {
                        updateLastMessage("No response received")
                    }

                    saveCurrentSession()
                }
            }
    }

    private fun cancelGeneration() {
        aiJob?.cancel()
        regenerateJob?.cancel()
        aiJob = null
        regenerateJob = null
        loadingTimer?.stop()
        loadingTimer = null

        val cancelMsg = "This query request was cancelled by user."
        inputPanel.setGeneratingState(false)
        chatDisplayPanel.updateLastBubbleText(cancelMsg)
        updateLastMessage(cancelMsg)
        saveCurrentSession()
    }

    private suspend fun processAIResponse(
        query: String,
        selectedModel: String?,
    ) = ioManager.getAIResponse(
        useWeb,
        selectedFiles.map { it.path },
        selectedModel,
        sessionManager?.currentSession?.id?.toString(),
        sessionManager?.currentSession?.messages!!,
        project!!,
    )

    private fun appendMessage(
        sender: String,
        message: String,
    ) {
        sessionManager?.addMessageToCurrentSession(sender, message)
        refreshChatDisplay()
    }

    private fun updateLastMessage(newMessage: String) {
        if (sessionManager?.currentSession?.messages?.isNotEmpty() == true) {
            val messages = sessionManager!!.currentSession.messages
            val lastIndex = messages.size - 1
            val (sender, _) = messages[lastIndex]

            // Replace the last message
            messages[lastIndex] = sender to newMessage

            // Update the display
            refreshChatDisplay()
        }
    }

    private fun refreshChatDisplay() {
        uiScope.launch {
            val messages = sessionManager?.currentSession?.messages ?: mutableListOf()
            ApplicationManager.getApplication().invokeLater {
                chatDisplayPanel.updateContent(messages)
                historyPanel.refresh()
            }
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        // Save current session when panel is being disposed
        saveCurrentSession()
    }

    fun updateModelList(models: Array<String>) {
        inputPanel.updateModelComboBox(models)
    }

    fun clearChat() {
        selectedFiles.clear()
        initializeChatHistory()
        resetToWelcome()
        saveCurrentSession()
    }

    private fun loadModelsFromConfig() {
        try {
            val models =
                getConfig()
                    .getModelsConfiguration()
                    ?.getAvailableChatModels()
                    ?.map { it.name }?.toTypedArray()
                    ?: emptyArray<String>()
            updateModelList(models)
        } catch (e: Exception) {
            println("Error loading models from config: ${e.message}")
            updateModelList(arrayOf("No models available"))
        }
    }

    fun reloadModels() {
        loadModelsFromConfig()
    }

    /**
     * Regenerates the chat from a specific index. (This is used for the "Regenerate" action)
     */
    private fun regenerateFromIndex(index: Int) {
        val messages = sessionManager?.currentSession?.messages ?: return
        if (index < 0 || index >= messages.size) return

        // Keep only messages up to the specified index (excluding the message at that index)
        val retainedMessages = messages.subList(0, index).toList()

        // Clear current messages and add retained ones
        sessionManager?.currentSession?.messages?.clear()
        sessionManager?.currentSession?.messages?.addAll(retainedMessages)

        saveCurrentSession()
        refreshChatDisplay()

        // Find last user message to regenerate from
        val lastUserMsg = retainedMessages.lastOrNull { it.first == USER_NAME }?.second ?: return
        val selectedModel = inputPanel.getSelectedModel()

        // Add loading message
        appendMessage(AI_NAME, "Generating.")
        inputPanel.setGeneratingState(true)

        // Start loading animation
        val loadingPatterns = arrayOf("Generating.", "Generating..", "Generating...")
        var patternIndex = 0
        loadingTimer?.stop()
        loadingTimer =
            Timer(300) {
                updateLastMessage(loadingPatterns[patternIndex])
                patternIndex = (patternIndex + 1) % loadingPatterns.size
            }
        loadingTimer?.start()

        // Launch AI coroutine
        aiJob =
            aiScope.launch {
                val aiResponse = processAIResponse(lastUserMsg, selectedModel)

                if (!isActive) return@launch // Cancelled

                ApplicationManager.getApplication().invokeLater {
                    loadingTimer?.stop()
                    inputPanel.setGeneratingState(false)
                    aiJob = null

                    if (aiResponse.responses.isNotEmpty()) {
                        updateLastMessage(aiResponse.responses.first())
                        refreshChatDisplay()
                    } else {
                        updateLastMessage("No response received")
                    }

                    if (aiResponse.title.isNotBlank()) {
                        sessionManager?.currentSession?.title = aiResponse.title
                        topBarPanel.updateTitle()
                    }

                    saveCurrentSession()
                }
            }
    }

    private fun enterEditMode(
        index: Int,
        originalText: String,
    ) {
        editIndex = index
        isEditing = true
        inputPanel.setInputText(originalText)
        inputPanel.focusInputField()
        inputPanel.showCancelEditButton {
            exitEditMode()
        }
        chatDisplayPanel.showEditOverlay()
    }

    private fun exitEditMode() {
        editIndex = null
        isEditing = false
        inputPanel.clearInput()
        inputPanel.hideCancelEditButton()
        chatDisplayPanel.hideEditOverlay()
    }

    /**
     * TODO this doesn't update it live
     * Resets all chats and memory after user logout.
     */
    fun resetAllChatsAfterLogout() {
        project?.let { proj ->
            val chatService = getProjectChatService(proj)
            chatService.clearAllChatsAndMemory()

            sessionManager = ChatSessionManager(chatService)

            // Recreate default session and clear UI
            sessionManager?.createNewSession("New Chat")
            chatDisplayPanel.updateContent(emptyList())
            historyPanel.refresh()
            topBarPanel.updateTitle()
            resetToWelcome()
        }
    }
}
