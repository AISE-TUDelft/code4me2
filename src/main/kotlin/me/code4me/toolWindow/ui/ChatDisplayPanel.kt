package me.code4me.toolWindow.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.Gray
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.Timer

class ChatDisplayPanel(private val project: Project) : JBPanel<ChatDisplayPanel>(BorderLayout()) {
    private val contentPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Gray._43
            alignmentX = Component.LEFT_ALIGNMENT
        }

    private val wrapperPanel =
        JPanel(BorderLayout()).apply {
            background = Gray._43
            add(contentPanel, BorderLayout.NORTH) // use NORTH to allow vertical expansion
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

    fun updateContent(messages: List<Pair<String, String>>) {
        val shouldAutoScroll = !userScrolledUp || isAtBottom()

        isUpdatingContent = true
        contentPanel.removeAll()

        if (messages.isEmpty()) {
            contentPanel.add(makeCenteredLabel("Welcome! Ask me anything."))
        } else {
            for ((sender, message) in messages) {
                val isUser = sender == "You"
                val bubble =
                    ChatBubble(sender, message, isUser, project).apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    }
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
                scrollToBottom(false) // Don't reset user scroll flag
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    private fun makeCenteredLabel(text: String): JPanel {
        val label = JLabel(text)
        label.foreground = Color(220, 220, 220)
        label.font = Font("SansSerif", Font.ITALIC, 16)

        val wrapper =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                background = Gray._43
                border = JBUI.Borders.empty(30, 10, 10, 10)
                add(Box.createHorizontalGlue())
                add(label)
                add(Box.createHorizontalGlue())
            }

        return wrapper
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
        userScrolledUp = false // User action resets the scroll state
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
}
