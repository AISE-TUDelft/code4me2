package me.code4me.services.modules

import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import me.code4me.utils.configuration.PreferenceCapable
import me.code4me.utils.record.Record

/**
 * Core interface defining the contract for all plugin modules in the Code4Me system.
 *
 * This interface establishes the fundamental structure for all modular components
 * within the plugin, including context collectors, telemetry gatherers, aggregators,
 * and specialized processing modules. All modules share common capabilities for:
 * - Data collection and processing
 * - Configuration and preference management
 * - Dependency resolution and submodule management
 * - Lifecycle management and initialization
 *
 * The modular architecture enables:
 * - **Extensibility**: New modules can be added without modifying existing code
 * - **Configurability**: Modules can be enabled/disabled through configuration
 * - **Testability**: Individual modules can be tested in isolation
 * - **Maintainability**: Clear separation of concerns and responsibilities
 * - **Performance**: Parallel data collection and processing capabilities
 *
 * ## Module Types
 * - **Context Modules**: Collect contextual information about the current editing environment
 * - **Telemetry Modules**: Gather usage statistics and performance metrics
 * - **Aggregator Modules**: Coordinate and combine data from multiple submodules
 * - **Processing Modules**: Transform and analyze collected data
 *
 * ## Lifecycle
 * 1. **Instantiation**: Modules are created by the configuration service
 * 2. **Initialization**: [initializeModules] sets up dependencies and submodules
 * 3. **Registration**: Modules register with their parent aggregators
 * 4. **Operation**: [collectData] is called to gather information
 * 5. **Disposal**: Modules are cleaned up when no longer needed
 *
 * @since 1.0.0
 * @see PreferenceCapable
 * @see Record
 * @see BaseAggregator
 */
interface PluginModule : PreferenceCapable {

    companion object {
        /**
         * Thread-safe storage for module submodule relationships.
         * Maps module IDs to their registered submodules.
         */
        private val moduleSubmodules = mutableMapOf<String, MutableList<PluginModule>>()

        /**
         * Thread-safe storage for hard dependency relationships.
         * Maps module IDs to the IDs of their required dependencies.
         */
        private val moduleHardDependencies = mutableMapOf<String, MutableSet<String>>()
    }

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
    val moduleName: String

    /**
     * Collects data from this module based on the provided completion request context.
     *
     * This is the primary data collection method that extracts relevant information
     * from the current editing environment. The method should be:
     * - **Non-blocking**: Execute quickly to avoid UI delays
     * - **Thread-safe**: May be called concurrently from multiple threads
     * - **Exception-safe**: Handle errors gracefully and return empty results if needed
     * - **Context-aware**: Use the request context to provide relevant data
     *
     * ## Implementation Guidelines
     * - Return empty list if no relevant data is available
     * - Log errors but don't throw exceptions that could break the completion flow
     * - Use appropriate [Record.Type] for categorizing collected data
     * - Consider caching expensive operations when appropriate
     *
     * @param request The inline completion request containing editor context,
     *                cursor position, and other relevant information for data collection
     * @return List of [Record] objects containing the collected data,
     *         categorized by type (CONTEXT, TELEMETRY, etc.)
     * @see Record
     * @see InlineCompletionRequest
     */
    fun collectData(request: InlineCompletionRequest): List<Record>


    /**
     * Called after inline completion elements have been inserted into the editor.
     *
     * This method allows modules to perform additional processing or cleanup
     * after the completion elements have been applied. It can be used for:
     * - Logging insertion events
     * - Updating UI components
     * - Triggering additional actions based on inserted elements
     *
     * ## Implementation Notes
     * - The default implementation does nothing; modules can override this method
     *   to provide specific post-insertion behavior.
     * - Should not block the UI thread or perform long-running operations.
     *
     * @param environment The environment containing information about the insertion context
     * @param elements The list of inline completion elements that were inserted
     */
    fun afterInsertion(
        environment: InlineCompletionInsertEnvironment,
        elements: List<InlineCompletionElement>) {
        // Default implementation does nothing, can be overridden by specific modules
    }


    /**
     * Initializes this module and sets up any required submodules or dependencies.
     *
     * This method is called during the plugin startup process and should:
     * - Initialize any required resources or connections
     * - Load and register submodules from configuration
     * - Verify that dependencies are available
     * - Set up any required event listeners or callbacks
     *
     * ## Error Handling
     * - Throw [IllegalStateException] for critical initialization failures
     * - Log warnings for non-critical issues but continue initialization
     * - Ensure the module is in a valid state even if some features are unavailable
     *
     * @throws IllegalStateException If critical dependencies are missing or
     *                               initialization fails in a way that makes the module unusable
     */
    fun initializeModules()

    /**
     * Retrieves the list of submodules managed by this module.
     *
     * Submodules are other [PluginModule] instances that this module coordinates
     * or depends upon. The relationship can be:
     * - **Aggregation**: This module combines data from submodules
     * - **Dependency**: This module requires submodules to function
     * - **Composition**: Submodules are integral parts of this module's functionality
     *
     * @return Immutable list of submodules, or empty list if none are managed
     */
    fun getSubmodules(): List<PluginModule> =
        moduleSubmodules.getOrDefault(getModuleId(), mutableListOf())

    /**
     * Registers a submodule with this module and defines the dependency relationship.
     *
     * This method establishes a parent-child relationship between modules and
     * defines whether the dependency is critical for operation.
     *
     * ## Dependency Types
     * - **Hard Dependencies** ([isHardDependency] = true): Required for basic functionality.
     *   If a hard dependency is unavailable, the module should not operate.
     * - **Soft Dependencies** ([isHardDependency] = false): Optional enhancements.
     *   The module can function with reduced capabilities if soft dependencies are missing.
     *
     * @param submodule The module to register as a submodule
     * @param isHardDependency Whether this submodule is required for basic operation.
     *                         Hard dependencies cause [checkDependencies] to fail if unavailable.
     * @throws IllegalArgumentException If the submodule is null or would create a circular dependency
     */
    fun registerSubmodule(
        submodule: PluginModule,
        isHardDependency: Boolean = false,
    ) {
        val moduleId = getModuleId()

        // Track hard dependencies separately for dependency checking
        if (isHardDependency) {
            moduleHardDependencies.getOrPut(moduleId) { mutableSetOf() }
                .add(submodule.getModuleId())
        }

        // Add to submodule list
        moduleSubmodules.getOrPut(moduleId) { mutableListOf() }
            .add(submodule)
    }

    /**
     * Verifies that all required dependencies are available and functional.
     *
     * This method checks whether all hard dependencies registered via
     * [registerSubmodule] are available and operational. It's typically
     * called during initialization or before critical operations.
     *
     * ## Implementation Notes
     * - The default implementation always returns true for backward compatibility
     * - Modules with dependencies should override this method
     * - Should check both availability and operational status of dependencies
     * - May perform lightweight health checks on dependent modules
     *
     * @return true if all required dependencies are available and functional,
     *         false if any hard dependencies are missing or non-functional
     */
    fun checkDependencies(): Boolean = true

    /**
     * Returns a unique identifier for this module used in dependency tracking.
     *
     * The module ID is used for:
     * - Dependency resolution and circular dependency detection
     * - Configuration file references and settings storage
     * - Logging and debugging identification
     * - Module registry and lookup operations
     *
     * By default, this delegates to [getPreferenceId] to maintain consistency
     * with the preference system, but can be overridden if different behavior is needed.
     *
     * @return A unique string identifier for this module
     */
    fun getModuleId(): String = getPreferenceId()
}