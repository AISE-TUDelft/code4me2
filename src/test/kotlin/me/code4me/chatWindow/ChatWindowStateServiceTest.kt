package me.code4me.chatWindow

import me.code4me.chatWindow.components.persistence.ChatWindowState
import me.code4me.chatWindow.components.persistence.ChatWindowStateService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// --------------------------------------------------------------------------
// ChatWindowStateServiceTest.kt (ISSUE-007)
// --------------------------------------------------------------------------

/**
 * Pins the tool-window persistence half of the registered chat surface
 * (`toolWindow` factoryClass `me.code4me.chatWindow.ChatWindowFactory` +
 * `projectService` `...persistence.ChatWindowStateService` in plugin.xml).
 *
 * Non-reproduction record (ISSUE-007), SUPERSEDED by repair: review later found
 * the factory discarded its project argument (firstOrNull binding), fixed in
 * ChatWindowFactory/ChatPanel with ChatWindowFactoryTest pinning the given
 * project; these assertions pin the observed working persistence behavior.
 */
class ChatWindowStateServiceTest {
    @Test
    fun `state service defaults to no last session`() {
        assertNull(ChatWindowStateService().getLastSessionId())
    }

    @Test
    fun `state service round-trips the last session id through bean state`() {
        val service = ChatWindowStateService()
        service.setLastSessionId("session-1")

        assertEquals("session-1", service.getLastSessionId())
        assertEquals("session-1", service.getState().lastSessionId)

        val restored = ChatWindowStateService()
        restored.loadState(service.getState())

        assertEquals("session-1", restored.getLastSessionId())
    }

    @Test
    fun `chat window state defaults to null session`() {
        assertNull(ChatWindowState().lastSessionId)
    }
}
