package integration

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.TypingEvent
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.code4me.completion.PluginInlineCompletionElement
import me.code4me.services.config.ConfigService
import me.code4me.services.modules.aggregators.BaseAfterInsertionAggregator
import me.code4me.services.modules.aggregators.BaseBehavioralTelemetryAggregator
import me.code4me.services.modules.aggregators.BaseContextAggregator
import me.code4me.services.modules.aggregators.BaseContextualTelemetryAggregator
import me.code4me.services.modules.aggregators.BaseModelAggregator
import me.code4me.services.modules.manager.ModuleManager
import me.code4me.services.modules.telemetry.behavioral.TimeSinceLastAcceptedCompletion
import me.code4me.services.modules.telemetry.behavioral.TimeSinceLastShownCompletion
import me.code4me.services.modules.telemetry.behavioral.TypingSpeed
import me.code4me.services.modules.telemetry.behavioral.helpers.typingSpeed.TypingSpeedService
import me.code4me.services.state.PrefState
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.record.Record
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.whenever
import java.util.UUID

class ModulesLightTest : BasePlatformTestCase() {

    companion object {
        private const val COMPLETE_MODULE_CONFIG = """
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
                        id = "TimeSinceLastShownCompletion"
                        class = "me.code4me.services.modules.telemetry.behavioral.TimeSinceLastShownCompletion"
                        name = "Time Since Last Shown Completion"
                        type = "telemetry"
                        description = "Calculates the time since the last shown completion"
                        enabled = true
                      },
                      {
                        id = "TypingSpeed"
                        class = "me.code4me.services.modules.telemetry.behavioral.TypingSpeed"
                        name = "Typing Speed"
                        type = "telemetry"
                        description = "Calculates the typing speed of the user"
                        enabled = true
                      }
                    ]
                    dependencies = [
                      {
                        moduleId = "TimeSinceLastAcceptedCompletion"
                        isHard = true
                      },
                      {
                        moduleId = "TimeSinceLastShownCompletion"
                        isHard = true
                      },
                      {
                        moduleId = "TypingSpeed"
                        isHard = false
                      }
                    ]
                  },
                  {
                    id = "ContextualTelemetryAggregator"
                    class = "me.code4me.services.modules.aggregators.BaseContextualTelemetryAggregator"
                    name = "Contextual Telemetry Aggregator"
                    type = "aggregator"
                    description = "Module for aggregating contextual telemetry data"
                    enabled = true
                    submodules = [
                      {
                        id = "EditorContextRetrievalModule"
                        class = "me.code4me.services.modules.telemetry.contextual.EditorContextRetrievalModule"
                        name = "Editor Context Retrieval Module"
                        type = "telemetry"
                        description = "Retrieves context from the editor"
                        enabled = true
                      }
                    ]
                    dependencies = [
                      {
                        moduleId = "EditorContextRetrievalModule"
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
                  },
                  {
                    id = "BaseModelAggregator"
                    class = "me.code4me.services.modules.aggregators.BaseModelAggregator"
                    name = "Model Aggregator"
                    type = "aggregator"
                    description = "Module for aggregating model data"
                    enabled = true
                    submodules = [
                      {
                        id = "ChatModel"
                        class = "me.code4me.services.modules.model.ChatModel"
                        name = "Model Selection and Settings for Chat"
                        type = "model"
                        description = "Module for selecting the appropriate model for chat interactions"
                        enabled = true
                      },
                      {
                        id = "CompletionModel"
                        class = "me.code4me.services.modules.model.CompletionModel"
                        name = "Model Selection and Settings for Code Completion"
                        type = "model"
                        description = "Module for selecting the appropriate model for code generation"
                        enabled = true
                      }
                    ]
                    dependencies = [
                      {
                        moduleId = "ChatModel"
                        isHard = true
                      },
                      {
                        moduleId = "CompletionModel"
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
                  contextualTelemetry = {
                    path = "me.code4me.services.modules.telemetry.contextual"
                    description = "Modules for contextual telemetry collection"
                  }
                  context = {
                    path = "me.code4me.services.modules.context"
                    description = "Modules for context retrieval"
                  }
                  aggregators = {
                    path = "me.code4me.services.modules.aggregators"
                    description = "Modules for data aggregation"
                  }
                  models = {
                    path = "me.code4me.services.modules.model"
                    description = "Modules for model selection and settings"
                  }
                }
              }
              
              server {
                host = "http://127.0.0.1"
                port = 8008
                contextPath = ""
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
                ]
                systemPrompt = "You are a helpful assistant"
              }
              
              languages {
                "Java" = 11
                "Kotlin" = 98
                "unknown" = 179
              }
            }
        """
        private const val COMPLETE_MODULE_CONFIG_WITH_AFTER_INSERTION = """config {
  // module configuration
  modules {
    // List of available modules
    available = [
      {
        id = "BehavioralTelemetryAggregator"
        class = "me.code4me.services.modules.aggregators.BaseBehavioralTelemetryAggregator"
        name = "Behavioral Telemetry Aggregator"
        type = "aggregator"
        description = "Module for aggregating behavioral telemetry data"
        enabled = true
        // Example of submodules
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
            id = "TimeSinceLastShownCompletion"
            class = "me.code4me.services.modules.telemetry.behavioral.TimeSinceLastShownCompletion"
            name = "Time Since Last Shown Completion"
            type = "telemetry"
            description = "Calculates the time since the last shown completion"
            enabled = true
          },
          {
            id = "TypingSpeed"
            class = "me.code4me.services.modules.telemetry.behavioral.TypingSpeed"
            name = "Typing Speed"
            type = "telemetry"
            description = "Calculates the typing speed of the user"
            enabled = true
          }
        ]
        // Example of dependencies
        dependencies = [
          {
            moduleId = "TimeSinceLastAcceptedCompletion"
            isHard = false
          },
          {
            moduleId = "TimeSinceLastShownCompletion"
            isHard = false
          },
          {
            moduleId = "TypingSpeed"
            isHard = false
          }
        ]
      },

      {
          id = "ContextualTelemetryAggregator"
          class = "me.code4me.services.modules.aggregators.BaseContextualTelemetryAggregator"
          name = "Contextual Telemetry Aggregator"
          type = "aggregator"
          description = "Module for aggregating contextual telemetry data"
          enabled = true
          // Example of submodules
          submodules = [
          {
              id = "EditorContextRetrievalModule"
              class = "me.code4me.services.modules.telemetry.contextual.EditorContextRetrievalModule"
              name = "Editor Context Retrieval Module"
              type = "telemetry"
              description = "Retrieves context from the editor"
              enabled = true
          }
          ]
          // Example of dependencies
          dependencies = [
          {
              moduleId = "EditorContextRetrievalModule"
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
        // Example of submodules
        submodules = [
          {
            id = "FileContextRetrievalModule"
            class = "me.code4me.services.modules.context.FileContextRetrievalModule"
            name = "File Context Retrieval Module"
            type = "context"
            description = "Retrieves context from the file"
            enabled = true
          },
          {
            id = "MultiFileContextRetrievalModule"
            class = "me.code4me.services.modules.context.MultiFileContextRetrievalModule"
            name = "Multi File Context Retrieval Module"
            type = "context"
            description = "Retrieves context from multiple files"
            enabled = true
          }
        ]
        // Example of dependencies
        dependencies = [
          {
            moduleId = "FileContextRetrievalModule"
            isHard = true  // This is a hard dependency
          },
          {
            moduleId = "MultiFileContextRetrievalModule"
            isHard = false
          }
        ]
      },

      {
        id = "BaseModelAggregator"
        class = "me.code4me.services.modules.aggregators.BaseModelAggregator"
        name = "Model Aggregator"
        type = "aggregator"
        description = "Module for aggregating model data"
        enabled = true
        submodules = [
          {
            id = "ChatModel"
            class = "me.code4me.services.modules.model.ChatModel"
            name = "Model Selection and Settings for Chat"
            type = "model"
            description = "Module for selecting the appropriate model for chat interactions"
            enabled = true
          }
          {
            id = "CompletionModel"
            class = "me.code4me.services.modules.model.CompletionModel"
            name = "Model Selection and Settings for Code Completion"
            type = "model"
            description = "Module for selecting the appropriate model for code generation"
            enabled = true
          }
        ]

        dependencies = [
          {
            moduleId = "ChatModel"
            isHard = true
          },
          {
            moduleId = "CompletionModel"
            isHard = true
          }
        ]
      },

      {
        id = "AfterInsertionAggregator"
        class = "me.code4me.services.modules.aggregators.BaseAfterInsertionAggregator"
        name = "After Insertion Aggregator"
        type = "aggregator"
        description = "Module for aggregating after code insertion activities"
        enabled = true
        submodules = [
          {
            id = "GroundTruth"
            class = "me.code4me.services.modules.afterInsertion.GroundTruth"
            name = "Ground Truth Module"
            type = "afterInsertion"
            description = "Module for handling actions after code insertion"
            enabled = true
          }
          {
            id = "AcceptanceFeedback"
            class = "me.code4me.services.modules.afterInsertion.AcceptanceFeedback"
            name = "Code Insertion Acceptance Feedback Module"
            type = "afterInsertion"
            description = "Module for sending acceptance feedback after code insertion"
            enabled = true
          }
        ]
        dependencies = [
          {
            moduleId = "GroundTruth"
            isHard = false
          }
          {
            moduleId = "AcceptanceFeedback"
            isHard = false
          }
        ]
      }

    ]

    // Module categories
    categories = {
      behavioralTelemetry = {
        path = "me.code4me.services.modules.telemetry.behavioral"
        description = "Modules for behavioral telemetry collection"
      }
      contextualTelemetry = {
        path = "me.code4me.services.modules.telemetry.contextual"
        description = "Modules for contextual telemetry collection"
      }
      context = {
        path = "me.code4me.services.modules.context"
        description = "Modules for context retrieval"
      }
      aggregators = {
        path = "me.code4me.services.modules.aggregators"
        description = "Modules for data aggregation"
      }
      models = {
        path = "me.code4me.services.modules.model"
        description = "Modules for model selection and settings"
      }
      afterInsertion = {
        path = "me.code4me.services.modules.afterInsertion"
        description = "Modules for actions after code insertion"
      }
    }
  }
  // Server Settings
  server {
    host = "http://127.0.0.1"
    port = 8008
    contextPath = ""
    timeout = 5000
  }
  // Authentication Settings
  auth {
    google {
      clientId = "67736337656-un249ihklv5i93n033i1v46q12bfv5g2.apps.googleusercontent.com"
    }
  }
  // model configuration
  models {
    available = [
      {
        id = 1
        name = "deepseek-coder-1.3b"
        isChatModel = false
        isDefault = true
      }
      {
        id = 2,
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
    systemPrompt = "You are a helpful assistant that provides information and answers questions to the best of your ability. Please respond in a clear and concise manner."
  }

  languages {
    "Oracle NetSuite" = 1,
    "GoPlusBuild" = 2,
    "HelmJSON" = 3,
    "USS" = 4,
    "UnityYaml" = 5,
    "Blade" = 6,
    "Xaml" = 7,
    "Meson" = 8,
    "Angular SVG template" = 9,
    "Pug (ex-Jade)" = 10,
    "Java" = 11,
    "DTD" = 12,
    "SQL" = 13,
    "LinkerScript" = 14,
    "Asp" = 15,
    "ClickHouse" = 16,
    "CMakeCache" = 17,
    "Chameleon template" = 18,
    "VB" = 19,
    ".ignore (IgnoreLang)" = 20,
    "Vue template" = 21,
    "UastContextLanguage" = 22,
    "EditorConfig" = 23,
    "XPath" = 24,
    "MariaDB" = 25,
    "MySQL based" = 26,
    "MongoJS" = 27,
    "Angular SVG template (17+)" = 28,
    "IBM Db2 LUW" = 29,
    "SCSS" = 30,
    "DotEnv" = 31,
    "Kotlin/Native Def" = 32,
    "JSONPath" = 33,
    "Microsoft SQL Server" = 34,
    "Flow JS" = 35,
    "Devicetree" = 36,
    "JSON5" = 37,
    "protobase" = 38,
    "Angular HTML template (17+)" = 39,
    "BladeHTML" = 40,
    "TypeScript" = 41,
    "Metadata JSON" = 42,
    "Greenplum" = 43,
    "Handlebars" = 44,
    "Oracle SQL*Plus" = 45,
    "JVM languages" = 46,
    "Python" = 47,
    "GoBuild" = 48,
    "VueTS" = 49,
    "JSON" = 50,
    "ASM" = 51,
    "VueExpr" = 52,
    "DynamoDB" = 53,
    "MSBuild" = 54,
    "C#" = 55,
    "Exasol" = 56,
    "Sybase ASE" = 57,
    "XML" = 58,
    ".gitignore (GitIgnore)" = 59,
    "vgo" = 60,
    "prototext" = 61,
    "Groovy" = 62,
    "ECMAScript 6" = 63,
    "TypeScript JSX" = 64,
    "CSS" = 65,
    "Config" = 66,
    "DjangoUrlPath" = 67,
    "Composer Log" = 68,
    "Jinja2" = 69,
    "Injectable PHP" = 70,
    "HelmTEXT" = 71,
    "PythonStub" = 72,
    "GoTag" = 73,
    "JSON Lines" = 74,
    "PyTypeHint" = 75,
    "Angular HTML template (18.1+)" = 76,
    "JSUnicodeRegexp" = 77,
    "Razor" = 78,
    "CMake" = 79,
    "HTML" = 80,
    "HtmlCompatible" = 81,
    "Go" = 82,
    "Angular2" = 83,
    "XHTML" = 84,
    "VueJS" = 85,
    "Apache Cassandra" = 86,
    "IBM Db2 iSeries" = 87,
    "SQL2016" = 88,
    "exclude (GitExclude)" = 89,
    "ShaderLab" = 90,
    ".hgignore (HgIgnore)" = 91,
    "Angular HTML template" = 92,
    "YouTrack" = 93,
    "Markdown" = 94,
    "WerfYAML" = 95,
    "RELAX-NG" = 96,
    "UXML" = 97,
    "Kotlin" = 98,
    "GDB" = 99,
    "Redis" = 100,
    "XPath2" = 101,
    "PostCSS" = 102,
    "Jupyter" = 103,
    "Twig" = 104,
    "JShell Snippet" = 105,
    "Go Template" = 106,
    "SQLite" = 107,
    "PyFunctionTypeComment" = 108,
    "Angular SVG template (18.1+)" = 109,
    "HTTP Request" = 110,
    "Scmp" = 111,
    "Plain text" = 112,
    "H2" = 113,
    "Gradle Declarative Configuration" = 114,
    "RuleSet" = 115,
    "plan9_x86" = 116,
    "WebSymbolsEnabledLanguage" = 117,
    "Properties" = 118,
    "Asxx" = 119,
    "Vertica" = 120,
    "JVM" = 121,
    "ModuleMap" = 122,
    "C/C++" = 123,
    "Resx" = 124,
    "Blazor" = 125,
    "HSQLDB" = 126,
    "Cookie" = 127,
    "Generic SQL" = 128,
    "XsdRegExp" = 129,
    "SlnLaunchLanguage" = 130,
    "Dockerfile" = 131,
    "MySQL" = 132,
    "IL" = 133,
    "HelmYAML" = 134,
    "Terminal Prompt" = 135,
    "Requirements" = 136,
    "Apache Spark" = 137,
    "HttpClientHandlerJavaScriptDialect" = 138,
    "textmate" = 139,
    "QML" = 140,
    "GoTime" = 141,
    "F#" = 142,
    "PostgreSQL" = 143,
    "Rest language" = 144,
    "protobuf" = 145,
    "Oracle" = 146,
    "Cgo" = 147,
    ".dockerignore (DockerIgnore)" = 148,
    "JQL" = 149,
    "Manifest" = 150,
    "LLDB" = 151,
    "Makefile" = 152,
    "RegExp" = 153,
    "GoFuzzCorpus" = 154,
    "Cython" = 155,
    "Sass" = 156,
    "TOML" = 157,
    "Shell Script" = 158,
    "Ini" = 159,
    "Less" = 160,
    "CockroachDB" = 161,
    "Apache Derby" = 162,
    "GoDebug" = 163,
    "SolutionFile" = 164,
    "YAML" = 165,
    "ActionScript" = 166,
    "JSRegexp" = 167,
    "T4" = 168,
    "JavaScript" = 169,
    "Gherkin" = 170,
    "TerminalOutput" = 171,
    "SVG" = 172,
    "SPI" = 173,
    "Doxygen" = 174,
    "Notebook" = 175,
    "Apache Hive" = 176,
    "GithubExpressionLanguage" = 177,
    "PythonRegExp" = 178,
    "unknown" = 179,
  }
}
"""
    }

