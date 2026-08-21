package com.gijsm.vibemine.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.gijsm.vibemine.gen.GeneratedProject;

/**
 * Builds the prompts sent to the LLM and parses its responses back into a
 * {@link GeneratedProject}. This is the "soul" of VibeMine: the quality of the
 * system prompt directly determines how good generated mods are.
 */
public final class PromptLibrary {

    private PromptLibrary() {
    }

    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Z][A-Za-z0-9]{1,31}$");

    // ------------------------------------------------------------------
    // Frozen API sources, embedded verbatim so the model always sees the
    // exact contract it must code against, regardless of plugin jar layout.
    // ------------------------------------------------------------------

    private static final String VIBE_MOD_SOURCE = """
            package com.gijsm.vibemine.api;

            /**
             * The contract every generated mod implements. Exactly one public class per mod
             * implements this interface; it must have a public no-arg constructor.
             *
             * All registrations (listeners, tasks, commands, actions) MUST go through the
             * supplied {@link VibeContext} so the mod can be torn down exactly on
             * disable/unload. A mod must never call Bukkit registration APIs directly.
             */
            public interface VibeMod {

                /**
                 * Called on the main server thread when the mod is enabled.
                 * Register listeners/tasks/commands via {@code ctx} here.
                 */
                void onEnable(VibeContext ctx) throws Exception;

                /**
                 * Called on the main server thread just before teardown. Registrations made
                 * through the context are cleaned up automatically after this returns; only
                 * override to release resources the context does not know about.
                 */
                default void onDisable(VibeContext ctx) {
                }
            }
            """;

    private static final String VIBE_CONTEXT_SOURCE = """
            package com.gijsm.vibemine.api;

            import java.nio.file.Path;
            import java.util.logging.Logger;

            import org.bukkit.Server;
            import org.bukkit.event.Listener;
            import org.bukkit.plugin.Plugin;
            import org.bukkit.scheduler.BukkitTask;

            /**
             * Everything a generated mod may touch. All registrations are tracked per mod
             * and undone exactly when the mod is disabled or unloaded.
             *
             * All methods must be called from the main server thread.
             */
            public interface VibeContext {

                /** The host plugin (VibeCore). For advanced use only. */
                Plugin plugin();

                /** Convenience for {@code plugin().getServer()}. */
                Server server();

                /** This mod's name. */
                String modName();

                /** Logger prefixed with the mod name. */
                Logger log();

                /** Per-mod data directory, created on first call. */
                Path dataFolder();

                /** Register an event listener (methods annotated with @EventHandler). Tracked. */
                void listen(Listener listener);

                /** Schedule a repeating main-thread task with an initial delay. Tracked. */
                BukkitTask repeat(long delayTicks, long periodTicks, Runnable task);

                /** Schedule a repeating main-thread task starting after one period. Tracked. */
                default BukkitTask repeat(long periodTicks, Runnable task) {
                    return repeat(periodTicks, periodTicks, task);
                }

                /** Schedule a one-shot delayed main-thread task. Tracked. */
                BukkitTask later(long delayTicks, Runnable task);

                /**
                 * Register a real top-level command, e.g. {@code command("boom", "Explodes things", h)}
                 * gives players {@code /boom}. Falls back to an action (see {@link #action}) if
                 * top-level registration is unavailable. Tracked and removed on disable.
                 */
                void command(String name, String description, ModCommandHandler handler);

                /** Register a named action invocable as {@code /vibe do <mod> <name> [args]}. Tracked. */
                void action(String name, ModCommandHandler handler);
            }
            """;

    private static final String MOD_COMMAND_HANDLER_SOURCE = """
            package com.gijsm.vibemine.api;

            import org.bukkit.command.CommandSender;

            /** Handler for a mod-registered command or named action. Runs on the main thread. */
            @FunctionalInterface
            public interface ModCommandHandler {
                void run(CommandSender sender, String[] args) throws Exception;
            }
            """;

    // ------------------------------------------------------------------
    // Few-shot examples. Kept as constants so they are easy to eyeball and
    // compile-check independently (see plugin/src/test/java/LlmSelfTest.java).
    // ------------------------------------------------------------------

    private static final String EXAMPLE_1_USER =
            "Create a mod: when a creeper dies, spawn a chicken at its location with a poof (requested by Steve)";

    private static final String EXAMPLE_1_ASSISTANT = """
            {"name":"ChickenCreepers","description":"When a creeper dies it turns into a chicken with a puff of smoke.","mainClass":"ChickenCreepers","files":[{"path":"ChickenCreepers.java","content":"package vibemod.chickencreepers;\\n\\nimport com.gijsm.vibemine.api.VibeContext;\\nimport com.gijsm.vibemine.api.VibeMod;\\n\\npublic final class ChickenCreepers implements VibeMod {\\n    @Override\\n    public void onEnable(VibeContext ctx) throws Exception {\\n        ctx.listen(new CreeperDeathListener(ctx));\\n        ctx.log().info(\\"ChickenCreepers enabled.\\");\\n    }\\n}\\n"},{"path":"CreeperDeathListener.java","content":"package vibemod.chickencreepers;\\n\\nimport com.gijsm.vibemine.api.VibeContext;\\nimport org.bukkit.Location;\\nimport org.bukkit.Particle;\\nimport org.bukkit.Sound;\\nimport org.bukkit.World;\\nimport org.bukkit.entity.EntityType;\\nimport org.bukkit.entity.LivingEntity;\\nimport org.bukkit.event.EventHandler;\\nimport org.bukkit.event.Listener;\\nimport org.bukkit.event.entity.EntityDeathEvent;\\n\\npublic final class CreeperDeathListener implements Listener {\\n\\n    private final VibeContext ctx;\\n\\n    public CreeperDeathListener(VibeContext ctx) {\\n        this.ctx = ctx;\\n    }\\n\\n    @EventHandler\\n    public void onCreeperDeath(EntityDeathEvent event) {\\n        LivingEntity entity = event.getEntity();\\n        if (entity == null || entity.getType() != EntityType.CREEPER) {\\n            return;\\n        }\\n        World world = entity.getWorld();\\n        if (world == null) {\\n            return;\\n        }\\n        Location loc = entity.getLocation();\\n        world.spawnEntity(loc, EntityType.CHICKEN);\\n        world.spawnParticle(Particle.POOF, loc, 12, 0.3, 0.3, 0.3, 0.01);\\n        world.playSound(loc, Sound.ENTITY_CHICKEN_AMBIENT, 1.0f, 1.2f);\\n    }\\n}\\n"}]}
            """;

    private static final String EXAMPLE_2_USER =
            "Create a mod: every 10 seconds all players get a brief speed boost (requested by Alex)";

    private static final String EXAMPLE_2_ASSISTANT = """
            {"name":"SpeedPulse","description":"Every 10 seconds all online players get a short burst of Speed.","mainClass":"SpeedPulse","files":[{"path":"SpeedPulse.java","content":"package vibemod.speedpulse;\\n\\nimport com.gijsm.vibemine.api.VibeContext;\\nimport com.gijsm.vibemine.api.VibeMod;\\nimport org.bukkit.Particle;\\nimport org.bukkit.Sound;\\nimport org.bukkit.entity.Player;\\nimport org.bukkit.potion.PotionEffect;\\nimport org.bukkit.potion.PotionEffectType;\\n\\npublic final class SpeedPulse implements VibeMod {\\n\\n    private static final long PERIOD_TICKS = 200L;\\n\\n    @Override\\n    public void onEnable(VibeContext ctx) throws Exception {\\n        ctx.repeat(PERIOD_TICKS, PERIOD_TICKS, () -> pulse(ctx));\\n        ctx.log().info(\\"SpeedPulse enabled.\\");\\n    }\\n\\n    private void pulse(VibeContext ctx) {\\n        for (Player player : ctx.server().getOnlinePlayers()) {\\n            if (player == null || !player.isOnline()) {\\n                continue;\\n            }\\n            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1, false, true, true));\\n            player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.01);\\n            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);\\n        }\\n    }\\n}\\n"}]}
            """;

    /** The full system prompt sent with every generation/edit/repair call. */
    public static String systemPrompt() {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are an expert Paper 1.21.8 gameplay-mod author. You write small, delightful,
                self-contained Minecraft server mods entirely in Java, targeting exactly one API:
                the VibeMod/VibeContext contract shown below. You never touch anything else.

                Your mods run hot-loaded inside a host plugin called VibeCore. A mod is not a
                Bukkit plugin: it is one or more plain Java classes, exactly one of which
                implements VibeMod, compiled in-process and loaded into a child class loader.
                Everything your mod does with the Bukkit API must be routed through the
                VibeContext instance you are handed in onEnable, so the host can cleanly tear
                your mod down later.

                ================ FROZEN API (verbatim source, do not deviate) ================

                """);

        sb.append("--- com/gijsm/vibemine/api/VibeMod.java ---\n");
        sb.append(VIBE_MOD_SOURCE).append('\n');
        sb.append("--- com/gijsm/vibemine/api/VibeContext.java ---\n");
        sb.append(VIBE_CONTEXT_SOURCE).append('\n');
        sb.append("--- com/gijsm/vibemine/api/ModCommandHandler.java ---\n");
        sb.append(MOD_COMMAND_HANDLER_SOURCE).append('\n');

        sb.append("""
                ================ OUTPUT CONTRACT ================

                You respond with strict JSON only. No markdown code fences, no prose before or
                after, no explanations, nothing but a single JSON object with exactly this shape:

                {
                  "name": "PascalCaseShortName",
                  "description": "One sentence describing what the mod does.",
                  "mainClass": "SimpleClassName",
                  "files": [
                    {"path": "SimpleClassName.java", "content": "full file source as a string"}
                  ]
                }

                Rules for the JSON itself:
                - "name" is PascalCase, starts with an uppercase letter, letters/digits only,
                  2 to 32 characters (e.g. "ChickenCreepers", "SpeedPulse", "LavaFloor").
                - "mainClass" is the simple (no package) name of the one public class that
                  implements VibeMod, and must have a public no-arg constructor.
                - Every entry in "files" has a "path" ending in ".java" and "content" holding the
                  complete, compilable source of that file (proper escaping of quotes/newlines
                  since this is a JSON string).
                - ALL files declare `package vibemod.<name lowercased>;` at the top (the mod name
                  from the JSON, lowercased, no dots, no dashes).
                - Exactly one public class across all files implements VibeMod.
                - Do not wrap the JSON in ```json fences or add any commentary. The response body
                  must be parseable as JSON from the first character to the last.

                ================ HARD RULES ================

                - Imports are limited to `java.*` and `org.bukkit.*` ONLY. NEVER import
                  `net.minecraft.*`, NEVER `io.papermc.*` internals, and NEVER
                  `java.lang.reflect.*` or any other reflection API. If the request seems to need
                  something outside this surface, implement the closest tasteful approximation
                  using only java.* and org.bukkit.*.
                - NEVER call `Bukkit.getPluginManager().registerEvents(...)`,
                  `Bukkit.getScheduler()...`, or `Bukkit.getCommandMap()` directly. Registration
                  always goes through the VibeContext you are given: `ctx.listen(...)` for event
                  listeners, `ctx.repeat(...)` / `ctx.later(...)` for scheduled work,
                  `ctx.command(...)` for a real top-level command, `ctx.action(...)` for a named
                  `/vibe do <mod> <name>` action.
                - Event handler methods and Runnables passed to ctx.repeat/ctx.later already run
                  on the main server thread — do not spawn your own threads and do not attempt to
                  hop threads yourself.
                - Be defensive: null-check worlds, entities, and players before using them; use
                  `instanceof` checks before casting entities to more specific types; guard against
                  players being offline/dead when tasks fire later.
                - Use only real Paper 1.21 enum constants for Material, EntityType, Sound, and
                  Particle. Do not invent names. If unsure, prefer a very common, obviously-real
                  constant (e.g. Material.DIAMOND_SWORD, EntityType.ZOMBIE, Sound.ENTITY_PLAYER_LEVELUP,
                  Particle.CLOUD) over a guess.
                - To spawn an entity, use `world.spawnEntity(location, EntityType.X)`. Never try to
                  construct entity instances directly.
                - Persistent per-player state is fine as a plain `HashMap<UUID, ...>` field on your
                  VibeMod class or listener, keyed by `player.getUniqueId()`. Do not use static
                  mutable state shared across mod instances beyond that.
                - Keep each file under roughly 150 lines. Split into a couple of small classes if a
                  single file would run long; every class still lives in the same
                  `vibemod.<name>` package.
                - Make effects juicy where it fits the request: pair visual feedback
                  (`world.spawnParticle(...)` / `player.spawnParticle(...)`) with a sound
                  (`world.playSound(...)` / `player.playSound(...)`) so the mod feels alive, not
                  silent.

                ================ WORKED EXAMPLES ================

                The following two examples show the exact expected input/output shape. Study the
                JSON formatting (escaped newlines and quotes inside "content") as closely as the
                Java itself.

                """);

        sb.append("--- Example 1 ---\n");
        sb.append("User: ").append(EXAMPLE_1_USER).append('\n');
        sb.append("Assistant: ").append(EXAMPLE_1_ASSISTANT).append('\n');
        sb.append("--- Example 2 ---\n");
        sb.append("User: ").append(EXAMPLE_2_USER).append('\n');
        sb.append("Assistant: ").append(EXAMPLE_2_ASSISTANT).append('\n');

        sb.append("""

                ================ END OF INSTRUCTIONS ================

                Now respond to the user's request the same way: strict JSON only, matching the
                contract above exactly.
                """);

        return sb.toString();
    }

    /** Prompt for a brand-new mod. */
    public static String makePrompt(String request, String creator) {
        return "Create a mod: " + request + " (requested by " + creator + ")";
    }

    /** Prompt for editing an existing mod's sources. */
    public static String editPrompt(String request, Map<String, String> currentSources) {
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
        sb.append("Return the FULL updated project (every file, not just the ones that changed) ")
                .append("as the same strict JSON object described in the system prompt — not a diff. ")
                .append("Keep the same \"name\" unless the request explicitly asks you to rename the mod.");
        return sb.toString();
    }

    /** Prompt asking the model to fix a project that failed to compile. */
    public static String repairPrompt(String javacDiagnostics) {
        return "Your previous JSON project failed to compile. javac says:\n\n"
                + javacDiagnostics
                + "\n\nReturn the corrected FULL project as the same strict JSON.";
    }

    /**
     * Lenient parse of an LLM response into a {@link GeneratedProject}: strips markdown
     * fences if present, extracts the first balanced top-level JSON object (tracking string
     * literals/escapes so braces inside code strings don't break the scan), and validates
     * the result.
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

        if (!obj.has("files") || !obj.get("files").isJsonArray()) {
            throw new IllegalArgumentException("\"files\" must be a JSON array");
        }
        JsonArray filesArray = obj.getAsJsonArray("files");
        if (filesArray.isEmpty()) {
            throw new IllegalArgumentException("\"files\" must not be empty");
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

        return new GeneratedProject(name, description, mainClass, files);
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
