// fabric: the Fabric host (ARCHITECTURE-V2 §1, §9 Phase D). ONE jar that serves
// both a dedicated server and a client — `fabric.mod.json` names a `main` and a
// `client` entrypoint and the client half is loaded only where it exists.
//
// Two things about this build are era-specific and easy to get wrong:
//
//   * The plugin id is `net.fabricmc.fabric-loom`, NOT `fabric-loom`. Since Loom
//     1.14 that id means "the game is unobfuscated" — which is true from MC 26.1.
//     Under it Loom registers no `mappings` configuration, no `mod*` remapping
//     configurations and no `remapJar` task: a `mappings loom.officialMojangMappings()`
//     line fails at configuration time, `modImplementation` does not exist, and the
//     shipped artifact is the plain `jar`.
//   * `include(...)` therefore nests into `jar`. That is how ECJ, Gson and the
//     Adventure jars ride along (§7.3: Jar-in-Jar, never relocated — relocation
//     would break ECJ's reflective FQCN and its ServiceLoader entry).

plugins {
    id("net.fabricmc.fabric-loom") version "1.17.19"
}

// MC 26.x runs on Java 25. This module therefore leaves the repo-wide Java 21
// toolchain the root build sets for everything else; core/platform-api/sdk-client
// stay at 21 and load fine here (a newer JVM reads older class files).
val fabricJava = (property("fabricJavaVersion") as String).toInt()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(fabricJava)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = fabricJava
}

// ---------------------------------------------------------------------------
// The sdk MOD flavor (§4.1)
//
// `sdk/src/mod/java` holds the Mojang-typed VibeContext/ModCommandHandler/
// TaskHandle; `Mod.java` and the deprecated `VibeMod` bridge are shared verbatim
// with the Paper flavor and are copied in rather than duplicated. They cannot
// simply be another srcDir: both flavors put VibeContext at the same relative
// path, so an unfiltered srcDir on `sdk/src/main/java` would pull the Bukkit one
// in too. NeoForge will consume exactly these two directories in Phase E.
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

// ---------------------------------------------------------------------------
// loader-common (§10.4): the Mojang-typed host code Fabric and NeoForge share.
//
// A shared SOURCE directory, not a Gradle module. Both loaders run official
// Mojang names on 26.1+, so ~2700 lines of this host — the dialog renderer, the
// mod host, the command bridge, the Adventure/vanilla text and audience
// adapters, the whole client surface below the loader's own hooks — name no
// loader type at all and are byte-for-byte the same work twice.
//
// It is not a module because a module needs a plugin, and the two candidates
// are Loom and ModDevGradle: whichever one it applied, the OTHER loader would
// then compile its host against a game jar produced by its rival's toolchain.
// As a source directory each loader compiles the shared code against ITS own
// patched game jar, so a NeoForge patch that changes a vanilla signature is a
// compile error in `neoforge` rather than a runtime surprise on a user's
// server. The sdk mod flavor above is wired exactly the same way, for the same
// reason.
// ---------------------------------------------------------------------------
val loaderCommonSources = rootProject.layout.projectDirectory.dir("loader-common/src/main/java")

// The same directory carries the third-party notices both loader jars must
// ship: ECJ is EPL-2.0 (§7.3) and a redistributed EPL binary has to travel with
// its licence. `neoforge.mods.toml` and `fabric.mod.json` both point at
// META-INF/licenses/, so this is the file that makes those lines true.
val loaderCommonResources = rootProject.layout.projectDirectory.dir("loader-common/src/main/resources")

sourceSets.main {
    java.srcDir(sdkModSources)
    java.srcDir(sharedSdkSources)
    java.srcDir(loaderCommonSources)
    resources.srcDir(loaderCommonResources)
}

tasks.named("compileJava") { dependsOn(copySharedSdkSources) }

