package me.code4me.services.modules.aggregators

import me.code4me.utils.configuration.Preference

/**
 * Telemetry aggregator responsible for collecting and managing telemetry data
 * from various telemetry-gathering submodules within the Code4Me plugin.
 *
 * This aggregator specializes in gathering telemetry information that helps
 * improve code completion accuracy and user experience, including:
 * - User interaction patterns and preferences
 * - Code completion usage statistics
 * - Performance metrics and timing data
 * - Error rates and completion effectiveness
 * - User feedback and satisfaction metrics
 * - Usage analytics and feature adoption
 *
 * The BaseTelemetryAggregator serves as the central coordinator for all
 * telemetry-related modules, ensuring that comprehensive usage and performance
 * data is collected while respecting user privacy and consent preferences.
 *
 * Telemetry data collected by this aggregator is used to:
 * - Improve completion algorithm accuracy
 * - Optimize plugin performance
 * - Understand user behavior patterns
 * - Guide feature development priorities
 * - Identify and resolve common issues
 *
 * **Privacy Note**: All telemetry collection respects user privacy settings
 * and follows applicable data protection regulations. Users can control
 * telemetry collection through the plugin's preference settings.
 *
 * @since 1.0.0
 * @see BaseAggregator
 * @see me.code4me.utils.record.Record.Type.BEHAVIORALTELEMETRY
 */
class BaseBehavioralTelemetryAggregator : BaseAggregator() {
    /**
     * The display name for this aggregator module.
     * Used in logging, configuration, and debugging contexts.
     */
    override val moduleName: String
        get() = "BaseBehavioralTelemetryAggregator"

    /**
     * Configuration module identifier used to locate this aggregator's
     * settings and submodule configurations in the plugin configuration file.
     *
     * This ID corresponds to the "BehavioralTelemetryAggregator" section in the
     * plugin configuration, which defines:
     * - Enabled telemetry collection modules
     * - Privacy and consent settings
     * - Data retention policies
     * - Telemetry transmission preferences
     * - Module-specific collection parameters
     */
    override val configModuleId: String = "BehavioralTelemetryAggregator"

    /**
     * Returns the list of configuration preferences for this telemetry aggregator.
     *
     * The base telemetry aggregator does not define any specific preferences,
     * relying instead on its submodules to provide their own configuration
     * options. Subclasses can override this method to add aggregator-level
     * preferences such as:
     * - Telemetry collection enable/disable toggle
     * - Data anonymization settings
     * - Collection frequency and batch size
     * - Privacy consent management
     * - Data retention period settings
     * - Opt-out mechanisms and user controls
     *
     * @return Empty list as the base implementation provides no preferences
     */
    override fun getPreferenceList(): List<Preference> {
        return emptyList()
    }
}
