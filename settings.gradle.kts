plugins {
    // Lets Gradle fetch the Java 21 toolchain when the machine has none, so the
    // build compiles (and the self-tests run) at exactly Java 21 everywhere.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "vibemod"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

// Phase B modules (ARCHITECTURE-V2.md §1). `fabric` and `neoforge` are added in
// Phases D and E; they need Loom / ModDevGradle and are deliberately absent here.
include("sdk-client")
include("platform-api")
include("sdk")
include("core")
include("paper")
