package me.code4me.services.modules.telemetry.behavioral.helpers.typingSpeed

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * Project-level service responsible for tracking and calculating user typing speed metrics.
 *
 * This service maintains a record of character typing timestamps and provides
 * real-time typing speed calculations in characters per second (CPS). It serves
 * as the central data repository for typing-related telemetry and analytics.
 *
 * ## Core Functionality
 * - **Timestamp Recording**: Captures precise timing of each character input
 * - **Speed Calculation**: Computes typing speed over configurable time windows
 * - **Memory Management**: Automatically cleans up old timestamp data
 * - **Performance Optimization**: Efficient data structures for minimal overhead
 *
 * ## Use Cases
 * - **Completion Timing**: Optimize when to show code completions based on typing speed
 * - **Debounce Adjustment**: Adapt UI response delays to user typing patterns
 * - **Performance Analytics**: Understand user typing behavior for UX improvements
 * - **Adaptive Features**: Modify plugin behavior based on typing characteristics
 *
 * ## Data Management
 * The service automatically manages memory by removing old timestamps that fall
 * outside the calculation window, preventing memory leaks during long editing sessions.
 *
 * ## Thread Safety
 * This service is designed to be accessed from the EDT (Event Dispatch Thread)
 * as it's primarily called from typing event handlers. The simple data structures
 * and operations make it naturally thread-safe for this use case.
 *
 * @param project The IntelliJ project instance this service is associated with
 *
 * @since 1.0.0
 * @see TypingSpeedHandler
 * @see me.code4me.services.modules.telemetry.behavioral.TypingSpeed
 */
@Service(Service.Level.PROJECT)
class TypingSpeedService(private val project: Project) {
    companion object {
        private val LOG = thisLogger()

        // Conversion constants
        private const val MILLISECONDS_PER_SECOND = 1000
    }

    /**
     * List of timestamps (in milliseconds) when characters were typed.
     * Maintained in chronological order for efficient processing.
     */
    private val typedTimestamps: MutableList<Long> = mutableListOf()

    /**
     * Counter for the total number of characters typed since service initialization.
     * Used for tracking overall typing activity and debugging purposes.
     */
    private var numOfTypedChars: Int = 0

    /**
     * Records the timestamp of a typed character.
     *
     * This method is called by the [TypingSpeedHandler] whenever a character
     * is typed in the editor. It captures the precise timing information
     * needed for accurate speed calculations.
     *
     * ## Implementation Details
     * - Records current system time with millisecond precision
     * - Increments the total character counter for session tracking
     * - Appends timestamp to the chronological list for window-based calculations
     *
     * ## Performance Considerations
     * - Minimal overhead: simple list append and counter increment
     * - No immediate cleanup: old timestamps are removed during speed calculations
     * - Memory efficient: timestamps are just long values
     *
     * @see TypingSpeedHandler.execute
     */
    fun recordCharTyped() {
        val now = System.currentTimeMillis()
        typedTimestamps.add(now)
        numOfTypedChars++
        LOG.trace("Recorded character typed at timestamp: $now (total: $numOfTypedChars)")
    }

    /**
     * Calculates the typing speed in characters per second (CPS) based on character
     * input within the specified time window.
     *
     * This method performs window-based speed calculation by:
     * 1. Determining the time range for the calculation window
     * 2. Removing timestamps that fall outside the window (memory cleanup)
     * 3. Counting characters typed within the window
     * 4. Computing speed as characters per second
     *
     * ## Calculation Method
     * - **Window**: Last N seconds from current time
     * - **Character Count**: Number of timestamps within the window
     * - **Speed Formula**: (characters in window) / (window size in seconds)
     * - **Unit**: Characters per second (CPS)
     *
     * ## Memory Management
     * As a side effect, this method cleans up old timestamps that are no longer
     * needed for calculations, preventing memory accumulation during long sessions.
     *
     * ## Edge Cases
     * - **Empty Window**: Returns 0.0 if no characters typed in the window
     * - **Partial Window**: Uses actual characters typed, even if fewer than expected
     * - **Recent Activity**: Accurately reflects recent typing speed changes
     *
     * @param windowSize The time window in seconds to calculate the typing speed over.
     *                   Must be positive; larger values provide more stable measurements
     *                   while smaller values are more responsive to changes.
     * @return The typing speed in characters per second (CPS) as a Double.
     *         Returns 0.0 if no characters were typed in the specified window.
     *
     * @throws IllegalArgumentException if windowSize is not positive
     */
    fun getTypingSpeed(windowSize: Int): Double {
        require(windowSize > 0) { "Window size must be positive, got: $windowSize" }

        val now = System.currentTimeMillis()
        val startOfTimeRange = now - (windowSize * MILLISECONDS_PER_SECOND)

        // Clean up old timestamps and keep only those within the window
        // This serves both calculation and memory management purposes
        typedTimestamps.removeIf { it < startOfTimeRange }

        val recentCharCount = typedTimestamps.size
        val speed = recentCharCount.toDouble() / windowSize.toDouble()

        LOG.trace("Calculated typing speed: $speed CPS (window: ${windowSize}s, chars: $recentCharCount)")

        // Reset the total counter after speed calculation for debugging purposes
        numOfTypedChars = 0

        return speed
    }

    /**
     * Provides a human-readable status string describing current typing activity.
     *
     * This method is primarily used for debugging and monitoring purposes,
     * providing insight into the service's current state and recent activity.
     *
     * ## Status Information
     * - **Character Count**: Characters typed since last status check
     * - **Current Speed**: Typing speed over a 10-second window (WPM approximation)
     * - **Activity Level**: General indication of typing activity
     *
     * ## Usage
     * Primarily intended for:
     * - Debug logging and troubleshooting
     * - Development and testing verification
     * - Monitoring service health and activity
     * - User interface status displays (if needed)
     *
     * @return A formatted string describing typing activity and current speed metrics
     */
    fun getStatus(): String {
        val currentSpeed = getTypingSpeed(10) // 10-second window for status
        return "Typed $numOfTypedChars chars since last activity. CPS: $currentSpeed"
    }
}
