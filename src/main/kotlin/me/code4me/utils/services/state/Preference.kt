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

/**
 * Helper method to retrieve list preference values with fallback.
 *
 * @param moduleId The module identifier
 * @param key The preference key
 * @param defaultValue The default value if preference is not set
 * @return The list preference value as a list of strings
 */
fun getListPreference(
    moduleId: String,
    key: String,
    defaultValue: String = "default",
): String {
    return PrefState.getPreferenceValue(moduleId, key) ?: defaultValue
}

/**
 * Helper method to retrieve textual preference values with fallback.
 *
 * @param moduleId The module identifier
 * @param key The preference key
 * @param defaultValue The default value if preference is not set
 * @return The string preference value
 */
fun getTextualPreference(
    moduleId: String,
    key: String,
    defaultValue: String = "",
): String {
    return PrefState.getPreferenceValue(moduleId, key) ?: defaultValue
}

/**
 * Helper method to retrieve string preference values with fallback.
 *
 * @param moduleId The module identifier
 * @param key The preference key
 * @param defaultValue The default value if preference is not set
 * @return The string preference value
 */
fun getFloatPreference(
    moduleId: String,
    key: String,
    defaultValue: Float = 0.0f,
): Float {
    return PrefState.getPreferenceValue(moduleId, key)?.toFloatOrNull() ?: defaultValue
}
