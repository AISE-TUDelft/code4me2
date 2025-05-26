package me.code4me.services.modules.aggregators

import me.code4me.services.modules.PluginModule
import me.code4me.utils.configuration.Preference

class BaseContextAggregator : BaseAggregator() {
    override val moduleName: String
        get() = "BaseContextAggregator"

    // ID used to find this module's configuration in the config file
    override val configModuleId: String = "contextAggregator"



    override fun getStatus(): String {
        TODO("not yet implemented")
    }

    override fun getPreferenceList(): List<Preference> {
        return emptyList()
    }

    // The modulesList is now deprecated and replaced by dynamic loading from config
    @Deprecated("Use loadModulesFromConfig() instead", ReplaceWith("loadModulesFromConfig()"))
    override val modulesList: List<PluginModule> = emptyList()
}
