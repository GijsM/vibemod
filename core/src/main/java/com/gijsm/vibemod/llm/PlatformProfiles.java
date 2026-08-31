package com.gijsm.vibemod.llm;

import java.util.List;

import com.gijsm.vibemod.platform.PlatformInfo;

/**
 * The v1 profile table (ARCHITECTURE-V2 §6.2). Phase C ships the two Paper
 * eras; {@code fabric} and {@code neoforge} join in Phases D and E, which is
 * why {@link #byId} names them in its error message rather than pretending
 * they do not exist.
 *
 * <p>The era split is the reason this table exists at all. Paper's own enum and
 * attribute vocabulary was rewritten across 1.20.5–1.21.3 — {@code Attribute
 * .GENERIC_MAX_HEALTH} became {@code Attribute.MAX_HEALTH}, {@code Registry}
 * lookups replaced several enums — so a prompt that teaches only the modern
 * names produces code that cannot compile on a 1.20.6 server, and every such
 * miss costs a self-heal round of real money.
 *
 * <p><b>The split is no longer where the API knowledge lives.</b> Measuring all
 * 21 supported {@code paper-api} jars (docs/API-VOCABULARY.md) showed the
 * vocabulary breaks at 1.20.5, 1.21 and 1.21.3, while these profiles split at
 * 1.21.7 — the dialog UI, a host concern. Two eras cannot express three
 * boundaries, and every measured prompt defect was an instance of that. So the
 * API half of the prompt is now a {@link PromptRule} table
 * ({@link PromptRules#PAPER}, shared by both Paper profiles) evaluated against
 * the running server's {@link com.gijsm.vibemod.platform.ApiVocabulary}, and
 * {@link #paperProfileIdFor} is demoted to what a version string can honestly
 * answer: which display range to print, and which host UI to use.
 */
public final class PlatformProfiles {

    /** {@code PlatformInfo.profileId()} value for Paper 1.21.7 and newer. */
    public static final String PAPER_MODERN_ID = "paper-modern";
    /** {@code PlatformInfo.profileId()} value for Paper 1.20 through 1.21.6. */
    public static final String PAPER_LEGACY_ID = "paper-legacy";
    /**
     * {@code PlatformInfo.profileId()} value for the Fabric host (MC 26.1+).
     *
     * <p>Since V3 this resolves to the <b>native</b> profile: generated Fabric
     * mods are ordinary Fabric mods now (Phase 0 §E). The v2 loader profile is
     * still here under {@link #FABRIC_LEGACY_ID}.
     */
    public static final String FABRIC_ID = "fabric";
    /**
     * The pre-V3 Fabric profile: {@code Mod}/{@code VibeContext}, curated
     * {@code ctx.on*} hooks.
     *
     * <p>Kept, and kept reachable, because the stored corpus was written
     * against it. Nothing selects it automatically — a host asks for
     * {@code "fabric"} and gets the native profile — but an edit or a fix round
     * on a legacy mod has somewhere honest to point, and the self-test uses it
     * to keep proving the loader-neutrality claim against NeoForge.
     */
    public static final String FABRIC_LEGACY_ID = "fabric-legacy";
    /** {@code PlatformInfo.profileId()} value for the NeoForge host (MC 26.1+, Phase E). */
    public static final String NEOFORGE_ID = "neoforge";

    private PlatformProfiles() {
    }

    // ------------------------------------------------------------------
    // Shared Paper text: the sdk flavor, the registration contract, and the
    // few-shots are identical across both Paper eras (§6.2 — the examples'
    // enum names were checked against 1.20.6 and hold there too).
    // ------------------------------------------------------------------

    private static final String PAPER_API_SOURCE_BLOCK =
            "--- com/gijsm/vibemod/api/Mod.java ---\n"
                    + GeneratedApiSources.MOD + "\n"
                    + "--- com/gijsm/vibemod/api/VibeContext.java ---\n"
                    + GeneratedApiSources.VIBE_CONTEXT + "\n"
                    + "--- com/gijsm/vibemod/api/ModCommandHandler.java ---\n"
                    + GeneratedApiSources.MOD_COMMAND_HANDLER + "\n";

    /**
     * The registration contract, moved here from the shared prompt skeleton in
     * V3 Phase 0 §D.
     *
     * <p>It had been sitting in {@code PromptLibrary} and therefore went into
     * <em>every</em> profile's prompt, including the loaders' — where
     * {@code Bukkit} does not exist and {@code ctx.listen} is not on the api at
     * all. Text a model cannot act on is not free: it is tokens spent teaching a
     * vocabulary the compiler will then reject.
     */
    private static final String PAPER_REGISTRATION = """
            - NEVER call `Bukkit.getPluginManager().registerEvents(...)`,
              `Bukkit.getScheduler()...`, or `Bukkit.getCommandMap()` directly. Registration
              always goes through the VibeContext you are given: `ctx.listen(...)` for event
              listeners, `ctx.repeat(...)` / `ctx.later(...)` for scheduled work,
              `ctx.command(...)` for a real top-level command, `ctx.action(...)` for a named
              `/vibe do <mod> <name>` action.
            - To spawn an entity, use `world.spawnEntity(location, EntityType.X)`. Never try to
              construct entity instances directly.
            - Persistent per-player state is fine as a plain `HashMap<UUID, ...>` field on your
              Mod class or listener, keyed by `player.getUniqueId()`. Do not use static
              mutable state shared across mod instances beyond that.""";

