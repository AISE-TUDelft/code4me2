package me.code4me.components.settings.fields

/**
 * Enum representing the state of a field in the settings UI.
 *
 * This enum is used to track whether a field in the settings UI is currently
 * active (enabled and can be interacted with) or inactive (disabled and cannot
 * be interacted with).
 */
enum class FieldInfo {
    /**
     * Represents an active field that can be interacted with.
     */
    ACTIVE,

    /**
     * Represents an inactive field that cannot be interacted with.
     */
    INACTIVE,
}
