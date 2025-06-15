package me.code4me.chatWindow.components.chatDisplayPanel

import com.intellij.openapi.project.Project
import com.intellij.ui.Gray
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import me.code4me.chatWindow.components.chatDisplayPanel.components.ChatBubble
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

class ChatDisplayPanel(private val project: Project) : JBPanel<ChatDisplayPanel>(BorderLayout()) {
    var onRegenerateFromIndex: ((Int) -> Unit)? = null
    private val contentPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Gray._43
            alignmentX = LEFT_ALIGNMENT
        }

    private val wrapperPanel =
        JPanel(BorderLayout()).apply {
            background = Gray._43
            add(contentPanel, BorderLayout.NORTH)
        }

    private val scrollPane =
        JBScrollPane(wrapperPanel).apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = null
            background = Gray._43
            viewport.background = Gray._43
        }

    private var userScrolledUp = false
    private var isUpdatingContent = false
    private val activeBubbles = mutableListOf<ChatBubble>()

    /** Callback invoked when the panel is restored (e.g., minimized → maximized). */
    var onRestore: (() -> Unit)? = null

    init {
        background = Gray._43
        border = null
        add(scrollPane, BorderLayout.CENTER)

        scrollPane.verticalScrollBar.addAdjustmentListener { e ->
            // Only track user scrolling when we're not programmatically updating content
            if (!isUpdatingContent && !e.valueIsAdjusting) {
                val scrollBar = scrollPane.verticalScrollBar
                val maxValue = scrollBar.maximum - scrollBar.visibleAmount
                userScrolledUp = scrollBar.value < maxValue - 10
            }
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        cleanupBubbles()
    }

    private fun cleanupBubbles() {
        activeBubbles.forEach { bubble ->
            try {
                bubble.disposeEditors()
            } catch (e: Exception) {
                println("Error disposing bubble: ${e.message}")
            }
        }
        activeBubbles.clear()
    }

    fun updateContent(messages: List<Pair<String, String>>) {
        val shouldAutoScroll = !userScrolledUp || isAtBottom()

        isUpdatingContent = true
        cleanupBubbles()
        contentPanel.removeAll()

        if (messages.isEmpty()) {
            contentPanel.add(makeCenteredLabel("Welcome! Ask me anything."))
        } else {
            for ((index, pair) in messages.withIndex()) {
                val (sender, message) = pair
                val isUser = sender == "You"
                val bubble =
                    ChatBubble(
                        sender,
                        message,
                        isUser,
                        project,
                        onRegenerate =
                            if (!isUser) {
                                { onRegenerateFromIndex?.invoke(index) }
                            } else {
                                null
                            },
                        showRegenerate = !message.startsWith("Generating"),
                    ).apply {
                        alignmentX = LEFT_ALIGNMENT
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    }
                activeBubbles.add(bubble)
                contentPanel.add(bubble)
                contentPanel.add(Box.createVerticalStrut(0))
            }
            contentPanel.add(Box.createVerticalStrut(50))
        }

        contentPanel.revalidate()
        contentPanel.repaint()
        wrapperPanel.revalidate()
        wrapperPanel.repaint()
        scrollPane.revalidate()
        scrollPane.repaint()

        isUpdatingContent = false

        if (shouldAutoScroll) {
            Timer(50) {
                scrollToBottom(false)
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    fun forceRefresh() {
        SwingUtilities.invokeLater {
            for (component in contentPanel.components) {
                if (component is ChatBubble) {
                    component.revalidate()
                    component.repaint()
                    component.forceResetEditorColors()
                }
            }
            contentPanel.revalidate()
            contentPanel.repaint()
            scrollPane.revalidate()
            scrollPane.repaint()
        }
    }

    private fun makeCenteredLabel(text: String): JPanel {
        val label = JLabel(text)
        label.foreground = Gray._220
        label.font = Font("SansSerif", Font.ITALIC, 16)

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(30, 10, 10, 10)
            add(Box.createHorizontalGlue())
            add(label)
            add(Box.createHorizontalGlue())
        }
    }

    override fun addNotify() {
        super.addNotify()
        onRestore?.invoke() // 👈 key line added here
    }

    private fun isAtBottom(): Boolean {
        val scrollBar = scrollPane.verticalScrollBar
        return scrollBar.value >= scrollBar.maximum - scrollBar.visibleAmount - 10
    }

    private fun scrollToBottom(resetUserScrollFlag: Boolean = true) {
        SwingUtilities.invokeLater {
            isUpdatingContent = true
            val scrollBar = scrollPane.verticalScrollBar
            scrollBar.value = scrollBar.maximum
            if (resetUserScrollFlag) {
                userScrolledUp = false
            }
            isUpdatingContent = false
        }
    }

    // New method for when user explicitly sends a message
    fun scrollToBottomOnUserAction() {
        userScrolledUp = false
        Timer(50) {
            scrollToBottom(true)
        }.apply {
            isRepeats = false
            start()
        }
    }

    override fun doLayout() {
        super.doLayout()
        contentPanel.revalidate()
        contentPanel.repaint()
        scrollPane.revalidate()
        scrollPane.repaint()
    }

    fun updateLastBubbleText(newText: String) {
        if (activeBubbles.isNotEmpty()) {
            activeBubbles.last().updateMessageTextOnly(newText)
        }
    }
}
