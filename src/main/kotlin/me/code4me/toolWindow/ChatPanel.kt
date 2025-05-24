package me.code4me.toolWindow
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import me.code4me.toolWindow.chatPanelUI.ChatDisplayPanel
import me.code4me.toolWindow.chatPanelUI.InputPanel
import me.code4me.toolWindow.chatPanelUI.TopBarPanel
import me.code4me.toolWindow.managers.ChatIOManager
import me.code4me.toolWindow.managers.ChatSessionManager
import me.code4me.toolWindow.utils.ChatMessageRenderer
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JPanel

/**
 * Main chat panel that manages UI and chat interactions.
 */
class ChatPanel : JBPanel<ChatPanel>(BorderLayout()) {
    companion object {
        private const val WELCOME_MESSAGE = "Welcome! Ask me anything."
        private const val USER_NAME = "You"
        private const val AI_NAME = "Code4Me2"
        private val BORDER_COLOR = Color(120, 120, 120)
    }

    private val ioManager = ChatIOManager()
    private val sessionManager = ChatSessionManager()
    private val selectedFiles = mutableListOf<String>()
    private var welcomeShown = true
    private var useWeb = false

    private val messageRenderer = ChatMessageRenderer()

    private lateinit var inputPanel: InputPanel
    private lateinit var topBarPanel: TopBarPanel
    private lateinit var chatDisplayPanel: ChatDisplayPanel

    init {
        setupPanelLayout()
        initializeChatHistory()
    }

    /**
     *  basic panel layout and borders
     */
    private fun setupPanelLayout() {
        border = JBUI.Borders.empty(10)

        inputPanel =
            InputPanel(
                onSend = ::sendMessage,
                onWebToggle = { enabled -> useWeb = enabled },
                onFileSelect = ::showFileSelectionPopup,
                borderColor = BORDER_COLOR,
            )
        chatDisplayPanel = ChatDisplayPanel()
        topBarPanel = TopBarPanel(sessionManager, ::refreshChatDisplay, ::resetToWelcome)
        add(topBarPanel, BorderLayout.NORTH)
        add(chatDisplayPanel, BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)
    }

    private fun resetToWelcome() {
        welcomeShown = true
    }

    /**
     * Shows the file selection popup
     */
    private fun showFileSelectionPopup() {
        val openFiles = ioManager.getOpenEditorFiles()

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        val checkBoxes =
            openFiles.map { file ->
                JCheckBox(file, selectedFiles.contains(file)).apply {
                    addActionListener {
                        if (isSelected) {
                            selectedFiles.add(file)
                        } else {
                            selectedFiles.remove(file)
                        }
                    }
                }
            }

        checkBoxes.forEach(panel::add)

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setTitle("Select Files")
            .setResizable(true)
            .setMovable(true)
            .createPopup()
            .showInCenterOf(inputPanel)
    }

    private fun initializeChatHistory() {
        sessionManager.currentSession.messages.clear()
        sessionManager.currentSession.messages.add(
            "" to "<div style='text-align:center; font-size: 16px; margin-top: 20px; margin-bottom: 20px;'>$WELCOME_MESSAGE</div>",
        )
        refreshChatDisplay()
    }

    /**
     * Sends the message from the input field and appends, with respose
     */
    private fun sendMessage() {
        val message = inputPanel.inputField.text.trim()
        if (message.isEmpty()) return
        if (welcomeShown) {
            welcomeShown = false
            sessionManager.currentSession.messages.removeIf { (sender, _) -> sender.isEmpty() }
            refreshChatDisplay()
        }
        appendMessage(USER_NAME, message)
        inputPanel.inputField.text = ""

        // TODO coroutines
        val aiResponse = ioManager.getAIResponse(message, useWeb, selectedFiles.toList())
        appendMessage(AI_NAME, aiResponse)
    }

    private fun appendMessage(
        sender: String,
        message: String,
    ) {
        sessionManager.currentSession.messages.add(sender to message)
        refreshChatDisplay()
    }

    private fun refreshChatDisplay() {
        chatDisplayPanel.updateContent(sessionManager.currentSession.messages, messageRenderer)
    }
}
