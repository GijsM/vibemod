import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.gijsm.vibemod.compile.InMemoryCompiler;
import com.gijsm.vibemod.gen.GeneratedProject;
import com.gijsm.vibemod.gen.GeneratedProject.ConfigKnob;
import com.gijsm.vibemod.store.JarExporter;
import com.gijsm.vibemod.store.ModConfigs;
import com.gijsm.vibemod.store.ModStore;

/**
 * Standalone self-test (no test framework) proving ModStore, ModConfigs and
 * JarExporter work end-to-end: disk round-trip of versions/metadata (including
 * the per-version changelog/kind/costUsd/requester), FQCN derivation, rollback
 * semantics, arbitrary setCurrentVersion, versionsOnDisk, path-traversal
 * rejection, v1 meta.json
 * null-normalization, the tri-state per-mod debugEcho override's persistence,
 * config knob validation/overlay/preservation, live
 * config caching, and a real compile + jar export (with an embedded
 * config.yml for knobbed mods) whose contents are verified with
 * java.util.jar.JarFile.
 *
 * <p>Finally it recompiles two stored-mod corpora against the live sdk, so an
 * api change that would break mods already on disk fails the build: the
 * checked-in fixture corpus ({@code -Dvibemod.fixture.mods.dir}, always
 * present, always required) and — where it exists — the maintainer's real one
 * ({@code -Dvibemod.mods.dir}, hundreds of sources, skipped when absent).
 */
public class StoreSelfTest {

    private static Path scratchRoot;

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        scratchRoot = Files.createTempDirectory("vibemod-store-selftest-");

        testRoundTripAndFqcnDerivation();
        testPathTraversalRejected();
        testResourceFilesRoundTripAndNamespaceRewrite();
        testPixelGridEncoding();
        testRegistryLedger();
        testNullFieldMetaJsonNormalized();
        testMetaSchemaV3Normalization();
        testVersionMetadataAndVersionsOnDisk();
        testIconRoundTripAndNormalization();
        testDebugEchoPersistence();
        testSetConfigValueValidationMatrix();
        testResolvedConfigValuesOverlay();
        testSaveNewVersionPreservesSurvivingConfigValues();
        testModConfigsLiveReads();
        testJarExporter();
        testJarExporterEmbedsConfigYml();
        testFixtureCorpusCompiles();
        testStoredCorpusCompiles();

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

        GeneratedProject project1 = new GeneratedProject("TestMod", "does a thing", "try /vibe do TestMod poke",
                "Punch things to see what happens.", null, "TORCH", "TestMod", List.of(
                new GeneratedProject.GeneratedFile("TestMod.java",
                        "package vibemod.testmod;\n\npublic class TestMod {}\n"),
                new GeneratedProject.GeneratedFile("Helper.java",
                        "package vibemod.testmod;\n\nclass Helper {}\n"),
                new GeneratedProject.GeneratedFile("NoPackage.java",
                        "public class NoPackage {}\n")),
                List.of(), null);

        ModStore.StoredMod saved = store.saveNewVersion("TestMod", "does a thing", "TestMod", "gijs",
                "make a test mod", "model-x", null, null, 0.0, null, project1);
        check("saveNewVersion returns version 1", saved.currentVersion() == 1);
        check("saveNewVersion enabled true", saved.enabled());
        check("saveNewVersion has 1 version entry", saved.versions().size() == 1);
        check("saveNewVersion sets creator", "gijs".equals(saved.creator()));
        check("saveNewVersion carries usage from project", "try /vibe do TestMod poke".equals(saved.usage()));
        check("saveNewVersion carries manual from project",
                "Punch things to see what happens.".equals(saved.manual()));
        check("saveNewVersion carries icon from project", "TORCH".equals(saved.icon()));
        check("saveNewVersion normalizes null config to empty list", saved.config().isEmpty());
        check("saveNewVersion normalizes absent config values to empty map", saved.configValues().isEmpty());

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

        GeneratedProject project2 = new GeneratedProject("TestMod", "does a thing v2", "usage v2", "manual v2",
                null, "TORCH", "TestMod", List.of(
                new GeneratedProject.GeneratedFile("TestMod.java",
                        "package vibemod.testmod;\n\npublic class TestMod { void v2() {} }\n")),
                List.of(), null);
        ModStore.StoredMod saved2 = store.saveNewVersion("TestMod", "does a thing v2", "TestMod", "someone-else",
                "edit test mod", "model-y", null, null, 0.0, null, project2);
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

