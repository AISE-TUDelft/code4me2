package me.code4me.services.config.models

/**
 * Data class representing a module category configuration.
 *
 * Categories are used to group related modules together.
 *
 * @property id The unique identifier of the category.
 * @property path The path or location of the category in the UI hierarchy.
 * @property description A description of the category.
 */
data class ModuleCategoryConfig(
    val id: String,
    val path: String,
    val description: String,
)
