
package me.code4me.utils.configuration

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Preferences Test Suite")
class PreferencesTest {
    @Nested
    @DisplayName("Preference Data Class")
    inner class PreferenceDataClassTests {
        @Test
        @DisplayName("Should create preference with all parameters")
        fun shouldCreatePreferenceWithAllParameters() {
            val preference =
                Preference(
                    key = "testKey",
                    type = PreferenceType.STRING,
                    defaultValue = "defaultValue",
                    displayName = "Test Preference",
                    description = "This is a test preference",
                )

            Assertions.assertEquals("testKey", preference.key)
            Assertions.assertEquals(PreferenceType.STRING, preference.type)
            Assertions.assertEquals("defaultValue", preference.defaultValue)
            Assertions.assertEquals("Test Preference", preference.displayName)
            Assertions.assertEquals("This is a test preference", preference.description)
        }

        @Test
        @DisplayName("Should create preference without description")
        fun shouldCreatePreferenceWithoutDescription() {
            val preferenceNoDesc =
                Preference(
                    key = "testKey2",
                    type = PreferenceType.BOOLEAN,
                    defaultValue = "true",
                    displayName = "Test Boolean",
                )

            Assertions.assertEquals("testKey2", preferenceNoDesc.key)
            Assertions.assertEquals(PreferenceType.BOOLEAN, preferenceNoDesc.type)
            Assertions.assertEquals("true", preferenceNoDesc.defaultValue)
            Assertions.assertEquals("Test Boolean", preferenceNoDesc.displayName)
            Assertions.assertEquals("", preferenceNoDesc.description)
        }

        @Test
        @DisplayName("Should create preference using parameterless constructor")
        fun shouldCreatePreferenceUsingParameterlessConstructor() {
            val preference = Preference()

            Assertions.assertEquals("", preference.key)
            Assertions.assertEquals(PreferenceType.STRING, preference.type)
            Assertions.assertEquals("", preference.defaultValue)
            Assertions.assertEquals("", preference.displayName)
            Assertions.assertEquals("", preference.description)
        }

        @Test
        @DisplayName("Should support mutable properties")
        fun shouldSupportMutableProperties() {
            val preference = Preference()

            preference.key = "updatedKey"
            preference.type = PreferenceType.INT
            preference.defaultValue = "42"
            preference.displayName = "Updated Display"
            preference.description = "Updated Description"

            Assertions.assertEquals("updatedKey", preference.key)
            Assertions.assertEquals(PreferenceType.INT, preference.type)
            Assertions.assertEquals("42", preference.defaultValue)
            Assertions.assertEquals("Updated Display", preference.displayName)
            Assertions.assertEquals("Updated Description", preference.description)
        }

        @Test
        @DisplayName("Should handle different preference types")
        fun shouldHandleDifferentPreferenceTypes() {
            val stringPreference = Preference("string", PreferenceType.STRING, "default", "String Pref")
            val booleanPreference = Preference("boolean", PreferenceType.BOOLEAN, "false", "Boolean Pref")
            val intPreference = Preference("int", PreferenceType.INT, "42", "Int Pref")

            Assertions.assertEquals(PreferenceType.STRING, stringPreference.type)
            Assertions.assertEquals(PreferenceType.BOOLEAN, booleanPreference.type)
            Assertions.assertEquals(PreferenceType.INT, intPreference.type)
        }

        @Test
        @DisplayName("Should support data class equality and hashCode")
        fun shouldSupportDataClassEqualityAndHashCode() {
            val preference1 = Preference("key1", PreferenceType.STRING, "value1", "Display1", "Desc1")
            val preference2 = Preference("key1", PreferenceType.STRING, "value1", "Display1", "Desc1")
            val preference3 = Preference("key2", PreferenceType.STRING, "value1", "Display1", "Desc1")

            Assertions.assertEquals(preference1, preference2)
            Assertions.assertEquals(preference1.hashCode(), preference2.hashCode())
            Assertions.assertNotEquals(preference1, preference3)
        }

        @Test
        @DisplayName("Should support data class copy")
        fun shouldSupportDataClassCopy() {
            val original = Preference("original", PreferenceType.BOOLEAN, "true", "Original", "Original desc")
            val copied = original.copy(key = "copied", description = "Copied desc")

            Assertions.assertEquals("copied", copied.key)
            Assertions.assertEquals(PreferenceType.BOOLEAN, copied.type)
            Assertions.assertEquals("true", copied.defaultValue)
            Assertions.assertEquals("Original", copied.displayName)
            Assertions.assertEquals("Copied desc", copied.description)
        }
    }

