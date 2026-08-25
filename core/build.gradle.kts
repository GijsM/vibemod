// core: the platform-free engine (ARCHITECTURE-V2 §1). JDK + Gson +
// Adventure + platform-api. Never org.bukkit / io.papermc / net.minecraft.
//
// Gson and Adventure are `compileOnly`: on Paper the server provides both
// (paper-api transitives, see ARCHITECTURE.md "No new dependencies"); on
// loaders they are Jar-in-Jar'd by the host module in Phases D/E.

dependencies {
    api(project(":platform-api"))

    compileOnlyApi("net.kyori:adventure-api:${property("adventureVersion")}")
    compileOnly("com.google.code.gson:gson:${property("gsonVersion")}")

    // ECJ: the compiler backend of last resort (ARCHITECTURE-V2 7.3). compileOnly,
    // and reached only reflectively / via ServiceLoader - so the Paper jar never
    // ships it (Paper servers run a JDK), while the loader hosts Jar-in-Jar it
    // unrelocated in Phases D/E. On the test runtime it IS present, so
    // `-Dvibemod.compiler=ecj` can force the ECJ path over the real corpus.
    compileOnly("org.eclipse.jdt:ecj:${property("ecjVersion")}")
    testRuntimeOnly("org.eclipse.jdt:ecj:${property("ecjVersion")}")

    // The self-tests drive InMemoryCompiler/JarExporter, which compile generated
    // mod sources against the live classpath: the sdk (the generated-code
    // contract) and paper-api (which those sources import) must be present at
    // RUNTIME for the tests - never at compile time for core itself.
    testImplementation("com.google.code.gson:gson:${property("gsonVersion")}")
    testRuntimeOnly(project(":sdk"))
    testRuntimeOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
}

// ---------------------------------------------------------------------------
// Build-time prompt sources (ARCHITECTURE-V2 §6.4)
//
// PromptLibrary used to carry hand-copied duplicates of the api sources; they
// are now read straight out of the sdk at build time so they cannot drift.
// LlmSelfTest still asserts the prompt matches the files on disk.
// ---------------------------------------------------------------------------

