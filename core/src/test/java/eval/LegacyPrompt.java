package eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.gijsm.vibemod.compile.CompileResult;
import com.gijsm.vibemod.compile.InMemoryCompiler;
import com.gijsm.vibemod.compile.JvmClasspathProvider;
import com.gijsm.vibemod.platform.CompilerProvider;

/**
 * The PRE-REBUILD system prompt, reconstructed from git rather than retyped.
 *
 * <p>The vocabulary/facts rework landed in {@code 46f452c}; the state before it
 * is {@code 46f452c^}. There, {@code PromptLibrary.systemPrompt} took a
 * {@link Object} {@code PlatformProfile} and nothing else, so the prompt was a
 * pure function of the profile: exactly TWO distinct strings exist,
 * {@code paper-modern} and {@code paper-legacy}.
 *
 * <p>Reconstruction, in five steps:
 *
 * <ol>
 *   <li>{@code git show 46f452c^:...} the five prompt sources.
 *   <li>Rewrite their package to {@code legacyprompt} and re-import the one
 *       same-package symbol they reach for that is NOT among the five:
 *       {@code GeneratedApiSources} (build-generated, still present at HEAD).
 *   <li>Compile all five in memory against the live test classpath.
 *   <li>Load the bytes through a throwaway {@link ClassLoader}.
 *   <li>Reflectively render {@code systemPrompt(profile)} for both profiles.
 * </ol>
 *
 * <p>Retyping the old prompt would have been a 24,000-character transcription
 * with no way to prove it faithful. This way the "before" arm of the eval is
 * the literal bytes VibeMod used to send.
 */
public final class LegacyPrompt {

    /** The commit that rebuilt the prompt; the state we reconstruct is its parent. */
    public static final String REBUILD_COMMIT = "46f452c";

    private static final String LEGACY_PKG = "legacyprompt";

    private static final List<String> SOURCES = List.of(
            "PromptLibrary", "PlatformProfile", "PlatformProfiles", "PromptExamples", "LoaderExamples");

    /** Measured against the shipped jar at {@code 46f452c^}. Advisory, not a gate. */
    private static final int EXPECTED_MODERN_CHARS = 23_028;
    private static final int EXPECTED_LEGACY_CHARS = 24_031;

    private static final Map<String, String> RENDERED = new LinkedHashMap<>();

    private static Path repoRoot = Path.of(System.getProperty("user.dir"));
    private static Path outDir;
    private static boolean loaded;

    private LegacyPrompt() {
    }

    /**
     * Points the reconstruction at a checkout and an output directory. Must be
     * called before the first {@link #forVersion(String)} to have any effect.
     */
    public static void configure(Path root, Path out) {
        repoRoot = root;
        outDir = out;
    }

    /** The profile ids the legacy code could produce, in render order. */
    public static List<String> profileIds() {
        return List.of("paper-modern", "paper-legacy");
    }

    /**
     * The legacy system prompt VibeMod would have sent a server on this MC
     * version. Both strings are rendered once and cached.
     */
    public static String forVersion(String mcVersion) {
        return forProfile(profileIdFor(mcVersion));
    }

    /** The legacy prompt for a profile id directly. */
    public static String forProfile(String profileId) {
        ensureLoaded();
        String prompt = RENDERED.get(profileId);
        if (prompt == null) {
            throw new IllegalStateException("no legacy prompt rendered for profile " + profileId);
        }
        return prompt;
    }

    private static Method legacyProfileIdMethod;

    /**
     * The profile the LEGACY code would have picked — resolved through the old
     * {@code PlatformProfiles.paperProfileIdFor}, not HEAD's, so the before arm
     * cannot silently inherit a boundary change.
     */
    public static String profileIdFor(String mcVersion) {
        ensureLoaded();
        try {
            return (String) legacyProfileIdMethod.invoke(null, mcVersion);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("legacy paperProfileIdFor failed for " + mcVersion, e);
        }
    }

