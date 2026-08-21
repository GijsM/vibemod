import com.gijsm.vibemine.gen.GeneratedProject;
import com.gijsm.vibemine.llm.PromptLibrary;

/**
 * Standalone self-test (no test framework, no network) proving PromptLibrary's
 * parse() is robust and its systemPrompt() covers the required content.
 */
public class LlmSelfTest {

    private static int failures = 0;

    public static void main(String[] args) {
        testParseCleanJson();
        testParseFencedWithProse();
        testParseBracesInsideStrings();
        testParseGarbageThrows();
        testSystemPromptContent();
        testPromptBuilders();

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

    private static void testSystemPromptContent() {
        String prompt = PromptLibrary.systemPrompt();
        System.out.println("systemPrompt() length = " + prompt.length() + " chars");
        check("systemPrompt contains 'VibeContext'", prompt.contains("VibeContext"));
        check("systemPrompt contains 'strict JSON'", prompt.contains("strict JSON"));
        check("systemPrompt contains example 1 mod name 'ChickenCreepers'", prompt.contains("ChickenCreepers"));
        check("systemPrompt contains example 2 mod name 'SpeedPulse'", prompt.contains("SpeedPulse"));
        if (failures == 0) {
            System.out.println("PASS: systemPrompt() contains all required markers");
        }
    }

    private static void testPromptBuilders() {
        String make = PromptLibrary.makePrompt("sheep can fly", "Gijs");
        check("makePrompt shape", make.equals("Create a mod: sheep can fly (requested by Gijs)"));

        java.util.LinkedHashMap<String, String> sources = new java.util.LinkedHashMap<>();
        sources.put("vibemod.foo.Foo", "package vibemod.foo;\npublic final class Foo {}\n");
        String edit = PromptLibrary.editPrompt("make it also spawn a bat", sources);
        check("editPrompt mentions request", edit.contains("make it also spawn a bat"));
        check("editPrompt includes source", edit.contains("public final class Foo"));
        check("editPrompt instructs full project", edit.toLowerCase().contains("full"));

        String repair = PromptLibrary.repairPrompt("Foo.java:3: error: cannot find symbol");
        check("repairPrompt includes diagnostics", repair.contains("cannot find symbol"));
        check("repairPrompt mentions javac", repair.contains("javac"));

        if (failures == 0) {
            System.out.println("PASS: makePrompt/editPrompt/repairPrompt build expected content");
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