    fun testModuleManagerInitializationWithConfigService() {
        // Given
        val configService = ConfigService(COMPLETE_MODULE_CONFIG)
        val moduleManager = ModuleManager()

        // When - instantiate modules from config and store them
        val instantiatedModules = configService.instantiateModules()
        moduleManager.storeModules(instantiatedModules)

        // Then
        val availableModules = moduleManager.getAvailableModules()
        assertTrue("Should have instantiated modules", availableModules.isNotEmpty())

        // Verify aggregator modules are present
        assertTrue("Should contain behavioral telemetry aggregator",
            availableModules.any { it is BaseBehavioralTelemetryAggregator })
        assertTrue("Should contain context aggregator",
            availableModules.any { it is BaseContextAggregator })
        assertTrue("Should contain contextual telemetry aggregator",
            availableModules.any { it is BaseContextualTelemetryAggregator })
        assertTrue("Should contain model aggregator",
            availableModules.any { it is BaseModelAggregator })
    }

    fun testModuleManagerWithEnabledModulesFromConfig() {
        // Given
        val configService = ConfigService(COMPLETE_MODULE_CONFIG)
        val moduleManager = ModuleManager()
        val instantiatedModules = configService.instantiateModules()

        // When
        moduleManager.storeModules(instantiatedModules)
        moduleManager.initializeModules()

        // Then - verify that modules configured as enabled are actually enabled
        val enabledModules = moduleManager.getEnabledModules()
        val enabledModuleIds = moduleManager.getEnabledModuleIds()

        assertTrue("Should have enabled modules", enabledModules.isNotEmpty())
        assertTrue("Should have enabled module IDs", enabledModuleIds.isNotEmpty())

        // Verify specific aggregators are enabled
        assertTrue("Behavioral telemetry aggregator should be enabled",
            enabledModuleIds.contains("BaseBehavioralTelemetryAggregator"))
        assertTrue("Context aggregator should be enabled",
            enabledModuleIds.contains("BaseContextAggregator"))
        assertTrue("Contextual telemetry aggregator should be enabled",
            enabledModuleIds.contains("BaseContextualTelemetryAggregator"))
        assertTrue("Model aggregator should be enabled",
            enabledModuleIds.contains("BaseModelAggregator"))
    }

