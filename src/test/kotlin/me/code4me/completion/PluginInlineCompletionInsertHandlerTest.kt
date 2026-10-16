package me.code4me.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.code4me.services.modules.manager.ModuleManager
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/**
 * Pins the custom insertion hook: accepting a ghost-text completion must still
 * run the platform's default insertion handling and, in addition, report the
 * inserted elements to the module system so the telemetry modules observe the
 * acceptance.
 */
class PluginInlineCompletionInsertHandlerTest : BasePlatformTestCase() {
    private lateinit var originalModuleManager: ModuleManager

    override fun tearDown() {
        try {
            if (::originalModuleManager.isInitialized) {
                restoreApplicationService(ModuleManager::class.java, originalModuleManager)
            }
        } finally {
            super.tearDown()
        }
    }

    fun testAfterInsertionReportsAcceptedElementsToTheModuleManager() {
        myFixture.configureByText("Test.kt", "val answer = ")
        val editor = myFixture.editor
        val file = myFixture.file
        val document = editor.document
        val caret = editor.caretModel.primaryCaret
        val offset = caret.offset

        val request =
            InlineCompletionRequest(
                event = InlineCompletionEvent.DirectCall(editor, caret, null),
                file = file,
                editor = editor,
                document = document,
                startOffset = offset,
                endOffset = offset,
                lookupElement = null,
            )
        val environment =
            InlineCompletionInsertEnvironment(editor, file, TextRange(offset, offset), request)
        val elements: List<InlineCompletionElement> = listOf(InlineCompletionGrayTextElement("42"))

        val moduleManager = mock<ModuleManager>()
        originalModuleManager = replaceApplicationService(ModuleManager::class.java, moduleManager)

        // The default handler edits the document, which the platform only allows
        // inside a write action.
        ApplicationManager.getApplication().runWriteAction {
            PluginInlineCompletionInsertHandler().afterInsertion(environment, elements)
        }

        verify(moduleManager).afterInsertion(environment, elements)
    }
}
