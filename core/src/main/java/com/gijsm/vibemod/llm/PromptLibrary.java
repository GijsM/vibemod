package com.gijsm.vibemod.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.gijsm.vibemod.gen.GeneratedProject;

/**
 * Builds the prompts sent to the LLM and parses its responses back into a
 * {@link GeneratedProject}. This is the "soul" of VibeMod: the quality of the
 * system prompt directly determines how good generated mods are.
 *
 * <p>v2 adds per-mod config knobs (usage/manual/config in the output contract,
 * with a hard rule that mods read them live via {@code ctx.configX} rather than
 * caching them) and a lightweight edit-response shape ({@code {"edits":[...]}})
 * that edit/repair rounds may use instead of resending the full project.
 *
 * <p>v2.0 makes the platform-specific half of the prompt data: a
 * {@link PlatformProfile} supplies the role line, the verbatim sdk sources, the
 * import rules, the era cheat sheet, the threading contract, the icon rule and
 * the few-shots, and {@link #systemPrompt(PlatformProfile)} slots them into one
 * skeleton shared by every platform (ARCHITECTURE-V2 §6). The four user-message
 * builders below stay profile-free: nothing in "Create a mod: X", the current
 * sources, the knob table or a javac diagnostic differs per platform, so
 * threading a profile through them would be a parameter nobody reads.
 */
public final class PromptLibrary {

