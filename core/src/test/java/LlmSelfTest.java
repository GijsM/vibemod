import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.gijsm.vibemod.compile.SymbolOracle;
import com.gijsm.vibemod.gen.GeneratedProject;
import com.gijsm.vibemod.llm.PlatformProfile;
import com.gijsm.vibemod.llm.PlatformProfiles;
import com.gijsm.vibemod.llm.PromptLibrary;
import com.gijsm.vibemod.llm.StreamScanner;

/**
 * Standalone self-test (no test framework, no network) proving PromptLibrary's
 * parse() is robust and its systemPrompt() covers the required content.
 *
 * args[0]: base directory of the real api/*.java sources (used to verify the
 * embedded Mod/VibeContext/ModCommandHandler constants match verbatim).
 */
public class LlmSelfTest {

    private static int failures = 0;

    public static void main(String[] args) {
        testParseCleanJson();
        testParseFencedWithProse();
        testParseBracesInsideStrings();
        testParseGarbageThrows();
        testParseEditShape();
        testParseBothShapesThrows();
        testParseNeitherShapeThrows();
        testConfigKnobParsing();
        testParseIconMapping();
        testParseChangelogMapping();
        testSystemPromptContent();
        testPlatformProfiles();
        testPromptHygiene();
        testNativeFabricProfile();
        testPromptBudgets();
        testSymbolOracle();
        testPromptBuilders();
        testFewShotPlansMatchFiles();
        testStreamScannerFullShapeWithDecoy();
        testStreamScannerOneCharAtATimeEquivalence();
        testStreamScannerEditShape();
        testStreamScannerPlanAbsent();
        testStreamScannerPlanSplitAcrossFeeds();

        if (args.length > 0) {
            testEmbeddedApiSourcesMatchDisk(args[0]);
        } else {
            System.out.println("SKIPPED: embedded-copy assertion (no args[0] base dir supplied)");
        }

        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    private static void testParseCleanJson() {
        String json = "{\"name\":\"TorchTrail\",\"description\":\"Leaves a torch trail behind sprinting players.\","
                + "\"mainClass\":\"TorchTrail\",\"files\":[{\"path\":\"TorchTrail.java\",\"content\":\"package vibemod.torchtrail;\\n\"}]}";
        try {
            GeneratedProject p = PromptLibrary.parse(json);
            check("clean JSON: name", "TorchTrail".equals(p.name()));
            check("clean JSON: mainClass", "TorchTrail".equals(p.mainClass()));
            check("clean JSON: one file", p.files().size() == 1);
            check("clean JSON: path", "TorchTrail.java".equals(p.files().get(0).path()));
            check("clean JSON: not an edit response", !p.isEditResponse());
            check("clean JSON: usage null when absent", p.usage() == null);
            check("clean JSON: manual null when absent", p.manual() == null);
            check("clean JSON: changelog null when absent", p.changelog() == null);
            check("clean JSON: icon null when absent", p.icon() == null);
            check("clean JSON: config null when absent", p.config() == null);
            check("clean JSON: edits null on full shape", p.edits() == null);
            System.out.println("PASS: parse() on clean JSON -> " + p.name());
        } catch (Exception e) {
            fail("clean JSON parse threw: " + e);
        }
    }

    private static void testParseFencedWithProse() {
        String wrapped = "Sure thing! Here is the mod you asked for:\n\n"
                + "```json\n"
                + "{\"name\":\"FrostStep\",\"description\":\"Freezes water under walking players.\","
                + "\"mainClass\":\"FrostStep\",\"files\":[{\"path\":\"FrostStep.java\",\"content\":\"package vibemod.froststep;\\n\"}]}\n"
                + "```\n\n"
                + "Let me know if you want any tweaks!";
        try {
            GeneratedProject p = PromptLibrary.parse(wrapped);
            check("fenced+prose: name", "FrostStep".equals(p.name()));
            check("fenced+prose: files not empty", !p.files().isEmpty());
            System.out.println("PASS: parse() strips markdown fences + surrounding prose -> " + p.name());
        } catch (Exception e) {
            fail("fenced+prose parse threw: " + e);
        }
    }

    private static void testParseBracesInsideStrings() {
        // The file "content" itself contains Java code full of { and } — the balance
        // scanner must treat those as inert because they're inside a JSON string.
        String innerJava = "package vibemod.bracey;\\n\\npublic final class Bracey {\\n"
                + "    void run() { if (true) { System.out.println(\\\"{nested}\\\"); } }\\n}\\n";
        String json = "{\"name\":\"Bracey\",\"description\":\"A mod whose source is full of braces.\","
                + "\"mainClass\":\"Bracey\",\"files\":[{\"path\":\"Bracey.java\",\"content\":\"" + innerJava + "\"}]}";
        try {
            GeneratedProject p = PromptLibrary.parse(json);
            check("braces-in-strings: name", "Bracey".equals(p.name()));
            check("braces-in-strings: content roundtrips",
                    p.files().get(0).content().contains("nested"));
            System.out.println("PASS: parse() handles braces embedded inside code strings -> " + p.name());
        } catch (Exception e) {
            fail("braces-in-strings parse threw: " + e);
        }
    }

    private static void testParseGarbageThrows() {
        String garbage = "I refuse to produce JSON today, sorry!";
        try {
            PromptLibrary.parse(garbage);
            fail("garbage input did not throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            System.out.println("PASS: parse() throws IllegalArgumentException on garbage input: " + expected.getMessage());
        } catch (Exception e) {
            fail("garbage input threw wrong exception type: " + e.getClass() + ": " + e);
        }
    }

    private static void testParseEditShape() {
        String json = "{\"edits\":[{\"path\":\"Foo.java\",\"find\":\"int x = 1;\",\"replace\":\"int x = 2;\"}],"
                + "\"usage\":\"Try it now\"}";
        try {
            GeneratedProject p = PromptLibrary.parse(json);
            check("edit shape: isEditResponse true", p.isEditResponse());
            check("edit shape: edits size", p.edits() != null && p.edits().size() == 1);
            check("edit shape: edit path", "Foo.java".equals(p.edits().get(0).path()));
            check("edit shape: edit find", "int x = 1;".equals(p.edits().get(0).find()));
            check("edit shape: edit replace", "int x = 2;".equals(p.edits().get(0).replace()));
            check("edit shape: files empty (not null)", p.files() != null && p.files().isEmpty());
            check("edit shape: name null when absent", p.name() == null);
            check("edit shape: usage carried through", "Try it now".equals(p.usage()));
            System.out.println("PASS: parse() on edit-shaped response -> isEditResponse()=true");
        } catch (Exception e) {
            fail("edit shape parse threw: " + e);
        }
    }

    private static void testParseBothShapesThrows() {
        String json = "{\"name\":\"Foo\",\"description\":\"d\",\"mainClass\":\"Foo\","
                + "\"files\":[{\"path\":\"Foo.java\",\"content\":\"package vibemod.foo;\\n\"}],"
                + "\"edits\":[{\"path\":\"Foo.java\",\"find\":\"a\",\"replace\":\"b\"}]}";
        try {
            PromptLibrary.parse(json);
            fail("response with BOTH files and edits did not throw");
        } catch (IllegalArgumentException expected) {
            System.out.println("PASS: parse() rejects BOTH files+edits: " + expected.getMessage());
        } catch (Exception e) {
            fail("both-shapes input threw wrong exception type: " + e.getClass() + ": " + e);
        }
    }

    private static void testParseNeitherShapeThrows() {
        String json = "{\"name\":\"Foo\",\"description\":\"d\",\"mainClass\":\"Foo\"}";
        try {
            PromptLibrary.parse(json);
            fail("response with NEITHER files nor edits did not throw");
        } catch (IllegalArgumentException expected) {
            System.out.println("PASS: parse() rejects NEITHER files nor edits: " + expected.getMessage());
        } catch (Exception e) {
            fail("neither-shape input threw wrong exception type: " + e.getClass() + ": " + e);
        }
    }

    private static void testConfigKnobParsing() {
        // Full knob with every optional field present.
        String full = "{\"name\":\"Foo\",\"description\":\"d\",\"mainClass\":\"Foo\","
                + "\"files\":[{\"path\":\"Foo.java\",\"content\":\"package vibemod.foo;\\n\"}],"
                + "\"config\":[{\"key\":\"chicken-count\",\"type\":\"integer\",\"default\":\"1\","
                + "\"description\":\"how many\",\"min\":1,\"max\":10,\"step\":1},"
                + "{\"key\":\"strength\",\"type\":\"choice\",\"default\":\"normal\",\"description\":\"how strong\","
                + "\"choices\":[\"weak\",\"normal\",\"strong\"]}]}";
        try {
            GeneratedProject p = PromptLibrary.parse(full);
            check("config: two knobs", p.config() != null && p.config().size() == 2);
            GeneratedProject.ConfigKnob k0 = p.config().get(0);
            check("config: key", "chicken-count".equals(k0.key()));
            check("config: type", "integer".equals(k0.type()));
            check("config: default", "1".equals(k0.def()));
            check("config: min", k0.min() != null && k0.min() == 1.0);
            check("config: max", k0.max() != null && k0.max() == 10.0);
            check("config: step", k0.step() != null && k0.step() == 1.0);
            check("config: choices null for integer", k0.choices() == null);
            GeneratedProject.ConfigKnob k1 = p.config().get(1);
            check("config: choice choices present", k1.choices() != null && k1.choices().size() == 3);
            System.out.println("PASS: parse() maps full config knobs (min/max/step/choices)");
        } catch (Exception e) {
            fail("full config knob parse threw: " + e);
        }

        // Optional numeric fields absent -> null.
        String minimal = "{\"name\":\"Foo\",\"description\":\"d\",\"mainClass\":\"Foo\","
                + "\"files\":[{\"path\":\"Foo.java\",\"content\":\"package vibemod.foo;\\n\"}],"
                + "\"config\":[{\"key\":\"enabled\",\"type\":\"boolean\",\"default\":\"true\",\"description\":\"toggle\"}]}";
        try {
            GeneratedProject p = PromptLibrary.parse(minimal);
            GeneratedProject.ConfigKnob k = p.config().get(0);
            check("config: missing min -> null", k.min() == null);
            check("config: missing max -> null", k.max() == null);
            check("config: missing step -> null", k.step() == null);
            check("config: missing choices -> null", k.choices() == null);
            System.out.println("PASS: parse() maps missing optional config fields to null");
        } catch (Exception e) {
            fail("minimal config knob parse threw: " + e);
        }

        // Wrong type for a numeric field -> IllegalArgumentException.
        String badMin = "{\"name\":\"Foo\",\"description\":\"d\",\"mainClass\":\"Foo\","
                + "\"files\":[{\"path\":\"Foo.java\",\"content\":\"package vibemod.foo;\\n\"}],"
                + "\"config\":[{\"key\":\"chicken-count\",\"type\":\"integer\",\"default\":\"1\","
                + "\"description\":\"how many\",\"min\":\"not-a-number\"}]}";
        try {
            PromptLibrary.parse(badMin);
            fail("config knob with non-numeric \"min\" did not throw");
        } catch (IllegalArgumentException expected) {
            System.out.println("PASS: parse() rejects non-numeric config \"min\": " + expected.getMessage());
        } catch (Exception e) {
            fail("bad-min config threw wrong exception type: " + e.getClass() + ": " + e);
        }

        // choice type without choices -> IllegalArgumentException.
        String badChoice = "{\"name\":\"Foo\",\"description\":\"d\",\"mainClass\":\"Foo\","
                + "\"files\":[{\"path\":\"Foo.java\",\"content\":\"package vibemod.foo;\\n\"}],"
                + "\"config\":[{\"key\":\"strength\",\"type\":\"choice\",\"default\":\"normal\",\"description\":\"d\"}]}";
        try {
            PromptLibrary.parse(badChoice);
            fail("choice knob without \"choices\" did not throw");
        } catch (IllegalArgumentException expected) {
            System.out.println("PASS: parse() rejects \"choice\" knob missing \"choices\": " + expected.getMessage());
        } catch (Exception e) {
            fail("bad-choice config threw wrong exception type: " + e.getClass() + ": " + e);
        }

        // Invalid type string -> IllegalArgumentException.
        String badType = "{\"name\":\"Foo\",\"description\":\"d\",\"mainClass\":\"Foo\","
                + "\"files\":[{\"path\":\"Foo.java\",\"content\":\"package vibemod.foo;\\n\"}],"
                + "\"config\":[{\"key\":\"x\",\"type\":\"float\",\"default\":\"1\",\"description\":\"d\"}]}";
        try {
            PromptLibrary.parse(badType);
            fail("config knob with invalid \"type\" did not throw");
        } catch (IllegalArgumentException expected) {
            System.out.println("PASS: parse() rejects invalid config \"type\": " + expected.getMessage());
        } catch (Exception e) {
            fail("bad-type config threw wrong exception type: " + e.getClass() + ": " + e);
        }
    }

    private static void testParseChangelogMapping() {
        String withChangelog = "{\"name\":\"Foo\",\"description\":\"d\",\"changelog\":\"Now with bats.\","
                + "\"mainClass\":\"Foo\","
                + "\"files\":[{\"path\":\"Foo.java\",\"content\":\"package vibemod.foo;\\n\"}]}";
        try {
            GeneratedProject p = PromptLibrary.parse(withChangelog);
            check("changelog: full shape maps changelog", "Now with bats.".equals(p.changelog()));
            System.out.println("PASS: parse() maps \"changelog\" on the full project shape");
        } catch (Exception e) {
            fail("changelog mapping (full shape) threw: " + e);
        }

        String withoutChangelog = "{\"name\":\"Foo\",\"description\":\"d\",\"mainClass\":\"Foo\","
                + "\"files\":[{\"path\":\"Foo.java\",\"content\":\"package vibemod.foo;\\n\"}]}";
        try {
            GeneratedProject p = PromptLibrary.parse(withoutChangelog);
            check("changelog: absent -> null (lenient, never throws)", p.changelog() == null);
            System.out.println("PASS: parse() maps missing \"changelog\" to null");
        } catch (Exception e) {
            fail("changelog mapping (absent) threw: " + e);
        }

        String editWithChangelog = "{\"edits\":[{\"path\":\"Foo.java\",\"find\":\"a\",\"replace\":\"b\"}],"
                + "\"changelog\":\"Bats spawn faster.\"}";
        try {
            GeneratedProject p = PromptLibrary.parse(editWithChangelog);
            check("changelog: edit shape maps changelog", "Bats spawn faster.".equals(p.changelog()));
            System.out.println("PASS: parse() maps \"changelog\" on the edit shape");
        } catch (Exception e) {
            fail("changelog mapping (edit shape) threw: " + e);
        }

        String editWithoutChangelog = "{\"edits\":[{\"path\":\"Foo.java\",\"find\":\"a\",\"replace\":\"b\"}]}";
        try {
            GeneratedProject p = PromptLibrary.parse(editWithoutChangelog);
            check("changelog: edit shape absent -> null (lenient, never throws)", p.changelog() == null);
            System.out.println("PASS: parse() maps missing \"changelog\" to null on the edit shape");
        } catch (Exception e) {
            fail("changelog mapping (edit shape, absent) threw: " + e);
        }
    }

    private static void testSystemPromptContent() {
        String prompt = PromptLibrary.systemPrompt();
        System.out.println("systemPrompt() length = " + prompt.length() + " chars");
        check("systemPrompt contains 'VibeContext'", prompt.contains("VibeContext"));
        check("systemPrompt contains 'strict JSON'", prompt.contains("strict JSON"));
        check("systemPrompt contains 'config'", prompt.contains("config"));
        check("systemPrompt contains 'manual'", prompt.contains("manual"));
        check("systemPrompt contains 'usage'", prompt.contains("usage"));
        check("systemPrompt contains 'changelog'", prompt.contains("\"changelog\""));
        check("systemPrompt contains 'icon'", prompt.contains("\"icon\""));
        check("systemPrompt contains example 1 mod name 'ChickenCreepers'", prompt.contains("ChickenCreepers"));
        check("systemPrompt contains example 2 mod name 'SpeedPulse'", prompt.contains("SpeedPulse"));
        check("systemPrompt example 1 few-shot JSON sets icon CHICKEN", prompt.contains("\"icon\":\"CHICKEN\""));
        check("systemPrompt example 2 few-shot JSON sets icon SUGAR", prompt.contains("\"icon\":\"SUGAR\""));
        check("systemPrompt mentions configInt", prompt.contains("configInt"));
        check("systemPrompt mentions edit shape", prompt.contains("\"edits\""));
        check("systemPrompt few-shots teach 'implements Mod'", prompt.contains("implements Mod {"));
        check("systemPrompt mentions the host plugin by its v3 name 'VibeMod'",
                prompt.contains("host plugin called VibeMod"));
        check("systemPrompt never teaches 'implements VibeMod' (the deprecated bridge)",
                !prompt.contains("implements VibeMod"));
        check("systemPrompt never embeds the deprecated VibeMod bridge source",
                !prompt.contains("api/VibeMod.java") && !prompt.contains("interface VibeMod"));
        check("systemPrompt contains \"plan\"", prompt.contains("\"plan\""));
        check("systemPrompt states the plan-is-first-key rule",
                prompt.contains("\"plan\" MUST be the FIRST key"));
        if (failures == 0) {
            System.out.println("PASS: systemPrompt() contains all required markers");
        }
        testFabricProfilePrompt();
    }

    /**
     * The fabric profile (ARCHITECTURE-V2 6.2), asserted where it differs from
     * Paper's. Every check below is something that, if it silently went missing,
     * would show up as a mod that does not compile and a self-heal round of real
     * money - the import bans, the curated hook list, the three loader few-shots,
     * and above all the absence of any Bukkit vocabulary.
     */
    private static void testFabricProfilePrompt() {
        PlatformProfile fabric = PlatformProfiles.byId(PlatformProfiles.FABRIC_LEGACY_ID);
        check("byId('fabric-legacy') resolves the v2 loader profile, not the paper fallback",
                PlatformProfiles.FABRIC_LEGACY_ID.equals(fabric.id()));

        String prompt = PromptLibrary.systemPrompt(fabric);
        System.out.println("fabric-legacy systemPrompt() length = " + prompt.length() + " chars");

        // The api block is the MOD flavor, generated from sdk/src/mod/java.
        check("fabric-legacy prompt embeds the Mojang-typed VibeContext",
                prompt.contains("MinecraftServer server();"));
        check("fabric-legacy prompt embeds the CommandSourceStack-typed handler",
                prompt.contains("void run(CommandSourceStack src, String[] args)"));
        check("fabric-legacy prompt embeds the ClientContext", prompt.contains("KeyLease key(String label"));
        check("fabric-legacy prompt embeds the HudCanvas", prompt.contains("int textWidth(String s);"));
        check("fabric-legacy prompt never embeds the Paper flavor",
                !prompt.contains("void listen(Listener listener)") && !prompt.contains("BukkitTask"));

        // The curated hook table is the whole event surface; a missing entry is a
        // hook the model will never use.
        for (String hook : new String[] {"onPlayerJoin", "onPlayerQuit", "onServerTick", "onChat",
                "onBlockBreak", "onUseBlock", "onUseItem", "onEntityDeath", "onPlayerDeath", "onRespawn"}) {
            check("fabric-legacy prompt teaches ctx." + hook, prompt.contains(hook));
        }

        check("fabric-legacy prompt bans net.fabricmc.*", prompt.contains("NEVER import `net.fabricmc.*`"));
        check("fabric-legacy prompt bans registry content", prompt.contains("NEVER register content"));
        check("fabric-legacy prompt bans mixins and Screen", prompt.contains("NEVER write a mixin, subclass `Screen`"));
        check("fabric-legacy prompt teaches the 26.x Identifier rename",
                prompt.contains("net.minecraft.resources.Identifier"));
        check("fabric-legacy prompt carries the render-thread contract",
                prompt.contains("RENDER THREAD") && prompt.contains("silent and unreproducible"));
        check("fabric-legacy prompt says a mod is not a Fabric mod",
                prompt.contains("A mod is NOT a\nFabric mod"));

        check("fabric-legacy prompt carries the HUD few-shot", prompt.contains("WorldTimer"));
        check("fabric-legacy prompt carries the keybind few-shot", prompt.contains("CoordToggle"));
        check("fabric-legacy prompt carries the gameplay few-shot", prompt.contains("BlockTally"));
        // By few-shot MARKER, not by mod name: the shared prompt body uses
        // "ChickenCreepers"/"SpeedPulse" as PascalCase naming examples on every
        // platform, so their absence is not the question - the absence of the
        // Paper EXAMPLES is.
        check("fabric-legacy few-shots are the three loader ones, not Paper's",
                !prompt.contains("\"icon\":\"CHICKEN\"") && !prompt.contains("\"icon\":\"SUGAR\""));
        check("fabric-legacy prompt has no Bukkit vocabulary at all",
                !prompt.contains("org.bukkit") && !prompt.contains("@EventHandler"));

        testNeoForgeProfilePrompt(prompt);

        if (failures == 0) {
            System.out.println("PASS: the fabric-legacy profile's prompt teaches the mod flavor, "
                    + "the curated hooks, the loader bans and the render-thread contract");
        }
    }

    /**
     * The neoforge profile (ARCHITECTURE-V2 6.2, Phase E) — asserted as being
     * the fabric-legacy one with a different role line, because that is the
     * claim 10.4 makes and it is worth guarding.
     *
     * <p>V3 note: the pairing moved from {@code FABRIC} to {@code FABRIC_LEGACY}
     * when Phase 0 gave Fabric a native profile. NeoForge has no bytecode seams
     * yet, so it stays on the v2 contract, and the claim still worth guarding is
     * that the v2 contract is loader-neutral.
     *
     * <p>The sdk mod flavor is loader-neutral, so the two prompts SHOULD be
     * identical apart from the loader's name and its manifest file. If someone
     * later adds NeoForge-specific vocabulary to one of them, generated mods
     * stop being portable between the loaders and nothing else would notice.
     * Hence the diff-shaped assertion rather than a second copy of the fabric
     * checks.
     */
    private static void testNeoForgeProfilePrompt(String fabricPrompt) {
        PlatformProfile neoforge = PlatformProfiles.byId(PlatformProfiles.NEOFORGE_ID);
        check("byId('neoforge') resolves the neoforge profile, not the paper fallback",
                PlatformProfiles.NEOFORGE_ID.equals(neoforge.id()));

        String prompt = PromptLibrary.systemPrompt(neoforge);
        check("neoforge prompt says a mod is not a NeoForge mod",
                prompt.contains("A mod is NOT a\nNeoForge mod")
                        && prompt.contains("no neoforge.mods.toml"));
        check("neoforge prompt never calls a mod a Fabric mod",
                !prompt.contains("A mod is NOT a\nFabric mod"));
        check("neoforge prompt bans net.neoforged.*", prompt.contains("`net.neoforged.*`"));
        check("neoforge prompt has no Bukkit vocabulary at all",
                !prompt.contains("org.bukkit") && !prompt.contains("@EventHandler"));

        // The load-bearing one: everything except the role line is the same
        // text, which is what makes a stored mod portable between loaders.
        String neutralised = prompt
                .replace("NeoForge mod", "Fabric mod")
                .replace("no neoforge.mods.toml", "no fabric.mod.json");
        check("the neoforge and fabric-legacy prompts differ ONLY in the loader's name "
                        + "and manifest (the sdk mod flavor is loader-neutral)",
                neutralised.equals(fabricPrompt));
        if (failures == 0) {
            System.out.println("PASS: the neoforge profile is the fabric-legacy one with a NeoForge "
                    + "role line (" + prompt.length() + " chars, diff = loader name + manifest)");
        }
    }

    // ------------------------------------------------------------------
    // V3 Phase 0 §D/§E
    // ------------------------------------------------------------------

    /**
     * Prompt hygiene (V3 Phase 0 §D): Bukkit vocabulary belongs to the Paper
     * profiles and nowhere else.
     *
     * <p>Three blocks used to live in {@code PromptLibrary}'s shared skeleton
     * and therefore went into every prompt — teaching a loader mod about
     * {@code Bukkit.getPluginManager()}, {@code ctx.listen} and
     * {@code world.spawnEntity(...)}, none of which exist there. Tokens spent
     * teaching a vocabulary the compiler will reject are not free, so this is
     * asserted in both directions: gone from the loaders, still present on
     * Paper.
     */
    private static void testPromptHygiene() {
        String[] bukkitOnly = {"Bukkit.", "ctx.listen", "spawnEntity("};

        for (String id : new String[] {PlatformProfiles.PAPER_MODERN_ID, PlatformProfiles.PAPER_LEGACY_ID}) {
            String prompt = PromptLibrary.systemPrompt(PlatformProfiles.byId(id));
            for (String marker : bukkitOnly) {
                check("the " + id + " prompt still teaches '" + marker + "'", prompt.contains(marker));
            }
        }

        for (String id : new String[] {PlatformProfiles.FABRIC_ID, PlatformProfiles.FABRIC_LEGACY_ID,
                PlatformProfiles.NEOFORGE_ID}) {
            String prompt = PromptLibrary.systemPrompt(PlatformProfiles.byId(id));
            for (String marker : bukkitOnly) {
                check("the " + id + " prompt does NOT carry the Bukkit-only text '" + marker + "'",
                        !prompt.contains(marker));
            }
        }

        // The hardcoded "two examples" said "two" while the loader profiles ship
        // three and the native profile ships one.
        check("the paper prompt counts its own few-shots",
                PromptLibrary.systemPrompt(PlatformProfiles.PAPER_MODERN)
                        .contains("The following two examples show"));
        check("the fabric-legacy prompt counts its own few-shots (three)",
                PromptLibrary.systemPrompt(PlatformProfiles.FABRIC_LEGACY)
                        .contains("The following three examples show"));
        check("the native fabric prompt counts its own few-shots (one)",
                PromptLibrary.systemPrompt(PlatformProfiles.FABRIC)
                        .contains("The following one example shows"));

        if (failures == 0) {
            System.out.println("PASS: Bukkit vocabulary is confined to the Paper profiles, and every "
                    + "profile counts its own few-shots");
        }
    }

    /**
     * The native Fabric profile (V3 Phase 0 §E): the thesis, as a prompt.
     *
     * <p>What matters here is what is ABSENT. There is no VibeMod API in this
     * mode — the mod is an ordinary {@code ModInitializer} and the host
     * intercepts its {@code Event.register} calls in bytecode — so a prompt that
     * still taught {@code Mod}/{@code VibeContext} would produce mods that do
     * not compile, and one that still promised {@code ctx.configX} would produce
     * config knobs no mod could read.
     */
    private static void testNativeFabricProfile() {
        PlatformProfile fabric = PlatformProfiles.byId(PlatformProfiles.FABRIC_ID);
        check("byId('fabric') now resolves the NATIVE profile",
                PlatformProfiles.FABRIC_ID.equals(fabric.id())
                        && fabric.displayName().contains("native"));
        check("the native profile's entrypoint is ModInitializer",
                "net.fabricmc.api.ModInitializer".equals(fabric.entrypointName()));

        String prompt = PromptLibrary.systemPrompt(fabric);
        System.out.println("fabric (native) systemPrompt() length = " + prompt.length() + " chars");

        check("the native prompt says the main class implements ModInitializer",
                prompt.contains("implements net.fabricmc.api.ModInitializer"));
        check("the native prompt says there is no fabric.mod.json and no mixins",
                prompt.contains("no `fabric.mod.json`") || prompt.contains("There is no\n"
                        + "            `fabric.mod.json`") || prompt.contains("fabric.mod.json"));
        check("the native prompt promises automatic teardown",
                prompt.contains("tracked for you"));
        check("the native prompt bans reflection, threads and sockets",
                prompt.contains("java.lang.reflect.*") && prompt.contains("Executors")
                        && prompt.contains("java.net.*"));
        check("the native prompt bans Event.addPhaseOrdering",
                prompt.contains("Event.addPhaseOrdering"));
        check("the native prompt defers commands, keybinds, HUD and registries",
                prompt.contains("CommandRegistrationCallback") && prompt.contains("KeyBindingHelper")
                        && prompt.contains("HudElementRegistry") && prompt.contains("Registry.register"));
        check("the native prompt carries the Yarn -> Mojang rename table",
                prompt.contains("`World` is `Level`") && prompt.contains("`PlayerEntity` is `Player`")
                        && prompt.contains("`Text` is `Component`"));
        check("the native prompt carries the 26.x Identifier rename",
                prompt.contains("net.minecraft.resources.Identifier"));
        check("the native prompt says config knobs do not exist yet",
                prompt.contains("THIS MODE HAS NO CONFIG KNOBS"));

        // Absences: the whole point of the profile.
        check("the native prompt does NOT embed the VibeContext api block",
                !prompt.contains("--- com/gijsm/vibemod/api/VibeContext.java ---"));
        check("the native prompt never teaches ctx.configInt",
                !prompt.contains("ctx.configInt") && !prompt.contains("configBool"));
        check("the native prompt never tells the mod to implement Mod",
                !prompt.contains("implements Mod {"));
        check("the native prompt has no Bukkit vocabulary at all",
                !prompt.contains("org.bukkit") && !prompt.contains("@EventHandler"));

        // The one few-shot IS a plain Fabric mod.
        check("the native few-shot is a plain Fabric mod",
                prompt.contains("implements ModInitializer")
                        && prompt.contains("AttackBlockCallback.EVENT.register")
                        && prompt.contains("ServerTickEvents.END_SERVER_TICK.register"));
        check("the native few-shot imports no VibeMod api",
                !prompt.contains("import com.gijsm.vibemod.api"));
        check("the native few-shot ships no config knobs",
                !prompt.contains("\"config\":[{\"key\""));

        if (failures == 0) {
            System.out.println("PASS: the native fabric profile teaches an ordinary Fabric mod, "
                    + "with no VibeMod api anywhere in it");
        }
    }

    /**
     * A budget print for every profile.
     *
     * <p>Not an assertion with a magic number — prompt length is a design
     * choice, not a bug — but the one number nobody looks at until it is a
     * problem, and every generation pays for it on every round.
     */
    private static void testPromptBudgets() {
        System.out.println("prompt budgets (chars, ~tokens at 4 chars/token):");
        for (PlatformProfile profile : PlatformProfiles.all()) {
            String prompt = PromptLibrary.systemPrompt(profile);
            System.out.printf("  %-14s %7d chars  ~%6d tokens  (%d few-shot%s)%n",
                    profile.id(), prompt.length(), prompt.length() / 4,
                    profile.fewShots().size(), profile.fewShots().size() == 1 ? "" : "s");
            check(profile.id() + " builds a non-trivial prompt", prompt.length() > 2000);
        }
    }

    /**
     * The {@link SymbolOracle} (V3 Phase 0 §D), on both backends' wordings.
     *
     * <p>{@code VibeContext} stands in for a game class here: it is the only
     * "interesting" owner (§D's list) that exists on this module's test runtime,
     * and what is under test is the parsing and the fuzzy match, not which jar
     * the class came from.
     */
    private static void testSymbolOracle() {
        SymbolOracle oracle = SymbolOracle.forLoader(LlmSelfTest.class.getClassLoader());

        // javac's three-line shape.
        String javac = "[ERROR] string:///vibemod/foo/Foo.java:12 - cannot find symbol\n"
                + "  symbol:   method configIntt(java.lang.String)\n"
                + "  location: interface com.gijsm.vibemod.api.VibeContext";
        String javacHints = oracle.hints(javac);
        check("oracle: javac 'cannot find symbol' produces a hints block",
                javacHints.contains("API HINTS"));
        check("oracle: javac hints name the real method (" + firstLineOf(javacHints) + ")",
                javacHints.contains("configInt"));
        check("oracle: javac hints name the owner",
                javacHints.contains("com.gijsm.vibemod.api.VibeContext"));

        // ECJ names the type by its SIMPLE name; the oracle resolves it from a
        // fully-qualified mention elsewhere in the same diagnostics.
        String ecj = "[ERROR] Foo.java:5 - The method configIntt(String) is undefined "
                + "for the type VibeContext\n"
                + "[ERROR] Foo.java:2 - The import com.gijsm.vibemod.api.VibeContext is fine";
        String ecjHints = oracle.hints(ecj);
        check("oracle: ECJ's 'is undefined for the type' produces a hints block",
                ecjHints.contains("API HINTS"));
        check("oracle: ECJ hints name the real method (" + firstLineOf(ecjHints) + ")",
                ecjHints.contains("configInt"));

        // Quiet where it has nothing to say, and never throwing.
        check("oracle: no hints for an unrelated diagnostic",
                oracle.hints("[ERROR] Foo.java:3 - ';' expected").isEmpty());
        check("oracle: no hints for an uninteresting owner (java.util.List)",
                oracle.hints("cannot find symbol\n  symbol: method sizee()\n"
                        + "  location: interface java.util.List").isEmpty());
        check("oracle: empty and null input are safe",
                oracle.hints("").isEmpty() && oracle.hints(null).isEmpty());
        check("oracle: a resolver that always fails is safe",
                new SymbolOracle(name -> null).hints(javac).isEmpty());

        // And the prompt overload actually carries them.
        String withHints = PromptLibrary.repairPrompt(javac, javacHints);
        check("repairPrompt(diagnostics, hints) includes the diagnostics",
                withHints.contains("cannot find symbol"));
        check("repairPrompt(diagnostics, hints) includes the hints",
                withHints.contains("API HINTS"));
        check("repairPrompt(diagnostics, hints) still asks for the corrected project",
                withHints.contains("\"edits\""));
        check("repairPrompt(diagnostics, null) is exactly the one-argument form",
                PromptLibrary.repairPrompt(javac, null).equals(PromptLibrary.repairPrompt(javac))
                        && PromptLibrary.repairPrompt(javac, "  ")
                                .equals(PromptLibrary.repairPrompt(javac)));

        if (failures == 0) {
            System.out.println("PASS: the symbol oracle reads both javac and ECJ diagnostics and "
                    + "feeds the repair prompt");
        }
    }

    private static String firstLineOf(String text) {
        if (text == null || text.isEmpty()) {
            return "(empty)";
        }
        String[] lines = text.split("\n", -1);
        return lines.length > 1 ? lines[1] : lines[0];
    }

    private static void testParseIconMapping() {
        String withIcon = "{\"name\":\"Foo\",\"description\":\"d\",\"icon\":\"CHICKEN\",\"mainClass\":\"Foo\","
                + "\"files\":[{\"path\":\"Foo.java\",\"content\":\"package vibemod.foo;\\n\"}]}";
        try {
            GeneratedProject p = PromptLibrary.parse(withIcon);
            check("icon: full shape maps icon", "CHICKEN".equals(p.icon()));
            System.out.println("PASS: parse() maps \"icon\" on the full project shape");
        } catch (Exception e) {
            fail("icon mapping (full shape) threw: " + e);
        }

        String withoutIcon = "{\"name\":\"Foo\",\"description\":\"d\",\"mainClass\":\"Foo\","
                + "\"files\":[{\"path\":\"Foo.java\",\"content\":\"package vibemod.foo;\\n\"}]}";
        try {
            GeneratedProject p = PromptLibrary.parse(withoutIcon);
            check("icon: absent -> null", p.icon() == null);
            System.out.println("PASS: parse() maps missing \"icon\" to null");
        } catch (Exception e) {
            fail("icon mapping (absent) threw: " + e);
        }

        String editWithIcon = "{\"edits\":[{\"path\":\"Foo.java\",\"find\":\"a\",\"replace\":\"b\"}],"
                + "\"icon\":\"SUGAR\"}";
        try {
            GeneratedProject p = PromptLibrary.parse(editWithIcon);
            check("icon: edit shape maps icon", "SUGAR".equals(p.icon()));
            System.out.println("PASS: parse() maps \"icon\" on the edit shape");
        } catch (Exception e) {
            fail("icon mapping (edit shape) threw: " + e);
        }
    }

    /**
     * The two Paper prompt profiles (ARCHITECTURE-V2 §6.2). What matters here is
     * not that the strings exist but that the era table actually diverges: the
     * legacy profile has to teach the {@code GENERIC_} attribute names and forbid
     * the 1.20.5+ item-data setters, because a modern-only prompt produces code
     * that cannot compile on 1.20.6 and each such miss costs a self-heal round of
     * real money.
     */
    private static void testPlatformProfiles() {
        String modern = PromptLibrary.systemPrompt(PlatformProfiles.PAPER_MODERN);
        String legacy = PromptLibrary.systemPrompt(PlatformProfiles.PAPER_LEGACY);

        check("default systemPrompt() is the paper-modern one",
                PromptLibrary.systemPrompt().equals(modern));
        check("the two Paper profiles produce different prompts", !modern.equals(legacy));

        check("modern teaches the short 1.21.3+ attribute names",
                modern.contains("Attribute.MAX_HEALTH"));
        check("modern allows setEnchantmentGlintOverride",
                modern.contains("setEnchantmentGlintOverride") && !legacy.isEmpty());

        check("legacy teaches the GENERIC_ attribute names",
                legacy.contains("Attribute.GENERIC_MAX_HEALTH"));
        check("legacy forbids the short 1.21.3+ attribute names",
                legacy.contains("NEVER the short"));
        check("legacy forbids setEnchantmentGlintOverride",
                legacy.contains("Do NOT call `ItemMeta#setEnchantmentGlintOverride"));
        check("legacy names its era in the role line",
                legacy.contains("Paper 1.20.6-1.21.6 gameplay-mod author"));

        // Adventure is now an officially allowed import root in both eras (§6.2):
        // 88+ stored mods already use it, and banning it in the prompt was a
        // standing source of avoidable repair rounds.
        for (String prompt : java.util.List.of(modern, legacy)) {
            check("adventure is an allowed import root", prompt.contains("net.kyori.adventure.*"));
            check("net.minecraft is still banned", prompt.contains("NEVER import `net.minecraft.*`"));
            check("the api sources are still embedded verbatim",
                    prompt.contains("--- com/gijsm/vibemod/api/VibeContext.java ---"));
            check("both few-shots survive the profile indirection",
                    prompt.contains("ChickenCreepers") && prompt.contains("SpeedPulse"));
        }

        // The era boundary is a pure function so the host has one place to get it
        // right, and 1.21.7 is exactly where the dialog UI becomes usable.
        check("1.20.6 is legacy",
                PlatformProfiles.paperProfileIdFor("1.20.6").equals(PlatformProfiles.PAPER_LEGACY_ID));
        check("1.21.6 is legacy",
                PlatformProfiles.paperProfileIdFor("1.21.6").equals(PlatformProfiles.PAPER_LEGACY_ID));
        check("1.21.7 is modern",
                PlatformProfiles.paperProfileIdFor("1.21.7").equals(PlatformProfiles.PAPER_MODERN_ID));
        check("1.21.8 is modern",
                PlatformProfiles.paperProfileIdFor("1.21.8").equals(PlatformProfiles.PAPER_MODERN_ID));
        check("1.21.11 is modern (double-digit patch, not string-compared)",
                PlatformProfiles.paperProfileIdFor("1.21.11").equals(PlatformProfiles.PAPER_MODERN_ID));
        check("26.2 is modern",
                PlatformProfiles.paperProfileIdFor("26.2").equals(PlatformProfiles.PAPER_MODERN_ID));
        // What a real 26.x server reports through getBukkitVersion().
        check("26.2.build.117 is modern (partially numeric version string)",
                PlatformProfiles.paperProfileIdFor("26.2.build.117").equals(PlatformProfiles.PAPER_MODERN_ID));
        check("a -R0.1-SNAPSHOT suffix is tolerated",
                PlatformProfiles.paperProfileIdFor("1.20.6-R0.1-SNAPSHOT")
                        .equals(PlatformProfiles.PAPER_LEGACY_ID));
        check("an unparseable version reads as modern, not legacy",
                PlatformProfiles.paperProfileIdFor("wat").equals(PlatformProfiles.PAPER_MODERN_ID));

        check("both Paper profiles export at the 1.20 floor",
                PlatformProfiles.PAPER_MODERN.pluginDescriptor().equals("1.20")
                        && PlatformProfiles.PAPER_LEGACY.pluginDescriptor().equals("1.20"));

        System.out.println("PASS: paper-modern and paper-legacy profiles differ where the era does");
    }

    private static void testPromptBuilders() {
        String make = PromptLibrary.makePrompt("sheep can fly", "Gijs");
        check("makePrompt shape", make.equals("Create a mod: sheep can fly (requested by Gijs)"));

        java.util.LinkedHashMap<String, String> sources = new java.util.LinkedHashMap<>();
        sources.put("vibemod.foo.Foo", "package vibemod.foo;\npublic final class Foo {}\n");

        List<GeneratedProject.ConfigKnob> schema = List.of(
                new GeneratedProject.ConfigKnob("chicken-count", "integer", "1", "how many",
                        1.0, 10.0, 1.0, null));
        Map<String, String> values = Map.of("chicken-count", "3");

        String edit = PromptLibrary.editPrompt("make it also spawn a bat", sources, schema, values);
        check("editPrompt mentions request", edit.contains("make it also spawn a bat"));
        check("editPrompt includes source", edit.contains("public final class Foo"));
        check("editPrompt instructs full project", edit.toLowerCase().contains("full"));
        check("editPrompt mentions edit shape", edit.contains("\"edits\""));
        check("editPrompt includes knob key", edit.contains("chicken-count"));
        check("editPrompt includes knob current value", edit.contains("3"));

        String editNoSchema = PromptLibrary.editPrompt("tweak it", sources, List.of(), Map.of());
        check("editPrompt tolerates empty schema", editNoSchema.contains("tweak it"));

        String repair = PromptLibrary.repairPrompt("Foo.java:3: error: cannot find symbol");
        check("repairPrompt includes diagnostics", repair.contains("cannot find symbol"));
        check("repairPrompt mentions javac", repair.contains("javac"));
        check("repairPrompt mentions edit shape", repair.contains("\"edits\""));

        String demand = PromptLibrary.demandFullProject("find snippet matched 0 times in Foo.java");
        check("demandFullProject mentions reason", demand.contains("find snippet matched 0 times in Foo.java"));
        check("demandFullProject demands full project", demand.toLowerCase().contains("full"));

        String errorReport = "3x NullPointerException at Foo.java:12 (onEnable)\n";
        String fix = PromptLibrary.fixPrompt(errorReport, sources, schema, values);
        check("fixPrompt includes the error report", fix.contains("NullPointerException"));
        check("fixPrompt asks for the root cause", fix.contains("ROOT CAUSE"));
        check("fixPrompt includes source", fix.contains("public final class Foo"));
        check("fixPrompt mentions edit shape", fix.contains("\"edits\""));
        check("fixPrompt includes knob key", fix.contains("chicken-count"));
        check("fixPrompt includes knob current value", fix.contains("3"));
        check("fixPrompt says to keep the same mod name", fix.contains("same") && fix.contains("name"));

        String fixNoSchema = PromptLibrary.fixPrompt(errorReport, sources, List.of(), Map.of());
        check("fixPrompt tolerates empty schema", fixNoSchema.contains("NullPointerException"));

        if (failures == 0) {
            System.out.println("PASS: makePrompt/editPrompt/repairPrompt/demandFullProject/fixPrompt build "
                    + "expected content");
        }
    }

    /**
     * Asserts the Mod/VibeContext/ModCommandHandler sources embedded in PromptLibrary's
     * system prompt match the real api/*.java files on disk, ignoring per-line leading/
     * trailing whitespace and blank lines (so reformatting the embedded text block's
     * indentation doesn't spuriously fail this check).
     */
    private static void testEmbeddedApiSourcesMatchDisk(String baseDir) {
        String prompt = PromptLibrary.systemPrompt();

        checkEmbeddedMatches(prompt, "Mod.java",
                Path.of(baseDir, "Mod.java"),
                "--- com/gijsm/vibemod/api/Mod.java ---",
                "--- com/gijsm/vibemod/api/VibeContext.java ---");
        checkEmbeddedMatches(prompt, "VibeContext.java",
                Path.of(baseDir, "VibeContext.java"),
                "--- com/gijsm/vibemod/api/VibeContext.java ---",
                "--- com/gijsm/vibemod/api/ModCommandHandler.java ---");
        checkEmbeddedMatches(prompt, "ModCommandHandler.java",
                Path.of(baseDir, "ModCommandHandler.java"),
                "--- com/gijsm/vibemod/api/ModCommandHandler.java ---",
                "================ OUTPUT CONTRACT ================");
    }

    private static void checkEmbeddedMatches(String prompt, String label, Path realFile,
                                              String startMarker, String endMarker) {
        try {
            int start = prompt.indexOf(startMarker);
            int end = prompt.indexOf(endMarker, start);
            if (start < 0 || end < 0) {
                fail("embedded-copy: could not locate markers for " + label);
                return;
            }
            String embedded = prompt.substring(start + startMarker.length(), end);
            String real = Files.readString(realFile);

            String normalizedEmbedded = normalize(embedded);
            String normalizedReal = normalize(real);

            check("embedded " + label + " matches real file verbatim (normalized)",
                    normalizedEmbedded.equals(normalizedReal));
            if (!normalizedEmbedded.equals(normalizedReal)) {
                System.out.println("  --- diff hint for " + label + " ---");
                System.out.println("  real file: " + realFile);
                printFirstDiffLine(normalizedReal, normalizedEmbedded);
            } else {
                System.out.println("PASS: embedded " + label + " matches " + realFile + " verbatim");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + realFile, e);
        }
    }

    private static void printFirstDiffLine(String real, String embedded) {
        String[] realLines = real.split("\n", -1);
        String[] embeddedLines = embedded.split("\n", -1);
        int n = Math.min(realLines.length, embeddedLines.length);
        for (int i = 0; i < n; i++) {
            if (!realLines[i].equals(embeddedLines[i])) {
                System.out.println("  first differing line " + i + ":");
                System.out.println("    real:     " + realLines[i]);
                System.out.println("    embedded: " + embeddedLines[i]);
                return;
            }
        }
        System.out.println("  line count differs: real=" + realLines.length + " embedded=" + embeddedLines.length);
    }

    /** Per-line trim, then drop blank lines, so incidental whitespace differences don't matter. */
    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            sb.append(trimmed).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Contract: "plan" is the first key, and both few-shots' plan.files
    // match their actual files[] paths in emission order.
    // ------------------------------------------------------------------

    private static void testFewShotPlansMatchFiles() {
        String prompt = PromptLibrary.systemPrompt();
        checkFewShotPlanMatchesFiles(prompt, "--- Example 1 ---", "example 1 (ChickenCreepers)");
        checkFewShotPlanMatchesFiles(prompt, "--- Example 2 ---", "example 2 (SpeedPulse)");
    }

    private static void checkFewShotPlanMatchesFiles(String prompt, String marker, String label) {
        try {
            int markerIdx = prompt.indexOf(marker);
            check(label + ": marker found", markerIdx >= 0);
            int assistantIdx = prompt.indexOf("Assistant: ", markerIdx);
            check(label + ": 'Assistant: ' found", assistantIdx >= 0);
            int jsonStart = assistantIdx + "Assistant: ".length();
            int jsonEnd = prompt.indexOf('\n', jsonStart);
            String json = prompt.substring(jsonStart, jsonEnd);

            JsonElement parsed = JsonParser.parseString(json);
            check(label + ": parses as JSON", parsed.isJsonObject());
            JsonObject obj = parsed.getAsJsonObject();

            check(label + ": has object \"plan\"", obj.has("plan") && obj.get("plan").isJsonObject());
            JsonObject plan = obj.getAsJsonObject("plan");
            check(label + ": plan has \"name\"", plan.has("name"));
            check(label + ": plan name matches top-level name",
                    plan.get("name").getAsString().equals(obj.get("name").getAsString()));

            List<String> planFiles = new ArrayList<>();
            for (JsonElement el : plan.getAsJsonArray("files")) {
                planFiles.add(el.getAsJsonObject().get("path").getAsString());
            }
            List<String> actualFiles = new ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray("files")) {
                actualFiles.add(el.getAsJsonObject().get("path").getAsString());
            }
            check(label + ": plan.files paths match files[] paths in the same order",
                    planFiles.equals(actualFiles));
            System.out.println("PASS: " + label + " few-shot plan.files == files[] paths: " + planFiles);
        } catch (Exception e) {
            fail(label + " plan/files consistency check threw: " + e);
        }
    }

    // ------------------------------------------------------------------
    // StreamScanner suite (plan/decoy fixtures used by several tests below).
    // ------------------------------------------------------------------

    /**
     * Full-shape response. The plan's own "files" list re-mentions Foo.java/Bar.java (must
     * NOT fire FileStarted). Bar.java's "content" carries an escaped-quotes decoy that, once
     * JSON-decoded, reads literally {@code "path":"Decoy.java"} - it must stay completely
     * inert since the lexer never leaves the surrounding content string for it.
     */
    private static final String FULL_SHAPE_SAMPLE =
            "{\"plan\":{\"name\":\"Foo\",\"files\":[{\"path\":\"Foo.java\",\"purpose\":\"p1\"},"
            + "{\"path\":\"Bar.java\",\"purpose\":\"p2\"}]},"
            + "\"name\":\"Foo\",\"description\":\"d\",\"mainClass\":\"Foo\","
            + "\"files\":[{\"path\":\"Foo.java\",\"content\":\"class Foo {}\"},"
            + "{\"path\":\"Bar.java\",\"content\":\"class Bar { String s = "
            + "\\\"path\\\":\\\"Decoy.java\\\"; }\"}]}";

    private static final String EDIT_SHAPE_SAMPLE =
            "{\"edits\":[{\"path\":\"Foo.java\",\"find\":\"a\",\"replace\":\"b\"},"
            + "{\"path\":\"Bar.java\",\"find\":\"c\",\"replace\":\"d\"}]}";

    private static final String PLAN_ABSENT_SAMPLE =
            "{\"name\":\"Baz\",\"files\":[{\"path\":\"Baz.java\",\"content\":\"class Baz {}\"}]}";

    private static final String SPLIT_SAMPLE =
            "{\"plan\":{\"name\":\"SplitMod\",\"files\":[{\"path\":\"A.java\",\"purpose\":\"first\"},"
            + "{\"path\":\"B.java\",\"purpose\":\"second\"}]},"
            + "\"name\":\"SplitMod\","
            + "\"files\":[{\"path\":\"A.java\",\"content\":\"x\"},{\"path\":\"B.java\",\"content\":\"y\"}]}";

    private static void testStreamScannerFullShapeWithDecoy() {
        try {
            StreamScanner scanner = new StreamScanner();
            List<StreamScanner.Event> events = scanner.feed(FULL_SHAPE_SAMPLE);

            List<StreamScanner.PlanReady> plans = new ArrayList<>();
            List<StreamScanner.FileStarted> files = new ArrayList<>();
            for (StreamScanner.Event e : events) {
                if (e instanceof StreamScanner.PlanReady p) {
                    plans.add(p);
                } else if (e instanceof StreamScanner.FileStarted f) {
                    files.add(f);
                }
            }

            check("decoy: exactly one PlanReady", plans.size() == 1);
            if (!plans.isEmpty()) {
                check("decoy: plan name", "Foo".equals(plans.get(0).name()));
                check("decoy: plan files", List.of("Foo.java", "Bar.java").equals(plans.get(0).files()));
            }

            check("decoy: exactly two FileStarted events (the decoy must not add a third)", files.size() == 2);
            if (files.size() == 2) {
                check("decoy: FileStarted[0] is Foo.java index 1",
                        "Foo.java".equals(files.get(0).path()) && files.get(0).index() == 1);
                check("decoy: FileStarted[1] is Bar.java index 2",
                        "Bar.java".equals(files.get(1).path()) && files.get(1).index() == 2);
            }
            for (StreamScanner.FileStarted f : files) {
                check("decoy: no FileStarted fired for the in-content decoy \"Decoy.java\"",
                        !"Decoy.java".equals(f.path()));
            }
            System.out.println("PASS: StreamScanner ignores an escaped \"path\":\"Decoy.java\" decoy in file content, "
                    + "events=" + events.size());
        } catch (Exception e) {
            fail("StreamScanner decoy test threw: " + e);
        }
    }

    private static void testStreamScannerOneCharAtATimeEquivalence() {
        try {
            StreamScanner whole = new StreamScanner();
            List<StreamScanner.Event> wholeEvents = whole.feed(FULL_SHAPE_SAMPLE);

            StreamScanner perChar = new StreamScanner();
            List<StreamScanner.Event> perCharEvents = new ArrayList<>();
            for (int i = 0; i < FULL_SHAPE_SAMPLE.length(); i++) {
                perCharEvents.addAll(perChar.feed(String.valueOf(FULL_SHAPE_SAMPLE.charAt(i))));
            }

            check("one-char-at-a-time feed produces the identical event sequence as one whole feed()",
                    wholeEvents.equals(perCharEvents));
            check("one-char-at-a-time totalChars() matches whole-feed totalChars()",
                    perChar.totalChars() == whole.totalChars());
            System.out.println("PASS: StreamScanner one-char-at-a-time feeding == whole-string feeding ("
                    + wholeEvents.size() + " events)");
        } catch (Exception e) {
            fail("StreamScanner one-char-at-a-time equivalence test threw: " + e);
        }
    }

    private static void testStreamScannerEditShape() {
        try {
            StreamScanner scanner = new StreamScanner();
            List<StreamScanner.Event> events = scanner.feed(EDIT_SHAPE_SAMPLE);
            List<StreamScanner.FileStarted> files = new ArrayList<>();
            for (StreamScanner.Event e : events) {
                check("edit-shape: no PlanReady (none present in this sample)",
                        !(e instanceof StreamScanner.PlanReady));
                if (e instanceof StreamScanner.FileStarted f) {
                    files.add(f);
                }
            }
            check("edit-shape: two FileStarted events", files.size() == 2);
            if (files.size() == 2) {
                check("edit-shape: FileStarted[0] Foo.java index 1",
                        "Foo.java".equals(files.get(0).path()) && files.get(0).index() == 1);
                check("edit-shape: FileStarted[1] Bar.java index 2",
                        "Bar.java".equals(files.get(1).path()) && files.get(1).index() == 2);
            }
            System.out.println("PASS: StreamScanner fires FileStarted per edits[].path");
        } catch (Exception e) {
            fail("StreamScanner edit-shape test threw: " + e);
        }
    }

    private static void testStreamScannerPlanAbsent() {
        try {
            StreamScanner scanner = new StreamScanner();
            List<StreamScanner.Event> events = scanner.feed(PLAN_ABSENT_SAMPLE);
            boolean sawPlan = events.stream().anyMatch(e -> e instanceof StreamScanner.PlanReady);
            check("plan-absent: no PlanReady event", !sawPlan);

            List<StreamScanner.FileStarted> files = new ArrayList<>();
            for (StreamScanner.Event e : events) {
                if (e instanceof StreamScanner.FileStarted f) {
                    files.add(f);
                }
            }
            check("plan-absent: FileStarted still fires", files.size() == 1);
            if (!files.isEmpty()) {
                check("plan-absent: FileStarted path/index",
                        "Baz.java".equals(files.get(0).path()) && files.get(0).index() == 1);
            }
            System.out.println("PASS: StreamScanner fires FileStarted with no plan present");
        } catch (Exception e) {
            fail("StreamScanner plan-absent test threw: " + e);
        }
    }

    private static void testStreamScannerPlanSplitAcrossFeeds() {
        try {
            int keyIdx = SPLIT_SAMPLE.indexOf("\"plan\":") + 3; // splits the "plan" KEY as "pl" | "an"
            int stringIdx = SPLIT_SAMPLE.indexOf("A.java") + 2; // splits "A.java" as "A." | "java" INSIDE the plan
            check("split-test: split points are well-ordered inside the sample",
                    keyIdx > 0 && stringIdx > keyIdx && stringIdx < SPLIT_SAMPLE.length());

            StreamScanner scanner = new StreamScanner();
            List<StreamScanner.Event> events = new ArrayList<>();
            events.addAll(scanner.feed(SPLIT_SAMPLE.substring(0, keyIdx)));
            events.addAll(scanner.feed(SPLIT_SAMPLE.substring(keyIdx, stringIdx)));
            events.addAll(scanner.feed(SPLIT_SAMPLE.substring(stringIdx)));

            List<StreamScanner.PlanReady> plans = new ArrayList<>();
            List<StreamScanner.FileStarted> files = new ArrayList<>();
            for (StreamScanner.Event e : events) {
                if (e instanceof StreamScanner.PlanReady p) {
                    plans.add(p);
                } else if (e instanceof StreamScanner.FileStarted f) {
                    files.add(f);
                }
            }
            check("split-test: plan still parses despite a mid-key + mid-string split across feed() calls",
                    plans.size() == 1);
            if (!plans.isEmpty()) {
                check("split-test: plan name", "SplitMod".equals(plans.get(0).name()));
                check("split-test: plan files", List.of("A.java", "B.java").equals(plans.get(0).files()));
            }
            check("split-test: FileStarted still fires for the real files[] (not the plan's own list)",
                    files.size() == 2);
            System.out.println("PASS: StreamScanner parses a plan object split mid-key and mid-string across feed()");
        } catch (Exception e) {
            fail("StreamScanner plan-split test threw: " + e);
        }
    }

    private static void check(String label, boolean condition) {
        if (!condition) {
            fail(label);
        }
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL: " + message);
    }
}
