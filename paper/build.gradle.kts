import xyz.jpenilla.runtask.task.AbstractRun

// paper: the Paper host. Everything Bukkit-typed lives here, plus the plugin
// bootstrap and plugin.yml. Produces the single shipped artifact, VibeMod.jar,
// which bundles core, platform-api, sdk and sdk-client so generated mods compile
// and link against the api straight out of the running plugin jar.

plugins {
    // Boots a real Paper server with the freshly built jar installed
    // (ARCHITECTURE-V2 §9 Phase C). One task per supported floor/ceiling so the
    // 1.20.6 chat-renderer path and the 1.21.8 dialog path are both one command
    // away.
    id("xyz.jpenilla.run-paper") version "3.1.0"

    // Shadow, for exactly one reason: bStats (ARCHITECTURE-V2 §9 Phase F) MUST
    // be relocated. Paper's PluginClassLoader delegates class lookups across
    // plugins, so an unrelocated `org.bstats` in two plugins is one shared class
    // with two conflicting configurations — which is why bStats' own
    // instructions make relocation a hard requirement rather than good manners.
    // Relocation is bytecode rewriting and the hand-rolled zipTree merge that
    // used to build this jar cannot do it.
    id("com.gradleup.shadow") version "9.6.1"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":platform-api"))
    implementation(project(":sdk"))
    implementation(project(":sdk-client"))

    // The only third-party code in the Paper jar. Bukkit-only by design: there
    // is no bStats client for Fabric or NeoForge, so the two loader hosts ship
    // no metrics at all (see ARCHITECTURE-V2 §10.5).
    implementation("org.bstats:bstats-bukkit:3.2.1")

    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
}

// ---------------------------------------------------------------------------
// The artifact
//
// `shadowJar` IS VibeMod.jar. It merges the runtime classpath — our four
// modules plus bStats — exactly as the previous hand-rolled zipTree merge did
// (Gson, Adventure and paper-api are all `compileOnly` / server-provided, so
// nothing else comes along), and additionally rewrites `org.bstats` into our
// own namespace.
//
// The plain `jar` keeps building, classified `thin`, because Gradle's Java
// plugin wires half the world to it and turning it off is more disruptive than
// letting it produce a small file nobody ships.
// ---------------------------------------------------------------------------

tasks.jar {
    archiveClassifier = "thin"
}

tasks.shadowJar {
    archiveFileName = "VibeMod.jar"
    relocate("org.bstats", "com.gijsm.vibemod.bstats")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// ---------------------------------------------------------------------------
// run-paper targets (ARCHITECTURE-V2 §9 Phase C)
//
// The three servers the acceptance gate names: the 1.20.6 floor (no dialog API,
// so the chat renderer is the whole UI), the 1.21.8 dialog baseline, and the
// newest 26.x line. Each gets its own run directory so worlds, configs and the
// stored mods of one version never leak into another.
// ---------------------------------------------------------------------------

// Resolved here, not inside the task closures: inside a task configuration block
// `property(...)` resolves against the task, not the project.
val legacyRunVersion = property("paperRunVersionLegacy") as String
val modernRunVersion = property("paperRunVersionModern") as String
val nextRunVersion = property("paperRunVersionNext") as String

/** Every run directory lives under `paper/run/`, which is git-ignored runtime state. */
fun AbstractRun.useRunDirFor(version: String) {
    runDirectory = project.layout.projectDirectory.dir("run/$version")
}

// `runServer` is the one run-paper creates for us; it points at the version the
// plugin is compiled against.
//
// Every one of these installs `shadowJar`, not `jar`. Since the shadow plugin
// arrived, `jar` is the thin, bStats-less, un-relocated artifact — running a dev
// server off it would be testing something we do not ship.
tasks.runServer {
    minecraftVersion(modernRunVersion)
    useRunDirFor(modernRunVersion)
    pluginJars.setFrom(tasks.shadowJar.flatMap { it.archiveFile })
}

listOf(
    legacyRunVersion to "the 1.20.6 floor: no dialog API, so the chat renderer is the whole UI",
    nextRunVersion to "the newest supported line",
).forEach { (version, why) ->
    tasks.register<xyz.jpenilla.runpaper.task.RunServer>("runServer${version.replace('.', '_')}") {
        group = "run paper"
        description = "Runs a Paper $version server with VibeMod installed ($why)."
        minecraftVersion(version)
        useRunDirFor(version)
        pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
    }
}
