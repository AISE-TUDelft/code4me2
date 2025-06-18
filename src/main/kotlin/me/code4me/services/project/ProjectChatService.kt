package me.code4me.services.project

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
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
) : PersistentStateComponent<ProjectChatState>, ChatRepository {
    private var internalState = ProjectChatState()
    private val LOG = thisLogger()

    override fun getState(): ProjectChatState = internalState

    override fun loadState(state: ProjectChatState) {
        internalState = state
    }

    override fun getChatSession(chatId: String): ChatSession? {
        val chatData = internalState.chats[chatId] ?: return null
        val messages =
            chatData.messages
                .filter { it.content != null }
                .map {
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
        return internalState.chats.keys.mapNotNull { getChatSession(it) }
            .sortedByDescending { it.lastUpdated }
    }

    override fun saveChat(chatSession: ChatSession) {
        val messages =
            chatSession.messages.map { (sender, content) ->
                ChatMessage(ChatConverter.senderToRole(sender).value, content)
            }

        val chatData = internalState.chats.getOrPut(chatSession.id) { ChatData() }
        chatData.title = chatSession.title
        chatData.lastUpdated = chatSession.lastUpdated.time
        chatData.messages = messages.toMutableList()

        internalState.chats[chatSession.id] = chatData // Force reassignment
        println("Saving chat ${chatSession.id} with ${chatSession.messages.size} messages")
    }

    override fun deleteChat(
        chatId: String,
        deleteFromServer: Boolean,
    ) {
        internalState.chats.remove(chatId)
        if (deleteFromServer) {
            getAppService().deleteChat(UUID.fromString(chatId), project)
        }
    }

    override fun addMessage(
        chatId: String,
        role: QueryChatMessageRole,
        content: String,
    ) {
        val chatData =
            internalState.chats.getOrPut(chatId) {
                ChatData().apply {
                    title = "Chat $chatId"
                    lastUpdated = System.currentTimeMillis()
                }
            }
        chatData.messages = (chatData.messages + ChatMessage(role.value, content)).toMutableList()
        chatData.lastUpdated = System.currentTimeMillis()
        internalState.chats[chatId] = chatData

        getChatSession(chatId)?.let { saveChat(it) }
    }

    override fun getMessages(chatId: String): List<Pair<QueryChatMessageRole, String>> {
        return internalState.chats[chatId]?.messages?.map {
            val role = QueryChatMessageRole.decode(it.role) ?: QueryChatMessageRole.user
            role to it.content!!
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
        val chatData = internalState.chats[chatId] ?: return
        chatData.title = newTitle
        chatData.lastUpdated = System.currentTimeMillis()
        internalState.chats[chatId] = chatData
    }

    /**
     * Gets the title of a specific chat
     */
    fun getChatTitle(chatId: String): String? = internalState.chats[chatId]?.title

    /**
     * Gets the last updated timestamp of a specific chat
     */
    fun getLastUpdated(chatId: String): Date? = internalState.chats[chatId]?.lastUpdated?.let { Date(it) }

    /**
     * Updates the last modified timestamp for a chat
     */
    fun updateLastModified(chatId: String) {
        val chatData = internalState.chats[chatId] ?: return
        chatData.lastUpdated = System.currentTimeMillis()
        internalState.chats[chatId] = chatData
    }
    // Existing utility methods (updated to work with new structure)

    /**
     * Gets all chat IDs
     */
    fun getAllChatIds(): Set<String> = internalState.chats.keys

    /**
     * Checks if a chat exists
     */
    fun hasChatId(chatId: String): Boolean = internalState.chats.containsKey(chatId)

    /**
     * Gets the number of messages in a specific chat
     */
    fun getMessageCount(chatId: String): Int = internalState.chats[chatId]?.messages?.size ?: 0

    /**
     * Clears all messages for a specific chat but keeps metadata
     */
    fun clearChat(chatId: String) {
        val chatData = internalState.chats[chatId] ?: return
        chatData.messages = mutableListOf()
        chatData.lastUpdated = System.currentTimeMillis()
        internalState.chats[chatId] = chatData
    }

    /**
     * Clears all chats
     */
    fun clearAllChats() {
        internalState.chats.clear()
    }

    /**
     * Gets the last message from a specific chat
     */
    fun getLastMessage(chatId: String): Pair<QueryChatMessageRole, String>? {
        val messages = internalState.chats[chatId]?.messages ?: return null
        if (messages.isEmpty()) return null
        val last = messages.last()
        val role = QueryChatMessageRole.decode(last.role) ?: QueryChatMessageRole.user
        return role to last.content!!
    }

    /**
     * Gets all messages for all chats
     */
    fun getAllChats(): Map<String, List<Pair<QueryChatMessageRole, String>>> {
        return internalState.chats.mapValues { (_, chatData) ->
            chatData.messages.map {
                val role = QueryChatMessageRole.decode(it.role) ?: QueryChatMessageRole.user
                role to it.content!!
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
            internalState.chats.getOrPut(chatId) {
                ChatData().apply {
                    title = "Chat $chatId"
                    lastUpdated = System.currentTimeMillis()
                }
            }
        messages.forEach { (role, content) ->
            chatData.messages = (chatData.messages + ChatMessage(role.value, content)).toMutableList()
        }
        chatData.lastUpdated = System.currentTimeMillis()
        internalState.chats[chatId] = chatData
    }

    /**
     * Replaces all messages for a specific chat
     */
    fun setMessages(
        chatId: String,
        messages: List<Pair<QueryChatMessageRole, String>>,
    ) {
        val chatData =
            internalState.chats.getOrPut(chatId) {
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
        internalState.chats[chatId] = chatData
    }

    /**
     * Completely clears all chats and forces persistence to disk.
     * This method ensures that the XML file is properly cleared.
     */
    fun clearAllChatsAndMemory() {
        LOG.info("Starting clearAllChatsAndMemory for project: ${project.name}")

        // Clear the internal state completely
        internalState.chats.clear()

        // Create a new empty state to ensure clean persistence
        internalState = ProjectChatState()

        // Force the component to save the state immediately
        try {
            // This triggers the persistence mechanism to write the cleared state to disk
            project.save()
            LOG.info("Project state saved after clearing chats")
        } catch (e: Exception) {
            LOG.warn("Failed to save project state after clearing chats", e)
        }

        LOG.info("Completed clearAllChatsAndMemory for project: ${project.name}")
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
