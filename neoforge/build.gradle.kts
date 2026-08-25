// neoforge: the NeoForge host (ARCHITECTURE-V2 §1, §9 Phase E). ONE jar that
// serves both a dedicated server and a client — `neoforge.mods.toml` declares a
// single mod and the client half is entered from a `Dist.CLIENT`-guarded
// listener rather than from a separate entrypoint the way Fabric does it.
//
// ModDevGradle (`net.neoforged.moddev`) is the build plugin. Under MC 26.x
// NeoForge runs official Mojang names, so — exactly as on Fabric — there is no
// mappings configuration, no remap task, and the plain `jar` IS the mod jar.
// The differences from `fabric/build.gradle.kts` worth knowing:
//
//   * Jar-in-Jar is the `jarJar` configuration, not Loom's `include`, and it IS
//     transitive: `jarJar(implementation(...))` resolves the graph itself, so
//     the artifact-walking dance the Fabric build needs for Adventure is not
//     required here. It does insist on a version RANGE for every nested module.
//   * `neoForge.mods` must name every source set whose classes belong to the
//     mod, or NeoForge's own module scanner will not see them at runtime.

plugins {
    id("net.neoforged.moddev") version "2.0.144"
}

// MC 26.x runs on Java 25 — this module leaves the repo-wide Java 21 toolchain
// for the same reason `fabric` does. core/platform-api/sdk-client stay at 21.
val neoJava = (property("neoforgeJavaVersion") as String).toInt()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(neoJava)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = neoJava
}

// ---------------------------------------------------------------------------
// The sdk MOD flavor (§4.1) — byte-identical inputs to Fabric's.
//
// `sdk/src/mod/java` holds the Mojang-typed VibeContext/ModCommandHandler/
// TaskHandle; `Mod.java` and the deprecated `VibeMod` bridge are shared verbatim
// with the Paper flavor and are copied in rather than duplicated (both flavors
// put VibeContext at the same relative path, so an unfiltered srcDir on
// `sdk/src/main/java` would drag the Bukkit one in too).
//
// This is what makes the mod flavor loader-NEUTRAL: nothing under `sdk/src/mod`
// names a Fabric or a NeoForge type, so generated code written for one loader
// compiles unchanged on the other.
// ---------------------------------------------------------------------------

val sdkModSources = rootProject.layout.projectDirectory.dir("sdk/src/mod/java")
val sharedSdkSources: Provider<Directory> = layout.buildDirectory.dir("generated/sources/sharedSdk/java")

val copySharedSdkSources = tasks.register<Copy>("copySharedSdkSources") {
    group = "build"
    description = "Copies the flavor-independent sdk sources (Mod, VibeMod) next to the mod flavor."
    from(rootProject.layout.projectDirectory.dir("sdk/src/main/java")) {
        include("com/gijsm/vibemod/api/Mod.java")
        include("com/gijsm/vibemod/api/VibeMod.java")
    }
    into(sharedSdkSources)
}

sourceSets.main {
    java.srcDir(sdkModSources)
    java.srcDir(sharedSdkSources)
}

tasks.named("compileJava") { dependsOn(copySharedSdkSources) }

// ---------------------------------------------------------------------------
// NeoForge
// ---------------------------------------------------------------------------

neoForge {
    version = property("neoforgeVersion") as String

    runs {
        register("client") {
            client()
            gameDirectory = layout.projectDirectory.dir("run/client").asFile
        }
        register("server") {
            server()
            gameDirectory = layout.projectDirectory.dir("run/server").asFile
        }
    }

    mods {
        register("vibemod") {
            sourceSet(sourceSets.main.get())
        }
    }
}

// ---------------------------------------------------------------------------
// Dependencies
// ---------------------------------------------------------------------------

dependencies {
    // Our own modules. Bundled into the jar wholesale (see `jar` below) rather
    // than nested, because generated mod code must link against the sdk and
    // `core` must live on the mod's own class loader.
    implementation(project(":core"))
    implementation(project(":platform-api"))
    implementation(project(":sdk-client"))

    // Adventure at EXACTLY the version Paper provides, so the `core` classes
    // bundled below run against the same bytes on every platform.
    //
    // NOT adventure-platform-neoforge, deliberately — §10.3 answered the §8.5
    // question on Fabric and all three findings apply here unchanged: no
    // Audience#showDialog implementation at all, an Adventure 5.x bump that
    // would break `core`'s 4.24 baseline, and per-MC builds pinned to different
    // Adventure majors. NeoForgeAudience/NeoForgeText implement the four
    // Audience methods `core` actually uses over vanilla instead.
    implementation("net.kyori:adventure-api:${property("adventureVersion")}")
    implementation("net.kyori:adventure-key:${property("adventureVersion")}")
    implementation("net.kyori:adventure-text-serializer-gson:${property("adventureVersion")}")
    implementation("net.kyori:adventure-text-serializer-plain:${property("adventureVersion")}")

    // Gson: NOT nested, same as Fabric. Minecraft depends on it and NeoForge
    // puts game libraries on the mod class loader.
    compileOnly("com.google.code.gson:gson:${property("gsonVersion")}")

    // ECJ: the compiler backend of last resort (§7.3). NEVER relocated — the
    // reflective FQCN `org.eclipse.jdt.internal.compiler.tool.EclipseCompiler`
    // and the META-INF/services entry are both load-bearing.
    implementation("org.eclipse.jdt:ecj:${property("ecjVersion")}")
}

/**
 * Prints the resolved compile classpath, one `:`-joined line.
 *
 * <p>The NeoForge twin of `:fabric:printCp`, and kept for the same reason: in
 * the unobfuscated era the game jar MDG resolves IS the documentation, and
 * `javap -cp "$(./gradlew -q :neoforge:printCp | tail -1)" net.neoforged.<X>`
 * is how every NeoForge signature in this module was verified rather than
 * recalled.
 */
tasks.register("printCp") {
    group = "help"
    description = "Prints the resolved compile classpath (for javap-ing Mojang/NeoForge signatures)."
    val cp = configurations.compileClasspath
    doLast { println(cp.get().files.joinToString(":")) }
}
