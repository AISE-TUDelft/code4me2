package me.code4me.toolWindow.chatPanelUI

import com.intellij.ui.components.JBScrollPane
import me.code4me.toolWindow.utils.ChatMessageRenderer
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

class ChatDisplayPanel : JPanel(BorderLayout()) {
    private val chatArea =
        JTextPane().apply {
            contentType = "text/html"
            isEditable = false
        }

    init {
        border = EmptyBorder(0, 0, 0, 0)
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
