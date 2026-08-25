package com.gijsm.vibemod.platform;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Resolves the {@link JavaCompiler} backing {@code InMemoryCompiler}, once,
 * at host init. Resolution order (ARCHITECTURE-V2 §7.3):
 * <ol>
 *   <li>{@link ToolProvider#getSystemJavaCompiler()} — full JDKs (typical servers, dev);</li>
 *   <li>{@link ServiceLoader} over {@link JavaCompiler} — picks up a bundled ECJ's service entry;</li>
 *   <li>reflective {@code org.eclipse.jdt.internal.compiler.tool.EclipseCompiler} — robust
 *       where classloader-context ServiceLoader lookups misbehave (Knot/ModLauncher).</li>
 * </ol>
 * ECJ is bundled (Jar-in-Jar, never relocated) only in the fabric/neoforge
 * hosts; on Paper the system compiler is expected and ECJ is an optional rescue
 * for JRE-run servers.
 *
 * <p><b>Forcing a backend.</b> {@code -Dvibemod.compiler=ecj} skips step 1 so
 * the ECJ path is exercised on a machine that has a perfectly good javac —
 * this is what {@code CompilerSelfTest}'s ECJ mode uses. {@code =javac} pins
 * step 1 and fails fast rather than silently falling back.
 */
public interface CompilerProvider {

    /** System property selecting a backend: {@code ecj}, {@code javac}, or unset (= the full chain). */
    String BACKEND_PROPERTY = "vibemod.compiler";

    /** FQCN of ECJ's {@link JavaCompiler}; never relocate it — this name and its service entry are load-bearing. */
    String ECJ_CLASS = "org.eclipse.jdt.internal.compiler.tool.EclipseCompiler";

    /** The resolved compiler; never null once a provider exists. */
    JavaCompiler compiler();

    /** Human-readable backend name for logs, e.g. {@code "javac (system)"} or {@code "ecj 3.43"}. */
    String name();

    /** Highest {@code --release} the backend supports; used to clamp compile options. */
    int maxSupportedRelease();

    /**
     * Whether the backend accepts compilation units that exist only in memory.
     *
     * <p>javac does. ECJ does not: it re-resolves every unit by
     * {@code JavaFileObject#getName()} against the filesystem and reports
     * {@code "File /p/A.java is missing"} for anything that is not one of its own
     * file objects, whatever file manager it is handed. Callers that get
     * {@code false} here must stage the sources as real files before compiling
     * — the contained fallback ARCHITECTURE-V2 §7.3 asks for. Class output is
     * unaffected and stays in memory either way.
     */
    default boolean acceptsInMemorySources() {
        return true;
    }

    /** Resolves per the documented order; empty when no backend is available. */
    static Optional<CompilerProvider> resolve() {
        return resolve(System.getProperty(BACKEND_PROPERTY));
    }

    /**
     * {@link #resolve()} with an explicit backend selector ({@code null} = the
     * full chain). Split out so the self-tests can drive both modes in one JVM.
     */
    static Optional<CompilerProvider> resolve(String backend) {
        String want = backend == null ? "" : backend.trim().toLowerCase(Locale.ROOT);
        boolean forceEcj = want.equals("ecj");

        if (!forceEcj) {
            JavaCompiler system = ToolProvider.getSystemJavaCompiler();
            if (system != null) {
                return Optional.of(of(system, "javac (system)", releaseCeilingOf(system, Runtime.version().feature())));
            }
            if (want.equals("javac")) {
                return Optional.empty();
            }
        }

        // The JDK's own jdk.compiler module registers JavacTool as a JavaCompiler
        // service, so an unfiltered ServiceLoader scan hands back javac even when
        // ECJ was explicitly asked for. In forced mode only ECJ counts.
        for (JavaCompiler found : ServiceLoader.load(JavaCompiler.class, CompilerProvider.class.getClassLoader())) {
            if (found == null) {
                continue;
            }
            boolean isEcj = found.getClass().getName().equals(ECJ_CLASS);
            if (forceEcj && !isEcj) {
                continue;
            }
            String label = isEcj ? "ecj (service)" : found.getClass().getSimpleName() + " (service)";
            return Optional.of(of(found, label, releaseCeilingOf(found, Runtime.version().feature())));
        }
        try {
            JavaCompiler ecj = (JavaCompiler) Class.forName(ECJ_CLASS)
                    .getDeclaredConstructor().newInstance();
            return Optional.of(of(ecj, "ecj (reflective)", releaseCeilingOf(ecj, Runtime.version().feature())));
        } catch (ReflectiveOperationException | LinkageError e) {
            return Optional.empty();
        }
    }

    /**
     * The highest {@code --release} the backend actually accepts, read from its
     * own {@code getSourceVersions()} rather than assumed from the runtime: a
     * bundled ECJ can lag the JVM it runs on by a release or two, and passing
     * it a {@code --release} it never heard of is a hard compile failure rather
     * than a diagnostic.
     */
    private static int releaseCeilingOf(JavaCompiler compiler, int runtimeFeature) {
        try {
            Set<javax.lang.model.SourceVersion> supported = compiler.getSourceVersions();
            int best = 0;
            for (javax.lang.model.SourceVersion v : supported) {
                // SourceVersion.RELEASE_N ordinal == N for every release we care about.
                best = Math.max(best, v.ordinal());
            }
            if (best > 0) {
                return best;
            }
        } catch (Throwable ignored) {
            // fall through to the runtime's own feature version
        }
        return runtimeFeature;
    }

    /** ECJ's version string ("3.42.0" style) when the backend is ECJ, else empty. */
    private static String versionSuffixOf(JavaCompiler compiler) {
        try {
            Method m = compiler.getClass().getMethod("getName");
            Object name = m.invoke(compiler);
            return name == null ? "" : " " + name;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static CompilerProvider of(JavaCompiler compiler, String name, int maxRelease) {
        boolean ecj = name.startsWith("ecj");
        String label = name + (ecj ? versionSuffixOf(compiler) : "");
        return new CompilerProvider() {
            @Override public JavaCompiler compiler() {
                return compiler;
            }

            @Override public String name() {
                return label;
            }

            @Override public int maxSupportedRelease() {
                return maxRelease;
            }

            @Override public boolean acceptsInMemorySources() {
                return !ecj;
            }
        };
    }
}
