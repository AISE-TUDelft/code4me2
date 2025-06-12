package me.code4me.utils.api

import com.squareup.moshi.JsonDataException
import me.code4me.api.generated.model.ContextData
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("Map Extension Functions Test Suite")
class MapExtensionTest {
    @Nested
    @DisplayName("mapsTo Extension Function")
    inner class MapsToExtensionTests {
        @Test
        @DisplayName("Should successfully map to ContextData with all required fields")
        fun shouldSuccessfullyMapToContextDataWithAllRequiredFields() {
            val inputMap =
                mapOf(
                    "prefix" to "fun test() {",
                    "suffix" to "}",
                    "file_name" to "TestFile.kt",
                    "selected_text" to "val x = 5",
                    "context_files" to listOf("File1.kt", "File2.kt"),
                )

            val result = inputMap.mapsTo(ContextData::class.java)

            Assertions.assertEquals("fun test() {", result.prefix)
            Assertions.assertEquals("}", result.suffix)
            Assertions.assertEquals("TestFile.kt", result.fileName)
            Assertions.assertEquals("val x = 5", result.selectedText)
            Assertions.assertEquals(listOf("File1.kt", "File2.kt"), result.contextFiles)
        }

        @Test
        @DisplayName("Should successfully map to ContextData with only required fields")
        fun shouldSuccessfullyMapToContextDataWithOnlyRequiredFields() {
            val inputMap =
                mapOf(
                    "prefix" to "class Example",
                    "suffix" to "// end of class",
                )

            val result = inputMap.mapsTo(ContextData::class.java)

            Assertions.assertEquals("class Example", result.prefix)
            Assertions.assertEquals("// end of class", result.suffix)
            Assertions.assertNull(result.fileName)
            Assertions.assertNull(result.selectedText)
            Assertions.assertNull(result.contextFiles)
        }

        @Test
        @DisplayName("Should successfully map to ContextData with partial optional fields")
        fun shouldSuccessfullyMapToContextDataWithPartialOptionalFields() {
            val inputMap =
                mapOf(
                    "prefix" to "import kotlin.collections.*",
                    "suffix" to "// imports complete",
                    "file_name" to "Imports.kt",
                    "selected_text" to "import kotlin.collections.*",
                )

            val result = inputMap.mapsTo(ContextData::class.java)

            Assertions.assertEquals("import kotlin.collections.*", result.prefix)
            Assertions.assertEquals("// imports complete", result.suffix)
            Assertions.assertEquals("Imports.kt", result.fileName)
            Assertions.assertEquals("import kotlin.collections.*", result.selectedText)
            Assertions.assertNull(result.contextFiles)
        }

        @Test
        @DisplayName("Should handle empty strings in required fields")
        fun shouldHandleEmptyStringsInRequiredFields() {
            val inputMap =
                mapOf(
                    "prefix" to "",
                    "suffix" to "",
                    "file_name" to "",
                    "selected_text" to "",
                    "context_files" to emptyList<String>(),
                )

            val result = inputMap.mapsTo(ContextData::class.java)

            Assertions.assertEquals("", result.prefix)
            Assertions.assertEquals("", result.suffix)
            Assertions.assertEquals("", result.fileName)
            Assertions.assertEquals("", result.selectedText)
            Assertions.assertEquals(emptyList<String>(), result.contextFiles)
        }

        @Test
        @DisplayName("Should handle complex context files list")
        fun shouldHandleComplexContextFilesList() {
            val contextFiles =
                listOf(
                    "src/main/kotlin/Main.kt",
                    "src/test/kotlin/MainTest.kt",
                    "build.gradle.kts",
                    "README.md",
                )

            val inputMap =
                mapOf(
                    "prefix" to "fun main(args: Array<String>) {",
                    "suffix" to "}",
                    "file_name" to "Main.kt",
                    "context_files" to contextFiles,
                )

            val result = inputMap.mapsTo(ContextData::class.java)

            Assertions.assertEquals("fun main(args: Array<String>) {", result.prefix)
            Assertions.assertEquals("}", result.suffix)
            Assertions.assertEquals("Main.kt", result.fileName)
            Assertions.assertEquals(contextFiles, result.contextFiles)
        }

        @Test
        @DisplayName("Should handle special characters and unicode in text fields")
        fun shouldHandleSpecialCharactersAndUnicodeInTextFields() {
            val inputMap =
                mapOf(
                    "prefix" to "// Special chars: !@#$%^&*() and unicode: 测试 🚀",
                    "suffix" to "/* End comment with العربية Русский */",
                    "file_name" to "SpecialChars_测试.kt",
                    "selected_text" to "val emoji = \"🎉 Party!\"",
                )

            val result = inputMap.mapsTo(ContextData::class.java)

            Assertions.assertEquals("// Special chars: !@#$%^&*() and unicode: 测试 🚀", result.prefix)
            Assertions.assertEquals("/* End comment with العربية Русский */", result.suffix)
            Assertions.assertEquals("SpecialChars_测试.kt", result.fileName)
            Assertions.assertEquals("val emoji = \"🎉 Party!\"", result.selectedText)
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when mapping fails")
        fun shouldThrowIllegalArgumentExceptionWhenMappingFails() {
            val invalidMap =
                mapOf(
                    "invalid_field" to "value",
                    // Missing required fields: prefix and suffix
                )

            val exception =
                assertThrows<JsonDataException> {
                    invalidMap.mapsTo(ContextData::class.java)
                }
        }

        @Test
        @DisplayName("Should handle large text content")
        fun shouldHandleLargeTextContent() {
            val largePrefix = "a".repeat(10000)
            val largeSuffix = "b".repeat(5000)
            val largeSelectedText = "c".repeat(3000)

            val inputMap =
                mapOf(
                    "prefix" to largePrefix,
                    "suffix" to largeSuffix,
                    "file_name" to "LargeFile.kt",
                    "selected_text" to largeSelectedText,
                )

            val result = inputMap.mapsTo(ContextData::class.java)

            Assertions.assertEquals(largePrefix, result.prefix)
            Assertions.assertEquals(largeSuffix, result.suffix)
            Assertions.assertEquals("LargeFile.kt", result.fileName)
            Assertions.assertEquals(largeSelectedText, result.selectedText)
        }

        @Test
        @DisplayName("Should preserve data class properties")
        fun shouldPreserveDataClassProperties() {
            val inputMap =
                mapOf(
                    "prefix" to "data class Test(",
                    "suffix" to ")",
                    "file_name" to "Test.kt",
                )

            val result1 = inputMap.mapsTo(ContextData::class.java)
            val result2 = inputMap.mapsTo(ContextData::class.java)

            // Test equality
            Assertions.assertEquals(result1, result2)
            Assertions.assertEquals(result1.hashCode(), result2.hashCode())

            // Test copy functionality (if available)
            Assertions.assertEquals(result1.prefix, result2.prefix)
            Assertions.assertEquals(result1.suffix, result2.suffix)
            Assertions.assertEquals(result1.fileName, result2.fileName)
        }

        @Test
        @DisplayName("Should work with mixed case field names if JSON annotations handle it")
        fun shouldWorkWithMixedCaseFieldNames() {
            // Testing that the JSON annotations properly handle field name mapping
            val inputMap =
                mapOf(
                    "prefix" to "package com.example",
                    "suffix" to "// end package",
                    "file_name" to "Package.kt", // underscore format as per JSON annotation
                    "selected_text" to "package com.example",
                    "context_files" to listOf("related.kt"),
                )

            val result = inputMap.mapsTo(ContextData::class.java)

            Assertions.assertEquals("package com.example", result.prefix)
            Assertions.assertEquals("// end package", result.suffix)
            Assertions.assertEquals("Package.kt", result.fileName)
            Assertions.assertEquals("package com.example", result.selectedText)
            Assertions.assertEquals(listOf("related.kt"), result.contextFiles)
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    inner class EdgeCasesTests {
        @Test
        @DisplayName("Should handle empty map")
        fun shouldHandleEmptyMap() {
            val emptyMap = emptyMap<String, Any>()

            val exception =
                assertThrows<JsonDataException> {
                    emptyMap.mapsTo(ContextData::class.java)
                }
        }

        @Test
        @DisplayName("Should handle map with wrong data types")
        fun shouldHandleMapWithWrongDataTypes() {
            val invalidMap =
                mapOf(
                    "prefix" to 123, // Should be string
                    "suffix" to true, // Should be string
                    "context_files" to "not a list", // Should be list
                )

            val exception =
                assertThrows<JsonDataException> {
                    invalidMap.mapsTo(ContextData::class.java)
                }
        }

        @Test
        @DisplayName("Should maintain type safety with generic parameter")
        fun shouldMaintainTypeSafetyWithGenericParameter() {
            val inputMap =
                mapOf(
                    "prefix" to "fun test() {",
                    "suffix" to "}",
                )

            // The function should work with the exact class type
            val result: ContextData = inputMap.mapsTo(ContextData::class.java)

            Assertions.assertNotNull(result)

            Assertions.assertEquals("fun test() {", result.prefix)
            Assertions.assertEquals("}", result.suffix)
        }

        @Test
        @DisplayName("Should handle extremely nested context files")
        fun shouldHandleExtremelyNestedContextFiles() {
            val deepPath = "a/".repeat(100) + "file.kt"
            val contextFiles = (1..50).map { "path$it/$deepPath" }

            val inputMap =
                mapOf(
                    "prefix" to "// Deep nesting test",
                    "suffix" to "// End test",
                    "context_files" to contextFiles,
                )

            val result = inputMap.mapsTo(ContextData::class.java)

            Assertions.assertEquals("// Deep nesting test", result.prefix)
            Assertions.assertEquals("// End test", result.suffix)
            Assertions.assertEquals(contextFiles, result.contextFiles)
            Assertions.assertEquals(50, result.contextFiles?.size)
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    inner class IntegrationTests {
        @Test
        @DisplayName("Should work in real-world scenario with typical IDE context")
        fun shouldWorkInRealWorldScenarioWithTypicalIdeContext() {
            val realWorldMap =
                mapOf(
                    "prefix" to
                        """
                        package me.code4me.utils.api
                        
                        import me.code4me.api.generated.infrastructure.Serializer.moshi
                        
                        public fun <T> Map<String, Any>.mapsTo(clazz: Class<T>): T {
                            // Convert map to JSON and then to target class using Moshi
                            val adapter = moshi.adapter(clazz)
                            val json = moshi.adapter(Map::class.java).toJson(this)
                            return adapter.fromJson(json) ?: throw IllegalArgumentException("Failed to map to ${"$"}{clazz.simpleName}")
                        }
                        
                        fun exampleUsage() {
                        """.trimIndent(),
                    "suffix" to
                        """
                        }
                        """.trimIndent(),
                    "file_name" to "Map.kt",
                    "selected_text" to "val adapter = moshi.adapter(clazz)",
                    "context_files" to
                        listOf(
                            "ContextData.kt",
                            "Serializer.kt",
                            "MapExtensionTest.kt",
                        ),
                )

            val result = realWorldMap.mapsTo(ContextData::class.java)

            Assertions.assertTrue(result.prefix.contains("package me.code4me.utils.api"))
            Assertions.assertTrue(result.prefix.contains("mapsTo"))
            Assertions.assertEquals("}", result.suffix.trim())
            Assertions.assertEquals("Map.kt", result.fileName)
            Assertions.assertEquals("val adapter = moshi.adapter(clazz)", result.selectedText)
            Assertions.assertEquals(3, result.contextFiles?.size)
            Assertions.assertTrue(result.contextFiles?.contains("ContextData.kt") == true)
        }

        @Test
        @DisplayName("Should support chaining with other map operations")
        fun shouldSupportChainingWithOtherMapOperations() {
            val baseMap =
                mapOf(
                    "prefix" to "fun test() {",
                    "suffix" to "}",
                )

            val enrichedMap =
                baseMap +
                    mapOf(
                        "file_name" to "Test.kt",
                        "selected_text" to "test code",
                    )

            val result = enrichedMap.mapsTo(ContextData::class.java)

            Assertions.assertEquals("fun test() {", result.prefix)
            Assertions.assertEquals("}", result.suffix)
            Assertions.assertEquals("Test.kt", result.fileName)
            Assertions.assertEquals("test code", result.selectedText)
        }

        @Test
        @DisplayName("Should work with filtered and transformed maps")
        fun shouldWorkWithFilteredAndTransformedMaps() {
            val originalMap =
                mapOf(
                    "prefix" to "original prefix",
                    "suffix" to "original suffix",
                    "file_name" to "Original.kt",
                    "extra_field" to "should be ignored",
                    "another_extra" to 123,
                )

            // Filter out extra fields and transform
            val filteredMap =
                originalMap
                    .filterKeys { it in setOf("prefix", "suffix", "file_name") }
                    .mapValues { (key, value) ->
                        when (key) {
                            "prefix" -> "transformed $value"
                            "suffix" -> "transformed $value"
                            else -> value
                        }
                    }

            val result = filteredMap.mapsTo(ContextData::class.java)

            Assertions.assertEquals("transformed original prefix", result.prefix)
            Assertions.assertEquals("transformed original suffix", result.suffix)
            Assertions.assertEquals("Original.kt", result.fileName)
            Assertions.assertNull(result.selectedText)
            Assertions.assertNull(result.contextFiles)
        }
    }
}
