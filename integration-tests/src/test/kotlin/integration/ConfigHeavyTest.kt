package me.code4me.services.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.code4me.services.config.models.*
import org.junit.Test

class ConfigServiceTest : BasePlatformTestCase() {

    companion object {
        private const val SAMPLE_CONFIG = """
            config {
              modules {
                available = [
                  {
                    id = "BehavioralTelemetryAggregator"
                    class = "me.code4me.services.modules.aggregators.BaseBehavioralTelemetryAggregator"
                    name = "Behavioral Telemetry Aggregator"
                    type = "aggregator"
                    description = "Module for aggregating behavioral telemetry data"
                    enabled = true
                    submodules = [
                      {
                        id = "TimeSinceLastAcceptedCompletion"
                        class = "me.code4me.services.modules.telemetry.behavioral.TimeSinceLastAcceptedCompletion"
                        name = "Time Since Last Accepted Completion"
                        type = "telemetry"
                        description = "Calculates the time since the last accepted completion"
                        enabled = true
                      },
                      {
                        id = "TypingSpeed"
                        class = "me.code4me.services.modules.telemetry.behavioral.TypingSpeed"
                        name = "Typing Speed"
                        type = "telemetry"
                        description = "Calculates the typing speed of the user"
                        enabled = false
                      }
                    ]
                    dependencies = [
                      {
                        moduleId = "TimeSinceLastAcceptedCompletion"
                        isHard = true
                      },
                      {
                        moduleId = "TypingSpeed"
                        isHard = false
                      }
                    ]
                  },
                  {
                    id = "contextAggregator"
                    class = "me.code4me.services.modules.aggregators.BaseContextAggregator"
                    name = "Context Aggregator"
                    type = "aggregator"
                    description = "Module for aggregating context data"
                    enabled = true
                    submodules = [
                      {
                        id = "FileContextRetrievalModule"
                        class = "me.code4me.services.modules.context.FileContextRetrievalModule"
                        name = "File Context Retrieval Module"
                        type = "context"
                        description = "Retrieves context from the file"
                        enabled = true
                      }
                    ]
                    dependencies = [
                      {
                        moduleId = "FileContextRetrievalModule"
                        isHard = true
                      }
                    ]
                  }
                ]
                
                categories = {
                  behavioralTelemetry = {
                    path = "me.code4me.services.modules.telemetry.behavioral"
                    description = "Modules for behavioral telemetry collection"
                  }
                  context = {
                    path = "me.code4me.services.modules.context"
                    description = "Modules for context retrieval"
                  }
                  aggregators = {
                    path = "me.code4me.services.modules.aggregators"
                    description = "Modules for data aggregation"
                  }
                }
              }
              
              server {
                host = "http://127.0.0.1"
                port = 8008
                contextPath = "/api"
                timeout = 5000
              }
              
              auth {
                google {
                  clientId = "test-client-id"
                }
              }
              
              models {
                available = [
                  {
                    id = 1
                    name = "deepseek-coder-1.3b"
                    isChatModel = false
                    isDefault = true
                  }
                  {
                    id = 2
                    name = "starcoder2-3b"
                    isChatModel = false
                    isDefault = false
                  }
                  {
                    id = 3
                    name = "Ministral-8B-Instruct"
                    isChatModel = true
                    isDefault = true
                  }
                ]
                systemPrompt = "You are a helpful assistant"
              }
              
              languages {
                "Java" = 11
                "Kotlin" = 98
                "Python" = 47
                "JavaScript" = 169
                "unknown" = 179
              }
            }
        """

        private const val MINIMAL_CONFIG = """
            config {
              modules {
                available = []
                categories = {}
              }
            }
        """

        private const val EMPTY_CONFIG = """
            config {
            }
        """
    }

