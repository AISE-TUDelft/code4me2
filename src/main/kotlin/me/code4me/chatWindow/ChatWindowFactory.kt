package me.code4me.chatWindow

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import me.code4me.chatWindow.components.ChatPanel

/**
 * Factory for creating the Code4Me chat tool window in IntelliJ IDEA.
 *
 * Implements the ToolWindowFactory interface to integrate the chat functionality
 * into IntelliJ's tool window system. Creates and configures the main ChatPanel
 * component when the tool window is first opened.
 */
class ChatWindowFactory : ToolWindowFactory {
    init {
        thisLogger().warn(
            "Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.",
        )
    }
    /**
     * Creates the chat tool window content by instantiating and adding the main ChatPanel.
     *
     * Called by IntelliJ when the tool window is first created or when content needs
     * to be initialized. Sets up the ChatPanel as the primary content component.
     *
     * @param project The current IntelliJ project context
     * @param toolWindow The tool window instance to populate with content
     */
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val content = ContentFactory.getInstance().createContent(ChatPanel(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
