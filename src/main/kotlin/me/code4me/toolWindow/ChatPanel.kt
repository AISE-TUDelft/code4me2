package me.code4me.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import me.code4me.toolWindow.managers.ChatIOManager
import me.code4me.toolWindow.managers.ChatSessionManager
import me.code4me.toolWindow.managers.ChatViewManager
import me.code4me.toolWindow.ui.ChatDisplayPanel
import me.code4me.toolWindow.ui.HistoryPanel
import me.code4me.toolWindow.ui.InputPanel
import me.code4me.toolWindow.ui.TopBarPanel
import me.code4me.toolWindow.utils.ChatMessageRenderer
import java.awt.BorderLayout

/**
 * Main container panel for the chat interface.
 *
 * Orchestrates the layout and behavior of the top bar, chat display,
 * input panel, file dropdown, and history panel. Also handles message sending,
 * session management, and interaction with the AI and file system.
 */
class ChatPanel : JBPanel<ChatPanel>(BorderLayout()) {
    companion object {
        private const val WELCOME_MESSAGE = "Welcome! Ask me anything."
        private const val USER_NAME = "You"
        private const val AI_NAME = "Code4Me2"
    }

    private val ioManager = ChatIOManager()
    private val sessionManager = ChatSessionManager()

    //    private val configManager = ConfigManager() //TODO USE CONFIG MANAGER FOR RETRIEVING MODELS. also it mgiht be better to do it in the loadModelsFromConfig method.
    private val selectedFiles = mutableSetOf<VirtualFile>()
    private var welcomeShown = true
    private var useWeb = false
    private var project: Project? = null

    private val messageRenderer = ChatMessageRenderer()

    private lateinit var inputPanel: InputPanel
    private lateinit var topBarPanel: TopBarPanel
    private lateinit var chatDisplayPanel: ChatDisplayPanel
    private lateinit var historyPanel: HistoryPanel

    private val viewManager = ChatViewManager()

    init {
        setupPanelLayout()
//        subscribeToFileChanges()
        initializeChatHistory()
        loadModelsFromConfig()
    }

    /**
     * Sets up the layout of the main chat components.
     * Initializes subpanels and registers them with the view manager.
     */
    private fun setupPanelLayout() {
        border = JBUI.Borders.empty()

        project = ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) return

        inputPanel = createInputPanel(project!!)
        chatDisplayPanel = ChatDisplayPanel()
        topBarPanel = createTopBarPanel()
        historyPanel = createHistoryPanel()

        val mainChatArea = createMainChatArea()
        viewManager.setViews(mainChatArea, historyPanel)
        add(viewManager.getContainer(), BorderLayout.CENTER)
    }

    /**
     * Creates the input panel with callbacks for sending messages,
     * toggling web usage, and file tab management.
     *
     * @param project the current project instance.
     * @return configured InputPanel instance.
     */
    private fun createInputPanel(project: Project) =
        InputPanel(
            project = project,
            onSend = ::sendMessage,
            onWebToggle = { enabled -> useWeb = enabled },
            onFileClose = ::removeSelectedFile,
            onFileSelected = ::addSelectedFile,
        )

    /**
     * Creates the top bar panel managing chat sessions and UI events.
     *
     * @return configured TopBarPanel instance.
     */
    private fun createTopBarPanel() =
        TopBarPanel(
            sessionManager,
            onSessionSwitched = ::refreshChatDisplay,
            onNewChatCreated = ::resetToWelcome,
            onHistoryClicked = { viewManager.showHistoryPanel() },
            onTitleRenamed = { historyPanel.refresh() },
        )

    /**
     * Creates the history panel that shows chat history and allows
     * switching back to the chat panel.
     *
     * @return configured HistoryPanel instance.
     */
    private fun createHistoryPanel() =
        HistoryPanel(sessionManager) {
            topBarPanel.updateTitle()
            refreshChatDisplay()
            viewManager.showChatPanel()
        }

    /**
     * Constructs the main chat area panel by combining the top bar,
     * chat display, and input panel in a BorderLayout.
     *
     * @return the main chat area panel.
     */
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
        filesToRemove.forEach { file ->
            inputPanel.removeFileTab(file)
        }
    }

    private fun initializeChatHistory() {
        sessionManager.currentSession.messages.clear()
        sessionManager.currentSession.messages.add(
            "" to "<div style='text-align:center; font-size: 16px; margin-top: 20px; margin-bottom: 20px;'>$WELCOME_MESSAGE</div>",
        )
        refreshChatDisplay()
    }

    private fun sendMessage() {
        val message = inputPanel.inputText.trim()
        if (message.isEmpty()) return

        if (welcomeShown) {
            welcomeShown = false
            sessionManager.currentSession.messages.removeIf { (sender, _) -> sender.isEmpty() }
            refreshChatDisplay()
        }
        val selectedModel = inputPanel.getSelectedModel()
        appendMessage(USER_NAME, message)
        inputPanel.clearInput()

        // TODO proper coroutine handling for ai response
        processAIResponse(message, selectedModel)
    }

    private fun processAIResponse(
        query: String,
        selectedModel: String?,
    ) {
        val aiResponse =
            ioManager.getAIResponse(query, useWeb, selectedFiles.mapNotNull { it.path }, selectedModel)
        appendMessage(AI_NAME, aiResponse)
    }

    private fun appendMessage(
        sender: String,
        message: String,
    ) {
        sessionManager.addMessageToCurrentSession(sender, message)
        refreshChatDisplay()
    }

    private fun refreshChatDisplay() {
        chatDisplayPanel.updateContent(sessionManager.currentSession.messages, messageRenderer)
        historyPanel.refresh()
    }

    // Public API for external access
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
//            val models = configManager.getModels()
            val models = arrayOf("GPT-4", "Claude-3", "Gemini-Pro", "BEST MODEL EVER") // TODO use configmanager.getChatModels()
            updateModelList(models)

            val defaultModel = ("GPT-4") // TODO use configmanager.getChatDefaultModel()
            if (defaultModel != null && models.contains(defaultModel)) {
                // TODO: Set the default model in the combo box
            }
        } catch (e: Exception) {
            println("Error loading models from config: ${e.message}")
            updateModelList(arrayOf("No models available"))
        }
    }

    fun reloadModels() {
        loadModelsFromConfig()
    }
}
