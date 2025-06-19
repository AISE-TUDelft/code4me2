package me.code4me

import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class BaseTest : BasePlatformTestCase() {
    fun testRandomFunctionality() {
        // This is a placeholder for an actual test case.
        // You can implement your test logic here.
        assertTrue(true) // Example assertion
    }

    override fun getTestDataPath(): String {
        return "src/test/testData/random"
    }
}
