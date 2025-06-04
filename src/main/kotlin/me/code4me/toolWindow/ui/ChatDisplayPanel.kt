package me.code4me.toolWindow.ui

import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import me.code4me.toolWindow.utils.ChatMessageRenderer
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.border.EmptyBorder

/**
 * Panel responsible for rendering the chat conversation display.
 *
 * Displays both user and assistant messages in a vertical scrollable layout.
 */
class ChatDisplayPanel : JBPanel<ChatDisplayPanel>(BorderLayout()) {
    private val chatArea =
        JTextPane().apply {
            contentType = "text/html"
            isEditable = false
        }

    init {
        border = EmptyBorder(0, 5, 0, 2)
        background = Color(43, 43, 43)

        val scrollPane =
            JBScrollPane(chatArea).apply {
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            }

        add(scrollPane, BorderLayout.CENTER)
    }

    fun updateContent(
        messages: List<Pair<String, String>>,
        renderer: ChatMessageRenderer,
    ) {
        chatArea.text = renderer.render(messages)
        chatArea.caretPosition = chatArea.document.length
    }
}
