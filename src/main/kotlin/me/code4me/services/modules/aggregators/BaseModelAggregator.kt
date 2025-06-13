package me.code4me.services.modules.aggregators

import me.code4me.utils.configuration.Preference

class BaseModelAggregator : BaseAggregator() {
    /**
     * The configuration module identifier used to locate this aggregator's
     * configuration in the plugin configuration file.
     *
     * This ID is used by the configuration service to:
     * - Load submodule configurations
     * - Apply aggregator-specific settings
     * - Manage module dependencies
     */
    override val configModuleId: String
        get() = "BaseModelAggregator"

    /**
     * The human-readable display name of this module.
     *
     * This name is used in:
     * - User interface elements and settings screens
     * - Logging and debugging output
     * - Error messages and diagnostics
     * - Configuration file documentation
     *
     * The name should be descriptive and unique within the plugin's module ecosystem.
     */
    override val moduleName: String
        get() = "BaseModelAggregator"

    override fun getPreferenceList(): List<Preference> {
        return emptyList()
    }
}