    // ------------------------------------------------------------------
    // Reconstruction
    // ------------------------------------------------------------------

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        try {
            load();
        } catch (Exception e) {
            throw new IllegalStateException("could not reconstruct the legacy prompt from git", e);
        }
        loaded = true;
    }

    private static void load() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String simple : SOURCES) {
            String original = gitShow(REBUILD_COMMIT + "^:core/src/main/java/com/gijsm/vibemod/llm/"
                    + simple + ".java");
            sources.put(LEGACY_PKG + "." + simple, rewritePackage(original));
        }

        CompilerProvider provider = CompilerProvider.resolve()
                .orElseThrow(() -> new IllegalStateException("no java compiler on this JVM"));
        InMemoryCompiler compiler = new InMemoryCompiler(provider, new JvmClasspathProvider());
        CompileResult result = compiler.compile(sources);
        if (!result.success()) {
            throw new IllegalStateException("the reconstructed legacy prompt sources did not compile:\n"
                    + result.diagnostics());
        }

        ClassLoader loader = new MapClassLoader(result.classes(), LegacyPrompt.class.getClassLoader());
        Class<?> profilesClass = loader.loadClass(LEGACY_PKG + ".PlatformProfiles");
        Class<?> profileClass = loader.loadClass(LEGACY_PKG + ".PlatformProfile");
        Class<?> libraryClass = loader.loadClass(LEGACY_PKG + ".PromptLibrary");

        legacyProfileIdMethod = profilesClass.getMethod("paperProfileIdFor", String.class);
        Method byId = profilesClass.getMethod("byId", String.class);
        Method systemPrompt = libraryClass.getMethod("systemPrompt", profileClass);

        for (String id : profileIds()) {
            Object profile = byId.invoke(null, id);
            String prompt = (String) systemPrompt.invoke(null, profile);
            RENDERED.put(id, prompt);
        }

        report();
    }

    /**
     * The only same-package symbol the five sources reach for that is not among
     * them is {@code GeneratedApiSources}. If a future rebase adds another, the
     * compile above fails loudly with the missing symbol rather than silently
     * rendering a truncated prompt.
     */
    private static String rewritePackage(String source) {
        return source.replaceFirst("(?m)^package com\\.gijsm\\.vibemod\\.llm;",
                "package " + LEGACY_PKG + ";\n\nimport com.gijsm.vibemod.llm.GeneratedApiSources;");
    }

    private static String gitShow(String spec) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "--no-pager", "show", spec);
        pb.directory(repoRoot.toFile());
        pb.redirectErrorStream(false);
        Process p = pb.start();
        byte[] out;
        try (InputStream in = p.getInputStream()) {
            out = readAll(in);
        }
        byte[] err;
        try (InputStream in = p.getErrorStream()) {
            err = readAll(in);
        }
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("git show timed out for " + spec);
        }
        if (p.exitValue() != 0) {
            throw new IOException("git show " + spec + " failed (" + p.exitValue() + "): "
                    + new String(err, StandardCharsets.UTF_8));
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static void report() {
        System.out.println();
        System.out.println("=== LegacyPrompt: reconstructed from " + REBUILD_COMMIT + "^ ===");
        for (Map.Entry<String, String> e : RENDERED.entrySet()) {
            int expected = "paper-modern".equals(e.getKey()) ? EXPECTED_MODERN_CHARS : EXPECTED_LEGACY_CHARS;
            int actual = e.getValue().length();
            int delta = actual - expected;
            System.out.printf(java.util.Locale.ROOT,
                    "  %-13s %,7d chars (expected ~%,d, delta %+d)%s%n",
                    e.getKey(), actual, expected, delta,
                    Math.abs(delta) > 200 ? "   <<< LARGER THAN EXPECTED DELTA - CHECK ME" : "");
        }
        if (outDir != null) {
            try {
                Files.createDirectories(outDir);
                for (Map.Entry<String, String> e : RENDERED.entrySet()) {
                    Path f = outDir.resolve("legacy-system-prompt-" + e.getKey() + ".txt");
                    Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
                    System.out.println("  wrote " + f);
                }
            } catch (IOException e) {
                System.out.println("  (could not write legacy prompts: " + e + ")");
            }
        }
        System.out.println();
    }

    /** Loads exactly the classes we just compiled; everything else from the parent. */
    private static final class MapClassLoader extends ClassLoader {

        private final Map<String, byte[]> classes;

        MapClassLoader(Map<String, byte[]> classes, ClassLoader parent) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** {@code ./gradlew :core:evalLegacyPrompt} style smoke run. */
    public static void main(String[] args) {
        if (args.length > 0) {
            configure(Path.of(args[0]), args.length > 1 ? Path.of(args[1]) : null);
        }
        ensureLoaded();
        for (String v : List.of("1.21.8", "1.21.1", "1.20", "26.2")) {
            System.out.printf(java.util.Locale.ROOT, "  %-8s -> %-13s (%,d chars)%n",
                    v, profileIdFor(v), forVersion(v).length());
        }
    }
}
