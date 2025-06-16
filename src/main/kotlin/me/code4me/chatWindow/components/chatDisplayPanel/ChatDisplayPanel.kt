package me.code4me.chatWindow.components.chatDisplayPanel

import com.intellij.openapi.project.Project
import com.intellij.ui.Gray
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import me.code4me.chatWindow.components.chatDisplayPanel.components.ChatBubble
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.OverlayLayout
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

class ChatDisplayPanel(private val project: Project) : JBPanel<ChatDisplayPanel>(BorderLayout()) {
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

    private val editOverlayPanel =
        object : JPanel(BorderLayout()) {
            override fun contains(
                x: Int,
                y: Int,
            ): Boolean = true

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.color = Color(0, 0, 0, 120) // 50% opacity black
                g2.fillRect(0, 0, width, height)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            isVisible = false

            val label = JLabel("Exit edit mode to view chat", JLabel.CENTER)
            label.foreground = Color.WHITE
            label.font = Font("SansSerif", Font.BOLD, 16)
            add(label, BorderLayout.CENTER)

            addMouseListener(
                object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent?) {}
                },
            )
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

    var onRegenerateFromIndex: ((Int) -> Unit)? = null
    var onEditUserMessage: ((index: Int, text: String) -> Unit)? = null

    /** Callback invoked when the panel is restored (e.g., minimized → maximized). */
    var onRestore: (() -> Unit)? = null

    init {
        val layeredWrapper =
            JPanel().apply {
                layout = OverlayLayout(this)
                isOpaque = false
                background = Color(0, 0, 0, 0) // fully transparent
                alignmentX = LEFT_ALIGNMENT
                alignmentY = TOP_ALIGNMENT
            }

// Set both to fill parent
        scrollPane.alignmentX = LEFT_ALIGNMENT
        scrollPane.alignmentY = TOP_ALIGNMENT

        editOverlayPanel.alignmentX = LEFT_ALIGNMENT
        editOverlayPanel.alignmentY = TOP_ALIGNMENT

// Force overlay panel to match scrollPane size later
        editOverlayPanel.addComponentListener(
            object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent?) {
                    editOverlayPanel.preferredSize = scrollPane.size
                    editOverlayPanel.revalidate()
                    editOverlayPanel.repaint()
                }
            },
        )

        layeredWrapper.add(editOverlayPanel) // overlay added last = on top
        layeredWrapper.add(scrollPane)

        add(layeredWrapper, BorderLayout.CENTER)
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
                        onEdit =
                            if (isUser) {
                                { onEditUserMessage?.invoke(index, message) }
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
        onRestore?.invoke()
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

        editOverlayPanel.setBounds(0, 0, scrollPane.width, scrollPane.height)
        editOverlayPanel.revalidate()
        editOverlayPanel.repaint()
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

    fun showEditOverlay() {
        editOverlayPanel.isVisible = true
        editOverlayPanel.revalidate()
        editOverlayPanel.repaint()
    }

    fun hideEditOverlay() {
        editOverlayPanel.isVisible = false
        editOverlayPanel.revalidate()
        editOverlayPanel.repaint()
    }
}
