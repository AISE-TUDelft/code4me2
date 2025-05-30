package me.code4me.components.settings.sections

import com.intellij.util.ui.FormBuilder
import me.code4me.components.settings.fields.StateValueField

/**
 * Interface defining the contract for settings sections in the Code4Me configuration UI.
 *
 * Settings sections are modular UI components that handle specific aspects of the
 * plugin configuration. Each section is responsible for:
 * - Creating and managing its own UI components
 * - Registering field state managers for persistence
 * - Handling section-specific logic and validation
 * - Integrating with the overall settings framework
 *
 * Common implementations include:
 * - [AuthenticationSection]: Handles user login/signup
 * - [ConfigurationSection]: Manages module and application preferences
 *
 * @since 1.0.0
 */
interface SettingsSection {

    /**
     * Applies this section's UI components to the provided form builder.
     *
     * This method is called during the settings UI construction phase and should:
     * 1. Create all necessary UI components for this section
     * 2. Add components to the form builder in the desired layout
     * 3. Register field state managers with the provided list
     * 4. Set up any necessary event listeners or bindings
     *
     * The implementation should be idempotent - calling this method multiple
     * times should not cause duplicate components or listeners.
     *
     * @param builder The form builder to add components to
     * @param stateValueFields Mutable list to register field state managers for persistence
     */
    fun applyTo(
        builder: FormBuilder,
        stateValueFields: MutableList<StateValueField<*>>
    )
}