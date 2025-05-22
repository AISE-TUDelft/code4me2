package me.code4me.toolWindow

import me.code4me.toolWindow.chatPanelUI.ChatDisplayPanel
import me.code4me.toolWindow.chatPanelUI.InputPanel
import me.code4me.toolWindow.chatPanelUI.TopBarPanel
import me.code4me.toolWindow.managers.ChatIOManager
import me.code4me.toolWindow.managers.ChatSessionManager
import me.code4me.toolWindow.utils.ChatMessageRenderer
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Main chat panel that manages UI and chat interactions.
 */
class ChatPanel : JPanel(BorderLayout()) {
    companion object {
        private const val WELCOME_MESSAGE = "Welcome! Ask me anything."
        private const val USER_NAME = "You"
        private const val AI_NAME = "Code4Me2"

        private val DARK_BG_COLOR = Color(43, 43, 43)
        private val BUTTON_BG_COLOR = Color(75, 75, 75)
        private val WEB_TOGGLE_INACTIVE = Color(60, 63, 65)
        private val WEB_TOGGLE_ACTIVE = Color(75, 110, 175)
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
        border = EmptyBorder(10, 10, 10, 10)

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
        val popup = JPopupMenu()

        for (file in openFiles) {
            val item = JCheckBoxMenuItem(file, selectedFiles.contains(file))
            item.addActionListener {
                if (item.state) {
                    selectedFiles.add(file)
                } else {
                    selectedFiles.remove(file)
                }
            }
            popup.add(item)
        }

        popup.show(inputPanel, inputPanel.width - popup.preferredSize.width, inputPanel.height)
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
