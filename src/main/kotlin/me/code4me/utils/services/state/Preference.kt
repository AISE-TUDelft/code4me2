package me.code4me.utils.services.state

import me.code4me.services.state.PrefState

/**
 * Helper method to retrieve boolean preference values with fallback.
 *
 * @param moduleId The module identifier
 * @param key The preference key
 * @param defaultValue The default value if preference is not set
 * @return The boolean preference value
 */
fun getBooleanPreference(
    moduleId: String,
    key: String,
    defaultValue: Boolean,
): Boolean {
    return PrefState.getPreferenceValue(moduleId, key)?.toBoolean() ?: defaultValue
}

/**
 * Helper method to retrieve integer preference values with fallback.
 *
 * @param moduleId The module identifier
 * @param key The preference key
 * @param defaultValue The default value if preference is not set or invalid
 * @return The integer preference value
 */
fun getIntPreference(
    moduleId: String,
    key: String,
    defaultValue: Int,
): Int {
    return PrefState.getPreferenceValue(moduleId, key)?.toIntOrNull() ?: defaultValue
}