/** Renders one source file as a Java string-concatenation expression. */
fun javaStringExpression(text: String, indent: String): String {
    fun literal(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    val normalized = text.replace("\r\n", "\n")
    val rawLines = normalized.split("\n")
    val endsWithNewline = rawLines.isNotEmpty() && rawLines.last().isEmpty()
    val lines = if (endsWithNewline) rawLines.dropLast(1) else rawLines
    if (lines.isEmpty()) {
        return literal(normalized)
    }
    return lines.mapIndexed { index, line ->
        val withNewline = if (index < lines.size - 1 || endsWithNewline) line + "\n" else line
        literal(withNewline)
    }.joinToString("\n$indent+ ")
}

val sdkApiDir = project(":sdk").layout.projectDirectory.dir("src/main/java/com/gijsm/vibemod/api")
val sdkModApiDir = project(":sdk").layout.projectDirectory.dir("src/mod/java/com/gijsm/vibemod/api")
val sdkClientApiDir =
    project(":sdk-client").layout.projectDirectory.dir("src/main/java/com/gijsm/vibemod/api/client")

// The MOD_* constants are the loader flavor of the same contract (ARCHITECTURE-V2
// 4.1) - Mojang-typed, and shared verbatim by Fabric and NeoForge. Mod.java has
// one flavor and is not repeated. Emitting both flavors from their real sources
// is what keeps the fabric profile's api block from drifting the way the
// hand-copied Paper constants used to.
val promptSourceFiles = mapOf(
    "MOD" to sdkApiDir.file("Mod.java").asFile,
    "VIBE_CONTEXT" to sdkApiDir.file("VibeContext.java").asFile,
    "MOD_COMMAND_HANDLER" to sdkApiDir.file("ModCommandHandler.java").asFile,
    "CLIENT_CONTEXT" to sdkClientApiDir.file("ClientContext.java").asFile,
    "MOD_VIBE_CONTEXT" to sdkModApiDir.file("VibeContext.java").asFile,
    "MOD_MOD_COMMAND_HANDLER" to sdkModApiDir.file("ModCommandHandler.java").asFile,
    "MOD_TASK_HANDLE" to sdkModApiDir.file("TaskHandle.java").asFile,
    "HUD_CANVAS" to sdkClientApiDir.file("HudCanvas.java").asFile,
)

val generatedPromptSourcesDir: Provider<Directory> =
    layout.buildDirectory.dir("generated/sources/promptSources/java")

val generatePromptSources = tasks.register("generatePromptSources") {
    group = "build"
    description = "Generates com.gijsm.vibemod.llm.GeneratedApiSources from the sdk sources."

    inputs.files(promptSourceFiles.values).withPathSensitivity(PathSensitivity.NAME_ONLY)
    val outputDir = generatedPromptSourcesDir
    outputs.dir(outputDir)

    val files = promptSourceFiles
    doLast {
        val target = outputDir.get().dir("com/gijsm/vibemod/llm").asFile
        target.mkdirs()
        val body = StringBuilder()
        body.append("package com.gijsm.vibemod.llm;\n\n")
        body.append("/**\n")
        body.append(" * The sdk sources the system prompt embeds verbatim, generated at build time by the\n")
        body.append(" * {@code :core:generatePromptSources} task straight from the sdk modules\n")
        body.append(" * (ARCHITECTURE-V2 &sect;6.4). DO NOT EDIT - edit the sdk source instead.\n")
        body.append(" */\n")
        body.append("public final class GeneratedApiSources {\n\n")
        body.append("    private GeneratedApiSources() {\n    }\n")
        files.forEach { (constant, file) ->
            body.append("\n    /** Verbatim contents of {@code ").append(file.name).append("}. */\n")
            body.append("    public static final String ").append(constant).append(" =\n")
            body.append("            ")
            body.append(javaStringExpression(file.readText(Charsets.UTF_8), "            "))
            body.append(";\n")
        }
        body.append("}\n")
        File(target, "GeneratedApiSources.java").writeText(body.toString(), Charsets.UTF_8)
    }
}

sourceSets.main {
    java.srcDir(generatedPromptSourcesDir)
}

tasks.named("compileJava") { dependsOn(generatePromptSources) }

// ---------------------------------------------------------------------------
// Platform-freedom guard (ARCHITECTURE-V2 §1)
// ---------------------------------------------------------------------------

tasks.register("checkPlatformFree") {
    group = "verification"
    description = "Fails if core imports a platform-specific package."
    val sources = fileTree("src/main/java") { include("**/*.java") }
    val banned = listOf("org.bukkit", "io.papermc", "net.minecraft", "net.fabricmc", "net.neoforged")
    inputs.files(sources)
    outputs.upToDateWhen { false }
    doLast {
        val offenders = mutableListOf<String>()
        sources.forEach { file ->
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ") && banned.any { trimmed.startsWith("import $it") }) {
                    offenders += "${file.name}: $trimmed"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("core must stay platform-free; found:\n" + offenders.joinToString("\n"))
        }
    }
}

tasks.named("check") { dependsOn("checkPlatformFree") }

// ---------------------------------------------------------------------------
// Self-tests: plain main() classes, no JUnit (ARCHITECTURE-V2 §9 Phase B)
// ---------------------------------------------------------------------------

/**
 * Stored-mod corpus for StoreSelfTest's compile-the-whole-corpus check.
 * Defaults to this checkout's own server dir; override with
 * `-Pvibemod.modsDir=/path/to/server/plugins/VibeMod/mods` (needed in git
 * worktrees, where `server/` - runtime state - is not checked out). The check
 * is skipped, not failed, when the directory does not exist.
 */
val modsDir: String = (findProperty("vibemod.modsDir") as String?)
    ?: rootProject.layout.projectDirectory.dir("server/plugins/VibeMod/mods").asFile.absolutePath

fun registerSelfTest(name: String, main: String, configure: JavaExec.() -> Unit = {}) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        description = "Runs $main."
        classpath = sourceSets["test"].runtimeClasspath
        mainClass = main
        configure()
    }

registerSelfTest("selfTestCompiler", "CompilerSelfTest")

registerSelfTest("selfTestLlm", "LlmSelfTest") {
    // args[0]: the real api sources the prompt must match verbatim.
    args(sdkApiDir.asFile.absolutePath)
}

registerSelfTest("selfTestStore", "StoreSelfTest") {
    systemProperty("vibemod.mods.dir", modsDir)
}

registerSelfTest("selfTestCatalog", "com.gijsm.vibemod.llm.CatalogSelfTest")

registerSelfTest("selfTestErrors", "com.gijsm.vibemod.runtime.ErrorsSelfTest")

/**
 * The ECJ-forced runs (ARCHITECTURE-V2 7.3): the same two compile-heavy
 * self-tests, but with `CompilerProvider.resolve()` steered past the system
 * javac onto the Eclipse compiler. This is the only way to find out whether ECJ
 * can actually build the stored corpus through our own InMemoryFileManager
 * before a loader host depends on it.
 */
registerSelfTest("selfTestCompilerEcj", "CompilerSelfTest") {
    systemProperty("vibemod.compiler", "ecj")
}

registerSelfTest("selfTestStoreEcj", "StoreSelfTest") {
    systemProperty("vibemod.mods.dir", modsDir)
    systemProperty("vibemod.compiler", "ecj")
}

tasks.register("selfTestEcj") {
    group = "verification"
    description = "Runs the compile-heavy self-tests with the ECJ backend forced."
    dependsOn("selfTestCompilerEcj", "selfTestStoreEcj")
}

tasks.register("selfTest") {
    group = "verification"
    description = "Runs core's self-tests."
    dependsOn("selfTestCompiler", "selfTestLlm", "selfTestStore", "selfTestCatalog", "selfTestErrors")
}

tasks.named("check") { dependsOn("selfTest") }
