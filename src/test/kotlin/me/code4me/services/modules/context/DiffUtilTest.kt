package me.code4me.services.modules.context

import computeLineDiffs
import me.code4me.api.generated.model.ContextChangeType
import me.code4me.services.modules.context.MultiFileContextRetrievalModule.FileContextChangeData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import toApiModel

class DiffUtilTest {
    @Nested
    @DisplayName("toApiModel Extension Function Tests")
    inner class ToApiModelTests {
        @Test
        @DisplayName("Should convert insert change type correctly")
        fun shouldConvertInsertChangeTypeCorrectly() {
            val internalData =
                FileContextChangeData(
                    changeType = "insert",
                    startLine = 1,
                    endLine = 3,
                    newLines = listOf("line1", "line2"),
                )

            val result = internalData.toApiModel()

            assertEquals(ContextChangeType.insert, result.changeType)
            assertEquals(1, result.startLine)
            assertEquals(3, result.endLine)
            assertEquals(listOf("line1", "line2"), result.newLines)
        }

        @Test
        @DisplayName("Should convert delete change type to remove")
        fun shouldConvertDeleteChangeTypeToRemove() {
            val internalData =
                FileContextChangeData(
                    changeType = "delete",
                    startLine = 5,
                    endLine = 10,
                    newLines = emptyList(),
                )

            val result = internalData.toApiModel()

            assertEquals(ContextChangeType.remove, result.changeType)
            assertEquals(5, result.startLine)
            assertEquals(10, result.endLine)
            assertEquals(emptyList<String>(), result.newLines)
        }

        @Test
        @DisplayName("Should convert replace change type to update")
        fun shouldConvertReplaceChangeTypeToUpdate() {
            val internalData =
                FileContextChangeData(
                    changeType = "replace",
                    startLine = 2,
                    endLine = 4,
                    newLines = listOf("new line"),
                )

            val result = internalData.toApiModel()

            assertEquals(ContextChangeType.update, result.changeType)
        }

        @Test
        @DisplayName("Should convert update change type correctly")
        fun shouldConvertUpdateChangeTypeCorrectly() {
            val internalData =
                FileContextChangeData(
                    changeType = "update",
                    startLine = 0,
                    endLine = 1,
                    newLines = listOf("updated line"),
                )

            val result = internalData.toApiModel()

            assertEquals(ContextChangeType.update, result.changeType)
        }

        @Test
        @DisplayName("Should handle case insensitive change types")
        fun shouldHandleCaseInsensitiveChangeTypes() {
            val testCases =
                listOf(
                    "INSERT" to ContextChangeType.insert,
                    "Insert" to ContextChangeType.insert,
                    "DELETE" to ContextChangeType.remove,
                    "Delete" to ContextChangeType.remove,
                    "REPLACE" to ContextChangeType.update,
                    "Replace" to ContextChangeType.update,
                )

            testCases.forEach { (input, expected) ->
                val internalData =
                    FileContextChangeData(
                        changeType = input,
                        startLine = 0,
                        endLine = 1,
                        newLines = emptyList(),
                    )

                val result = internalData.toApiModel()

                assertEquals(expected, result.changeType, "Failed for input: $input")
            }
        }

        @Test
        @DisplayName("Should default to update for unknown change types")
        fun shouldDefaultToUpdateForUnknownChangeTypes() {
            val unknownTypes = listOf("unknown", "modify", "change", "")

            unknownTypes.forEach { unknownType ->
                val internalData =
                    FileContextChangeData(
                        changeType = unknownType,
                        startLine = 0,
                        endLine = 1,
                        newLines = emptyList(),
                    )

                val result = internalData.toApiModel()

                assertEquals(ContextChangeType.update, result.changeType, "Failed for unknown type: $unknownType")
            }
        }
    }