    /**
     * V3 Phase 2 §A. The claim under test is the one that makes collisions
     * structurally impossible: whatever namespace the model wrote, what lands on
     * disk is {@code vibemod_<mod>} — in the path AND in every id inside the
     * bodies — while {@code minecraft:} is left alone, and the compiler never
     * sees any of it.
     */
    private static void testResourceFilesRoundTripAndNamespaceRewrite() throws Exception {
        Path modsDir = tempDir("modstore-resources");
        ModStore store = new ModStore(modsDir);

        String recipe = "{\"type\":\"minecraft:crafting_shaped\","
                + "\"key\":{\"#\":\"minecraft:redstone\"},"
                + "\"result\":{\"id\":\"minecraft:amethyst_shard\","
                + "\"components\":{\"minecraft:item_model\":\"charm:ruby\"}}}";
        GeneratedProject project = new GeneratedProject("RubyCharm", "a charm", "craft it", "manual",
                null, "AMETHYST_SHARD", "RubyCharm", List.of(
                new GeneratedProject.GeneratedFile("RubyCharm.java",
                        "package vibemod.rubycharm;\n\npublic class RubyCharm {}\n"),
                new GeneratedProject.GeneratedFile("data/charm/recipe/ruby.json", recipe),
                new GeneratedProject.GeneratedFile("assets/charm/lang/en_us.json",
                        "{\"item.charm.ruby\":\"Ruby\"}")),
                List.of(), null);
        store.saveNewVersion("RubyCharm", "a charm", "RubyCharm", "gijs",
                "make it", "model-x", null, null, 0.0, null, project);

        Map<String, String> sources = store.sources("RubyCharm", 1);
        check("sources() hands the compiler ONLY the java file", sources.size() == 1
                && sources.containsKey("vibemod.rubycharm.RubyCharm"));

        Map<String, String> resources = store.resources("RubyCharm", 1);
        check("resources() returns both non-java files", resources.size() == 2);
        check("the model's namespace was rewritten in the data path",
                resources.containsKey("data/vibemod_rubycharm/recipe/ruby.json"));
        check("the model's namespace was rewritten in the assets path",
                resources.containsKey("assets/vibemod_rubycharm/lang/en_us.json"));
        String storedRecipe = resources.get("data/vibemod_rubycharm/recipe/ruby.json");
        check("an id inside the body was rewritten too",
                storedRecipe.contains("\"vibemod_rubycharm:ruby\"")
                        && !storedRecipe.contains("\"charm:ruby\""));
        check("minecraft: ids were left alone",
                storedRecipe.contains("\"minecraft:redstone\"")
                        && storedRecipe.contains("\"minecraft:crafting_shaped\""));
        check("a dotted translation key is NOT rewritten (java names it, and java is not)",
                resources.get("assets/vibemod_rubycharm/lang/en_us.json").contains("item.charm.ruby"));
        check("the canonical namespace is derived from the mod name",
                "vibemod_rubycharm".equals(
                        com.gijsm.vibemod.store.ModResources.canonicalNamespace("RubyCharm")));
        check("a mod name with punctuation still yields a legal namespace",
                "vibemod_my_mod_2".equals(
                        com.gijsm.vibemod.store.ModResources.canonicalNamespace("My-Mod 2")));

        // §F: a version that changes ONLY a resource still round-trips.
        GeneratedProject v2 = new GeneratedProject("RubyCharm", "a charm", "craft it", "manual",
                null, "AMETHYST_SHARD", "RubyCharm", List.of(
                new GeneratedProject.GeneratedFile("RubyCharm.java",
                        "package vibemod.rubycharm;\n\npublic class RubyCharm {}\n"),
                new GeneratedProject.GeneratedFile("data/charm/recipe/ruby.json",
                        recipe.replace("amethyst_shard", "emerald")),
                new GeneratedProject.GeneratedFile("assets/charm/lang/en_us.json",
                        "{\"item.charm.ruby\":\"Ruby\"}")),
                List.of(), null);
        ModStore.StoredMod saved2 = store.saveNewVersion("RubyCharm", "a charm", "RubyCharm", "gijs",
                "swap the result", "model-x", null, null, 0.0, null, v2);
        check("a resource-only change makes a new version", saved2.currentVersion() == 2);
        check("v2's resource carries the change",
                store.resources("RubyCharm", 2).get("data/vibemod_rubycharm/recipe/ruby.json")
                        .contains("emerald"));
        check("v1's resource is untouched by v2",
                store.resources("RubyCharm", 1).get("data/vibemod_rubycharm/recipe/ruby.json")
                        .contains("amethyst_shard"));
        check("resources() of a mod with none is empty", store.resources("NoSuchMod", 1).isEmpty());

        System.out.println("PASS: resource files round-trip, and the canonical namespace is enforced");
    }

    /**
     * The hand-rolled PNG encoder (§D). Asserted on the bytes, because the only
     * consumer that matters is a texture loader that will reject a malformed
     * one silently.
     */
    /**
     * The registry ledger (V3 Phase 3 §A): live entries are idempotent, an
     * unload is a tombstone, and both survive a restart.
     *
     * <p>The restart is the point. The ledger's whole job is to be read by the
     * NEXT boot of a world, so a version of it that only works in memory would
     * pass every runtime assertion and re-register a deleted mod's ids anyway.
     */
    private static void testRegistryLedger() throws Exception {
        java.nio.file.Path file = Files.createTempDirectory(scratchRoot, "ledger")
                .resolve(com.gijsm.vibemod.store.RegistryLedger.FILE_NAME);
        com.gijsm.vibemod.store.RegistryLedger ledger =
                new com.gijsm.vibemod.store.RegistryLedger(file);

        check("a fresh ledger knows nothing", ledger.modNames().isEmpty()
                && ledger.describeState().contains("ledgerIds=0"));
        ledger.record("RubySword", 1, "minecraft:item", "vibemod_rubysword:ruby_sword");
        ledger.record("RubySword", 1, "minecraft:item", "vibemod_rubysword:ruby_sword");
        check("recording the same id twice records it once",
                ledger.entriesOf("RubySword").size() == 1);
        ledger.record("RubySword", 1, "minecraft:entity_type", "vibemod_rubysword:ruby_pig");
        check("a second registry is a second entry (" + ledger.describeState() + ")",
                ledger.describeState().contains("ledgerIds=2"));
        check("a live mod is not tombstoned", !ledger.isTombstoned("RubySword"));

        check("it wrote itself to disk", Files.isRegularFile(file));
        com.gijsm.vibemod.store.RegistryLedger reopened =
                new com.gijsm.vibemod.store.RegistryLedger(file);
        check("and a fresh reader sees the same two ids",
                reopened.entriesOf("RubySword").size() == 2);

        reopened.tombstone("RubySword");
        check("unloading tombstones every id the mod owned",
                reopened.isTombstoned("RubySword")
                        && reopened.describeState().contains("ledgerTombstones=1"));
        check("the tombstone survives a restart, which is the only thing it is for",
                new com.gijsm.vibemod.store.RegistryLedger(file).isTombstoned("RubySword"));
        check("and the ids are still listed, so the UI can say what became of them",
                new com.gijsm.vibemod.store.RegistryLedger(file)
                        .entriesOf("RubySword").size() == 2);
        check("a mod that never registered anything is not tombstoned",
                !reopened.isTombstoned("SomeOtherMod"));

        // The install card is where a player is told the id outlives the mod.
        com.gijsm.vibemod.ui.InstallCard.setRegisteredContent(
                name -> reopened.entriesOf(name).stream()
                        .map(com.gijsm.vibemod.store.RegistryLedger.Entry::id).toList());
        try {
            ModStore store = new ModStore(tempDir("registry-card"));
            GeneratedProject project = new GeneratedProject("RubySword", "a sword", "craft it",
                    "manual", null, "IRON_SWORD", "RubySword", List.of(
                    new GeneratedProject.GeneratedFile("RubySword.java",
                            "package vibemod.rubysword;\n\npublic class RubySword {}\n")),
                    List.of(), null);
            store.saveNewVersion("RubySword", "a sword", "RubySword", "tester",
                    "make a sword", "model-x", null, null, 0.0, null, project);
            java.util.List<String> lines = com.gijsm.vibemod.ui.InstallCard.verifiedFactLines(
                    store.get("RubySword"), null, java.util.Map.of());
            check("/vibe info reports the ids a mod registered",
                    lines.stream().anyMatch(line -> line.startsWith("registered content: ")
                            && line.contains("vibemod_rubysword:ruby_sword")));
            check("and says out loud that they outlive the mod",
                    lines.stream().anyMatch(line ->
                            line.contains("stays registered until the world is restarted")));
        } finally {
            com.gijsm.vibemod.ui.InstallCard.setRegisteredContent(null);
        }

        System.out.println("PASS: the registry ledger records ids, tombstones an unloaded mod, "
                + "and survives a restart");
    }