    /**
     * Adventure is now an officially allowed import root: 88+ of the stored
     * corpus already uses it, it is a standalone library rather than a server
     * internal, and it is the project's own text currency (ARCHITECTURE-V2 §1,
     * §6.2). Banning it in the prompt while the corpus depended on it was the
     * single largest source of avoidable self-heal rounds.
     */
    private static final String PAPER_IMPORT_RULES = """
            - Imports are limited to `java.*`, `org.bukkit.*` and `net.kyori.adventure.*` ONLY.
              NEVER import `net.minecraft.*`, NEVER `io.papermc.*` internals, and NEVER
              `java.lang.reflect.*` or any other reflection API. If the request seems to need
              something outside this surface, implement the closest tasteful approximation
              using only those three roots. Adventure is the right way to build any text you
              send a player (`Component.text(...)`), and `Player`/`CommandSender` are
              Adventure `Audience`s, so `sendMessage(Component)` just works.
            """ + PAPER_REGISTRATION;

    /**
     * Empty on purpose: Paper's threading contract is now a probe-predicated
     * rule pair, {@code paper.threading.main} / {@code paper.threading.regionised}
     * in {@link PromptRules#PAPER}.
     *
     * <p>It had to move. A {@code threadingContract} on the profile can only say
     * one thing per profile, and Folia is served by BOTH Paper profiles — it
     * exists at 1.20.6 and at 26.2 — so there is no profile split that could
     * carry the difference. Adding a third {@code paper-folia} profile would have
     * meant four, since the era axis does not go away, and each with a
     * hand-maintained copy of the other profile's text: precisely the duplication
     * the rule table replaced.
     *
     * <p>The loader profiles keep their {@code LOADER_THREADING} string, and that
     * is not an inconsistency. On Fabric and NeoForge one profile really does
     * mean one threading model, so the field still states a fact about the
     * profile. On Paper it no longer does.
     */
    private static final String PAPER_THREADING = "";

    /**
     * The {@code files[]} rule for every profile that has only ever accepted
     * Java (V3 Phase 2 §E). Byte-for-byte the two bullets that used to be
     * hardcoded in {@code PromptLibrary}'s skeleton, so the four profiles that
     * did not change produce a byte-identical prompt.
     */
    private static final String JAVA_ONLY_FILES = """
            - Every entry in "files" has a "path" ending in ".java" and "content" holding the
              complete, compilable source of that file (proper escaping of quotes/newlines
              since this is a JSON string).
            - ALL files declare `package vibemod.<name lowercased>;` at the top (the mod name
              from the JSON, lowercased, no dots, no dashes).""";

    /**
     * The native Fabric profile's {@code files[]} rule (V3 Phase 2 §A/§E): Java
     * plus a real {@code data/**}/{@code assets/**} resource tree.
     *
     * <p>The namespace is stated rather than left to the model. It is rewritten
     * onto the canonical one either way ({@code ModResources.canonicalize}), but
     * a model that writes the right one produces a mod whose Java and whose
     * lang keys agree — and Java is not rewritten.
     */
    private static final String NATIVE_FABRIC_FILES = """
            - Every "files" entry is either a JAVA SOURCE - "path" ending in ".java", "content"
              the complete compilable source - or a RESOURCE FILE whose "path" starts with
              "data/" or "assets/" (see RESOURCE FILES below).
            - ALL java files declare `package vibemod.<name lowercased>;` at the top (the mod
              name from the JSON, lowercased, no dots, no dashes).""";

    /**
     * The config-knob contract for every profile that HAS config: expose the
     * tunables, and read them live rather than caching them.
     *
     * <p>Profile data since V3 because a native Fabric mod has no
     * {@code VibeContext} and so no {@code ctx.configX} to read them with
     * (§E) — the one place where "the same rules everywhere" stopped being
     * true.
     */
    private static final String CTX_CONFIG_CONTRACT = """
            - Expose any value a player would obviously want to tweak — counts, durations,
              radii, chances, on/off toggles, named choices — as a "config" knob instead of a
              hardcoded constant. Pick the narrowest fitting type (boolean/integer/decimal/
              text/choice) and sane min/max/step for numeric knobs.
            - Read config knobs with `ctx.configBool/configInt/configDouble/configString(key)`
              INSIDE the event handler or task body, at the exact moment you need the value.
              NEVER read a config value once in onEnable (or a constructor) and cache it in a
              field — a knob change must take effect on the very next event/tick, not require
              a reload. Use `configBool` for boolean knobs, `configInt` for integer knobs,
              `configDouble` for decimal knobs, and `configString` for text or choice knobs.""";

    /**
     * Shared by BOTH Paper profiles, and deliberately free of a version number.
     *
     * <p>It used to name one ("expert Paper 1.21.8", "expert Paper 1.20.6-1.21.6")
     * and it was the FIRST thing in the prompt, which made the shared prefix
     * between the two Paper profiles 27 characters. Worse, it was a claim: the
     * modern profile is selected for eight versions up to 26.2, so it told a
     * 26.2 server it was 1.21.8. The version is now stated once, as a measured
     * fact, in the prompt's "THIS SERVER" section — where the model reads it next
     * to the constants that server actually declares.
     */
    private static final String PAPER_ROLE = """
            You are an expert Paper gameplay-mod author. You write small, delightful,
            self-contained Minecraft server mods entirely in Java, targeting exactly one API:
            the Mod/VibeContext contract shown below. You never touch anything else.

            Your mods run hot-loaded inside a host plugin called VibeMod. A mod is not a
            Bukkit plugin: it is one or more plain Java classes, exactly one of which
            implements Mod, compiled in-process and loaded into a child class loader.
            Everything your mod does with the Bukkit API must be routed through the
            VibeContext instance you are handed in onEnable, so the host can cleanly tear
            your mod down later.""";

