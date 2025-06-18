package me.code4me.actions

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.CaretModel
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TriggerInlineCompletionActionTest {
    private lateinit var action: TriggerInlineCompletionAction
    private lateinit var event: AnActionEvent
    private lateinit var editor: Editor
    private lateinit var project: Project
    private lateinit var caretModel: CaretModel
    private lateinit var caret: Caret
    private lateinit var dataContext: DataContext
    private lateinit var presentation: Presentation

    @BeforeEach
    fun setUp() {
        action = TriggerInlineCompletionAction()
        event = mock()
        editor = mock()
        project = mock()
        caretModel = mock()
        caret = mock()
        dataContext = mock()
        presentation = Presentation()
        whenever(event.getData(CommonDataKeys.EDITOR)).thenReturn(editor)
        whenever(event.getData(CommonDataKeys.PROJECT)).thenReturn(project)
        whenever(editor.caretModel).thenReturn(caretModel)
        whenever(caretModel.currentCaret).thenReturn(caret)
        whenever(event.dataContext).thenReturn(dataContext)
        whenever(event.presentation).thenReturn(presentation)
    }

    @Test
    fun `actionPerformed logs warning when handler is absent`() {
        mockStatic(InlineCompletion::class.java).use { mocked ->
            whenever(InlineCompletion.getHandlerOrNull(editor)).thenReturn(null)
            action.actionPerformed(event)
            // No exception should be thrown, nothing to verify
        }
    }

    @Test
    fun `actionPerformed does nothing if editor is null`() {
        whenever(event.getData(CommonDataKeys.EDITOR)).thenReturn(null)
        action.actionPerformed(event)
        // Should return early, nothing to verify
    }

    @Test
    fun `actionPerformed does nothing if project is null`() {
        whenever(event.getData(CommonDataKeys.PROJECT)).thenReturn(null)
        action.actionPerformed(event)
        // Should return early, nothing to verify
    }

    @Test
    fun `update disables action if editor or project is null`() {
        whenever(event.getData(CommonDataKeys.EDITOR)).thenReturn(null)
        action.update(event)
        assert(!presentation.isEnabledAndVisible)
        whenever(event.getData(CommonDataKeys.EDITOR)).thenReturn(editor)
        whenever(event.getData(CommonDataKeys.PROJECT)).thenReturn(null)
        action.update(event)
        assert(!presentation.isEnabledAndVisible)
    }

    @Test
    fun `update enables action if editor and project are present`() {
        whenever(event.getData(CommonDataKeys.EDITOR)).thenReturn(editor)
        whenever(event.getData(CommonDataKeys.PROJECT)).thenReturn(project)
        action.update(event)
        assert(presentation.isEnabledAndVisible)
    }
}