    private static void testPixelGridEncoding() {
        com.gijsm.vibemod.store.PixelGrid grid = com.gijsm.vibemod.store.PixelGrid.parse(
                "{\"palette\":{\".\":\"transparent\",\"r\":\"#ff0000\",\"g\":\"#00ff0080\"},"
                        + "\"rows\":[\".r\",\"g.\"]}");
        check("a grid knows its size", grid.size() == 2);
        byte[] png = grid.toPng();
        check("the PNG starts with the 8-byte signature",
                png.length > 8 && (png[0] & 0xff) == 0x89 && png[1] == 'P' && png[2] == 'N'
                        && png[3] == 'G' && png[4] == '\r' && png[5] == '\n' && png[6] == 0x1a
                        && png[7] == '\n');
        String asLatin1 = new String(png, java.nio.charset.StandardCharsets.ISO_8859_1);
        check("the PNG carries IHDR, IDAT and IEND", asLatin1.contains("IHDR")
                && asLatin1.contains("IDAT") && asLatin1.contains("IEND"));
        check("the IHDR declares 8-bit RGBA (colour type 6)",
                png[24] == 8 && png[25] == 6);
        // IHDR data starts at 16 (8 signature + 4 length + 4 type): width is
        // 16..19, height 20..23, then depth and colour type.
        check("the IHDR declares the right dimensions", png[19] == 2 && png[23] == 2);
        check("every chunk's CRC checks out", crcsAreValid(png));

        // The failure modes the prompt promises are caught at generation time.
        expectGridFailure("a grid with a rows array that is not square",
                "{\"palette\":{\"a\":\"#ff0000\"},\"rows\":[\"aaa\",\"aaa\"]}");
        expectGridFailure("a grid bigger than 64x64",
                "{\"palette\":{\"a\":\"#ff0000\"},\"rows\":[" + "\"a\",".repeat(64) + "\"a\"]}");
        expectGridFailure("a palette key that is not one character",
                "{\"palette\":{\"ab\":\"#ff0000\"},\"rows\":[\"a\"]}");
        expectGridFailure("a colour that is not hex", "{\"palette\":{\"a\":\"#gggggg\"},\"rows\":[\"a\"]}");
        expectGridFailure("a grid that is not JSON at all", "not json");

        System.out.println("PASS: the pixel-grid PNG encoder produces a CRC-valid RGBA PNG");
    }

    private static void expectGridFailure(String what, String json) {
        try {
            com.gijsm.vibemod.store.PixelGrid.parse(json);
            check("PixelGrid rejects " + what, false);
        } catch (IllegalArgumentException expected) {
            // the message is the point: it is what the model is shown
            check("PixelGrid rejects " + what + " (" + expected.getMessage() + ")", true);
        }
    }

