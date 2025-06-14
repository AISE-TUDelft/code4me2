package me.code4me.services.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefSettingsTest {
    @Test
    fun testPrefSettingsProperties() {
        // Create a new PrefSettings instance
        val prefSettings = PrefSettings()

        // Test default values
        assertFalse(prefSettings.storeContext)
        assertFalse(prefSettings.storeBehavioralTelemetry)
        assertFalse(prefSettings.storeContextualTelemetry)
        assertTrue(prefSettings.enabledModules.isEmpty())
        assertTrue(prefSettings.modulePreferences.isEmpty())
        assertTrue(prefSettings.moduleValues.isEmpty())

        // Test setting values
        prefSettings.storeBehavioralTelemetry = true
        prefSettings.storeContext = true
        prefSettings.storeContextualTelemetry = true

        // Verify values were set
        assertTrue(prefSettings.storeContext)
        assertTrue(prefSettings.storeBehavioralTelemetry)
        assertTrue(prefSettings.storeContextualTelemetry)
    }

    @Test
    fun testModuleValuesMap() {
        // Create a new PrefSettings instance
        val prefSettings = PrefSettings()

        // Add values to the moduleValues map
        prefSettings.moduleValues["module1.key1"] = "value1"
        prefSettings.moduleValues["module1.key2"] = "value2"
        prefSettings.moduleValues["module2.key1"] = "value3"

        // Verify values were added
        assertEquals(3, prefSettings.moduleValues.size)
        assertEquals("value1", prefSettings.moduleValues["module1.key1"])
        assertEquals("value2", prefSettings.moduleValues["module1.key2"])
        assertEquals("value3", prefSettings.moduleValues["module2.key1"])

        // Update a value
        prefSettings.moduleValues["module1.key1"] = "updatedValue"

        // Verify value was updated
        assertEquals("updatedValue", prefSettings.moduleValues["module1.key1"])

        // Remove a value
        prefSettings.moduleValues.remove("module1.key2")

        // Verify value was removed
        assertEquals(2, prefSettings.moduleValues.size)
        assertNull(prefSettings.moduleValues["module1.key2"])
    }
}
