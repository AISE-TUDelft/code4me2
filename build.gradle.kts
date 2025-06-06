import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
    alias(libs.plugins.dokka) // Gradle Dokka Plugin for documentation
    alias(libs.plugins.ktlint) // Gradle Ktlint Plugin for Kotlin code style
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    implementation(project(":generated"))

    implementation("com.typesafe:config:1.4.2") // Hocon configuration library
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // OkHttp library for HTTP requests
    implementation("com.google.api-client:google-api-client:2.2.0") // Google API Client Library
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1") // Google OAuth Client Library
    implementation("com.google.auth:google-auth-library-oauth2-http:1.20.0") // Google Auth Library
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1") // Moshi library for JSON parsing
    implementation("com.squareup.moshi:moshi-adapters:1.15.1") // Moshi adapters for additional types
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.0")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins("org.intellij.plugins.markdown")
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
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

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes =
            providers.gradleProperty("pluginVersion")
                .map { pluginVersion ->
                    with(changelog) {
                        renderItem(
                            (getOrNull(pluginVersion) ?: getUnreleased())
                                .withHeader(false)
                                .withEmptySections(false),
                            Changelog.OutputType.HTML,
                        )
                    }
                }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
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

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

ktlint {
    version = "1.1.1"
    verbose = true
    outputToConsole = true
    enableExperimentalRules = true
    filter {
        exclude { element -> element.file.path.contains("generated/") }
    }
    ignoreFailures = true
    // the reason I set this to true is that we don't want to fail the build if there are any ktlint issues
    // but it is something we should fix and be aware of
}

tasks {

    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    register("formatKotlin") {
        group = "formatting"
        description = "Format Kotlin code style."
        dependsOn("ktlintFormat")
    }

    build {
        dependsOn("formatKotlin", "ktlintCheck")
    }

    // Configure Dokka HTML documentation task
    val dokkaHtml by getting(org.jetbrains.dokka.gradle.DokkaTask::class) {
        outputDirectory.set(layout.buildDirectory.dir("dokka"))
    }

    // Task to create a zip archive of the Dokka documentation
    register<Zip>("dokkaZip") {
        dependsOn(dokkaHtml)
        archiveBaseName.set("dokka-documentation")
        archiveVersion.set(project.version.toString())
        archiveExtension.set("zip")
        from(layout.buildDirectory.dir("dokka"))
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    }

    publishPlugin {
        dependsOn(patchChangelog)
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