    private static final String PAPER_ICON_INSTRUCTION = """
            - "icon" is ONE thematic, obtainable ITEM Material name from vanilla Minecraft,
              in UPPER_SNAKE_CASE, that best represents the mod to a player browsing a menu
              (e.g. "CHICKEN" for a mod about chickens, "SUGAR" for a speed effect, "TNT" for
              an explosion mod, "DIAMOND_SWORD" for a combat mod). It must be a real obtainable
              item a player could hold, NEVER a block-only or technical/internal material (e.g.
              never "BEDROCK", "COMMAND_BLOCK", "AIR", "STRUCTURE_VOID"), and NEVER "AIR" itself.""";

    /**
     * Paper 1.21.7+. The exported descriptor still declares
     * {@code api-version: '1.20'} (§6.3).
     *
     * <p><b>Invariant — do not raise this to signal a minimum version.</b>
     * {@code api-version} governs legacy data conversion (materials, the
     * Commodore bytecode rewriter), not which API exists. Declaring a value
     * above the running server makes Paper refuse the plugin outright with
     * {@code InvalidPluginException: Unsupported API version …} before any code
     * runs — so raising it does not "require" a newer server, it only stops
     * older ones from loading a plugin that would have worked. The lowest
     * honest value is therefore the one that lets an export travel furthest.
     * See ARCHITECTURE-V2 §10.6 and {@code paper/src/main/resources/plugin.yml}.
     */
    public static final PlatformProfile PAPER_MODERN = new PlatformProfile(
            PAPER_MODERN_ID,
            "Paper 1.21.7+",
            PAPER_ROLE,
            PAPER_API_SOURCE_BLOCK,
            PAPER_IMPORT_RULES,
            PromptRules.PAPER_PROFILE,
            PAPER_THREADING,
            PromptExamples.PAPER_FEW_SHOTS,
            "1.20",
            PAPER_ICON_INSTRUCTION,
            "Mod",
            CTX_CONFIG_CONTRACT,
            JAVA_ONLY_FILES);

    /**
     * Paper 1.20-1.21.6. The display name used to say "1.20.6-1.21.6" while the
     * profile actually served 1.20 upward (ARCHITECTURE-V2 §10.6); it is now the
     * measured range. The role line no longer names a range at all — see
     * {@link #PAPER_ROLE}.
     *
     * <p>{@code api-version: '1.20'} — the floor VibeMod itself declares
     * (§6.3), and the only honest claim for a jar built out of code that
     * compiled against a pre-1.21.7 server. Same invariant as
     * {@link #PAPER_MODERN}: it governs legacy data conversion, not which API
     * exists, and raising it to signal a minimum version makes Paper refuse the
     * plugin outright.
     */
    public static final PlatformProfile PAPER_LEGACY = new PlatformProfile(
            PAPER_LEGACY_ID,
            "Paper 1.20-1.21.6",
            PAPER_ROLE,
            PAPER_API_SOURCE_BLOCK,
            PAPER_IMPORT_RULES,
            PromptRules.PAPER_PROFILE,
            PAPER_THREADING,
            PromptExamples.PAPER_FEW_SHOTS,
            "1.20",
            PAPER_ICON_INSTRUCTION,
            "Mod",
            CTX_CONFIG_CONTRACT,
            JAVA_ONLY_FILES);

    // ------------------------------------------------------------------
    // The two loaders (MC 26.1+). Phase D wrote this half expecting NeoForge to
    // reuse it, and Phase E confirmed the expectation: everything below is
    // shared verbatim EXCEPT the role line's one word.
    //
    // That is a property of the design rather than a convenience. The sdk mod
    // flavor (§4.1) is loader-NEUTRAL — nothing under `sdk/src/mod` names a
    // Fabric or a NeoForge type, because every registration a generated mod
    // makes goes through the VibeContext it is handed. So the api source block,
    // the import bans, the threading contract, the cheat sheet and all three
    // worked examples are the same text on both loaders, and a mod generated
    // for one compiles unchanged on the other. Only the stored `platform` stamp
    // (§5) keeps them apart, and that is a UX decision, not a technical one.
    // ------------------------------------------------------------------

    private static final String LOADER_API_SOURCE_BLOCK =
            "--- com/gijsm/vibemod/api/Mod.java ---\n"
                    + GeneratedApiSources.MOD + "\n"
                    + "--- com/gijsm/vibemod/api/VibeContext.java ---\n"
                    + GeneratedApiSources.MOD_VIBE_CONTEXT + "\n"
                    + "--- com/gijsm/vibemod/api/ModCommandHandler.java ---\n"
                    + GeneratedApiSources.MOD_MOD_COMMAND_HANDLER + "\n"
                    + "--- com/gijsm/vibemod/api/TaskHandle.java ---\n"
                    + GeneratedApiSources.MOD_TASK_HANDLE + "\n"
                    + "--- com/gijsm/vibemod/api/client/ClientContext.java ---\n"
                    + GeneratedApiSources.CLIENT_CONTEXT + "\n"
                    + "--- com/gijsm/vibemod/api/client/HudCanvas.java ---\n"
                    + GeneratedApiSources.HUD_CANVAS + "\n";

    /**
     * The role line — the ONLY loader-dependent text in either loader profile.
     *
     * @param loader   {@code "Fabric"} or {@code "NeoForge"}
     * @param manifest that loader's mod manifest, named so the model recognises
     *                 the thing it must not write
     */
    private static String loaderRole(String loader, String manifest) {
        return """
            You are an expert Minecraft 26.1+ gameplay-mod author working against the
            official (unobfuscated) Mojang names. You write small, delightful,
            self-contained mods entirely in Java, targeting exactly one API: the
            Mod/VibeContext contract shown below. You never touch anything else.

            Your mods run hot-loaded inside a host mod called VibeMod. A mod is NOT a
            LOADER mod: it has no MANIFEST, no entrypoint, no mixins and no
            registrations of its own. It is one or more plain Java classes, exactly one of
            which implements Mod, compiled in-process and loaded into a child class
            loader. Everything your mod does must be routed through the VibeContext
            instance you are handed in onEnable, so the host can cleanly tear your mod
            down later - including while the game is running."""
                .replace("LOADER", loader)
                .replace("MANIFEST", manifest);
    }

