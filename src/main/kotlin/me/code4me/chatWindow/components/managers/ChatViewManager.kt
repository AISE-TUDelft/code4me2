package me.code4me.chatWindow.components.managers

import java.awt.CardLayout
import javax.swing.JComponent
import javax.swing.JPanel

class ChatViewManager(
    private val chatViewName: String = "chat",
    private val historyViewName: String = "history",
) {
    private val cardPanel = JPanel(CardLayout())

    fun getContainer(): JPanel = cardPanel

    fun setViews(
        chatView: JComponent,
        historyView: JComponent,
    ) {
        cardPanel.add(chatView, chatViewName)
        cardPanel.add(historyView, historyViewName)
    }

    fun showChatPanel() {
        (cardPanel.layout as CardLayout).show(cardPanel, chatViewName)
    }

    fun showHistoryPanel() {
        (cardPanel.layout as CardLayout).show(cardPanel, historyViewName)
    }
}
