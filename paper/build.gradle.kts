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
}

dependencies {
    implementation(project(":core"))
    implementation(project(":platform-api"))
    implementation(project(":sdk"))
    implementation(project(":sdk-client"))

    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
}

// Everything on the runtime classpath is one of our own modules (Gson,
// Adventure and paper-api are all `compileOnly` / server-provided), so a plain
// merge of the runtime classpath reproduces the single-jar Maven layout.
val bundledModules: Configuration = configurations.runtimeClasspath.get()

tasks.jar {
    archiveFileName = "VibeMod.jar"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(bundledModules.elements.map { jars -> jars.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

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
tasks.runServer {
    minecraftVersion(modernRunVersion)
    useRunDirFor(modernRunVersion)
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
        pluginJars.from(tasks.jar.flatMap { it.archiveFile })
    }
}
