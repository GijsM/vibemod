import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.gijsm.vibemine.compile.InMemoryCompiler;
import com.gijsm.vibemine.gen.GeneratedProject;
import com.gijsm.vibemine.store.JarExporter;
import com.gijsm.vibemine.store.ModStore;

/**
 * Standalone self-test (no test framework) proving ModStore and JarExporter
 * work end-to-end: disk round-trip of versions/metadata, FQCN derivation,
 * rollback semantics, path-traversal rejection, and a real compile + jar
 * export whose contents are verified with java.util.jar.JarFile.
 */
public class StoreSelfTest {

    private static final String SCRATCH_ROOT =
            "/private/tmp/claude-501/-Users-gijsmulder-projects-vibemine/28576c55-1a45-4088-81bb-f47d2e6ed714/scratchpad/store-selftest";

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(SCRATCH_ROOT));

        testRoundTripAndFqcnDerivation();
        testPathTraversalRejected();
        testJarExporter();

        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    private static void testRoundTripAndFqcnDerivation() throws Exception {
        Path modsDir = tempDir("modstore-roundtrip");
        ModStore store = new ModStore(modsDir);

        GeneratedProject project1 = new GeneratedProject("TestMod", "does a thing", "TestMod", List.of(
                new GeneratedProject.GeneratedFile("TestMod.java",
                        "package vibemod.testmod;\n\npublic class TestMod {}\n"),
                new GeneratedProject.GeneratedFile("Helper.java",
                        "package vibemod.testmod;\n\nclass Helper {}\n"),
                new GeneratedProject.GeneratedFile("NoPackage.java",
                        "public class NoPackage {}\n")));

        ModStore.StoredMod saved = store.saveNewVersion("TestMod", "does a thing", "TestMod", "gijs",
                "make a test mod", "model-x", project1);
        check("saveNewVersion returns version 1", saved.currentVersion() == 1);
        check("saveNewVersion enabled true", saved.enabled());
        check("saveNewVersion has 1 version entry", saved.versions().size() == 1);
        check("saveNewVersion sets creator", "gijs".equals(saved.creator()));

        List<ModStore.StoredMod> all = store.all();
        check("all() contains 1 mod", all.size() == 1);
        check("all() name matches", all.get(0).name().equals("TestMod"));

        ModStore.StoredMod fetched = store.get("testmod");
        check("get() case-insensitive lookup works", fetched != null && "TestMod".equals(fetched.name()));

        Map<String, String> srcs = store.sources("TESTMOD", 1);
        check("sources() returns 3 files", srcs.size() == 3);
        check("sources() FQCN main class derived from package decl",
                srcs.containsKey("vibemod.testmod.TestMod"));
        check("sources() FQCN helper class derived from package decl",
                srcs.containsKey("vibemod.testmod.Helper"));
        check("sources() bare classname used when no package decl",
                srcs.containsKey("NoPackage"));
        check("source content round-trips",
                srcs.get("vibemod.testmod.TestMod").contains("public class TestMod"));

        GeneratedProject project2 = new GeneratedProject("TestMod", "does a thing v2", "TestMod", List.of(
                new GeneratedProject.GeneratedFile("TestMod.java",
                        "package vibemod.testmod;\n\npublic class TestMod { void v2() {} }\n")));
        ModStore.StoredMod saved2 = store.saveNewVersion("TestMod", "does a thing v2", "TestMod", "someone-else",
                "edit test mod", "model-y", project2);
        check("saveNewVersion v2 returns version 2", saved2.currentVersion() == 2);
        check("v2 has 2 version entries", saved2.versions().size() == 2);
        check("creator preserved from original creation, not overwritten by edit call",
                "gijs".equals(saved2.creator()));

        Map<String, String> v1srcs = store.sources("TestMod", 1);
        check("v1 sources still readable after v2 save", v1srcs.containsKey("vibemod.testmod.TestMod"));
        check("v1 content unchanged", !v1srcs.get("vibemod.testmod.TestMod").contains("v2()"));

        boolean rb1 = store.rollback("TestMod");
        check("rollback from v2 succeeds", rb1);
        ModStore.StoredMod afterRollback = store.get("TestMod");
        check("currentVersion after rollback == 1", afterRollback.currentVersion() == 1);

        boolean rb2 = store.rollback("TestMod");
        check("rollback from v1 fails (already at v1)", !rb2);

        boolean rbUnknown = store.rollback("NoSuchMod");
        check("rollback of unknown mod fails", !rbUnknown);

        store.setEnabled("TestMod", false);
        ModStore.StoredMod disabled = store.get("TestMod");
        check("setEnabled(false) works", !disabled.enabled());

        store.setCurrentVersion("TestMod", 2);
        ModStore.StoredMod bumped = store.get("TestMod");
        check("setCurrentVersion sets to 2", bumped.currentVersion() == 2);

        store.delete("TestMod");
        check("delete removes mod from get()", store.get("TestMod") == null);
        check("delete removes mod from all()", store.all().isEmpty());

        System.out.println("PASS: ModStore round-trip, FQCN derivation, rollback, enable/delete");
    }

    private static void testPathTraversalRejected() throws Exception {
        Path modsDir = tempDir("modstore-traversal");
        ModStore store = new ModStore(modsDir);

        boolean traversalRejected = rejectsFile(store, "Evil", "../../Evil.java");
        check("'..' path traversal filename rejected", traversalRejected);

        boolean slashRejected = rejectsFile(store, "Evil2", "sub/Evil2.java");
        check("'/' containing filename rejected", slashRejected);

        boolean extRejected = rejectsFile(store, "Evil3", "Evil3.txt");
        check("non-.java extension rejected", extRejected);

        check("nothing was actually written for the rejected mod",
                store.get("Evil") == null && store.get("Evil2") == null && store.get("Evil3") == null);

        System.out.println("PASS: unsafe generated file names are rejected");
    }

    private static boolean rejectsFile(ModStore store, String name, String path) {
        GeneratedProject bad = new GeneratedProject(name, "desc", name,
                List.of(new GeneratedProject.GeneratedFile(path, "package vibemod." + name.toLowerCase() + ";\n"
                        + "public class " + name + " {}\n")));
        try {
            store.saveNewVersion(name, "desc", name, "gijs", "p", "m", bad);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void testJarExporter() throws Exception {
        if (!InMemoryCompiler.available()) {
            System.out.println("SKIP: no system Java compiler available for JarExporter self-test");
            return;
        }

        String modSource = "package vibemod.trivial;\n\n"
                + "import com.gijsm.vibemine.api.VibeContext;\n"
                + "import com.gijsm.vibemine.api.VibeMod;\n\n"
                + "public class TrivialMod implements VibeMod {\n"
                + "    @Override\n"
                + "    public void onEnable(VibeContext ctx) throws Exception {\n"
                + "        ctx.log().info(\"trivial mod enabled\");\n"
                + "    }\n"
                + "}\n";
        Map<String, String> sources = Map.of("vibemod.trivial.TrivialMod", modSource);

        ModStore.StoredMod mod = new ModStore.StoredMod("Trivial", "A trivial export test mod", "TrivialMod", 1,
                true, "gijs", List.of(new ModStore.StoredVersion(1, "make a trivial mod", "model-x",
                        System.currentTimeMillis())));

        Path outDir = tempDir("jarexport-out");
        JarExporter exporter = new JarExporter(new InMemoryCompiler());
        Path jarPath = exporter.export(mod, sources, outDir);

        check("export returns expected jar path", jarPath.equals(outDir.resolve("Trivial-1.jar")));
        check("jar file exists", Files.isRegularFile(jarPath));

        String wrapperFqcn = "vibemod.trivial.TrivialExportPlugin";

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry pluginYmlEntry = jarFile.getJarEntry("plugin.yml");
            check("plugin.yml present in jar", pluginYmlEntry != null);
            String pluginYml = new String(jarFile.getInputStream(pluginYmlEntry).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("  plugin.yml:\n" + indent(pluginYml));
            check("plugin.yml name correct", pluginYml.contains("name: Trivial"));
            check("plugin.yml version correct", pluginYml.contains("version: 1.0"));
            check("plugin.yml main points at wrapper FQCN", pluginYml.contains("main: " + wrapperFqcn));
            check("plugin.yml api-version 1.21", pluginYml.contains("api-version: '1.21'"));
            check("plugin.yml author present", pluginYml.contains("author: \"gijs\""));

            check("wrapper class present", jarFile.getJarEntry("vibemod/trivial/TrivialExportPlugin.class") != null);
            check("nested StandaloneContext class present",
                    jarFile.getJarEntry("vibemod/trivial/TrivialExportPlugin$StandaloneContext.class") != null);
            check("mod class present", jarFile.getJarEntry("vibemod/trivial/TrivialMod.class") != null);
            check("api class VibeMod embedded", jarFile.getJarEntry("com/gijsm/vibemine/api/VibeMod.class") != null);
            check("api class VibeContext embedded",
                    jarFile.getJarEntry("com/gijsm/vibemine/api/VibeContext.class") != null);
            check("api class ModCommandHandler embedded",
                    jarFile.getJarEntry("com/gijsm/vibemine/api/ModCommandHandler.class") != null);
            check("manifest present", jarFile.getJarEntry("META-INF/MANIFEST.MF") != null);
        }

        Path srcDir = outDir.resolve("Trivial-src");
        check("source tree directory written", Files.isDirectory(srcDir));
        check("mod source copied into source tree",
                Files.isRegularFile(srcDir.resolve("vibemod/trivial/TrivialMod.java")));
        check("wrapper source copied into source tree",
                Files.isRegularFile(srcDir.resolve("vibemod/trivial/TrivialExportPlugin.java")));
        check("README.txt written", Files.isRegularFile(srcDir.resolve("README.txt")));

        System.out.println("PASS: JarExporter compiles a mod + wrapper and produces a jar with "
                + "plugin.yml, wrapper, mod, and embedded api classes, plus a source tree");
    }

    private static Path tempDir(String prefix) throws Exception {
        return Files.createTempDirectory(Path.of(SCRATCH_ROOT), prefix + "-");
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
}
