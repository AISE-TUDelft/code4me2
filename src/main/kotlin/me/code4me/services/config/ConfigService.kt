package me.code4me.services.config

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import me.code4me.services.modules.PluginModule

fun getConfig(): ConfigService {
    return service<ConfigService>()
}

/**
 * Service for managing module configuration from HOCON config file.
 *
 * This service is responsible for:
 * - Loading module configurations from the plugin.conf file
 * - Parsing module categories and their properties
 * - Maintaining a list of available modules
 * - Instantiating module classes dynamically
 *
 * The configuration uses the HOCON format (Human-Optimized Config Object Notation)
 * which is a superset of JSON with additional features like comments and includes.
 */
@Service
class ConfigService {
    /**
     * The parsed configuration from the plugin.conf resource file.
     */
    private var config: Config =
        ConfigFactory.parseResources(this.javaClass.classLoader, "plugin.conf").resolve()

    /**
     * List of available modules parsed from the configuration.
     */
    private val availableModules: MutableList<ModuleConfig> = mutableListOf()

    /**
     * Map of module categories parsed from the configuration.
     * Keys are category IDs and values are [ModuleCategoryConfig] objects.
     */
    private val moduleCategories: MutableMap<String, ModuleCategoryConfig> = mutableMapOf()

    private var serverConfig: ServerConfig? = null

    /**
     * Google OAuth configuration parsed from the configuration.
     */
    private var googleOAuthConfig: GoogleOAuthConfig? = null

    private var modulesFlattened: List<ModuleConfig>? = null

    /**
     * Initializes the service by parsing the configuration file.
     *
     * This initialization block:
     * 1. Loads the "modules" section from the configuration
     * 2. Parses module categories if they exist
     * 3. Parses available modules if they exist
     */
    init {

        // load the high-level config configuration
        val highLevelConfig = config.getConfig("config")
        if (highLevelConfig.hasPath("modules")) {
            val modulesConfig = highLevelConfig.getConfig("modules")

            // Parse module categories
            if (modulesConfig.hasPath("categories")) {
                val categoriesConfig = modulesConfig.getConfig("categories")
                categoriesConfig.root().keys.forEach { categoryKey ->
                    val categoryConfig = categoriesConfig.getConfig(categoryKey)
                    moduleCategories[categoryKey] =
                        ModuleCategoryConfig(
                            id = categoryKey,
                            path = categoryConfig.getString("path"),
                            description = categoryConfig.getString("description"),
                        )
                }
            }

            // Parse available modules
            if (modulesConfig.hasPath("available")) {
                val modulesList = modulesConfig.getConfigList("available")
                modulesList.forEach { moduleConfig ->
                    availableModules.add(parseModuleConfig(moduleConfig, moduleCategories))
                }
            }
        }

        if (highLevelConfig.hasPath("server")) {
            val serverConfig = highLevelConfig.getConfig("server")
            this.serverConfig = ServerConfig.fromConfig(serverConfig)
        }

        // Parse Google OAuth configuration
        if (highLevelConfig.hasPath("auth") &&
            highLevelConfig.getConfig("auth").hasPath("google")
        ) {
            val googleConfig = highLevelConfig.getConfig("auth").getConfig("google")
            this.googleOAuthConfig = GoogleOAuthConfig.fromConfig(googleConfig)
        }
    }

    /**
     * Gets all available modules from the configuration.
     *
     * @return A list of [ModuleConfig] objects representing available modules.
     */
    fun getAvailableModules(): List<ModuleConfig> {
        return availableModules
    }

    /**
     * Gets the server configuration.
     *
     * @return The [ServerConfig] object representing the server configuration.
     */
    fun getServerConfig(): ServerConfig? {
        return serverConfig
    }

    /**
     * Gets the Google OAuth configuration.
     *
     * @return The [GoogleOAuthConfig] object representing the Google OAuth configuration.
     */
    fun getGoogleOAuthConfig(): GoogleOAuthConfig? {
        return googleOAuthConfig
    }

    /**
     * Gets all module categories from the configuration.
     *
     * @return A map of category IDs to [ModuleCategoryConfig] objects.
     */
    fun getModuleCategories(): Map<String, ModuleCategoryConfig> {
        return moduleCategories
    }

