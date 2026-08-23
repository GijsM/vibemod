package com.gijsm.vibemine.llm;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Incremental, char-at-a-time JSON lexer that watches a streaming LLM response for two
 * structural landmarks — the {@code "plan"} manifest and each file's {@code "path"} — without
 * ever buffering the full response. Every SSE delta can split ANYTHING (a key mid-name, a
 * string mid-escape, the plan object mid-brace), so every piece of lexer state lives in an
 * instance field and {@link #feed} simply resumes the same state machine wherever the last
 * call left off.
 *
 * <h2>What makes {@code "path"} inside generated Java source inert</h2>
 * The lexer only ever classifies a string as a <em>key</em> once that string's own closing
 * quote has been seen (i.e. it is not itself sitting inside an outer string) AND the next
 * non-whitespace character after it is {@code ':'}. A decoy like {@code "path":"Decoy.java"}
 * embedded inside a file's {@code "content"} value only appears in the wire bytes as an
 * <em>escaped</em> {@code \"path\":\"Decoy.java\"} — those backslash-escaped quotes never
 * close the surrounding content string, so the lexer never leaves {@code IN_STRING} for that
 * span and the decoy text is never seen as separate string literals at all, let alone keys.
 *
 * <h2>State machine</h2>
 * <ul>
 *   <li><b>IN_STRING + escape</b> — standard JSON string scanning: a backslash sets
 *       {@code escape} for exactly one character; an unescaped {@code '"'} closes the
 *       string.</li>
 *   <li><b>depth</b> — nesting depth, incremented on {@code '{'}/{@code '['} and decremented
 *       on {@code '}'}/{@code ']'}, tracked outside strings only. Used to require the
 *       {@code "plan"} key be a direct child of the root object (depth == 1) and to detect the
 *       plan value's balanced close.</li>
 *   <li><b>key classification</b> — every string's content is captured into a shared buffer
 *       (cap 64 chars, with an overflow flag once a string outgrows it — file contents blow
 *       past this almost immediately and are never fully copied). Once the string closes, the
 *       next non-whitespace character decides whether it was a key ({@code ':'}) or a value
 *       (anything else, e.g. {@code ','}/{@code '}'}). This works one character at a time: no
 *       lookahead beyond "the next character actually received" is ever needed.</li>
 *   <li><b>plan capture</b> — once a {@code "plan"} key is classified at depth 1 (and no plan
 *       has been captured yet) and its value's first non-whitespace token is {@code '{'}, the
 *       lexer switches into a separate raw-capture mode: every character (string-aware, so
 *       nested braces inside plan text stay balanced) is appended to a raw buffer until depth
 *       returns to the level it was at before the plan's own {@code '{'} — its balanced close.
 *       That raw text is then parsed with Gson; a malformed or ill-shaped plan is swallowed
 *       (no event, scanning continues) rather than failing the stream. Key classification is
 *       suspended for the whole span, which is exactly why {@code "path"} keys describing the
 *       plan's own file list never fire {@link FileStarted}.</li>
 *   <li><b>path capture</b> — a structural {@code "path"} key classified outside the plan span
 *       arms a one-shot "the next string is a path value" flag; that string is captured into a
 *       second buffer (cap 256) and fires {@link FileStarted} with a 1-based, ever-increasing
 *       counter the moment it closes. Works uniformly for {@code files[].path} and
 *       {@code edits[].path} since both are just structural {@code "path"} keys.</li>
 * </ul>
 */
public final class StreamScanner {

    private static final int KEY_BUFFER_CAP = 64;
    private static final int PATH_VALUE_CAP = 256;

    /** One landmark observed in the stream so far. */
    public sealed interface Event permits PlanReady, FileStarted {
    }

    /** The plan manifest parsed from the {@code "plan"} object, in file-emission order. */
    public record PlanReady(String name, List<String> files) implements Event {
    }

    /** A structural {@code "path"} value closed; {@code index} is 1-based and ever-increasing. */
    public record FileStarted(String path, int index) implements Event {
    }

    // ---- string scanning ----
    private boolean inString = false;
    private boolean escape = false;
    private int depth = 0;

    // ---- the one "current string" buffer, reused for key-candidates AND path values ----
    private final StringBuilder stringBuf = new StringBuilder();
    private int stringCap = KEY_BUFFER_CAP;
    private boolean overflow = false;

    // ---- deferred "what does the next token mean" flags ----
    private boolean awaitingColonDecision = false; // just closed a non-path-value string
    private boolean expectingPathValue = false;    // just classified a "path" key
    private boolean pendingPlanKey = false;        // just classified a "plan" key at depth 1

    // ---- "path" value capture ----
    private boolean capturingPathValue = false;
    private int fileCounter = 0;

    // ---- plan capture ----
    private boolean planCapturing = false;
    private boolean planDone = false;
    private int planCaptureStartDepth = 0;
    private final StringBuilder planRaw = new StringBuilder();

    private int totalChars = 0;

    /** Feeds the next chunk of raw response text; returns the events it produced, if any. */
    public List<Event> feed(String delta) {
        List<Event> out = new ArrayList<>();
        if (delta == null || delta.isEmpty()) {
            return out;
        }
        totalChars += delta.length();
        for (int i = 0; i < delta.length(); i++) {
            consume(delta.charAt(i), out);
        }
        return out;
    }

    /** Total number of characters ever passed to {@link #feed}. */
    public int totalChars() {
        return totalChars;
    }

    private void consume(char c, List<Event> out) {
        if (planCapturing) {
            consumePlanCapture(c, out);
            return;
        }
        if (inString) {
            consumeInString(c, out);
            return;
        }
        if (awaitingColonDecision) {
            if (Character.isWhitespace(c)) {
                return; // keep waiting for the first significant char after the closed string
            }
            awaitingColonDecision = false;
            resolveKeyClassification(c);
            if (c == ':') {
                // The colon itself never has structural meaning (never a brace/bracket/quote);
                // returning here avoids the abandon-checks below misfiring on the very colon
                // that just armed pendingPlanKey/expectingPathValue, before the real value
                // token (e.g. the plan's opening '{') has even arrived.
                return;
            }
            // c itself still needs its normal structural handling below (e.g. it may be '{').
        }
        consumeStructural(c, out);
    }

    /** Applies once a closed string's classification (key vs. value) is known. */
    private void resolveKeyClassification(char nextSignificantChar) {
        if (nextSignificantChar != ':') {
            return; // it was a value, not a key - nothing to classify
        }
        if (overflow) {
            return; // longer than either keyword could ever be
        }
        String key = stringBuf.toString();
        if ("path".equals(key)) {
            expectingPathValue = true;
        } else if ("plan".equals(key) && depth == 1 && !planDone) {
            pendingPlanKey = true;
        }
    }

    private void consumeStructural(char c, List<Event> out) {
        // A pending "the next token must be X" expectation is abandoned by anything else
        // (other than whitespace, which we keep waiting through).
        if (pendingPlanKey && c != '{' && !Character.isWhitespace(c)) {
            pendingPlanKey = false;
        }
        if (expectingPathValue && c != '"' && !Character.isWhitespace(c)) {
            expectingPathValue = false;
        }

        switch (c) {
            case '"' -> {
                inString = true;
                stringBuf.setLength(0);
                overflow = false;
                if (expectingPathValue) {
                    capturingPathValue = true;
                    stringCap = PATH_VALUE_CAP;
                    expectingPathValue = false;
                } else {
                    capturingPathValue = false;
                    stringCap = KEY_BUFFER_CAP;
                }
            }
            case '{', '[' -> {
                depth++;
                if (c == '{' && pendingPlanKey) {
                    pendingPlanKey = false;
                    planCapturing = true;
                    planCaptureStartDepth = depth;
                    planRaw.setLength(0);
                    planRaw.append(c);
                }
            }
            case '}', ']' -> depth--;
            default -> { /* whitespace, ',', ':', literals - no structural effect */ }
        }
    }

    private void consumeInString(char c, List<Event> out) {
        if (escape) {
            escape = false;
            appendCapped(c);
            return;
        }
        if (c == '\\') {
            escape = true;
            return;
        }
        if (c == '"') {
            inString = false;
            if (capturingPathValue) {
                fileCounter++;
                out.add(new FileStarted(stringBuf.toString(), fileCounter));
                capturingPathValue = false;
            } else {
                awaitingColonDecision = true;
            }
            return;
        }
        appendCapped(c);
    }

    private void appendCapped(char c) {
        if (stringBuf.length() < stringCap) {
            stringBuf.append(c);
        } else {
            overflow = true;
        }
    }

    /** String-aware raw capture of the plan value, from its opening '{' to its balanced close. */
    private void consumePlanCapture(char c, List<Event> out) {
        planRaw.append(c);
        if (inString) {
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                inString = false;
            }
            return;
        }
        if (c == '"') {
            inString = true;
        } else if (c == '{' || c == '[') {
            depth++;
        } else if (c == '}' || c == ']') {
            depth--;
            if (depth == planCaptureStartDepth - 1) {
                planCapturing = false;
                planDone = true;
                tryParsePlan(planRaw.toString(), out);
            }
        }
    }

    /** Gson-parses a captured plan object; any failure or shape mismatch is swallowed. */
    private static void tryParsePlan(String raw, List<Event> out) {
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return;
            }
            JsonObject obj = parsed.getAsJsonObject();
            if (!obj.has("name") || !obj.get("name").isJsonPrimitive()) {
                return;
            }
            String name = obj.get("name").getAsString();

            List<String> files = new ArrayList<>();
            if (obj.has("files") && obj.get("files").isJsonArray()) {
                JsonArray filesArray = obj.getAsJsonArray("files");
                for (JsonElement el : filesArray) {
                    if (el.isJsonObject()) {
                        JsonObject fileObj = el.getAsJsonObject();
                        if (fileObj.has("path") && fileObj.get("path").isJsonPrimitive()) {
                            files.add(fileObj.get("path").getAsString());
                        }
                    }
                }
            }
            out.add(new PlanReady(name, files));
        } catch (RuntimeException malformed) {
            // Swallow: an unparsable or ill-shaped plan simply produces no PlanReady event.
        }
    }
}
