package me.code4me.chatWindow.components.managers

import me.code4me.api.generated.model.QueryChatMessageRole
import me.code4me.chatWindow.components.repository.ChatRepository
import me.code4me.chatWindow.components.utils.ChatConverter
import java.util.Date
import java.util.UUID

/**
 * Represents a chat session with a unique ID, title, messages, and last updated timestamp.
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New Chat",
    val messages: MutableList<Pair<String, String>> = mutableListOf(),
    var lastUpdated: Date = Date(),
)

/**
 * Manages chat sessions by interacting with the ChatRepository.
 */
class ChatSessionManager(val chatRepository: ChatRepository) {
    // Keep a cache of active sessions
    private val activeSessions = mutableMapOf<String, ChatSession>()
    var currentSession: ChatSession
        private set

    /**
     * Gets a session by its ID, either from the cache or from the repository
     *
     * @param id The ID of the session to retrieve
     * @return The session with the given ID, or null if not found
     */
    fun getSessionById(id: String): ChatSession? {
        return activeSessions[id] ?: chatRepository.getChatSession(id)?.also {
            activeSessions[id] = it
        }
    }

    /**
     * Checks if a session is an empty "New Chat" session
     *
     * @param session The session to check
     * @return True if the session is a "New Chat" with 0 or 1 messages
     */
    fun isEmptyNewChat(session: ChatSession): Boolean {
        return session.title == "New Chat" && session.messages.size <= 1
    }

    init {
        // Load the first session or create a new one
        val sessions = chatRepository.getAllChatSessions()
        currentSession =
            if (sessions.isNotEmpty()) {
                val session = sessions.first()
                activeSessions[session.id] = session
                session
            } else {
                createNewSession()
            }
    }

    fun createNewSession(title: String? = null): ChatSession {
        val sessionTitle = title?.takeIf { it.isNotBlank() } ?: "New Chat"
        val session = ChatSession(title = sessionTitle)

        // Save to repository
        chatRepository.saveChat(session)

        // Add to active sessions
        activeSessions[session.id] = session
        currentSession = session
        return session
    }

    fun switchToSession(chatId: String) {
        // Get from cache or load from repository
        val session =
            activeSessions[chatId] ?: chatRepository.getChatSession(chatId)?.also {
                activeSessions[chatId] = it
            } ?: createNewSession()

        currentSession = session
    }

    fun switchToSession(session: ChatSession) {
        activeSessions[session.id] = session
        currentSession = session
    }

    fun getAllSessions(): List<ChatSession> = chatRepository.getAllChatSessions()

    fun addMessageToCurrentSession(
        sender: String,
        message: String,
    ) {
        currentSession.messages.add(sender to message)
        currentSession.lastUpdated = Date()

        // Save changes to repository
        chatRepository.saveChat(currentSession)
    }

    fun addMessageToCurrentSession(
        role: QueryChatMessageRole,
        message: String,
    ) {
        val sender = ChatConverter.roleToSender(role)
        addMessageToCurrentSession(sender, message)
    }

    fun deleteSession(
        session: ChatSession,
        deleteFromServer: Boolean = false,
    ) {
        // Remove from repository
        chatRepository.deleteChat(session.id, deleteFromServer)

        // Remove from active sessions
        activeSessions.remove(session.id)

        if (currentSession.id == session.id) {
            val sessions = chatRepository.getAllChatSessions()
            currentSession =
                if (sessions.isNotEmpty()) {
                    val newSession = sessions.first()
                    activeSessions[newSession.id] = newSession
                    newSession
                } else {
                    createNewSession("New Chat")
                }
        }
    }
}