    @Test
    fun `test ConfigService initialization with string configuration`() {
        // Given
        val configService = ConfigService(SAMPLE_CONFIG)

        // Then
        assertNotNull(configService)
        assertNotNull(configService.getAvailableModules())
        assertNotNull(configService.getModuleCategories())
    }

    @Test
    fun `test available modules parsing`() {
        // Given
        val configService = ConfigService(SAMPLE_CONFIG)

        // When
        val modules = configService.getAvailableModules()

        // Then
        assertEquals(2, modules.size)

        val behavioralModule = modules.find { it.id == "BehavioralTelemetryAggregator" }
        assertNotNull(behavioralModule)
        assertEquals("me.code4me.services.modules.aggregators.BaseBehavioralTelemetryAggregator", behavioralModule!!.className)
        assertEquals("Behavioral Telemetry Aggregator", behavioralModule.name)
        assertTrue(behavioralModule.enabled)
        assertEquals(2, behavioralModule.submodules.size)
        assertEquals(2, behavioralModule.dependencies.size)

        val contextModule = modules.find { it.id == "contextAggregator" }
        assertNotNull(contextModule)
        assertEquals("me.code4me.services.modules.aggregators.BaseContextAggregator", contextModule!!.className)
        assertEquals(1, contextModule.submodules.size)
        assertEquals(1, contextModule.dependencies.size)
    }

    @Test
    fun `test submodules parsing`() {
        // Given
        val configService = ConfigService(SAMPLE_CONFIG)

        // When
        val modules = configService.getAvailableModules()
        val behavioralModule = modules.find { it.id == "BehavioralTelemetryAggregator" }

        // Then
        assertNotNull(behavioralModule)
        val submodules = behavioralModule!!.submodules
        assertEquals(2, submodules.size)

        val timeSinceModule = submodules.find { it.id == "TimeSinceLastAcceptedCompletion" }
        assertNotNull(timeSinceModule)
        assertTrue(timeSinceModule!!.enabled)

        val typingSpeedModule = submodules.find { it.id == "TypingSpeed" }
        assertNotNull(typingSpeedModule)
        assertFalse(typingSpeedModule!!.enabled)
    }

    @Test
    fun `test dependencies parsing`() {
        // Given
        val configService = ConfigService(SAMPLE_CONFIG)

        // When
        val modules = configService.getAvailableModules()
        val behavioralModule = modules.find { it.id == "BehavioralTelemetryAggregator" }

        // Then
        assertNotNull(behavioralModule)
        val dependencies = behavioralModule!!.dependencies
        assertEquals(2, dependencies.size)

        val hardDependency = dependencies.find { it.moduleId == "TimeSinceLastAcceptedCompletion" }
        assertNotNull(hardDependency)
        assertTrue(hardDependency!!.isHard)

        val softDependency = dependencies.find { it.moduleId == "TypingSpeed" }
        assertNotNull(softDependency)
        assertFalse(softDependency!!.isHard)
    }

    @Test
    fun `test module categories parsing`() {
        // Given
        val configService = ConfigService(SAMPLE_CONFIG)

        // When
        val categories = configService.getModuleCategories()

        // Then
        assertEquals(3, categories.size)

        val behavioralCategory = categories["behavioralTelemetry"]
        assertNotNull(behavioralCategory)
        assertEquals("me.code4me.services.modules.telemetry.behavioral", behavioralCategory!!.path)
        assertEquals("Modules for behavioral telemetry collection", behavioralCategory.description)

        val contextCategory = categories["context"]
        assertNotNull(contextCategory)
        assertEquals("me.code4me.services.modules.context", contextCategory!!.path)

        val aggregatorsCategory = categories["aggregators"]
        assertNotNull(aggregatorsCategory)
        assertEquals("me.code4me.services.modules.aggregators", aggregatorsCategory!!.path)
    }

