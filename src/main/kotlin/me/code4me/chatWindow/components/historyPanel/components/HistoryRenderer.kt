package me.code4me.chatWindow.components.historyPanel.components

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import me.code4me.chatWindow.components.managers.ChatSession
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JSeparator

/**
 * Renderer for creating UI components that display chat session history.
 *
 * Creates time-grouped sections of chat sessions with interactive elements
 * for selecting and deleting sessions. Each session item shows the title,
 * relative timestamp, and optional delete button with hover effects.
 *
 * @param onClick Callback invoked when a session item is clicked
 * @param onDelete Optional callback for deleting sessions via trash icon
 */
class HistoryRenderer(
    private val onClick: (ChatSession) -> Unit,
    private val onDelete: ((ChatSession) -> Unit)? = null,
) {

    /**
     * Creates a time-grouped section of chat sessions with a header and separator.
     *
     * Builds a list of UI components starting with a styled section header,
     * followed by individual session items, and ending with spacing.
     *
     * @param title The section title (e.g., "Today", "Yesterday", "Last 7 days")
     * @param sessions List of chat sessions to display in this section
     * @return List of JComponent elements ready for addition to a container
     */
    fun createTimeSection(
        title: String,
        sessions: List<ChatSession>,
    ): List<JComponent> {
        val components = mutableListOf<JComponent>()

        val header =
            JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(12, 0, 8, 0)
                maximumSize = Dimension(Int.MAX_VALUE, 32)
            }

        header.add(
            JLabel(title).apply {
                font = Font("SansSerif", Font.BOLD, 14)
                foreground = JBColor(Color.BLACK, Color.WHITE)
                alignmentX = Component.LEFT_ALIGNMENT
            },
        )

        header.add(Box.createVerticalStrut(4) as JComponent)
        header.add(
            JSeparator().apply {
                maximumSize = Dimension(Int.MAX_VALUE, 1)
                foreground = JBColor(Color.LIGHT_GRAY, Color.DARK_GRAY)
                background = JBColor(Color.LIGHT_GRAY, Color.DARK_GRAY)
                alignmentX = Component.LEFT_ALIGNMENT
            },
        )

        components.add(header)
        sessions.forEach { session -> components.add(createSessionItem(session)) }
        components.add(Box.createVerticalStrut(8) as JComponent)

        return components
    }

    /**
     * Creates an interactive UI item for a single chat session.
     *
     * Builds a panel containing the session title, relative timestamp,
     * and delete button. Includes hover effects and click handling that
     * distinguishes between session selection and deletion actions.
     *
     * @param session The chat session to create an item for
     * @return JComponent representing the session item with all interactions
     */
    private fun createSessionItem(session: ChatSession): JComponent {
        val itemHeight = 56

        val panel =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(8, 16)
                preferredSize = Dimension(Int.MAX_VALUE, itemHeight)
                maximumSize = Dimension(Int.MAX_VALUE, itemHeight)
                minimumSize = Dimension(Int.MAX_VALUE, itemHeight)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentX = Component.LEFT_ALIGNMENT
            }

        val content =
            JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        content.add(
            JLabel(session.title).apply {
                font = Font("SansSerif", Font.PLAIN, 13)
                foreground = JBColor(Color.BLACK, Color.WHITE)
                alignmentX = Component.LEFT_ALIGNMENT
            },
        )

        content.add(Box.createVerticalStrut(2))

        content.add(
            JLabel(formatRelativeTime(session.lastUpdated)).apply {
                font = Font("SansSerif", Font.PLAIN, 11)
                foreground = JBColor(Color.DARK_GRAY, Color.LIGHT_GRAY)
                alignmentX = Component.LEFT_ALIGNMENT
            },
        )

        val deleteButton =
            JButton(IconLoader.getIcon("/icons/trashcan.svg", HistoryRenderer::class.java)).apply {
                isContentAreaFilled = false
                border = null
                toolTipText = "Delete session"
                isFocusable = false
                addActionListener {
                    onDelete?.invoke(session)
                }
            }

        val rightSide =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                add(deleteButton, BorderLayout.EAST)
            }

        val centerWithDelete =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                add(content, BorderLayout.CENTER)
                add(rightSide, BorderLayout.EAST)
            }

        panel.add(centerWithDelete, BorderLayout.CENTER)

        // Preserve hover effect
        panel.addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    panel.background = JBColor(Gray._230, Gray._75)
                    panel.isOpaque = true
                    panel.repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    panel.isOpaque = false
                    panel.repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (!deleteButton.bounds.contains(e.point)) {
                        onClick(session)
                    }
                }
            },
        )

        return panel
    }

    /**
     * Formats a date as a human-readable relative time string.
     *
     * Converts absolute timestamps into user-friendly relative descriptions
     * such as "5m ago", "2h ago", "Yesterday 14:30", or "Jan 15" depending
     * on how recent the date is compared to the current time.
     *
     * @param date The date to format
     * @return Human-readable relative time string
     */
    private fun formatRelativeTime(date: Date): String {
        val now = LocalDateTime.now()
        val sessionTime = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()

        val daysBetween = ChronoUnit.DAYS.between(sessionTime.toLocalDate(), now.toLocalDate())
        val hoursBetween = ChronoUnit.HOURS.between(sessionTime, now)
        val minutesBetween = ChronoUnit.MINUTES.between(sessionTime, now)

        return when {
            daysBetween == 0L ->
                when {
                    hoursBetween == 0L -> "${minutesBetween}m ago"
                    hoursBetween < 24 -> "${hoursBetween}h ago"
                    else -> SimpleDateFormat("HH:mm").format(date)
                }

            daysBetween == 1L -> "Yesterday ${SimpleDateFormat("HH:mm").format(date)}"
            daysBetween < 7 -> SimpleDateFormat("EEE HH:mm").format(date)
            else -> SimpleDateFormat("MMM dd").format(date)
        }
    }
}