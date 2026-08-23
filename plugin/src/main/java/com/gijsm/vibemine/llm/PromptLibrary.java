package com.gijsm.vibemine.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>v2 adds per-mod config knobs (usage/manual/config in the output contract,
 * with a hard rule that mods read them live via {@code ctx.configX} rather than
 * caching them) and a lightweight edit-response shape ({@code {"edits":[...]}})
 * that edit/repair rounds may use instead of resending the full project.
 */
public final class PromptLibrary {

    private PromptLibrary() {
    }

    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Z][A-Za-z0-9]{1,31}$");
    private static final Set<String> VALID_KNOB_TYPES = Set.of("boolean", "integer", "decimal", "text", "choice");

    // ------------------------------------------------------------------
    // Frozen API sources, embedded verbatim so the model always sees the
    // exact contract it must code against, regardless of plugin jar layout.
    // ------------------------------------------------------------------

    private static final String MOD_SOURCE = """
            package com.gijsm.vibemine.api;

            /**
             * The contract every generated mod implements. Exactly one public class per mod
             * implements this interface; it must have a public no-arg constructor.
             *
             * All registrations (listeners, tasks, commands, actions) MUST go through the
             * supplied {@link VibeContext} so the mod can be torn down exactly on
             * disable/unload. A mod must never call Bukkit registration APIs directly.
             */
            public interface Mod {

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

                /** The host plugin (VibeMod). For advanced use only. */
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

                // ---- live config ----
                // Mods declare tunable settings in their generation output ("config" knobs);
                // these accessors serve the CURRENT value at call time: stored value, else the
                // knob's declared default, else the type's zero value with a one-time warning.
                // Read config at the moment of use - never cache it in a field - so that
                // knob changes apply instantly without a reload.

                /** Current value of a boolean knob. */
                boolean configBool(String key);

                /** Current value of an integer knob. */
                long configInt(String key);

                /** Current value of a decimal knob. */
                double configDouble(String key);

                /** Current value of a text or choice knob. */
                String configString(String key);
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
            {"plan":{"name":"ChickenCreepers","files":[{"path":"ChickenCreepers.java","purpose":"Mod entry point: registers the listener."},{"path":"CreeperDeathListener.java","purpose":"Listens for creeper deaths and spawns chickens."}]},"name":"ChickenCreepers","description":"When a creeper dies it turns into one or more chickens with a puff of smoke.","usage":"Kill a creeper and watch","manual":"Every time a creeper dies anywhere on the server, it bursts into a cloud of smoke and leaves behind some chickens instead. By default only one chicken appears, but you can turn up the chaos with the chicken-count setting, which controls how many chickens spawn per creeper kill (from 1 up to 10). Change it any time from the mod's config book or the settings panel in /vibe gui - the very next creeper that dies will use the new count immediately, no reload needed. A short puff of particles and a chicken sound play at the creeper's location so the swap is obvious even in a crowd.","icon":"CHICKEN","mainClass":"ChickenCreepers","files":[{"path":"ChickenCreepers.java","content":"package vibemod.chickencreepers;\\n\\nimport com.gijsm.vibemine.api.VibeContext;\\nimport com.gijsm.vibemine.api.Mod;\\n\\npublic final class ChickenCreepers implements Mod {\\n    @Override\\n    public void onEnable(VibeContext ctx) throws Exception {\\n        ctx.listen(new CreeperDeathListener(ctx));\\n        ctx.log().info(\\"ChickenCreepers enabled.\\");\\n    }\\n}\\n"},{"path":"CreeperDeathListener.java","content":"package vibemod.chickencreepers;\\n\\nimport com.gijsm.vibemine.api.VibeContext;\\nimport org.bukkit.Location;\\nimport org.bukkit.Particle;\\nimport org.bukkit.Sound;\\nimport org.bukkit.World;\\nimport org.bukkit.entity.EntityType;\\nimport org.bukkit.entity.LivingEntity;\\nimport org.bukkit.event.EventHandler;\\nimport org.bukkit.event.Listener;\\nimport org.bukkit.event.entity.EntityDeathEvent;\\n\\npublic final class CreeperDeathListener implements Listener {\\n\\n    private final VibeContext ctx;\\n\\n    public CreeperDeathListener(VibeContext ctx) {\\n        this.ctx = ctx;\\n    }\\n\\n    @EventHandler\\n    public void onCreeperDeath(EntityDeathEvent event) {\\n        LivingEntity entity = event.getEntity();\\n        if (entity == null || entity.getType() != EntityType.CREEPER) {\\n            return;\\n        }\\n        World world = entity.getWorld();\\n        if (world == null) {\\n            return;\\n        }\\n        Location loc = entity.getLocation();\\n        long chickenCount = ctx.configInt(\\"chicken-count\\");\\n        if (chickenCount < 1) {\\n            chickenCount = 1;\\n        }\\n        for (long i = 0; i < chickenCount; i++) {\\n            world.spawnEntity(loc, EntityType.CHICKEN);\\n        }\\n        world.spawnParticle(Particle.POOF, loc, 12, 0.3, 0.3, 0.3, 0.01);\\n        world.playSound(loc, Sound.ENTITY_CHICKEN_AMBIENT, 1.0f, 1.2f);\\n    }\\n}\\n"}],"config":[{"key":"chicken-count","type":"integer","default":"1","description":"How many chickens spawn per creeper kill.","min":1,"max":10,"step":1}]}
            """;

    private static final String EXAMPLE_2_USER =
            "Create a mod: every 10 seconds all players get a brief speed boost (requested by Alex)";

    private static final String EXAMPLE_2_ASSISTANT = """
            {"plan":{"name":"SpeedPulse","files":[{"path":"SpeedPulse.java","purpose":"Mod entry point: schedules the repeating task."},{"path":"SpeedPulseTask.java","purpose":"Ticks the timer and applies the speed pulse."}]},"name":"SpeedPulse","description":"On a repeating timer, all online players get a short burst of Speed.","usage":"Stand around and feel the speed boost kick in","manual":"On a repeating timer, every player currently online gets a brief Speed effect together with a puff of cloud particles and a level-up sound. Two settings control the feel of it: period-seconds sets how many seconds pass between pulses (default 10, from 1 up to 120), and strength picks how strong the boost is - weak, normal, or strong. Both are read fresh every time the timer fires, so changing them from the config book or the settings panel in /vibe gui takes effect on the very next pulse without needing to reload or re-enable the mod.","icon":"SUGAR","mainClass":"SpeedPulse","files":[{"path":"SpeedPulse.java","content":"package vibemod.speedpulse;\\n\\nimport com.gijsm.vibemine.api.VibeContext;\\nimport com.gijsm.vibemine.api.Mod;\\n\\npublic final class SpeedPulse implements Mod {\\n\\n    private static final long TICK_PERIOD = 20L;\\n\\n    @Override\\n    public void onEnable(VibeContext ctx) throws Exception {\\n        SpeedPulseTask task = new SpeedPulseTask(ctx);\\n        ctx.repeat(TICK_PERIOD, TICK_PERIOD, task::tick);\\n        ctx.log().info(\\"SpeedPulse enabled.\\");\\n    }\\n}\\n"},{"path":"SpeedPulseTask.java","content":"package vibemod.speedpulse;\\n\\nimport com.gijsm.vibemine.api.VibeContext;\\nimport org.bukkit.Particle;\\nimport org.bukkit.Sound;\\nimport org.bukkit.entity.Player;\\nimport org.bukkit.potion.PotionEffect;\\nimport org.bukkit.potion.PotionEffectType;\\n\\n/** Ticks once a second; fires the actual speed pulse once period-seconds have elapsed. */\\npublic final class SpeedPulseTask {\\n\\n    private final VibeContext ctx;\\n    private long secondsSinceLastPulse = 0L;\\n\\n    public SpeedPulseTask(VibeContext ctx) {\\n        this.ctx = ctx;\\n    }\\n\\n    public void tick() {\\n        secondsSinceLastPulse++;\\n        long periodSeconds = ctx.configInt(\\"period-seconds\\");\\n        if (periodSeconds < 1) {\\n            periodSeconds = 1;\\n        }\\n        if (secondsSinceLastPulse < periodSeconds) {\\n            return;\\n        }\\n        secondsSinceLastPulse = 0L;\\n        pulse();\\n    }\\n\\n    private void pulse() {\\n        int amplifier = amplifierFor(ctx.configString(\\"strength\\"));\\n        for (Player player : ctx.server().getOnlinePlayers()) {\\n            if (player == null || !player.isOnline()) {\\n                continue;\\n            }\\n            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, amplifier, false, true, true));\\n            player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.01);\\n            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);\\n        }\\n    }\\n\\n    private int amplifierFor(String strength) {\\n        if (strength == null) {\\n            return 1;\\n        }\\n        return switch (strength) {\\n            case \\"weak\\" -> 0;\\n            case \\"strong\\" -> 2;\\n            default -> 1;\\n        };\\n    }\\n}\\n"}],"config":[{"key":"period-seconds","type":"integer","default":"10","description":"Seconds between speed pulses.","min":1,"max":120,"step":1},{"key":"strength","type":"choice","default":"normal","description":"How strong the speed boost is.","choices":["weak","normal","strong"]}]}
            """;

    /** The full system prompt sent with every generation/edit/repair call. */
    public static String systemPrompt() {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are an expert Paper 1.21.8 gameplay-mod author. You write small, delightful,
                self-contained Minecraft server mods entirely in Java, targeting exactly one API:
                the Mod/VibeContext contract shown below. You never touch anything else.

                Your mods run hot-loaded inside a host plugin called VibeMod. A mod is not a
                Bukkit plugin: it is one or more plain Java classes, exactly one of which
                implements Mod, compiled in-process and loaded into a child class loader.
                Everything your mod does with the Bukkit API must be routed through the
                VibeContext instance you are handed in onEnable, so the host can cleanly tear
                your mod down later.

                ================ FROZEN API (verbatim source, do not deviate) ================

                """);

        sb.append("--- com/gijsm/vibemine/api/Mod.java ---\n");
        sb.append(MOD_SOURCE).append('\n');
        sb.append("--- com/gijsm/vibemine/api/VibeContext.java ---\n");
        sb.append(VIBE_CONTEXT_SOURCE).append('\n');
        sb.append("--- com/gijsm/vibemine/api/ModCommandHandler.java ---\n");
        sb.append(MOD_COMMAND_HANDLER_SOURCE).append('\n');

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
                  "manual": "4 to 10 sentences of player-facing guidance. Mention every config knob.",
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
                - "manual" is 4 to 10 sentences of player-facing prose. If the mod has any config
                  knobs, the manual MUST mention what each one does and its default.
                - "icon" is ONE thematic, obtainable ITEM Material name from vanilla Minecraft 1.21,
                  in UPPER_SNAKE_CASE, that best represents the mod to a player browsing a menu
                  (e.g. "CHICKEN" for a mod about chickens, "SUGAR" for a speed effect, "TNT" for
                  an explosion mod, "DIAMOND_SWORD" for a combat mod). It must be a real obtainable
                  item a player could hold, NEVER a block-only or technical/internal material (e.g.
                  never "BEDROCK", "COMMAND_BLOCK", "AIR", "STRUCTURE_VOID"), and NEVER "AIR" itself.
                - "mainClass" is the simple (no package) name of the one public class that
                  implements Mod, and must have a public no-arg constructor.
                - Every entry in "files" has a "path" ending in ".java" and "content" holding the
                  complete, compilable source of that file (proper escaping of quotes/newlines
                  since this is a JSON string).
                - ALL files declare `package vibemod.<name lowercased>;` at the top (the mod name
                  from the JSON, lowercased, no dots, no dashes).
                - Exactly one public class across all files implements Mod.
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
                  Mod class or listener, keyed by `player.getUniqueId()`. Do not use static
                  mutable state shared across mod instances beyond that.
                - Keep each file under roughly 150 lines. Split into a couple of small classes if a
                  single file would run long; every class still lives in the same
                  `vibemod.<name>` package.
                - Make effects juicy where it fits the request: pair visual feedback
                  (`world.spawnParticle(...)` / `player.spawnParticle(...)`) with a sound
                  (`world.playSound(...)` / `player.playSound(...)`) so the mod feels alive, not
                  silent.
                - Expose any value a player would obviously want to tweak — counts, durations,
                  radii, chances, on/off toggles, named choices — as a "config" knob instead of a
                  hardcoded constant. Pick the narrowest fitting type (boolean/integer/decimal/
                  text/choice) and sane min/max/step for numeric knobs.
                - Read config knobs with `ctx.configBool/configInt/configDouble/configString(key)`
                  INSIDE the event handler or task body, at the exact moment you need the value.
                  NEVER read a config value once in onEnable (or a constructor) and cache it in a
                  field — a knob change must take effect on the very next event/tick, not require
                  a reload. Use `configBool` for boolean knobs, `configInt` for integer knobs,
                  `configDouble` for decimal knobs, and `configString` for text or choice knobs.

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
                - Omitting "usage"/"manual"/"config" means "unchanged". When you do include
                  "config", send the complete knob list the mod should have afterward — not a diff
                  of just the knobs that changed.
                - If you are told your previous edits did not apply cleanly, respond with the FULL
                  project shape (complete files) next, not another edit.

                ================ WORKED EXAMPLES ================

                The following two examples show the exact expected input/output shape, including
                config knobs read live via ctx.configX. Study the JSON formatting (escaped
                newlines and quotes inside "content") as closely as the Java itself.

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
        return "Your previous JSON response failed to compile. javac says:\n\n"
                + javacDiagnostics
                + "\n\nReturn the corrected project as JSON: either the FULL project shape (every file) "
                + "described in the system prompt, or — if the fix is small and surgical — the EDIT shape "
                + "({\"edits\":[{\"path\":...,\"find\":...,\"replace\":...}]}) whose \"find\" matches the "
                + "current source of that file exactly once.";
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
        String icon = optionalString(obj, "icon");

        if (hasEdits) {
            String name = optionalString(obj, "name");
            String description = optionalString(obj, "description");
            String mainClass = optionalString(obj, "mainClass");
            List<GeneratedProject.EditBlock> edits = parseEdits(editsArray);
            return new GeneratedProject(name, description, usage, manual, icon, mainClass, List.of(), config, edits);
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

        return new GeneratedProject(name, description, usage, manual, icon, mainClass, files, config, null);
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