    @Test
    fun `test server configuration parsing`() {
        // Given
        val configService = ConfigService(SAMPLE_CONFIG)

        // When
        val serverConfig = configService.getServerConfig()

        // Then
        assertNotNull(serverConfig)
        assertEquals("http://127.0.0.1", serverConfig!!.host)
        assertEquals(8008, serverConfig.port)
        assertEquals("/api", serverConfig.contextPath)
        assertEquals(5000, serverConfig.timeout)
    }

    @Test
    fun `test google oauth configuration parsing`() {
        // Given
        val configService = ConfigService(SAMPLE_CONFIG)

        // When
        val googleOAuthConfig = configService.getGoogleOAuthConfig()

        // Then
        assertNotNull(googleOAuthConfig)
        assertEquals("test-client-id", googleOAuthConfig!!.clientId)
    }

    @Test
    fun `test models configuration parsing`() {
        // Given
        val configService = ConfigService(SAMPLE_CONFIG)

        // When
        val modelsConfiguration = configService.getModelsConfiguration()

        // Then
        assertNotNull(modelsConfiguration)
        assertEquals(3, modelsConfiguration!!.availableModels.size)
        assertEquals("You are a helpful assistant", modelsConfiguration.systemPrompt)

        val chatModels = modelsConfiguration.getAvailableChatModels()
        assertEquals(1, chatModels.size)
        assertEquals("Ministral-8B-Instruct", chatModels[0].name)
        assertTrue(chatModels[0].isDefault)

        val completionModels = modelsConfiguration.getAvailableCompletionModels()
        assertEquals(2, completionModels.size)

        val defaultCompletionModel = completionModels.find { it.isDefault }
        assertNotNull(defaultCompletionModel)
        assertEquals("deepseek-coder-1.3b", defaultCompletionModel!!.name)
    }

    @Test
    fun `test languages configuration parsing`() {
        // Given
        val configService = ConfigService(SAMPLE_CONFIG)

        // When
        val languagesConfig = configService.getLanguagesConfig()

        // Then
        assertNotNull(languagesConfig)
        assertEquals(11, languagesConfig!!.getLanguageId("Java"))
        assertEquals(98, languagesConfig.getLanguageId("Kotlin"))
        assertEquals(47, languagesConfig.getLanguageId("Python"))
        assertEquals(169, languagesConfig.getLanguageId("JavaScript"))
        assertEquals(179, languagesConfig.getLanguageId("NonExistentLanguage"))
    }

    @Test
    fun `test languages configuration fuzzy matching`() {
        // Given
        val configService = ConfigService(SAMPLE_CONFIG)
        val languagesConfig = configService.getLanguagesConfig()!!

        // When & Then
        assertEquals(11, languagesConfig.getLanguageIdFuzzy("java"))
        assertEquals(98, languagesConfig.getLanguageIdFuzzy("kotlin"))
        assertEquals(47, languagesConfig.getLanguageIdFuzzy("py"))
        assertEquals(179, languagesConfig.getLanguageIdFuzzy("CompletelyUnknownLanguage"))
    }

    @Test
    fun `test transitive hard dependencies`() {
        // Given
        val configService = ConfigService(SAMPLE_CONFIG)

        // When
        val dependants = configService.getTransitiveHardDependants("TimeSinceLastAcceptedCompletion")

        // Then
        assertNotNull(dependants)
        assertTrue(dependants.any { it.id == "BehavioralTelemetryAggregator" })
    }

    @Test
    fun `test empty configuration handling`() {
        // Given
        val configService = ConfigService(EMPTY_CONFIG)

        // When & Then
        assertTrue(configService.getAvailableModules().isEmpty())
        assertTrue(configService.getModuleCategories().isEmpty())
        assertNull(configService.getServerConfig())
        assertNull(configService.getGoogleOAuthConfig())
        assertNull(configService.getModelsConfiguration())
        assertNull(configService.getLanguagesConfig())
    }

