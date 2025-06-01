package me.code4me.utils.configuration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PreferencesTest {
    @Test
    fun testPreferenceDataClass() {
        // Test creating a Preference object with all parameters
        val preference =
            Preference(
                key = "testKey",
                type = PreferenceType.STRING,
                defaultValue = "defaultValue",
                displayName = "Test Preference",
                description = "This is a test preference",
            )

        // Verify all properties are correctly set
        assertEquals("testKey", preference.key)
        assertEquals(PreferenceType.STRING, preference.type)
        assertEquals("defaultValue", preference.defaultValue)
        assertEquals("Test Preference", preference.displayName)
        assertEquals("This is a test preference", preference.description)

        // Test creating a Preference object without description
        val preferenceNoDesc =
            Preference(
                key = "testKey2",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Test Boolean",
            )

        // Verify default value for description is empty string
        assertEquals("", preferenceNoDesc.description)
    }

    @Test
    fun testPreferenceType() {
        // Test all enum values
        assertEquals("boolean", PreferenceType.BOOLEAN.type)
        assertEquals("string", PreferenceType.STRING.type)
        assertEquals("int", PreferenceType.INT.type)
        assertEquals("float", PreferenceType.FLOAT.type)
        assertEquals("long", PreferenceType.LONG.type)
        assertEquals("double", PreferenceType.DOUBLE.type)
        assertEquals("list", PreferenceType.LIST.type)
        assertEquals("map", PreferenceType.MAP.type)
    }

    @Test
    fun testPreferenceClass() {
        // Test all enum values
        assertEquals("module", PreferenceClass.MODULE.type)
        assertEquals("behavioraltelemetry", PreferenceClass.BEHAVIORALTELEMETRY.type)
        assertEquals("contextualtelemetry", PreferenceClass.CONTEXTUALTELEMETRY.type)
        assertEquals("context", PreferenceClass.CONTEXT.type)
        assertEquals("auth", PreferenceClass.AUTH.type)
    }

    @Test
    fun testPreferenceCapable() {
        // Create a test implementation of PreferenceCapable
        val testPreferenceCapable =
            object : PreferenceCapable {
                override fun getPreferenceList(): List<Preference> {
                    return listOf(
                        Preference(
                            key = "testKey",
                            type = PreferenceType.STRING,
                            defaultValue = "defaultValue",
                            displayName = "Test Preference",
                        ),
                    )
                }

                override fun getPreferenceClass(): PreferenceClass {
                    return PreferenceClass.MODULE
                }
            }

        // Test getPreferenceList
        val preferences = testPreferenceCapable.getPreferenceList()
        assertEquals(1, preferences.size)
        assertEquals("testKey", preferences[0].key)

        // Test getPreferenceClass
        assertEquals(PreferenceClass.MODULE, testPreferenceCapable.getPreferenceClass())

        // Test getPreferenceId (default implementation)
        // For anonymous objects, the class name might vary or be empty
        // Just verify it's not null
        assertNotNull(testPreferenceCapable.getPreferenceId())
    }
}
