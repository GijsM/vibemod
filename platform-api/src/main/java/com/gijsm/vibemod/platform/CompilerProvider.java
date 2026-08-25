package com.gijsm.vibemod.platform;

import java.util.Optional;
import java.util.ServiceLoader;

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
 */
public interface CompilerProvider {

    /** The resolved compiler; never null once a provider exists. */
    JavaCompiler compiler();

    /** Human-readable backend name for logs, e.g. {@code "javac (system)"} or {@code "ecj 3.43"}. */
    String name();

    /** Highest {@code --release} the backend supports; used to clamp compile options. */
    int maxSupportedRelease();

    /** Resolves per the documented order; empty when no backend is available. */
    static Optional<CompilerProvider> resolve() {
        JavaCompiler system = ToolProvider.getSystemJavaCompiler();
        if (system != null) {
            return Optional.of(of(system, "javac (system)", Runtime.version().feature()));
        }
        for (JavaCompiler found : ServiceLoader.load(JavaCompiler.class, CompilerProvider.class.getClassLoader())) {
            return Optional.of(of(found, found.getClass().getSimpleName() + " (service)", Runtime.version().feature()));
        }
        try {
            JavaCompiler ecj = (JavaCompiler) Class
                    .forName("org.eclipse.jdt.internal.compiler.tool.EclipseCompiler")
                    .getDeclaredConstructor().newInstance();
            return Optional.of(of(ecj, "ecj (bundled)", Runtime.version().feature()));
        } catch (ReflectiveOperationException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static CompilerProvider of(JavaCompiler compiler, String name, int maxRelease) {
        return new CompilerProvider() {
            @Override public JavaCompiler compiler() { return compiler; }
            @Override public String name() { return name; }
            @Override public int maxSupportedRelease() { return maxRelease; }
        };
    }
}
