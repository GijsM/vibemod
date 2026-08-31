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
import com.gijsm.vibemod.store.ModResources;
import com.gijsm.vibemod.store.PixelGrid;

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
 * import rules, the rule table, the threading contract, the icon rule and
 * the few-shots, and {@link #systemPrompt(PromptFacts)} slots them into one
 * skeleton shared by every platform (ARCHITECTURE-V2 §6). The era-specific text
 * is no longer a per-profile string but a {@link PromptRule} table evaluated
 * against the running server's measured {@link PromptFacts}, so the prompt
 * cannot contradict a probe the host already made. The four user-message
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
    // The prompt is assembled from a fixed skeleton, the running platform's
    // PlatformProfile (ARCHITECTURE-V2 §6.1) and the server's own measured facts
    // (§6.5). The role line, the verbatim api sources, the import rules, the
    // threading contract, the icon rule and the few-shots come from the profile;
    // everything between them is shared by every platform.
    //
    // What used to be the profile's `cheatSheet` — one hand-written string per
    // era saying which enum names are real — is gone. It could not be right: the
    // vocabulary breaks at 1.20.5, 1.21 and 1.21.3, three boundaries inside what
    // was one era, and it contradicted probes the host had already made. Era text
    // is now a PromptRule table evaluated against PromptFacts, and the constant
    // lists are read off the running server rather than described.
    //
    // The api sources inside the profile are NOT hand-copied: GeneratedApiSources
    // is emitted at build time straight from the sdk module's real files by the
    // `:core:generatePromptSources` task (§6.4), so prompt and api cannot drift
    // apart. LlmSelfTest still asserts the match on disk.
    // ------------------------------------------------------------------

    /**
     * The full system prompt sent with every generation/edit/repair call, for
     * the platform the host is actually running.
     *
     * <h2>Order: invariant first</h2>
     *
     * <p>Everything a jar cannot change comes first — the role line, the frozen
     * api sources, the output contract, the hard rules, the edit shape — and the
     * server-derived section sits between them and the few-shots. The role line
     * used to be the first thing in the prompt AND the one sentence that differed
     * between the two Paper eras, which made their shared prefix 27 characters;
     * it is now identical for both, and the version it used to assert is stated
     * as a measured fact in "THIS SERVER" instead.
     *
     * <p><b>Be honest about what this buys: nothing, today.</b> Within one boot
     * the system prompt is byte-identical on every call, so no cache hit changes.
     * This is hygiene that starts paying once there is more than one variable
     * fragment. It is not a cost saving, and it deliberately does not add a
     * {@code cache_control} breakpoint — that is a separate task.
     */
    public static String systemPrompt(PromptFacts facts) {
        return systemPrompt(facts, null);
    }

    /**
     * The system prompt plus one short block of facts about <em>this</em>
     * running host (V3 Phase 4).
     *
     * <p>A profile describes a platform; {@code hostFacts} describes the
     * process. The distinction earns its keep on Fabric, where the same profile
     * serves a singleplayer client and a dedicated server and several of its
     * rules branch on which one you are: {@code assets/**} render or are inert,
     * {@code ClientModInitializer} runs or is skipped, a registered item works
     * or is refused. Before this existed the prompt stated both branches and
     * left the model to guess, and the live demo showed what that costs — a
     * model that followed the prompt perfectly wrote a registered item on a
     * dedicated server, was refused, and burned a whole repair round rediscovering
     * a fact the host knew all along.
     *
     * <p>It goes in the SYSTEM prompt rather than the request because it is
     * constant for the life of a host, which is what keeps it cacheable.
     * {@code null} or blank reproduces {@link #systemPrompt(PromptFacts)}
     * byte for byte — asserted, so a host that supplies nothing is not paying
     * for this.
     */
    public static String systemPrompt(PromptFacts facts, String hostFacts) {
        PlatformProfile profile = facts.profile();
        StringBuilder sb = new StringBuilder();

        sb.append(profile.roleLine());
        if (!profile.roleLine().endsWith("\n")) {
            sb.append('\n');
        }
        if (hostFacts != null && !hostFacts.isBlank()) {
            sb.append("\n================ THIS HOST ================\n\n")
                    .append(hostFacts.strip())
                    .append('\n');
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
                """.replace("ENTRYPOINT", profile.entrypointName()));

        // What may be in files[] is profile data (V3 Phase 2 §E): every profile
        // but the native Fabric one accepts .java and nothing else, and saying
        // "paths end in .java" AND "here is how to write a recipe" in the same
        // prompt would be a contradiction the model has to resolve for itself.
        sb.append(profile.filesContract());
        if (!profile.filesContract().endsWith("\n")) {
            sb.append('\n');
        }

        sb.append("""
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

        // Blank on Paper, whose threading contract is a probe-predicated rule pair
        // in the THIS SERVER section instead — see PlatformProfiles.PAPER_THREADING.
        // Skipping the blank keeps a stray empty line out of the prompt.
        if (!profile.threadingContract().isBlank()) {
            sb.append(profile.threadingContract());
            sb.append('\n');
        }

        sb.append("""
                - Be defensive: null-check worlds, entities, and players before using them; use
                  `instanceof` checks before casting entities to more specific types; guard against
                  players being offline/dead when tasks fire later.
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

                """);

        // ---- the only part of the prompt that varies with the server --------
        // Everything above is fixed for a platform; everything below was probed
        // off the running server's own classpath at boot. Keeping the boundary
        // this sharp is what lets the offline gate check the variable half in
        // isolation, and what makes a diff between two versions' prompts small
        // enough to read.
        sb.append(serverSection(facts));

        sb.append("""

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
     * The measured half of the prompt: what this server is, and which rules its
     * own classpath says are true.
     *
     * <p>No rule survives that the vocabulary contradicts, because
     * {@link PromptRule#appliesTo} checks each rule's own symbol claims before
     * it is emitted. Prose about the API is the thing this section exists to
     * stop writing.
     *
     * <p>This section used to end with exhaustive {@code Attribute},
     * {@code Enchantment} and {@code PotionEffectType} constant lists read off
     * the running server. They were deleted after being measured: the ablation
     * scored {@code after-novocab} 18/25 against {@code after} 19/25, so ~850
     * tokens on every call and every self-heal round bought at most one
     * generation in twenty-five. The same eval found only ONE targeted
     * vocabulary failure in 225 generations — a capable model writes the real
     * names whatever the prompt claims — and {@link
     * com.gijsm.vibemod.gen.SymbolRepair} repairs the rest deterministically for
     * free. Listing the names was insurance against a fire that does not start.
     */
    private static String serverSection(PromptFacts facts) {
        PlatformProfile profile = facts.profile();
        String rules = PromptRules.render(profile.rules(), facts);
        if (rules.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n================ THIS SERVER ================\n\n");
        sb.append("""
                Everything in this section was measured on the server you are writing for, when
                it started. It overrides anything you remember about other Minecraft versions:
                where they disagree, this section is right and your memory is wrong.

                """);

        String version = facts.mcVersion();
        if (!version.isEmpty()) {
            sb.append("This server reports Minecraft ").append(version)
                    .append(" (").append(profile.displayName()).append(").\n\n");
        } else {
            sb.append("Target platform: ").append(profile.displayName()).append(".\n\n");
        }

        sb.append(rules);
        return sb.toString();
    }

    /**
     * The prompt for a profile with no host behind it: every vocabulary query
     * answers UNKNOWN, so the capability-predicated rules drop out and the fixed
     * text remains. Used by the self-tests and {@code JarExporter}; a running
     * host always has {@link PromptFacts#of(com.gijsm.vibemod.platform.PlatformInfo)}
     * and should never call this.
     */
    public static String systemPrompt(PlatformProfile profile) {
        return systemPrompt(PromptFacts.unknown(profile));
    }

    /**
     * The Paper 1.21.7+ prompt. Kept as the no-argument default because the
     * whole stored corpus and every self-test assertion were written against
     * it; a host always passes its own facts explicitly.
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
        return repairPrompt(javacDiagnostics, List.of(), null);
    }

    /**
     * The same repair prompt, carrying what
     * {@link com.gijsm.vibemod.gen.SymbolRepair} measured off the running server
     * before the compile.
     *
     * <p>Two kinds of note, and both matter. A note about a constant the host
     * <em>already rewrote</em> stops the model "correcting" the repair back to the
     * name it remembers from training, which would loop the round forever. A note
     * about one it could not resolve names the constants that really exist, so the
     * round is spent on choosing between real names rather than on rediscovering
     * that the old one is gone — which is a whole 6k-token round otherwise.
     *
     * <p>These are measurements, not guesses, and the prompt says so: the model
     * has to be told this outranks its training data, or it will argue.
     */
    public static String repairPrompt(String javacDiagnostics, List<String> vocabularyNotes) {
        return repairPrompt(javacDiagnostics, vocabularyNotes, null);
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
        return repairPrompt(javacDiagnostics, List.of(), hints);
    }

    /**
     * Both repair aids at once, which is what a live host actually has: the
     * {@link com.gijsm.vibemod.compile.SymbolOracle}’s hints about symbols javac
     * could not resolve, and {@link com.gijsm.vibemod.gen.SymbolRepair}’s notes
     * about names it rewrote off the running server.
     *
     * <p>They are separate blocks because they answer different questions. The
     * hints say <em>where to look</em>; the notes say <em>what is true here</em>,
     * and the notes come last so the measured facts are the final thing read
     * before the instruction.
     */
    public static String repairPrompt(String javacDiagnostics, List<String> vocabularyNotes,
            String hints) {
        StringBuilder sb = new StringBuilder();
        sb.append("Your previous JSON response failed to compile. javac says:\n\n")
                .append(javacDiagnostics);
        if (hints != null && !hints.isBlank()) {
            sb.append("\n\n").append(hints.strip());
        }
        if (vocabularyNotes != null && !vocabularyNotes.isEmpty()) {
            sb.append("\n\nThis server's API was measured directly at boot. These facts about it beat "
                    + "anything you remember about Minecraft versions:\n");
            for (String note : vocabularyNotes) {
                sb.append("- ").append(note).append('\n');
            }
        }
        sb.append("\n\nReturn the corrected project as JSON: either the FULL project shape (every file) ")
                .append("described in the system prompt, or — if the fix is small and surgical — the EDIT ")
                .append("shape ({\"edits\":[{\"path\":...,\"find\":...,\"replace\":...}]}) whose \"find\" ")
                .append("matches the current source of that file exactly once.");
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
            // V3 Phase 2 §A: files[] carries resources as well as sources now.
            // Both halves are validated HERE rather than at the store, because a
            // rejection here is a self-heal round with the model's own text in
            // it, and a rejection at the store is a stack trace after the money
            // has already been spent.
            if (ModResources.isResourcePath(path)) {
                ModResources.validate(path);
                if (ModResources.isGridPath(path)) {
                    try {
                        PixelGrid.parse(content);
                    } catch (IllegalArgumentException bad) {
                        throw new IllegalArgumentException(path + ": " + bad.getMessage());
                    }
                }
            } else if (!path.endsWith(".java")) {
                throw new IllegalArgumentException("File path must end with .java, got: " + path);
            }
            files.add(new GeneratedProject.GeneratedFile(path, content));
        }
        requireBlockstatesForBlockRegistrations(files);

        return new GeneratedProject(name, description, usage, manual, changelog, icon, mainClass,
                files, config, null);
    }

    /**
     * V4 Phase 1: a mod that registers into {@code minecraft:block} must ship the
     * blockstate file that maps the block to a model, or every copy of it in the
     * world is the missing model.
     *
     * <p>Reported the way every other contract violation here is — an
     * {@link IllegalArgumentException} carrying the fix — because this one is
     * invisible until somebody places the block, and a repair round that adds one
     * small JSON file is the cheapest moment to catch it. Matched on the
     * registration call with whitespace squeezed out, so a wrapped
     * {@code Registry.register(} still trips it and a mere {@code BLOCK} lookup
     * does not.
     */
    private static void requireBlockstatesForBlockRegistrations(
            List<GeneratedProject.GeneratedFile> files) {
        String registrar = null;
        for (GeneratedProject.GeneratedFile file : files) {
            if (file.path().endsWith(".java")
                    && file.content().replaceAll("\\s+", "")
                            .contains("register(BuiltInRegistries.BLOCK,")) {
                registrar = file.path();
                break;
            }
        }
        if (registrar == null) {
            return;
        }
        for (GeneratedProject.GeneratedFile file : files) {
            String path = file.path();
            if (path.startsWith(ModResources.ASSETS_ROOT) && path.contains("/blockstates/")
                    && path.endsWith(".json")) {
                return;
            }
        }
        throw new IllegalArgumentException(registrar + " registers a block in minecraft:block, "
                + "but the project ships no assets/<namespace>/blockstates/<name>.json - "
                + "without it the block renders as the missing model. Add one per registered "
                + "block: {\"variants\": {\"\": {\"model\": \"<namespace>:block/<name>\"}}}");
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
            if (!path.endsWith(".java") && !ModResources.isResourcePath(path)) {
                throw new IllegalArgumentException("Edit \"path\" must end with .java, or name a "
                        + "data/ or assets/ resource file, got: " + path);
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
