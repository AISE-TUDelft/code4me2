package me.code4me.services.config.models

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
}
