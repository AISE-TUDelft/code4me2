package me.code4me.services.modules.telemetry

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.components.service
import me.code4me.services.modules.PluginModule
import me.code4me.utils.record.Record
import me.code4me.services.modules.telemetry.helpers.typingSpeed.TypingSpeedService
import me.code4me.services.state.PrefState
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType

/**
 * This module collects typing speed telemetry data.
 */
class TypingSpeed() : PluginModule {
    override val moduleName: String
        get() = "TypingSpeed"

    /**
     * Collects typing speed telemetry data.
     * This method uses the TypingSpeedService to get the typing speed in characters per second (CPS).
     * Only collects data if the module is enabled in the global configuration.
     */
    override fun collectData(request: InlineCompletionRequest): List<Record> {
        // Check if this module is enabled in the global configuration
        val prefState = me.code4me.services.state.getPrefState()
        if (!prefState.enabledModules.contains(getPreferenceId())) {
            return emptyList()
        }

        val record = Record(Record.Type.TELEMETRY)

        val editor = request.editor ?: return emptyList() // Editor might be nullable
        val project = editor.project ?: return emptyList() // Project might be nullable

        val trackingService: TypingSpeedService = project.service()

        val cpsKey = Record.key<Int>("typing_speed")
        val windowSize =
            PrefState
                .getPreferenceValue(getPreferenceId(), "telemetry.typing_speed.window_size")
                ?.toInt() ?: 10
        val cps = trackingService.getTypingSpeed(windowSize).toInt()

        record.put(cpsKey, cps)

        return listOf(record)
    }

    override fun getStatus(): String {
        TODO("Not yet implemented")
    }

    override fun initializeModules() {
    }

    /**
     * this class has a variable windowSize that determines the time window for calculating typing speed.
     * this is set in the preferences.
     */
    override fun getPreferenceList(): List<Preference> {
        return listOf(
            Preference(
                "telemetry.typing_speed.window_size",
                PreferenceType.INT,
                "10",
                "Time window size in seconds",
                "This determines what amount of time before the request is taken into account for calculating average typing speed.",
            ),
        )
    }

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.TELEMETRY
    }
}
