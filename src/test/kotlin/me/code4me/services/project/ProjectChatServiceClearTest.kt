package me.code4me.services.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.code4me.api.generated.model.QueryChatMessageRole
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Pins how clearing the chat history reaches the disk. Signing out clears it, and the first
 * AppService construction can sign out from a status-bar repaint: a write-unsafe EDT context,
 * where a synchronous Project.save() (a modal progress on the EDT) logs "Write-unsafe context!".
 * The chats must leave memory at once, and the project save must run later in a write-safe event.
 */
class ProjectChatServiceClearTest : BasePlatformTestCase() {
    private var chatProjectDisposed = false

    /** For each Project.save(), whether it ran where model changes are allowed. */
    private val saves = mutableListOf<Boolean>()

    private val chatProject: Project = mock()

    override fun setUp() {
        super.setUp()
        whenever(chatProject.name).thenReturn("chat-clear-test")
        whenever(chatProject.isDisposed).thenAnswer { chatProjectDisposed }
        whenever(chatProject.disposed).thenReturn(Condition<Any?> { chatProjectDisposed })
        doAnswer {
            saves += TransactionGuard.getInstance().isWritingAllowed
            null
        }.whenever(chatProject).save()
    }

    fun testClearFromWriteUnsafeContextSavesLaterInWriteSafeEvent() {
        val chatService = serviceWithOneChat()
        var clearRanWhereWritingAllowed: Boolean? = null
        var savesDuringClear: Int? = null

        // Runnables with ModalityState.any() run where model changes are not allowed, like the
        // status-bar repaint that first constructed AppService.
        ApplicationManager.getApplication().invokeLater(
            {
                clearRanWhereWritingAllowed = TransactionGuard.getInstance().isWritingAllowed
                chatService.clearAllChatsAndMemory()
                savesDuringClear = saves.size
            },
            ModalityState.any(),
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("precondition: the clear ran in a write-unsafe context", false, clearRanWhereWritingAllowed)
        assertTrue("the chats leave memory at once", chatService.getAllChatSessions().isEmpty())
        assertEquals("the clear itself must not save the project", 0, savesDuringClear)
        assertEquals("one later save, where model changes are allowed", listOf(true), saves)
    }

    fun testDeferredSaveSkipsProjectDisposedBeforeItRuns() {
        val chatService = serviceWithOneChat()

        chatService.clearAllChatsAndMemory()
        chatProjectDisposed = true
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue(chatService.getAllChatSessions().isEmpty())
        assertEquals("a disposed project is not saved", emptyList<Boolean>(), saves)
    }

    private fun serviceWithOneChat(): ProjectChatService =
        ProjectChatService(chatProject).apply {
            addMessage("chat-1", QueryChatMessageRole.user, "How do I parse this file?")
            assertEquals(1, getAllChatSessions().size)
        }
}
