package me.code4me.services.modules.telemetry

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.telemetry.helpers.typingSpeed.TypingSpeedService
import me.code4me.services.state.PrefState
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import me.code4me.utils.record.Record

/**
 * Telemetry module for collecting and analyzing user typing speed metrics.
 *
 * This module tracks typing patterns and calculates typing speed in characters per second (CPS)
 * that can be used to:
 * - Optimize completion timing and debounce delays based on user typing speed
 * - Understand user coding patterns and typing behavior
 * - Improve completion relevance and timing based on typing velocity
 * - Provide insights into developer productivity and coding rhythm
 * - Adapt UI responsiveness to user typing characteristics
 *
 * The module leverages the [TypingSpeedService] to maintain accurate typing
 * metrics across the IDE session and provides real-time speed calculations
 * for completion timing optimization.
 *
 * ## Metrics Collected
 * - **Typing Speed**: Characters per second calculated over a configurable time window
 * - **Window-based Measurement**: Configurable time window for speed calculations
 * - **Real-time Updates**: Continuous tracking of typing velocity changes
 *
 * ## Key Features
 * - **Configurable Time Window**: Adjustable measurement window for speed calculations
 * - **Real-time Tracking**: Continuous monitoring of typing patterns
 * - **Performance Optimized**: Minimal impact on typing responsiveness
 * - **Adaptive Metrics**: Speed calculations adapt to recent typing activity
 *
 * ## Privacy and Performance
 * - All metrics are calculated locally without external transmission
 * - Uses efficient timestamp tracking for recent typing data
 * - Respects user privacy preferences for telemetry collection
 * - Minimal performance impact on typing responsiveness
 *
 * ## Integration with Completion System
 * The typing speed data helps optimize:
 * - Completion suggestion timing based on user typing velocity
 * - Debounce delays for fast vs. slow typists
 * - UI responsiveness and suggestion frequency
 * - Adaptive completion behavior based on typing patterns
 *
 * @since 1.0.0
 * @see PluginModule
 * @see TypingSpeedService
 * @see TypingSpeedHandler
 */
class TypingSpeed : PluginModule {
    companion object {
        private val LOG = thisLogger()

        // Record key names following the new naming convention (underscore separated)
        private const val KEY_TYPING_SPEED = "typing_speed"

        // Preference key names using dot notation
        private const val PREF_WINDOW_SIZE = "telemetry.typing.speed.window.size"

        // Default configuration values
        private const val DEFAULT_WINDOW_SIZE = 10
    }

    /**
     * The display name for this typing speed telemetry module.
     */
    override val moduleName: String = "TypingSpeed"

    /**
     * Collects typing speed telemetry data based on recent user input patterns.
     *
     * This method calculates typing speed in characters per second (CPS) using the
     * [TypingSpeedService] and packages it into telemetry records. The measurement
     * uses a configurable time window to balance accuracy with responsiveness.
     *
     * ## Data Collection Process
     * 1. Validates that the module is enabled in user preferences
     * 2. Retrieves the configured time window for speed calculation
     * 3. Gets current typing speed from the TypingSpeedService
     * 4. Packages the speed metric into a properly typed Record entry
     *
     * ## Speed Calculation
     * - **Measurement Window**: Configurable time period for calculating current speed
     * - **Unit**: Characters per second (CPS) converted to integer for consistency
     * - **Accuracy**: Based on actual character input timestamps within the window
     * - **Responsiveness**: Adapts quickly to changes in typing patterns
     *
     * ## Error Handling
     * - Returns empty list if module is disabled
     * - Handles null editor or project gracefully
     * - Provides fallback values for missing preferences
     * - Logs errors for debugging without crashing
     *
     * @param request The inline completion request containing editor and project context
     * @return List containing a single [Record] with typing speed telemetry,
     *         or empty list if insufficient data, module disabled, or errors occur
     */
    override fun collectData(request: InlineCompletionRequest): List<Record> {
        try {
            // Check if this module is enabled in the global configuration
            val prefState = me.code4me.services.state.getPrefState()
            if (!prefState.enabledModules.contains(getPreferenceId())) {
                LOG.debug("Module $moduleName is disabled, skipping data collection")
                return emptyList()
            }

            val editor = request.editor
            val project = editor.project ?: return emptyList() // Project might be nullable

            val record = Record(Record.Type.TELEMETRY)
            val trackingService: TypingSpeedService = project.service()

            // Get window size preference with fallback to default value
            val windowSize =
                PrefState
                    .getPreferenceValue(getPreferenceId(), PREF_WINDOW_SIZE)
                    ?.toInt() ?: DEFAULT_WINDOW_SIZE

            // Calculate current typing speed and convert to integer
            val cps = trackingService.getTypingSpeed(windowSize).toInt()

            // Create properly typed record key and store the value
            val cpsKey = Record.key<Int>(KEY_TYPING_SPEED)
            record.put(cpsKey, cps)

            LOG.trace("Collected typing speed: $cps CPS (window: ${windowSize}s)")
            return listOf(record)
        } catch (e: Exception) {
            LOG.error("Failed to collect typing speed telemetry", e)
            return emptyList()
        }
    }

    /**
     * Initializes the typing speed telemetry module.
     *
     * This module relies on the [TypingSpeedService] which is initialized
     * automatically by the IntelliJ platform as a project-level service,
     * and the [TypingSpeedHandler] which is registered during project startup
     * via [TypingTelemetryStartup].
     *
     * No additional initialization is required at the module level as the
     * typing tracking infrastructure is set up through the project activity
     * system and service framework.
     */
    override fun initializeModules() {
        LOG.debug("Initialized $moduleName")
    }

    /**
     * Returns the preference class for this telemetry module.
     *
     * @return [PreferenceClass.TELEMETRY] indicating this is a telemetry collection module
     */
    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.TELEMETRY
    }

    /**
     * Provides the list of configurable preferences for typing speed telemetry.
     *
     * This module provides a single configurable preference that controls
     * the time window used for calculating typing speed. The window size
     * affects the balance between measurement accuracy and responsiveness:
     *
     * - **Smaller Windows**: More responsive to typing speed changes, but potentially noisier
     * - **Larger Windows**: More stable measurements, but slower to adapt to changes
     *
     * ## Available Preferences
     * - **Window Size**: Time window in seconds for calculating average typing speed
     *
     * @return List containing a single [Preference] for the measurement window configuration
     */
    override fun getPreferenceList(): List<Preference> {
        return listOf(
            Preference(
                key = PREF_WINDOW_SIZE,
                type = PreferenceType.INT,
                defaultValue = DEFAULT_WINDOW_SIZE.toString(),
                displayName = "Time Window Size (seconds)",
                description =
                    "This determines the time window before the request that is taken into account " +
                        "for calculating average typing speed. Smaller values are more responsive to changes " +
                        "but may be less stable, while larger values provide more stable measurements.",
            ),
        )
    }
}
