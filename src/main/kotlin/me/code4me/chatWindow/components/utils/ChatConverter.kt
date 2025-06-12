package me.code4me.chatWindow.components.utils

import me.code4me.api.generated.model.QueryChatMessageRole

/**
 * Utility class for converting between different chat message formats.
 * Handles conversions between UI format, storage format, and backend API format.
 */
object ChatConverter {
    // Constants for sender names
    const val USER_SENDER = "You"
    const val ASSISTANT_SENDER = "Code4Me V2"
    const val SYSTEM_SENDER = "System"
    
    /**
     * Convert from ChatSession messages (UI format) to backend API format
     * @param messages List of sender-content pairs from ChatSession
     * @return List of lists where each inner list contains [role, content]
     */
    fun toApiMessages(messages: List<Pair<String, String>>): List<List<Any>> {
        return messages.map { (sender, content) ->
            val role = when(sender) {
                USER_SENDER -> QueryChatMessageRole.user
                ASSISTANT_SENDER -> QueryChatMessageRole.assistant
                else -> QueryChatMessageRole.system
            }
            listOf(role.value, content)
        }
    }
    
    /**
     * Convert from ProjectChatService format to ChatSession format (UI format)
     * @param messages List of role-content pairs from ProjectChatService
     * @return List of sender-content pairs for ChatSession
     */
    fun toChatSessionMessages(messages: List<Pair<QueryChatMessageRole, String>>): List<Pair<String, String>> {
        return messages.map { (role, content) ->
            val sender = when(role) {
                QueryChatMessageRole.user -> USER_SENDER
                QueryChatMessageRole.assistant -> ASSISTANT_SENDER
                QueryChatMessageRole.system -> SYSTEM_SENDER
            }
            sender to content
        }
    }
    
    /**
     * Convert from backend API format to ProjectChatService format
     * @param messages List of lists where each inner list contains [role, content]
     * @return List of role-content pairs for ProjectChatService
     */
    fun fromApiMessages(messages: List<List<Any>>): List<Pair<QueryChatMessageRole, String>> {
        return messages.map { message ->
            val roleStr = message[0] as String
            val content = message[1] as String
            val role = QueryChatMessageRole.decode(roleStr) ?: QueryChatMessageRole.user
            Pair(role, content)
        }
    }
    
    /**
     * Converts a QueryChatMessageRole to a sender string for UI display
     */
    fun roleToSender(role: QueryChatMessageRole): String {
        return when(role) {
            QueryChatMessageRole.user -> USER_SENDER
            QueryChatMessageRole.assistant -> ASSISTANT_SENDER
            QueryChatMessageRole.system -> SYSTEM_SENDER
        }
    }
    
    /**
     * Converts a sender string from UI to QueryChatMessageRole for storage
     */
    fun senderToRole(sender: String): QueryChatMessageRole {
        return when(sender) {
            USER_SENDER -> QueryChatMessageRole.user
            ASSISTANT_SENDER -> QueryChatMessageRole.assistant
            else -> QueryChatMessageRole.system
        }
    }
}