    /**
     * The import rules loader mods live under. The {@code net.fabricmc.*} ban is
     * the load-bearing one and the javadoc on {@code FabricEventBridge} explains
     * why: a Fabric event cannot be unregistered, so a mod that subscribed
     * directly could never be torn down.
     */
    private static final String LOADER_IMPORT_RULES = """
            - Imports are limited to `java.*`, `com.gijsm.vibemod.api.*` (including
              `com.gijsm.vibemod.api.client.*`) and `net.minecraft.*` ONLY.
            - NEVER import `net.fabricmc.*` or `net.neoforged.*`. Every registration goes
              through the VibeContext you are handed - the loader's own event, command,
              keybind and HUD registries are the host's business, not yours, and its events
              cannot be unregistered, so a mod that subscribed directly could never be
              disabled.
            - NEVER write a mixin, subclass `Screen`, hook world/entity rendering, open a
              socket (`java.net.*`), or use reflection (`java.lang.reflect.*`).
            - NEVER register content: no items, no blocks, no entity types, no recipes, no
              data components, no registry entries of any kind. Registries are frozen after
              startup and your mod loads long after that.
            - Use `net.minecraft.*` types read-only: the game objects handed to your hooks
              (ServerPlayer, BlockPos, BlockState, LivingEntity, MinecraftServer) are yours
              to inspect and act on, not to extend or replace.""";

    /**
     * §8.4, restated for the model. This is the paragraph that prevents the
     * single worst failure mode of a client-capable mod: in singleplayer the
     * server thread and the render thread live in one JVM, so touching the wrong
     * one from a HUD renderer produces a race that never throws and never
     * reproduces.
     */
    private static final String LOADER_THREADING = """
            - onEnable/onDisable, every ctx.on* hook, every ctx.command/ctx.action handler
              and every Runnable passed to ctx.repeat/ctx.later run on the MAIN SERVER
              THREAD. Do not spawn threads and do not hop threads yourself.
            - Everything you register inside `ctx.client(...)` - hud, tick, key presses,
              clientCommand - runs on the RENDER THREAD instead. Inside those callbacks use
              ONLY the ClientContext's own getters and your mod's own fields. NEVER touch
              server state (a ServerPlayer, a level, anything a ctx.on* hook gave you) from
              a client callback, or client state from a server hook: in singleplayer both
              sides share one JVM, so such a race is silent and unreproducible.
            - Fields shared between the two sides must be `volatile` (or an
              `AtomicInteger`/`ConcurrentHashMap`). `ctx.config*` reads are thread-safe
              everywhere and can be called from either side.""";

    // The loader cheat sheet now lives in PromptRules.LOADER, unchanged in
    // wording. Every one of its rules is unconditional — the sdk mod flavor is
    // loader-neutral, so there is nothing version-specific to predicate — but it
    // goes through the same table so there is one mechanism, and so its symbols
    // are declared where the offline gate can read them.

    private static final String LOADER_ICON_INSTRUCTION = """
            - "icon" is ONE thematic, obtainable ITEM name from vanilla Minecraft, in
              UPPER_SNAKE_CASE, that best represents the mod to a player browsing a menu
              (e.g. "CHICKEN" for a mod about chickens, "SUGAR" for a speed effect, "TNT"
              for an explosion mod, "DIAMOND_SWORD" for a combat mod). It must be a real
              obtainable item a player could hold, NEVER a block-only or technical material
              (never "BEDROCK", "COMMAND_BLOCK", "AIR", "STRUCTURE_VOID"), and NEVER "AIR".""";

    /**
     * Fabric on MC 26.1+, the v2 way: {@code Mod} + {@code VibeContext} and the
     * ten curated hooks. Superseded for new generations by {@link #FABRIC}
     * (V3 Phase 0 §E) and kept because the stored corpus speaks it.
     *
     * <p>The {@code pluginDescriptor} is deliberately empty: {@code /vibe export}
     * is not supported on the loaders in v1 (§6.3), and
     * {@link com.gijsm.vibemod.store.JarExporter#supported()} gates on the
     * profile id rather than on this field, so there is nothing honest to put
     * here.
     */
    public static final PlatformProfile FABRIC_LEGACY = new PlatformProfile(
            FABRIC_LEGACY_ID,
            "Fabric 26.1+ (VibeContext)",
            loaderRole("Fabric", "fabric.mod.json"),
            LOADER_API_SOURCE_BLOCK,
            LOADER_IMPORT_RULES,
            PromptRules.LOADER,
            LOADER_THREADING,
            LoaderExamples.LOADER_FEW_SHOTS,
            "",
            LOADER_ICON_INSTRUCTION,
            "Mod",
            CTX_CONFIG_CONTRACT,
            JAVA_ONLY_FILES);

