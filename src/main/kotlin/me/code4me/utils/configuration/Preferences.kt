package me.code4me.utils.configuration

interface PreferenceCapable {
    /**
     * Returns the list of preferences for this component.
     * @return A list of [Preference] objects representing the configurable parameters.
     */
    fun getPreferenceList(): List<Preference>

    /**
     * Returns the class type of the preference.
     * @return A [PreferenceClass] object representing the type of the preference.
     */
    fun getPreferenceClass(): PreferenceClass

    /**
     * Returns the key for the preference.
     * @return A string representing the key of the preference.
     */
    fun getPreferenceId(): String {
        return this::class.java.simpleName
    }
}

/**
 * Represents a type of configurable parameter for any component in the application
 * @param key The key of the preference
 * @param type The type of the preference (e.g., "boolean", "string", "int")
 * @param defaultValue The default value of the preference
 * @param displayName The name to display in the UI
 * @param description A description of the preference
 */
data class Preference(
    val key: String,
    val type: PreferenceType,
    val defaultValue: String,
    val displayName: String,
    val description: String = "",
)

enum class PreferenceType(val type: String) {
    BOOLEAN("boolean"),
    STRING("string"),
    INT("int"),
    FLOAT("float"),
    LONG("long"),
    DOUBLE("double"),
    LIST("list"),
    MAP("map"),
}

enum class PreferenceClass(val type: String) {
    MODULE("module"),
    BEHAVIORAL_TELEMETRY("behavioralTelemetry"),
    CONTEXTUAL_TELEMETRY("contextualTelemetry"),
    CONTEXT("context"),
    AUTH("auth"),
    SYSTEM("system"),
}
