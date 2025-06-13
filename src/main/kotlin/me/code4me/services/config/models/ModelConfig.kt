package me.code4me.services.config.models

import com.typesafe.config.Config

/**
 * Represents a single model configuration.
 *
 * @property id Unique identifier for the model
 * @property name Display name of the model
 * @property isChatModel Whether this model supports chat/conversation mode
 */
data class ModelConfig(
    val id: Int,
    val name: String,
    val isChatModel: Boolean,
    val isDefault: Boolean = false,
    // Default value for isDefault, can be overridden in config
) {
    companion object {
        /**
         * Creates a ModelConfig from a Typesafe Config object.
         *
         * @param config The configuration object containing model settings
         * @return A ModelConfig instance parsed from the configuration
         */
        fun fromConfig(config: Config): ModelConfig {
            return ModelConfig(
                id = config.getInt("id"),
                name = config.getString("name"),
                isChatModel = config.getBoolean("isChatModel"),
                isDefault = config.getBoolean("isDefault"),
            )
        }
    }
}