    /**
     * NeoForge on MC 26.1+ (§6.2, Phase E).
     *
     * <p>Identical to {@link #FABRIC_LEGACY} but for the role line's one word,
     * and that is the finding rather than a shortcut — see the section comment
     * above. (V3 Phase 0 gave Fabric a native profile and NeoForge no seams
     * yet, so NeoForge stays on the v2 contract and the pairing that is worth
     * guarding is now with {@code FABRIC_LEGACY}.)
     * In particular the cheat sheet deliberately does NOT list NeoForge
     * event names, though §6.2 left room for them: a generated mod never sees
     * one. The ten curated {@code ctx.on*} hooks ARE its entire event surface,
     * and which loader event the host subscribed to on its behalf is the host's
     * business. Teaching the model {@code PlayerLoggedInEvent} would be
     * teaching it a name it is forbidden to write.
     */
    public static final PlatformProfile NEOFORGE = new PlatformProfile(
            NEOFORGE_ID,
            "NeoForge 26.1+",
            loaderRole("NeoForge", "neoforge.mods.toml"),
            LOADER_API_SOURCE_BLOCK,
            LOADER_IMPORT_RULES,
            PromptRules.LOADER,
            LOADER_THREADING,
            LoaderExamples.LOADER_FEW_SHOTS,
            "",
            LOADER_ICON_INSTRUCTION,
            "Mod",
            CTX_CONFIG_CONTRACT,
            JAVA_ONLY_FILES);

    // ------------------------------------------------------------------
    // V3: the native Fabric profile (Phase 0 §E)
    //
    // The thesis, as a prompt. Everything above teaches a model VibeMod's own
    // API; this teaches it nothing at all, because a generated mod is an
    // ordinary Fabric mod now — a `ModInitializer` registering to real Fabric
    // events, with zero VibeMod imports. That is worth far more than any api
    // block: the model already knows Fabric, and the frontier of what a
    // generated mod can do stops being "whatever VibeMod wrapped" and becomes
    // "whatever Fabric exposes".
    //
    // The host makes that safe rather than the prompt. Every Event.register
    // call site is rewritten into a host shim before defineClass, so the
    // subscription is revocable even though a Fabric Event is not; and the
    // bytecode is policy-checked, so the bans below are enforced rather than
    // merely requested. The prompt still states them, because a violation
    // caught at compile time costs a self-heal round, and a violation never
    // written costs nothing.
    // ------------------------------------------------------------------

    private static final String NATIVE_FABRIC_ROLE = """
            You are an expert Fabric mod author for Minecraft 26.2, working against the
            official (unobfuscated) Mojang names on Java 25. You write small, delightful,
            self-contained gameplay mods in plain Java.

            You write a NORMAL FABRIC MOD. Your main class implements
            `net.fabricmc.api.ModInitializer` and registers everything from
            `onInitialize()` through the ordinary Fabric API, exactly as you would in any
            other Fabric mod. There is no VibeMod API, and you must not import one.

            Two things are different from a mod on disk, and only two. There is no
            `fabric.mod.json` and there are no mixins: the host compiles your source in
            memory and loads it into the running game. And the host can UNLOAD you at any
            moment - so everything you register through the Fabric API is tracked for you
            and revoked when you are disabled. You do not write teardown code and you must
            not try to; just register normally and it is handled.

            If your mod has a client half - a keybind, a HUD, a screen, anything that draws
            or reads input - put it in `onInitializeClient()` by ALSO implementing
            `net.fabricmc.api.ClientModInitializer` on the same class. On a dedicated server
            that half is simply never run, and the rest of your mod still works.""";

    private static final String NATIVE_FABRIC_IMPORT_RULES = """
            - Imports are limited to `java.*`, `net.minecraft.*`, `net.fabricmc.api.*`,
              `net.fabricmc.fabric.api.*`, `com.mojang.*` and `org.joml.*`. Anything else is
              refused by the host before your mod is loaded.
            - NEVER use reflection (`java.lang.reflect.*`, `MethodHandles`), threads
              (`new Thread`, `Executors`, `CompletableFuture.*Async`), processes
              (`Runtime`, `ProcessBuilder`, `System.exit`) or networking (`java.net.*`
              except `java.net.URI`). Your mod shares the server's thread and the server's
              process; all of these outlive a `/vibe disable` and none of them can be
              revoked.
            - NEVER write a mixin (`org.spongepowered.*`), and NEVER touch loader internals
              (`net.fabricmc.loader.*`, `net.fabricmc.fabric.impl.*`,
              `net.fabricmc.fabric.mixin.*`).
            - NEVER call `Event.addPhaseOrdering(...)`. Phase order is global and permanent,
              so it cannot be undone when your mod is disabled.
            - You MAY register real items, blocks and entity types with `Registry.register`
              from `onInitialize()` - see REGISTERING REAL CONTENT below for the rules. Every
              OTHER registry is still refused, and so is registering from anywhere but
              `onInitialize()`.""";

    private static final String NATIVE_FABRIC_THREADING = """
            - `onInitialize()` and every SERVER callback you register run on the MAIN SERVER
              THREAD. Do not spawn threads and do not hop threads yourself.
            - `onInitializeClient()` and every client callback (keybind polling, HUD
              drawing, `ClientTickEvents`) run on the RENDER THREAD instead. NEVER read or
              write server state from client code: in singleplayer the server and the client
              share one JVM, so `server.getPlayerList()` from a HUD renderer is a real data
              race that will corrupt the world rather than a theoretical one. Keep the two
              halves' state in separate fields and let the client half read only
              `Minecraft.getInstance()`.
            - Keep per-mod state in fields on your own classes. A `ConcurrentHashMap` keyed
              by `player.getUUID()` is the normal way to hold per-player state.""";

