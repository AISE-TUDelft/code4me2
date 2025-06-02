// ChatPanel.kt
package me.code4me.toolWindow

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import me.code4me.toolWindow.chatPanelUI.ChatDisplayPanel
import me.code4me.toolWindow.chatPanelUI.HistoryPanel
import me.code4me.toolWindow.chatPanelUI.InputPanel
import me.code4me.toolWindow.chatPanelUI.TopBarPanel
import me.code4me.toolWindow.managers.ChatIOManager
import me.code4me.toolWindow.managers.ChatSessionManager
import me.code4me.toolWindow.managers.ChatViewManager
import me.code4me.toolWindow.utils.ChatMessageRenderer
import java.awt.BorderLayout
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JList

class ChatPanel : JBPanel<ChatPanel>(BorderLayout()) {
    companion object {
        private const val WELCOME_MESSAGE = "Welcome! Ask me anything."
        private const val USER_NAME = "You"
        private const val AI_NAME = "Code4Me2"
    }

    private val ioManager = ChatIOManager()
    private val sessionManager = ChatSessionManager()

//    private val configManager = ConfigManager() //TODO USE CONFIG MANAGER FOR RETRIEVING MODELS
    private val selectedFiles = mutableSetOf<VirtualFile>()
    private var welcomeShown = true
    private var useWeb = false
    private var project: Project? = null

    private val messageRenderer = ChatMessageRenderer()

    private lateinit var inputPanel: InputPanel
    private lateinit var topBarPanel: TopBarPanel
    private lateinit var chatDisplayPanel: ChatDisplayPanel
    private lateinit var fileComboBox: ComboBox<VirtualFile>
    private lateinit var historyPanel: HistoryPanel

    private val viewManager = ChatViewManager()

    init {
        setupPanelLayout()
        subscribeToFileChanges()
        initializeChatHistory()
        loadModelsFromConfig()
    }

    private fun setupPanelLayout() {
        border = JBUI.Borders.empty(0)

        fileComboBox = createFileComboBox()
        inputPanel = createInputPanel()
        chatDisplayPanel = ChatDisplayPanel()
        topBarPanel = createTopBarPanel()
        historyPanel = createHistoryPanel()

        val mainChatArea = createMainChatArea()
        viewManager.setViews(mainChatArea, historyPanel)
        add(viewManager.getContainer(), BorderLayout.CENTER)
    }

    private fun createFileComboBox() =
        ComboBox<VirtualFile>().apply {
            renderer =
                object : ColoredListCellRenderer<VirtualFile>() {
                    override fun customizeCellRenderer(
                        list: JList<out VirtualFile>,
                        value: VirtualFile?,
                        index: Int,
                        selected: Boolean,
                        hasFocus: Boolean,
                    ) {
                        if (value == null) {
                            append("Select a file...", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        } else {
                            append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        }
                    }
                }

            addItemListener { event ->
                if (event.stateChange == ItemEvent.SELECTED) {
                    val selectedFile = event.item as? VirtualFile
                    if (selectedFile != null && !selectedFiles.contains(selectedFile)) {
                        addSelectedFile(selectedFile)
                    }
                    selectedIndex = 0
                }
            }
        }

    private fun createInputPanel() =
        InputPanel(
            onSend = ::sendMessage,
            onWebToggle = { enabled -> useWeb = enabled },
            fileComboBox = fileComboBox,
            onFileClose = ::removeSelectedFile,
            onFileSelected = ::addSelectedFile,
        )

    private fun createTopBarPanel() =
        TopBarPanel(
            sessionManager,
            onSessionSwitched = ::refreshChatDisplay,
            onNewChatCreated = ::resetToWelcome,
            onHistoryClicked = { viewManager.showHistoryPanel() },
        )

    private fun createHistoryPanel() =
        HistoryPanel(sessionManager) {
            topBarPanel.updateTitle()
            refreshChatDisplay()
            viewManager.showChatPanel()
        }

    private fun createMainChatArea() =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(topBarPanel, BorderLayout.NORTH)
            add(chatDisplayPanel, BorderLayout.CENTER)
            add(inputPanel, BorderLayout.SOUTH)
        }

    private fun subscribeToFileChanges() {
        project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val connection = project!!.messageBus.connect()

        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = updateFileDropdown()

                override fun fileOpened(
                    source: FileEditorManager,
                    file: VirtualFile,
                ) = updateFileDropdown()

                override fun fileClosed(
                    source: FileEditorManager,
                    file: VirtualFile,
                ) {
                    if (selectedFiles.contains(file)) {
                        removeSelectedFile(file)
                    }
                    updateFileDropdown()
                }
            },
        )

        updateFileDropdown()
    }

    private fun updateFileDropdown() {
        val openFiles = ioManager.getOpenEditorFiles()
        val model = DefaultComboBoxModel<VirtualFile>()
        model.addElement(null)
        openFiles.filterNot { selectedFiles.contains(it) }.forEach { model.addElement(it) }
        fileComboBox.model = model
    }

    private fun addSelectedFile(file: VirtualFile) {
        if (selectedFiles.add(file)) {
            inputPanel.addFileTab(file)
            updateFileDropdown()
        }
    }

    private fun removeSelectedFile(file: VirtualFile) {
        if (selectedFiles.remove(file)) {
            inputPanel.removeFileTab(file)
            updateFileDropdown()
        }
    }

    private fun resetToWelcome() {
        welcomeShown = true
        clearSelectedFiles()
    }

    private fun clearSelectedFiles() {
        selectedFiles.clear()
        updateFileDropdown()
        // Clear all file tabs from the input panel
        selectedFiles.toList().forEach { file ->
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
        val message = inputPanel.inputText.trim() // Fixed: using inputText instead of inputField.text
        if (message.isEmpty()) return

        if (welcomeShown) {
            welcomeShown = false
            sessionManager.currentSession.messages.removeIf { (sender, _) -> sender.isEmpty() }
            refreshChatDisplay()
        }

        appendMessage(USER_NAME, message)
        inputPanel.clearInput()

        processAIResponse(message)
    }

    private fun processAIResponse(userMessage: String) {
        val aiResponse =
            ioManager.getAIResponse(
                userMessage,
                useWeb,
                selectedFiles.mapNotNull { it.path },
            )
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
            val models = arrayOf("GPT-4", "Claude-3", "Gemini-Pro", "BEST MODEL EVER") //TODO use configmanager.getChatModels()
            updateModelList(models)

            val defaultModel = ("GPT-4") // TODO use configmanager.getChatDefaultModel()
            if (defaultModel != null && models.contains(defaultModel)) {
                // TODO: Set the default model in the combo box
            }
        } catch (e: Exception) {
            println("Error loading models from config: ${e.message}")
            updateModelList(getDefaultModels())
        }
    }

    private fun getDefaultModels(): Array<String> {
        return arrayOf("GPT-4", "Claude-3", "Gemini-Pro")
    }

    fun reloadModels() {
        loadModelsFromConfig()
    }
}