    /** Walks a PNG's chunk list and verifies every CRC32, the way a decoder would. */
    private static boolean crcsAreValid(byte[] png) {
        int at = 8;
        while (at + 8 <= png.length) {
            int length = ((png[at] & 0xff) << 24) | ((png[at + 1] & 0xff) << 16)
                    | ((png[at + 2] & 0xff) << 8) | (png[at + 3] & 0xff);
            int typeAt = at + 4;
            int crcAt = typeAt + 4 + length;
            if (crcAt + 4 > png.length) {
                return false;
            }
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(png, typeAt, 4 + length);
            int stored = ((png[crcAt] & 0xff) << 24) | ((png[crcAt + 1] & 0xff) << 16)
                    | ((png[crcAt + 2] & 0xff) << 8) | (png[crcAt + 3] & 0xff);
            if (stored != (int) crc.getValue()) {
                return false;
            }
            at = crcAt + 4;
        }
        return at == png.length;
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
        GeneratedProject bad = new GeneratedProject(name, "desc", null, null, null, null, name,
                List.of(new GeneratedProject.GeneratedFile(path, "package vibemod." + name.toLowerCase() + ";\n"
                        + "public class " + name + " {}\n")),
                List.of(), null);
        try {
            store.saveNewVersion(name, "desc", name, "gijs", "p", "m", null, null, 0.0, null, bad);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /** A v1-shaped meta.json (no usage/manual/config/configValues) must read back fully normalized. */
    private static void testNullFieldMetaJsonNormalized() throws Exception {
        Path modsDir = tempDir("modstore-v1-meta");
        Path modDir = modsDir.resolve("OldMod");
        Files.createDirectories(modDir);

        String v1Json = "{\n"
                + "  \"name\": \"OldMod\",\n"
                + "  \"description\": \"an old-shaped mod\",\n"
                + "  \"mainClass\": \"OldMod\",\n"
                + "  \"currentVersion\": 1,\n"
                + "  \"enabled\": true,\n"
                + "  \"creator\": \"gijs\",\n"
                + "  \"versions\": [\n"
                + "    { \"version\": 1, \"prompt\": \"make an old mod\", \"model\": \"model-z\", \"createdAt\": 123 }\n"
                + "  ]\n"
                + "}\n";
        Files.writeString(modDir.resolve("meta.json"), v1Json, StandardCharsets.UTF_8);

        ModStore store = new ModStore(modsDir);
        ModStore.StoredMod mod = store.get("OldMod");
        check("v1 meta.json is readable at all", mod != null);
        check("v1 meta.json preserves non-null fields (name)", "OldMod".equals(mod.name()));
        check("v1 meta.json preserves non-null fields (creator)", "gijs".equals(mod.creator()));
        check("v1 meta.json preserves non-null fields (versions)", mod.versions().size() == 1);
        check("v1 meta.json null usage normalized to \"\"", "".equals(mod.usage()));
        check("v1 meta.json null manual normalized to \"\"", "".equals(mod.manual()));
        check("v1 meta.json null config normalized to List.of()", mod.config() != null && mod.config().isEmpty());
        check("v1 meta.json null configValues normalized to Map.of()",
                mod.configValues() != null && mod.configValues().isEmpty());

        check("v1 meta.json debugEcho stays null (tri-state: no override, NOT defaulted)",
                mod.debugEcho() == null);

        // meta.json v3 (ARCHITECTURE-V2 5): a file with no schema/platform/mcVersion/side
        // is every one of the 49 stored mods, and reads back as paper/1.21.8/server.
        check("pre-v3 meta.json reads as schema 3", mod.schema() == ModStore.SCHEMA_VERSION);
        check("pre-v3 meta.json platform normalized to paper", "paper".equals(mod.platform()));
        check("pre-v3 meta.json mcVersion normalized to 1.21.8", "1.21.8".equals(mod.mcVersion()));
        check("pre-v3 meta.json side normalized to server", "server".equals(mod.side()));

        // Version entries written before changelog/kind/costUsd/requester existed
        // (the 21 live mods) must read back with ""/0.0 defaults, never null.
        ModStore.StoredVersion oldVersion = mod.versions().get(0);
        check("old-shaped version entry null changelog normalized to \"\"", "".equals(oldVersion.changelog()));
        check("old-shaped version entry null kind normalized to \"\"", "".equals(oldVersion.kind()));
        check("old-shaped version entry missing costUsd defaults to 0.0", oldVersion.costUsd() == 0.0);
        check("old-shaped version entry null requester normalized to \"\"", "".equals(oldVersion.requester()));
        check("old-shaped version entry keeps its pre-existing fields",
                oldVersion.version() == 1 && "make an old mod".equals(oldVersion.prompt())
                        && "model-z".equals(oldVersion.model()) && oldVersion.createdAt() == 123);

        // all() must normalize too.
        ModStore.StoredMod fromAll = store.all().get(0);
        check("all() also normalizes v1-shaped meta.json", "".equals(fromAll.usage())
                && fromAll.config().isEmpty() && fromAll.configValues().isEmpty());

        System.out.println("PASS: v1-shaped meta.json degrades to normalized empty fields");
    }

    /**
     * meta.json v3 (ARCHITECTURE-V2 §5): a pre-v3 file is normalized on read AND
     * written back stamped on the next save; a store told which host it is
     * running on stamps new mods with that; {@code side} is derived from whether
     * the generated code calls {@code ctx.client(...)}; and {@code runsOn}
     * refuses a mod belonging to another platform.
     */
    private static void testMetaSchemaV3Normalization() throws Exception {
        Path modsDir = tempDir("modstore-v3-meta");

        // (a) a fabric store stamps what it generates, and derives side from the source.
        ModStore fabric = new ModStore(modsDir, "fabric", "26.1");
        GeneratedProject serverOnly = new GeneratedProject("ServerSide", "server only", "", "", null, "STONE",
                "ServerSide", List.of(new GeneratedProject.GeneratedFile("ServerSide.java",
                        "package v;\npublic class ServerSide { void go(Object ctx) {} }\n")),
                List.of(), null);
        ModStore.StoredMod s1 = fabric.saveNewVersion("ServerSide", "server only", "ServerSide", "gijs",
                "p", "m", null, null, 0.0, null, serverOnly);
        check("new mod stamps schema 3", s1.schema() == ModStore.SCHEMA_VERSION);
        check("new mod stamps the host platform", "fabric".equals(s1.platform()));
        check("new mod stamps the host mc version", "26.1".equals(s1.mcVersion()));
        check("server-only source derives side=server", "server".equals(s1.side()));

        GeneratedProject clientToo = new GeneratedProject("HudSide", "has a hud", "", "", null, "CLOCK",
                "HudSide", List.of(new GeneratedProject.GeneratedFile("HudSide.java",
                        "package v;\npublic class HudSide { void go(Object c) { ctx.client(x -> {}); } }\n")),
                List.of(), null);
        ModStore.StoredMod s2 = fabric.saveNewVersion("HudSide", "has a hud", "HudSide", "gijs",
                "p", "m", null, null, 0.0, null, clientToo);
        check("ctx.client(...) source derives side=both", "both".equals(s2.side()));

        // (b) the v3 fields survive a disk round trip untouched.
        ModStore reread = new ModStore(modsDir, "fabric", "26.1");
        ModStore.StoredMod back = reread.get("HudSide");
        check("v3 fields round-trip through disk",
                "fabric".equals(back.platform()) && "26.1".equals(back.mcVersion())
                        && "both".equals(back.side()) && back.schema() == ModStore.SCHEMA_VERSION);

        // (c) runsOn gates a foreign mod and passes a native one.
        check("runsOn accepts a same-platform mod", reread.runsOn("HudSide", "fabric"));
        check("runsOn refuses a foreign mod", !reread.runsOn("HudSide", "paper"));
        check("runsOn is case-insensitive", reread.runsOn("HudSide", "FABRIC"));
        check("runsOn passes unknown mods through (the caller reports those)",
                reread.runsOn("NoSuchMod", "paper"));

        // (d) a pre-v3 file is written back stamped: normalize-on-read + the next save.
        Path legacyDir = modsDir.resolve("Legacy");
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("meta.json"),
                "{\"name\":\"Legacy\",\"description\":\"d\",\"mainClass\":\"L\","
                        + "\"currentVersion\":1,\"enabled\":true,\"creator\":\"g\",\"versions\":[]}\n",
                StandardCharsets.UTF_8);
        ModStore legacyStore = new ModStore(modsDir, "fabric", "26.1");
        legacyStore.setEnabled("Legacy", false);
        String written = Files.readString(legacyDir.resolve("meta.json"), StandardCharsets.UTF_8);
        check("a pre-v3 file gets the v3 fields written back on next save",
                written.contains("\"schema\": 3") && written.contains("\"platform\": \"paper\"")
                        && written.contains("\"mcVersion\": \"1.21.8\"")
                        && written.contains("\"side\": \"server\""));
        check("write-back keeps the retro-stamp, not the running host",
                "paper".equals(legacyStore.get("Legacy").platform()));

        System.out.println("PASS: meta.json v3 stamps new mods, normalizes old ones, and gates foreign platforms");
    }

    /**
     * The post-v1 version metadata (changelog/kind/costUsd/requester) round-trips
     * through disk, setCurrentVersion jumps to any (non-adjacent) stored version,
     * and versionsOnDisk() reflects a version whose sources directory was deleted.
     */
    private static void testVersionMetadataAndVersionsOnDisk() throws Exception {
        Path modsDir = tempDir("modstore-version-meta");
        ModStore store = new ModStore(modsDir);

        for (int i = 1; i <= 3; i++) {
            GeneratedProject project = new GeneratedProject("Timeline", "desc v" + i, null, null, null, null,
                    "Timeline", List.of(new GeneratedProject.GeneratedFile("Timeline.java",
                            "package vibemod.timeline;\n\npublic class Timeline { /* v" + i + " */ }\n")),
                    List.of(), null);
            store.saveNewVersion("Timeline", "desc v" + i, "Timeline", "gijs",
                    "prompt v" + i, "model-x", "changelog v" + i, i == 1 ? "create" : "edit",
                    0.01 * i, "steve", project);
        }

        // New fields round-trip from disk (get() goes through readMeta every time).
        ModStore.StoredVersion v2 = store.get("Timeline").versions().get(1);
        check("changelog round-trips from disk", "changelog v2".equals(v2.changelog()));
        check("kind round-trips from disk", "edit".equals(v2.kind()));
        check("costUsd round-trips from disk", v2.costUsd() == 0.02);
        check("requester round-trips from disk", "steve".equals(v2.requester()));

        // Arbitrary, non-adjacent version pointer: v3 -> v1 persists.
        check("fresh save leaves currentVersion at 3", store.get("Timeline").currentVersion() == 3);
        store.setCurrentVersion("Timeline", 1);
        check("setCurrentVersion jumps non-adjacently from v3 to v1",
                store.get("Timeline").currentVersion() == 1);
        check("non-adjacent setCurrentVersion leaves the version log untouched",
                store.get("Timeline").versions().size() == 3);

        check("versionsOnDisk sees all three version dirs",
                store.versionsOnDisk("Timeline").equals(java.util.Set.of(1, 2, 3)));
        Path v1Dir = modsDir.resolve("Timeline").resolve("v1");
        Files.delete(v1Dir.resolve("Timeline.java"));
        Files.delete(v1Dir);
        check("versionsOnDisk drops a version whose v<N>/ dir was deleted",
                store.versionsOnDisk("Timeline").equals(java.util.Set.of(2, 3)));
        check("versionsOnDisk of an unknown mod is empty", store.versionsOnDisk("NoSuchMod").isEmpty());

        System.out.println("PASS: version metadata round-trip, non-adjacent setCurrentVersion, versionsOnDisk");
    }

    /** A stored icon round-trips verbatim; a project with no icon normalizes to "". */
    private static void testIconRoundTripAndNormalization() throws Exception {
        Path modsDir = tempDir("modstore-icon");
        ModStore store = new ModStore(modsDir);

        GeneratedProject withIcon = new GeneratedProject("IconMod", "has an icon", null, null, null, "CHICKEN", "IconMod",
                List.of(new GeneratedProject.GeneratedFile("IconMod.java",
                        "package vibemod.iconmod;\n\npublic class IconMod {}\n")),
                List.of(), null);
        ModStore.StoredMod saved = store.saveNewVersion("IconMod", "has an icon", "IconMod", "gijs",
                "make icon mod", "model-x", null, null, 0.0, null, withIcon);
        check("saveNewVersion carries icon from project", "CHICKEN".equals(saved.icon()));
        check("get() round-trips icon from disk", "CHICKEN".equals(store.get("IconMod").icon()));

        GeneratedProject noIcon = new GeneratedProject("NoIconMod", "no icon here", null, null, null, null, "NoIconMod",
                List.of(new GeneratedProject.GeneratedFile("NoIconMod.java",
                        "package vibemod.noiconmod;\n\npublic class NoIconMod {}\n")),
                List.of(), null);
        ModStore.StoredMod savedNoIcon = store.saveNewVersion("NoIconMod", "no icon here", "NoIconMod", "gijs",
                "make no-icon mod", "model-x", null, null, 0.0, null, noIcon);
        check("saveNewVersion normalizes a missing icon to \"\"", "".equals(savedNoIcon.icon()));
        check("get() round-trips normalized empty icon", "".equals(store.get("NoIconMod").icon()));

        System.out.println("PASS: ModStore round-trips icon and normalizes a missing icon to \"\"");
    }

    private static ModStore.StoredMod saveModWithConfig(ModStore store, String name, List<ConfigKnob> config)
            throws Exception {
        GeneratedProject project = new GeneratedProject(name, "a configurable mod", "usage", "manual", null, null, name,
                List.of(new GeneratedProject.GeneratedFile(name + ".java",
                        "package vibemod." + name.toLowerCase() + ";\n\npublic class " + name + " {}\n")),
                config, null);
        return store.saveNewVersion(name, "a configurable mod", name, "gijs", "make it", "model-x",
                null, null, 0.0, null, project);
    }

    private static List<ConfigKnob> baseSchema() {
        return List.of(
                new ConfigKnob("flag", "boolean", "true", "Whether the thing is on.", null, null, null, null),
                new ConfigKnob("count", "integer", "5", "How many things.", 1.0, 10.0, 1.0, null),
                new ConfigKnob("mode", "choice", "normal", "How intense.", null, null, null,
                        List.of("weak", "normal", "strong")));
    }

    /**
     * The tri-state per-mod debug echo override: null on new mods, set/clear via
     * setDebugEcho, surviving every other metadata rewrite (setEnabled/
     * setCurrentVersion/rollback/setConfigValue) and carried forward by
     * saveNewVersion.
     */
    private static void testDebugEchoPersistence() throws Exception {
        Path modsDir = tempDir("modstore-debug-echo");
        ModStore store = new ModStore(modsDir);
        saveModWithConfig(store, "Echoey", baseSchema());

        check("a new mod starts with debugEcho null (no override)", store.get("Echoey").debugEcho() == null);

        store.setDebugEcho("Echoey", true);
        check("setDebugEcho(true) round-trips from disk", Boolean.TRUE.equals(store.get("Echoey").debugEcho()));
        store.setDebugEcho("Echoey", false);
        check("setDebugEcho(false) round-trips from disk", Boolean.FALSE.equals(store.get("Echoey").debugEcho()));
        store.setDebugEcho("Echoey", null);
        check("setDebugEcho(null) clears the override", store.get("Echoey").debugEcho() == null);

        store.setDebugEcho("Echoey", true);
        store.setEnabled("Echoey", false);
        check("debugEcho survives setEnabled", Boolean.TRUE.equals(store.get("Echoey").debugEcho()));
        saveModWithConfig(store, "Echoey", baseSchema()); // v2
        check("saveNewVersion carries debugEcho forward", Boolean.TRUE.equals(store.get("Echoey").debugEcho()));
        store.setCurrentVersion("Echoey", 1);
        check("debugEcho survives setCurrentVersion", Boolean.TRUE.equals(store.get("Echoey").debugEcho()));
        store.setCurrentVersion("Echoey", 2);
        check("debugEcho survives rollback",
                store.rollback("Echoey") && Boolean.TRUE.equals(store.get("Echoey").debugEcho()));
        store.setConfigValue("Echoey", "count", "3");
        check("debugEcho survives setConfigValue", Boolean.TRUE.equals(store.get("Echoey").debugEcho()));

        System.out.println("PASS: debugEcho tri-state persists, clears, and survives every metadata rewrite");
    }

    private static void testSetConfigValueValidationMatrix() throws Exception {
        Path modsDir = tempDir("modstore-config-validate");
        ModStore store = new ModStore(modsDir);
        saveModWithConfig(store, "Knobby", baseSchema());

        // boolean: good/bad
        store.setConfigValue("Knobby", "flag", "false");
        check("boolean 'false' accepted", "false".equals(store.get("Knobby").configValues().get("flag")));
        store.setConfigValue("Knobby", "flag", "TRUE");
        check("boolean case-insensitive 'TRUE' accepted and normalized",
                "true".equals(store.get("Knobby").configValues().get("flag")));
        check("boolean 'yes' rejected", throwsIllegalArgument(() -> store.setConfigValue("Knobby", "flag", "yes")));
        check("boolean '1' rejected", throwsIllegalArgument(() -> store.setConfigValue("Knobby", "flag", "1")));

        // integer: good/bad/out-of-range
        store.setConfigValue("Knobby", "count", "7");
        check("integer in range accepted", "7".equals(store.get("Knobby").configValues().get("count")));
        check("integer above max rejected", throwsIllegalArgument(() -> store.setConfigValue("Knobby", "count", "20")));
        check("integer below min rejected", throwsIllegalArgument(() -> store.setConfigValue("Knobby", "count", "0")));
        check("non-numeric integer rejected", throwsIllegalArgument(() -> store.setConfigValue("Knobby", "count", "abc")));

        // choice: good/bad, case-insensitive membership, canonical casing returned
        store.setConfigValue("Knobby", "mode", "STRONG");
        check("choice case-insensitive match normalizes to schema casing",
                "strong".equals(store.get("Knobby").configValues().get("mode")));
        check("choice non-member rejected", throwsIllegalArgument(() -> store.setConfigValue("Knobby", "mode", "loud")));

        // unknown key
        check("unknown config key rejected", throwsIllegalArgument(() -> store.setConfigValue("Knobby", "nope", "x")));

        // nothing was mutated by the rejected calls
        Map<String, String> finalValues = store.get("Knobby").configValues();
        check("rejected calls left prior good values untouched", "true".equals(finalValues.get("flag"))
                && "7".equals(finalValues.get("count")) && "strong".equals(finalValues.get("mode")));

        System.out.println("PASS: setConfigValue validation matrix (boolean/integer/choice, good and bad)");
    }

    private static void testResolvedConfigValuesOverlay() throws Exception {
        Path modsDir = tempDir("modstore-resolved-overlay");
        ModStore store = new ModStore(modsDir);
        saveModWithConfig(store, "Overlay", baseSchema());

        Map<String, String> beforeAnySet = store.resolvedConfigValues("Overlay");
        check("resolvedConfigValues before any set() uses schema defaults",
                "true".equals(beforeAnySet.get("flag")) && "5".equals(beforeAnySet.get("count"))
                        && "normal".equals(beforeAnySet.get("mode")));

        store.setConfigValue("Overlay", "count", "9");
        Map<String, String> afterSet = store.resolvedConfigValues("Overlay");
        check("resolvedConfigValues overlays the stored value for a set key", "9".equals(afterSet.get("count")));
        check("resolvedConfigValues keeps schema default for an untouched key", "true".equals(afterSet.get("flag")));
        check("resolvedConfigValues has exactly one entry per schema key", afterSet.size() == 3);

        check("resolvedConfigValues for unknown mod is empty map",
                store.resolvedConfigValues("NoSuchMod").isEmpty());

        System.out.println("PASS: resolvedConfigValues overlays stored values on schema defaults");
    }

    private static void testSaveNewVersionPreservesSurvivingConfigValues() throws Exception {
        Path modsDir = tempDir("modstore-preserve-config");
        ModStore store = new ModStore(modsDir);
        saveModWithConfig(store, "Evolve", baseSchema());

        store.setConfigValue("Evolve", "flag", "false");
        store.setConfigValue("Evolve", "count", "3");
        store.setConfigValue("Evolve", "mode", "weak");

        // v2 schema: keeps flag and mode, drops count, adds a new "volume" knob.
        List<ConfigKnob> v2Schema = List.of(
                new ConfigKnob("flag", "boolean", "true", "Whether the thing is on.", null, null, null, null),
                new ConfigKnob("mode", "choice", "normal", "How intense.", null, null, null,
                        List.of("weak", "normal", "strong")),
                new ConfigKnob("volume", "decimal", "0.5", "How loud.", 0.0, 1.0, 0.1, null));
        ModStore.StoredMod v2 = saveModWithConfig(store, "Evolve", v2Schema);

        check("v2 schema has 3 knobs (flag, mode, volume)", v2.config().size() == 3);
        check("v2 configValues preserves 'flag' (still in schema)", "false".equals(v2.configValues().get("flag")));
        check("v2 configValues preserves 'mode' (still in schema)", "weak".equals(v2.configValues().get("mode")));
        check("v2 configValues drops 'count' (removed from schema)", !v2.configValues().containsKey("count"));
        check("v2 configValues has no stray entry for the new 'volume' knob (never set)",
                !v2.configValues().containsKey("volume"));

        Map<String, String> resolved = store.resolvedConfigValues("Evolve");
        check("resolved 'volume' falls back to its new schema default", "0.5".equals(resolved.get("volume")));
        check("resolved 'flag' still reflects the preserved override", "false".equals(resolved.get("flag")));

        System.out.println("PASS: saveNewVersion preserves surviving config values and drops removed keys");
    }

    private static void testModConfigsLiveReads() throws Exception {
        Path modsDir = tempDir("modconfigs-live");
        ModStore store = new ModStore(modsDir);
        saveModWithConfig(store, "Live", baseSchema());

        ModConfigs configs = new ModConfigs(store);

        // Unknown mod: type zero, no throw.
        check("unknown mod bool() is false", !configs.bool("NoSuchMod", "flag"));
        check("unknown mod integer() is 0", configs.integer("NoSuchMod", "count") == 0L);
        check("unknown mod decimal() is 0.0", configs.decimal("NoSuchMod", "x") == 0.0);
        check("unknown mod text() is \"\"", "".equals(configs.text("NoSuchMod", "x")));

        configs.register("Live", store.get("Live").config(), store.resolvedConfigValues("Live"));
        check("registered schema() has 3 knobs", configs.schema("Live").size() == 3);
        check("registered bool() reads schema default true", configs.bool("Live", "flag"));
        check("registered integer() reads schema default 5", configs.integer("live", "COUNT") == 5L);
        check("registered text() reads schema default 'normal'", "normal".equals(configs.text("Live", "mode")));

        // Unknown key on a known mod: still type zero.
        check("known mod, unknown key -> false", !configs.bool("Live", "nope"));

        configs.set("Live", "count", "8");
        check("set() updates the live cache immediately", configs.integer("Live", "count") == 8L);
        check("set() persisted through to the store", "8".equals(store.get("Live").configValues().get("count")));
        check("set() rejects an invalid value",
                throwsIllegalArgument(() -> configs.set("Live", "count", "not-a-number")));
        check("a rejected set() does not corrupt the cache", configs.integer("Live", "count") == 8L);

        configs.forget("Live");
        check("forget() clears the cache back to unknown-mod behavior", !configs.bool("Live", "flag"));

        System.out.println("PASS: ModConfigs live reads, unknown-key zero values, set()/forget()");
    }

    private static void testJarExporter() throws Exception {
        if (!InMemoryCompiler.available()) {
            System.out.println("SKIP: no system Java compiler available for JarExporter self-test");
            return;
        }

        String modSource = "package vibemod.trivial;\n\n"
                + "import com.gijsm.vibemod.api.VibeContext;\n"
                + "import com.gijsm.vibemod.api.Mod;\n\n"
                + "public class TrivialMod implements Mod {\n"
                + "    @Override\n"
                + "    public void onEnable(VibeContext ctx) throws Exception {\n"
                + "        ctx.log().info(\"trivial mod enabled\");\n"
                + "    }\n"
                + "}\n";
        Map<String, String> sources = Map.of("vibemod.trivial.TrivialMod", modSource);

        ModStore.StoredMod mod = new ModStore.StoredMod(ModStore.SCHEMA_VERSION, "paper", "1.21.8", "server",
                "Trivial", "A trivial export test mod", "", "", "",
                "TrivialMod", 1, true, "gijs", List.of(new ModStore.StoredVersion(1, "make a trivial mod", "model-x",
                        System.currentTimeMillis(), "", "", 0.0, "")), List.of(), Map.of(), null);

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
            // The floor drop (ARCHITECTURE-V2 §6.3): api-version comes from the
            // PlatformProfile now, and both Paper profiles declare the 1.20 floor
            // so an export can load on the oldest server VibeMod supports.
            check("plugin.yml api-version 1.20 (the floor, from the profile)",
                    pluginYml.contains("api-version: '1.20'"));
            check("plugin.yml author present", pluginYml.contains("author: \"gijs\""));

            check("no-knob mod exports with NO config.yml (exactly as v1)",
                    jarFile.getJarEntry("config.yml") == null);

            check("wrapper class present", jarFile.getJarEntry("vibemod/trivial/TrivialExportPlugin.class") != null);
            check("nested StandaloneContext class present",
                    jarFile.getJarEntry("vibemod/trivial/TrivialExportPlugin$StandaloneContext.class") != null);
            check("mod class present", jarFile.getJarEntry("vibemod/trivial/TrivialMod.class") != null);
            check("api class Mod embedded", jarFile.getJarEntry("com/gijsm/vibemod/api/Mod.class") != null);
            check("deprecated api class VibeMod bridge embedded (old exported sources link)",
                    jarFile.getJarEntry("com/gijsm/vibemod/api/VibeMod.class") != null);
            check("api class VibeContext embedded",
                    jarFile.getJarEntry("com/gijsm/vibemod/api/VibeContext.class") != null);
            check("api class ModCommandHandler embedded",
                    jarFile.getJarEntry("com/gijsm/vibemod/api/ModCommandHandler.class") != null);
            check("api client contract embedded (VibeContext.client names it)",
                    jarFile.getJarEntry("com/gijsm/vibemod/api/client/ClientContext.class") != null
                            && jarFile.getJarEntry("com/gijsm/vibemod/api/client/HudCanvas.class") != null);
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

    private static void testJarExporterEmbedsConfigYml() throws Exception {
        if (!InMemoryCompiler.available()) {
            System.out.println("SKIP: no system Java compiler available for JarExporter config.yml self-test");
            return;
        }

        String modSource = "package vibemod.knobby;\n\n"
                + "import com.gijsm.vibemod.api.VibeContext;\n"
                + "import com.gijsm.vibemod.api.Mod;\n\n"
                + "public class KnobbyMod implements Mod {\n"
                + "    @Override\n"
                + "    public void onEnable(VibeContext ctx) throws Exception {\n"
                + "        boolean flag = ctx.configBool(\"flag\");\n"
                + "        long count = ctx.configInt(\"count\");\n"
                + "        String mode = ctx.configString(\"mode\");\n"
                + "        ctx.log().info(\"flag=\" + flag + \" count=\" + count + \" mode=\" + mode);\n"
                + "    }\n"
                + "}\n";
        Map<String, String> sources = Map.of("vibemod.knobby.KnobbyMod", modSource);

        List<ConfigKnob> config = baseSchema();
        Map<String, String> configValues = new LinkedHashMap<>();
        configValues.put("count", "9"); // override the schema default of 5

        ModStore.StoredMod mod = new ModStore.StoredMod(ModStore.SCHEMA_VERSION, "paper", "1.21.8", "server",
                "Knobby", "A configurable export test mod", "", "", "",
                "KnobbyMod", 1, true, "gijs",
                List.of(new ModStore.StoredVersion(1, "make a knobby mod", "model-x", System.currentTimeMillis(),
                        "", "", 0.0, "")),
                config, configValues, null);

        Path outDir = tempDir("jarexport-config-out");
        JarExporter exporter = new JarExporter(new InMemoryCompiler());
        Path jarPath = exporter.export(mod, sources, outDir);
        check("knobbed export jar exists", Files.isRegularFile(jarPath));

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry configEntry = jarFile.getJarEntry("config.yml");
            check("knobbed mod exports a config.yml", configEntry != null);
            String configYml = new String(jarFile.getInputStream(configEntry).readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("  config.yml:\n" + indent(configYml));
            check("config.yml has description comment for 'flag'", configYml.contains("# Whether the thing is on."));
            check("config.yml has description comment for 'count'", configYml.contains("# How many things."));
            check("config.yml has description comment for 'mode'", configYml.contains("# How intense."));
            check("config.yml seeds 'flag' with its schema default", configYml.contains("flag: true"));
            check("config.yml seeds 'count' with the STORED override (9), not the schema default (5)",
                    configYml.contains("count: 9"));
            check("config.yml seeds 'mode' with its schema default, quoted", configYml.contains("mode: \"normal\""));

            check("wrapper class compiled", jarFile.getJarEntry("vibemod/knobby/KnobbyExportPlugin.class") != null);
            check("mod class compiled", jarFile.getJarEntry("vibemod/knobby/KnobbyMod.class") != null);
        }

        System.out.println("PASS: JarExporter embeds a config.yml seeded with resolved values for a knobbed mod, "
                + "and the wrapper (with baked-in defaults) compiles");
    }

    /**
     * The corpus gate, run over the checked-in fixture corpus
     * ({@code core/src/test/resources/corpus}, wired by the Gradle task as
     * {@code -Dvibemod.fixture.mods.dir}).
     *
     * <p>REQUIRED, not skippable — that is the whole point of it existing. The
     * real corpus below is the honest gate but it lives on one machine and
     * cannot be committed (it is a user's generated mods, and it is large), so
     * on a fresh clone and on every CI runner the corpus check used to print
     * SKIPPED and prove nothing. Three small mods in the real on-disk layout
     * cover the paths that matter: multi-version history, a multi-file mod, the
     * Bukkit-typed half of the api, the {@code sdk-client} surface, and a
     * pre-rename mod that still {@code implements VibeMod} and still imports
     * {@code com.gijsm.vibemine.api}.
     */
    private static void testFixtureCorpusCompiles() {
        String configured = System.getProperty("vibemod.fixture.mods.dir", "");
        if (configured.isBlank() || !Files.isDirectory(Path.of(configured))) {
            check("the fixture corpus is present (it is checked in; -Dvibemod.fixture.mods.dir="
                    + (configured.isBlank() ? "<unset>" : configured) + ")", false);
            return;
        }
        compileCorpus(Path.of(configured), "fixture corpus", true);
    }

    /**
     * Reads every stored mod out of a real mods directory and recompiles every
     * version of every one of them against the live sdk. This is the corpus gate
     * on api compatibility: the frozen {@code com.gijsm.vibemod.api} surface must
     * keep compiling the sources already on disk (including pre-v3 mods that
     * declare {@code implements VibeMod}), whatever the module layout does.
     *
     * <p>The directory comes from {@code -Dvibemod.mods.dir}; the Gradle task
     * defaults it to {@code <repo>/server/plugins/VibeMod/mods} and it can be
     * pointed elsewhere with {@code -Pvibemod.modsDir=...} (needed in git
     * worktrees, where {@code server/} is runtime state and not checked out).
     * When the directory is absent the check reports SKIPPED rather than failing
     * — {@link #testFixtureCorpusCompiles()} is the one that always runs.
     */
    private static void testStoredCorpusCompiles() {
        String configured = System.getProperty("vibemod.mods.dir", "");
        if (configured.isBlank()) {
            System.out.println("SKIPPED: stored-corpus compile (no -Dvibemod.mods.dir)");
            return;
        }
        Path modsDir = Path.of(configured);
        if (!Files.isDirectory(modsDir)) {
            System.out.println("SKIPPED: stored-corpus compile (no such directory: " + modsDir + ")");
            return;
        }
        compileCorpus(modsDir, "stored corpus", false);
    }

    /** Compiles every version of every mod under {@code modsDir}. Shared by both corpora. */
    private static void compileCorpus(Path modsDir, String label, boolean fixture) {
        ModStore store = new ModStore(modsDir);
        List<ModStore.StoredMod> mods = store.all();
        int versions = 0;
        int files = 0;
        List<String> broken = new java.util.ArrayList<>();
        List<String> names = new java.util.ArrayList<>();

        for (ModStore.StoredMod mod : mods) {
            names.add(mod.name());
            for (ModStore.StoredVersion version : mod.versions()) {
                Map<String, String> sources = store.sources(mod.name(), version.version());
                if (sources.isEmpty()) {
                    continue;
                }
                versions++;
                files += sources.size();
                String what = mod.name() + " v" + version.version();
                try {
                    var result = new InMemoryCompiler().compile(sources);
                    if (!result.success()) {
                        broken.add(what + ": " + result.diagnostics().lines().findFirst().orElse(""));
                    }
                } catch (RuntimeException e) {
                    broken.add(what + ": threw " + e);
                }
            }
        }

        // Naming the backend is not decoration: this is the gate that answers
        // "can ECJ build the real corpus" (ARCHITECTURE-V2 §7.3), and a run that
        // silently fell back to javac would answer the wrong question.
        String backend = com.gijsm.vibemod.platform.CompilerProvider.resolve()
                .map(com.gijsm.vibemod.platform.CompilerProvider::name).orElse("none");
        System.out.println("  " + label + ": " + mods.size() + " mods, " + versions + " versions, " + files
                + " sources from " + modsDir + " · backend " + backend);
        String requested = System.getProperty(
                com.gijsm.vibemod.platform.CompilerProvider.BACKEND_PROPERTY, "");
        if (!requested.isBlank()) {
            check("requested backend '" + requested + "' was actually used",
                    backend.startsWith(requested));
        }
        check(label + " has content", files > 0);
        check("every source in the " + label + " compiles against the sdk", broken.isEmpty());
        for (String failure : broken) {
            System.out.println("    broken: " + failure);
        }

        // The fixture is checked in, so its inventory is knowable and asserting on
        // it is what stops it rotting into "one empty directory that trivially
        // passes". The real corpus grows every time somebody types /vibe make, so
        // there is nothing to assert about its shape.
        if (fixture) {
            check("the fixture corpus still holds all three mods " + names,
                    names.equals(List.of("FixtureCanary", "FixtureClient", "FixtureLegacy")));
            check("FixtureCanary still has two versions on disk", versions == 4);
        }

        System.out.println("PASS: the whole " + label + " recompiles against the current api");
    }

    private static Path tempDir(String prefix) throws Exception {
        return Files.createTempDirectory(scratchRoot, prefix + "-");
    }

    private static String indent(String s) {
        if (s == null) {
            return "";
        }
        return "    " + s.replace("\n", "\n    ");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static boolean throwsIllegalArgument(ThrowingRunnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
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
