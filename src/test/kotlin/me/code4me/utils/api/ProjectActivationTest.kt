package me.code4me.utils.api

import me.code4me.api.generated.infrastructure.ClientException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The server refuses an expired session with the same 401 as an unknown project.
 * Giving the project up then created a new one, and the agent's memory of the
 * participant's earlier chats (kept per project) became unreadable. So a refused
 * activation is retried once in a renewed session before the project is dropped.
 */
class ProjectActivationTest {
    private val renewals = mutableListOf<Unit>()
    private val activated = mutableListOf<UUID>()

    @Test
    fun `a project refused for an expired session is activated in a renewed one`() {
        assertTrue(activateAfterRenewingSession(PROJECT_ID, { renewals.add(Unit) }, { activated.add(it) }))

        assertEquals(1, renewals.size)
        assertEquals(listOf(UUID.fromString(PROJECT_ID)), activated)
    }

    @Test
    fun `a project still refused after renewal is reported as gone`() {
        for (status in listOf(401, 404)) {
            assertFalse(activateAfterRenewingSession(PROJECT_ID, {}, { throw ClientException("refused", status) }))
        }
    }

    @Test
    fun `a login the server refuses propagates without touching the project`() {
        val error =
            assertThrows(ClientException::class.java) {
                activateAfterRenewingSession(PROJECT_ID, { throw ClientException("login expired", 401) }, { activated.add(it) })
            }

        assertEquals(401, error.statusCode)
        assertTrue(activated.isEmpty())
    }

    @Test
    fun `other server errors propagate`() {
        val error =
            assertThrows(ClientException::class.java) {
                activateAfterRenewingSession(PROJECT_ID, {}, { throw ClientException("rate limited", 429) })
            }

        assertEquals(429, error.statusCode)
    }

    private companion object {
        const val PROJECT_ID = "0f1e2d3c-4b5a-4968-8776-655443322110"
    }
}
