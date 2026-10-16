package me.code4me.chatWindow

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import me.code4me.chatWindow.components.ChatPanel
import me.code4me.chatWindow.components.historyPanel.HistoryPanel
import me.code4me.chatWindow.components.managers.ChatSession
import me.code4me.chatWindow.components.utils.ChatConverter
import me.code4me.completion.replaceApplicationService
import me.code4me.completion.restoreApplicationService
import me.code4me.services.project.getProjectChatService
import me.code4me.services.state.AuthSettings
import me.code4me.services.state.AuthState
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Date
import javax.swing.JLabel

/**
 * Pins the chat reset after a privacy erase: the signed-in user keeps working, but neither the
 * stored history nor the history panel (whose entries restore their session when clicked) may
 * keep a chat from before the erase.
 */
class ChatPanelEraseResetTest : BasePlatformTestCase() {
    private val authSettings = AuthSettings(null, null, "erased-account-token") { }
    private lateinit var originalAuthState: AuthState

    override fun setUp() {
        super.setUp()
        val authState = mock<AuthState>()
        whenever(authState.state).thenReturn(authSettings)
        originalAuthState = replaceApplicationService(AuthState::class.java, authState)
    }

    override fun tearDown() {
        try {
            getProjectChatService(project).clearAllChatsAndMemory()
            if (::originalAuthState.isInitialized) {
                restoreApplicationService(AuthState::class.java, originalAuthState)
            }
        } finally {
            super.tearDown()
        }
    }

    fun testEraseResetRemovesStoredAndListedChatsAndKeepsTheUserSignedIn() {
        val chatService = getProjectChatService(project)
        seedChats()
        val panel = ChatPanel(project)
        assertTrue("the history lists the older chat before the erase", OLD_TITLE in historyLabels(panel))

        panel.resetAllChatsAfterErase(authSettings.tokenGeneration())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertFalse(OLD_TITLE in historyLabels(panel))
        assertTrue(chatService.getAllChatSessions().none { session -> session.messages.any { it.second.isNotBlank() } })
        assertTrue(authSettings.isAuthenticated())
    }

    fun testEraseResetIsSkippedOnceAnotherSignInReplacedTheErasedOne() {
        val chatService = getProjectChatService(project)
        seedChats()
        val panel = ChatPanel(project)
        val erasedGeneration = authSettings.tokenGeneration()
        authSettings.setToken("next-account-token")

        panel.resetAllChatsAfterErase(erasedGeneration)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue(chatService.getAllChatSessions().any { it.title == OLD_TITLE && it.messages.isNotEmpty() })
        assertTrue(OLD_TITLE in historyLabels(panel))
    }

    /**
     * An older chat the history lists, and a newer one the panel opens as its current session
     * (a new panel without a remembered session starts that one empty).
     */
    private fun seedChats() {
        val chatService = getProjectChatService(project)
        chatService.saveChat(chat(OLD_TITLE, Date(1_000L)))
        chatService.saveChat(chat("Most recent chat", Date()))
    }

    private fun chat(
        title: String,
        lastUpdated: Date,
    ): ChatSession =
        ChatSession(
            title = title,
            messages =
                mutableListOf(
                    ChatConverter.USER_SENDER to "How do I parse this file?",
                    ChatConverter.ASSISTANT_SENDER to "Use a streaming reader.",
                ),
            lastUpdated = lastUpdated,
        )

    private fun historyLabels(panel: ChatPanel): List<String> {
        val history = UIUtil.findComponentOfType(panel, HistoryPanel::class.java) ?: error("the chat panel has no history panel")
        return UIUtil.findComponentsOfType(history, JLabel::class.java).map { it.text }
    }

    private companion object {
        const val OLD_TITLE = "Chat from before the erase"
    }
}