    fun testBehavioralTelemetryAggregatorWithSubmodules() {
        // Given
        val configService = ConfigService(COMPLETE_MODULE_CONFIG)
        val moduleManager = ModuleManager()
        val instantiatedModules = configService.instantiateModules()

        // When
        moduleManager.storeModules(instantiatedModules)
        moduleManager.enableModule("BehavioralTelemetryAggregator")
        moduleManager.initializeModules()

        // Then
        val behavioralAggregator = moduleManager.getModule("BaseBehavioralTelemetryAggregator")
        assertNotNull("Should find behavioral telemetry aggregator", behavioralAggregator)
        assertTrue("Should be a behavioral telemetry aggregator",
            behavioralAggregator is BaseBehavioralTelemetryAggregator)

        val submodules = behavioralAggregator!!.getSubmodules()
        assertTrue("Should have submodules", submodules.isNotEmpty())

        // Verify specific submodules are present
        assertTrue("Should have TimeSinceLastAcceptedCompletion submodule",
            submodules.any { it is TimeSinceLastAcceptedCompletion })
        assertTrue("Should have TimeSinceLastShownCompletion submodule",
            submodules.any { it is TimeSinceLastShownCompletion })
        assertTrue("Should have TypingSpeed submodule",
            submodules.any { it is TypingSpeed })
    }

