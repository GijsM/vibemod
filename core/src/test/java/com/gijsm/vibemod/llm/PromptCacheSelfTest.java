package com.gijsm.vibemod.llm;

import java.time.Duration;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Offline gate for the B4 prompt-caching wire shape.
 *
 * <p>The caching policy is a claim about other people's APIs, and the evidence
 * for it is a measurement that is already in the past
 * ({@link OpenRouterClient} carries the table). What this test protects is the
 * half that lives here: that the request body VibeMod actually builds is the
 * one the measurement was taken against. A breakpoint in the wrong place, or a
 * vendor quietly dropping out of the allowlist, is invisible until someone
 * reads a bill.
 *
 * <p>No network, no key, no cost: {@code buildBody} is package-private and this
 * test reads the JSON it produces.
 */
public final class PromptCacheSelfTest {

    private static int checks;
    private static int failures;

    private static final String SYSTEM = "SYSTEM PROMPT BODY";

    public static void main(String[] args) {
        automaticVendorsGetAPlainString();
        breakpointVendorsGetAContentBlockArray();
        secondBreakpointOnlyAppearsOnAHealRound();
        neverMoreThanTwoBreakpoints();
        theSystemTextIsUnchangedByTheWrapping();
        vendorParsingHandlesTheIdsWeActuallySend();

        System.out.println();
        if (failures > 0) {
            System.out.println("PromptCacheSelfTest: " + failures + " of " + checks + " checks FAILED");
            System.exit(1);
        }
        System.out.println("PromptCacheSelfTest: ALL " + checks + " CHECKS PASSED");
    }

    // ------------------------------------------------------------------

    /**
     * openai, deepseek, z-ai, moonshotai, mistralai and nvidia cache
     * automatically or not at all; a breakpoint measurably changes nothing on
     * them, so the body must stay exactly the plain-string shape that shipped
     * before B4. This is the no-regression half.
     */
    private static void automaticVendorsGetAPlainString() {
        for (String model : List.of("openai/gpt-5.6-luna", "deepseek/deepseek-v4-flash",
                "z-ai/glm-4.7-flash", "moonshotai/kimi-k2.5", "mistralai/ministral-8b-2512",
                "nvidia/nemotron-3.5-lightning")) {
            JsonObject sys = firstMessage(bodyFor(model, oneUserTurn()));
            check(model + ": system content is a plain string",
                    sys.get("content").isJsonPrimitive());
            check(model + ": system content is the prompt verbatim",
                    SYSTEM.equals(sys.get("content").getAsString()));
        }
    }

    /**
     * anthropic, google, x-ai and qwen cache nothing without an explicit
     * breakpoint — measured 0% versus 81-92%. They must get the content-block
     * array form, because that is the only shape {@code cache_control} is read
     * in.
     */
    private static void breakpointVendorsGetAContentBlockArray() {
        for (String model : List.of("anthropic/claude-opus-5", "google/gemini-3.7-flash",
                "x-ai/grok-4.3", "qwen/qwen3-max")) {
            JsonObject sys = firstMessage(bodyFor(model, oneUserTurn()));
            check(model + ": system content is a block array", sys.get("content").isJsonArray());
            JsonArray blocks = sys.getAsJsonArray("content");
            check(model + ": exactly one system block", blocks.size() == 1);
            JsonObject block = blocks.get(0).getAsJsonObject();
            check(model + ": block is type=text", "text".equals(block.get("type").getAsString()));
            check(model + ": block carries the prompt verbatim",
                    SYSTEM.equals(block.get("text").getAsString()));
            check(model + ": block is an ephemeral cache breakpoint",
                    block.has("cache_control")
                            && "ephemeral".equals(block.getAsJsonObject("cache_control")
                                    .get("type").getAsString()));
        }
    }

    /**
     * The second breakpoint exists to stop a self-heal round re-paying for the
     * whole generated project on every retry. It must NOT appear on a first
     * round, where it would write a cache entry covering a single short user
     * message and buy nothing.
     */
    private static void secondBreakpointOnlyAppearsOnAHealRound() {
        JsonArray first = messages(bodyFor("anthropic/claude-opus-5", oneUserTurn()));
        check("first round: only the system message is marked",
                countBreakpoints(first) == 1);
        check("first round: the user turn is a plain string",
                first.get(1).getAsJsonObject().get("content").isJsonPrimitive());

        JsonArray heal = messages(bodyFor("anthropic/claude-opus-5", healRound()));
        check("heal round: two breakpoints", countBreakpoints(heal) == 2);
        JsonObject last = heal.get(heal.size() - 1).getAsJsonObject();
        check("heal round: the breakpoint is on the LAST message",
                last.get("content").isJsonArray()
                        && last.getAsJsonArray("content").get(0).getAsJsonObject()
                                .has("cache_control"));
        check("heal round: the middle turns stay plain strings",
                heal.get(1).getAsJsonObject().get("content").isJsonPrimitive()
                        && heal.get(2).getAsJsonObject().get("content").isJsonPrimitive());
    }

