package me.code4me.chatWindow.components

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        chatDisplayPanel = ChatDisplayPanel(project!!)
        chatDisplayPanel.onRestore = {
            refreshChatDisplay()
            chatDisplayPanel.forceRefresh()
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
            onHistoryClicked = { viewManager.showHistoryPanel() },
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
        sessionManager?.currentSession?.id?.toString()?.let { sessionId ->
            stateService?.setLastSessionId(sessionId)
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

        if (welcomeShown) {
            welcomeShown = false
            sessionManager!!.currentSession.messages.removeIf { (sender, _) -> sender.isEmpty() }
            sessionManager!!.chatRepository.saveChat(sessionManager!!.currentSession)
            refreshChatDisplay()
        }

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
        val loadingTimer =
            Timer(300) { _ ->
                val loadingText = loadingPatterns[patternIndex]
                updateLastMessage(loadingText)
                patternIndex = (patternIndex + 1) % loadingPatterns.size
            }
        loadingTimer.start()

        // Process AI response in background
        aiScope.launch {
            val aiResponse = processAIResponse(message, selectedModel)

            // Update UI on main thread
            ApplicationManager.getApplication().invokeLater {
                // Stop the loading timer
                loadingTimer.stop()

                // Update title if needed
                if (aiResponse.title.isNotBlank() && sessionManager?.currentSession?.title != aiResponse.title) {
                    sessionManager?.currentSession?.title = aiResponse.title
                    topBarPanel.updateTitle()
                }

                // Replace the loading message with the actual response
                if (aiResponse.responses.isNotEmpty()) {
                    updateLastMessage(aiResponse.responses.first())
                    refreshChatDisplay()
                } else {
                    updateLastMessage("No response received")
                }

                // Save session after receiving response
                saveCurrentSession()
            }
        }
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
}
