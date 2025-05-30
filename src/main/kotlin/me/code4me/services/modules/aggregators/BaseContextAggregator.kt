package me.code4me.services.modules.aggregators

import me.code4me.utils.configuration.Preference

/**
 * Context aggregator responsible for collecting and managing contextual information
 * from various context-gathering submodules within the Code4Me plugin.
 *
 * This aggregator specializes in gathering contextual data that influences code completion
 * suggestions, including:
 * - Current file context and cursor position
 * - Project structure and dependencies
 * - Recently opened files and editing history
 * - Language-specific context information
 * - IDE state and user preferences
 *
 * The BaseContextAggregator serves as the central coordinator for all context-related
 * modules, ensuring that comprehensive contextual information is available for
 * generating intelligent code completions.
 *
 * Context data collected by this aggregator is typically used by the completion
 * engine to understand the current development environment and provide relevant
 * suggestions based on the user's current editing context.
 *
 * @since 1.0.0
 * @see BaseAggregator
 * @see me.code4me.utils.record.Record.Type.CONTEXT
 */
class BaseContextAggregator : BaseAggregator() {
    /**
     * The display name for this aggregator module.
     * Used in logging, configuration, and debugging contexts.
     */
    override val moduleName: String
        get() = "BaseContextAggregator"

    /**
     * Configuration module identifier used to locate this aggregator's
     * settings and submodule configurations in the plugin configuration file.
     *
     * This ID corresponds to the "contextAggregator" section in the
     * plugin configuration, which defines:
     * - Enabled context collection modules
     * - Context collection preferences
     * - Module-specific settings and parameters
     */
    override val configModuleId: String = "contextAggregator"

    /**
     * Returns the list of configuration preferences for this context aggregator.
     *
     * The base context aggregator does not define any specific preferences,
     * relying instead on its submodules to provide their own configuration
     * options. Subclasses can override this method to add aggregator-level
     * preferences such as:
     * - Context collection strategy
     * - Maximum context length
     * - etc.
     *
     * @return Empty list as the base implementation provides no preferences
     */
    override fun getPreferenceList(): List<Preference> {
        return emptyList()
    }
}
