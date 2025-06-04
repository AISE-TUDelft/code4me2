package me.code4me.toolWindow.chatPanelUI

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import me.code4me.toolWindow.managers.ChatSessionManager
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JTextField

/**
 * Represents the top bar in the chat UI, showing the session title and controls
 * for creating new chats, viewing history, and renaming the session.
 *
 * @param sessionManager Manages chat sessions and their state.
 * @param onSessionSwitched Callback invoked when a new session is selected.
 * @param onNewChatCreated Callback invoked after a new session is created.
 * @param onHistoryClicked Callback invoked when the history button is clicked.
 * @param onTitleRenamed Callback invoked after the session title is renamed.
 */

class TopBarPanel(
    private val sessionManager: ChatSessionManager,
    private val onSessionSwitched: () -> Unit,
    private val onNewChatCreated: () -> Unit,
    private val onHistoryClicked: () -> Unit,
    private val onTitleRenamed: () -> Unit = {},
) : JBPanel<TopBarPanel>(FlowLayout(FlowLayout.LEFT)) {
    private val sessionTitleLabel =
        JLabel().apply {
            font = Font("SansSerif", Font.BOLD, 16)
            foreground = JBColor.foreground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click to rename"
        }

    private val sessionTitleField = JTextField()
    private var isEditingTitle = false

    private val standardButtonHeight = 28
    private val standardButtonWidth = 110

    init {
        background = JBColor.PanelBackground
        border = JBUI.Borders.empty(5)

        add(sessionTitleLabel)
        add(createNewChatButton())
        add(createHistoryButton())

        updateTitle()
        setupEditBehavior()
    }

    /**
     * Configures event listeners that enable in-place editing of the session title
     * via label click, Enter key, or focus loss.
     */
    private fun setupEditBehavior() {
        sessionTitleLabel.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    if (e?.clickCount == 1) {
                        enterEditMode()
                    }
                }
            },
        )

        sessionTitleField.addActionListener {
            exitEditMode(save = true)
        }

        sessionTitleField.addFocusListener(
            object : FocusAdapter() {
                override fun focusLost(e: FocusEvent?) {
                    exitEditMode(save = true)
                }
            },
        )

        sessionTitleField.font = sessionTitleLabel.font
        sessionTitleField.foreground = sessionTitleLabel.foreground
    }

    /**
     * Replaces the label with a text field to allow renaming the session title.
     */
    private fun enterEditMode() {
        isEditingTitle = true
        sessionTitleField.text = sessionTitleLabel.text
        remove(sessionTitleLabel)
        add(sessionTitleField, 0)
        sessionTitleField.requestFocus()
        sessionTitleField.selectAll()
        revalidate()
        repaint()
    }

    /**
     * Exits title editing mode and optionally updates the session title.
     *
     * @param save Whether to commit the new title. If false, reverts to the previous title.
     */
    private fun exitEditMode(save: Boolean = true) {
        if (save) {
            val newTitle = sessionTitleField.text.trim()
            if (newTitle.isNotEmpty()) {
                sessionManager.currentSession.title = newTitle
                onTitleRenamed()
            }
        }

        isEditingTitle = false
        remove(sessionTitleField)
        add(sessionTitleLabel, 0)
        updateTitle()
        revalidate()
        repaint()
    }

    /**
     * Builds and returns the "New Chat" button.
     * When clicked, prompts the user for a title, creates a new session, and updates the UI.
     *
     * @return A JButton configured for creating a new chat.
     */
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

    /**
     * Builds and returns the "History" button.
     * When clicked, invokes the provided onHistoryClicked callback.
     *
     * @return A JButton configured to show history.
     */
    private fun createHistoryButton(): JButton {
        return JButton("🕘 History").apply {
            preferredSize = Dimension(standardButtonWidth, standardButtonHeight)
            margin = Insets(0, 8, 0, 8)
            addActionListener {
                onHistoryClicked()
            }
        }
    }

    /**
     * Updates the session title label with the current session’s title
     */
    fun updateTitle() {
        if (!isEditingTitle) {
            sessionTitleLabel.text = sessionManager.currentSession.title
        }
    }
}
