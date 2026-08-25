// paper: the Paper host. Everything Bukkit-typed lives here, plus the plugin
// bootstrap and plugin.yml. Produces the single shipped artifact, VibeMod.jar
// (the Maven build's `<finalName>VibeMod</finalName>`), which bundles core,
// platform-api, sdk and sdk-client so generated mods compile and link against
// the api straight out of the running plugin jar.

dependencies {
    implementation(project(":core"))
    implementation(project(":platform-api"))
    implementation(project(":sdk"))
    implementation(project(":sdk-client"))

    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")

    // ErrorsSelfTest drives the Bukkit-typed ModErrors.
    testImplementation("io.papermc.paper:paper-api:${property("paperApiVersion")}")
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
// Self-tests: plain main() classes, no JUnit (ARCHITECTURE-V2 §9 Phase B)
// ---------------------------------------------------------------------------

tasks.register<JavaExec>("selfTestErrors") {
    group = "verification"
    description = "Runs com.gijsm.vibemod.runtime.ErrorsSelfTest."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.gijsm.vibemod.runtime.ErrorsSelfTest"
}

tasks.register("selfTest") {
    group = "verification"
    description = "Runs paper's self-tests."
    dependsOn("selfTestErrors")
}

tasks.named("check") { dependsOn("selfTest") }
