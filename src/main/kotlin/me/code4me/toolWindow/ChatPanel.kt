package me.code4me.toolWindow

import com.intellij.ui.components.JBScrollPane
import java.awt.*
import java.util.UUID
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Data class representing a chat session with unique ID, title and messages.
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val messages: MutableList<Pair<String, String>> = mutableListOf(), // Pair<Sender, Message>
)

/**
 * Main chat panel that manages UI and chat interactions.
 */
class ChatPanel : JPanel(BorderLayout()) {
    // Constants
    companion object {
        private const val WELCOME_MESSAGE = "Welcome! Ask me anything."
        private const val USER_NAME = "You"
        private const val AI_NAME = "Code4Me2"

        // UI Colors
        private val DARK_BG_COLOR = Color(43, 43, 43)
        private val BUTTON_BG_COLOR = Color(75, 75, 75)
        private val WEB_TOGGLE_INACTIVE = Color(60, 63, 65)
        private val WEB_TOGGLE_ACTIVE = Color(75, 110, 175)
        private val BORDER_COLOR = Color(120, 120, 120)
    }

    // session management
    private val chatSessions = mutableListOf<ChatSession>()
    private var currentSession: ChatSession

    // ui state
    private val selectedFiles = mutableListOf<String>()
    private var welcomeShown = true
    private var useWeb = false

    // UI components
    private val chatHistory = StringBuilder()
    private val chatArea = JTextPane()
    private val inputField = JTextField()
    private val sendButton = JButton("Send")
    private val plusButton = JButton("+")
    private val webButton = JButton("Web")
    private val topPanel = JPanel(FlowLayout(FlowLayout.LEFT))

    init {
        // first session
        currentSession = createNewSession()

        setupPanelLayout()
        setupUIComponents()
        initializeEventListeners()
        initializeChatHistory()
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

        add(topPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)
    }

    /**
     * sets up the input text field and web, file selection and send buttons
     */
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
        // Chat Area
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
                        currentSession = createNewSession()
                        refreshChatDisplay()
                    }
                }

            val historyButton =
                JButton("🕘 History").apply {
                    addActionListener { showHistoryPopup(this) }
                }

            add(newChatButton)
            add(historyButton)
        }
    }

    /**
     * Shows the history popup menu with available chat sessions
     */
    private fun showHistoryPopup(component: JComponent) {
        val popup = JPopupMenu()
        for (session in chatSessions) {
            val item = JMenuItem(session.title)
            item.addActionListener {
                currentSession = session
                refreshChatDisplay()
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
            border = RoundedBorder(10)

            addActionListener { showFileSelectionPopup() }
        }
    }

    /**
     * Shows the file selection popup
     */
    private fun showFileSelectionPopup() {
        val openFiles = getOpenEditorFiles()
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
     * Sets up the web  button
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
            border = RoundedBorder(15)
            horizontalAlignment = SwingConstants.CENTER
        }
    }

    /**
     * Initializes all event listeners
     */
    private fun initializeEventListeners() {
        sendButton.addActionListener { sendMessage() }
        inputField.addActionListener { sendMessage() }

        webButton.addActionListener {
            useWeb = !useWeb
            webButton.background = if (useWeb) WEB_TOGGLE_ACTIVE else WEB_TOGGLE_INACTIVE
        }
    }

    /**
     * Creates a new chat session and adds it to the list
     */
    private fun createNewSession(): ChatSession {
        val session = ChatSession()
        chatSessions.add(session)
        return session
    }

    /**
     * Initializes the chat history with welcome message
     */
    private fun initializeChatHistory() {
        chatHistory.clear()
        chatHistory.append("<html><body style='font-family:sans-serif;'>")
        chatHistory.append("<div style='text-align:center; font-size: 16px; margin-top: 20px; margin-bottom: 20px;'>$WELCOME_MESSAGE</div>")
        updateChatDisplay()
    }

    /**
     * Returns a list of open editor files
     * TODO: Implement actual file retrieval
     */
    private fun getOpenEditorFiles(): List<String> {
        // TODO add search (all project files)
        // TODO add actual open files
        return listOf("Main.kt", "Utils.kt", "ChatPanel.kt", "AIService.kt")
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

        val aiResponse = getAIResponse(message, useWeb, selectedFiles.toList())
        appendMessage(AI_NAME, aiResponse)
    }

    /**
     * Gets AI response based on the input and context
     * TODO: Implement actual AI response logic
     */
    private fun getAIResponse(
        query: String,
        useWeb: Boolean,
        selectedFiles: List<String>,
    ): String {
        return "Query: $query | Web: $useWeb | Files: ${selectedFiles.joinToString()}"
    }

    /**
     * Appends a message to the current session and refreshes the display
     */
    private fun appendMessage(
        sender: String,
        message: String,
    ) {
        currentSession.messages.add(sender to message)
        refreshChatDisplay()
    }

    /**
     * Removes the welcome message from the chat history
     */
    private fun removeWelcomeMessage() {
        chatHistory.clear()
        chatHistory.append("<html><body style='font-family:sans-serif;'>")
    }

    /**
     * Refreshes the chat display with all messages from the current session
     */
    private fun refreshChatDisplay() {
        chatHistory.clear()
        chatHistory.append("<html><body style='font-family:sans-serif;'>")

        for ((sender, message) in currentSession.messages) {
            val formattedMessage = formatMessageWithCodeBlocks(message)
            chatHistory.append(
                "<div style='margin-top:10px;'>" +
                    "<div style='font-weight:bold;'>$sender</div>" +
                    "<div>$formattedMessage</div>" +
                    "Chat are we cooked?</div>",
            )
        }

        updateChatDisplay()
    }

    /**
     * Updates the chat area with the current chat history
     */
    private fun updateChatDisplay() {
        chatArea.text = chatHistory.toString() + "</body></html>"
        chatArea.caretPosition = chatArea.document.length
    }

    /**
     * Formats a message to properly display code blocks
     */
    private fun formatMessageWithCodeBlocks(text: String): String {
        return text //Todo do actual formatting, previous implementation didn't work.
    }

    /**
     * Custom rounded border class for buttons
     */
    class RoundedBorder(private val radius: Int) : javax.swing.border.Border {
        override fun getBorderInsets(c: Component): Insets = Insets(4, 10, 4, 10)

        override fun isBorderOpaque(): Boolean = false

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
