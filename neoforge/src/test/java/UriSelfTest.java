import java.nio.file.Path;

import com.gijsm.vibemod.neoforge.LoaderUris;

/**
 * ARCHITECTURE-V2 §9's "union: URL translation unit-tested" item, plus the two
 * other URI shapes a loader can hand out.
 *
 * <p>Plain {@code main()}, like every other VibeMod self-test — the project has
 * no test framework on purpose (docs/ARCHITECTURE-V1.md, "no new dependencies").
 *
 * <p>Worth having even though FML 11 no longer emits {@code union:} anywhere:
 * this function decides whether a classpath entry survives into the compile
 * classpath, and an entry it silently drops is a mod that mysteriously fails to
 * compile against a class the server obviously has. That failure is expensive
 * to diagnose and free to prevent.
 */
public final class UriSelfTest {

    private static int failures;

    public static void main(String[] args) {
        // The ordinary case.
        eq("file: URL", "file:/opt/mc/libraries/brigadier-1.3.10.jar",
                "/opt/mc/libraries/brigadier-1.3.10.jar");

        // A jar URL: the OUTER jar is what a compiler wants, not the entry.
        eq("jar:file: URL drops the entry", "jar:file:/opt/mc/mods/vibemod.jar!/",
                "/opt/mc/mods/vibemod.jar");
        eq("jar:file: URL drops a named entry",
                "jar:file:/opt/mc/mods/vibemod.jar!/META-INF/jarjar/ecj-3.42.0.jar",
                "/opt/mc/mods/vibemod.jar");

        // SecureJarHandler's own scheme (§7.1): strip the scheme, the "%23<n>"
        // package-set index, and the "!/" entry separator.
        eq("union: URL", "union:/opt/mc/libraries/neoforge-universal.jar%23142!/",
                "/opt/mc/libraries/neoforge-universal.jar");
        eq("union: URL with an unescaped #",
                "union:/opt/mc/libraries/neoforge-universal.jar#142!/",
                "/opt/mc/libraries/neoforge-universal.jar");
        eq("union: URL with no index", "union:/opt/mc/libraries/x.jar!/", "/opt/mc/libraries/x.jar");

        // A bare path is passed through, because loaders do hand those out too.
        eq("a bare path", "/opt/mc/libraries/x.jar", "/opt/mc/libraries/x.jar");

        // Nothing in, nothing out — never an exception, because this runs inside
        // a loop over entries the caller cannot vet.
        isNull("null", null);
        isNull("empty", "");
        isNull("blank", "   ");

        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    private static void eq(String what, String uri, String expected) {
        Path got = LoaderUris.toPath(uri);
        boolean ok = got != null && got.equals(Path.of(expected));
        report(what, ok, uri + " -> " + got + (ok ? "" : " (expected " + expected + ")"));
    }

    private static void isNull(String what, String uri) {
        Path got = LoaderUris.toPath(uri);
        report(what, got == null, uri + " -> " + got);
    }

    private static void report(String what, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS: " + what + ": " + detail);
        } else {
            System.out.println("FAIL: " + what + ": " + detail);
            failures++;
        }
    }
}