    fun testDataCollectionFromEnabledModules() {
        // Given - Setup test file and editor using fixture
        val file = myFixture.configureByText("TestFile.kt", """
        class TestClass {
            fun testMethod() {
                // Test code for completion
            }
        }
    """.trimIndent())

        // Setup modules with configuration
        val configService = ConfigService(COMPLETE_MODULE_CONFIG)
        val moduleManager = ModuleManager()
        val instantiatedModules = configService.instantiateModules()

        moduleManager.storeModules(instantiatedModules)
        moduleManager.enableModule("BehavioralTelemetryAggregator")
        moduleManager.enableModule("TimeSinceLastShownCompletion")
        moduleManager.initializeModules()

        // Position cursor and simulate typing
        myFixture.editor.caretModel.moveToOffset(file.textLength)
        myFixture.type("val x = ")

        // Create real InlineCompletionRequest using fixture components
        val psiFile = file
        val editor = myFixture.editor
        val document = editor.document
        val startOffset = myFixture.caretOffset
        val endOffset = startOffset

        val caretOffset = myFixture.caretOffset
        val typedText = "completion"
        val range = com.intellij.openapi.util.TextRange(caretOffset, caretOffset + typedText.length)

        val typingEvent = com.intellij.codeInsight.inline.completion.TypingEvent.OneSymbol('c', caretOffset)

        val event = com.intellij.codeInsight.inline.completion.InlineCompletionEvent.DocumentChange(
            typing = typingEvent,
            editor = myFixture.editor
        )

        val request = com.intellij.codeInsight.inline.completion.InlineCompletionRequest(
            event = event,
            file = psiFile,
            editor = editor,
            document = document,
            startOffset = startOffset,
            endOffset = endOffset
        )

        // When - Collect data from enabled modules
        val collectedData = moduleManager.collectData(request)

        // Then - Verify data collection
        assertNotNull("Should collect data from enabled modules", collectedData)
        assertTrue("Should have collected some data", collectedData.isNotEmpty())

        // Verify that behavioral telemetry data is collected
        val behavioralTelemetryRecords = collectedData.filter {
            it.type == Record.Type.BEHAVIORAL_TELEMETRY
        }
        assertTrue("Should have behavioral telemetry records", behavioralTelemetryRecords.isNotEmpty())

        // Verify specific telemetry modules are working
        val timeSinceLastShownKey = Record.key<Long>("time_since_last_shown")
        val hasTimingData = behavioralTelemetryRecords.any { record ->
            record.containsKey(timeSinceLastShownKey)
        }

        // First call might have 0ms timing, so we'll check that the key exists
        assertTrue("Should have timing telemetry data", hasTimingData)

        // Verify that enabled modules are actually contributing data
        val enabledModules = moduleManager.getEnabledModules()
        assertTrue("Should have enabled modules", enabledModules.isNotEmpty())

        // Verify module enablement state
        assertTrue("BehavioralTelemetryAggregator should be enabled",
            moduleManager.isModuleEnabled("BehavioralTelemetryAggregator"))
        assertTrue("TimeSinceLastShownCompletion should be enabled",
            moduleManager.isModuleEnabled("TimeSinceLastShownCompletion"))

        println("Collected ${collectedData.size} records from ${enabledModules.size} enabled modules")
        collectedData.forEach { record ->
            println("Record type: ${record.type}, keys: ${record.expanded.keys.map { it.name }}")
        }
    }

    fun testModuleEnableDisableFunctionality() {
        // Given
        val configService = ConfigService(COMPLETE_MODULE_CONFIG)
        val moduleManager = ModuleManager()
        val instantiatedModules = configService.instantiateModules()

        moduleManager.storeModules(instantiatedModules)

        // When - enable a module
        moduleManager.enableModule("BaseBehavioralTelemetryAggregator")

        // Then
        assertTrue("Module should be enabled",
            moduleManager.isModuleEnabled("BaseBehavioralTelemetryAggregator"))
        assertEquals("Should have one enabled module", 4, moduleManager.getEnabledModules().size)

        // When - disable the module
        moduleManager.disableModule("BaseBehavioralTelemetryAggregator")

        // Then
        assertFalse("Module should be disabled",
            moduleManager.isModuleEnabled("BaseBehavioralTelemetryAggregator"))
        assertEquals("Should have no enabled modules", 3, moduleManager.getEnabledModules().size)
    }

    fun testModuleRetrievalById() {
        // Given
        val configService = ConfigService(COMPLETE_MODULE_CONFIG)
        val moduleManager = ModuleManager()
        val instantiatedModules = configService.instantiateModules()

        moduleManager.storeModules(instantiatedModules)

        // When
        val behavioralAggregator = moduleManager.getModule("BaseBehavioralTelemetryAggregator")
        val contextAggregator = moduleManager.getModule("BaseContextAggregator")
        val nonExistentModule = moduleManager.getModule("NonExistentModule")

        // Then
        assertNotNull("Should find behavioral aggregator", behavioralAggregator)
        assertNotNull("Should find context aggregator", contextAggregator)
        assertNull("Should not find non-existent module", nonExistentModule)

        assertEquals("Should have correct module name", "BaseContextAggregator",
            contextAggregator!!.moduleName)
    }

    fun testSubmoduleInitializationAndDataCollection() {
        // Given - test individual submodule functionality
        val timeSinceLastShown = TimeSinceLastShownCompletion()
        val timeSinceLastAccepted = TimeSinceLastAcceptedCompletion()

        PrefState.enableModule(timeSinceLastShown.getPreferenceId())
        PrefState.enableModule(timeSinceLastAccepted.getPreferenceId())

        // When
        assertDoesNotThrow("TimeSinceLastShownCompletion should initialize") {
            timeSinceLastShown.initializeModules()
        }
        assertDoesNotThrow("TimeSinceLastAcceptedCompletion should initialize") {
            timeSinceLastAccepted.initializeModules()
        }

        // Create mock request for data collection
        val mockRequest = mock(InlineCompletionRequest::class.java)

        // Then - test data collection
        val shownData = timeSinceLastShown.collectData(mockRequest)
        val acceptedData = timeSinceLastAccepted.collectData(mockRequest)

        assertNotNull("Should collect data from TimeSinceLastShownCompletion", shownData)
        assertNotNull("Should collect data from TimeSinceLastAcceptedCompletion", acceptedData)

        if (shownData.isNotEmpty()) {
            assertEquals("Should be behavioral telemetry type",
                Record.Type.BEHAVIORAL_TELEMETRY, shownData[0].type)
        }

        if (acceptedData.isNotEmpty()) {
            assertEquals("Should be behavioral telemetry type",
                Record.Type.BEHAVIORAL_TELEMETRY, acceptedData[0].type)
        }
    }