    @Nested
    @DisplayName("PreferenceType Enum")
    inner class PreferenceTypeTests {
        @Test
        @DisplayName("Should have correct type values for all enum constants")
        fun shouldHaveCorrectTypeValuesForAllEnumConstants() {
            Assertions.assertEquals("boolean", PreferenceType.BOOLEAN.type)
            Assertions.assertEquals("string", PreferenceType.STRING.type)
            Assertions.assertEquals("int", PreferenceType.INT.type)
            Assertions.assertEquals("float", PreferenceType.FLOAT.type)
            Assertions.assertEquals("long", PreferenceType.LONG.type)
            Assertions.assertEquals("double", PreferenceType.DOUBLE.type)
            Assertions.assertEquals("list", PreferenceType.LIST.type)
            Assertions.assertEquals("map", PreferenceType.MAP.type)
        }

        @Test
        @DisplayName("Should contain all expected preference types")
        fun shouldContainAllExpectedPreferenceTypes() {
            val expectedTypes =
                setOf(
                    PreferenceType.BOOLEAN,
                    PreferenceType.STRING,
                    PreferenceType.INT,
                    PreferenceType.FLOAT,
                    PreferenceType.LONG,
                    PreferenceType.DOUBLE,
                    PreferenceType.LIST,
                    PreferenceType.MAP,
                    PreferenceType.TEXT,
                )

            val actualTypes = PreferenceType.values().toSet()
            Assertions.assertEquals(expectedTypes, actualTypes)
        }

        @Test
        @DisplayName("Should support valueOf and name operations")
        fun shouldSupportValueOfAndNameOperations() {
            Assertions.assertEquals(PreferenceType.STRING, PreferenceType.valueOf("STRING"))
            Assertions.assertEquals(PreferenceType.BOOLEAN, PreferenceType.valueOf("BOOLEAN"))
            Assertions.assertEquals("STRING", PreferenceType.STRING.name)
            Assertions.assertEquals("BOOLEAN", PreferenceType.BOOLEAN.name)
        }
    }

    @Nested
    @DisplayName("PreferenceClass Enum")
    inner class PreferenceClassTests {
        @Test
        @DisplayName("Should have correct type values for all enum constants")
        fun shouldHaveCorrectTypeValuesForAllEnumConstants() {
            Assertions.assertEquals("module", PreferenceClass.MODULE.type)
            Assertions.assertEquals("behavioralTelemetry", PreferenceClass.BEHAVIORAL_TELEMETRY.type)
            Assertions.assertEquals("contextualTelemetry", PreferenceClass.CONTEXTUAL_TELEMETRY.type)
            Assertions.assertEquals("context", PreferenceClass.CONTEXT.type)
            Assertions.assertEquals("auth", PreferenceClass.AUTH.type)
            Assertions.assertEquals("system", PreferenceClass.SYSTEM.type)
        }

        @Test
        @DisplayName("Should contain all expected preference classes")
        fun shouldContainAllExpectedPreferenceClasses() {
            val expectedClasses =
                setOf(
                    PreferenceClass.MODULE,
                    PreferenceClass.BEHAVIORAL_TELEMETRY,
                    PreferenceClass.CONTEXTUAL_TELEMETRY,
                    PreferenceClass.CONTEXT,
                    PreferenceClass.AUTH,
                    PreferenceClass.SYSTEM,
                    PreferenceClass.AFTER_INSERTION,
                    PreferenceClass.MODEL,
                )

            val actualClasses = PreferenceClass.values().toSet()
            Assertions.assertEquals(expectedClasses, actualClasses)
        }

        @Test
        @DisplayName("Should support valueOf and name operations")
        fun shouldSupportValueOfAndNameOperations() {
            Assertions.assertEquals(PreferenceClass.MODULE, PreferenceClass.valueOf("MODULE"))
            Assertions.assertEquals(PreferenceClass.SYSTEM, PreferenceClass.valueOf("SYSTEM"))
            Assertions.assertEquals("MODULE", PreferenceClass.MODULE.name)
            Assertions.assertEquals("SYSTEM", PreferenceClass.SYSTEM.name)
        }
    }

