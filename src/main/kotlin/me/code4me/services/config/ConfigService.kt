package me.code4me.services.config

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import me.code4me.services.config.models.GoogleOAuthConfig
import me.code4me.services.config.models.LanguagesConfig
import me.code4me.services.config.models.ModelsConfiguration
import me.code4me.services.config.models.ModuleCategoryConfig
import me.code4me.services.config.models.ModuleConfig
import me.code4me.services.config.models.ModuleDependency
import me.code4me.services.config.models.ServerConfig
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
 * - Managing model configurations for AI/ML features
 *
 * The configuration uses the HOCON format (Human-Optimized Config Object Notation)
 * which is a superset of JSON with additional features like comments and includes.
 */
@Service
class ConfigService {
    /**
     * The parsed configuration from the plugin.conf file.
     * Automatically resolved to handle any includes or substitutions.
     *
     * If the system property "plugin.conf.path" is set, it will load the configuration
     * from that file path instead of the resource.
     */
    private val config: Config = loadConfiguration()

    /**
     * Loads the configuration from either a custom path specified by the system property
     * "plugin.conf.path" or from the default resource.
     *
     * @return The loaded configuration
     */
    private fun loadConfiguration(): Config {
        val customPath = System.getProperty("plugin.conf.path")
        return if (customPath != null) {
            ConfigFactory.parseFile(java.io.File(customPath)).resolve()
        } else {
            ConfigFactory.parseResources(
                this.javaClass.classLoader,
                "plugin.conf",
            ).resolve()
        }
    }

    /**
     * List of available modules parsed from the configuration.
     * Populated during initialization from the 'modules.available' section.
     */
    private val availableModules: MutableList<ModuleConfig> = mutableListOf()

    /**
     * Map of module categories parsed from the configuration.
     * Keys are category IDs and values are ModuleCategoryConfig objects.
     * Used for categorizing and organizing modules by type.
     */
    private val moduleCategories: MutableMap<String, ModuleCategoryConfig> = mutableMapOf()

    /**
     * Cache of instantiated module instances.
     * Keys are class names and values are the instantiated module objects.
     * Used to prevent re-instantiation of modules when they're requested multiple times.
     */
    private val moduleInstanceCache: MutableMap<String, PluginModule> = mutableMapOf()

    /**
     * Server configuration parsed from the 'server' section.
     * Contains host, port, context path, and timeout settings.
     */
    private var serverConfig: ServerConfig? = null

    /**
     * Google OAuth configuration parsed from the 'auth.google' section.
     * Contains client credentials and OAuth flow settings.
     */
    private var googleOAuthConfig: GoogleOAuthConfig? = null

    /**
     * Models configuration parsed from the 'models' section.
     * Contains available models and system prompt configuration.
     */
    private var modelsConfiguration: ModelsConfiguration? = null

    /**
     * Languages configuration parsed from the 'languages' section.
     * Contains a mapping of language names to their corresponding IDs.
     */
    private var languagesConfig: LanguagesConfig? = null

    /**
     * Lazy-initialized flattened list of all modules and their submodules.
     * Built only when first accessed to improve startup performance.
     */
    private val modulesFlattened: List<ModuleConfig> by lazy {
        buildFlattenedModulesList()
    }

    /**
     * Initializes the service by parsing the entire configuration file.
     * Called automatically when the service is first accessed.
     */
    init {
        parseConfiguration()
    }

    // --- Public API ---

    /**
     * Gets all available modules from the configuration.
     *
     * @return A defensive copy of the list of ModuleConfig objects representing available modules.
     */
    fun getAvailableModules(): List<ModuleConfig> = availableModules.toList()

    /**
     * Gets the server configuration.
     *
     * @return The ServerConfig object representing the server configuration, or null if not configured.
     */
    fun getServerConfig(): ServerConfig? = serverConfig

    /**
     * Gets the Google OAuth configuration.
     *
     * @return The GoogleOAuthConfig object representing the Google OAuth configuration, or null if not configured.
     */
    fun getGoogleOAuthConfig(): GoogleOAuthConfig? = googleOAuthConfig

    /**
     * Gets the models configuration.
     *
     * @return The ModelsConfiguration object representing the models configuration, or null if not configured.
     */
    fun getModelsConfiguration(): ModelsConfiguration? = modelsConfiguration

    /**
     * Gets the languages configuration.
     *
     * @return The LanguagesConfig object representing the languages configuration, or null if not configured.
     */
    fun getLanguagesConfig(): LanguagesConfig? = languagesConfig

    /**
     * Gets all module categories from the configuration.
     *
     * @return A defensive copy of the map of category IDs to ModuleCategoryConfig objects.
     */
    fun getModuleCategories(): Map<String, ModuleCategoryConfig> = moduleCategories.toMap()

