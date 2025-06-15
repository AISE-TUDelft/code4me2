package me.code4me.services.config.models

import com.intellij.psi.codeStyle.NameUtil
import com.typesafe.config.Config

/**
 * Represents the languages configuration section.
 *
 * @property languageMap Map of language names to their corresponding IDs
 */
data class LanguagesConfig(
    val languageMap: Map<String, Int>,
) {
    companion object {
        private const val VALUE_UNKNOWN_LANGUAGE = 179

        /**
         * Creates a LanguagesConfig from a Typesafe Config object.
         *
         * @param config The configuration object containing languages settings
         * @return A LanguagesConfig instance parsed from the configuration
         */
        fun fromConfig(config: Config): LanguagesConfig {
            val languageMap = mutableMapOf<String, Int>()

            // Get all entries as a map to avoid issues with special characters in keys
            val configMap = config.root().unwrapped() as? Map<*, *>

            // For each entry, convert the key to string and the value to int
            configMap?.forEach { (key, value) ->
                if (key is String && value is Number) {
                    languageMap[key] = value.toInt()
                }
            }

            return LanguagesConfig(
                languageMap = languageMap,
            )
        }
    }

    /**
     * Returns the ID of the specified language.
     * If the language is not found, returns a default value for unknown languages (check config file).
     */
    fun getLanguageId(language: String): Int {
        return languageMap[language] ?: VALUE_UNKNOWN_LANGUAGE
    }

    /**
     * Returns the ID of the specified language, using a fuzzy matching approach.
     * This method is intended to handle cases where the language name might not match exactly.
     */
    fun getLanguageIdFuzzy(language: String): Int {
        val matcher = NameUtil.buildMatcher(language).build()
        val bestMatch =
            languageMap.keys
                .asSequence()
                .filter { matcher.matches(it) }
                .maxByOrNull { matcher.matchingDegree(it) }

        return bestMatch?.let { languageMap[it] } ?: VALUE_UNKNOWN_LANGUAGE
    }
}