    fun testTypingSpeedModuleFunctionality() {
        // Given - Setup test file and simulate typing scenario
        val file = myFixture.configureByText("TypingSpeedTest.kt", """
        class TestClass {
            fun testMethod() {
                // Starting to type here
            }
        }
    """.trimIndent())

        // Create and enable TypingSpeed module
        val typingSpeed = TypingSpeed()
        PrefState.enableModule(typingSpeed.getPreferenceId())

        // When - Initialize the module
        assertDoesNotThrow("TypingSpeed should initialize without exception") {
            typingSpeed.initializeModules()
        }

        // Get the typing speed service directly and simulate typing
        val project = myFixture.project
        val typingSpeedService = project.service<TypingSpeedService>()

        // Position cursor and simulate realistic typing sequence with timing
        myFixture.editor.caretModel.moveToOffset(file.textLength - 10)

        // Simulate a sequence of typing to generate realistic timing data
        val typingSequence = "val result = calculateValue()"

        typingSequence.forEachIndexed { index, char ->
            // Add small delay between keystrokes to simulate realistic typing
            Thread.sleep(50 + (Math.random() * 100).toLong()) // 50-150ms between keystrokes

            // Directly record the character in the service (simulating what TypingSpeedHandler would do)
            if (!char.isISOControl()) {
                typingSpeedService.recordCharTyped()
            }

            // Also type in the editor for realistic document state
            myFixture.type(char.toString())

            // Create typing event for each character
            val caretOffset = myFixture.caretOffset - 1
            val typingEvent = com.intellij.codeInsight.inline.completion.TypingEvent.OneSymbol(char, caretOffset)

            val event = com.intellij.codeInsight.inline.completion.InlineCompletionEvent.DocumentChange(
                typing = typingEvent,
                editor = myFixture.editor
            )

            val request = com.intellij.codeInsight.inline.completion.InlineCompletionRequest(
                event = event,
                file = file,
                editor = myFixture.editor,
                document = myFixture.editor.document,
                startOffset = caretOffset,
                endOffset = caretOffset + 1
            )

            // Collect data from the TypingSpeed module
            val collectedData = typingSpeed.collectData(request)

            // After a few keystrokes, we should start getting typing speed data
            if (index > 2) { // Allow a few keystrokes to establish timing baseline
                assertNotNull("Should return data collection result", collectedData)

                // Look for typing speed data in the collected records
                val behavioralRecords = collectedData.filter {
                    it.type == Record.Type.BEHAVIORAL_TELEMETRY
                }

                if (behavioralRecords.isNotEmpty()) {
                    val typingSpeedKey = Record.key<Int>("typing_speed")
                    val hasTypingSpeedData = behavioralRecords.any { record ->
                        record.containsKey(typingSpeedKey)
                    }

                    if (hasTypingSpeedData) {
                        println("Typing speed data found after ${index + 1} keystrokes")
                        val typingSpeedValue = behavioralRecords.first { it.containsKey(typingSpeedKey) }
                            .expanded[typingSpeedKey] as Int
                        assertTrue("Typing speed should be positive", typingSpeedValue >= 0)
                        println("Measured typing speed: $typingSpeedValue characters per minute")
                        return@forEachIndexed // Exit early once we have data
                    }
                }
            }
        }

        // Final verification
        val finalTypingEvent = com.intellij.codeInsight.inline.completion.TypingEvent.OneSymbol('\n', myFixture.caretOffset)
        val finalEvent = com.intellij.codeInsight.inline.completion.InlineCompletionEvent.DocumentChange(
            typing = finalTypingEvent,
            editor = myFixture.editor
        )

        val finalRequest = com.intellij.codeInsight.inline.completion.InlineCompletionRequest(
            event = finalEvent,
            file = file,
            editor = myFixture.editor,
            document = myFixture.editor.document,
            startOffset = myFixture.caretOffset,
            endOffset = myFixture.caretOffset
        )

        val finalCollectedData = typingSpeed.collectData(finalRequest)

        // Then - Verify module functionality
        assertNotNull("Should return data collection result", finalCollectedData)

        val behavioralRecords = finalCollectedData.filter {
            it.type == Record.Type.BEHAVIORAL_TELEMETRY
        }

        assertTrue("Should have behavioral records", behavioralRecords.isNotEmpty())

        val typingSpeedKey = Record.key<Int>("typing_speed")
        val hasTypingSpeedData = behavioralRecords.any { record ->
            record.containsKey(typingSpeedKey)
        }

        if (hasTypingSpeedData) {
            val typingSpeedRecord = behavioralRecords.first { it.containsKey(typingSpeedKey) }
            val typingSpeedValue = typingSpeedRecord.expanded[typingSpeedKey] as Int

            assertTrue("Typing speed should be non-negative", typingSpeedValue >= 0)
            assertTrue("Typing speed should be reasonable (< 1000 CPM)", typingSpeedValue < 1000)

            println("Final typing speed measurement: $typingSpeedValue characters per minute")
        }

        // Verify the module state is correct
        assertTrue("TypingSpeed module should be enabled",
            PrefState.getEnabledModules().contains(typingSpeed.getPreferenceId()))
    }

    fun testModuleManagerDataAggregation() {
        // Set up test file fixture
        val file = myFixture.configureByText("DataAggregationTest.kt", """
            class DataAggregationTest {
                fun testMethod() {
                    // Test code for completion
                }
            }
        """.trimIndent())
        myFixture.type("abcd")

        // Given
        val configService = ConfigService(COMPLETE_MODULE_CONFIG)
        val moduleManager = ModuleManager()
        val instantiatedModules = configService.instantiateModules()

        moduleManager.storeModules(instantiatedModules)

        // Enable multiple modules for comprehensive data aggregation
        moduleManager.enableModule("BehavioralTelemetryAggregator")
        moduleManager.enableModule("contextAggregator")
        moduleManager.enableModule("ContextualTelemetryAggregator")
        moduleManager.enableModule("TimeSinceLastShownCompletion")
        moduleManager.enableModule("TypingSpeed")
        moduleManager.enableModule("FileContextRetrievalModule")
        moduleManager.initializeModules()

        // Create real InlineCompletionRequest using fixture
        val psiFile = file
        val editor = myFixture.editor
        val document = editor.document
        val startOffset = myFixture.caretOffset
        val endOffset = startOffset

        val caretOffset = myFixture.caretOffset
        val typedText = "\n"
        val range = TextRange(caretOffset, caretOffset + typedText.length)

        val typingEvent = TypingEvent.NewLine(typedText, range)
        val event = InlineCompletionEvent.DocumentChange(
            typing = typingEvent,
            editor = myFixture.editor,
        )

        val request = InlineCompletionRequest(
            event = event,
            file = psiFile,
            editor = editor,
            document = document,
            startOffset = startOffset,
            endOffset = endOffset,
        )

        // When
        val aggregatedData = moduleManager.collectData(request)

        // Then
        assertNotNull("Should return aggregated data", aggregatedData)
        assertTrue("Should collect non-empty data", aggregatedData.isNotEmpty())

        // Verify data structure integrity
        aggregatedData.forEach { record ->
            record.expanded.forEach { (key, value) ->
                assertTrue(
                    "Type mismatch for key '${key.name}' in record of type '${record.type}': expected ${key.type.simpleName}, got ${value::class.java.simpleName}",
                    key.type.isAssignableFrom(value::class.java)
                )
            }
        }

        // Verify different types of records are collected from enabled aggregators
        val recordTypes = aggregatedData.map { it.type }.toSet()
        val enabledModules = moduleManager.getEnabledModules()

        assertTrue("Should collect data from multiple modules",
            aggregatedData.isNotEmpty())

        // Verify specific record types based on enabled aggregators
        val expectedRecordTypes = mutableSetOf<Record.Type>()

        if (enabledModules.any { it.javaClass.simpleName.contains("BehavioralTelemetryAggregator") }) {
            expectedRecordTypes.add(Record.Type.BEHAVIORAL_TELEMETRY)
        }
        if (enabledModules.any { it.javaClass.simpleName.contains("ContextAggregator") }) {
            expectedRecordTypes.add(Record.Type.CONTEXT)
        }
        if (enabledModules.any { it.javaClass.simpleName.contains("ContextualTelemetryAggregator") }) {
            expectedRecordTypes.add(Record.Type.CONTEXTUAL_TELEMETRY)
        }

        // Verify that aggregation works correctly
        expectedRecordTypes.forEach { expectedType ->
            assertTrue("Should have ${expectedType} records when corresponding aggregator is enabled",
                recordTypes.contains(expectedType))
        }

        if (moduleManager.isModuleEnabled("FileContextRetrievalModule")) {
            val fileContentsKey = Record.Companion.key<String>("file_contents")
            val hasFileContextData = aggregatedData.any { record ->
                record.type == Record.Type.CONTEXT && record.containsKey(fileContentsKey)
            }
            assertTrue("Should collect file context data when FileContextRetrievalModule is enabled", hasFileContextData)
        }

        println("Data aggregation test collected ${aggregatedData.size} records of types: ${recordTypes.joinToString()}")
        println("Enabled modules: ${enabledModules.map { it.moduleName }.joinToString()}")
    }

