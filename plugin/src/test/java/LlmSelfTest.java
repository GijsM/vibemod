import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.gijsm.vibemine.gen.GeneratedProject;
import com.gijsm.vibemine.llm.PromptLibrary;

/**
 * Standalone self-test (no test framework, no network) proving PromptLibrary's
 * parse() is robust and its systemPrompt() covers the required content.
 *
 * args[0]: base directory of the real api/*.java sources (used to verify the
 * embedded VibeMod/VibeContext/ModCommandHandler constants match verbatim).
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
        testSystemPromptContent();
        testPromptBuilders();

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

    private static void testSystemPromptContent() {
        String prompt = PromptLibrary.systemPrompt();
        System.out.println("systemPrompt() length = " + prompt.length() + " chars");
        check("systemPrompt contains 'VibeContext'", prompt.contains("VibeContext"));
        check("systemPrompt contains 'strict JSON'", prompt.contains("strict JSON"));
        check("systemPrompt contains 'config'", prompt.contains("config"));
        check("systemPrompt contains 'manual'", prompt.contains("manual"));
        check("systemPrompt contains 'usage'", prompt.contains("usage"));
        check("systemPrompt contains 'icon'", prompt.contains("\"icon\""));
        check("systemPrompt contains example 1 mod name 'ChickenCreepers'", prompt.contains("ChickenCreepers"));
        check("systemPrompt contains example 2 mod name 'SpeedPulse'", prompt.contains("SpeedPulse"));
        check("systemPrompt example 1 few-shot JSON sets icon CHICKEN", prompt.contains("\"icon\":\"CHICKEN\""));
        check("systemPrompt example 2 few-shot JSON sets icon SUGAR", prompt.contains("\"icon\":\"SUGAR\""));
        check("systemPrompt mentions configInt", prompt.contains("configInt"));
        check("systemPrompt mentions edit shape", prompt.contains("\"edits\""));
        if (failures == 0) {
            System.out.println("PASS: systemPrompt() contains all required markers");
        }
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

        if (failures == 0) {
            System.out.println("PASS: makePrompt/editPrompt/repairPrompt/demandFullProject build expected content");
        }
    }

    /**
     * Asserts the VibeMod/VibeContext/ModCommandHandler sources embedded in PromptLibrary's
     * system prompt match the real api/*.java files on disk, ignoring per-line leading/
     * trailing whitespace and blank lines (so reformatting the embedded text block's
     * indentation doesn't spuriously fail this check).
     */
    private static void testEmbeddedApiSourcesMatchDisk(String baseDir) {
        String prompt = PromptLibrary.systemPrompt();

        checkEmbeddedMatches(prompt, "VibeMod.java",
                Path.of(baseDir, "VibeMod.java"),
                "--- com/gijsm/vibemine/api/VibeMod.java ---",
                "--- com/gijsm/vibemine/api/VibeContext.java ---");
        checkEmbeddedMatches(prompt, "VibeContext.java",
                Path.of(baseDir, "VibeContext.java"),
                "--- com/gijsm/vibemine/api/VibeContext.java ---",
                "--- com/gijsm/vibemine/api/ModCommandHandler.java ---");
        checkEmbeddedMatches(prompt, "ModCommandHandler.java",
                Path.of(baseDir, "ModCommandHandler.java"),
                "--- com/gijsm/vibemine/api/ModCommandHandler.java ---",
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
