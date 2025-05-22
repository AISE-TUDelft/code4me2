package me.code4me.toolWindow.managers

import java.util.UUID

/**
 * Data class representing a chat session with unique ID, title and messages.
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val messages: MutableList<Pair<String, String>> = mutableListOf(),
    // sender, messanger
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
}