    /**
     * Anthropic rejects a request carrying more than four breakpoints. We use
     * at most two by construction; this pins that so a future edit that starts
     * marking every turn fails here rather than at the provider.
     */
    private static void neverMoreThanTwoBreakpoints() {
        JsonArray longRun = messages(bodyFor("anthropic/claude-opus-5", longHealRound()));
        int n = countBreakpoints(longRun);
        check("a six-turn conversation still has at most 2 breakpoints (was " + n + ")", n <= 2);
        check("a six-turn conversation still has the system breakpoint", n == 2);
    }

    /**
     * Wrapping must not alter a single byte of the prompt. A stray trim or an
     * added newline here would silently invalidate every cached prefix and
     * break the {@code selfTestPromptSymbols} gate's premise that the rendered
     * text is what the model reads.
     */
    private static void theSystemTextIsUnchangedByTheWrapping() {
        String awkward = "line one\n  indented\ttab\n\"quoted\"\nlast line without newline";
        OpenRouterClient client = new OpenRouterClient("unused", "anthropic/claude-opus-5",
                Duration.ofSeconds(30));
        JsonObject body = client.buildBody(awkward, oneUserTurn(), false);
        String sent = messages(body).get(0).getAsJsonObject()
                .getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        check("the system prompt survives block-wrapping byte for byte", awkward.equals(sent));
    }

    /**
     * The policy keys off the vendor prefix of the model id. Real ids carry
     * variant suffixes ({@code :nitro}, {@code :batch}, {@code :free}) after
     * the slug, never before the slash, and the config file's own default is
     * one of them.
     */
    private static void vendorParsingHandlesTheIdsWeActuallySend() {
        check("openai/gpt-5.6-luna:nitro -> no breakpoint (automatic vendor)",
                !breakpointsFor("openai/gpt-5.6-luna:nitro"));
        check("anthropic/claude-sonnet-4.5:batch -> breakpoint",
                breakpointsFor("anthropic/claude-sonnet-4.5:batch"));
        check("ANTHROPIC/Claude-Opus-5 (odd case) -> breakpoint",
                breakpointsFor("ANTHROPIC/Claude-Opus-5"));
        check("a vendorless id -> no breakpoint, and no crash",
                !breakpointsFor("some-bare-model-id"));
        check("an unknown vendor -> no breakpoint (conservative default)",
                !breakpointsFor("brand-new-lab/their-first-model"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static List<OpenRouterClient.ChatMessage> oneUserTurn() {
        return List.of(new OpenRouterClient.ChatMessage("user", "Create a mod: something"));
    }

    private static List<OpenRouterClient.ChatMessage> healRound() {
        return List.of(new OpenRouterClient.ChatMessage("user", "Create a mod: something"),
                new OpenRouterClient.ChatMessage("assistant", "{\"name\":\"Demo\"}"),
                new OpenRouterClient.ChatMessage("user", "javac said: cannot find symbol"));
    }

    private static List<OpenRouterClient.ChatMessage> longHealRound() {
        return List.of(new OpenRouterClient.ChatMessage("user", "Create a mod: something"),
                new OpenRouterClient.ChatMessage("assistant", "{\"name\":\"Demo\"}"),
                new OpenRouterClient.ChatMessage("user", "javac said: cannot find symbol"),
                new OpenRouterClient.ChatMessage("assistant", "{\"edits\":[]}"),
                new OpenRouterClient.ChatMessage("user", "javac said: still broken"),
                new OpenRouterClient.ChatMessage("assistant", "{\"edits\":[]}"),
                new OpenRouterClient.ChatMessage("user", "javac said: and again"));
    }

    private static JsonObject bodyFor(String model, List<OpenRouterClient.ChatMessage> messages) {
        OpenRouterClient client = new OpenRouterClient("unused-key", model, Duration.ofSeconds(30));
        return client.buildBody(SYSTEM, messages, false);
    }

    private static boolean breakpointsFor(String model) {
        return new OpenRouterClient("unused-key", model, Duration.ofSeconds(30))
                .cacheBreakpointsActive();
    }

    private static JsonArray messages(JsonObject body) {
        return body.getAsJsonArray("messages");
    }

    private static JsonObject firstMessage(JsonObject body) {
        return messages(body).get(0).getAsJsonObject();
    }

    /** How many {@code cache_control} markers the whole message list carries. */
    private static int countBreakpoints(JsonArray msgs) {
        int found = 0;
        for (JsonElement m : msgs) {
            JsonElement content = m.getAsJsonObject().get("content");
            if (!content.isJsonArray()) {
                continue;
            }
            for (JsonElement block : content.getAsJsonArray()) {
                if (block.getAsJsonObject().has("cache_control")) {
                    found++;
                }
            }
        }
        return found;
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + what);
        } else {
            System.out.println("  ok    " + what);
        }
    }

    private PromptCacheSelfTest() {
    }
}
