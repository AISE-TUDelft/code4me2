import me.code4me.utils.configuration.PreferenceCapable

// Shared interface for telemetry and context retrieval modules
interface PluginModule : PreferenceCapable {
    val moduleName: String

    // Method to send data to the core
    fun sendDataToCore(data: Map<String, Any>)

    // Optional: Method to retrieve configuration or status
    fun getStatus(): String
}