    private static final String NATIVE_FABRIC_CHEAT_SHEET = """
            - Register from `onInitialize()`, e.g.
              `ServerTickEvents.END_SERVER_TICK.register(server -> ...)`,
              `AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> ...)`,
              `PlayerBlockBreakEvents.BEFORE.register(...)`,
              `ServerPlayConnectionEvents.JOIN.register(...)`,
              `ServerLivingEntityEvents.AFTER_DEATH.register(...)`.
            - COMMANDS are hot. Register `CommandRegistrationCallback.EVENT` from
              `onInitialize()` and build an ordinary Brigadier tree
              (`dispatcher.register(Commands.literal("name").executes(ctx -> ...))`). Your
              command works the instant the mod loads - you do NOT need a `/reload` - and it
              is removed again when the mod is disabled. Pick a name nothing else uses: the
              first registration of a name wins and yours would be rejected.
            - KEYBINDS come from a shared pool of eight slots.
              `KeyMappingHelper.registerKeyMapping(new KeyMapping(...))` returns a DIFFERENT
              `KeyMapping` than you passed in - keep the returned one and poll it with
              `consumeClick()`/`isDown()`. The physical key may not be the one you asked
              for, so the manual must never promise one without saying it is rebindable
              under Options -> Controls.
            - HUD: `HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("yourmod",
              "thing"), (graphics, delta) -> ...)` from `onInitializeClient()`. Draw with
              `graphics.fill(x1, y1, x2, y2, argb)` and
              `graphics.text(client.font, "text", x, y, argb)`; sizes come from
              `graphics.guiWidth()`/`guiHeight()`. Keep it cheap: it runs every frame and a
              renderer that overruns its time budget gets your mod disabled.
            - SCREENS: you may subclass `net.minecraft.client.gui.screens.Screen` and open it
              from client code. The host closes it for you if your mod is disabled while it
              is open.
            - RESOURCE FILES. You may ship the same `data/**` and `assets/**` tree a real mod
              jar would, as extra "files" entries whose "content" is the file's text. They
              install on load and are removed on disable. The RubySword example below shows
              the whole shape - copy its layout.
              * YOUR NAMESPACE IS `vibemod_<name lowercased>` - the mod name from the JSON,
                lowercased, anything not a-z/0-9 replaced by `_`. Use it everywhere: in paths,
                in ids inside the JSON, and in any translation key your Java names.
              * LIVE IMMEDIATELY, gone again on disable: `data/<ns>/recipe/…`,
                `advancement/…`, `function/<name>.mcfunction`, `loot_table/…`, `predicate/…`,
                `item_modifier/…`, `tags/…`.
              * AN INGREDIENT IS A STRING, NOT AN OBJECT. You may remember
                `{"item": "minecraft:redstone"}`; 26.x REJECTS it. Everywhere a recipe takes
                one (`ingredient` in smelting/blasting/smoking/campfire/stonecutting, each
                value of a shaped `key`, each entry of a shapeless `ingredients`) write
                `"minecraft:redstone"`, a tag `"#minecraft:planks"`, or an array of either.
                This does NOT fail your build: the recipe is silently dropped as the pack
                loads and the mod looks fine until a player tries to craft it.
              * ONLY ON THE NEXT WORLD LOAD: enchantments, dialogs, damage types, jukebox
                songs, painting variants, `worldgen/`. Avoid unless the player asked.
              * CLIENT FILES (`assets/**`) only work on a physical client; on a dedicated
                server they are stored and inert. Text goes in
                `assets/<ns>/lang/en_us.json`; a custom icon needs
                `assets/<ns>/items/<name>.json`, `assets/<ns>/models/item/<name>.json` and the
                texture, in the shapes the example uses.
              * TEXTURES ARE PIXEL GRIDS: you cannot emit binary PNG, so write
                `assets/<ns>/textures/item/<name>.png.grid` = {"palette": {"a": "#8b1a1a",
                ".": "transparent"}, "rows": ["..aa..", ".abba."]}. Square, at most 64x64
                (16x16 is the vanilla item size), every row as long as the row count, every
                character in the palette. The host encodes the PNG.
            - A "CUSTOM ITEM" WITHOUT A REGISTRY: put components on a vanilla item in the
              recipe result. It works on a dedicated server, where a registered item does
              not, so prefer it when a renamed vanilla item is genuinely enough:
              `"result":{"id":"minecraft:amethyst_shard","components":{"minecraft:custom_name":
              {"text":"Ruby Charm","color":"red","italic":false},"minecraft:lore":[{"text":"Warm
              to the touch.","color":"gray","italic":false}],"minecraft:item_model":
              "<ns>:ruby","minecraft:enchantment_glint_override":true}}`
            - REGISTERING REAL CONTENT: items, blocks and entity types only, from
              `onInitialize()` and nowhere else. The RubySword and RubyBlock examples
              below are the whole shape - copy them.
              * ITEMS: `Registry.register(BuiltInRegistries.ITEM, id, new MyItem(new
                Item.Properties()...setId(ResourceKey.create(Registries.ITEM, id))))`. 26.x
                needs `setId(...)` BEFORE the item is constructed or the constructor throws,
                and there is no `SwordItem` class any more - `Item.Properties` carries
                `.sword(ToolMaterial.IRON, 4.0F, -2.4F)`, `.pickaxe(...)`, `.axe(...)`.
                Subclass `Item` for behaviour (`use`, `useOn`, `hurtEnemy`) and end the class
                name in `Item`. Ids are rewritten to your `vibemod_<name>` namespace, so use
                that namespace in the Java too and your recipe, model and id all agree.
              * AN ITEM IS NOTHING WITHOUT ITS FILES: the two-file model, a `.png.grid`
                texture, `"item.<ns>.<name>"` in the lang file (derived from the id, so it
                must match), and a recipe so a player can get one. Registered items reach
                the creative INGREDIENTS tab and creative search automatically.
              * BLOCKS: `Registry.register(BuiltInRegistries.BLOCK, id, new Block(
                BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(3.0F)
                .sound(SoundType.STONE).setId(ResourceKey.create(Registries.BLOCK, id))))`.
                `setId(...)` goes BEFORE construction here too: the `Block` constructor
                bakes the description id AND the loot-table path out of it. Register the
                item you hold under the SAME id, right after:
                `new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix()
                .setId(ResourceKey.create(Registries.ITEM, id)))`.
              * BUDGET YOUR BLOCKSTATES - about 402 are left for the WHOLE server. A block
                costs the PRODUCT of its property value counts: no properties costs 1, one
                boolean costs 2, one 4-value enum costs 4, a fence 32, a door 64, stairs
                80, a wall 324. So PREFER PLAIN CUBES with no blockstate properties; add a
                property only when the block genuinely needs one, and never more than a
                couple. Past the budget the host REFUSES the registration and the message
                tells you how many states were left.
              * A BLOCK IS NOTHING WITHOUT ITS FILES:
                `assets/<ns>/blockstates/<name>.json` = `{"variants": {"": {"model":
                "<ns>:block/<name>"}}}` - WITHOUT IT THE BLOCK IS THE MISSING MODEL; a
                block model parenting `minecraft:block/cube_all`, which is what declares
                `"particle": "#all"` so break particles work; the two-file item model,
                whose model parents the BLOCK model; a `.png.grid` under
                `textures/block/`; `"block.<ns>.<name>"` in the lang file;
                `data/<ns>/loot_table/blocks/<name>.json` or the block drops NOTHING (the
                default drop key is `<ns>:blocks/<path>`); and the block's id in
                `data/minecraft/tags/block/mineable/pickaxe.json`.
              * NEVER REGISTER A RENDER LAYER, and never put a `render_type` key in a
                block model. 26.x has neither the key nor any API for it, whatever you
                remember from 1.20. The layer is derived from the texture's own alpha:
                opaque is solid, all-or-nothing alpha is cutout, partial alpha is
                translucent. Ship the right texture and register nothing.
              * ENTITY TYPES: `EntityType.Builder.of(MyMob::new, MobCategory.CREATURE)
                .build(ResourceKey.create(Registries.ENTITY_TYPE, id))`, registered the same
                way, plus `FabricDefaultAttributeRegistry.register(TYPE, Mob.createAttributes())`
                for anything living. Subclass a vanilla mob so a renderer already exists, and
                spawn it yourself: `TYPE.create(level, EntitySpawnReason.COMMAND)` +
                `level.addFreshEntity(...)`. No spawn eggs, no natural spawning.
              * SINGLEPLAYER AND LAN-HOST ONLY. On a DEDICATED server the host REFUSES the
                registration - use the components-on-a-vanilla-item trick above instead.
              * NO OTHER REGISTRY: not block entities, enchantments, biomes, particles
                or sounds. Use `data/**` for anything datapack-shaped.
            - STILL NOT AVAILABLE: `ClientCommandRegistrationCallback`.
            - If your mod does its setup when the server starts, register
              `ServerLifecycleEvents.SERVER_STARTING` (or `SERVER_STARTED`) normally: the host
              replays it for you if the server is already running when you are loaded.
            - There is no per-tick scheduler. Count ticks in an `END_SERVER_TICK` handler
              (`if (++ticks % 200 == 0)` is every ten seconds).
            - MOJANG NAMES, 26.x SPELLINGS. You may remember some of these differently:
              * `World` is `Level`, `PlayerEntity` is `Player`, `ServerPlayerEntity` is
                `ServerPlayer`, `Text` is `Component`.
              * `ResourceLocation` is now `net.minecraft.resources.Identifier`
                (`Identifier.withDefaultNamespace("stone")`).
              * Text is `net.minecraft.network.chat.Component`: `Component.literal("hi")`,
                `Component.translatable("key")`. Send it with
                `player.sendSystemMessage(component)`.
              * Interaction callbacks return `net.minecraft.world.InteractionResult`:
                `InteractionResult.PASS` lets vanilla carry on, `InteractionResult.FAIL`
                cancels. `PlayerBlockBreakEvents.BEFORE` returns `boolean` instead
                (`false` cancels).
              * The player list is `server.getPlayerList().getPlayers()`.
            - Use `net.minecraft.*` types as the game hands them to you. The ONLY ones you
              may extend are the ones you own an instance of - your own `Item` subclass,
              your own `Block` subclass, your own `Screen`, your own mob class. Never
              replace or wrap a vanilla singleton.""";

