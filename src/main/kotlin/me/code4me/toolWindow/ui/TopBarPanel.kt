package me.code4me.toolWindow.chatPanelUI

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import me.code4me.toolWindow.managers.ChatSessionManager
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane

/**
 * Represents the top bar panel in the chat UI, providing controls for creating new chats,
 * switching sessions, and accessing chat history.
 *
 * @param sessionManager The manager responsible for handling chat sessions.
 * @param onSessionSwitched A callback invoked when the session is switched.
 * @param onNewChatCreated A callback invoked when a new chat is created.
 * @param onHistoryClicked A callback invoked when the history button is clicked.
 */
class TopBarPanel(
    private val sessionManager: ChatSessionManager,
    private val onSessionSwitched: () -> Unit,
    private val onNewChatCreated: () -> Unit,
    private val onHistoryClicked: () -> Unit,
) : JBPanel<TopBarPanel>(FlowLayout(FlowLayout.LEFT)) {
    private val sessionTitleLabel =
        JLabel().apply {
            font = Font("SansSerif", Font.BOLD, 16)
            foreground = JBColor.foreground()
        }

    private val standardButtonHeight = 28
    private val standardButtonWidth = 110

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
            preferredSize = Dimension(standardButtonWidth, standardButtonHeight)
            margin = Insets(0, 8, 0, 8)
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
            preferredSize = Dimension(standardButtonWidth, standardButtonHeight)
            margin = Insets(0, 8, 0, 8)
            addActionListener {
                onHistoryClicked()
            }
        }
    }

    fun updateTitle() {
        sessionTitleLabel.text = sessionManager.currentSession.title
    }
}
