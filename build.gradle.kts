import org.gradle.kotlin.dsl.register
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
    jacoco // JaCoCo Plugin for code coverage
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

    // IntelliJ Platform Gradle Plugin Repositories Extension
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies
dependencies {
    implementation(project(":generated"))

    implementation("com.typesafe:config:1.4.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.api-client:google-api-client:2.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1") // Google OAuth Client Library
    implementation("com.google.auth:google-auth-library-oauth2-http:1.20.0") // Google Auth Library
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1") // Moshi library for JSON parsing
    implementation("com.squareup.moshi:moshi-adapters:1.15.1") // Moshi adapters for additional types
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    // mockk's BOM pins kotlinx-coroutines to 1.6.4, which wins over the (much newer) coroutines
    // the IntelliJ Platform puts on the test classpath. The platform's coroutines-javaagent is
    // compiled against the newer DebugProbesImpl API, so with 1.6.4 present its premain throws
    // NoSuchMethodError and aborts every test JVM. Plugins must always use the platform's
    // coroutines, so drop mockk's copy and its version constraint.
    testImplementation("io.mockk:mockk:1.14.2") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }

    // IntelliJ Platform Gradle Plugin Dependencies Extension
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        // Both lists are trimmed and emptied-filtered so a blank gradle property yields no
        // coordinate at all rather than a single "" entry the resolver would choke on.
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
    testImplementation(kotlin("test"))
}

// Configure IntelliJ Platform Gradle Plugin
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

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

        val changelog = project.changelog
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

// Configure Gradle Changelog Plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

// Configure JaCoCo Plugin
jacoco {
    toolVersion = "0.8.11"
}

ktlint {
    version = "1.1.1"
    verbose = true
    outputToConsole = true
    enableExperimentalRules = true
    filter {
        exclude { element -> element.file.path.contains("generated/") }
        exclude { element -> element.file.path.contains("integration/") }
    }
    ignoreFailures = true
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

    runIde {
        // The vendored codex-acp proxy lives in THIS plugin repo, but at runtime the open project
        // is the user's codebase, so AgentStartupManager cannot find it via project.basePath.
        // Pass the repo path explicitly.
        //
        // DEVELOPMENT-only: this points the sandbox IDE at the codex-acp source tree, which needs
        // Node.js >= 18 and npm on PATH. A production release would instead compile codex-acp to a
        // standalone binary, bundle it in the plugin distribution zip, and drop this property.
        systemProperty(
            "code4me.codexProxyDir",
            projectDir.resolve("dev/codex-acp-proxy/codex-acp").absolutePath,
        )
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
        finalizedBy(jacocoTestReport) // Generate JaCoCo report after tests
    }

    jacocoTestReport {
        dependsOn(test) // Ensure tests run before generating report
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }

        // Exclude generated code from coverage reports
        classDirectories.setFrom(
            files(
                classDirectories.files.map {
                    fileTree(it) {
                        exclude(
                            "**/generated/**",
                            "**/integration/**",
                        )
                    }
                },
            ),
        )
    }

    jacocoTestCoverageVerification {
        dependsOn(jacocoTestReport)
        violationRules {
            rule {
                limit {
                    minimum = "0.60".toBigDecimal() // 60% minimum coverage
                }
            }
        }

        // Exclude generated code from coverage verification
        classDirectories.setFrom(
            files(
                classDirectories.files.map {
                    fileTree(it) {
                        exclude(
                            "**/generated/**",
                            "**/integration/**",
                        )
                    }
                },
            ),
        )
    }

    // Make check task depend on JaCoCo test report generation
    check {
        dependsOn(jacocoTestReport)
    }

    val dokkaHtml by getting(org.jetbrains.dokka.gradle.DokkaTask::class) {
        outputDirectory.set(layout.buildDirectory.dir("dokka"))
    }

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

// Add a task to run integration tests - but don't make it part of the build cycle
tasks.register("integrationTest") {
    description = "Runs integration tests in the integration-tests subproject"
    group = "verification"

    dependsOn(":integration-tests:test")
}

// DON'T make check depend on integration tests to avoid circular dependency
// Users can run integration tests separately with ./gradlew integrationTest

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
