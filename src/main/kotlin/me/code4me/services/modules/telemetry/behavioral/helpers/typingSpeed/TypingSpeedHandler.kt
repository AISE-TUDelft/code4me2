package me.code4me.services.modules.telemetry.behavioral.helpers.typingSpeed

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.project.Project

/**
 * Custom typed action handler that intercepts character input to track typing speed metrics.
 *
 * This handler serves as a bridge between the IntelliJ editor's typing system and
 * the typing speed telemetry collection. It wraps the original typing handler to
 * capture timing information while preserving all normal typing functionality.
 *
 * ## Architecture
 * The handler follows the decorator pattern:
 * 1. **Intercepts** character typing events from the IntelliJ editor
 * 2. **Records** timing information for non-control characters
 * 3. **Delegates** to the original handler to maintain normal editor behavior
 * 4. **Integrates** seamlessly with existing typing workflows
 *
 * ## Integration Points
 * - **Installation**: Registered during project startup via [TypingTelemetryStartup]
 * - **Data Collection**: Feeds timing data to [TypingSpeedService]
 * - **Transparency**: Preserves all original typing behavior and functionality
 * - **Performance**: Minimal overhead added to typing operations
 *
 * ## Character Filtering
 * Only printable characters are tracked for speed calculations:
 * - **Included**: Letters, numbers, symbols, spaces, and printable characters
 * - **Excluded**: Control characters (backspace, delete, arrow keys, etc.)
 * - **Rationale**: Control characters don't represent typing productivity
 *
 * ## Error Handling
 * The handler is designed to be robust and never interfere with normal typing:
 * - Graceful handling of service unavailability
 * - Exception isolation to prevent typing disruption
 * - Comprehensive logging for debugging and monitoring
 *
 * @param originalHandler The original typed action handler that was active before
 *                        this handler was installed. This ensures that all normal
 *                        typing functionality is preserved.
 * @param project The IntelliJ project instance used to access the [TypingSpeedService]
 *                for recording typing timing data.
 *
 * @since 1.0.0
 * @see TypedActionHandler
 * @see TypingSpeedService
 * @see TypingTelemetryStartup
 */
class TypingSpeedHandler(
    private val originalHandler: TypedActionHandler?,
    private val project: Project,
) : TypedActionHandler {
    companion object {
        private val LOG = thisLogger()
    }

    /**
     * Handles character input events and records timing data for typing speed analysis.
     *
     * This method is called by the IntelliJ platform whenever a character is typed
     * in any editor within the project. It captures timing information for
     * telemetry purposes while ensuring normal typing behavior is preserved.
     *
     * ## Execution Flow
     * 1. **Character Filtering**: Checks if the typed character should be tracked
     * 2. **Timing Recording**: Records timestamp if character is trackable
     * 3. **Service Integration**: Updates the typing speed service with new data
     * 4. **Delegation**: Passes control to the original handler for normal processing
     *
     * ## Character Classification
     * Uses [Char.isISOControl] to filter out control characters:
     * - **Control Characters**: Tab, newline, backspace, delete, arrow keys, etc.
     * - **Printable Characters**: Letters, digits, punctuation, spaces, symbols
     * - **Tracking Logic**: Only printable characters contribute to typing speed
     *
     * ## Error Resilience
     * The method is designed to handle various error conditions gracefully:
     * - Service unavailability during project shutdown
     * - Null project references in edge cases
     * - Exceptions in timing recording without affecting typing
     *
     * ## Performance Considerations
     * - **Minimal Overhead**: Simple character check and service call
     * - **Fast Execution**: No complex calculations in the typing path
     * - **Non-blocking**: Never delays or interrupts normal typing flow
     * - **Memory Efficient**: Only timestamp recording, no large data structures
     *
     * @param editor The editor instance where the character was typed
     * @param charTyped The character that was typed by the user
     * @param dataContext The action context containing additional event information
     *
     * @see Char.isISOControl
     * @see TypingSpeedService.recordCharTyped
     */
    override fun execute(
        editor: Editor,
        charTyped: Char,
        dataContext: DataContext,
    ) {
        try {
            // Only track printable characters for typing speed calculations
            // Control characters (backspace, arrows, etc.) don't represent typing productivity
            if (!charTyped.isISOControl()) {
                val typingSpeedService = project.service<TypingSpeedService>()
                typingSpeedService.recordCharTyped()
                LOG.trace("Recorded typing event for character: '$charTyped'")
            } else {
                LOG.trace("Skipped control character: ${charTyped.code}")
            }
        } catch (e: Exception) {
            // Never let telemetry collection interfere with normal typing
            LOG.warn("Failed to record typing event for character: '$charTyped'", e)
        }

        // Always delegate to the original handler to preserve normal typing behavior
        // This ensures that all standard editor functionality continues to work
        originalHandler?.execute(editor, charTyped, dataContext)
    }
}
