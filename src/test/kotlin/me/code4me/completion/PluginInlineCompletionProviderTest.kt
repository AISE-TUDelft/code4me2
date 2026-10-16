package me.code4me.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.code4me.services.state.AuthSettings
import me.code4me.services.state.AuthState
import me.code4me.services.state.PrefState
import me.code4me.services.state.getAuthState
import me.code4me.services.state.getPrefState
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Pins the gating contract of the ghost-text provider: inline completions are
 * only offered to an authenticated user who has not switched them off, and the
 * provider id/insertion wiring stays stable because telemetry and settings key
 * off them.
 */
class PluginInlineCompletionProviderTest : BasePlatformTestCase() {
    private lateinit var provider: PluginInlineCompletionProvider
    private val authSettings = AuthSettings(null, null, null) { }
    private lateinit var originalAuthState: AuthState

    override fun setUp() {
        super.setUp()
        provider = PluginInlineCompletionProvider()

        val authState = mock<AuthState>()
        whenever(authState.state).thenReturn(authSettings)
        originalAuthState = replaceApplicationService(AuthState::class.java, authState)
    }

    override fun tearDown() {
        try {
            if (::originalAuthState.isInitialized) {
                restoreApplicationService(AuthState::class.java, originalAuthState)
            }
            getPrefState().moduleValues.remove(INLINE_PREFERENCE_KEY)
        } finally {
            super.tearDown()
        }
    }

    fun testGhostTextIsDisabledWhenTheUserIsNotAuthenticated() {
        assertFalse(getAuthState().isAuthenticated())

        assertFalse(provider.isEnabled(mock<InlineCompletionEvent>()))
    }

    fun testGhostTextIsEnabledForAnAuthenticatedUserByDefault() {
        getAuthState().setToken("test-token")

        assertTrue(getAuthState().isAuthenticated())
        assertTrue(provider.isEnabled(mock<InlineCompletionEvent>()))
    }

    fun testGhostTextIsDisabledWhenTheInlinePreferenceIsOff() {
        getAuthState().setToken("test-token")
        PrefState.setPreferenceValue("CompletionModel", "getCompletionInline", "false")

        assertFalse(provider.isEnabled(mock<InlineCompletionEvent>()))
    }

    fun testProviderIdentityAndInsertionWiringAreStable() {
        assertEquals("Code4Me V2", provider.id.id)
        assertNotNull(provider.insertHandler)
        assertNotNull(provider.suggestionUpdateManager)
    }

    private companion object {
        const val INLINE_PREFERENCE_KEY = "CompletionModel.getCompletionInline"
    }
}
