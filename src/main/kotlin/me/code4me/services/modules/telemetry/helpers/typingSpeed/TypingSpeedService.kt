package me.code4me.services.modules.telemetry.helpers.typingSpeed

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Service to track typing speed in characters per second (CPS).'
 * This service records the timestamps of typed characters and calculates the typing speed based on the number of characters typed in a given time window.
 */
@Service(Service.Level.PROJECT)
class TypingSpeedService(private val project: Project) {
    private val typedTimestamps: MutableList<Long> = mutableListOf()
    private var numOfTypedChars: Int = 0

    /**
     * Records the timestamp of a typed character.
     * This method is called whenever a character is typed in the editor.
     */
    fun recordCharTyped() {
        val now = System.currentTimeMillis()
        typedTimestamps.add(now)
        numOfTypedChars++
    }

    /**
     * Calculates the typing speed in characters per second (CPS) based on the number of characters typed in the last 60 seconds.
     * @param window_size The time window in seconds to calculate the typing speed.
     * @return The typing speed in CPS.
     */
    fun getTypingSpeed(window_size: Int): Double {
        val now = System.currentTimeMillis()
        val startOfTimeRange = now - (window_size * 1000)

        // Keep only timestamps within the last 60 seconds
        typedTimestamps.removeIf { it < startOfTimeRange }

        val recentCharCount = typedTimestamps.size
        val speed = (recentCharCount / window_size.toDouble()) // 1 minute window
        println("[TypingSpeedService] Current CPS (last $window_size): $speed")
        println("[TypingSpeedService] Typed chars since $window_size seconds ago: $numOfTypedChars")
        numOfTypedChars = 0 // Reset the count after reporting
        return speed
    }

    fun getStatus(): String {
        return "Typed  chars since last activity. WPM: ${getTypingSpeed(10)}"
    }
}
