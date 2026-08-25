// Root build for the VibeMod multi-module Gradle build (ARCHITECTURE-V2.md §1).
//
// Shared conventions only: every module is a plain java-library on the Java 21
// toolchain, UTF-8 everywhere, no -Werror (the dialog API is @Experimental and
// its warnings are accepted, see ARCHITECTURE.md "Ground rules").

plugins {
    base
}

group = "com.gijsm"
version = "1.0.0"

subprojects {
    apply(plugin = "java-library")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).encoding = "UTF-8"
    }

    // VibeMod has no test framework: the tests are plain `main()` classes wired
    // as `selfTest*` JavaExec tasks (ARCHITECTURE.md, "no -Werror / no new
    // dependencies"). Gradle's JUnit-driven `test` task has nothing to find, so
    // it stays inert and `check` depends on the self-tests instead.
    tasks.withType<Test>().configureEach {
        enabled = false
    }
}

/**
 * Aggregate: runs every module's self-tests. The self-tests are plain
 * `main()` classes (no JUnit), wired as JavaExec tasks in their own modules.
 */
tasks.register("selfTest") {
    group = "verification"
    description = "Runs all VibeMod self-tests (plain main() classes, no JUnit)."
    dependsOn(
        ":core:selfTestCompiler",
        ":core:selfTestLlm",
        ":core:selfTestStore",
        ":core:selfTestCatalog",
        ":paper:selfTestErrors",
    )
}
