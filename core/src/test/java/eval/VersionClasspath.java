package eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.gijsm.vibemod.compile.CompileResult;
import com.gijsm.vibemod.compile.InMemoryCompiler;
import com.gijsm.vibemod.platform.ClasspathProvider;
import com.gijsm.vibemod.platform.CompilerProvider;

/**
 * The compile classpath a generated mod would see on ONE specific Paper
 * version.
 *
 * <p>The test runtime classpath already has everything a generated mod imports
 * — the sdk, Adventure, Gson, the JDK — plus <em>a</em> paper-api, whichever
 * version {@code core/build.gradle.kts} pins. That pinned one is exactly the
 * thing an eval across versions must not use, so this provider drops every
 * entry whose filename contains {@code paper-api} and swaps in the cached jar
 * for the version under test. Everything else is kept, because a generated mod
 * legitimately compiles against Adventure ({@code Component}) and the sdk
 * ({@code VibeContext}) as well as Bukkit.
 *
 * <p>Whether the paper-api jar ALONE would do is an empirical question, not a
 * design opinion — see {@link #reportComposition}, which answers it by
 * compiling.
 */
public final class VersionClasspath implements ClasspathProvider {

    private final Path apiJar;
    private final Path jarsDir;
    private final boolean jarOnly;
    private final String version;

    public VersionClasspath(Path jarsDir, String version) {
        this(jarsDir, version, false);
    }

    private VersionClasspath(Path jarsDir, String version, boolean jarOnly) {
        this.version = version;
        this.jarsDir = jarsDir;
        this.apiJar = EvalFacts.jar(jarsDir, version);
        this.jarOnly = jarOnly;
        if (!Files.isReadable(apiJar)) {
            throw new IllegalArgumentException("no cached paper-api jar for " + version + " at " + apiJar);
        }
    }

    /** The same version's jar and NOTHING else — the control arm of the probe. */
    public static VersionClasspath jarOnly(Path jarsDir, String version) {
        return new VersionClasspath(jarsDir, version, true);
    }

    public String version() {
        return version;
    }

    @Override
    public List<Path> compileClasspath() {
        List<Path> out = new ArrayList<>();
        List<Path> adventure = adventureOverride(jarsDir, version);
        if (!jarOnly) {
            for (Path p : hostEntriesWithoutPaperApi()) {
                // When this version pins its own Adventure, the host's copy must
                // go: two Adventures on one classpath resolves to whichever comes
                // first, which is the bug this override exists to fix.
                if (!adventure.isEmpty() && isAdventureArtifact(p)) {
                    continue;
                }
                out.add(p);
            }
        }
        out.addAll(adventure);
        out.add(apiJar);
        return out;
    }

    /**
     * The Adventure jars this paper-api version was actually compiled against,
     * or empty when the host's own Adventure is good enough.
     *
     * <p><strong>Why this exists.</strong> ARCHITECTURE-V2 decision #7 is "the
     * live game is the compile classpath - never ship or pin API jars for
     * generated code". An offline eval cannot honour that: there is no live
     * game, so it reconstructs a stand-in classpath. Reconstructing it wrongly
     * is precisely the failure that decision prevents, and it happened here.
     * Measured from each paper-api POM:
     *
     * <pre>
     *   1.21.1  needs adventure 4.17.0    host supplies 4.24.0  ok (newer)
     *   1.21.4  needs adventure 4.20.0    host supplies 4.24.0  ok (newer)
     *   1.21.8  needs adventure 4.24.0    host supplies 4.24.0  exact
     *   26.1.1  needs adventure 4.26.1    host supplies 4.24.0  TOO OLD
     *   26.2    needs adventure 5.2.0     host supplies 4.24.0  TOO OLD (major)
     * </pre>
     *
     * <p>On 26.2 that gap made {@code paper-api}'s own signatures reference
     * {@code net.kyori.adventure.text.object.PlayerHeadObjectContents}, which
     * 4.24.0 does not have, so javac answered "cannot access org.bukkit.Material"
     * for EVERY generated source. All 50 of the 26.2 generations scored as
     * compile failures for a reason that had nothing to do with the model - a
     * real Paper 26.2 server compiles the same code fine, because it supplies
     * the matching Adventure.
     *
     * <p>Populated by {@code scripts}-side tooling into
     * {@code paper/api-jars/adventure/<version>/}; absent directory means "the
     * host's Adventure is fine", which is true for every 1.21.x version.
     */
    static List<Path> adventureOverride(Path jarsDir, String version) {
        Path dir = jarsDir.resolve("adventure").resolve(version);
        if (!java.nio.file.Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        try (var stream = java.nio.file.Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read the Adventure override at " + dir, e);
        }
        return jars;
    }

    /** An Adventure (or its examination/option transitive) jar on the host classpath. */
    private static boolean isAdventureArtifact(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        return name.startsWith("adventure-") || name.startsWith("examination-")
                || name.startsWith("option-");
    }

    /** Every {@code java.class.path} entry except the pinned paper-api jar. */
    public static List<Path> hostEntriesWithoutPaperApi() {
        List<Path> kept = new ArrayList<>();
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path p = Path.of(entry);
            String name = p.getFileName() == null ? entry : p.getFileName().toString();
            if (name.contains("paper-api")) {
                continue;
            }
            kept.add(p);
        }
        return kept;
    }

