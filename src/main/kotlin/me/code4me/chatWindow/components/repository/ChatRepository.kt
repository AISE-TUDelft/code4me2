package me.code4me.chatWindow.components.repository

import me.code4me.api.generated.model.QueryChatMessageRole
import me.code4me.chatWindow.components.managers.ChatSession

/**
 * Unified interface for chat data operations.
 * Provides a common API for accessing and manipulating chat sessions
 * regardless of the underlying storage mechanism.
 */
interface ChatRepository {
    // Core operations
    fun getChatSession(chatId: String): ChatSession?
    fun getAllChatSessions(): List<ChatSession>
    fun saveChat(chatSession: ChatSession)
    fun deleteChat(chatId: String, deleteFromServer: Boolean = false)
    
    // Message operations
    fun addMessage(chatId: String, role: QueryChatMessageRole, content: String)
    fun getMessages(chatId: String): List<Pair<QueryChatMessageRole, String>>
}