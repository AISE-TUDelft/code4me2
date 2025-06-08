import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.TypingEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.code4me.services.modules.manager.ModuleManager
import me.code4me.services.modules.manager.getModuleManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue


class TelemetryLightTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String {
        // Provide a real or dummy path if you're using testdata files.
        return "src/test/testData"
    }

    fun testTypingAbcd() {
        val file = myFixture.configureByFile("telemetry/TypingAbcd.kt")
        myFixture.type("abcd")
        myFixture.checkResult("""
            abcd
        """.trimIndent())
    }

    fun testCollectData() {
        val file = myFixture.configureByFile("telemetry/TypingAbcd.kt")
        myFixture.type("abcd")

        val psiFile = file
        val editor = myFixture.editor
        val document = editor.document
        val startOffset = myFixture.caretOffset
        val endOffset = startOffset // or adjust if needed


        val caretOffset = myFixture.caretOffset
        val typedText = "\n"
        val range = TextRange(caretOffset, caretOffset + typedText.length)

        val typingEvent = TypingEvent.NewLine(typedText, range)

// Create a dummy inline completion event (you can use a fake one)
        val event = InlineCompletionEvent.DocumentChange(
            typing = typingEvent,
            editor = myFixture.editor,
        )// <- one of the built-in static events

        val request = InlineCompletionRequest(
            event = event,
            file = psiFile,
            editor = editor,
            document = document,
            startOffset = startOffset,
            endOffset = endOffset
        )

        val result = getModuleManager(project).collectData(request)

        assertNotNull(result, "collectData should not return null")
        assertTrue(result.isNotEmpty(), "collectData should return non-empty results when modules are enabled")

        println("Collected data: ${result.joinToString()}")

        result.forEach { record ->
            record.expanded.forEach { (key, value) ->
                assertTrue(
                    key.type.isAssignableFrom(value::class.java),
                    "Type mismatch for key '${key.name}' in record of type '${record.type}': expected ${key.type.simpleName}, got ${value::class.java.simpleName}"
                )
            }
        }

    }



}