// ---------------------------------------------------------------------------
// Dependencies
// ---------------------------------------------------------------------------

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraftVersion")}")
    // No `mappings` line: 26.1+ ships official names, and under the no-remap
    // plugin id the `mappings` configuration does not exist at all.

    implementation("net.fabricmc:fabric-loader:${property("fabricLoaderVersion")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabricApiVersion")}")

    // Our own modules. They are bundled into the jar wholesale (see `jar` below)
    // rather than nested, because generated mod code must link against the sdk
    // and core must be on the mod's own class loader.
    implementation(project(":core"))
    implementation(project(":platform-api"))
    implementation(project(":sdk-client"))

    // Adventure is the project's text currency and no loader provides it, so it
    // is nested (§1) — at EXACTLY the version Paper provides, so the `core`
    // classes bundled below run against the same bytes they were compiled
    // against on every platform.
    //
    // NOT adventure-platform-mod, deliberately (§8.5's "check first" — see §10.3).
    // Three findings killed it: it does not implement Audience#showDialog at all
    // (a full-tree grep for "dialog" is empty; Adventure's own DialogLike is an
    // empty marker interface), so it buys nothing for the one thing §8.5 wanted
    // it for; its MC 26.2 build pulls Adventure 5.2.0, a major version above the
    // 4.24.0 the rest of this project compiles against; and it publishes per-MC
    // builds pinned to different Adventure majors (7.1.1 = 26.2 + Adventure 5,
    // 6.9.0 = 26.1.x + Adventure 4.26), so honouring the "MC 26.1+" floor with it
    // would mean two Adventure majors in one product. FabricMessenger implements
    // the four Audience methods core actually uses over vanilla instead.
    implementation("net.kyori:adventure-api:${property("adventureVersion")}")
    implementation("net.kyori:adventure-key:${property("adventureVersion")}")
    // Component -> JSON -> net.minecraft.network.chat.Component is how Adventure
    // text reaches the game (ComponentSerialization.CODEC on the other side).
    implementation("net.kyori:adventure-text-serializer-gson:${property("adventureVersion")}")
    implementation("net.kyori:adventure-text-serializer-plain:${property("adventureVersion")}")
    // The nesting itself is done below, transitively: `include` takes exactly the
    // artifact it is handed, and adventure-api alone would leave `examination-api`
    // (which every Component implements) unnested and the mod dead on first use.

    // Gson: NOT nested. §1 assumed the loaders would have to ship it; they do
    // not — Minecraft itself depends on Gson (2.14.0 wins the conflict with our
    // 2.11.0 pin) and Fabric puts game libraries on the same class loader as
    // mods. Nesting a second copy would be a duplicate for no reason.
    implementation("com.google.code.gson:gson:${property("gsonVersion")}")

    // ECJ: the compiler backend of last resort, so a JRE-only install can still
    // compile generated mods (§7.3). NEVER relocated - the reflective FQCN
    // `org.eclipse.jdt.internal.compiler.tool.EclipseCompiler` and the
    // META-INF/services entry are both load-bearing.
    implementation("org.eclipse.jdt:ecj:${property("ecjVersion")}")
    include("org.eclipse.jdt:ecj:${property("ecjVersion")}")
}

/**
 * Nests Adventure and everything it drags in.
 *
 * <p>Loom's `include` nests exactly the artifact it is handed and nothing
 * transitive. Adventure is a five-jar graph — `adventure-api` implements
 * `Examinable` from `examination-api` on every Component, and the gson
 * serializer pulls `adventure-text-serializer-json` and `option` — so nesting
 * only the four we name would produce a mod that loads, boots, and dies with a
 * NoClassDefFoundError the first time it builds a message. Resolving the graph
 * and nesting each file is the version of this that cannot rot: a new Adventure
 * transitive arrives already handled.
 *
 * <p>Gson is excluded on purpose: the game already has it (see above).
 */
val adventureBundle: Configuration = configurations.detachedConfiguration(
    dependencies.create("net.kyori:adventure-api:${property("adventureVersion")}"),
    dependencies.create("net.kyori:adventure-text-serializer-gson:${property("adventureVersion")}"),
    dependencies.create("net.kyori:adventure-text-serializer-plain:${property("adventureVersion")}"),
).apply {
    exclude(group = "com.google.code.gson")
}

dependencies {
    // By coordinates, not by file: Loom refuses to nest an artifact that is not a
    // module component ("has no capabilities"), because a nested jar needs a
    // group:name:version to synthesize its fabric.mod.json from.
    // Driven off the resolved ARTIFACTS, not the resolution graph: the graph also
    // contains `adventure-bom`, which is a platform with no jar at all, and
    // asking Loom to nest it fails the build with a variant-matching error.
    adventureBundle.incoming.artifacts.artifacts.forEach { artifact ->
        val id = artifact.id.componentIdentifier
        if (id is ModuleComponentIdentifier) {
            include("${id.group}:${id.module}:${id.version}")
        }
    }
}

