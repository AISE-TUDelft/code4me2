package me.code4me.toolWindow.chatPanelUI

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import me.code4me.toolWindow.managers.ChatSessionManager
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPopupMenu

class TopBarPanel(
    private val sessionManager: ChatSessionManager,
    private val onSessionSwitched: () -> Unit,
    private val onNewChatCreated: () -> Unit,
) : JBPanel<TopBarPanel>(FlowLayout(FlowLayout.LEFT)) {
    private val sessionTitleLabel =
        JLabel().apply {
            font = Font("SansSerif", Font.BOLD, 16)
            foreground = JBColor.foreground()
        }

    init {
        background = JBColor.PanelBackground
        border = JBUI.Borders.empty(5)
        add(sessionTitleLabel)
        add(createNewChatButton())
        add(createHistoryButton())

        updateTitle()
    }

    private fun createNewChatButton(): JButton {
        return JButton("＋ New Chat").apply {
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
    }

    private fun createHistoryButton(): JButton {
        return JButton("🕘 History").apply {
            addActionListener { showHistoryPopup(this) }
        }
    }

    fun updateTitle() {
        sessionTitleLabel.text = sessionManager.currentSession.title
    }

    private fun showHistoryPopup(component: JComponent) {
        val popup = JPopupMenu()
        sessionManager.getAllSessions().forEach { session ->
            JMenuItem(session.title).apply {
                addActionListener {
                    sessionManager.switchToSession(session)
                    updateTitle()
                    onSessionSwitched()
                }
                popup.add(this)
            }
        }
        popup.show(component, 0, component.height)
    }
}
