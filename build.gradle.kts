import groovy.json.JsonSlurper
import java.io.File
import java.net.URI
import java.security.MessageDigest
import org.gradle.kotlin.dsl.register
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

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
// Java 25: required for IntelliJ Platform 2026.2+ (IDE runs on JBR 25).
// Kotlin 2.4 matches the 2026.2 platform runtime (sinceBuild 262).
kotlin {
    jvmToolchain(25)
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
    }
}

configurations.named("runtimeClasspath") {
    // Kotlin is supplied by IntelliJ. Do not package transitive copies pulled
    // in by Moshi, OkHttp, or the generated API client.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
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

// Phase 2 fastutil test-runtime fix for 2026.2.2 (262.10315.125).
// Reproduced: NoSuchMethodError 'void Int2IntOpenHashMap.forEach(IntIntBiConsumer)'
// at LightPlatformTestCase.initProject:221. Graph shows test configs contain both
// bundledModule:fleet.fastutil (plain upstream, missing fork methods)
// and bundledModule:intellij.libraries.fastutil (patched fork, superset).
// Keep the fork, drop upstream, test-runtime-only. Production classpaths untouched.
configurations.matching { it.name.contains("Test", ignoreCase = true) }.configureEach {
    exclude(group = "bundledModule", module = "fleet.fastutil")
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
    // 0.8.15+: reads Java 25 (v69) bytecode from toolchain-25 classes.
    toolVersion = "0.8.15"
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

dokka {
    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("dokka"))
    }
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
        providers.systemProperty("code4me.developerAgents").orNull?.let {
            systemProperty("code4me.developerAgents", it)
        }

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
        // Phase 2 fastutil test-JVM fix (2026.2.2): the flat Gradle test classpath
        // contains THREE fastutil jars: patched fork twice (intellij-deps-fastutil
        // Maven + intellij.libraries.fastutil module) and plain upstream
        // fleet.fastutil.jar. They are NOT interchangeable: `it.unimi.dsi.*`
        // exists in both (fork has extra methods like Int2IntOpenHashMap.forEach),
        // while `fleet.fastutil.*` (relocated) exists ONLY in the fleet jar —
        // removing it hangs app init with NoClassDefFoundError fleet/fastutil.
        // So: keep all three, but force a fork copy FIRST so `it.unimi` resolves
        // from the fork while `fleet.*` still resolves from the fleet jar.
        // Test-JVM-only; distribution, sandbox, production, verifier untouched.
        // Precedent: JetBrains IdeaVim test-classpath surgery for flat-classpath
        // collisions. Pinned version fails loudly at resolve time if renamed.
        val forkFastutil = configurations.detachedConfiguration(
            project.dependencies.create("org.jetbrains.intellij.deps.fastutil:intellij-deps-fastutil:8.5.18-jb1"),
        )
        classpath = files(forkFastutil) + classpath.filter { it.name != "fleet.fastutil.jar" } + files(
            // Re-append ONLY if present, after the fork: provides fleet.* packages.
            classpath.filter { it.name == "fleet.fastutil.jar" },
        )
        // Drop the JetBrains AI Assistant plugin from the TEST JVM only: it is
        // unloadable in the test sandbox (missing com.intellij.modules.platform)
        // and its obfuscated jars collide with Ultimate post-startup loading.
        // runIde/production keep it for ACP chat E2E.
        classpath = classpath.filter { !it.absolutePath.contains("com.intellij.ml.llm-") }
        // Ultimate plugin first: its obfuscated B.B.B.B.s is the concrete
        // postStartupActivity impl; lib/product-backend.jar carries the same FQN
        // as an interface and wins alphabetically on the flat test classpath.
        // Mirrors production (plugin layer shadows platform). Test-JVM-only.
        classpath = files(classpath.filter { it.name == "ultimate-plugin.jar" }) + classpath.filter { it.name != "ultimate-plugin.jar" }
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

    register<Zip>("dokkaZip") {
        dependsOn("dokkaGeneratePublicationHtml")
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

// Participant releases must explicitly select the hosted backend. The source plugin.conf remains
// developer-owned (and may point at localhost); only the copied build resource is rewritten.
val participantServerUrl = providers.gradleProperty("code4me.serverUrl")
val configuredParticipantServerUrl = participantServerUrl.orNull?.trim()?.trimEnd('/')
val configuredPluginVersion = providers.gradleProperty("pluginVersion").get()
val localRuntimeResourceDir = providers.gradleProperty("code4me.localRuntimeDir").orNull?.trim()?.takeIf { it.isNotEmpty() }
val participantRuntimeResourceRoot = layout.projectDirectory.dir("src/main/resources").asFile.toPath().toString()
val participantBuildRequested = gradle.startParameter.taskNames.any { it.endsWith("buildParticipantPlugin") }
require(!(participantBuildRequested && localRuntimeResourceDir != null)) {
    "Local runtime overlays cannot be used for participant release builds."
}
if (participantBuildRequested) {
    require(project.version.toString().matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?"))) {
        "Participant plugin version must be meaningful SemVer (for example 1.2.0)."
    }
}
tasks.named<ProcessResources>("processResources") {
    inputs.property("code4me.serverUrl", configuredParticipantServerUrl ?: "")
    inputs.property("code4me.localRuntimeDir", localRuntimeResourceDir ?: "")
    localRuntimeResourceDir?.let { generatedResourcePath ->
        val generatedResourceRoot = file(generatedResourcePath)
        inputs.dir(generatedResourceRoot)
        doLast {
            val generatedRuntimeRoot = generatedResourceRoot.resolve("code4me-runtime")
            require(generatedRuntimeRoot.resolve("manifest.json").isFile) {
                "Local runtime overlay is missing code4me-runtime/manifest.json"
            }
            val destination = destinationDir.resolve("code4me-runtime")
            delete(destination)
            copy {
                from(generatedRuntimeRoot)
                into(destination)
            }
        }
    }
    if (participantBuildRequested) require(!configuredParticipantServerUrl.isNullOrBlank()) {
        "Pass -Pcode4me.serverUrl=https://api.example.org"
    }
    configuredParticipantServerUrl?.let { url ->
        val origin = runCatching { URI(url) }.getOrElse {
            throw GradleException("Participant backend is not a valid HTTPS origin: $url", it)
        }
        require(
            origin.scheme == "https" &&
                !origin.host.isNullOrBlank() &&
                origin.userInfo == null &&
                origin.query == null &&
                origin.fragment == null &&
                (origin.path.isNullOrBlank() || origin.path == "/"),
        ) { "Participant backend must be a credential-free HTTPS origin: $url" }
        filesMatching("plugin.conf") {
            filter { line: String ->
                if (line.trimStart().startsWith("host =") || line.trimStart().startsWith("acpRuntimeBaseUrl =")) {
                    line.replace(Regex("\"[^\"]*\""), "\"$url\"")
                } else line
            }
        }
    }
}

tasks.register("buildParticipantPlugin") {
    group = "distribution"
    description = "Builds a study ZIP with an explicit HTTPS backend (-Pcode4me.serverUrl=...)."
    dependsOn("verifyParticipantRuntimeResources", "buildPlugin")
}

tasks.register("verifyParticipantRuntimeResources") {
    group = "verification"
    description = "Verifies all four native runtime archives before a participant build."
    // JsonSlurper and digest verification run only for release assembly. This
    // task intentionally opts out instead of making the repository-wide
    // configuration-cache setting turn an otherwise valid release into a
    // second, unrelated build failure.
    notCompatibleWithConfigurationCache("Participant archive verification uses release-only script objects")
    inputs.dir(participantRuntimeResourceRoot)
    inputs.property("participantPluginVersion", configuredPluginVersion)
    doLast {
        val resourceRoot = File(participantRuntimeResourceRoot)
        val manifestFile = resourceRoot.resolve("code4me-runtime/manifest.json")
        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parse(manifestFile) as Map<String, Any?>
        require(manifest["manifest_version"].toString() == "1") {
            "Runtime manifest version must be 1"
        }
        require(manifest["managed_protocol_version"].toString() == "1") {
            "Managed protocol version must be 1"
        }
        require(manifest["runtime_version"].toString() == configuredPluginVersion) {
            "Runtime and participant plugin versions must match"
        }
        @Suppress("UNCHECKED_CAST")
        val artifacts = manifest["artifacts"] as? List<Map<String, Any?>> ?: error("Runtime manifest has no artifacts")
        val expected = setOf("macos-arm64", "macos-x64", "windows-x64", "linux-x64")
        val actual = artifacts.map { "${it["platform"]}-${it["architecture"]}" }.toSet()
        require(artifacts.size == expected.size && actual == expected) {
            "Runtime manifest platforms must be exactly $expected; found $actual"
        }
        artifacts.forEach { artifact ->
            require(artifact["runtime_id"] == "code4me-agent") { "Unexpected runtime ID in manifest" }
            require(artifact["version"].toString() == configuredPluginVersion) {
                "Runtime artifact version does not match the participant plugin"
            }
            require(artifact["managed_protocol"].toString() == "1") {
                "Runtime artifact does not support managed protocol v1"
            }
            val archive = resourceRoot.resolve(artifact["archive"].toString()).normalize()
            require(archive.startsWith(resourceRoot) && archive.isFile) { "Missing runtime archive: $archive" }
            val digest = MessageDigest.getInstance("SHA-256")
            archive.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            require(checksum == artifact["sha256"].toString().lowercase()) {
                "Runtime checksum mismatch: ${archive.name}"
            }
        }
    }
}

// When both tasks are selected by buildParticipantPlugin, reject incomplete or
// mismatched native bundles before starting the expensive IntelliJ ZIP build.
tasks.named("buildPlugin") {
    mustRunAfter("verifyParticipantRuntimeResources")
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