    /**
     * There is no {@code ctx} in a native mod, so there is nowhere to read a
     * knob from. Saying so plainly beats letting the model emit a
     * {@code config[]} the mod cannot honour and a manual that promises
     * settings that do not exist.
     */
    private static final String NATIVE_FABRIC_CONFIG_CONTRACT = """
            - THIS MODE HAS NO CONFIG KNOBS. A native Fabric mod has no VibeMod context to
              read them from, so ALWAYS omit "config" from your JSON (or send an empty
              array), and do not describe any settings in the "manual". Put tunable values
              in named `private static final` constants instead, so they are easy to find
              and easy to edit later.""";

    /**
     * Fabric on MC 26.2, natively (V3 Phase 0 §E). What
     * {@link #byId byId("fabric")} resolves to.
     */
    public static final PlatformProfile FABRIC = new PlatformProfile(
            FABRIC_ID,
            "Fabric 26.2 (native)",
            NATIVE_FABRIC_ROLE,
            "(This mode has no VibeMod API. You write a normal Fabric mod.)\n\n",
            NATIVE_FABRIC_IMPORT_RULES,
            // V3’s native cheat sheet, carried across the V4 merge as one
            // unconditional rule: PlatformProfile now holds a PromptRule table
            // instead of a cheatSheet string, and render() concatenates rule
            // text, so the sheet reaches the model unchanged.
            List.of(PromptRule.always("native-fabric-cheat-sheet", NATIVE_FABRIC_CHEAT_SHEET)),
            NATIVE_FABRIC_THREADING,
            NativeFabricExamples.NATIVE_FABRIC_FEW_SHOTS,
            "",
            LOADER_ICON_INSTRUCTION,
            "net.fabricmc.api.ModInitializer",
            NATIVE_FABRIC_CONFIG_CONTRACT,
            NATIVE_FABRIC_FILES);

