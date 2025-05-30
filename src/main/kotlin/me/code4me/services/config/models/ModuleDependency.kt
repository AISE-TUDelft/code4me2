package me.code4me.services.config.models

/**
 * Data class representing a module dependency.
 *
 * @property moduleId The ID of the module that is depended on.
 * @property isHard Whether this is a hard dependency (true) or soft dependency (false).
 *             Hard dependencies are required for the module to function, while soft dependencies are optional.
 */
data class ModuleDependency(
    val moduleId: String,
    val isHard: Boolean,
)