    /** Every {@code java.class.path} entry that WAS dropped, for the audit trail. */
    public static List<Path> droppedPaperApiEntries() {
        List<Path> dropped = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path p = Path.of(entry);
            String name = p.getFileName() == null ? entry : p.getFileName().toString();
            if (name.contains("paper-api")) {
                dropped.add(p);
            }
        }
        return dropped;
    }

    /**
     * Prints what this classpath is made of, and answers empirically whether the
     * paper-api jar alone suffices: the same known-good sources are compiled
     * twice, once with the jar only and once with the jar plus the kept host
     * entries. Whichever arm goes green is the honest answer.
     */
    public static void reportComposition(Path jarsDir, String version, Map<String, String> probeSources) {
        System.out.println();
        System.out.println("=== VersionClasspath composition (target " + version + ") ===");
        List<Path> dropped = droppedPaperApiEntries();
        System.out.println("  dropped (paper-api on the host classpath):");
        if (dropped.isEmpty()) {
            System.out.println("    (none)");
        }
        for (Path p : dropped) {
            System.out.println("    - " + p);
        }
        System.out.println("  swapped in: " + EvalFacts.jar(jarsDir, version));
        List<Path> kept = hostEntriesWithoutPaperApi();
        System.out.println("  kept " + kept.size() + " host entries:");
        for (Path p : kept) {
            System.out.println("    + " + p.getFileName() + "   (" + p.getParent() + ")");
        }

        if (probeSources == null || probeSources.isEmpty()) {
            System.out.println("  (no probe sources supplied - jar-alone question not answered)");
            System.out.println();
            return;
        }

        CompilerProvider provider = CompilerProvider.resolve().orElse(null);
        if (provider == null) {
            System.out.println("  (no compiler on this JVM - jar-alone question not answered)");
            System.out.println();
            return;
        }

        CompileResult jarOnlyResult = new InMemoryCompiler(provider, jarOnly(jarsDir, version))
                .compile(probeSources);
        CompileResult fullResult = new InMemoryCompiler(provider, new VersionClasspath(jarsDir, version))
                .compile(probeSources);

        System.out.println("  probe: " + probeSources.size() + " known-good corpus source(s)");
        System.out.println("    paper-api jar ALONE          : " + (jarOnlyResult.success() ? "GREEN" : "RED"));
        System.out.println("    jar + kept host entries      : " + (fullResult.success() ? "GREEN" : "RED"));
        if (!jarOnlyResult.success()) {
            System.out.println("    -> the jar alone is NOT enough. First diagnostics:");
            firstLines(jarOnlyResult.diagnostics(), 12).forEach(l -> System.out.println("       " + l));
        } else {
            System.out.println("    -> the jar alone WOULD suffice for this probe; the harness still keeps");
            System.out.println("       the host entries, since other corpus mods import the sdk and Adventure.");
        }
        System.out.println();
    }

    static List<String> firstLines(String text, int n) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            if (out.size() >= n) {
                out.add("... (truncated)");
                break;
            }
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return out;
    }
}
