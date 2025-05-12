package me.code4me.services.modules.telemetry

import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import me.code4me.services.state.PrefState
import me.code4me.utils.configuration.PreferenceType


class TypingSpeed(private val project: Project): PluginModule {
    override val moduleName: String
        get() = "TypingSpeed"

    private val trackingService: TypingSpeedService = project.service()

    override fun collectData(): List<Record> {
        val record = Record(Record.Type.TELEMETRY)

        val wpmKey = Record.EntryKey("typing_speed_wpm", java.lang.Double::class.java)
        val window_size = PrefState.getPreferenceValue(getPreferenceId(), "telemetry.typing_speed.window_size")?.toInt() ?: 10
        val wpm = trackingService.getTypingSpeed(window_size).toDouble()

        record.put(wpmKey, wpm)

        return listOf(record)
    }

    override fun getStatus(): String {
        TODO("Not yet implemented")
    }

    override fun initializeModules() {

    }

    override fun getPreferenceList(): List<Preference> {
        return listOf(
            Preference(
                "telemetry.typing_speed",
                PreferenceType.BOOLEAN,
                "true",
                "Enable Typing Speed Telemetry",
                "Enable or disable typing speed telemetry. This will send your typing speed to the server for analysis."
            ),
            Preference(
                "telemetry.typing_speed.window_size",
                PreferenceType.INT,
                "10",
                "Time window size in seconds",
                "This determines what amount of time before the request is taken into account for calculating average typing speed."
            )
        )
    }

    override fun getPreferenceClass(): PreferenceClass {
        TODO("Not yet implemented")
    }
}