    /**
     * Instantiates all available modules using reflection.
     *
     * This method:
     * 1. Iterates through all available module configurations
     * 2. Attempts to load the class specified by the className property
     * 3. Instantiates the class using its default constructor
     * 4. Recursively instantiates and registers submodules
     * 5. Returns a list of successfully instantiated modules
     *
     * @return A list of instantiated PluginModule objects.
     */
    fun instantiateModules(): List<PluginModule> = instantiateModulesRecursive(availableModules)

    /**
     * Instantiates modules from a specific list of module configurations.
     *
     * This is useful for instantiating submodules of a specific module or a filtered set of modules.
     *
     * @param moduleConfigs List of module configurations to instantiate.
     * @return A list of instantiated PluginModule objects.
     */
    fun instantiateModulesFromConfigs(moduleConfigs: List<ModuleConfig>): List<PluginModule> = instantiateModulesRecursive(moduleConfigs)

    /**
     * Finds all modules that transitively depend on the module with the given ID via hard dependencies.
     *
     * This method:
     * 1. Searches the entire module hierarchy including submodules to find the target module
     * 2. Identifies all modules that have a hard dependency on the target module (directly or indirectly)
     * 3. Constructs and returns the complete dependency chains from root modules to the target
     *
     * @param moduleId The ID of the module to find dependants for (can be at any level in the hierarchy)
     * @return A flattened list of ModuleConfig objects that depend on the target module
     */
    fun getTransitiveHardDependants(moduleId: String): List<ModuleConfig> {
        val allModules = modulesFlattened
        val targetModule = allModules.find { it.id == moduleId } ?: return emptyList()

        // Build a map of module ID -> list of modules that depend on it
        val directDependantsMap = buildDependantsMap(allModules)
        val dependencyChains = mutableListOf<List<ModuleConfig>>()

        // Recursively build all dependency chains starting from the target module
        buildDependencyChains(targetModule, emptyList(), directDependantsMap, dependencyChains)

        return dependencyChains.flatten()
    }

    // --- Private Implementation ---

    /**
     * Parses the entire configuration file by delegating to specific parsing methods.
     * This method serves as the main entry point for configuration parsing.
     */
    private fun parseConfiguration() {
        // All configuration is nested under the 'config' top-level key
        val highLevelConfig = config.getConfig("config")

        parseModulesConfiguration(highLevelConfig)
        parseServerConfiguration(highLevelConfig)
        parseAuthConfiguration(highLevelConfig)
        parseModelConfiguration(highLevelConfig)
        parseLanguagesConfiguration(highLevelConfig)
    }

    /**
     * Parses the modules section of the configuration.
     * Handles both module categories and available module definitions.
     *
     * @param highLevelConfig The top-level configuration object
     */
    private fun parseModulesConfiguration(highLevelConfig: Config) {
        if (!highLevelConfig.hasPath("modules")) return

        val modulesConfig = highLevelConfig.getConfig("modules")

        // Parse categories first as they're referenced by module definitions
        parseModuleCategories(modulesConfig)
        parseAvailableModules(modulesConfig)
    }

