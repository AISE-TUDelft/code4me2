package me.code4me.toolWindow.managers

import java.util.Date
import java.util.UUID

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New Chat",
    val messages: MutableList<Pair<String, String>> = mutableListOf(),
    var lastUpdated: Date = Date(),
)

class ChatSessionManager {
    private val chatSessions = mutableListOf<ChatSession>()
    var currentSession: ChatSession
        private set

    init {
        currentSession = createNewSession()
    }

    fun createNewSession(title: String? = null): ChatSession {
        val sessionTitle = title?.takeIf { it.isNotBlank() } ?: "New Chat"
        val session = ChatSession(title = sessionTitle)
        chatSessions.add(session)
        currentSession = session
        return session
    }

    fun switchToSession(session: ChatSession) {
        currentSession = session
    }

    fun getAllSessions(): List<ChatSession> = chatSessions.toList()

    fun addMessageToCurrentSession(
        sender: String,
        message: String,
    ) {
        currentSession.messages.add(sender to message)
        currentSession.lastUpdated = Date()
    }

    fun deleteSession(session: ChatSession) {
        chatSessions.remove(session)
        if (currentSession == session) {
            currentSession = chatSessions.firstOrNull() ?: createNewSession("New Chat")
        }
    }
}
