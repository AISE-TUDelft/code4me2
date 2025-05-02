import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass

// Example implementation of a telemetry module
class TelemetryModule : PluginModule {
    override val moduleName = "TelemetryModule"

    override fun sendDataToCore(data: Map<String, Any>) {
        println("TelemetryModule sending data: $data")
        // Logic to send data to the core
    }

    override fun getStatus(): String {
        return "TelemetryModule is active"
    }

    override fun getPreferenceList(): List<Preference> {
        TODO("Not yet implemented")
    }

    override fun getPreferenceClass(): PreferenceClass {
        TODO("Not yet implemented")
    }
}