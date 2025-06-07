package me.code4me.toolWindow.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import me.code4me.toolWindow.managers.ChatSession
import me.code4me.toolWindow.managers.ChatSessionManager
import me.code4me.toolWindow.ui.components.IconButton
import me.code4me.toolWindow.utils.HistoryRenderer
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.time.LocalDateTime
import java.time.ZoneId
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.SwingConstants

/**
 * Represents the panel that displays the history of chat sessions.
 *
 * @param sessionManager The manager responsible for handling chat sessions.
 * @param onSessionSelected A callback invoked when a session is selected.
 */
class HistoryPanel(
    private val sessionManager: ChatSessionManager,
    private val onSessionSelected: () -> Unit,
) : JBPanel<HistoryPanel>(BorderLayout()) {
    private val scrollPane: JBScrollPane
    private val contentPanel: JBPanel<*>
    private val renderer =
        HistoryRenderer(
            onClick = { session ->
                try {
                    // Ensure we're switching to a valid session
                    if (sessionManager.getAllSessions().contains(session)) {
                        sessionManager.switchToSession(session)
                        // Invoke callback after successful switch
                        onSessionSelected()
                    } else {
                        // Session might have been deleted, refresh the panel
                        refresh()
                    }
                } catch (e: Exception) {
                    println("Error switching to session: ${e.message}")
                    e.printStackTrace()
                    // Refresh panel in case of error
                    refresh()
                }
            },
            onDelete = { session ->
                try {
                    sessionManager.deleteSession(session)
                    refresh()
                } catch (e: Exception) {
                    println("Error deleting session: ${e.message}")
                    e.printStackTrace()
                    refresh()
                }
            },
        )

    init {
        border = JBUI.Borders.empty(12)
        isOpaque = false

        val headerPanel =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(16)

                add(
                    JLabel("Chat History").apply {
                        font = Font("SansSerif", Font.BOLD, 18)
                        foreground = JBColor(Color.BLACK, Color.WHITE)
                    },
                    BorderLayout.WEST,
                )

                val buttonPanel =
                    JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                        isOpaque = false

                        add(
                            JButton("Delete All").apply {
                                isContentAreaFilled = false
                                isBorderPainted = false
                                foreground = JBColor.RED
                                font = Font("SansSerif", Font.PLAIN, 12)
                                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                toolTipText = "Delete all chat sessions"

                                addActionListener {
                                    val visibleSessions = getVisibleSessions()

                                    if (visibleSessions.isNotEmpty()) {
                                        val confirm =
                                            JOptionPane.showConfirmDialog(
                                                this,
                                                "Are you sure you want to delete all chat sessions?",
                                                "Confirm Deletion",
                                                JOptionPane.YES_NO_OPTION,
                                                JOptionPane.WARNING_MESSAGE,
                                            )
                                        if (confirm == JOptionPane.YES_OPTION) {
                                            // Delete all sessions except create a new one
                                            val allSessions = sessionManager.getAllSessions().toList()
                                            allSessions.forEach { sessionManager.deleteSession(it) }
                                            // This will automatically create a new session if none exist
                                            refresh()
                                        }
                                    }
                                }
                            },
                        )

                        add(
                            IconButton(AllIcons.General.Add, "New Chat") {
                                // Always create a new session instead of trying to reuse
                                sessionManager.createNewSession("New Chat")
                                onSessionSelected()
                            },
                        )
                    }

                add(buttonPanel, BorderLayout.EAST)
            }

        add(headerPanel, BorderLayout.NORTH)

        contentPanel =
            JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        scrollPane =
            JBScrollPane(contentPanel).apply {
                border = null
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                isOpaque = false
                viewport.isOpaque = false
            }

        add(scrollPane, BorderLayout.CENTER)
        refresh()
    }

    /**
     * Gets sessions that should be visible in the history panel.
     * Filters out empty "New Chat" sessions.
     */
    private fun getVisibleSessions(): List<ChatSession> {
        return sessionManager.getAllSessions().filter { session ->
            session.messages.size > 1 || session.title != "New Chat"
        }
    }

    /**
     * Refreshes the history panel content by grouping (by date) and displaying chat sessions.
     */
    fun refresh() {
        try {
            contentPanel.removeAll()

            val sessions = getVisibleSessions()

            if (sessions.isEmpty()) {
                contentPanel.add(
                    JLabel("No chat sessions yet").apply {
                        font = Font("SansSerif", Font.ITALIC, 14)
                        foreground = JBColor(Color.GRAY, Color.LIGHT_GRAY)
                        border = JBUI.Borders.empty(20, 16)
                        horizontalAlignment = SwingConstants.CENTER
                    },
                )
            } else {
                val groupedSessions = groupSessionsByTime(sessions)
                listOf("Today", "Yesterday", "Last Week", "Older").forEach { period ->
                    val sessionsInPeriod = groupedSessions[period] ?: emptyList()
                    if (sessionsInPeriod.isNotEmpty()) {
                        renderer.createTimeSection(period, sessionsInPeriod).forEach(contentPanel::add)
                    }
                }
            }

            contentPanel.add(Box.createVerticalStrut(20))
            contentPanel.revalidate()
            contentPanel.repaint()
        } catch (e: Exception) {
            println("Error refreshing history panel: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Groups chat sessions by time periods: Today, Yesterday, Last Week, and Older.
     *
     * @param sessions The list of chat sessions to group.
     * @return A map where the keys are time periods and the values are lists of chat sessions.
     */
    private fun groupSessionsByTime(sessions: List<ChatSession>): Map<String, List<ChatSession>> {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val lastWeek = today.minusDays(7)

        return sessions.groupBy { session ->
            val sessionDate = session.lastUpdated.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            when {
                sessionDate == today -> "Today"
                sessionDate == yesterday -> "Yesterday"
                sessionDate.isAfter(lastWeek) -> "Last Week"
                else -> "Older"
            }
        }.mapValues { (_, list) -> list.sortedByDescending { it.lastUpdated } }
    }
}
