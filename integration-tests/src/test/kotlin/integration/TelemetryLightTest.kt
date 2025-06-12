package integration

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.TypingEvent
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.code4me.services.modules.context.FileContextRetrievalModule
import me.code4me.services.modules.manager.getModuleManager
import me.code4me.services.modules.telemetry.behavioral.TypingSpeed
import me.code4me.services.state.PrefState
import me.code4me.utils.record.Record
import org.junit.jupiter.api.Assertions

class TelemetryLightTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String {
        // Provide a real or dummy path if you're using testdata files.
        return "src/test/testData"
    }

    fun testTypingAbcd() {
        val file = myFixture.configureByFile("telemetry/TypingAbcd.kt")
        myFixture.type("abcd")
        myFixture.checkResult(
            """
            abcd
            """.trimIndent(),
        )
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
        val event =
            InlineCompletionEvent.DocumentChange(
                typing = typingEvent,
                editor = myFixture.editor,
            ) // <- one of the built-in static events

        val request =
            InlineCompletionRequest(
                event = event,
                file = psiFile,
                editor = editor,
                document = document,
                startOffset = startOffset,
                endOffset = endOffset,
            )

        val result = getModuleManager(project).collectData(request)

        Assertions.assertNotNull(result, "collectData should not return null")
        Assertions.assertTrue(
            result.isNotEmpty(),
            "collectData should return non-empty results when modules are enabled",
        )

        println("Collected data: ${result.joinToString()}")

        result.forEach { record ->
            record.expanded.forEach { (key, value) ->
                Assertions.assertTrue(
                    key.type.isAssignableFrom(value::class.java),
                    "Type mismatch for key '${key.name}' in record of type '${record.type}': expected ${key.type.simpleName}, got ${value::class.java.simpleName}",
                )
            }
        }
    }

    fun testCollectDataWithDisabledModule() {
        val file = myFixture.configureByFile("telemetry/TypingAbcd.kt")
        myFixture.type("abcd")

        // Get the TypingSpeed module's preference ID and disable it
        val typingSpeed = TypingSpeed()
        val typingSpeedPreferenceId = typingSpeed.getPreferenceId()
        PrefState.Companion.disableModule(typingSpeedPreferenceId)

        // Set up the request the same way as in testCollectData
        val psiFile = file
        val editor = myFixture.editor
        val document = editor.document
        val startOffset = myFixture.caretOffset
        val endOffset = startOffset

        val caretOffset = myFixture.caretOffset
        val typedText = "\n"
        val range = TextRange(caretOffset, caretOffset + typedText.length)

        val typingEvent = TypingEvent.NewLine(typedText, range)

        val event =
            InlineCompletionEvent.DocumentChange(
                typing = typingEvent,
                editor = myFixture.editor,
            )

        val request =
            InlineCompletionRequest(
                event = event,
                file = psiFile,
                editor = editor,
                document = document,
                startOffset = startOffset,
                endOffset = endOffset,
            )

        // Call collectData
        val result = getModuleManager(project).collectData(request)

        // Verify that the results don't contain typing speed data
        Assertions.assertNotNull(result, "collectData should not return null")

        // Check if any record contains typing speed data
        val typingSpeedKey = Record.Companion.key<Int>("typing_speed")
        val hasTypingSpeedData =
            result.any { record ->
                record.type == Record.Type.BEHAVIORAL_TELEMETRY && record.containsKey(typingSpeedKey)
            }

        Assertions.assertFalse(
            hasTypingSpeedData,
            "collectData should not return typing speed data when the TypingSpeed module is disabled",
        )

        // Re-enable the module for other tests
        PrefState.Companion.enableModule(typingSpeedPreferenceId)

//        val secondResult = getModuleManager(project).collectData(request)
//
//        Assertions.assertNotNull(secondResult, "collectData should not return null after re-enabling the module")
//
//        val hasTypingSpeedDataAfterEnable =
//            secondResult.any { record ->
//                record.type == Record.Type.BEHAVIORAL_TELEMETRY && record.containsKey(typingSpeedKey)
//            }
//
//        Assertions.assertTrue(
//            hasTypingSpeedDataAfterEnable,
//            "collectData should return typing speed data after re-enabling the TypingSpeed module",
//        )
    }

    fun testFileContextRetrievalModule() {
        val file = myFixture.configureByFile("telemetry/TypingAbcd.kt")
        myFixture.type("abcd")

        // Get the FileContextRetrievalModule's preference ID
        val fileContextModule = FileContextRetrievalModule()
        val fileContextModuleId = fileContextModule.getPreferenceId()

        // Set up the request the same way as in testCollectData
        val psiFile = file
        val editor = myFixture.editor
        val document = editor.document
        val startOffset = myFixture.caretOffset
        val endOffset = startOffset

        val caretOffset = myFixture.caretOffset
        val typedText = "\n"
        val range = TextRange(caretOffset, caretOffset + typedText.length)

        val typingEvent = TypingEvent.NewLine(typedText, range)

        val event =
            InlineCompletionEvent.DocumentChange(
                typing = typingEvent,
                editor = myFixture.editor,
            )

        val request =
            InlineCompletionRequest(
                event = event,
                file = psiFile,
                editor = editor,
                document = document,
                startOffset = startOffset,
                endOffset = endOffset,
            )

        // First, disable the entire module
        PrefState.Companion.disableModule(fileContextModuleId)

        // Call collectData
        val result = getModuleManager(project).collectData(request)
        Assertions.assertNotNull(result, "collectData should not return null")

        // Define keys for all FileContextRetrievalModule data points
        val fileContentsKey = Record.Companion.key<String>("file_contents")
        val prefixKey = Record.Companion.key<String>("prefix")
        val suffixKey = Record.Companion.key<String>("suffix")
        val fileNameKey = Record.Companion.key<String>("file_name")

        // Check that no record contains any FileContextRetrievalModule data
        val hasFileContextData =
            result.any { record ->
                record.type == Record.Type.CONTEXT &&
                        (
                                record.containsKey(fileContentsKey) ||
                                        record.containsKey(prefixKey) ||
                                        record.containsKey(suffixKey) ||
                                        record.containsKey(fileNameKey)
                                )
            }

        Assertions.assertFalse(
            hasFileContextData,
            "collectData should not return any file context data when the FileContextRetrievalModule is disabled",
        )

        // Now re-enable the module
        PrefState.Companion.enableModule(fileContextModuleId)

        // But disable specific data points: prefix and suffix
        PrefState.Companion.setPreferenceValue(fileContextModuleId, "context.include.prefix", "false")
        PrefState.Companion.setPreferenceValue(fileContextModuleId, "context.include.suffix", "false")

        // Call collectData again
        val secondResult = getModuleManager(project).collectData(request)
        Assertions.assertNotNull(secondResult, "collectData should not return null after re-enabling the module")

        // Find the CONTEXT record
        val contextRecord = secondResult.find { it.type == Record.Type.CONTEXT }
        Assertions.assertNotNull(
            contextRecord,
            "collectData should return a CONTEXT record when FileContextRetrievalModule is enabled",
        )

        // Verify that prefix and suffix are not included
        Assertions.assertFalse(
            contextRecord!!.containsKey(prefixKey),
            "collectData should not return prefix data when it's disabled",
        )
        Assertions.assertFalse(
            contextRecord.containsKey(suffixKey),
            "collectData should not return suffix data when it's disabled",
        )

        // But file_contents and file_name should be included
        Assertions.assertTrue(
            contextRecord.containsKey(fileContentsKey),
            "collectData should return file_contents data when it's enabled",
        )
        Assertions.assertTrue(
            contextRecord.containsKey(fileNameKey),
            "collectData should return file_name data when it's enabled",
        )

        // Reset preferences for other tests
        PrefState.Companion.setPreferenceValue(fileContextModuleId, "context.include.prefix", "true")
        PrefState.Companion.setPreferenceValue(fileContextModuleId, "context.include.suffix", "true")
    }
}