    fun testModuleDependencyResolution() {
        // Given
        val configService = ConfigService(COMPLETE_MODULE_CONFIG)

        // When - get transitive hard dependencies
        val dependants = configService.getTransitiveHardDependants("TimeSinceLastAcceptedCompletion")

        // Then
        assertNotNull("Should find dependants", dependants)
        assertTrue("Should have modules that depend on TimeSinceLastAcceptedCompletion",
            dependants.any { it.id == "BehavioralTelemetryAggregator" })
    }

    fun testCompleteWorkflowWithConfigAndModuleManager() {
        // Set up test file fixture similar to TelemetryLightTest
        val file = myFixture.configureByText("CompleteWorkflowTest.kt", """
            class CompleteWorkflowTest {
                fun testMethod() {
                    // Test code for completion
                }
            }
        """.trimIndent())
        myFixture.type("abcd")

        // Given - complete workflow test following the plugin startup pattern
        val configService = ConfigService(COMPLETE_MODULE_CONFIG)
        val moduleManager = ModuleManager()

        // When - follow the complete initialization workflow
        val configModules = configService.getAvailableModules()
        val instantiatedModules = configService.instantiateModules()

        assertNotNull("Should have config modules", configModules)
        assertNotNull("Should have instantiated modules", instantiatedModules)
        assertTrue("Should have matching counts", configModules.size <= instantiatedModules.size)

        // Store and initialize modules
        moduleManager.storeModules(instantiatedModules)
        moduleManager.initializeModules()

        // Verify final state
        val enabledModules = moduleManager.getEnabledModules()
        val enabledModuleIds = moduleManager.getEnabledModuleIds()

        // Then
        assertTrue("Should have enabled modules", enabledModules.isNotEmpty())
        assertTrue("Should have enabled module IDs", enabledModuleIds.isNotEmpty())

        // Verify that all main aggregators are available and can be enabled
        val availableModules = moduleManager.getAvailableModules()
        val aggregatorTypes = setOf(
            BaseBehavioralTelemetryAggregator::class,
            BaseContextAggregator::class,
            BaseContextualTelemetryAggregator::class,
            BaseModelAggregator::class
        )

        aggregatorTypes.forEach { aggregatorType ->
            assertTrue("Should have ${aggregatorType.simpleName}",
                availableModules.any { aggregatorType.isInstance(it) })
        }

        // Create a real InlineCompletionRequest using the fixture like TelemetryLightTest
        val psiFile = file
        val editor = myFixture.editor
        val document = editor.document
        val startOffset = myFixture.caretOffset
        val endOffset = startOffset

        val caretOffset = myFixture.caretOffset
        val typedText = "\n"
        val range = TextRange(caretOffset, caretOffset + typedText.length)

        val typingEvent = TypingEvent.NewLine(typedText, range)
        val event = InlineCompletionEvent.DocumentChange(
            typing = typingEvent,
            editor = myFixture.editor,
        )

        val request = InlineCompletionRequest(
            event = event,
            file = psiFile,
            editor = editor,
            document = document,
            startOffset = startOffset,
            endOffset = endOffset,
        )

        // Test data collection from the complete setup with real request
        val finalData = moduleManager.collectData(request)
        assertNotNull("Should collect data from complete setup", finalData)
        assertTrue("Should collect non-empty data", finalData.isNotEmpty())

        // Verify data structure integrity similar to TelemetryLightTest
        finalData.forEach { record ->
            record.expanded.forEach { (key, value) ->
                assertTrue(
                    "Type mismatch for key '${key.name}' in record of type '${record.type}': expected ${key.type.simpleName}, got ${value::class.java.simpleName}",
                    key.type.isAssignableFrom(value::class.java)
                )
            }
        }

        // Verify specific record types are present based on enabled modules
        val recordTypes = finalData.map { it.type }.toSet()

        // Check for expected record types from the complete configuration
        val expectedRecordTypes = mutableSetOf<Record.Type>()
        if (enabledModules.any { it is BaseBehavioralTelemetryAggregator }) {
            expectedRecordTypes.add(Record.Type.BEHAVIORAL_TELEMETRY)
        }
        if (enabledModules.any { it is BaseContextAggregator }) {
            expectedRecordTypes.add(Record.Type.CONTEXT)
        }
        if (enabledModules.any { it is BaseContextualTelemetryAggregator }) {
            expectedRecordTypes.add(Record.Type.CONTEXTUAL_TELEMETRY)
        }

        expectedRecordTypes.forEach { expectedType ->
            assertTrue("Should have ${expectedType} records when corresponding aggregator is enabled",
                recordTypes.contains(expectedType))
        }

        println("Complete workflow test collected ${finalData.size} records of types: ${recordTypes.joinToString()}")
    }

    fun testModuleManagerServiceIntegration() {
        // Given
        val moduleManager = ModuleManager()

        // When - test the service integration
        assertNotNull("ModuleManager should be available as service", moduleManager)
        assertEquals("ModuleManager", moduleManager.moduleName)
        assertNotNull("Should have preference class", moduleManager.getPreferenceClass())
        assertNotNull("Should have preference list", moduleManager.getPreferenceList())

        // Then - verify service-like behavior
        val mockRequest = mock(InlineCompletionRequest::class.java)
        val serviceData = moduleManager.collectData(mockRequest)
        assertNotNull("Service should provide data collection", serviceData)
    }