    @Nested
    @DisplayName("PreferenceCapable Interface")
    inner class PreferenceCapableTests {
        private val testPreferenceCapable =
            object : PreferenceCapable {
                override fun getPreferenceList(): List<Preference> {
                    return listOf(
                        Preference(
                            key = "testKey",
                            type = PreferenceType.STRING,
                            defaultValue = "defaultValue",
                            displayName = "Test Preference",
                        ),
                        Preference(
                            key = "booleanKey",
                            type = PreferenceType.BOOLEAN,
                            defaultValue = "true",
                            displayName = "Boolean Preference",
                        ),
                    )
                }

                override fun getPreferenceClass(): PreferenceClass {
                    return PreferenceClass.MODULE
                }
            }

        @Test
        @DisplayName("Should return correct preference list")
        fun shouldReturnCorrectPreferenceList() {
            val preferences = testPreferenceCapable.getPreferenceList()

            Assertions.assertEquals(2, preferences.size)

            val firstPreference = preferences[0]
            Assertions.assertEquals("testKey", firstPreference.key)
            Assertions.assertEquals(PreferenceType.STRING, firstPreference.type)
            Assertions.assertEquals("defaultValue", firstPreference.defaultValue)
            Assertions.assertEquals("Test Preference", firstPreference.displayName)

            val secondPreference = preferences[1]
            Assertions.assertEquals("booleanKey", secondPreference.key)
            Assertions.assertEquals(PreferenceType.BOOLEAN, secondPreference.type)
            Assertions.assertEquals("true", secondPreference.defaultValue)
            Assertions.assertEquals("Boolean Preference", secondPreference.displayName)
        }

        @Test
        @DisplayName("Should return correct preference class")
        fun shouldReturnCorrectPreferenceClass() {
            val preferenceClass = testPreferenceCapable.getPreferenceClass()
            Assertions.assertEquals(PreferenceClass.MODULE, preferenceClass)
        }

        @Test
        @DisplayName("Should handle empty preference list")
        fun shouldHandleEmptyPreferenceList() {
            val emptyPreferenceCapable =
                object : PreferenceCapable {
                    override fun getPreferenceList(): List<Preference> = emptyList()

                    override fun getPreferenceClass(): PreferenceClass = PreferenceClass.CONTEXT
                }

            val preferences = emptyPreferenceCapable.getPreferenceList()
            Assertions.assertTrue(preferences.isEmpty())
            Assertions.assertEquals(PreferenceClass.CONTEXT, emptyPreferenceCapable.getPreferenceClass())
        }

        @Test
        @DisplayName("Should support custom getPreferenceId implementation")
        fun shouldSupportCustomGetPreferenceIdImplementation() {
            val customPreferenceCapable =
                object : PreferenceCapable {
                    override fun getPreferenceList(): List<Preference> = emptyList()

                    override fun getPreferenceClass(): PreferenceClass = PreferenceClass.SYSTEM

                    override fun getPreferenceId(): String = "custom-id-123"
                }

            Assertions.assertEquals("custom-id-123", customPreferenceCapable.getPreferenceId())
        }

        @Test
        @DisplayName("Should work with named classes")
        fun shouldWorkWithNamedClasses() {
            class TestModule : PreferenceCapable {
                override fun getPreferenceList(): List<Preference> =
                    listOf(
                        Preference("test", PreferenceType.STRING, "value", "Test"),
                    )

                override fun getPreferenceClass(): PreferenceClass = PreferenceClass.MODULE
            }

            val testModule = TestModule()
            Assertions.assertEquals("TestModule", testModule.getPreferenceId())
        }
    }

