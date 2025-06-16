package me.code4me.chatWindow.components.persistence

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * A persistent service responsible for storing and retrieving the state of the chat window.
 *
 * This service currently tracks the last active chat session ID, allowing the chat plugin
 * to restore the user's previous session across IDE restarts or plugin reloads.</p>
 *
 */
@State(
    name = "ChatWindowState",
    storages = [Storage("chatWindowState.xml")],
)
class ChatWindowStateService : PersistentStateComponent<ChatWindowState> {
    private var state = ChatWindowState()

    override fun getState(): ChatWindowState = state

    override fun loadState(state: ChatWindowState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun setLastSessionId(sessionId: String?) {
        state.lastSessionId = sessionId
    }

    fun getLastSessionId(): String? = state.lastSessionId

    companion object {
        fun getInstance(project: Project): ChatWindowStateService {
            return project.service<ChatWindowStateService>()
        }
    }
}

data class ChatWindowState(
    var lastSessionId: String? = null,
)
