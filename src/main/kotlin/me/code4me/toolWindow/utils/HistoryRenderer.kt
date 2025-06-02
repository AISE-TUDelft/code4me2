package me.code4me.toolWindow.utils

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import me.code4me.toolWindow.managers.ChatSession
import java.awt.*
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder

class HistoryRenderer(
    private val onClick: (ChatSession) -> Unit,
) {
    fun createTimeSection(
        title: String,
        sessions: List<ChatSession>,
    ): List<JComponent> {
        val components = mutableListOf<JComponent>()

        val header =
            JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = EmptyBorder(12, 0, 8, 0)
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

    private fun createSessionItem(session: ChatSession): JComponent {
        val itemHeight = 56

        val panel =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                border = EmptyBorder(8, 16, 8, 16)
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

        panel.add(content, BorderLayout.CENTER)

        panel.addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent) {
                    panel.background = JBColor(Color(230, 230, 230), Color(75, 75, 75))
                    panel.isOpaque = true
                    panel.repaint()
                }

                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    panel.isOpaque = false
                    panel.repaint()
                }

                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    onClick(session)
                }
            },
        )

        return panel
    }

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
