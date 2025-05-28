package me.code4me.services.config.models

/**
 * Data class representing a module configuration.
 *
 * This class holds all the configuration properties for a plugin module.
 *
 * @property id The unique identifier of the module.
 * @property className The fully qualified class name of the module implementation.
 * @property name The display name of the module.
 * @property type The category configuration to which this module belongs.
 * @property description A description of the module's functionality.
 * @property enabled Whether the module is enabled by default.
 * @property submodules List of submodules for this module.
 * @property dependencies List of module dependencies.
 */
data class ModuleConfig(
    val id: String,
    val className: String,
    val name: String,
    val type: ModuleCategoryConfig,
    val description: String,
    val enabled: Boolean,
    val submodules: List<ModuleConfig> = emptyList(),
    val dependencies: List<ModuleDependency> = emptyList(),
)
