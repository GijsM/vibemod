import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLClassLoader;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

import com.gijsm.vibemod.compile.CompileResult;
import com.gijsm.vibemod.compile.InMemoryCompiler;
import com.gijsm.vibemod.platform.CompilerProvider;

/**
 * Standalone self-test (no test framework) proving InMemoryCompiler works
 * end-to-end: successful compile with inner class + lambda, real bytecode
 * loading/execution, syntax-error failure reporting, and compiling against
 * the frozen Mod api (plus the deprecated VibeMod bridge, so pre-v3-rename
 * generated sources still compile).
 *
 * <p>Also guards the {@code formatDiagnostics} snapshot (ARCHITECTURE-V2 §10.1)
 * and, with {@code -Dvibemod.compiler=ecj}, runs the whole suite on the Eclipse
 * compiler instead of javac (§7.3).
 */
public class CompilerSelfTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        reportBackend();
        System.out.println("available() = " + InMemoryCompiler.available());
        if (!InMemoryCompiler.available()) {
            System.out.println("FAIL: no compiler backend available, cannot self-test");
            System.exit(1);
        }

        testSuccessfulCompileWithInnerClassAndLambda();
        testSyntaxErrorFailure();
        testCompileAgainstModApi();
        testCompileAgainstDeprecatedVibeModBridge();
        testDiagnosticsSnapshotSurvivesConcurrentReport();

        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    /**
     * Names the resolved backend, and refuses to pass silently when the run asked
     * for ECJ and got javac: an ECJ-forced gate that quietly retested javac would
     * be worse than no gate at all (ARCHITECTURE-V2 §7.3).
     */
    private static void reportBackend() {
        String wanted = System.getProperty(CompilerProvider.BACKEND_PROPERTY, "");
        CompilerProvider provider = CompilerProvider.resolve().orElse(null);
        System.out.println("backend = " + (provider == null ? "none" : provider.name())
                + (wanted.isBlank() ? "" : " (requested: " + wanted + ")")
                + ", max --release = " + (provider == null ? "n/a" : provider.maxSupportedRelease())
                + ", runtime feature = " + Runtime.version().feature());
        if (wanted.toLowerCase(Locale.ROOT).equals("ecj")) {
            check("requested backend ecj was actually resolved",
                    provider != null && provider.name().startsWith("ecj"));
        }
    }

    /**
     * The Phase B bug (ARCHITECTURE-V2 §10.1): {@code formatDiagnostics} used to
     * iterate {@code DiagnosticCollector.getDiagnostics()} live while calling
     * {@code getMessage} on each entry — and {@code getMessage} can drive javac far
     * enough to append to that very list (deferred diagnostics, mandatory-warning
     * aggregation). A compile could therefore throw
     * {@link ConcurrentModificationException} straight out of {@code compile()},
     * which is documented never to throw.
     *
     * <p>Reproducing it through a real javac run turned out to depend on the exact
     * JDK build and classpath — it did NOT reproduce on this machine's JDK 25 over
     * all 148 stored corpus versions — so this test attacks the mechanism instead
     * of the symptom: a diagnostic whose {@code getMessage} reports another
     * diagnostic into the same collector. The naive loop below genuinely throws;
     * the shipped snapshot does not. That invariant is worth locking down whichever
     * javac builds happen to trigger it in the wild.
     */
    private static void testDiagnosticsSnapshotSurvivesConcurrentReport() {
        DiagnosticCollector<JavaFileObject> naive = new DiagnosticCollector<>();
        naive.report(new GrowingDiagnostic(naive, "first"));
        naive.report(new GrowingDiagnostic(naive, "second"));

        boolean naiveThrew = false;
        try {
            StringBuilder sb = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> d : naive.getDiagnostics()) {
                sb.append(d.getMessage(null));
            }
        } catch (ConcurrentModificationException expected) {
            naiveThrew = true;
        }
        check("the naive live iteration really does throw CME (the bug is real)", naiveThrew);

        DiagnosticCollector<JavaFileObject> fresh = new DiagnosticCollector<>();
        fresh.report(new GrowingDiagnostic(fresh, "first"));
        fresh.report(new GrowingDiagnostic(fresh, "second"));
        try {
            String rendered = InMemoryCompiler.formatDiagnostics(fresh);
            check("formatDiagnostics snapshots first and does not throw", rendered.contains("first"));
            System.out.println("PASS: formatDiagnostics survives diagnostics appended while rendering");
        } catch (RuntimeException e) {
            check("formatDiagnostics snapshots first and does not throw (" + e + ")", false);
        }
    }

    /** A diagnostic that appends another diagnostic to its own collector when rendered. */
    private static final class GrowingDiagnostic extends SyntheticDiagnostic {

        private final DiagnosticCollector<JavaFileObject> collector;
        private final String label;
        private boolean grown;

        GrowingDiagnostic(DiagnosticCollector<JavaFileObject> collector, String label) {
            this.collector = collector;
            this.label = label;
        }

        @Override
        public String getMessage(Locale locale) {
            if (!grown) {
                grown = true;
                // Exactly what javac's mandatory-warning aggregation does to us.
                collector.report(new SyntheticDiagnostic());
            }
            return label;
        }

        @Override
        public Kind getKind() {
            return Kind.WARNING;
        }
    }

    /** Position-free inert diagnostic; {@link GrowingDiagnostic} specializes it. */
    private static class SyntheticDiagnostic implements Diagnostic<JavaFileObject> {

        @Override
        public String getMessage(Locale locale) {
            return "synthetic";
        }

        @Override
        public Kind getKind() {
            return Kind.NOTE;
        }

        @Override
        public JavaFileObject getSource() {
            return new SimpleJavaFileObject(URI.create("string:///Synthetic.java"),
                    JavaFileObject.Kind.SOURCE) {
            };
        }

        @Override
        public long getPosition() {
            return NOPOS;
        }

        @Override
        public long getStartPosition() {
            return NOPOS;
        }

        @Override
        public long getEndPosition() {
            return NOPOS;
        }

        @Override
        public long getLineNumber() {
            return NOPOS;
        }

        @Override
        public long getColumnNumber() {
            return NOPOS;
        }

        @Override
        public String getCode() {
            return null;
        }
    }

    private static void testSuccessfulCompileWithInnerClassAndLambda() throws Exception {
        String source = """
                package vibemod.selftest;

                import java.util.function.Supplier;

                public class Hello {

                    static class Inner {
                        String greet() {
                            return "inner";
                        }
                    }

                    public static String greet() {
                        Supplier<String> supplier = () -> "hi";
                        Inner inner = new Inner();
                        return supplier.get() + "-" + inner.greet();
                    }
                }
                """;

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("vibemod.selftest.Hello", source);

        InMemoryCompiler compiler = new InMemoryCompiler();
        CompileResult result = compiler.compile(sources);

        check("successful compile", result.success());
        if (!result.success()) {
            System.out.println("  diagnostics: " + result.diagnostics());
            return;
        }

        check("outer class present", result.classes().containsKey("vibemod.selftest.Hello"));
        check("inner class present", result.classes().containsKey("vibemod.selftest.Hello$Inner"));
        System.out.println("  classes: " + result.classes().keySet());

        // Load the bytes via a throwaway URLClassLoader subclass and invoke a static method.
        try (ThrowawayClassLoader loader = new ThrowawayClassLoader(result.classes())) {
            Class<?> helloClass = loader.loadClass("vibemod.selftest.Hello");
            Method greet = helloClass.getMethod("greet");
            Object value = greet.invoke(null);
            check("loaded bytecode executes and returns expected value",
                    "hi-inner".equals(value));
            System.out.println("  greet() -> " + value);
        }

        System.out.println("PASS: successful compile with inner class + lambda, bytecode loads and runs");
    }

    private static void testSyntaxErrorFailure() {
        String badSource = """
                package vibemod.selftest;

                public class Broken {
                    public static String broken( {
                        return "oops"
                    }
                }
                """;

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("vibemod.selftest.Broken", badSource);

        InMemoryCompiler compiler = new InMemoryCompiler();
        CompileResult result = compiler.compile(sources);

        check("syntax error compile fails", !result.success());
        check("syntax error result has no classes", result.classes().isEmpty());
        check("diagnostics mention a line number",
                result.diagnostics() != null && result.diagnostics().matches("(?s).*:[0-9]+.*"));
        System.out.println("  diagnostics:\n" + indent(result.diagnostics()));

        System.out.println("PASS: syntax error compile reports failure with line info");
    }

    private static void testCompileAgainstModApi() {
        String source = """
                package vibemod.selftest;

                import com.gijsm.vibemod.api.VibeContext;
                import com.gijsm.vibemod.api.Mod;

                public class ApiMod implements Mod {
                    @Override
                    public void onEnable(VibeContext ctx) throws Exception {
                    }
                }
                """;

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("vibemod.selftest.ApiMod", source);

        InMemoryCompiler compiler = new InMemoryCompiler();
        CompileResult result = compiler.compile(sources);

        check("compile against frozen Mod api succeeds", result.success());
        if (!result.success()) {
            System.out.println("  diagnostics: " + result.diagnostics());
            return;
        }
        check("ApiMod class present", result.classes().containsKey("vibemod.selftest.ApiMod"));

        System.out.println("PASS: compiling against frozen Mod/VibeContext api works");
    }

    /**
     * Proves the deprecated {@code VibeMod extends Mod} bridge still compiles, so mod
     * sources generated before the v3 rename (which declare {@code implements VibeMod})
     * keep recompiling from stored source at boot.
     */
    private static void testCompileAgainstDeprecatedVibeModBridge() {
        String source = """
                package vibemod.selftest;

                import com.gijsm.vibemod.api.VibeContext;
                import com.gijsm.vibemod.api.VibeMod;

                public class BridgeMod implements VibeMod {
                    @Override
                    public void onEnable(VibeContext ctx) throws Exception {
                    }
                }
                """;

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("vibemod.selftest.BridgeMod", source);

        InMemoryCompiler compiler = new InMemoryCompiler();
        CompileResult result = compiler.compile(sources);

        check("compile against deprecated VibeMod bridge succeeds", result.success());
        if (!result.success()) {
            System.out.println("  diagnostics: " + result.diagnostics());
            return;
        }
        check("BridgeMod class present", result.classes().containsKey("vibemod.selftest.BridgeMod"));

        System.out.println("PASS: compiling a pre-v3-rename \"implements VibeMod\" source still works via the bridge");
    }

    private static String indent(String s) {
        if (s == null) {
            return "";
        }
        return "    " + s.replace("\n", "\n    ");
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            System.out.println("  ok: " + label);
        } else {
            System.out.println("  FAIL: " + label);
            failures++;
        }
    }

    /** Minimal URLClassLoader subclass that defines classes straight from in-memory bytes. */
    private static final class ThrowawayClassLoader extends URLClassLoader {
        private final Map<String, byte[]> classBytes;

        ThrowawayClassLoader(Map<String, byte[]> classBytes) {
            super(new java.net.URL[0], CompilerSelfTest.class.getClassLoader());
            this.classBytes = classBytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classBytes.get(name);
            if (bytes == null) {
                return super.findClass(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
