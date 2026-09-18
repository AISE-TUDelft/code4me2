// Real-IDE UI navigation suite (robot-server client).
//
// This module does NOT boot an IDE. It is a pure JVM/JUnit 5 client that talks
// to the robot-server shipped by the `:runIdeForUiTests` task
// (`-Drobot-server.port=8082`). The e2e harness owns the IDE lifecycle:
//
//   cd code4me2 && ./gradlew :runIdeForUiTests   # boots the sandbox IDE
//   ./gradlew :ui-tests:test --rerun             # drives it through robot-server
//
// `python3 -m code4me_e2e ui-test` orchestrates both and records the result.
//
// The suite is inert unless CODE4ME_UI_TEST=1 (class-level
// @EnabledIfEnvironmentVariable), so a plain `./gradlew test` neither needs a
// running IDE nor fails.

import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("java")
    alias(libs.plugins.kotlin)
}

group = "me.code4me"
version = "1.0-SNAPSHOT"

// Same JVM toolchain as the root project (IntelliJ Platform 2026.2+ runs on JBR 25).
kotlin {
    jvmToolchain(25)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
    }
}

repositories {
    mavenCentral()

    // JetBrains publishes the robot client (`com.intellij.remoterobot:remote-robot`)
    // and its fixtures (`remote-fixtures`) to the `intellij-dependencies`
    // repository, which is one of the repositories `intellijPlatform {
    // defaultRepositories() }` adds elsewhere in this build. It is added
    // explicitly here because this module deliberately does not apply the
    // IntelliJ Platform Gradle plugin.
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}

dependencies {
    // `kotlin.stdlib.default.dependency=false` is set in the root
    // gradle.properties, so the stdlib must be requested explicitly.
    implementation(kotlin("stdlib"))

    // The robot client. `remote-fixtures` brings the fixture classes
    // (CommonContainerFixture, JButtonFixture, JTreeFixture, Locators, …) and
    // depends on `remote-robot`; both are declared explicitly because the
    // dependency is declared with `runtime` scope upstream.
    testImplementation("com.intellij.remoterobot:remote-robot:0.11.23")
    testImplementation("com.intellij.remoterobot:remote-fixtures:0.11.23")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // The suite is re-run against a live IDE; never let Gradle serve a cached result.
    outputs.upToDateWhen { false }
    // Gson (inside the robot client) reflects over the JDK's Throwable/collections
    // when it deserializes a remote response; the strong encapsulation of
    // java.base otherwise makes that fail with InaccessibleObjectException.
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
