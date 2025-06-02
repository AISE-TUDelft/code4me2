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
    }

    private fun setupPanelLayout() {
        border = JBUI.Borders.empty(0)

        fileComboBox =
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

        inputPanel =
            InputPanel(
                onSend = ::sendMessage,
                onWebToggle = { enabled -> useWeb = enabled },
                fileComboBox = fileComboBox,
                onFileClose = { file -> removeSelectedFile(file) },
                onFileSelected = { file -> addSelectedFile(file) },
            )

        chatDisplayPanel = ChatDisplayPanel()
        topBarPanel =
            TopBarPanel(
                sessionManager,
                onSessionSwitched = ::refreshChatDisplay,
                onNewChatCreated = ::resetToWelcome,
                onHistoryClicked = { viewManager.showHistoryPanel() },
            )

        historyPanel =
            HistoryPanel(sessionManager) {
                topBarPanel.updateTitle()
                refreshChatDisplay()
                viewManager.showChatPanel()
            }

        val mainChatArea =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(topBarPanel, BorderLayout.NORTH)
                add(chatDisplayPanel, BorderLayout.CENTER)
                add(inputPanel, BorderLayout.SOUTH)
            }

        viewManager.setViews(mainChatArea, historyPanel)
        add(viewManager.getContainer(), BorderLayout.CENTER)
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
    }

    private fun initializeChatHistory() {
        sessionManager.currentSession.messages.clear()
        sessionManager.currentSession.messages.add(
            "" to "<div style='text-align:center; font-size: 16px; margin-top: 20px; margin-bottom: 20px;'>$WELCOME_MESSAGE</div>",
        )
        refreshChatDisplay()
    }

    private fun sendMessage() {
        val message = inputPanel.inputField.text.trim()
        if (message.isEmpty()) return

        if (welcomeShown) {
            welcomeShown = false
            sessionManager.currentSession.messages.removeIf { (sender, _) -> sender.isEmpty() }
            refreshChatDisplay()
        }
        appendMessage(USER_NAME, message)
        inputPanel.clearInput()

        val aiResponse =
            ioManager.getAIResponse(
                message,
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
}