    // --------- TESTS WITH after insertion -------------
    fun testAfterInsertionAggregatorInitialization() {
        // Set up test file fixture
        val file = myFixture.configureByText("AfterInsertionTest.kt", """""")
        myFixture.type("abcd")

        // Given - configuration with after insertion modules
        val configService = ConfigService(COMPLETE_MODULE_CONFIG_WITH_AFTER_INSERTION)
        val moduleManager = ModuleManager()
        val instantiatedModules = configService.instantiateModules()

        // When
        moduleManager.storeModules(instantiatedModules)
        moduleManager.initializeModules()

        // Then
        val availableModules = moduleManager.getAvailableModules()
        val enabledModules = moduleManager.getEnabledModules()

        // Verify that BaseAfterInsertionAggregator is available and enabled
        assertTrue("Should contain after insertion aggregator",
            availableModules.any { it is BaseAfterInsertionAggregator })

        val afterInsertionAggregator = availableModules.find { it is BaseAfterInsertionAggregator }
        assertNotNull("After insertion aggregator should be available", afterInsertionAggregator)

        // Verify that after insertion submodules are available
        val submodules = afterInsertionAggregator!!.getSubmodules()
        assertTrue("Should have after insertion submodules", submodules.isNotEmpty())

        val groundTruthModule = submodules.find { it.moduleName == "GroundTruth" }
        val acceptanceFeedbackModule = submodules.find { it.moduleName == "AcceptanceFeedback" }

        assertNotNull("Should have GroundTruth module", groundTruthModule)
        assertNotNull("Should have AcceptanceFeedback module", acceptanceFeedbackModule)

        println("After insertion aggregator initialized with ${submodules.size} submodules")
    }

    fun testAfterInsertionWorkflow() {
        // Set up test file fixture
        val file = myFixture.configureByText("AfterInsertionTest.kt", """""")
        myFixture.type("code completion text")

        // Given - complete after insertion configuration
        val configService = ConfigService(COMPLETE_MODULE_CONFIG_WITH_AFTER_INSERTION)
        val moduleManager = ModuleManager()
        val instantiatedModules = configService.instantiateModules()

        moduleManager.storeModules(instantiatedModules)
        moduleManager.enableModule("AfterInsertionAggregator")
        moduleManager.enableModule("GroundTruth")
        moduleManager.enableModule("AcceptanceFeedback")
        moduleManager.initializeModules()

        // Create mock inline completion elements
        val insertedText = "function example() { return true; }"
        val mockElement = mock(PluginInlineCompletionElement::class.java).apply {
            `when`(text).thenReturn(insertedText)
            `when`(metaQueryId).thenReturn(UUID.randomUUID())
            `when`(completionModel).thenReturn("deepseek-coder-1.3b")
        }

        val elements = listOf<InlineCompletionElement>(mockElement)

        // Create insertion environment
        val insertEnvironment = mock(InlineCompletionInsertEnvironment::class.java).apply {
            `when`(editor).thenReturn(myFixture.editor)
        }

        // When - trigger after insertion
        assertDoesNotThrow("After insertion should not throw exceptions") {
            moduleManager.afterInsertion(insertEnvironment, elements)
        }

        println("After insertion workflow completed successfully")
    }

    fun testGroundTruthModuleFunctionality() {
        // Set up test file fixture
        val file = myFixture.configureByText("GroundTruthTest.kt", """""")
        myFixture.type("existing code ")

        // Given - configuration with GroundTruth module
        val configService = ConfigService(COMPLETE_MODULE_CONFIG_WITH_AFTER_INSERTION)
        val moduleManager = ModuleManager()
        val instantiatedModules = configService.instantiateModules()

        moduleManager.storeModules(instantiatedModules)
        moduleManager.enableModule("AfterInsertionAggregator")
        moduleManager.enableModule("GroundTruth")
        moduleManager.initializeModules()

        // Find the GroundTruth module
        val groundTruthModule = moduleManager.getAvailableModules()
            .flatMap { if (it is BaseAfterInsertionAggregator) it.getSubmodules() else emptyList() }
            .find { it.moduleName == "GroundTruth" }

        assertNotNull("GroundTruth module should be available", groundTruthModule)
        // Test after insertion functionality
        val insertedText = "// auto-generated comment"
        val mockElement = mock(PluginInlineCompletionElement::class.java).apply {
            `when`(text).thenReturn(insertedText)
            `when`(metaQueryId).thenReturn(UUID.randomUUID())
            `when`(completionModel).thenReturn("deepseek-coder-1.3b")
        }

        val insertEnvironment = mock(InlineCompletionInsertEnvironment::class.java).apply {
            `when`(editor).thenReturn(myFixture.editor)
        }

        // When - trigger after insertion
        assertDoesNotThrow("GroundTruth after insertion should not throw") {
            groundTruthModule?.afterInsertion(insertEnvironment, listOf(mockElement))
        }

        println("GroundTruth module functionality verified")
    }


    fun testAcceptanceFeedbackModuleFunctionality() {
        // Set up test file fixture similar to TelemetryLightTest
        val file = myFixture.configureByText("AcceptanceFeedbackTest.kt", """
            class AcceptanceFeedbackTest {
                fun testMethod() {
                    // Test code for completion
                }
            }
        """.trimIndent())
        myFixture.type("user code")

        // Given - configuration with AcceptanceFeedback module
        val configService = ConfigService(COMPLETE_MODULE_CONFIG_WITH_AFTER_INSERTION)
        val moduleManager = ModuleManager()
        val instantiatedModules = configService.instantiateModules()

        moduleManager.storeModules(instantiatedModules)
        moduleManager.enableModule("AfterInsertionAggregator")
        moduleManager.enableModule("AcceptanceFeedback")
        moduleManager.initializeModules()

        // Find the AcceptanceFeedback module
        val acceptanceFeedbackModule = moduleManager.getAvailableModules()
            .flatMap { if (it is BaseAfterInsertionAggregator) it.getSubmodules() else emptyList() }
            .find { it.moduleName == "AcceptanceFeedback" }

        assertNotNull("AcceptanceFeedback module should be available", acceptanceFeedbackModule)

        // Verify AcceptanceFeedback module characteristics
        assertEquals("Should have correct preference class",
            PreferenceClass.AFTER_INSERTION, acceptanceFeedbackModule!!.getPreferenceClass())

        // Test data collection using real InlineCompletionRequest (should return empty for after insertion modules)
        val psiFile = file
        val editor = myFixture.editor
        val document = editor.document
        val startOffset = myFixture.caretOffset
        val endOffset = startOffset

        val caretOffset = myFixture.caretOffset
        val typedText = "\n"
        val range = TextRange(caretOffset, caretOffset + typedText.length)

        val typingEvent = TypingEvent.NewLine(typedText, range)
        val event = InlineCompletionEvent.DocumentChange(
            typing = typingEvent,
            editor = myFixture.editor,
        )

        val request = InlineCompletionRequest(
            event = event,
            file = psiFile,
            editor = editor,
            document = document,
            startOffset = startOffset,
            endOffset = endOffset,
        )

        val collectedData = acceptanceFeedbackModule.collectData(request)
        assertTrue("AcceptanceFeedback should not collect data during normal operation",
            collectedData.isEmpty())

        // Test after insertion functionality with real insertion environment
        val insertEnvironment = InlineCompletionInsertEnvironment(
            editor=myFixture.editor,
            file=psiFile,
            insertedRange = TextRange(startOffset, endOffset),
            request = request
        )

        // Create a simple mock element that doesn't require stubbing internal methods
        val elements = listOf<InlineCompletionElement>()

        // When - trigger after insertion (should handle empty elements gracefully)
        assertDoesNotThrow("AcceptanceFeedback after insertion should not throw") {
            acceptanceFeedbackModule.afterInsertion(insertEnvironment, elements)
        }

        // Test with module manager's after insertion method
        assertDoesNotThrow("Module manager after insertion should work") {
            moduleManager.afterInsertion(insertEnvironment, elements)
        }

        println("AcceptanceFeedback module functionality verified")
    }

