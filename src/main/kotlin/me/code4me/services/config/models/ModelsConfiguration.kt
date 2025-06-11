package me.code4me.services.config.models

import com.typesafe.config.Config

/**
 * Represents the complete models configuration section.
 *
 * @property availableModels List of available model configurations
 * @property systemPrompt Default system prompt for model interactions
 */
data class ModelsConfiguration(
    val availableModels: List<ModelConfig>,
    val systemPrompt: String
) {
    companion object {
        /**
         * Creates a ModelsConfiguration from a Typesafe Config object.
         *
         * @param config The configuration object containing models settings
         * @return A ModelsConfiguration instance parsed from the configuration
         */
        fun fromConfig(config: Config): ModelsConfiguration {
            val availableModels = if (config.hasPath("available")) {
                config.getConfigList("available").map { modelConfig ->
                    ModelConfig.fromConfig(modelConfig)
                }
            } else {
                emptyList()
            }

            val systemPrompt = if (config.hasPath("systemPrompt")) {
                config.getString("systemPrompt")
            } else {
                "You are a helpful assistant."
            }

            return ModelsConfiguration(
                availableModels = availableModels,
                systemPrompt = systemPrompt
            )
        }
    }

    fun getAvailableChatModels(): List<ModelConfig> {
        return availableModels.filter { it.isChatModel }
    }

    fun getAvailableCompletionModels(): List<ModelConfig> {
        return availableModels.filter { !it.isChatModel }
    }

    fun getModelIdByName(name: String): Int? {
        if (name == "default") {
            return availableModels.firstOrNull { it.isDefault }?.id
        }
        return availableModels.find { it.name == name }?.id
    }
}