    @Test
    fun `test minimal configuration handling`() {
        // Given
        val configService = ConfigService(MINIMAL_CONFIG)

        // When & Then
        assertTrue(configService.getAvailableModules().isEmpty())
        assertTrue(configService.getModuleCategories().isEmpty())
        assertNull(configService.getServerConfig())
        assertNull(configService.getGoogleOAuthConfig())
        assertNull(configService.getModelsConfiguration())
        assertNull(configService.getLanguagesConfig())
    }

    @Test
    fun `test module instantiation with invalid classes`() {
        // Given
        val invalidConfig = """
            config {
              modules {
                available = [
                  {
                    id = "InvalidModule"
                    class = "com.non.existent.Class"
                    name = "Invalid Module"
                    type = "test"
                    description = "Test module with invalid class"
                    enabled = true
                  }
                ]
                categories = {}
              }
            }
        """
        val configService = ConfigService(invalidConfig)

        // When
        val instantiatedModules = configService.instantiateModules()

        // Then
        assertTrue(instantiatedModules.isEmpty())
    }

    @Test
    fun `test models configuration edge cases`() {
        // Given
        val configService = ConfigService(SAMPLE_CONFIG)
        val modelsConfiguration = configService.getModelsConfiguration()!!

        // When & Then
        assertEquals(1, modelsConfiguration.getModelIdByName("deepseek-coder-1.3b"))
        assertEquals(3, modelsConfiguration.getModelIdByName("Ministral-8B-Instruct"))
        assertNull(modelsConfiguration.getModelIdByName("non-existent-model"))

        assertEquals("deepseek-coder-1.3b", modelsConfiguration.modelNameById(1))
        assertEquals("starcoder2-3b", modelsConfiguration.modelNameById(2))
    }

    @Test
    fun `test complex dependency resolution`() {
        // Given
        val complexConfig = """
            config {
              modules {
                available = [
                  {
                    id = "ParentModule"
                    class = "test.ParentClass"
                    name = "Parent Module"
                    type = "parent"
                    description = "Parent module"
                    enabled = true
                    submodules = [
                      {
                        id = "ChildModule"
                        class = "test.ChildClass"
                        name = "Child Module"
                        type = "child"
                        description = "Child module"
                        enabled = true
                      }
                    ]
                    dependencies = [
                      {
                        moduleId = "ChildModule"
                        isHard = true
                      }
                    ]
                  },
                  {
                    id = "DependentModule"
                    class = "test.DependentClass"
                    name = "Dependent Module"
                    type = "dependent"
                    description = "Module that depends on child"
                    enabled = true
                    dependencies = [
                      {
                        moduleId = "ChildModule"
                        isHard = true
                      }
                    ]
                  }
                ]
                categories = {}
              }
            }
        """
        val configService = ConfigService(complexConfig)

        // When
        val dependants = configService.getTransitiveHardDependants("ChildModule")

        // Then
        assertEquals(2, dependants.size)
        assertTrue(dependants.any { it.id == "ParentModule" })
        assertTrue(dependants.any { it.id == "DependentModule" })
    }

    @Test
    fun `test configuration with special characters in language names`() {
        // Given
        val specialConfig = """
            config {
              languages {
                "C++" = 123
                "C#" = 55
                ".NET" = 200
                "F#" = 142
              }
            }
        """
        val configService = ConfigService(specialConfig)

        // When
        val languagesConfig = configService.getLanguagesConfig()

        // Then
        assertNotNull(languagesConfig)
        assertEquals(123, languagesConfig!!.getLanguageId("C++"))
        assertEquals(55, languagesConfig.getLanguageId("C#"))
        assertEquals(200, languagesConfig.getLanguageId(".NET"))
        assertEquals(142, languagesConfig.getLanguageId("F#"))
    }

    @Test
    fun `test default constructor loads from resource`() {
        // When
        val configService = ConfigService()

        // Then
        assertNotNull(configService)
        // Note: This test assumes plugin.conf exists in resources
        // The actual assertions would depend on the content of that file
    }
}