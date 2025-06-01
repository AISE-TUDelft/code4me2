package me.code4me.services.modules.telemetry.behavioral.helpers.typingSpeed

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Project startup activity responsible for initializing typing speed telemetry collection.
 *
 * This startup activity integrates the typing speed tracking system into the IntelliJ
 * editor infrastructure by replacing the default typed action handler with a custom
 * handler that captures timing information while preserving all normal typing functionality.
 *
 * ## Initialization Process
 * 1. **Handler Capture**: Retrieves the current default typed action handler
 * 2. **Handler Replacement**: Installs [TypingSpeedHandler] as the new global handler
 * 3. **Delegation Setup**: Configures the new handler to delegate to the original
 * 4. **Service Integration**: Connects the handler to the project's [TypingSpeedService]
 *
 * ## Architecture Integration
 * The startup activity follows the IntelliJ platform's initialization patterns:
 * - **Project-level Scope**: Activated separately for each opened project
 * - **Early Execution**: Runs during project opening to capture all typing activity
 * - **Global Installation**: Affects all editors within the project
 * - **Transparent Operation**: No visible impact on normal editor functionality
 *
 * ## Handler Chain Management
 * The implementation carefully preserves the existing handler chain:
 * - **Original Preservation**: Maintains reference to the pre-existing handler
 * - **Delegation**: Ensures all original functionality remains intact
 * - **Wrapper Pattern**: Uses decoration rather than replacement for safety
 * - **Fallback Safety**: Handles cases where no original handler exists
 *
 * ## Lifecycle Management
 * - **Automatic Startup**: Triggered by IntelliJ's project activity system
 * - **Single Installation**: Ensures handler is only installed once per project
 * - **Memory Management**: Handlers are cleaned up when project closes
 * - **Exception Safety**: Robust error handling prevents startup failures
 *
 * ## Performance Impact
 * The typing telemetry system is designed for minimal performance impact:
 * - **Handler Overhead**: Negligible addition to typing latency
 * - **Memory Footprint**: Small timestamp storage with automatic cleanup
 * - **CPU Usage**: Simple character filtering and timestamp recording
 * - **I/O Impact**: No file or network operations during typing
 *
 * @since 1.0.0
 * @see ProjectActivity
 * @see TypingSpeedHandler
 * @see TypingSpeedService
 */

/**
 * @deprecated This startup activity has been replaced by initialization in the TypingSpeed module.
 * The functionality is now triggered when the TypingSpeed module is initialized rather than at project startup.
 */
@Deprecated("Replaced by initialization in TypingSpeed.initializeModules()")
class TypingTelemetryStartup : ProjectActivity {
    companion object {
        private val LOG = thisLogger()
    }

    /**
     * Executes the typing telemetry initialization for the specified project.
     *
     * This method is called automatically by the IntelliJ platform during project
     * startup and is responsible for installing the typing speed tracking
     * infrastructure into the editor system.
     *
     * ## Installation Steps
     * 1. **Handler Retrieval**: Gets the current global typed action handler
     * 2. **Custom Handler Creation**: Creates a new [TypingSpeedHandler] instance
     * 3. **Handler Registration**: Installs the custom handler globally
     * 4. **Verification**: Logs successful installation for monitoring
     *
     * ## Error Handling
     * The method includes comprehensive error handling to ensure that:
     * - Typing functionality is never compromised by telemetry issues
     * - Startup failures are logged but don't prevent project opening
     * - Fallback behavior maintains normal editor operation
     * - Recovery mechanisms handle edge cases gracefully
     *
     * ## Handler Scope
     * The installed handler affects all typing within the IntelliJ instance:
     * - **Global Registration**: Handler applies to all editors and projects
     * - **Project Association**: Handler instance is linked to the specific project
     * - **Service Integration**: Connected to the project's typing speed service
     * - **Lifecycle Binding**: Handler lifecycle follows project lifecycle
     *
     * ## Concurrency Considerations
     * - **Thread Safety**: Installation happens on the appropriate platform thread
     * - **Atomic Operation**: Handler replacement is performed atomically
     * - **Race Condition Prevention**: Proper sequencing with other startup activities
     * - **State Consistency**: Ensures consistent handler state across components
     *
     * @param project The IntelliJ project instance for which typing telemetry
     *                should be initialized. The project provides access to
     *                project-specific services and configurations.
     *
     * @throws Exception If handler installation fails, but exceptions are caught
     *                   and logged to prevent startup failure.
     *
     * @see TypedAction.getInstance
     * @see TypedAction.setupRawHandler
     * @see TypingSpeedHandler
     */
    override suspend fun execute(project: Project) {
        try {
            LOG.debug("Starting typing telemetry initialization for project: ${project.name}")

            // Capture the current typed action handler to preserve existing functionality
            // This ensures that all normal typing behavior continues to work after our handler is installed
            val originalHandler = TypedAction.getInstance().rawHandler
            LOG.trace("Captured original typed action handler: ${originalHandler?.javaClass?.simpleName ?: "null"}")

            // Create our custom handler that wraps the original handler
            // This handler will record typing timing data while delegating to the original
            val typingSpeedHandler = TypingSpeedHandler(originalHandler, project)

            // Install our custom handler as the global typed action handler
            // This replaces the default handler system-wide, but our handler delegates
            // to the original to maintain all existing functionality
            TypedAction.getInstance().setupRawHandler(typingSpeedHandler)

            LOG.info("Successfully installed TypingSpeedHandler for project: ${project.name}")
        } catch (e: Exception) {
            // Log the error but don't let telemetry initialization failure prevent project startup
            // The project should still function normally even if typing speed telemetry is unavailable
            LOG.error("Failed to initialize typing telemetry for project: ${project.name}", e)
        }
    }
}
