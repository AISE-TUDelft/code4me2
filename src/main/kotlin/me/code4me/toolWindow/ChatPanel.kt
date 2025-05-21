package me.code4me.toolWindow

import com.intellij.ui.components.JBScrollPane
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

    private val sessionTitleLabel = JLabel()
    private val chatHistory = StringBuilder()
    private val chatArea = JTextPane()
    private val inputField = JTextField()
    private val sendButton = JButton("Send")
    private val plusButton = JButton("+")
    private val webButton = JButton("Web")
    private val topPanel = JPanel(FlowLayout(FlowLayout.LEFT))

    init {
        setupPanelLayout()
        setupUIComponents()
        initializeEventListeners()
        initializeChatHistory()
        updateSessionTitle()
    }

    /**
     *  basic panel layout and borders
     */
    private fun setupPanelLayout() {
        border = EmptyBorder(10, 10, 10, 10)

        val scrollPane =
            JBScrollPane(chatArea).apply {
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            }

        val inputPanel = createInputPanel()

        val centerPanel =
            JPanel(BorderLayout()).apply {
                add(scrollPane, BorderLayout.CENTER)
            }

        add(topPanel, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)
    }

    private fun updateSessionTitle() {
        sessionTitleLabel.text = sessionManager.currentSession.title
        sessionTitleLabel.font = Font("SansSerif", Font.BOLD, 16)
        sessionTitleLabel.foreground = Color.WHITE
    }

    private fun createInputPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val rightPanel =
                JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                    add(plusButton)
                    add(webButton)
                    add(sendButton)
                }

            add(inputField, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.EAST)
        }
    }

    /**
     * Sets up individual UI components with their properties
     */
    private fun setupUIComponents() {
        chatArea.apply {
            contentType = "text/html"
            isEditable = false
        }
        setupSessionPanel()
        setupFileSelectionButton()
        setupWebButton()
    }

    /**
     * Sets up the session (history list and new chat panel) panel with new chat and history buttons
     */
    private fun setupSessionPanel() {
        topPanel.apply {
            background = DARK_BG_COLOR

            val newChatButton =
                JButton("＋ New Chat").apply {
                    addActionListener {
                        val title =
                            JOptionPane.showInputDialog(
                                this@ChatPanel,
                                "Enter a name for the new chat:",
                                "New Chat",
                                JOptionPane.PLAIN_MESSAGE,
                            )
                        sessionManager.createNewSession(title)
                        welcomeShown = true
                        refreshChatDisplay()
                        updateSessionTitle()
                    }
                }

            val sessionTitlePanel =
                JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    background = DARK_BG_COLOR
                    add(sessionTitleLabel)
                }

            val historyButton =
                JButton("🕘 History").apply {
                    addActionListener { showHistoryPopup(this) }
                }

            add(sessionTitlePanel, BorderLayout.NORTH)
            add(newChatButton)
            add(historyButton)
        }
    }

    /**
     * Shows the history popup menu with available chat sessions
     */
    private fun showHistoryPopup(component: JComponent) {
        val popup = JPopupMenu()
        for (session in sessionManager.getAllSessions()) {
            val item = JMenuItem(session.title)
            item.addActionListener {
                sessionManager.switchToSession(session)
                refreshChatDisplay()
                updateSessionTitle()
            }
            popup.add(item)
        }
        popup.show(component, 0, component.height)
    }

    /**
     * Sets up the file selection button
     */
    private fun setupFileSelectionButton() {
        plusButton.apply {
            preferredSize = Dimension(28, 28)
            font = Font("SansSerif", Font.BOLD, 14)
            isFocusPainted = false
            isContentAreaFilled = true
            isOpaque = true
            background = BUTTON_BG_COLOR
            foreground = Color.WHITE
            border = roundedBorder(10)

            addActionListener { showFileSelectionPopup() }
        }
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

        popup.show(plusButton, 0, plusButton.height)
    }

    /**
     * sets up the web  button
     */
    private fun setupWebButton() {
        webButton.apply {
            isFocusPainted = false
            isContentAreaFilled = true
            isOpaque = true
            background = WEB_TOGGLE_INACTIVE
            foreground = Color.WHITE
            preferredSize = Dimension(50, 28)
            font = Font("SansSerif", Font.PLAIN, 12)
            border = roundedBorder(15)
            horizontalAlignment = SwingConstants.CENTER
        }
    }

    /**
     * Initializes some of the vent listeners
     */
    private fun initializeEventListeners() {
        sendButton.addActionListener { sendMessage() }
        inputField.addActionListener { sendMessage() }

        webButton.addActionListener {
            useWeb = !useWeb
            webButton.background = if (useWeb) WEB_TOGGLE_ACTIVE else WEB_TOGGLE_INACTIVE
        }
    }

    private fun initializeChatHistory() {
        chatHistory.clear()
        chatHistory.append("<html><body style='font-family:sans-serif;'>")
        chatHistory.append("<div style='text-align:center; font-size: 16px; margin-top: 20px; margin-bottom: 20px;'>$WELCOME_MESSAGE</div>")
        updateChatDisplay()
    }

    /**
     * Sends the message from the input field and appends, with respose
     */
    private fun sendMessage() {
        val message = inputField.text.trim()
        if (message.isEmpty()) return
        if (welcomeShown) {
            removeWelcomeMessage()
            welcomeShown = false
        }
        appendMessage(USER_NAME, message)
        inputField.text = ""
        //TODO coroutines
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

    private fun removeWelcomeMessage() {
        chatHistory.clear()
        chatHistory.append("<html><body style='font-family:sans-serif;'>")
    }

    private fun refreshChatDisplay() {
        chatHistory.clear()
        chatHistory.append("<html><body style='font-family:sans-serif;'>")
        for ((sender, message) in sessionManager.currentSession.messages) {
            val formattedMessage = formatMessageWithCodeBlocks(message)
            chatHistory.append(
                "<div style='margin-top:10px;'>" +
                    "<div style='font-weight:bold;'>$sender</div>" +
                    "<div>$formattedMessage</div>" +
                    "</div>",
            )
        }
        updateChatDisplay()
    }

    private fun updateChatDisplay() {
        chatArea.text = chatHistory.toString() + "</body></html>"
        chatArea.caretPosition = chatArea.document.length
    }

    /**
     * Formats a message to properly display code blocks
     */
    private fun formatMessageWithCodeBlocks(text: String): String {
        return text // Todo do actual formatting, previous implementation didn't work.
    }

    /**
     *  rounded borders for buttons
     */
    private fun roundedBorder(radius: Int): javax.swing.border.Border {
        return object : javax.swing.border.Border {
            override fun getBorderInsets(c: Component) = Insets(4, 10, 4, 10)

            override fun isBorderOpaque() = false

            override fun paintBorder(
                c: Component,
                g: Graphics,
                x: Int,
                y: Int,
                width: Int,
                height: Int,
            ) {
                val g2 = g as Graphics2D
                g2.color = BORDER_COLOR
                g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
            }
        }
    }
}
