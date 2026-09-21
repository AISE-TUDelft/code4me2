import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.kotlin.dsl.register
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest

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
        val forkFastutil =
            configurations.detachedConfiguration(
                project.dependencies.create("org.jetbrains.intellij.deps.fastutil:intellij-deps-fastutil:8.5.18-jb1"),
            )
        classpath = files(forkFastutil) + classpath.filter { it.name != "fleet.fastutil.jar" } +
            files(
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
val participantReleaseDir = providers.gradleProperty("participantReleaseDir").orNull?.let { file(it) }
val participantRuntimeResourceRoot =
    participantReleaseDir?.resolve("resources")?.absolutePath
        ?: layout.projectDirectory.dir("src/main/resources").asFile.toPath().toString()
val participantBuildRequested = gradle.startParameter.taskNames.any { it.endsWith("buildParticipantPlugin") }
require(!(participantBuildRequested && localRuntimeResourceDir != null)) {
    "Local runtime overlays cannot be used for participant release builds."
}
if (participantBuildRequested) {
    require(participantReleaseDir?.resolve("catalog.json")?.isFile == true) {
        "Participant builds require -PparticipantReleaseDir=<prepared recipe>; run scripts/participant-release.py prepare."
    }
    require(project.version.toString().matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?"))) {
        "Participant plugin version must be meaningful SemVer (for example 1.2.0)."
    }
}
tasks.named<ProcessResources>("processResources") {
    inputs.property("code4me.serverUrl", configuredParticipantServerUrl ?: "")
    inputs.property("code4me.localRuntimeDir", localRuntimeResourceDir ?: "")
    (participantReleaseDir?.resolve("resources")?.absolutePath ?: localRuntimeResourceDir)?.let { generatedResourcePath ->
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
    if (participantBuildRequested) {
        require(!configuredParticipantServerUrl.isNullOrBlank()) {
            "Pass -Pcode4me.serverUrl=https://api.example.org"
        }
    }
    configuredParticipantServerUrl?.let { url ->
        val origin =
            runCatching { URI(url) }.getOrElse {
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
                } else {
                    line
                }
            }
        }
    }
}

