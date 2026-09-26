package me.code4me.services.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.beans.PropertyChangeListener

class AuthSettingsTest {
    @Test
    fun `deferred logout reset skips a newer login including a reused token`() {
        for (replacement in listOf("new-token", "old-token")) {
            val settings = AuthSettings(null, null, null) { }
            val logoutGeneration = settings.tokenGeneration()
            var resetRan = false
            assertTrue(settings.runIfSignedOut(logoutGeneration) { resetRan = true })
            assertTrue(resetRan)

            resetRan = false
            settings.setToken(replacement)
            assertFalse(settings.runIfSignedOut(logoutGeneration) { resetRan = true })
            assertFalse(resetRan)
        }
    }

    @Test
    fun `token listener observes the newly published authentication state`() {
        var persistedToken: String? = null
        val settings = AuthSettings(null, null, null) { persistedToken = it }
        var observedToken: String? = null
        settings.addPropertyChangeListener(
            TOKEN_PROPERTY,
            PropertyChangeListener { observedToken = settings.getToken() },
        )

        settings.setToken("new-token")

        assertEquals("new-token", observedToken)
        assertEquals("new-token", settings.getToken())
        assertEquals("new-token", persistedToken)
    }
}
