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
import me.code4me.chatWindow.components.topBarPanel.TopBarPanel
import me.code4me.services.config.getConfig
import me.code4me.services.project.getProjectChatService
import java.awt.BorderLayout

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

    private lateinit var inputPanel: InputPanel
    private lateinit var topBarPanel: TopBarPanel
    private lateinit var chatDisplayPanel: ChatDisplayPanel
    private lateinit var historyPanel: HistoryPanel
    private val viewManager = ChatViewManager()
    private val uiScope = CoroutineScope(Dispatchers.Default)

    init {
        setupPanelLayout()
        initializeChatHistory()
        loadModelsFromConfig()
    }

    private fun setupPanelLayout() {
        border = JBUI.Borders.empty()
        project = ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) return

        // Initialize the session manager with the repository
        val chatRepository = getProjectChatService(project!!)
        sessionManager = ChatSessionManager(chatRepository)

        inputPanel = createInputPanel(project!!)
        chatDisplayPanel = ChatDisplayPanel(project!!)
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
            onSessionSwitched = ::refreshChatDisplay,
            onNewChatCreated = ::resetToWelcome,
            onHistoryClicked = { viewManager.showHistoryPanel() },
            onTitleRenamed = { historyPanel.refresh() },
        )

    private fun createHistoryPanel() =
        HistoryPanel(sessionManager!!) {
            ApplicationManager.getApplication().invokeLater {
                topBarPanel.updateTitle()
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
        processAIResponse(message, selectedModel)
    }

    private fun processAIResponse(
        query: String,
        selectedModel: String?,
    ) {
        val aiResponse = ioManager.getAIResponse(
            query,
            useWeb,
            selectedFiles.map { it.path },
            selectedModel,
            sessionManager?.currentSession?.id?.toString(),
            sessionManager?.currentSession?.messages!!,
            project!!)
        // TODO: handle multiple responses
        if (aiResponse.title.isNotBlank() && sessionManager?.currentSession?.title != aiResponse.title) {
            sessionManager?.currentSession?.title = aiResponse.title
            topBarPanel.updateTitle()
        }
        appendMessage(AI_NAME, aiResponse.responses.first())
    }

    private fun appendMessage(
        sender: String,
        message: String,
    ) {
        sessionManager?.addMessageToCurrentSession(sender, message)
        refreshChatDisplay()
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

    fun updateModelList(models: Array<String>) {
        inputPanel.updateModelComboBox(models)
    }

    fun clearChat() {
        selectedFiles.clear()
        initializeChatHistory()
        resetToWelcome()
    }

    private fun loadModelsFromConfig() {
        try {
            val models = getConfig()
                .getModelsConfiguration()
                ?.getAvailableChatModels()
                ?.map {it.name}?.toTypedArray()
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