    /**
     * Parses a module configuration from a Config object.
     *
     * This method recursively parses module configurations, including submodules and dependencies.
     *
     * @param moduleConfig The Config object containing the module configuration.
     * @param moduleCategories Map of module categories.
     * @return A ModuleConfig object representing the parsed module configuration.
     */
    private fun parseModuleConfig(
        moduleConfig: com.typesafe.config.Config,
        moduleCategories: Map<String, ModuleCategoryConfig>,
    ): ModuleConfig {
        // Parse submodules if they exist
        val submodules =
            if (moduleConfig.hasPath("submodules")) {
                moduleConfig.getConfigList("submodules").map { submoduleConfig ->
                    parseModuleConfig(submoduleConfig, moduleCategories)
                }
            } else {
                emptyList()
            }

        // Parse dependencies if they exist
        val dependencies =
            if (moduleConfig.hasPath("dependencies")) {
                moduleConfig.getConfigList("dependencies").map { dependencyConfig ->
                    ModuleDependency(
                        moduleId = dependencyConfig.getString("moduleId"),
                        isHard = dependencyConfig.getBoolean("isHard"),
                    )
                }
            } else {
                emptyList()
            }

        return ModuleConfig(
            id = moduleConfig.getString("id"),
            className = moduleConfig.getString("class"),
            name = moduleConfig.getString("name"),
            type =
                moduleCategories[moduleConfig.getString("type")] ?: ModuleCategoryConfig(
                    id = "unknown",
                    path = "unknown",
                    description = "Unknown category",
                ),
            description = moduleConfig.getString("description"),
            enabled = if (moduleConfig.hasPath("enabled")) moduleConfig.getBoolean("enabled") else false,
            submodules = submodules,
            dependencies = dependencies,
        )
    }

    /**
     * Instantiates all available modules using reflection.
     *
     * This method:
     * 1. Iterates through all available module configurations
     * 2. Attempts to load the class specified by the className property
     * 3. Instantiates the class using its default constructor
     * 4. Casts the instance to a [PluginModule]
     * 5. Returns a list of successfully instantiated modules
     *
     * @return A list of instantiated [PluginModule] objects.
     */
    fun instantiateModules(): List<PluginModule> {
        return instantiateModulesRecursive(availableModules)
    }

    /**
     * Instantiates modules from a specific list of module configurations.
     *
     * This is useful for instantiating submodules of a specific module.
     *
     * @param moduleConfigs List of module configurations to instantiate.
     * @return A list of instantiated [PluginModule] objects.
     */
    fun instantiateModulesFromConfigs(moduleConfigs: List<ModuleConfig>): List<PluginModule> {
        return instantiateModulesRecursive(moduleConfigs)
    }

    /**
     * Recursively instantiates modules and their submodules.
     *
     * @param moduleConfigs List of module configurations to instantiate.
     * @return A list of instantiated [PluginModule] objects.
     */
    private fun instantiateModulesRecursive(moduleConfigs: List<ModuleConfig>): List<PluginModule> {
        val modules = mutableListOf<PluginModule>()

        moduleConfigs.forEach { moduleConfig ->
            try {
                val moduleClass = Class.forName(moduleConfig.className)
                val module = moduleClass.getDeclaredConstructor().newInstance() as? PluginModule

                if (module != null) {
                    modules.add(module)

                    // Recursively instantiate submodules
                    val submodules = instantiateModulesRecursive(moduleConfig.submodules)

                    // Register submodules with their parent module
                    submodules.forEach { submodule ->
                        // Find the dependency configuration for this submodule
                        val dependency = moduleConfig.dependencies.find { it.moduleId == submodule.getModuleId() }
                        // Register the submodule, specifying whether it's a hard dependency
                        module.registerSubmodule(submodule, dependency?.isHard ?: false)
                    }
                }
            } catch (e: Exception) {
                // If instantiation fails, skip this module
                null
            }
        }

        return modules
    }

