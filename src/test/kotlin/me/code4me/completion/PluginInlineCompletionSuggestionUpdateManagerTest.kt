package me.code4me.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestionUpdateManager
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.openapi.util.UserDataHolderBase
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Pins that Code4Me's update manager is a transparent delegate: until custom
 * update handling exists, every update must reach the platform's manager and
 * return its result unchanged.
 */
class PluginInlineCompletionSuggestionUpdateManagerTest {
    @Test
    fun `update delegates to the base manager and returns its result`() {
        val baseManager = mock<InlineCompletionSuggestionUpdateManager>()
        // UpdateResult is a sealed interface, so the test uses a real platform
        // implementation instead of a mock.
        val expectedResult = InlineCompletionSuggestionUpdateManager.UpdateResult.Same
        val event = mock<InlineCompletionEvent>()
        val snapshot =
            InlineCompletionVariant.Snapshot(
                UserDataHolderBase(),
                emptyList(),
                0,
                true,
                InlineCompletionVariant.Snapshot.State.UNTOUCHED,
            )
        whenever(baseManager.update(event, snapshot)).thenReturn(expectedResult)

        val manager = PluginInlineCompletionSuggestionUpdateManager(baseManager)

        assertSame(expectedResult, manager.update(event, snapshot))
        verify(baseManager).update(event, snapshot)
    }
}