    private PromptLibrary() {
    }

    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Z][A-Za-z0-9]{1,31}$");
    private static final Set<String> VALID_KNOB_TYPES = Set.of("boolean", "integer", "decimal", "text", "choice");

    // ------------------------------------------------------------------
    // The prompt is assembled from a fixed skeleton plus the running platform's
    // PlatformProfile (ARCHITECTURE-V2 §6.1): the role line, the verbatim api
    // sources, the import rules, the era cheat sheet, the threading contract,
    // the icon rule and the few-shots all come from the profile, and everything
    // between them is shared by every platform.
    //
    // The api sources inside the profile are NOT hand-copied: GeneratedApiSources
    // is emitted at build time straight from the sdk module's real files by the
    // `:core:generatePromptSources` task (§6.4), so prompt and api cannot drift
    // apart. LlmSelfTest still asserts the match on disk.
    // ------------------------------------------------------------------

    /**
     * The full system prompt sent with every generation/edit/repair call, for
     * the platform the host is actually running.
     */
    public static String systemPrompt(PlatformProfile profile) {
        StringBuilder sb = new StringBuilder();

        sb.append(profile.roleLine());
        if (!profile.roleLine().endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("""

                ================ FROZEN API (verbatim source, do not deviate) ================

                """);

        sb.append(profile.apiSourceBlock());

        sb.append("""
                ================ OUTPUT CONTRACT ================

                You respond with strict JSON only. No markdown code fences, no prose before or
                after, no explanations, nothing but a single JSON object with exactly this shape:

                {
                  "plan": {"name": "PascalCaseShortName",
                           "files": [{"path": "SimpleClassName.java", "purpose": "one-line purpose"}]},
                  "name": "PascalCaseShortName",
                  "description": "One sentence describing what the mod does.",
                  "usage": "One-line \\"try this\\" hint, e.g. Kill a creeper and watch",
                  "manual": "Markdown player manual (see the manual rules below). Mention every config knob.",
                  "changelog": "One short player-facing line describing what this version changes.",
                  "icon": "CHICKEN",
                  "mainClass": "SimpleClassName",
                  "files": [
                    {"path": "SimpleClassName.java", "content": "full file source as a string"}
                  ],
                  "config": [
                    {"key": "some-knob", "type": "integer", "default": "1",
                     "description": "What it controls.", "min": 1, "max": 10, "step": 1}
                  ]
                }

                Rules for the JSON itself:
                - "plan" MUST be the FIRST key in the object, before every other field. It is a
                  short manifest of what you are about to write: {"name": same PascalCase name as
                  below, "files": [{"path", "purpose"}, ...]} listing every file you will emit, in
                  the exact order you will emit them in "files" below, each with a one-line
                  "purpose". Emit "plan" first so a listener can show the mod's name and file list
                  before the rest of the response has even arrived.
                - "name" is PascalCase, starts with an uppercase letter, letters/digits only,
                  2 to 32 characters (e.g. "ChickenCreepers", "SpeedPulse", "LavaFloor").
                - "usage" is one short line describing the quickest way for a player to see the
                  mod in action.
                - "manual" is player-facing Markdown: 4 to 10 sentences of prose overall, using
                  EXACTLY this Markdown subset and nothing else: "## " and "### " line-start
                  headings, "- " bullet lines, blank lines between paragraphs, and inline
                  **bold**, *italic*, and `code`. No links, images, tables, code fences, or any
                  other Markdown. If the mod has any config knobs, the manual MUST mention what
                  each one does and its default (knob keys read best as `code`).
                - "changelog" is REQUIRED: ONE short player-facing plain-text line (roughly 100
                  characters at most, no Markdown) saying what this version changes. For a
                  brand-new mod it describes what the mod does.
                """);

        sb.append(profile.iconInstruction());
        if (!profile.iconInstruction().endsWith("\n")) {
            sb.append('\n');
        }

        sb.append("""
                - "mainClass" is the simple (no package) name of the one public class that
                  implements ENTRYPOINT, and must have a public no-arg constructor.
                - Every entry in "files" has a "path" ending in ".java" and "content" holding the
                  complete, compilable source of that file (proper escaping of quotes/newlines
                  since this is a JSON string).
                - ALL files declare `package vibemod.<name lowercased>;` at the top (the mod name
                  from the JSON, lowercased, no dots, no dashes).
                - Exactly one public class across all files implements ENTRYPOINT.
                - "config" is an array of tunable knobs, one object per knob:
                  {"key", "type", "default", "description", "min"?, "max"?, "step"?, "choices"?}.
                  "type" is one of boolean | integer | decimal | text | choice. "default" is
                  always a JSON string (e.g. "1", "true", "normal"), even for numeric/boolean
                  types. "min"/"max"/"step" are numbers and only apply to integer/decimal knobs.
                  "choices" is a JSON array of strings and is REQUIRED (non-empty) for the choice
                  type. Omit "config" entirely (or send an empty array) only when the mod truly
                  has nothing worth tuning.
                - Do not wrap the JSON in ```json fences or add any commentary. The response body
                  must be parseable as JSON from the first character to the last.

                ================ HARD RULES ================

                """.replace("ENTRYPOINT", profile.entrypointName()));

        sb.append(profile.importRules());
        sb.append('\n');

        sb.append(profile.threadingContract());
        sb.append('\n');

        sb.append("""
                - Be defensive: null-check worlds, entities, and players before using them; use
                  `instanceof` checks before casting entities to more specific types; guard against
                  players being offline/dead when tasks fire later.
                """);

        sb.append(profile.cheatSheet());
        sb.append('\n');

        sb.append("""
                - Keep each file under roughly 150 lines. Split into a couple of small classes if a
                  single file would run long; every class still lives in the same
                  `vibemod.<name>` package.
                """);

        sb.append(profile.configContract());
        sb.append('\n');

        sb.append("""
                ================ EDIT RESPONSE SHAPE (edit/repair rounds only) ================

                Initial generation of a brand-new mod always uses the full JSON shape above. On an
                EDIT or REPAIR round, when you are shown the current sources and the requested
                change is small and surgical, you MAY instead respond with:

                {
                  "plan": {"name": "SameModName", "files": [{"path": "TouchedFile.java", "purpose": "what changes"}]},
                  "edits": [
                    {"path": "SimpleClassName.java",
                     "find": "<exact snippet from the file's CURRENT source>",
                     "replace": "<new snippet>"}
                  ],
                  "usage": "optional — include only if it changed",
                  "manual": "optional — include only if it changed",
                  "changelog": "REQUIRED — one short line describing what this edit changes",
                  "config": [ "optional — include the FULL updated knob list only if any knob changed" ]
                }

                Rules for the edit shape:
                - "plan" is again the FIRST key, same rule as above: {"name", "files":[{"path",
                  "purpose"}]} but here "files" lists only the file(s) this edit actually touches,
                  in "edits" emission order.
                - A response must contain either "files" (full project) or "edits" (edit shape),
                  never both and never neither.
                - Each edit's "find" must match the target file's current source EXACTLY ONCE,
                  including whitespace and indentation. If the change cannot be expressed as one
                  or more such unambiguous snippets, respond with the full project shape instead.
                - Omitting "usage"/"manual"/"config" means "unchanged". "changelog" is NOT covered
                  by that rule: it is REQUIRED on every edit response, same one-short-line format
                  as the full shape. An included "manual" uses the same Markdown subset as the
                  full shape. When you do include "config", send the complete knob list the mod
                  should have afterward — not a diff of just the knobs that changed.
                - If you are told your previous edits did not apply cleanly, respond with the FULL
                  project shape (complete files) next, not another edit.

                ================ WORKED EXAMPLES ================

                """);

        List<PlatformProfile.FewShot> fewShots = profile.fewShots();
        // The count was hardcoded as "two" while the loader profiles ship three
        // and the native Fabric profile ships one — a small lie in the prompt is
        // still a lie the model has to reconcile with what it can see.
        sb.append("The following ").append(numberWord(fewShots.size()))
                .append(fewShots.size() == 1 ? " example shows" : " examples show")
                .append(" the exact expected input/output shape.\n")
                .append("Study the JSON formatting (escaped newlines and quotes inside \"content\") ")
                .append("as closely as the Java itself.\n\n");
        for (int i = 0; i < fewShots.size(); i++) {
            sb.append("--- Example ").append(i + 1).append(" ---\n");
            sb.append("User: ").append(fewShots.get(i).user()).append('\n');
            sb.append("Assistant: ").append(fewShots.get(i).assistant()).append('\n');
        }

        sb.append("""

                ================ END OF INSTRUCTIONS ================

                Now respond to the user's request the same way: strict JSON only, matching the
                contract above exactly.
                """);

        return sb.toString();
    }

    /** Small numbers as words, so the worked-examples preamble reads like English. */
    private static String numberWord(int n) {
        return switch (n) {
            case 0 -> "no";
            case 1 -> "one";
            case 2 -> "two";
            case 3 -> "three";
            case 4 -> "four";
            case 5 -> "five";
            default -> String.valueOf(n);
        };
    }

    /**
     * The Paper 1.21.7+ prompt. Kept as the no-argument default because the
     * whole stored corpus and every self-test assertion were written against
     * it; a host always passes its own profile explicitly.
     */
    public static String systemPrompt() {
        return systemPrompt(PlatformProfiles.PAPER_MODERN);
    }

    /** Prompt for a brand-new mod. Always answered with the full project shape. */
    public static String makePrompt(String request, String creator) {
        return "Create a mod: " + request + " (requested by " + creator + ")";
    }

    /**
     * Prompt for editing an existing mod's sources. Includes the current config schema
     * and live values so an edit can preserve or extend the knob list, and reminds the
     * model it may answer with either the full project shape or the edit shape.
     */
    public static String editPrompt(String request, Map<String, String> currentSources,
                                     List<GeneratedProject.ConfigKnob> schema, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("Edit the existing mod: ").append(request).append("\n\n");
        sb.append("Here are the current project sources:\n\n");
        for (Map.Entry<String, String> entry : currentSources.entrySet()) {
            sb.append("--- ").append(entry.getKey()).append(" ---\n");
            sb.append(entry.getValue());
            if (!entry.getValue().endsWith("\n")) {
                sb.append('\n');
            }
            sb.append('\n');
        }

        if (schema != null && !schema.isEmpty()) {
            sb.append("Here are the mod's current config knobs and their live values:\n\n");
            for (GeneratedProject.ConfigKnob knob : schema) {
                String currentValue = values == null ? null : values.get(knob.key());
                sb.append("- ").append(knob.key())
                        .append(" (").append(knob.type()).append(", default=").append(knob.def()).append(")");
                if (knob.min() != null) {
                    sb.append(" min=").append(knob.min());
                }
                if (knob.max() != null) {
                    sb.append(" max=").append(knob.max());
                }
                if (knob.step() != null) {
                    sb.append(" step=").append(knob.step());
                }
                if (knob.choices() != null) {
                    sb.append(" choices=").append(knob.choices());
                }
                sb.append(" - ").append(knob.description());
                sb.append(" (current value: ")
                        .append(currentValue != null ? currentValue : knob.def())
                        .append(")\n");
            }
            sb.append('\n');
        }

        sb.append("You may respond with either shape described in the system prompt: the FULL ")
                .append("project (every file, not just the ones that changed) if the change is broad, ")
                .append("or the EDIT shape ({\"edits\":[...]}) for small, surgical changes whose \"find\" ")
                .append("matches the current source of that file exactly once. Keep the same \"name\" ")
                .append("unless the request explicitly asks you to rename the mod. If you add, remove, or ")
                .append("change any config knob, include the full updated \"config\" array reflecting every ")
                .append("knob the mod should have afterward; otherwise leave \"config\" out to keep the ")
                .append("existing knobs unchanged.");
        return sb.toString();
    }

    /**
     * Prompt asking the model to fix a mod that is throwing at runtime (a "degraded"
     * mod, as opposed to one that failed to compile — see {@link #repairPrompt}).
     * Includes the current config schema and live values so a fix can preserve or
     * extend the knob list, and reminds the model it may answer with either the full
     * project shape or the edit shape.
     */
    public static String fixPrompt(String errorReport, Map<String, String> currentSources,
                                    List<GeneratedProject.ConfigKnob> schema, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("This mod throws at runtime. Most recent distinct errors with occurrence counts:\n");
        sb.append(errorReport);
        if (errorReport == null || !errorReport.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("\nFix the ROOT CAUSE. Keep all other behavior identical. Keep the same mod name and ")
                .append("knobs unless the fix requires changing them.\n\n");

        sb.append("Here are the current project sources:\n\n");
        for (Map.Entry<String, String> entry : currentSources.entrySet()) {
            sb.append("--- ").append(entry.getKey()).append(" ---\n");
            sb.append(entry.getValue());
            if (!entry.getValue().endsWith("\n")) {
                sb.append('\n');
            }
            sb.append('\n');
        }

        if (schema != null && !schema.isEmpty()) {
            sb.append("Here are the mod's current config knobs and their live values:\n\n");
            for (GeneratedProject.ConfigKnob knob : schema) {
                String currentValue = values == null ? null : values.get(knob.key());
                sb.append("- ").append(knob.key())
                        .append(" (").append(knob.type()).append(", default=").append(knob.def()).append(")");
                if (knob.min() != null) {
                    sb.append(" min=").append(knob.min());
                }
                if (knob.max() != null) {
                    sb.append(" max=").append(knob.max());
                }
                if (knob.step() != null) {
                    sb.append(" step=").append(knob.step());
                }
                if (knob.choices() != null) {
                    sb.append(" choices=").append(knob.choices());
                }
                sb.append(" - ").append(knob.description());
                sb.append(" (current value: ")
                        .append(currentValue != null ? currentValue : knob.def())
                        .append(")\n");
            }
            sb.append('\n');
        }

        sb.append("You may respond with either shape described in the system prompt: the FULL ")
                .append("project (every file, not just the ones that changed) if the fix is broad, ")
                .append("or the EDIT shape ({\"edits\":[...]}) for small, surgical changes whose \"find\" ")
                .append("matches the current source of that file exactly once. Keep the same \"name\" ")
                .append("and config knobs unless the fix genuinely requires changing them. If you change ")
                .append("any config knob, include the full updated \"config\" array reflecting every knob ")
                .append("the mod should have afterward; otherwise leave \"config\" out to keep the existing ")
                .append("knobs unchanged.");
        return sb.toString();
    }

    /** Prompt asking the model to fix a project that failed to compile. */
    public static String repairPrompt(String javacDiagnostics) {
        return repairPrompt(javacDiagnostics, null);
    }

    /**
     * {@link #repairPrompt(String)} plus an {@code API HINTS} block from the
     * {@link com.gijsm.vibemod.compile.SymbolOracle} (V3 Phase 0 §D).
     *
     * <p>The hints go <em>after</em> the diagnostics and before the instruction,
     * deliberately: the model has to read the error first or it will patch the
     * wrong call. {@code hints} being blank collapses this to the one-argument
     * form exactly, so a host with no oracle installed sends the prompt it
     * always sent.
     */
    public static String repairPrompt(String javacDiagnostics, String hints) {
        StringBuilder sb = new StringBuilder();
        sb.append("Your previous JSON response failed to compile. javac says:\n\n")
                .append(javacDiagnostics);
        if (hints != null && !hints.isBlank()) {
            sb.append("\n\n").append(hints.strip());
        }
        sb.append("\n\nReturn the corrected project as JSON: either the FULL project shape (every file) ")
                .append("described in the system prompt, or — if the fix is small and surgical — the EDIT shape ")
                .append("({\"edits\":[{\"path\":...,\"find\":...,\"replace\":...}]}) whose \"find\" matches the ")
                .append("current source of that file exactly once.");
        return sb.toString();
    }

    /**
     * User message used when an edit round's {@code EditBlock}s failed to apply cleanly
     * against the current sources (e.g. a "find" snippet did not match exactly once). Asks
     * the model to fall back to the full project shape rather than attempting another edit.
     */
    public static String demandFullProject(String reason) {
        return "Your edits did not apply cleanly: " + reason
                + "; return the FULL corrected project as strict JSON with complete files.";
    }

    /**
     * Lenient parse of an LLM response into a {@link GeneratedProject}: strips markdown
     * fences if present, extracts the first balanced top-level JSON object (tracking string
     * literals/escapes so braces inside code strings don't break the scan), and validates
     * the result. Accepts either the full project shape ("files" non-empty) or the edit
     * shape ("edits" non-empty); rejects a response carrying both or neither.
     *
     * @throws IllegalArgumentException with a precise reason on any contract violation.
     */
    public static GeneratedProject parse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            throw new IllegalArgumentException("LLM response was empty");
        }

        String withoutFences = stripFenceLines(llmResponse);
        String jsonText = extractBalancedJsonObject(withoutFences);

        JsonObject obj;
        try {
            JsonElement parsed = JsonParser.parseString(jsonText);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Extracted JSON was not an object");
            }
            obj = parsed.getAsJsonObject();
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IllegalArgumentException("LLM response was not valid JSON: " + e.getMessage(), e);
        }

        // "plan" is transient stream-time data consumed incrementally off the wire by
        // StreamScanner (see llm/StreamScanner.java); it is never mapped onto GeneratedProject.
        // A present-but-malformed "plan" (wrong type) is not a parse error - ignore it and
        // keep parsing the rest of the response normally.
        if (obj.has("plan") && !obj.get("plan").isJsonNull() && !obj.get("plan").isJsonObject()) {
            obj.remove("plan");
        }

        JsonArray filesArray = arrayOrNull(obj, "files");
        JsonArray editsArray = arrayOrNull(obj, "edits");
        boolean hasFiles = filesArray != null && !filesArray.isEmpty();
        boolean hasEdits = editsArray != null && !editsArray.isEmpty();

        if (hasFiles && hasEdits) {
            throw new IllegalArgumentException(
                    "LLM response must not contain both \"files\" and \"edits\" — pick one shape");
        }
        if (!hasFiles && !hasEdits) {
            throw new IllegalArgumentException(
                    "LLM response must contain either a non-empty \"files\" array (full project) "
                            + "or a non-empty \"edits\" array (edit shape)");
        }

        List<GeneratedProject.ConfigKnob> config = parseConfigOrNull(obj);
        String usage = optionalString(obj, "usage");
        String manual = optionalString(obj, "manual");
        // The contract demands a changelog, but a missing one must never kill a
        // response — ModGenerator derives a fallback from the prompt instead.
        String changelog = optionalString(obj, "changelog");
        String icon = optionalString(obj, "icon");

        if (hasEdits) {
            String name = optionalString(obj, "name");
            String description = optionalString(obj, "description");
            String mainClass = optionalString(obj, "mainClass");
            List<GeneratedProject.EditBlock> edits = parseEdits(editsArray);
            return new GeneratedProject(name, description, usage, manual, changelog, icon, mainClass,
                    List.of(), config, edits);
        }

        String name = requireString(obj, "name");
        String description = requireString(obj, "description");
        String mainClass = requireString(obj, "mainClass");

        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "\"name\" must match [A-Z][A-Za-z0-9]{1,31}, got: " + name);
        }
        if (mainClass.isBlank()) {
            throw new IllegalArgumentException("\"mainClass\" must not be blank");
        }

        List<GeneratedProject.GeneratedFile> files = new ArrayList<>();
        for (JsonElement element : filesArray) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Each entry in \"files\" must be an object");
            }
            JsonObject fileObj = element.getAsJsonObject();
            String path = requireString(fileObj, "path");
            String content = requireString(fileObj, "content");
            if (!path.endsWith(".java")) {
                throw new IllegalArgumentException("File path must end with .java, got: " + path);
            }
            files.add(new GeneratedProject.GeneratedFile(path, content));
        }

        return new GeneratedProject(name, description, usage, manual, changelog, icon, mainClass,
                files, config, null);
    }

    // ------------------------------------------------------------------
    // parse() helpers
    // ------------------------------------------------------------------

    private static List<GeneratedProject.EditBlock> parseEdits(JsonArray editsArray) {
        List<GeneratedProject.EditBlock> edits = new ArrayList<>();
        for (JsonElement element : editsArray) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Each entry in \"edits\" must be an object");
            }
            JsonObject editObj = element.getAsJsonObject();
            String path = requireString(editObj, "path");
            if (!path.endsWith(".java")) {
                throw new IllegalArgumentException("Edit \"path\" must end with .java, got: " + path);
            }
            String find = requireString(editObj, "find");
            if (find.isEmpty()) {
                throw new IllegalArgumentException("Edit \"find\" must not be empty (path=" + path + ")");
            }
            String replace = requireString(editObj, "replace");
            edits.add(new GeneratedProject.EditBlock(path, find, replace));
        }
        return edits;
    }

    private static List<GeneratedProject.ConfigKnob> parseConfigOrNull(JsonObject obj) {
        JsonArray configArray = arrayOrNull(obj, "config");
        if (configArray == null) {
            return null;
        }
        List<GeneratedProject.ConfigKnob> knobs = new ArrayList<>();
        for (JsonElement element : configArray) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Each entry in \"config\" must be an object");
            }
            knobs.add(parseConfigKnob(element.getAsJsonObject()));
        }
        return knobs;
    }

    private static GeneratedProject.ConfigKnob parseConfigKnob(JsonObject knobObj) {
        String key = requireString(knobObj, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("Config knob \"key\" must not be blank");
        }
        String type = requireString(knobObj, "type");
        if (!VALID_KNOB_TYPES.contains(type)) {
            throw new IllegalArgumentException("Config knob \"" + key + "\" has invalid \"type\": \"" + type
                    + "\"; must be one of boolean|integer|decimal|text|choice");
        }
        String def = requireString(knobObj, "default");
        String description = requireString(knobObj, "description");

        Double min = optionalNumber(knobObj, "min", key);
        Double max = optionalNumber(knobObj, "max", key);
        Double step = optionalNumber(knobObj, "step", key);
        List<String> choices = optionalStringArray(knobObj, "choices", key);

        if ("choice".equals(type) && (choices == null || choices.isEmpty())) {
            throw new IllegalArgumentException(
                    "Config knob \"" + key + "\" has type \"choice\" but no non-empty \"choices\" array");
        }

        return new GeneratedProject.ConfigKnob(key, type, def, description, min, max, step, choices);
    }

    private static Double optionalNumber(JsonObject obj, String field, String knobKey) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(field);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(
                    "Config knob \"" + knobKey + "\" field \"" + field + "\" must be a number when present, got: " + el);
        }
        return el.getAsDouble();
    }

    private static List<String> optionalStringArray(JsonObject obj, String field, String knobKey) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(field);
        if (!el.isJsonArray()) {
            throw new IllegalArgumentException(
                    "Config knob \"" + knobKey + "\" field \"" + field + "\" must be an array when present");
        }
        List<String> result = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(
                        "Config knob \"" + knobKey + "\" field \"" + field + "\" must contain only strings");
            }
            result.add(item.getAsString());
        }
        return result;
    }

    private static String optionalString(JsonObject obj, String field) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(field);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("\"" + field + "\" must be a string when present, got: " + el);
        }
        return el.getAsString();
    }

    /** Returns the named array field, or null if absent/null; throws if present but not an array. */
    private static JsonArray arrayOrNull(JsonObject obj, String field) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return null;
        }
        if (!obj.get(field).isJsonArray()) {
            throw new IllegalArgumentException("\"" + field + "\" must be a JSON array");
        }
        return obj.getAsJsonArray(field);
    }

    private static String requireString(JsonObject obj, String field) {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive()
                || !obj.get(field).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing or non-string required field: \"" + field + "\"");
        }
        return obj.get(field).getAsString();
    }

    /**
     * Removes lines that are pure markdown fence markers (``` or ```json etc.), leaving
     * everything else untouched. Safe against JSON content because valid JSON never contains
     * a raw (unescaped) newline inside a string, so a legitimate JSON payload never has a
     * line that is *only* backticks.
     */
    private static String stripFenceLines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : lines) {
            if (line.strip().startsWith("```")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * Scans for the first '{' and returns the substring up to its balanced closing '}',
     * treating characters inside JSON string literals (honoring backslash escapes) as inert
     * so braces embedded in code snippets never confuse the balance count.
     */
    private static String extractBalancedJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("No JSON object found in LLM response");
        }

        boolean inString = false;
        boolean escape = false;
        int depth = 0;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        throw new IllegalArgumentException("Unbalanced JSON object in LLM response (no matching '}')");
    }
}
