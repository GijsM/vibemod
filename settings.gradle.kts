pluginManagement {
    repositories {
        // Loom lives on Fabric's own maven, not the plugin portal mirror.
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle fetch the Java 21 toolchain when the machine has none, so the
    // build compiles (and the self-tests run) at exactly Java 21 everywhere.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "vibemod"

dependencyResolutionManagement {
    // PREFER_PROJECT (the default) on purpose: Loom injects its own repositories
    // into the `fabric` project, and a FAIL_ON_PROJECT_REPOS policy would break it.
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
}

// ARCHITECTURE-V2.md §1. `neoforge` (ModDevGradle) joins in Phase E.
include("sdk-client")
include("platform-api")
include("sdk")
include("core")
include("paper")
include("fabric")
