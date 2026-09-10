pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provision JDKs for jvmToolchain requests (e.g. Java 25 for 2026.2+)
    // when no matching local installation exists. CI benefits as well.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    versionCatalogs {
        create("clientLibs") { // Use a different name
            from(files("gradle/libs.versions.toml"))
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "client"

include(":generated")
include(":integration-tests")