    @Nested
    @DisplayName("computeLineDiffs Function Tests")
    inner class ComputeLineDiffsTests {
        @Test
        @DisplayName("Should return empty list for identical texts")
        fun shouldReturnEmptyListForIdenticalTexts() {
            val text = "line1\nline2\nline3"
            val result = computeLineDiffs(text, text)
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("Should handle empty old text as full insertion")
        fun shouldHandleEmptyOldTextAsFullInsertion() {
            val oldText = ""
            val newText = "line1\nline2\nline3"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("insert", change.changeType)
            assertEquals(0, change.startLine)
            assertEquals(3, change.endLine)
            assertEquals(listOf("line1", "line2", "line3"), change.newLines)
        }

        @Test
        @DisplayName("Should handle empty new text as full deletion")
        fun shouldHandleEmptyNewTextAsFullDeletion() {
            val oldText = "line1\nline2\nline3"
            val newText = ""

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("delete", change.changeType)
            assertEquals(0, change.startLine)
            assertEquals(3, change.endLine)
            assertEquals(emptyList<String>(), change.newLines)
        }

        @Test
        @DisplayName("Should detect middle section deletion")
        fun shouldDetectMiddleSectionDeletion() {
            val oldText = "line1\nline2\nline3\nline4\nline5"
            val newText = "line1\nline5"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("delete", change.changeType)
            assertEquals(1, change.startLine)
            assertEquals(4, change.endLine)
            assertEquals(emptyList<String>(), change.newLines)
        }

        @Test
        @DisplayName("Should detect middle section insertion")
        fun shouldDetectMiddleSectionInsertion() {
            val oldText = "line1\nline5"
            val newText = "line1\nline2\nline3\nline4\nline5"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("insert", change.changeType)
            assertEquals(1, change.startLine)
            assertEquals(1, change.endLine)
            assertEquals(listOf("line2", "line3", "line4"), change.newLines)
        }

        @Test
        @DisplayName("Should detect middle section replacement")
        fun shouldDetectMiddleSectionReplacement() {
            val oldText = "line1\noldline2\noldline3\nline4"
            val newText = "line1\nnewline2\nnewline3\nline4"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("replace", change.changeType)
            assertEquals(1, change.startLine)
            assertEquals(3, change.endLine)
            assertEquals(listOf("newline2", "newline3"), change.newLines)
        }

        @Test
        @DisplayName("Should handle beginning insertion")
        fun shouldHandleBeginningInsertion() {
            val oldText = "line3\nline4"
            val newText = "line1\nline2\nline3\nline4"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("insert", change.changeType)
            assertEquals(0, change.startLine)
            assertEquals(0, change.endLine)
            assertEquals(listOf("line1", "line2"), change.newLines)
        }

        @Test
        @DisplayName("Should handle end insertion")
        fun shouldHandleEndInsertion() {
            val oldText = "line1\nline2"
            val newText = "line1\nline2\nline3\nline4"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("insert", change.changeType)
            assertEquals(2, change.startLine)
            assertEquals(2, change.endLine)
            assertEquals(listOf("line3", "line4"), change.newLines)
        }

        @Test
        @DisplayName("Should handle beginning deletion")
        fun shouldHandleBeginningDeletion() {
            val oldText = "line1\nline2\nline3\nline4"
            val newText = "line3\nline4"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("delete", change.changeType)
            assertEquals(0, change.startLine)
            assertEquals(2, change.endLine)
            assertEquals(emptyList<String>(), change.newLines)
        }

        @Test
        @DisplayName("Should handle end deletion")
        fun shouldHandleEndDeletion() {
            val oldText = "line1\nline2\nline3\nline4"
            val newText = "line1\nline2"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("delete", change.changeType)
            assertEquals(2, change.startLine)
            assertEquals(4, change.endLine)
            assertEquals(emptyList<String>(), change.newLines)
        }

        @Test
        @DisplayName("Should handle Windows line endings")
        fun shouldHandleWindowsLineEndings() {
            val oldText = "line1\r\nline2\r\nline3"
            val newText = "line1\r\nnewline2\r\nline3"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("replace", change.changeType)
            assertEquals(1, change.startLine)
            assertEquals(2, change.endLine)
            assertEquals(listOf("newline2"), change.newLines)
        }

        @Test
        @DisplayName("Should handle mixed line endings")
        fun shouldHandleMixedLineEndings() {
            val oldText = "line1\r\nline2\nline3"
            val newText = "line1\nnewline2\r\nline3"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("replace", change.changeType)
            assertEquals(1, change.startLine)
            assertEquals(2, change.endLine)
            assertEquals(listOf("newline2"), change.newLines)
        }

        @Test
        @DisplayName("Should handle single line texts")
        fun shouldHandleSingleLineTexts() {
            val oldText = "single line"
            val newText = "modified single line"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("replace", change.changeType)
            assertEquals(0, change.startLine)
            assertEquals(1, change.endLine)
            assertEquals(listOf("modified single line"), change.newLines)
        }

        @Test
        @DisplayName("Should handle empty lines in the middle")
        fun shouldHandleEmptyLinesInMiddle() {
            val oldText = "line1\n\nline3"
            val newText = "line1\nnewline\nline3"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("replace", change.changeType)
            assertEquals(1, change.startLine)
            assertEquals(2, change.endLine)
            assertEquals(listOf("newline"), change.newLines)
        }

        @Test
        @DisplayName("Should handle complete replacement")
        fun shouldHandleCompleteReplacement() {
            val oldText = "old1\nold2\nold3"
            val newText = "new1\nnew2"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("replace", change.changeType)
            assertEquals(0, change.startLine)
            assertEquals(3, change.endLine)
            assertEquals(listOf("new1", "new2"), change.newLines)
        }

        @Test
        @DisplayName("Should handle complex multi-section changes")
        fun shouldHandleComplexMultiSectionChanges() {
            // This tests the algorithm's ability to find optimal matching sections
            val oldText = "same1\ndiff1\ndiff2\nsame2\nsame3\ndiff3\nsame4"
            val newText = "same1\nnew1\nnew2\nnew3\nsame2\nsame3\nnew4\nnew5\nsame4"

            val result = computeLineDiffs(oldText, newText)

            // Should detect this as a single replacement in the middle
            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("replace", change.changeType)
            assertEquals(1, change.startLine)
            assertEquals(6, change.endLine)
            assertEquals(listOf("new1", "new2", "new3", "same2", "same3", "new4", "new5"), change.newLines)
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    inner class EdgeCasesTests {
        @Test
        @DisplayName("Should handle null-like empty strings")
        fun shouldHandleNullLikeEmptyStrings() {
            val result1 = computeLineDiffs("", "")
            assertTrue(result1.isEmpty())

            val result2 = computeLineDiffs("\n", "")
            assertEquals(1, result2.size)
            assertEquals("delete", result2[0].changeType)

            val result3 = computeLineDiffs("", "\n")
            assertEquals(1, result3.size)
            assertEquals("insert", result3[0].changeType)
        }

        @Test
        @DisplayName("Should handle very large texts efficiently")
        fun shouldHandleVeryLargeTextsEfficiently() {
            val largeOldText = (1..1000).joinToString("\n") { "oldline$it" }
            val largeNewText = (1..1000).joinToString("\n") { "newline$it" }

            val result = computeLineDiffs(largeOldText, largeNewText)

            assertEquals(1, result.size)
            assertEquals("replace", result[0].changeType)
        }

        @Test
        @DisplayName("Should handle texts with only whitespace differences")
        fun shouldHandleTextsWithOnlyWhitespaceDifferences() {
            val oldText = "line1\nline2\nline3"
            val newText = "line1 \nline2\t\n line3"

            val result = computeLineDiffs(oldText, newText)

            // Should detect differences in whitespace
            assertTrue(result.isNotEmpty())
        }

        @Test
        @DisplayName("Should handle unicode characters correctly")
        fun shouldHandleUnicodeCharactersCorrectly() {
            val oldText = "línea1\n日本語\némoji😀"
            val newText = "línea1\n中文\némoji🎉"

            val result = computeLineDiffs(oldText, newText)

            assertEquals(1, result.size)
            val change = result[0]
            assertEquals("replace", change.changeType)
            assertEquals(1, change.startLine)
            assertEquals(3, change.endLine)
            assertEquals(listOf("中文", "émoji🎉"), change.newLines)
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    inner class IntegrationTests {
        @Test
        @DisplayName("Should work correctly with toApiModel conversion")
        fun shouldWorkCorrectlyWithToApiModelConversion() {
            val oldText = "line1\nline2\nline3"
            val newText = "line1\nnewline2\nline3"

            val internalResult = computeLineDiffs(oldText, newText)
            val apiResult = internalResult.map { it.toApiModel() }

            assertEquals(1, apiResult.size)
            val apiChange = apiResult[0]
            assertEquals(ContextChangeType.update, apiChange.changeType)
            assertEquals(1, apiChange.startLine)
            assertEquals(2, apiChange.endLine)
            assertEquals(listOf("newline2"), apiChange.newLines)
        }

        @Test
        @DisplayName("Should maintain consistency across multiple operations")
        fun shouldMaintainConsistencyAcrossMultipleOperations() {
            val texts =
                listOf(
                    "",
                    "single",
                    "line1\nline2",
                    "line1\nline2\nline3",
                    "a\nb\nc\nd\ne",
                )

            // Test all combinations
            for (oldText in texts) {
                for (newText in texts) {
                    val result = computeLineDiffs(oldText, newText)

                    // Basic consistency checks
                    if (oldText == newText) {
                        assertTrue(result.isEmpty(), "Identical texts should produce no changes")
                    } else {
                        assertTrue(result.isNotEmpty(), "Different texts should produce changes")
                    }

                    // Each change should have valid bounds
                    result.forEach { change ->
                        assertTrue(change.startLine >= 0, "Start line should be non-negative")
                        assertTrue(change.endLine >= change.startLine, "End line should be >= start line")
                        assertNotNull(change.newLines, "New lines should not be null")
                        assertTrue(
                            change.changeType in listOf("insert", "delete", "replace"),
                            "Change type should be valid",
                        )
                    }
                }
            }
        }
    }
}
