import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.register
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
    alias(libs.plugins.changelog)
}

group = "me.code4me"
version = "1.0-SNAPSHOT"

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

// Read properties from parent project directly
val rootProperties = rootProject.file("gradle.properties").readLines()
    .filter { it.contains("=") && !it.startsWith("#") }
    .associate {
        val parts = it.split("=", limit = 2)
        parts[0].trim() to parts[1].trim()
    }

fun getProperty(key: String, default: String = ""): String {
    return rootProperties[key] ?: default
}

dependencies {
    // Depend on the compiled classes from the main project
    testImplementation(project(":"))
    testImplementation(project(":generated"))

    // IntelliJ Platform dependencies for testing
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(
            providers.gradleProperty("platformBundledPlugins")
                .map {
                    it.split(",")
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                },
        )

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(
            providers.gradleProperty("platformPlugins")
                .map {
                    it.split(",")
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                },
        )

        testFramework(TestFrameworkType.Platform)
    }


    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
    // Align Kotlin stdlib with the root project (2.4.0): transitive moshi pins
    // resolve 1.9.x here, whose coroutine debug metadata (v1) mismatches the
    // platform's coroutines-debug (expects v2) and hangs app init in tests.
    testImplementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.0"))
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")

}

// Same 2026.2.2 flat-test-classpath treatment as the root project (see root
// build.gradle.kts Phase 2 comments): fork-first fastutil ordering, ml.llm out
// of the test JVM, Ultimate plugin first. Test-JVM-only.
tasks.named<Test>("test") {
    // NOTE: do not call useJUnitPlatform() here; the default test detector
    // matches the JUnit3-style platform fixtures. Forcing the platform launcher
    // mixes junit-platform-commons versions at executor startup.
    val forkFastutil =
        configurations.detachedConfiguration(
            project.dependencies.create("org.jetbrains.intellij.deps.fastutil:intellij-deps-fastutil:8.5.18-jb1"),
        )
    classpath = files(forkFastutil) + classpath.filter { it.name != "fleet.fastutil.jar" } + files(
        classpath.filter { it.name == "fleet.fastutil.jar" },
    )
    classpath = classpath.filter { !it.absolutePath.contains("com.intellij.ml.llm-") }
    classpath = files(classpath.filter { it.name == "ultimate-plugin.jar" }) + classpath.filter { it.name != "ultimate-plugin.jar" }

    // The live HTTP workflow test needs the harness-owned disposable backend.
    // JUnit3-style platform fixtures do not honour a JUnit 4 assumption as a
    // skip (it is reported as a failure), so gate the class at the task level:
    // without CODE4ME_E2E_BASE_URL the suite stays green and the live test is
    // simply not selected.
    if (System.getenv("CODE4ME_E2E_BASE_URL").isNullOrBlank()) {
        filter.excludeTestsMatching("integration.LiveStudyWorkflowPluginTest")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description =
            providers.fileContents(layout.projectDirectory.file("README.md")).asText
                .map {
                    val start = "<!-- Plugin description -->"
                    val end = "<!-- Plugin description end -->"

                    with(it.lines()) {
                        if (!containsAll(listOf(start, end))) {
                            throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                        }
                        subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
                    }
                }

        // Get the latest available change notes from the changelog file
        changeNotes = ""

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels =
            providers.gradleProperty("pluginVersion")
                .map { pluginVersion ->
                    listOf(
                        pluginVersion
                            .substringAfter('-', "")
                            .substringBefore('.')
                            .ifEmpty { "default" },
                    )
                }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders +=
                    CommandLineArgumentProvider {
                        listOf(
                            "-Drobot-server.port=8082",
                            "-Dide.mac.message.dialogs.as.sheets=false",
                            "-Djb.privacy.policy.text=<!--999.999-->",
                            "-Djb.consents.confirmation.enabled=false",
                        )
                    }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}
