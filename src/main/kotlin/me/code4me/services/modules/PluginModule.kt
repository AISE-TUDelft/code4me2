package me.code4me.services.modules

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
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
 * - Managing submodules and dependencies
 * 
 * All plugin modules are also project-level services and can be disposed when no longer needed.
 */
interface PluginModule : PreferenceCapable, Disposable {
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
     * TODO: change to the proto message when implemented
     *
     * @param data A map containing the data to send, with string keys and any type of values.
     */
    fun collectData(request: InlineCompletionRequest): List<Record>

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

    /**
     * Gets the submodules of this module.
     *
     * @return A list of submodules.
     */
    fun getSubmodules(): List<PluginModule> = emptyList()

    /**
     * Registers a submodule with this module.
     *
     * @param submodule The submodule to register.
     * @param isHardDependency Whether this is a hard dependency (true) or soft dependency (false).
     *        Hard dependencies are required for the module to function, while soft dependencies are optional.
     */
    fun registerSubmodule(
        submodule: PluginModule,
        isHardDependency: Boolean = false,
    ) {
        // Default implementation does nothing
    }

    /**
     * Checks if all required dependencies are available.
     *
     * @return True if all required dependencies are available, false otherwise.
     */
    fun checkDependencies(): Boolean = true

    /**
     * Gets the module ID used for dependency tracking.
     *
     * @return The module ID.
     */
    fun getModuleId(): String = getPreferenceId()

    /**
     * Disposes the module when it's no longer needed.
     * This method is called when the module is disabled or when the project is closed.
     * 
     * Default implementation does nothing, but subclasses can override this to release resources.
     */
    override fun dispose() {
        // Default implementation does nothing
    }
}