tasks.register("buildParticipantPlugin") {
    group = "distribution"
    description = "Builds a study ZIP with an explicit HTTPS backend (-Pcode4me.serverUrl=...)."
    dependsOn("verifyParticipantRuntimeResources", "buildPlugin")
    val zipFile = tasks.named<org.gradle.api.tasks.bundling.Zip>("buildPlugin").flatMap { it.archiveFile }
    val artifactPath = layout.buildDirectory.file("participant-artifact-path.txt")
    doLast {
        artifactPath.get().asFile.writeText(zipFile.get().asFile.absolutePath + "\n")
    }
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
        val runtimeVersion = manifest["runtime_version"].toString()
        require(runtimeVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")) &&
            runtimeVersion != "0.0.0-dev") { "Runtime version must be a real release version" }
        @Suppress("UNCHECKED_CAST")
        val artifacts = manifest["artifacts"] as? List<Map<String, Any?>> ?: error("Runtime manifest has no artifacts")
        val expected = setOf("macos-arm64", "macos-x64", "windows-x64", "linux-x64")
        val actual = artifacts.map { "${it["platform"]}-${it["architecture"]}" }.toSet()
        require(artifacts.size == expected.size && actual == expected) {
            "Runtime manifest platforms must be exactly $expected; found $actual"
        }
        artifacts.forEach { artifact ->
            require(artifact["runtime_id"] == "code4me-agent") { "Unexpected runtime ID in manifest" }
            require(artifact["version"].toString() == runtimeVersion) {
                "Runtime artifact version does not match the runtime manifest"
            }
            require(artifact["managed_protocol"].toString() == "1") {
                "Runtime artifact does not support managed protocol v1"
            }
            val archive = resourceRoot.resolve(artifact["archive"].toString()).normalize()
            require(archive.startsWith(resourceRoot) && archive.isFile) { "Missing runtime archive: $archive" }
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(archive.inputStream().buffered(), digest).use {
                it.transferTo(OutputStream.nullOutputStream())
            }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            require(checksum == artifact["sha256"].toString().lowercase()) {
                "Runtime checksum mismatch: ${archive.name}"
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Packaged research ACP proxy runtime (Issue 08 / Issue 11).
//
// `research-runtime/` is staged into plugin resources and shipped in the plugin
// jar/ZIP. `proxy-manifest.json` pins every payload file by relative safe path,
// size, and SHA-256; at runtime `PackagedProxyRuntimeResolver` refuses anything
// missing, mismatched, escaping, or not self-contained. No PATH/npm/source
// fallback is ever consulted.
// ---------------------------------------------------------------------------
val researchProxySourceRoot = layout.projectDirectory.dir("telemetry-acp-proxy")
val researchProxyDistRoot = layout.projectDirectory.dir(
    providers.gradleProperty("researchProxyDistDir").getOrElse("telemetry-acp-proxy/dist"),
)
val researchRuntimeStagingDir = layout.buildDirectory.dir("research-runtime-staging")

val hostResearchOs: String =
    when {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
        System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> "linux"
        else -> "unsupported"
    }
val hostResearchArch: String =
    when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "amd64", "x86_64", "x64" -> "x64"
        else -> "unsupported"
    }

// Participant platform matrix (ISSUE-04). A release declares every supported
// platform (`-PresearchProxyPlatforms=macos-aarch64,macos-x64,linux-x64`) and
// `-PrequireResearchProxyBundles=true` refuses to stage source for any platform
// whose prebuilt PyInstaller bundle is absent. Without either flag the staging
// task keeps the build host only and may use the source fallback for local dev.
val researchProxyPlatformsOption: String? =
    providers.gradleProperty("researchProxyPlatforms").orNull?.trim()?.takeIf { it.isNotEmpty() }
val requireResearchProxyBundles: Boolean =
    providers.gradleProperty("requireResearchProxyBundles").orNull?.trim()?.equals("true", ignoreCase = true) == true
val researchProxyStrictMode: Boolean = researchProxyPlatformsOption != null || requireResearchProxyBundles

val stageResearchProxy =
    tasks.register("stageResearchProxy") {
        group = "build"
        description =
            "Stages the digest-pinned research ACP proxy runtime into plugin resources. " +
            "Declare release platforms with -PresearchProxyPlatforms=<os>-<arch>,... and " +
            "require prebuilt bundles with -PrequireResearchProxyBundles=true. " +
            "Override the packaged agent with -PresearchAgentDir/-PresearchAgentBinary."
        inputs.dir(researchProxySourceRoot).withPathSensitivity(PathSensitivity.RELATIVE)
        if (researchProxyDistRoot.asFile.isDirectory) {
            inputs.dir(researchProxyDistRoot).withPathSensitivity(PathSensitivity.RELATIVE)
        }
        outputs.dir(researchRuntimeStagingDir)
        // Capture plain values (no script/Provider references) so the task action
        // is safe to store in the configuration cache.
        val sourceRootFile = researchProxySourceRoot.asFile
        val distRootFile = researchProxyDistRoot.asFile
        val stagingRootFile = researchRuntimeStagingDir.get().asFile
        val runtimeResourceRootPath = participantRuntimeResourceRoot
        val targetOs = hostResearchOs
        val targetArch = hostResearchArch
        val platformsOption = researchProxyPlatformsOption
        val strictMode = researchProxyStrictMode
        inputs.property("researchProxyPlatforms", platformsOption ?: "")
        inputs.property("requireResearchProxyBundles", strictMode)
        // Supported packaged-agent staging overrides (Issue 11 / Gap 5). They let
        // an operator stage a real agent without touching the dev tree; the
        // `agent` block is still populated only with real digests.
        val agentDirOption = providers.gradleProperty("researchAgentDir").orNull
        val agentBinaryOption = providers.gradleProperty("researchAgentBinary").orNull
        val agentDigestOption = providers.gradleProperty("researchAgentDigest").orNull
        val agentArgsOption = providers.gradleProperty("researchAgentArgs").orNull
        val preparedRelease = participantReleaseDir
        preparedRelease?.let { inputs.dir(it).withPathSensitivity(PathSensitivity.RELATIVE) }
        val participantVersion = configuredPluginVersion
        agentDirOption?.let { inputs.dir(it).withPathSensitivity(PathSensitivity.RELATIVE) }
        agentBinaryOption?.let { inputs.file(it) }
        // The declared pin is a task input so changing it re-runs the task and a
        // wrong pin is never silently skipped by an up-to-date output.
        inputs.property("researchAgentDigest", agentDigestOption ?: "")
        inputs.property("researchAgentArgs", agentArgsOption ?: "")
        doLast {
            @Suppress("UNCHECKED_CAST")
            val catalog = preparedRelease?.let {
                JsonSlurper().parse(it.resolve("catalog.json")) as Map<String, Any?>
            }
            @Suppress("UNCHECKED_CAST")
            val inventory = catalog?.get("participant_release") as? Map<String, Any?>
            if (catalog != null) {
                require(catalog["schema_version"] == "1" && inventory?.get("plugin_version") == participantVersion) {
                    "Prepared catalog version does not match this participant build"
                }
                require(agentDirOption == null && agentBinaryOption == null && agentArgsOption == null && agentDigestOption == null) {
                    "Prepared participant releases cannot be overridden by legacy agent properties"
                }
            }
            fun sha256Of(file: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                DigestInputStream(file.inputStream().buffered(), digest).use {
                    it.transferTo(OutputStream.nullOutputStream())
                }
                return digest.digest().joinToString("") { "%02x".format(it) }
            }

            fun copyTree(
                source: File,
                destination: File,
                skipDirectoryNames: Set<String> = emptySet(),
            ) {
                if (!source.exists()) return
                source.walkTopDown().forEach { file ->
                    val relative = file.relativeTo(source).path.replace(File.separatorChar, '/')
                    if (relative.split('/').any { it in skipDirectoryNames }) return@forEach
                    val target = File(destination, relative)
                    if (file.isDirectory) {
                        target.mkdirs()
                    } else if (!file.name.endsWith(".pyc")) {
                        target.parentFile?.mkdirs()
                        file.copyTo(target, overwrite = true)
                    }
                }
            }

            val staging = stagingRootFile
            staging.walkBottomUp().forEach { it.delete() }
            staging.mkdirs()

            val knownPlatformOs = setOf("macos", "linux", "windows")
            val knownPlatformArch = setOf("aarch64", "x64")

            fun parsePlatformList(raw: String?): List<Pair<String, String>> {
                if (raw == null) return listOf(targetOs to targetArch)
                val tokens = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (tokens.isEmpty()) {
                    throw GradleException(
                        "-PresearchProxyPlatforms must be a comma-separated list such as " +
                            "macos-aarch64,macos-x64,linux-x64",
                    )
                }
                return tokens.map { token ->
                    val separator = token.indexOf('-')
                    val osName = if (separator > 0) token.substring(0, separator) else ""
                    val archName = if (separator > 0) token.substring(separator + 1) else ""
                    if (osName !in knownPlatformOs || archName !in knownPlatformArch) {
                        throw GradleException(
                            "Unsupported -PresearchProxyPlatforms entry '$token'; expected " +
                                "<os>-<arch> with os in $knownPlatformOs and arch in $knownPlatformArch",
                        )
                    }
                    osName to archName
                }.distinct()
            }

            fun proxyExecutableName(osName: String): String =
                if (osName == "windows") "telemetry-acp-proxy.exe" else "telemetry-acp-proxy"

            fun prebuiltBundle(platform: Pair<String, String>): File? {
                val directory = File(distRootFile, "${platform.first}-${platform.second}")
                return directory.takeIf {
                    it.isDirectory && File(it, proxyExecutableName(platform.first)).isFile
                }
            }

            val requestedPlatforms = parsePlatformList(platformsOption)
            val missingPlatforms =
                requestedPlatforms
                    .filter { prebuiltBundle(it) == null }
                    .map { "${it.first}-${it.second}" }
            if (strictMode && missingPlatforms.isNotEmpty()) {
                throw GradleException(
                    "Missing self-contained research proxy bundle(s) for: ${missingPlatforms.joinToString(", ")}. " +
                        "Release staging refuses to package proxy source; build each platform on a matching " +
                        "host with ./gradlew buildResearchProxyBundle so its output lands in " +
                        "telemetry-acp-proxy/dist/<os>-<arch>/. The participant workflow builds these in its " +
                        "research-proxy matrix and downloads the research-proxy-* artifacts before staging.",
                )
            }

            // Explicit agent overrides describe a single bundle. They apply to the
            // only declared platform, or to the build host when it is part of the
            // matrix; multi-platform releases stage agents from
            // telemetry-acp-proxy/agents/<os>-<arch>/ instead.
            val explicitAgentRequested = agentDirOption != null || agentBinaryOption != null
            val explicitAgentPlatform: Pair<String, String>? =
                when {
                    !explicitAgentRequested -> null
                    requestedPlatforms.size == 1 -> requestedPlatforms.single()
                    else ->
                        requestedPlatforms.firstOrNull { it.first == targetOs && it.second == targetArch }
                            ?: throw GradleException(
                                "-PresearchAgentDir/-PresearchAgentBinary describe one agent bundle, but the " +
                                    "declared platform matrix does not contain the build host $targetOs-$targetArch. " +
                                    "Stage per-platform agents under telemetry-acp-proxy/agents/<os>-<arch>/ instead.",
                            )
                }

            fun normalizeDigest(raw: String?): String? {
                if (raw == null) return null
                val text = raw.trim().lowercase()
                val stripped = text.removePrefix("sha256:")
                return if (Regex("^[0-9a-f]{64}$").matches(stripped)) stripped else null
            }

            // If the runtime manifest names a platform archive that actually
            // exists on disk, prefer its declared executable name so the staged
            // agent is named correctly. When the archive is absent this is empty
            // and detection behaves exactly as before.
            val runtimeManifestFile = File(runtimeResourceRootPath, "code4me-runtime/manifest.json")

            fun manifestAgentExecutables(platformOs: String, platformArch: String): Set<String> {
                if (!runtimeManifestFile.isFile) return emptySet()
                return runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val manifest = JsonSlurper().parse(runtimeManifestFile) as Map<String, Any?>
                    @Suppress("UNCHECKED_CAST")
                    val artifacts = manifest["artifacts"] as? List<Map<String, Any?>> ?: emptyList()
                    fun normalizeManifestArch(raw: Any?): String =
                        when (raw?.toString()?.lowercase()) {
                            "aarch64", "arm64" -> "aarch64"
                            "x64", "amd64", "x86_64" -> "x64"
                            else -> raw?.toString()?.lowercase().orEmpty()
                        }
                    artifacts
                        .filter {
                            it["platform"]?.toString() == platformOs &&
                                normalizeManifestArch(it["architecture"]) == platformArch
                        }.mapNotNull { artifact ->
                            val archive = artifact["archive"]?.toString()
                            val executable = artifact["executable"]?.toString()
                            if (archive != null &&
                                executable != null &&
                                File(runtimeResourceRootPath, archive).isFile
                            ) {
                                executable
                            } else {
                                null
                            }
                        }.toSet()
                }.getOrElse { emptySet() }
            }

            // Optional packaged agent. A real agent is staged only from an explicit
            // `-PresearchAgentDir`/`-PresearchAgentBinary`, or from the dev
            // auto-detection path; otherwise the `agent` block is omitted (never
            // invented) and the runtime is refused at launch.
            fun agentBlockFor(platformOs: String, platformArch: String): Map<String, Any?>? {
                val platformId = "$platformOs-$platformArch"
                val agentTargetDir = File(staging, "agents/$platformId")
                val autoAgentSource = File(sourceRootFile, "agents/$platformId")
                val agentCandidates =
                    setOf("code4me-agent", "code4me-agent.exe", "codex-acp", "codex-acp.exe") +
                        manifestAgentExecutables(platformOs, platformArch)

                fun detectAgentEntrypoint(directory: File, allowSingleFile: Boolean = false): String? {
                    val allFiles = directory.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
                    val entry = allFiles.firstOrNull { it.name in agentCandidates }
                        ?: allFiles.singleOrNull().takeIf { allowSingleFile }
                    return entry?.relativeTo(directory)?.invariantSeparatorsPath
                }

                val useExplicit = explicitAgentPlatform == (platformOs to platformArch)
                val explicitAgentDir = if (useExplicit) agentDirOption?.let { File(it) } else null
                val explicitAgentBinary = if (useExplicit) agentBinaryOption?.let { File(it) } else null

                val agentEntryRelative: String? =
                    when {
                        explicitAgentDir != null -> {
                            if (!explicitAgentDir.isDirectory) {
                                throw GradleException("-PresearchAgentDir is not a directory: ${explicitAgentDir.absolutePath}")
                            }
                            if (explicitAgentBinary != null && !explicitAgentBinary.isFile) {
                                throw GradleException("-PresearchAgentBinary is not a file: ${explicitAgentBinary.absolutePath}")
                            }
                            copyTree(explicitAgentDir, agentTargetDir)
                            if (explicitAgentBinary != null) {
                                val relative = explicitAgentBinary.relativeTo(explicitAgentDir)
                                if (relative.path.startsWith("..")) {
                                    throw GradleException(
                                        "-PresearchAgentBinary must live inside -PresearchAgentDir; got " +
                                            "${explicitAgentBinary.absolutePath} outside ${explicitAgentDir.absolutePath}",
                                    )
                                }
                                relative.path.replace(File.separatorChar, '/')
                            } else {
                                detectAgentEntrypoint(agentTargetDir, allowSingleFile = true)
                                    ?: throw GradleException(
                                        "could not determine the agent entrypoint in ${agentTargetDir.absolutePath}; " +
                                            "pass -PresearchAgentBinary=<path> or name it one of $agentCandidates",
                                    )
                            }
                        }
                        explicitAgentBinary != null -> {
                            if (!explicitAgentBinary.isFile) {
                                throw GradleException("-PresearchAgentBinary is not a file: ${explicitAgentBinary.absolutePath}")
                            }
                            agentTargetDir.mkdirs()
                            explicitAgentBinary.copyTo(File(agentTargetDir, explicitAgentBinary.name), overwrite = true)
                            explicitAgentBinary.name
                        }
                        autoAgentSource.isDirectory -> {
                            detectAgentEntrypoint(autoAgentSource)?.also {
                                copyTree(autoAgentSource, agentTargetDir)
                            }
                        }
                        else -> null
                    }
                if (agentEntryRelative == null) return null

                // A managed Code4Me agent must run in `--managed` mode; other
                // agents keep an argument-free entrypoint unless
                // -PresearchAgentArgs overrides.
                val managedAgentExecutableNames =
                    setOf("code4me-agent", "code4me-agent.exe", "code4me2-agent", "code4me2-agent.exe")
                val isManagedCode4MeAgent =
                    File(agentEntryRelative).name.lowercase() in managedAgentExecutableNames
                val explicitAgentArgs =
                    agentArgsOption?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
                val researchAgentArgs: List<String> =
                    when {
                        explicitAgentArgs != null -> explicitAgentArgs
                        isManagedCode4MeAgent -> listOf("--managed")
                        else -> emptyList()
                    }

                val agentEntryFile = File(agentTargetDir, agentEntryRelative)
                agentEntryFile.parentFile?.mkdirs()
                agentEntryFile.setExecutable(true, false)
                val computedDigest = sha256Of(agentEntryFile)
                if (useExplicit && agentDigestOption != null) {
                    val declaredDigest = normalizeDigest(agentDigestOption)
                    if (declaredDigest == null) {
                        throw GradleException(
                            "-PresearchAgentDigest must be a 64-hex sha256 (optionally prefixed with 'sha256:'): $agentDigestOption",
                        )
                    }
                    if (declaredDigest != computedDigest) {
                        throw GradleException(
                            "-PresearchAgentDigest $declaredDigest does not match the staged agent executable sha256 $computedDigest",
                        )
                    }
                }

                val agentFiles =
                    agentTargetDir
                        .walkTopDown()
                        .filter { it.isFile }
                        .sortedBy { it.path }
                        .map { file ->
                            linkedMapOf<String, Any>(
                                "path" to file.relativeTo(staging).invariantSeparatorsPath,
                                "sha256" to sha256Of(file),
                                "size" to file.length(),
                                "executable" to true,
                            )
                        }.toList()
                return linkedMapOf(
                    "entrypoint" to (listOf("agents/$platformId/$agentEntryRelative") + researchAgentArgs),
                    "digest" to computedDigest,
                    "files" to agentFiles,
                )
            }

            val platformEntries = mutableListOf<Map<String, Any?>>()
            for ((platformOs, platformArch) in requestedPlatforms) {
                val platformId = "$platformOs-$platformArch"
                val executableName = proxyExecutableName(platformOs)
                val prebuilt = prebuiltBundle(platformOs to platformArch)
                val selfContained = prebuilt != null
                val entrypoint: List<String>
                val proxyFiles = mutableListOf<Map<String, Any>>()

                if (prebuilt != null) {
                    if (inventory != null) {
                        val provenanceFile = File(prebuilt, "participant-provenance.json")
                        require(provenanceFile.isFile) { "Proxy $platformId is missing build provenance; rebuild it from pinned sources" }
                        @Suppress("UNCHECKED_CAST")
                        val provenance = JsonSlurper().parse(provenanceFile) as Map<String, Any?>
                        require(provenance["plugin_commit"] == inventory["plugin_commit"] &&
                            provenance["server_commit"] == inventory["server_commit"] &&
                            provenance["source_dirty"] == false &&
                            Regex("[0-9a-f]{64}").matches(provenance["contract_digest"].toString())
                        ) { "Proxy $platformId was not built from the recipe's clean pinned sources" }
                    }
                    // Multi-platform releases keep each PyInstaller onedir bundle
                    // under platforms/<os>-<arch>/ so their _internal trees cannot
                    // collide. The manifest entrypoint is relative to the runtime
                    // root and every record is verified by the resolver.
                    val platformPrefix = "platforms/$platformId/"
                    val platformDirectory = File(staging, platformPrefix)
                    copyTree(prebuilt, platformDirectory)
                    val executableRelative = platformPrefix + executableName
                    File(staging, executableRelative).setExecutable(true, false)
                    entrypoint = listOf(executableRelative)
                    platformDirectory
                        .walkTopDown()
                        .filter { it.isFile }
                        .sortedBy { it.path }
                        .forEach { file ->
                            val relative = file.relativeTo(staging).invariantSeparatorsPath
                            proxyFiles.add(
                                linkedMapOf(
                                    "path" to relative,
                                    "sha256" to sha256Of(file),
                                    "size" to file.length(),
                                    "executable" to (relative == executableRelative),
                                ),
                            )
                        }
                } else {
                    // Local-dev fallback (no platform matrix, no strict flag): a
                    // configured development interpreter can run the source
                    // package. Release staging never reaches this branch.
                    copyTree(
                        File(sourceRootFile, "telemetry_acp_proxy"),
                        File(staging, "py/telemetry_acp_proxy"),
                        skipDirectoryNames = setOf("__pycache__"),
                    )
                    val launcher = File(staging, "run.py")
                    launcher
                        .writeText(
                            """
                            |#!/usr/bin/env python3
                            |# Development launcher for the source-bundled telemetry ACP proxy.
                            |import sys
                            |from pathlib import Path
                            |
                            |sys.path.insert(0, str(Path(__file__).resolve().parent / "py"))
                            |
                            |from telemetry_acp_proxy.main import main  # noqa: E402
                            |
                            |if __name__ == "__main__":
                            |    raise SystemExit(main())
                            |
                            """.trimMargin(),
                        )
                    entrypoint = listOf("run.py")
                    proxyFiles.add(
                        linkedMapOf(
                            "path" to "run.py",
                            "sha256" to sha256Of(launcher),
                            "size" to launcher.length(),
                            "executable" to false,
                        ),
                    )
                    File(staging, "py")
                        .walkTopDown()
                        .filter { it.isFile }
                        .sortedBy { it.path }
                        .forEach { file ->
                            val relative = file.relativeTo(staging).invariantSeparatorsPath
                            proxyFiles.add(
                                linkedMapOf(
                                    "path" to relative,
                                    "sha256" to sha256Of(file),
                                    "size" to file.length(),
                                    "executable" to false,
                                ),
                            )
                        }
                }

                val platformEntry =
                    linkedMapOf<String, Any?>(
                        "os" to platformOs,
                        "arch" to platformArch,
                        "self_contained" to selfContained,
                        "entrypoint" to entrypoint,
                        "files" to proxyFiles,
                    )
                if (catalog == null) {
                    agentBlockFor(platformOs, platformArch)?.let { platformEntry["agent"] = it }
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val preparedPlatforms = catalog["platforms"] as List<Map<String, Any?>>
                    val preparedPlatform = preparedPlatforms.singleOrNull { it["os"] == platformOs && it["arch"] == platformArch }
                        ?: error("Prepared release has no unique platform $platformId")
                    @Suppress("UNCHECKED_CAST")
                    val agents = preparedPlatform["agents"] as List<Map<String, Any?>>
                    require(agents.isNotEmpty() && agents.map { it["release_id"] }.toSet().size == agents.size) {
                        "Prepared platform must have unique release-keyed agents"
                    }
                    val inputRoot = preparedRelease!!.resolve("research-agents").toPath().toRealPath()
                    for (agent in agents) {
                        @Suppress("UNCHECKED_CAST")
                        val records = agent["files"] as List<Map<String, Any?>>
                        for (record in records) {
                            val relative = record["path"].toString()
                            val source = inputRoot.resolve(relative).normalize()
                            require(source.startsWith(inputRoot) && source.toRealPath().startsWith(inputRoot)) { "Agent path escapes input" }
                            require(source.toFile().length() == (record["size"] as Number).toLong() &&
                                sha256Of(source.toFile()) == record["sha256"]) { "Prepared agent file differs: $relative" }
                            val target = File(staging, relative)
                            require(target.toPath().normalize().startsWith(staging.toPath())) { "Agent path escapes output" }
                            target.parentFile.mkdirs()
                            source.toFile().copyTo(target, overwrite = true)
                            target.setExecutable(record["executable"] == true, false)
                        }
                    }
                    platformEntry["agents"] = agents
                }
                platformEntries.add(platformEntry)
            }

            val manifest =
                linkedMapOf<String, Any?>(
                    "schema_version" to "1",
                    "platforms" to platformEntries,
                )
            inventory?.let {
                require((it["platforms"] as List<*>).toSet() ==
                    requestedPlatforms.map { pair -> pair.first + "-" + pair.second }.toSet()
                ) { "Prepared release platform coverage differs from requested platforms" }
                manifest["participant_release"] = it
            }
            File(staging, "proxy-manifest.json")
                .writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)))
        }
    }