    /**
     * Parses module categories from the 'modules.categories' section.
     * Categories are used to organize and classify different types of modules.
     *
     * @param modulesConfig The modules configuration section
     */
    private fun parseModuleCategories(modulesConfig: Config) {
        if (!modulesConfig.hasPath("categories")) return

        val categoriesConfig = modulesConfig.getConfig("categories")

        // Iterate through each category key and parse its configuration
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

    /**
     * Parses available modules from the 'modules.available' section.
     * Each module can have submodules and dependencies defined recursively.
     *
     * @param modulesConfig The modules configuration section
     */
    private fun parseAvailableModules(modulesConfig: Config) {
        if (!modulesConfig.hasPath("available")) return

        val modulesList = modulesConfig.getConfigList("available")

        // Parse each module configuration and add to available modules list
        modulesList.forEach { moduleConfig ->
            availableModules.add(parseModuleConfig(moduleConfig, moduleCategories))
        }
    }

    /**
     * Parses server configuration from the 'server' section.
     * Uses the ServerConfig.fromConfig() factory method for parsing.
     *
     * @param highLevelConfig The top-level configuration object
     */
    private fun parseServerConfiguration(highLevelConfig: Config) {
        if (highLevelConfig.hasPath("server")) {
            val serverConfigData = highLevelConfig.getConfig("server")
            serverConfig = ServerConfig.fromConfig(serverConfigData)
        }
    }

    /**
     * Parses authentication configuration from the 'auth' section.
     * Currently supports Google OAuth configuration under 'auth.google'.
     *
     * @param highLevelConfig The top-level configuration object
     */
    private fun parseAuthConfiguration(highLevelConfig: Config) {
        if (highLevelConfig.hasPath("auth") && highLevelConfig.getConfig("auth").hasPath("google")) {
            val googleConfig = highLevelConfig.getConfig("auth").getConfig("google")
            googleOAuthConfig = GoogleOAuthConfig.fromConfig(googleConfig)
        }
    }

    /**
     * Parses model configuration from the 'models' section.
     * Handles available models and system prompt configuration.
     *
     * @param highLevelConfig The top-level configuration object
     */
    private fun parseModelConfiguration(highLevelConfig: Config) {
        if (highLevelConfig.hasPath("models")) {
            val modelsConfig = highLevelConfig.getConfig("models")
            modelsConfiguration = ModelsConfiguration.fromConfig(modelsConfig)
        }
    }

    /**
     * Parses languages configuration from the 'languages' section.
     * Creates a mapping of language names to their corresponding IDs.
     *
     * @param highLevelConfig The top-level configuration object
     */
    private fun parseLanguagesConfiguration(highLevelConfig: Config) {
        if (highLevelConfig.hasPath("languages")) {
            val languagesConfig = highLevelConfig.getConfig("languages")
            this.languagesConfig = LanguagesConfig.fromConfig(languagesConfig)
        }
    }

    /**
     * Parses a single module configuration from a Config object.
     *
     * This method recursively parses module configurations, including submodules and dependencies.
     * It handles the complete module definition including metadata, type resolution, and relationships.
     *
     * @param moduleConfig The Config object containing the module configuration
     * @param moduleCategories Map of module categories for type resolution
     * @return A complete ModuleConfig object representing the parsed module configuration
     */
    private fun parseModuleConfig(
        moduleConfig: Config,
        moduleCategories: Map<String, ModuleCategoryConfig>,
    ): ModuleConfig {
        // Parse nested structures first
        val submodules = parseSubmodules(moduleConfig, moduleCategories)
        val dependencies = parseDependencies(moduleConfig)

        return ModuleConfig(
            id = moduleConfig.getString("id"),
            className = moduleConfig.getString("class"),
            name = moduleConfig.getString("name"),
            type = resolveModuleType(moduleConfig.getString("type"), moduleCategories),
            description = moduleConfig.getString("description"),
            // Default to false if enabled flag is not specified
            enabled = moduleConfig.getBoolean("enabled").takeIf { moduleConfig.hasPath("enabled") } ?: false,
            submodules = submodules,
            dependencies = dependencies,
        )
    }

    /**
     * Parses submodules for a given module configuration.
     * Submodules are parsed recursively using the same parsing logic as top-level modules.
     *
     * @param moduleConfig The parent module configuration
     * @param moduleCategories Map of module categories for type resolution
     * @return List of parsed submodule configurations
     */
    private fun parseSubmodules(
        moduleConfig: Config,
        moduleCategories: Map<String, ModuleCategoryConfig>,
    ): List<ModuleConfig> =
        if (moduleConfig.hasPath("submodules")) {
            moduleConfig.getConfigList("submodules").map { submoduleConfig ->
                parseModuleConfig(submoduleConfig, moduleCategories)
            }
        } else {
            emptyList()
        }

    /**
     * Parses dependencies for a given module configuration.
     * Dependencies define relationships between modules and whether they are hard or soft dependencies.
     *
     * @param moduleConfig The module configuration to parse dependencies from
     * @return List of parsed module dependencies
     */
    private fun parseDependencies(moduleConfig: Config): List<ModuleDependency> =
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

    /**
     * Resolves a module type ID to its corresponding ModuleCategoryConfig.
     * Falls back to an "unknown" category if the type is not found.
     *
     * @param typeId The type identifier from the module configuration
     * @param moduleCategories Map of available module categories
     * @return The resolved ModuleCategoryConfig or a default "unknown" category
     */
    private fun resolveModuleType(
        typeId: String,
        moduleCategories: Map<String, ModuleCategoryConfig>,
    ): ModuleCategoryConfig =
        moduleCategories[typeId] ?: ModuleCategoryConfig(
            id = "unknown",
            path = "unknown",
            description = "Unknown category",
        )

    /**
     * Recursively instantiates modules and their submodules using reflection.
     *
     * This method attempts to instantiate each module and, if successful, recursively
     * instantiates its submodules and registers them with the parent module.
     *
     * @param moduleConfigs List of module configurations to instantiate
     * @return List of successfully instantiated PluginModule objects
     */
    private fun instantiateModulesRecursive(moduleConfigs: List<ModuleConfig>): List<PluginModule> {
        return moduleConfigs.mapNotNull { moduleConfig ->
            instantiateModule(moduleConfig)?.also { module ->
                // Register submodules with their parent after successful instantiation
                registerSubmodules(module, moduleConfig)
            }
        }
    }

    /**
     * Instantiates a single module using reflection or returns a cached instance if available.
     * Handles class loading and instantiation with appropriate error handling.
     *
     * @param moduleConfig The module configuration containing class name and metadata
     * @return The instantiated PluginModule or null if instantiation fails
     */
    private fun instantiateModule(moduleConfig: ModuleConfig): PluginModule? =
        try {
            // Check if we already have an instance of this module in the cache
            moduleInstanceCache[moduleConfig.className]?.let { return it }

            val moduleClass = Class.forName(moduleConfig.className)
            // Use default constructor and cast to PluginModule
            val instance = moduleClass.getDeclaredConstructor().newInstance() as? PluginModule

            // Cache the instance for future use
            instance?.let { moduleInstanceCache[moduleConfig.className] = it }

            instance
        } catch (e: Exception) {
            // Silently ignore instantiation failures - could be enhanced with logging
            null
        }

    /**
     * Registers submodules with their parent module.
     * Instantiates submodules recursively and registers them based on dependency configuration.
     *
     * @param module The parent module to register submodules with
     * @param moduleConfig The configuration containing submodule and dependency information
     */
    private fun registerSubmodules(
        module: PluginModule,
        moduleConfig: ModuleConfig,
    ) {
        val submodules = instantiateModulesRecursive(moduleConfig.submodules)

        submodules.forEach { submodule ->
            // Find the dependency configuration to determine if it's a hard dependency
            val dependency = moduleConfig.dependencies.find { it.moduleId == submodule.getModuleId() }
            module.registerSubmodule(submodule, dependency?.isHard ?: false)
        }
    }

    /**
     * Builds a flattened list of all modules and their submodules.
     * This is used for efficient searching and dependency analysis.
     *
     * @return A flat list containing all modules and submodules at all levels
     */
    private fun buildFlattenedModulesList(): List<ModuleConfig> {
        val result = mutableListOf<ModuleConfig>()

        // Recursive function to add a module and all its submodules
        fun addModuleAndSubmodules(module: ModuleConfig) {
            result.add(module)
            module.submodules.forEach(::addModuleAndSubmodules)
        }

        // Start with top-level modules and recurse through submodules
        availableModules.forEach(::addModuleAndSubmodules)
        return result
    }

    /**
     * Builds a map of module IDs to their direct hard dependants.
     * This is used for efficient dependency chain analysis.
     *
     * @param allModules Complete list of all modules (flattened)
     * @return Map where keys are module IDs and values are lists of modules that depend on them
     */
    private fun buildDependantsMap(allModules: List<ModuleConfig>): Map<String, List<ModuleConfig>> =
        allModules.associateWith { module ->
            // Find all modules that have this module as a hard dependency
            allModules.filter { potentialDependant ->
                potentialDependant.dependencies.any { dep ->
                    dep.moduleId == module.id && dep.isHard
                }
            }
        }.mapKeys { it.key.id }

    /**
     * Recursively builds dependency chains from a target module to all its dependants.
     * Uses depth-first search to find all possible dependency paths.
     *
     * @param currentModule The current module in the dependency chain
     * @param currentChain The current chain of modules leading to this point
     * @param directDependantsMap Pre-computed map of direct dependants for efficiency
     * @param dependencyChains Output list to collect all complete dependency chains
     */
    private fun buildDependencyChains(
        currentModule: ModuleConfig,
        currentChain: List<ModuleConfig>,
        directDependantsMap: Map<String, List<ModuleConfig>>,
        dependencyChains: MutableList<List<ModuleConfig>>,
    ) {
        val directDependants = directDependantsMap[currentModule.id] ?: emptyList()

        // If no dependants, this is a leaf node - add the complete chain
        if (directDependants.isEmpty()) {
            if (currentChain.isNotEmpty()) {
                dependencyChains.add(currentChain)
            }
            return
        }

        // Avoid cycles by checking if dependant is already in the chain
        val currentChainIds = currentChain.map { it.id }.toSet()
        directDependants.forEach { dependant ->
            if (dependant.id !in currentChainIds) {
                buildDependencyChains(dependant, currentChain + dependant, directDependantsMap, dependencyChains)
            }
        }
    }

    companion object {
        /**
         * Gets the singleton instance of the ConfigService.
         * This is an alternative to the top-level getConfig() function.
         *
         * @return The ConfigService instance managed by IntelliJ's service framework
         */
        fun getInstance(): ConfigService = service()
    }
}
