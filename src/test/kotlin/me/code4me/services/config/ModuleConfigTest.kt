package me.code4me.services.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleConfigTest {
    @Test
    fun testModuleCategoryConfig() {
        // Test creating a ModuleCategoryConfig object
        val category =
            ModuleCategoryConfig(
                id = "test-category",
                path = "test/path",
                description = "Test Category Description",
            )

        // Verify all properties are correctly set
        assertEquals("test-category", category.id)
        assertEquals("test/path", category.path)
        assertEquals("Test Category Description", category.description)

        // Test equality
        val sameCategory =
            ModuleCategoryConfig(
                id = "test-category",
                path = "test/path",
                description = "Test Category Description",
            )
        assertEquals(category, sameCategory)

        // Test inequality
        val differentCategory =
            ModuleCategoryConfig(
                id = "different-category",
                path = "test/path",
                description = "Test Category Description",
            )
        assertNotEquals(category, differentCategory)
    }

    @Test
    fun testModuleConfig() {
        // Create a test category
        val category =
            ModuleCategoryConfig(
                id = "test-category",
                path = "test/path",
                description = "Test Category Description",
            )

        // Test creating a ModuleConfig object
        val module =
            ModuleConfig(
                id = "test-module",
                className = "me.code4me.TestModule",
                name = "Test Module",
                type = category,
                description = "Test Module Description",
                enabled = true,
            )

        // Verify all properties are correctly set
        assertEquals("test-module", module.id)
        assertEquals("me.code4me.TestModule", module.className)
        assertEquals("Test Module", module.name)
        assertEquals(category, module.type)
        assertEquals("Test Module Description", module.description)
        assertTrue(module.enabled)

        // Test equality
        val sameModule =
            ModuleConfig(
                id = "test-module",
                className = "me.code4me.TestModule",
                name = "Test Module",
                type = category,
                description = "Test Module Description",
                enabled = true,
            )
        assertEquals(module, sameModule)

        // Test inequality
        val differentModule =
            ModuleConfig(
                id = "different-module",
                className = "me.code4me.TestModule",
                name = "Test Module",
                type = category,
                description = "Test Module Description",
                enabled = true,
            )
        assertNotEquals(module, differentModule)

        // Test with different enabled state
        val disabledModule =
            ModuleConfig(
                id = "test-module",
                className = "me.code4me.TestModule",
                name = "Test Module",
                type = category,
                description = "Test Module Description",
                enabled = false,
            )
        assertNotEquals(module, disabledModule)
    }
}
