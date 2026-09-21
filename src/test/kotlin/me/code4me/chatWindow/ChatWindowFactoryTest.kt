package me.code4me.chatWindow

import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import me.code4me.chatWindow.components.ChatPanel
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

// --------------------------------------------------------------------------
// ChatWindowFactoryTest.kt (ISSUE-007)
// --------------------------------------------------------------------------

/**
 * Pins the tool-window factory repair: [ChatWindowFactory.createToolWindowContent]
 * must thread the given project into the created [ChatPanel] (previously it
 * discarded the project and the panel fell back to
 * `ProjectManager.openProjects.firstOrNull()`, binding the wrong project when
 * several are open and leaving panels uninitialized when none was open yet).
 */
class ChatWindowFactoryTest : BasePlatformTestCase() {
    fun testCreateToolWindowContentBindsGivenProject() {
        val toolWindow = mock<ToolWindow>()
        val contentManager = mock<ContentManager>()
        whenever(toolWindow.contentManager).thenReturn(contentManager)

        ChatWindowFactory().createToolWindowContent(project, toolWindow)

        val captor = argumentCaptor<Content>()
        verify(contentManager, times(1)).addContent(captor.capture())
        val panel = captor.firstValue.component as ChatPanel
        assertSame(project, panel.getBoundProject())
    }
}