// ---------------------------------------------------------------------------
// Client game tests (ARCHITECTURE-V2 §9 Phase D: the CLIENT half of the gate)
//
// fabric-client-gametest-api drives a REAL client: it boots the game, creates a
// singleplayer world, and runs assertions inside it. That is the only way to
// prove the §8 client surface for real — a HUD element that is never asked to
// draw has not been shown to survive drawing.
//
// The api is `devOnlyModules` in fabric-api, so it is not in the fat jar; it
// arrives transitively on the dev classpath and is named explicitly here so the
// dependency is visible rather than inherited by luck.
// ---------------------------------------------------------------------------

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "vibemod-clientgametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
        username = "VibeModGate"
    }
}

// ---------------------------------------------------------------------------
// The artifact
// ---------------------------------------------------------------------------

// `include` handles the nested jars; this merges our own modules flat, the same
// way the Paper jar does, so the sdk classes generated code compiles against are
// visible on the mod's class loader without a nested-jar lookup.
val bundledModules: Configuration = configurations.detachedConfiguration(
    dependencies.project(":core"),
    dependencies.project(":platform-api"),
    dependencies.project(":sdk-client"),
)

tasks.jar {
    archiveBaseName = "vibemod-fabric"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(bundledModules.elements.map { jars -> jars.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

// ---------------------------------------------------------------------------
// The surgeon self-test (V3 Phase 0 §F.1)
//
// Its own source set, and it has to be, because it needs three things at once
// that no existing one has together: Java 25 (java.lang.classfile), the
// loader-common sources (the surgeon itself), and the real Fabric API on the
// compile classpath — so a fixture can contain a GENUINE `Event.register` call
// site rather than an imitation of one.
//
// `main`'s compile classpath is handed on verbatim rather than re-declared: the
// point of the test is that the surgeon behaves on bytecode compiled against
// exactly what the host compiles generated mods against, and re-declaring the
// dependencies would let the two drift.
//
// Not a `gametest`: this proves a pure function of bytes and needs no game.
// The game-side proof that the same rewrite survives a real client is
// VibeModClientGateTest's NativeCanary.
// ---------------------------------------------------------------------------
val surgeonTest: SourceSet = sourceSets.create("surgeonTest")

surgeonTest.compileClasspath =
    sourceSets.main.get().output + sourceSets.main.get().compileClasspath
surgeonTest.runtimeClasspath = surgeonTest.output + surgeonTest.compileClasspath

val surgeonSelfTest = tasks.register<JavaExec>("surgeonSelfTest") {
    group = "verification"
    description = "Checks the bytecode policy and the Event.register seam rewrite (V3 Phase 0)."
    mainClass = "SurgeonSelfTest"
    classpath = surgeonTest.runtimeClasspath
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(fabricJava)
    }
    // The classpath the FIXTURES compile against, passed as a property because
    // the test builds its own InMemoryCompiler exactly as a host would.
    val fixtureCp = sourceSets.main.get().output.classesDirs + sourceSets.main.get().compileClasspath
    doFirst {
        systemProperty("vibemod.surgeon.cp", fixtureCp.files.joinToString(File.pathSeparator))
    }
}

tasks.named("check") { dependsOn(surgeonSelfTest) }

/**
 * Prints the resolved compile classpath, one `:`-joined line.
 *
 * <p>Kept deliberately. In the unobfuscated era there is no mappings browser to
 * consult: the game jar Loom hands you IS the documentation, and
 * `javap -cp "$(./gradlew -q :fabric:printCp | tail -1)" net.minecraft.<X>` is
 * how every Mojang and Fabric signature in this module was verified rather than
 * remembered. Phase E will want the same for NeoForge.
 */
tasks.register("printCp") {
    group = "help"
    description = "Prints the resolved compile classpath (for javap-ing Mojang/Fabric signatures)."
    val cp = configurations.compileClasspath
    doLast { println(cp.get().files.joinToString(":")) }
}
