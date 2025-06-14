package me.code4me.services.config

import com.typesafe.config.ConfigFactory
import me.code4me.services.config.models.LanguagesConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesConfigTest {
    @Test
    fun testLanguagesConfig() {
        // Create a test config string with a few languages
        val configString =
            """
            {
                "Java" = 11,
                "Python" = 47,
                "Kotlin" = 98,
                "JavaScript" = 169
            }
            """.trimIndent()

        // Parse the config string
        val config = ConfigFactory.parseString(configString)

        // Create a LanguagesConfig from the parsed config
        val languagesConfig = LanguagesConfig.fromConfig(config)

        // Verify the map contains the expected entries
        assertEquals(4, languagesConfig.languageMap.size)
        assertEquals(11, languagesConfig.languageMap["Java"])
        assertEquals(47, languagesConfig.languageMap["Python"])
        assertEquals(98, languagesConfig.languageMap["Kotlin"])
        assertEquals(169, languagesConfig.languageMap["JavaScript"])

        // Test equality
        val sameLanguagesConfig =
            LanguagesConfig(
                languageMap =
                    mapOf(
                        "Java" to 11,
                        "Python" to 47,
                        "Kotlin" to 98,
                        "JavaScript" to 169,
                    ),
            )
        assertEquals(languagesConfig, sameLanguagesConfig)

        // Test with a different map
        val differentLanguagesConfig =
            LanguagesConfig(
                languageMap =
                    mapOf(
                        "Java" to 11,
                        "Python" to 47,
                        "Kotlin" to 98,
                        "TypeScript" to 41,
                    ),
            )
        assertTrue(languagesConfig != differentLanguagesConfig)
    }

    @Test
    fun testEmptyLanguagesConfig() {
        // Test with an empty config
        val emptyConfig = ConfigFactory.parseString("{}")
        val emptyLanguagesConfig = LanguagesConfig.fromConfig(emptyConfig)

        // Verify the map is empty
        assertTrue(emptyLanguagesConfig.languageMap.isEmpty())
    }
}
