package me.code4me.toolWindow.chatPanelUI

import me.code4me.toolWindow.managers.ChatSessionManager
import java.awt.*
import javax.swing.*

class TopBarPanel(
    private val sessionManager: ChatSessionManager,
    private val onSessionSwitched: () -> Unit,
    private val onNewChatCreated: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT)) {
    private val sessionTitleLabel = JLabel()

    init {
        background = Color(43, 43, 43)
        font = Font("SansSerif", Font.BOLD, 16)

        val titlePanel =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                background = background
                add(sessionTitleLabel)
            }

        val newChatButton =
            JButton("＋ New Chat").apply {
                addActionListener {
                    val title =
                        JOptionPane.showInputDialog(
                            this@TopBarPanel,
                            "Enter a name for the new chat:",
                            "New Chat",
                            JOptionPane.PLAIN_MESSAGE,
                        )
                    sessionManager.createNewSession(title)
                    onNewChatCreated()
                    updateTitle()
                    onSessionSwitched()
                }
            }

        val historyButton =
            JButton("🕘 History").apply {
                addActionListener { showHistoryPopup(this) }
            }

        add(titlePanel)
        add(newChatButton)
        add(historyButton)

        updateTitle()
    }

    fun updateTitle() {
        sessionTitleLabel.text = sessionManager.currentSession.title
        sessionTitleLabel.foreground = Color.WHITE
    }

    private fun showHistoryPopup(component: JComponent) {
        val popup = JPopupMenu()
        for (session in sessionManager.getAllSessions()) {
            val item = JMenuItem(session.title)
            item.addActionListener {
                sessionManager.switchToSession(session)
                updateTitle()
                onSessionSwitched()
            }
            popup.add(item)
        }
        popup.show(component, 0, component.height)
    }
}
