import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.LinkedHashMap;
import java.util.Map;

import com.gijsm.vibemod.compile.CompileResult;
import com.gijsm.vibemod.compile.InMemoryCompiler;

/**
 * Standalone self-test (no test framework) proving InMemoryCompiler works
 * end-to-end: successful compile with inner class + lambda, real bytecode
 * loading/execution, syntax-error failure reporting, and compiling against
 * the frozen Mod api (plus the deprecated VibeMod bridge, so pre-v3-rename
 * generated sources still compile).
 */
public class CompilerSelfTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("available() = " + InMemoryCompiler.available());
        if (!InMemoryCompiler.available()) {
            System.out.println("FAIL: no system compiler available, cannot self-test");
            System.exit(1);
        }

        testSuccessfulCompileWithInnerClassAndLambda();
        testSyntaxErrorFailure();
        testCompileAgainstModApi();
        testCompileAgainstDeprecatedVibeModBridge();

        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
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
