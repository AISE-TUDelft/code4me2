import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType


fun getModuleManager(): Code4me2CoreCommunicator {
    return service<Code4me2CoreCommunicator>()
}

@Service
class Code4me2CoreCommunicator : PluginModule{
    private val pluginModules = mutableListOf<PluginModule>()

    // Register a module
    fun registerModule(pluginModule: PluginModule) {
        pluginModules.add(pluginModule)
        println("Module registered: ${pluginModule.moduleName}")
    }

    // Process data from a module
    fun processData(pluginModule: PluginModule, data: Map<String, Any>) {
        println("Processing data from ${pluginModule.moduleName}: $data")
        // Forward data to the core platform (implementation omitted)
    }

    override val moduleName: String
        get() = "ModuleManager"

    override fun sendDataToCore(data: Map<String, Any>) {
        TODO("Not yet implemented")
    }

    override fun getStatus(): String {
        TODO("Not yet implemented")
    }

    override fun getPreferenceList(): List<Preference> {
        return listOf(
            Preference(
                key = "useAI",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Use AI Completion",
                description = "Use AI-powered code completion"
            ),
            Preference(
                key = "maxSuggestions",
                type = PreferenceType.STRING,
                defaultValue = "5",
                displayName = "Max Suggestions",
                description = "Maximum number of suggestions to show"
            )
        )
    }

    override fun getPreferenceClass(): PreferenceClass {
        TODO("Not yet implemented")
    }

    override fun toString(): String {
        return moduleName
    }
}