// Per-platform helper: builds a self-contained PyInstaller bundle for the
// current platform. It is deliberately NOT wired into `build`/`test`; it
// requires a local Python with PyInstaller and only produces inputs for
// `stageResearchProxy`. PyInstaller cannot cross-compile, so CI runs it once per
// runner in the participant workflow's research-proxy matrix.
//
// The absolute paths it passes to PyInstaller (`--distpath`, `--workpath`,
// `--paths`) are BUILD inputs only: the produced onedir bundle embeds its own
// interpreter and dependencies, so the staged
// `telemetry-acp-proxy/dist/<os>-<arch>/` directory is self-contained and
// relocatable. No absolute build path is required (or referenced) at runtime.
val buildResearchProxyBundle =
    tasks.register("buildResearchProxyBundle") {
        group = "build"
        description =
            "Builds a self-contained PyInstaller proxy bundle into " +
            "telemetry-acp-proxy/dist/<os>-<arch>/ for this host. Not part of build/test."
        val proxyDirFile = researchProxySourceRoot.asFile
        val distRootFile = researchProxyDistRoot.asFile
        val serverSrcFile = layout.projectDirectory.dir("../code4me2-server/src").asFile
        val buildDirFile = layout.buildDirectory.dir("research-proxy-bundle").get().asFile
        val targetOs = hostResearchOs
        val targetArch = hostResearchArch
        val hostPlatformId = "$targetOs-$targetArch"
        val platformsOption = researchProxyPlatformsOption
        val pythonOverride = providers.environmentVariable("PYTHON").orNull
        val pythonPropertyOverride = providers.gradleProperty("researchProxyPython").orNull
        // Repo venvs are the common local-dev case (PyInstaller installed there);
        // `PYTHON` / `-PresearchProxyPython` always win. Windows venvs put the
        // interpreter under `Scripts/python.exe` instead of `bin/python`.
        val repoRootDir = layout.projectDirectory.dir("..").asFile
        val venvInterpreterPath =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "Scripts/python.exe"
            } else {
                "bin/python"
            }
        val venvPythonCandidates =
            listOf(
                File(repoRootDir, ".venv/$venvInterpreterPath"),
                File(repoRootDir, "code4me2-server/.venv/$venvInterpreterPath"),
                File(repoRootDir, "code4me2/.venv/$venvInterpreterPath"),
            ).map { it.absolutePath }
        doLast {
            // PyInstaller is host-only. When CI passes the matrix platform, make a
            // runner/arch mismatch fail here instead of producing a mislabeled bundle.
            if (platformsOption != null) {
                val tokens = platformsOption.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (tokens.size != 1 || tokens.single() != hostPlatformId) {
                    throw GradleException(
                        "PyInstaller cannot cross-compile: -PresearchProxyPlatforms=$platformsOption " +
                            "does not match this build host $hostPlatformId.",
                    )
                }
            }

            fun runCommand(
                command: List<String>,
                workingDirectory: File,
            ): Pair<Int, String> {
                val process =
                    ProcessBuilder(command)
                        .directory(workingDirectory)
                        .redirectErrorStream(true)
                        .start()
                val output = process.inputStream.bufferedReader().readText()
                return process.waitFor() to output
            }

            fun works(candidate: String): Boolean =
                try {
                    runCommand(listOf(candidate, "-c", "import sys"), proxyDirFile).first == 0
                } catch (_: Exception) {
                    false
                }

            val python =
                listOfNotNull(
                    pythonOverride,
                    pythonPropertyOverride,
                    *venvPythonCandidates.toTypedArray(),
                    "python3",
                    "python",
                ).firstOrNull { works(it) }
                    ?: throw GradleException(
                        "No usable Python interpreter found. Set PYTHON=/absolute/path/to/python, " +
                            "pass -PresearchProxyPython=/absolute/path/to/python, or install " +
                            "Python 3.10+, then re-run ./gradlew buildResearchProxyBundle.",
                    )
            logger.lifecycle("Research proxy builder using Python interpreter: $python")
            val (pyinstallerCode, pyinstallerOutput) =
                runCommand(listOf(python, "-c", "import PyInstaller"), proxyDirFile)
            if (pyinstallerCode != 0) {
                throw GradleException(
                    "PyInstaller is not installed for $python. Install it with:\n" +
                        "  $python -m pip install pyinstaller\n" +
                        "then re-run ./gradlew buildResearchProxyBundle.\n" +
                        pyinstallerOutput.trim(),
                )
            }
            if (!File(serverSrcFile, "research").isDirectory) {
                throw GradleException(
                    "Shared research contracts were not found at $serverSrcFile; " +
                        "check out code4me2-server next to code4me2 before bundling the proxy.",
                )
            }

            val workDist = File(buildDirFile, "dist")
            val workBuild = File(buildDirFile, "build")
            val workSpec = File(buildDirFile, "spec")
            workDist.deleteRecursively()
            workBuild.deleteRecursively()
            workSpec.deleteRecursively()
            workDist.mkdirs()
            workBuild.mkdirs()
            workSpec.mkdirs()

            val command =
                listOf(
                    python,
                    "-m",
                    "PyInstaller",
                    "--noconfirm",
                    "--clean",
                    "--onedir",
                    "--name",
                    "telemetry-acp-proxy",
                    "--distpath",
                    workDist.absolutePath,
                    "--workpath",
                    workBuild.absolutePath,
                    "--specpath",
                    workSpec.absolutePath,
                    "--paths",
                    serverSrcFile.absolutePath,
                    "--collect-submodules",
                    "research",
                    "packaging/pyinstaller_entry.py",
                )
            val (code, output) = runCommand(command, proxyDirFile)
            if (code != 0) {
                throw GradleException("PyInstaller failed with exit code $code:\n$output")
            }
            val built = File(workDist, "telemetry-acp-proxy")
            if (!built.isDirectory) throw GradleException("PyInstaller did not produce $built")
            val target = File(distRootFile, "$targetOs-$targetArch")
            if (target.exists()) target.deleteRecursively()
            target.mkdirs()
            built.copyRecursively(target, overwrite = true)
            val pluginRoot = proxyDirFile.parentFile
            val serverRoot = serverSrcFile.parentFile
            fun revision(root: File): String {
                val (status, text) = runCommand(listOf("git", "rev-parse", "HEAD"), root)
                require(status == 0) { "Cannot identify proxy source revision" }
                return text.trim()
            }
            fun dirty(root: File): Boolean {
                val (status, text) = runCommand(
                    listOf("git", "status", "--porcelain", "--untracked-files=normal", "--", "src", "telemetry-acp-proxy", "build.gradle.kts"), root,
                )
                require(status == 0) { "Cannot identify proxy source changes" }
                return text.isNotBlank()
            }
            val contractFiles = sortedMapOf<String, String>()
            for ((prefix, directory) in listOf("server/research" to File(serverSrcFile, "research"),
                "proxy" to File(proxyDirFile, "telemetry_acp_proxy"))) {
                directory.walkTopDown().filter { it.isFile && it.extension == "py" }.forEach { source ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
                    contractFiles[prefix + "/" + source.relativeTo(directory).invariantSeparatorsPath] =
                        digest.joinToString("") { "%02x".format(it) }
                }
            }
            val contractDigest = MessageDigest.getInstance("SHA-256")
                .digest(JsonOutput.toJson(contractFiles).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            File(target, "participant-provenance.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(
                linkedMapOf("schema_version" to "1", "plugin_commit" to revision(pluginRoot),
                    "server_commit" to revision(serverRoot), "source_dirty" to (dirty(pluginRoot) || dirty(serverRoot)),
                    "contract_digest" to contractDigest, "contract_files" to contractFiles),
            )))
            logger.lifecycle("Research proxy bundle staged at $target")
        }
    }

tasks.named<ProcessResources>("processResources") {
    dependsOn(stageResearchProxy)
    from(researchRuntimeStagingDir) { into("research-runtime") }
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
                // The harness supplies a private home/config/system directory.
                // No test touches the developer's ACP registry or saved accounts.
                val e2eHome = providers.environmentVariable("CODE4ME_UI_HOME").orNull
                if (e2eHome != null) {
                    systemProperty("user.home", e2eHome)
                    sandboxConfigDirectory.set(file("$e2eHome/config"))
                    sandboxSystemDirectory.set(file("$e2eHome/system"))
                    sandboxLogDirectory.set(file("$e2eHome/log"))
                    systemProperty("idea.trust.all.projects", "true")
                }
                jvmArgumentProviders +=
                    CommandLineArgumentProvider {
                        listOf(
                            "-Drobot-server.port=${providers.environmentVariable("CODE4ME_UI_ROBOT_PORT").getOrElse("8082")}",
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
