package me.code4me.services.modules

import me.code4me.services.modules.Record
import me.code4me.utils.configuration.PreferenceCapable

/**
 * Shared interface for plugin modules in the Code4Me application.
 * 
 * This interface defines the contract that all plugin modules must implement.
 * It extends [PreferenceCapable] to ensure that all modules can provide
 * preference information for configuration.
 * 
 * Plugin modules are responsible for:
 * - Providing telemetry data
 * - Retrieving some sort of information and sending it to the core application
 * - Reporting their status
 */
interface PluginModule : PreferenceCapable {
    /**
     * The display name of the module.
     * 
     * This name is used in the UI to identify the module to users.
     */
    val moduleName: String

    /**
     * Sends data from this module to the core application.
     * 
     * This method is used to transmit telemetry data, context information,
     * or other module-specific data to the core application for processing.
     * 
     * @param data A map containing the data to send, with string keys and any type of values.
     */
    // TODO: change to the proto message when implemented
    fun collectData() : List<Record>

    /**
     * Retrieves the current status of the module.
     * 
     * This method can be used to check if the module is functioning correctly,
     * to get configuration information, or to retrieve other status details.
     * 
     * @return A string representing the current status of the module.
     */
    fun getStatus(): String

    /**
     * Initializes the module and registers any sub-modules or components.
     *
     * This method is called during the startup process to ensure that all
     * necessary components of the module are properly initialized and ready to use.
     */
    fun initializeModules()
}
