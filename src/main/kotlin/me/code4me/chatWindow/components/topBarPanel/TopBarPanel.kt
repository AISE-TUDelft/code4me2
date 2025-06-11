package me.code4me.chatWindow.components.topBarPanel

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import me.code4me.chatWindow.components.inputPanel.components.IconButton
import me.code4me.chatWindow.components.managers.ChatSessionManager
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JLabel
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
) : JBPanel<TopBarPanel>(BorderLayout()) {
    private val sessionTitleLabel =
        JLabel().apply {
            font = Font("SansSerif", Font.BOLD, 16)
            foreground = JBColor.foreground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click to rename"
        }

    private val sessionTitleField = JTextField()
    private var isEditingTitle = false

    private var rightPanel: JBPanel<*>

    init {
        background = JBColor.PanelBackground
        border = JBUI.Borders.empty(5)

        val leftPanel =
            JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                isOpaque = false
                add(createHistoryButton())
                add(createNewChatButton())
            }

        rightPanel =
            JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                isOpaque = false
                add(sessionTitleLabel)
            }

        add(leftPanel, BorderLayout.EAST)
        add(rightPanel, BorderLayout.WEST)

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
        rightPanel.remove(sessionTitleLabel)
        rightPanel.add(sessionTitleField)
        sessionTitleField.requestFocus()
        sessionTitleField.selectAll()
        rightPanel.revalidate()
        rightPanel.repaint()
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
        rightPanel.remove(sessionTitleField)
        rightPanel.add(sessionTitleLabel)
        updateTitle()
        rightPanel.revalidate()
        rightPanel.repaint()
    }

    /**
     * Builds and returns the "New Chat" button.
     * When clicked, switches to or creates a session titled "New Chat".
     *
     * @return A JButton configured for creating a new chat.
     */
    private fun createNewChatButton(): IconButton {
        val newChatIcon: Icon = AllIcons.General.Add
        return IconButton(newChatIcon, "New Chat") {
            // Always create a new chat session
            sessionManager.createNewSession("New Chat")

            onNewChatCreated()
            updateTitle()
            onSessionSwitched()
        }
    }

    /**
     * Builds and returns the "History" button.
     * When clicked, invokes the provided onHistoryClicked callback.
     *
     * @return A JButton configured to show history.
     */
    private fun createHistoryButton(): IconButton {
        val historyIcon: Icon = AllIcons.General.History
        return IconButton(historyIcon, "View History") {
            onHistoryClicked()
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
