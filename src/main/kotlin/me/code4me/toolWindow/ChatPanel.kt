package me.code4me.toolWindow

import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

class ChatPanel : JPanel(BorderLayout()) {
    private var welcomeShown = true

    private val chatHistory =
        StringBuilder(
            "<html><body style='font-family:sans-serif;'>" +
                "<div style='text-align:center; font-size: 16px; margin-top: 20px; margin-bottom: 20px;'>Welcome! Ask me anything.</div>",
        )

    private val chatArea =
        JTextPane().apply {
            contentType = "text/html"
            isEditable = false
            text = chatHistory.toString() + "</body></html>"
        }

    private val inputField = JTextField()
    private val sendButton = JButton("Send")

    init {
        border = EmptyBorder(10, 10, 10, 10)

        val scrollPane = JScrollPane(chatArea)
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED

        val inputPanel =
            JPanel(BorderLayout()).apply {
                add(inputField, BorderLayout.CENTER)
                add(sendButton, BorderLayout.EAST)
            }

        add(scrollPane, BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)

        sendButton.addActionListener { sendMessage() }
        inputField.addActionListener { sendMessage() }
    }

    private fun sendMessage() {
        val message = inputField.text.trim()
        if (message.isEmpty()) return
        if (welcomeShown) {
            removeWelcomeMessage()
            welcomeShown = false
        }
        appendMessage("You", message)
        inputField.text = ""

        // TODO: replace with coroutine call
        val aiResponse = getAIResponse(message)
        appendMessage("Code4Me2", aiResponse)
    }

    private fun appendMessage(
        sender: String,
        message: String,
    ) {
        val formattedMessage = formatMessageWithCodeBlocks(message)
        chatHistory.append(
            "<div style='margin-top:10px;'>" +
                "<div style='font-weight:bold;'>$sender</div>" +
                "<div>$formattedMessage</div>" +
                "</div>",
        )
        chatArea.text = chatHistory.toString() + "</body></html>"
        chatArea.caretPosition = chatArea.document.length
    }

    private fun getAIResponse(query: String): String {
        // TODO: get AI response
        return query
    }

    private fun formatMessageWithCodeBlocks(text: String): String {
        val codeBlockPattern = Regex("```(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val parts = mutableListOf<String>()
        var lastIndex = 0

        codeBlockPattern.findAll(text).forEach { match ->
            // Add normal text before the code block
            if (match.range.first > lastIndex) {
                val normalText = text.substring(lastIndex, match.range.first)
                parts.add(escapeHtml(normalText).replace("\n", "<br/>"))
            }

            // Add code block
            val code =
                match.groupValues[1]
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
            parts.add("<pre>$code</pre>")

            lastIndex = match.range.last + 1
        }

        // Add remaining normal text
        if (lastIndex < text.length) {
            val remaining = text.substring(lastIndex)
            parts.add(escapeHtml(remaining).replace("\n", "<br/>"))
        }

        return parts.joinToString("")
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun removeWelcomeMessage() {
        chatHistory.clear()
        chatHistory.append("<html><body style='font-family:sans-serif;'>")
    }
}