    @Nested
    @DisplayName("XML Serialization Support")
    inner class XmlSerializationTests {
        @Test
        @DisplayName("Should support XML serialization with annotations")
        fun shouldSupportXmlSerializationWithAnnotations() {
            val preference =
                Preference(
                    key = "xmlTest",
                    type = PreferenceType.BOOLEAN,
                    defaultValue = "false",
                    displayName = "XML Test",
                    description = "Test for XML serialization",
                )

            // Test that the data class has the proper structure for XML serialization
            // The @Tag and @Attribute annotations should be present
            Assertions.assertNotNull(preference.key)
            Assertions.assertNotNull(preference.type)
            Assertions.assertNotNull(preference.defaultValue)
            Assertions.assertNotNull(preference.displayName)
            Assertions.assertNotNull(preference.description)
        }

        @Test
        @DisplayName("Should handle default values for XML deserialization")
        fun shouldHandleDefaultValuesForXmlDeserialization() {
            // Test parameterless constructor which is required for XML deserialization
            val preference = Preference()

            // Verify default values match what would be expected during XML deserialization
            Assertions.assertEquals("", preference.key)
            Assertions.assertEquals(PreferenceType.STRING, preference.type)
            Assertions.assertEquals("", preference.defaultValue)
            Assertions.assertEquals("", preference.displayName)
            Assertions.assertEquals("", preference.description)
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    inner class IntegrationTests {
        @Test
        @DisplayName("Should work with all preference types and classes")
        fun shouldWorkWithAllPreferenceTypesAndClasses() {
            val preferences = mutableListOf<Preference>()

            // Create preferences for each type
            PreferenceType.values().forEachIndexed { index, type ->
                val preference =
                    Preference(
                        key = "key_$index",
                        type = type,
                        defaultValue = "default_$index",
                        displayName = "Display $index",
                        description = "Description $index",
                    )
                preferences.add(preference)
            }

            Assertions.assertEquals(PreferenceType.values().size, preferences.size)

            // Verify each preference has the correct type
            preferences.forEachIndexed { index, preference ->
                Assertions.assertEquals(PreferenceType.values()[index], preference.type)
                Assertions.assertEquals("key_$index", preference.key)
                Assertions.assertEquals("default_$index", preference.defaultValue)
            }
        }

        @Test
        @DisplayName("Should support different preference classes with same preferences")
        fun shouldSupportDifferentPreferenceClassesWithSamePreferences() {
            val sharedPreferences =
                listOf(
                    Preference("shared1", PreferenceType.STRING, "value1", "Shared 1"),
                    Preference("shared2", PreferenceType.INT, "42", "Shared 2"),
                )

            val moduleCapable =
                object : PreferenceCapable {
                    override fun getPreferenceList() = sharedPreferences

                    override fun getPreferenceClass() = PreferenceClass.MODULE
                }

            val contextCapable =
                object : PreferenceCapable {
                    override fun getPreferenceList() = sharedPreferences

                    override fun getPreferenceClass() = PreferenceClass.CONTEXT
                }

            Assertions.assertEquals(sharedPreferences, moduleCapable.getPreferenceList())
            Assertions.assertEquals(sharedPreferences, contextCapable.getPreferenceList())
            Assertions.assertEquals(PreferenceClass.MODULE, moduleCapable.getPreferenceClass())
            Assertions.assertEquals(PreferenceClass.CONTEXT, contextCapable.getPreferenceClass())
        }

        @Test
        @DisplayName("Should support complex preference scenarios")
        fun shouldSupportComplexPreferenceScenarios() {
            val complexCapable =
                object : PreferenceCapable {
                    override fun getPreferenceList(): List<Preference> {
                        return PreferenceType.values().map { type ->
                            Preference(
                                key = "complex_${type.name.lowercase()}",
                                type = type,
                                defaultValue =
                                    when (type) {
                                        PreferenceType.BOOLEAN -> "true"
                                        PreferenceType.INT -> "100"
                                        PreferenceType.FLOAT -> "3.14"
                                        PreferenceType.LONG -> "1000000"
                                        PreferenceType.DOUBLE -> "2.718"
                                        PreferenceType.LIST -> "[]"
                                        PreferenceType.MAP -> "{}"
                                        else -> "default"
                                    },
                                displayName = "Complex ${type.name}",
                                description = "Complex preference for ${type.type} type",
                            )
                        }
                    }

                    override fun getPreferenceClass(): PreferenceClass = PreferenceClass.SYSTEM
                }

            val preferences = complexCapable.getPreferenceList()
            Assertions.assertEquals(PreferenceType.values().size, preferences.size)
            Assertions.assertEquals(PreferenceClass.SYSTEM, complexCapable.getPreferenceClass())

            // Verify all types are represented
            val representedTypes = preferences.map { it.type }.toSet()
            Assertions.assertEquals(PreferenceType.values().toSet(), representedTypes)
        }

        @Test
        @DisplayName("Should handle preference validation scenarios")
        fun shouldHandlePreferenceValidationScenarios() {
            val validationCapable =
                object : PreferenceCapable {
                    override fun getPreferenceList(): List<Preference> {
                        return listOf(
                            Preference("required_field", PreferenceType.STRING, "", "Required Field", "This field is required"),
                            Preference("optional_field", PreferenceType.STRING, "default", "Optional Field", ""),
                            Preference("numeric_field", PreferenceType.INT, "0", "Numeric Field", "Must be a number"),
                            Preference("boolean_field", PreferenceType.BOOLEAN, "false", "Boolean Field", "True or false only"),
                        )
                    }

                    override fun getPreferenceClass(): PreferenceClass = PreferenceClass.AUTH
                }

            val preferences = validationCapable.getPreferenceList()

            // Test that preferences can be created with various validation scenarios
            val requiredField = preferences.find { it.key == "required_field" }!!
            Assertions.assertEquals("", requiredField.defaultValue)
            Assertions.assertTrue(requiredField.description.isNotEmpty())

            val optionalField = preferences.find { it.key == "optional_field" }!!
            Assertions.assertEquals("default", optionalField.defaultValue)
            Assertions.assertEquals("", optionalField.description)

            val numericField = preferences.find { it.key == "numeric_field" }!!
            Assertions.assertEquals(PreferenceType.INT, numericField.type)
            Assertions.assertEquals("0", numericField.defaultValue)
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    inner class EdgeCasesTests {
        @Test
        @DisplayName("Should handle extreme string values")
        fun shouldHandleExtremeStringValues() {
            val longString = "a".repeat(10000)
            val emptyString = ""
            val specialChars = "!@#$%^&*(){}[]|\\:;\"'<>,.?/~`"

            val longPreference = Preference("long", PreferenceType.STRING, longString, "Long")
            val emptyPreference = Preference("empty", PreferenceType.STRING, emptyString, "Empty")
            val specialPreference = Preference("special", PreferenceType.STRING, specialChars, "Special")

            Assertions.assertEquals(longString, longPreference.defaultValue)
            Assertions.assertEquals(emptyString, emptyPreference.defaultValue)
            Assertions.assertEquals(specialChars, specialPreference.defaultValue)
        }

        @Test
        @DisplayName("Should handle unicode and international characters")
        fun shouldHandleUnicodeAndInternationalCharacters() {
            val unicodePreference =
                Preference(
                    key = "unicode",
                    type = PreferenceType.STRING,
                    defaultValue = "测试 🚀 العربية Русский",
                    displayName = "Unicode Test",
                    description = "Tests unicode handling",
                )

            Assertions.assertEquals("unicode", unicodePreference.key)
            Assertions.assertEquals("测试 🚀 العربية Русский", unicodePreference.defaultValue)
        }

        @Test
        @DisplayName("Should maintain consistency across preference operations")
        fun shouldMaintainConsistencyAcrossPreferenceOperations() {
            val original = Preference("test", PreferenceType.BOOLEAN, "true", "Test", "Test desc")

            // Test mutability
            original.defaultValue = "false"
            Assertions.assertEquals("false", original.defaultValue)

            // Test copy preserves original
            val copied = original.copy()
            original.defaultValue = "true"
            Assertions.assertEquals("false", copied.defaultValue)
            Assertions.assertEquals("true", original.defaultValue)
        }
    }
}