    /**
     * Finds all modules that transitively depend on the module with the given ID via hard dependencies,
     * and returns the complete dependency chains leading to the target module.
     *
     * This method:
     * 1. Searches the entire module hierarchy including submodules to find the target module
     * 2. Identifies all modules that have a hard dependency on the target module (directly or indirectly)
     * 3. Constructs and returns the complete dependency chains from root modules to the target
     *
     * @param moduleId The ID of the module to find dependants for (can be at any level in the hierarchy)
     * @return A list of lists of [ModuleConfig] objects, where each inner list represents a complete
     *         dependency chain leading to the target module
     */
    fun getTransitiveHardDependants(moduleId: String): List<ModuleConfig> {
        val dependencyChains = mutableListOf<List<ModuleConfig>>()
        val allModules = getAllModulesFlattened()

        // Find the target module in the flattened list
        val targetModule = allModules.find { it.id == moduleId }
        if (targetModule == null) return emptyList()

        // Map to store direct hard dependants for each module
        val directDependantsMap = mutableMapOf<String, List<ModuleConfig>>()

        // Precompute direct hard dependants for each module
        allModules.forEach { module ->
            directDependantsMap[module.id] =
                allModules.filter { potentialDependant ->
                    potentialDependant.dependencies.any {
                        it.moduleId == module.id && it.isHard
                    }
                }
        }

        // Recursively build all dependency chains
        fun buildDependencyChains(
            currentModule: ModuleConfig,
            currentChain: List<ModuleConfig>,
        ) {
            val directDependants = directDependantsMap[currentModule.id] ?: emptyList()

            if (directDependants.isEmpty()) {
                // If there are no dependants, this is a complete chain
                if (currentChain.isNotEmpty()) {
                    dependencyChains.add(currentChain)
                }
                return
            }

            for (dependant in directDependants) {
                // Avoid cycles in the dependency chain
                if (dependant.id !in currentChain.map { it.id }) {
                    buildDependencyChains(dependant, currentChain + dependant)
                }
            }
        }

        // Start building chains from the target module
        buildDependencyChains(targetModule, listOf(targetModule))

        return dependencyChains.flatten()
    }

    /**
     * Returns a flattened list of all modules and their submodules.
     */
    private fun getAllModulesFlattened(): List<ModuleConfig> {
        if (modulesFlattened != null) {
            return modulesFlattened!!
        }
        val result = mutableListOf<ModuleConfig>()

        fun addModuleAndSubmodules(module: ModuleConfig) {
            result.add(module)
            module.submodules.forEach { submodule ->
                addModuleAndSubmodules(submodule)
            }
        }

        availableModules.forEach { addModuleAndSubmodules(it) }
        modulesFlattened = result
        return result
    }

    companion object {
        /**
         * Gets the singleton instance of the ModuleConfigService.
         *
         * @return The [ConfigService] instance.
         */
        fun getInstance(): ConfigService {
            return service()
        }
    }
}

/**
 * Data class representing a module configuration.
 *
 * This class holds all the configuration properties for a plugin module.
 *
 * @property id The unique identifier of the module.
 * @property className The fully qualified class name of the module implementation.
 * @property name The display name of the module.
 * @property type The category configuration to which this module belongs.
 * @property description A description of the module's functionality.
 * @property enabled Whether the module is enabled by default.
 * @property submodules List of submodules for this module.
 * @property dependencies List of module dependencies.
 */
data class ModuleConfig(
    val id: String,
    val className: String,
    val name: String,
    val type: ModuleCategoryConfig,
    val description: String,
    val enabled: Boolean,
    val submodules: List<ModuleConfig> = emptyList(),
    val dependencies: List<ModuleDependency> = emptyList(),
)

/**
 * Data class representing a module dependency.
 *
 * @property moduleId The ID of the module that is depended on.
 * @property isHard Whether this is a hard dependency (true) or soft dependency (false).
 *             Hard dependencies are required for the module to function, while soft dependencies are optional.
 */
data class ModuleDependency(
    val moduleId: String,
    val isHard: Boolean,
)

/**
 * Data class representing a module category configuration.
 *
 * Categories are used to group related modules together.
 *
 * @property id The unique identifier of the category.
 * @property path The path or location of the category in the UI hierarchy.
 * @property description A description of the category.
 */
data class ModuleCategoryConfig(
    val id: String,
    val path: String,
    val description: String,
)

data class ServerConfig(
    val host: String,
    val port: Int,
    val contextPath: String,
    val timeout: Int,
) {
    companion object {
        fun fromConfig(config: Config): ServerConfig {
            return ServerConfig(
                host = config.getString("host"),
                port = config.getInt("port"),
                contextPath = config.getString("contextPath"),
                timeout = config.getInt("timeout"),
            )
        }
    }
}

/**
 * Data class representing Google OAuth configuration.
 *
 * @property clientId The Google OAuth client ID.
 */
data class GoogleOAuthConfig(
    val clientId: String,
) {
    companion object {
        fun fromConfig(config: Config): GoogleOAuthConfig {
            return GoogleOAuthConfig(
                clientId = config.getString("clientId"),
            )
        }
    }
}
