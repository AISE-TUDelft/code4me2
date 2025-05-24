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
    private lateinit var config: Config

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

    /**
     * Initializes the service by parsing the configuration file.
     *
     * This initialization block:
     * 1. Loads the "modules" section from the configuration
     * 2. Parses module categories if they exist
     * 3. Parses available modules if they exist
     */
    init {
        // first make sure the config is loaded
        config = ConfigFactory.parseResources(this.javaClass.classLoader, "plugin.conf").resolve()

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
                    availableModules.add(
                        ModuleConfig(
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
                        ),
                    )
                }
            }
        }

        if (highLevelConfig.hasPath("server")) {
            val serverConfig = highLevelConfig.getConfig("server")
            this.serverConfig = ServerConfig.fromConfig(serverConfig)
        }

        // Parse Google OAuth configuration
        if (highLevelConfig.hasPath("auth") && highLevelConfig.getConfig("auth").hasPath("google")) {
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
        return availableModules.mapNotNull { moduleConfig ->
            try {
                val moduleClass = Class.forName(moduleConfig.className)
                moduleClass.getDeclaredConstructor().newInstance() as? PluginModule
            } catch (e: Exception) {
                // If instantiation fails, skip this module
                null
            }
        }
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
 */
data class ModuleConfig(
    val id: String,
    val className: String,
    val name: String,
    val type: ModuleCategoryConfig,
    val description: String,
    val enabled: Boolean,
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
