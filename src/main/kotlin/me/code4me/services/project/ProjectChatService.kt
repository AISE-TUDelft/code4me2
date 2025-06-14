package me.code4me.services.project

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import me.code4me.api.generated.model.QueryChatMessageRole
import me.code4me.chatWindow.components.managers.ChatSession
import me.code4me.chatWindow.components.repository.ChatRepository
import me.code4me.chatWindow.components.utils.ChatConverter
import me.code4me.services.app.getAppService
import java.util.Date
import java.util.UUID

/**
 * Project-specific service that stores chat conversations data.
 * This service is scoped to individual projects and persists data in project-specific storage.
 * Now implements ChatRepository interface to provide unified chat operations.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ProjectChatState",
    storages = [Storage("code4me-project-chats.xml")],
)
class ProjectChatService(
    private val project: Project,
) : SimplePersistentStateComponent<ProjectChatState>(ProjectChatState()), ChatRepository {
    // ChatRepository interface implementation
    override fun getChatSession(chatId: String): ChatSession? {
        val chatData = state.chats[chatId] ?: return null

        val messages =
            chatData.messages.map {
                val role = QueryChatMessageRole.decode(it.role) ?: QueryChatMessageRole.user
                ChatConverter.roleToSender(role) to it.content!!
            }

        return ChatSession(
            id = chatId,
            title = chatData.title ?: "Chat $chatId",
            messages = messages.toMutableList(),
            lastUpdated = Date(chatData.lastUpdated ?: System.currentTimeMillis()),
        )
    }

    override fun getAllChatSessions(): List<ChatSession> {
        return state.chats.keys.mapNotNull { getChatSession(it) }
            .sortedByDescending { it.lastUpdated }
    }

    override fun saveChat(chatSession: ChatSession) {
        val messages =
            chatSession.messages.map { (sender, content) ->
                ChatMessage(ChatConverter.senderToRole(sender).value, content)
            }

        val chatData = state.chats.getOrPut(chatSession.id) { ChatData() }
        chatData.title = chatSession.title
        chatData.lastUpdated = chatSession.lastUpdated.time
        chatData.messages = messages.toMutableList()
    }

    override fun deleteChat(
        chatId: String,
        deleteFromServer: Boolean,
    ) {
        state.chats.remove(chatId)
        if (deleteFromServer) {
            getAppService().deleteChat(
                UUID.fromString(chatId),
                project = project,
            )
        }
    }

    override fun addMessage(
        chatId: String,
        role: QueryChatMessageRole,
        content: String,
    ) {
        val chatData =
            state.chats.getOrPut(chatId) {
                ChatData().apply {
                    title = "Chat $chatId"
                    lastUpdated = System.currentTimeMillis()
                }
            }
        chatData.messages.add(ChatMessage(role.value, content))
        chatData.lastUpdated = System.currentTimeMillis()
    }

    override fun getMessages(chatId: String): List<Pair<QueryChatMessageRole, String>> {
        return state.chats[chatId]?.messages?.map {
            val role = QueryChatMessageRole.decode(it.role) ?: QueryChatMessageRole.user
            Pair(role, it.content!!)
        } ?: emptyList()
    }

    // Enhanced methods for title and metadata management

    /**
     * Updates the title of a specific chat
     */
    fun updateChatTitle(
        chatId: String,
        newTitle: String,
    ) {
        val chatData = state.chats[chatId] ?: return
        chatData.title = newTitle
        chatData.lastUpdated = System.currentTimeMillis()
    }

    /**
     * Gets the title of a specific chat
     */
    fun getChatTitle(chatId: String): String? {
        return state.chats[chatId]?.title
    }

    /**
     * Gets the last updated timestamp of a specific chat
     */
    fun getLastUpdated(chatId: String): Date? {
        return state.chats[chatId]?.lastUpdated?.let { Date(it) }
    }

    /**
     * Updates the last modified timestamp for a chat
     */
    fun updateLastModified(chatId: String) {
        state.chats[chatId]?.lastUpdated = System.currentTimeMillis()
    }

    // Existing utility methods (updated to work with new structure)

    /**
     * Gets all chat IDs
     */
    fun getAllChatIds(): Set<String> {
        return state.chats.keys.toSet()
    }

    /**
     * Checks if a chat exists
     */
    fun hasChatId(chatId: String): Boolean {
        return state.chats.containsKey(chatId)
    }

    /**
     * Gets the number of messages in a specific chat
     */
    fun getMessageCount(chatId: String): Int {
        return state.chats[chatId]?.messages?.size ?: 0
    }

    /**
     * Clears all messages for a specific chat but keeps metadata
     */
    fun clearChat(chatId: String) {
        state.chats[chatId]?.let { chatData ->
            chatData.messages.clear()
            chatData.lastUpdated = System.currentTimeMillis()
        }
    }

    /**
     * Clears all chats
     */
    fun clearAllChats() {
        state.chats.clear()
    }

    /**
     * Gets the last message from a specific chat
     */
    fun getLastMessage(chatId: String): Pair<QueryChatMessageRole, String>? {
        val messages = state.chats[chatId]?.messages
        return if (messages.isNullOrEmpty()) {
            null
        } else {
            val lastMessage = messages.last()
            val role = QueryChatMessageRole.decode(lastMessage.role) ?: QueryChatMessageRole.user
            Pair(role, lastMessage.content!!)
        }
    }

    /**
     * Gets all messages for all chats
     */
    fun getAllChats(): Map<String, List<Pair<QueryChatMessageRole, String>>> {
        return state.chats.mapValues { (_, chatData) ->
            chatData.messages.map {
                val role = QueryChatMessageRole.decode(it.role) ?: QueryChatMessageRole.user
                Pair(role, it.content!!)
            }
        }
    }

    /**
     * Adds multiple messages to a chat at once
     */
    fun addMessages(
        chatId: String,
        messages: List<Pair<QueryChatMessageRole, String>>,
    ) {
        val chatData =
            state.chats.getOrPut(chatId) {
                ChatData().apply {
                    title = "Chat $chatId"
                    lastUpdated = System.currentTimeMillis()
                }
            }
        messages.forEach { (role, content) ->
            chatData.messages.add(ChatMessage(role.value, content))
        }
        chatData.lastUpdated = System.currentTimeMillis()
    }

    /**
     * Replaces all messages for a specific chat
     */
    fun setMessages(
        chatId: String,
        messages: List<Pair<QueryChatMessageRole, String>>,
    ) {
        val chatData =
            state.chats.getOrPut(chatId) {
                ChatData().apply {
                    title = "Chat $chatId"
                    lastUpdated = System.currentTimeMillis()
                }
            }
        chatData.messages =
            messages.map { (role, content) ->
                ChatMessage(role.value, content)
            }.toMutableList()
        chatData.lastUpdated = System.currentTimeMillis()
    }
}

/**
 * State class for project chat data
 */
class ProjectChatState : BaseState() {
    var chats by map<String, ChatData>()
}

/**
 * Represents a complete chat with metadata and messages
 */
open class ChatData : BaseState() {
    var title by string()
    var lastUpdated by property(0L)
    var messages by list<ChatMessage>()
}

/**
 * Represents a single chat message
 */
open class ChatMessage() : BaseState() {
    var role by string()
    var content by string()

    constructor(role: String, content: String) : this() {
        this.role = role
        this.content = content
    }
}

/**
 * Helper function to get the ProjectChatService for a specific project
 */
fun getProjectChatService(project: Project): ProjectChatService {
    return project.service<ProjectChatService>()
}