    /**
     * The "THIS HOST" block for the native Fabric profile (V3 Phase 4 §C),
     * given whether this process is a dedicated server.
     *
     * <p>Prompt text, so it lives with the prompts rather than in the host that
     * knows the boolean — which is also what lets {@code LlmSelfTest} budget the
     * real worst case rather than the profile alone.
     *
     * <p>Every sentence restates, in one direction, a rule {@link #FABRIC}
     * already gives in both. That is the whole point: the profile describes the
     * platform, this describes the process, and before it existed the model had
     * to guess. The live demo showed the bill — a model that had followed the
     * prompt exactly registered an item on a dedicated server, was refused, and
     * spent a repair round rediscovering what the host knew at boot.
     */
    public static String fabricHostFacts(boolean dedicatedServer) {
        return dedicatedServer
                ? """
                  This host is a DEDICATED SERVER, with no client in the process:
                  - Registering items, blocks or entity types is REFUSED here and your mod
                    will fail to load. Put components on a vanilla item in the result
                    instead.
                  - `assets/**` are stored but inert - no texture, model or translation you
                    ship is ever seen. Prefer text players can read.
                  - A `ClientModInitializer` half is skipped: keybinds, HUD and Screens do
                    nothing. Do it server-side, or say in the manual it needs a client."""
                : """
                  This host is a MINECRAFT CLIENT (singleplayer, or a world opened to LAN).
                  The whole surface works here: `assets/**` render, a `ClientModInitializer`
                  half runs, and registering items, blocks and entity types is allowed.""";
    }

    /** Every profile this build knows. */
    public static List<PlatformProfile> all() {
        return List.of(PAPER_MODERN, PAPER_LEGACY, FABRIC, FABRIC_LEGACY, NEOFORGE);
    }

    /** The profile for an id, falling back to {@link #PAPER_MODERN} with a logged warning. */
    public static PlatformProfile byId(String id) {
        for (PlatformProfile p : all()) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        StringBuilder known = new StringBuilder();
        for (PlatformProfile p : all()) {
            known.append(known.isEmpty() ? "" : ", ").append(p.id());
        }
        java.util.logging.Logger.getLogger(PlatformProfiles.class.getName()).warning(
                "Unknown platform profile '" + id + "' (this build ships " + known
                        + ") - falling back to " + PAPER_MODERN_ID);
        return PAPER_MODERN;
    }

    /** The profile the running host asks for. */
    public static PlatformProfile forPlatform(PlatformInfo info) {
        return byId(info.profileId());
    }

    /**
     * The Paper era boundary, as a pure function of the version string so the
     * host has one place to get it right and the self-tests can pin it. Anything
     * unparseable is treated as modern: a newer-than-we-know Paper is far more
     * likely than a fork reporting nonsense while running 1.20.
     *
     * <p><b>This is no longer the authority on what the API looks like.</b> It
     * answers exactly two questions a version string can answer honestly: which
     * display range the prompt prints, and — via
     * {@code PaperPlatformInfo.hasDialogs()} — whether the host may use the
     * native dialog UI. 1.21.7 is the right boundary for both, because that is
     * where {@code io.papermc.paper.dialog.Dialog} first appears. It is the
     * wrong boundary for the API vocabulary, which breaks at 1.20.5, 1.21 and
     * 1.21.3; {@link PromptRules#PAPER} handles that by probing rather than
     * comparing. Do not add vocabulary knowledge here.
     */
    public static String paperProfileIdFor(String mcVersion) {
        int[] parts = parseVersion(mcVersion);
        if (parts == null) {
            return PAPER_MODERN_ID;
        }
        int major = parts[0];
        int minor = parts[1];
        int patch = parts[2];
        if (major > 1) {
            // The 26.x line and beyond: unobfuscated, well past the 1.21.7 split.
            return PAPER_MODERN_ID;
        }
        if (minor > 21 || (minor == 21 && patch >= 7)) {
            return PAPER_MODERN_ID;
        }
        return PAPER_LEGACY_ID;
    }

    /**
     * {@code "1.21.8"} / {@code "26.2"} / {@code "1.20.6-R0.1-SNAPSHOT"} /
     * {@code "26.2.build.117"} -> {major, minor, patch}.
     *
     * <p>Parses the leading numeric components and stops at the first one that is
     * not a number, rather than giving up on the whole string: Paper's 26.x line
     * reports {@code getBukkitVersion()} as {@code "26.2.build.117-..."}, and a
     * server whose version we half-understand is still a server whose major and
     * minor we know. Returns null only when nothing numeric is there at all.
     */
    private static int[] parseVersion(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) {
            return null;
        }
        String cleaned = mcVersion.trim();
        int dash = cleaned.indexOf('-');
        if (dash > 0) {
            cleaned = cleaned.substring(0, dash);
        }
        String[] bits = cleaned.split("\\.");
        int[] out = {0, 0, 0};
        int parsed = 0;
        for (int i = 0; i < 3 && i < bits.length; i++) {
            try {
                out[i] = Integer.parseInt(bits[i]);
            } catch (NumberFormatException stop) {
                break;
            }
            parsed++;
        }
        return parsed == 0 ? null : out;
    }
}
