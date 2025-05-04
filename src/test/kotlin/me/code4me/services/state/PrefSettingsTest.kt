package me.code4me.services.state

import org.junit.Assert.*
import org.junit.Test

class PrefSettingsTest {
    
    @Test
    fun testPrefSettingsProperties() {
        // Create a new PrefSettings instance
        val prefSettings = PrefSettings()
        
        // Test default values
        assertFalse(prefSettings.storeCompletions)
        assertFalse(prefSettings.storeContext)
        assertTrue(prefSettings.availableModules.isEmpty())
        assertTrue(prefSettings.enabledModules.isEmpty())
        assertTrue(prefSettings.modulePreferences.isEmpty())
        assertTrue(prefSettings.moduleValues.isEmpty())
        
        // Test setting values
        prefSettings.storeCompletions = true
        prefSettings.storeContext = true
        
        // Verify values were set
        assertTrue(prefSettings.storeCompletions)
        assertTrue(prefSettings.storeContext)
    }
    
    @Test
    fun testModulePreferenceDelegate() {
        // Create a new PrefSettings instance
        val prefSettings = PrefSettings()
        
        // Create a test class that uses the ModulePreferenceDelegate
        class TestClass(private val prefSettings: PrefSettings) {
            var testPreference by prefSettings.ModulePreferenceDelegate("testModule", "testKey", "defaultValue")
        }
        
        // Create an instance of the test class
        val testClass = TestClass(prefSettings)
        
        // Test getting the default value
        assertEquals("defaultValue", testClass.testPreference)
        
        // Test setting a value
        testClass.testPreference = "newValue"
        
        // Verify the value was set in the moduleValues map
        assertEquals("newValue", prefSettings.moduleValues["testModule.testKey"])
        
        // Test getting the set value
        assertEquals("newValue", testClass.testPreference)
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