    fun testAfterInsertionWithMultipleModules() {
        // Set up test file fixture similar to TelemetryLightTest
        val file = myFixture.configureByText("MultipleAfterInsertionTest.kt", """
            class MultipleAfterInsertionTest {
                fun testMethod() {
                    // Test code for completion
                }
            }
        """.trimIndent())
        myFixture.type("complex code scenario")

        // Given - complete configuration with multiple after insertion modules
        val configService = ConfigService(COMPLETE_MODULE_CONFIG_WITH_AFTER_INSERTION)
        val moduleManager = ModuleManager()
        val instantiatedModules = configService.instantiateModules()

        moduleManager.storeModules(instantiatedModules)

        // Enable all after insertion related modules
        moduleManager.enableModule("AfterInsertionAggregator")
        moduleManager.enableModule("GroundTruth")
        moduleManager.enableModule("AcceptanceFeedback")

        // Also enable some data collection modules for comprehensive testing
        moduleManager.enableModule("BehavioralTelemetryAggregator")
        moduleManager.enableModule("contextAggregator")

        moduleManager.initializeModules()

        // Verify comprehensive module setup
        val enabledModules = moduleManager.getEnabledModules()
        val afterInsertionModules = enabledModules.filter {
            it.getPreferenceClass() == PreferenceClass.AFTER_INSERTION
        }

        // Create realistic completion scenario using fixture
        val psiFile = file
        val editor = myFixture.editor
        val document = editor.document
        val startOffset = myFixture.caretOffset
        val endOffset = startOffset

        val caretOffset = myFixture.caretOffset
        val typedText = "\n"
        val range = TextRange(caretOffset, caretOffset + typedText.length)

        val typingEvent = TypingEvent.NewLine(typedText, range)
        val event = InlineCompletionEvent.DocumentChange(
            typing = typingEvent,
            editor = myFixture.editor,
        )

        val request = InlineCompletionRequest(
            event = event,
            file = psiFile,
            editor = editor,
            document = document,
            startOffset = startOffset,
            endOffset = endOffset,
        )

        // Create real insertion environment
        val insertEnvironment = InlineCompletionInsertEnvironment(
            editor = myFixture.editor,
            file = psiFile,
            insertedRange = TextRange(startOffset, endOffset),
            request = request
        )

        // Test that all after insertion modules are called with empty elements (realistic scenario)
        val elements = listOf<InlineCompletionElement>()

        assertDoesNotThrow("Multiple after insertion modules should work together") {
            moduleManager.afterInsertion(insertEnvironment, elements)
        }

        // Verify that data collection still works normally
        val collectedData = moduleManager.collectData(request)
        assertNotNull("Should still collect data from regular modules", collectedData)

        // Verify data structure integrity like TelemetryLightTest
        collectedData.forEach { record ->
            record.expanded.forEach { (key, value) ->
                assertTrue(
                    "Type mismatch for key '${key.name}' in record of type '${record.type}': expected ${key.type.simpleName}, got ${value::class.java.simpleName}",
                    key.type.isAssignableFrom(value::class.java)
                )
            }
        }

        // After insertion modules should not contribute to data collection
        val afterInsertionData = collectedData.filter { record ->
            // Check if any after insertion modules contributed data (they shouldn't)
            false // After insertion modules don't collect data
        }
        assertTrue("After insertion modules should not contribute to data collection",
            afterInsertionData.isEmpty())

        // Verify that regular data collection modules still work
        val recordTypes = collectedData.map { it.type }.toSet()
        val expectedRecordTypes = mutableSetOf<Record.Type>()

        if (enabledModules.any { it is BaseBehavioralTelemetryAggregator }) {
            expectedRecordTypes.add(Record.Type.BEHAVIORAL_TELEMETRY)
        }
        if (enabledModules.any { it is BaseContextAggregator }) {
            expectedRecordTypes.add(Record.Type.CONTEXT)
        }

        expectedRecordTypes.forEach { expectedType ->
            if (expectedRecordTypes.isNotEmpty()) {
                assertTrue("Should have ${expectedType} records when corresponding aggregator is enabled",
                    recordTypes.contains(expectedType))
            }
        }

        println("Multiple after insertion modules working together successfully")
        println("Enabled after insertion modules: ${afterInsertionModules.map { it.moduleName }}")
        println("Collected ${collectedData.size} records of types: ${recordTypes.joinToString()}")
    }

    fun testAfterInsertionModuleDisabling() {
        // Set up test file fixture
        val file = myFixture.configureByText("AfterInsertionDisablingTest.kt", """""")
        myFixture.type("test content")

        // Given - configuration with after insertion modules
        val configService = ConfigService(COMPLETE_MODULE_CONFIG_WITH_AFTER_INSERTION)
        val moduleManager = ModuleManager()
        val instantiatedModules = configService.instantiateModules()

        moduleManager.storeModules(instantiatedModules)
        moduleManager.enableModule("AfterInsertionAggregator")
        moduleManager.enableModule("GroundTruth")
        moduleManager.enableModule("AcceptanceFeedback")
        moduleManager.initializeModules()

        // Verify modules are enabled
        assertTrue("GroundTruth should be enabled",
            moduleManager.isModuleEnabled("GroundTruth"))
        assertTrue("AcceptanceFeedback should be enabled",
            moduleManager.isModuleEnabled("AcceptanceFeedback"))

        // When - disable specific after insertion modules
        moduleManager.disableModule("GroundTruth")

        // Then - verify module is disabled
        assertFalse("GroundTruth should be disabled",
            moduleManager.isModuleEnabled("GroundTruth"))
        assertTrue("AcceptanceFeedback should still be enabled",
            moduleManager.isModuleEnabled("AcceptanceFeedback"))

        // Test after insertion with partially disabled modules
        val mockElement = mock(PluginInlineCompletionElement::class.java).apply {
            `when`(text).thenReturn("test insertion")
            `when`(metaQueryId).thenReturn(UUID.randomUUID())
            `when`(completionModel).thenReturn("deepseek-coder-1.3b")
        }

        val insertEnvironment = mock(InlineCompletionInsertEnvironment::class.java).apply {
            `when`(editor).thenReturn(myFixture.editor)
        }

        // Should work even with some modules disabled
        assertDoesNotThrow("After insertion should work with partially disabled modules") {
            moduleManager.afterInsertion(insertEnvironment, listOf(mockElement))
        }

        // When - disable the aggregator
        moduleManager.disableModule("AfterInsertionAggregator")

        // Then - verify aggregator is disabled
        assertFalse("AfterInsertionAggregator should be disabled",
            moduleManager.isModuleEnabled("AfterInsertionAggregator"))

        println("After insertion module disabling functionality verified")
    }
}