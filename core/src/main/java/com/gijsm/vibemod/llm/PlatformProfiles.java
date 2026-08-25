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
 */
public final class PlatformProfiles {

    /** {@code PlatformInfo.profileId()} value for Paper 1.21.7 and newer. */
    public static final String PAPER_MODERN_ID = "paper-modern";
    /** {@code PlatformInfo.profileId()} value for Paper 1.20.6 through 1.21.6. */
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
              `/vibe do <mod> <name>` action.""";

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

    private static final String PAPER_THREADING = """
            - Event handler methods and Runnables passed to ctx.repeat/ctx.later already run
              on the main server thread — do not spawn your own threads and do not attempt to
              hop threads yourself.""";

    /**
     * The other half of the V3 §D move: Bukkit-shaped craft advice that was
     * also being shown to loader mods, which have neither
     * {@code world.spawnEntity} nor {@code player.getUniqueId}.
     */
    private static final String PAPER_CRAFT = """
            - To spawn an entity, use `world.spawnEntity(location, EntityType.X)`. Never try to
              construct entity instances directly.
            - Persistent per-player state is fine as a plain `HashMap<UUID, ...>` field on your
              Mod class or listener, keyed by `player.getUniqueId()`. Do not use static
              mutable state shared across mod instances beyond that.
            - Make effects juicy where it fits the request: pair visual feedback
              (`world.spawnParticle(...)` / `player.spawnParticle(...)`) with a sound
              (`world.playSound(...)` / `player.playSound(...)`) so the mod feels alive, not
              silent.""";

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

    private static final String PAPER_ROLE_MODERN = """
            You are an expert Paper 1.21.8 gameplay-mod author. You write small, delightful,
            self-contained Minecraft server mods entirely in Java, targeting exactly one API:
            the Mod/VibeContext contract shown below. You never touch anything else.

            Your mods run hot-loaded inside a host plugin called VibeMod. A mod is not a
            Bukkit plugin: it is one or more plain Java classes, exactly one of which
            implements Mod, compiled in-process and loaded into a child class loader.
            Everything your mod does with the Bukkit API must be routed through the
            VibeContext instance you are handed in onEnable, so the host can cleanly tear
            your mod down later.""";

    private static final String PAPER_ROLE_LEGACY = """
            You are an expert Paper 1.20.6-1.21.6 gameplay-mod author. You write small,
            delightful, self-contained Minecraft server mods entirely in Java, targeting
            exactly one API: the Mod/VibeContext contract shown below. You never touch
            anything else.

            Your mods run hot-loaded inside a host plugin called VibeMod. A mod is not a
            Bukkit plugin: it is one or more plain Java classes, exactly one of which
            implements Mod, compiled in-process and loaded into a child class loader.
            Everything your mod does with the Bukkit API must be routed through the
            VibeContext instance you are handed in onEnable, so the host can cleanly tear
            your mod down later.""";

    private static final String PAPER_CHEAT_SHEET_MODERN = """
            - Use only real Paper 1.21 enum constants for Material, EntityType, Sound, and
              Particle. Do not invent names. If unsure, prefer a very common, obviously-real
              constant (e.g. Material.DIAMOND_SWORD, EntityType.ZOMBIE, Sound.ENTITY_PLAYER_LEVELUP,
              Particle.CLOUD) over a guess.
            - Attributes use the SHORT 1.21.3+ names: `Attribute.MAX_HEALTH`,
              `Attribute.MOVEMENT_SPEED`, `Attribute.ATTACK_DAMAGE`, `Attribute.SCALE`. The old
              `GENERIC_`/`PLAYER_`/`ZOMBIE_` prefixes were removed - never use them.
            - `ItemMeta#setEnchantmentGlintOverride(Boolean)` and the other 1.20.5+ item-data
              methods are available.
            """ + PAPER_CRAFT;

    /**
     * The era table §6.2 asks for. Everything here is a compile error on
     * 1.20.6-1.21.6 if the model reaches for the modern spelling instead.
     */
    private static final String PAPER_CHEAT_SHEET_LEGACY = """
            - Use only enum constants that exist in Paper 1.20.6. Do not invent names. If
              unsure, prefer a very common, obviously-real constant (e.g.
              Material.DIAMOND_SWORD, EntityType.ZOMBIE, Sound.ENTITY_PLAYER_LEVELUP,
              Particle.CLOUD) over a guess.
            - THIS SERVER IS PRE-1.21.7. The following renames had NOT happened yet, so the
              modern spellings do not compile here:
              * Attributes keep their long prefixes: `Attribute.GENERIC_MAX_HEALTH`,
                `Attribute.GENERIC_MOVEMENT_SPEED`, `Attribute.GENERIC_ATTACK_DAMAGE`,
                `Attribute.GENERIC_ARMOR`, `Attribute.GENERIC_SCALE`. NEVER the short
                1.21.3+ forms (`Attribute.MAX_HEALTH` and friends).
              * `AttributeInstance`/`AttributeModifier` still take a `NamespacedKey` +
                `AttributeModifier.Operation`; prefer `setBaseValue(...)` over modifiers when
                you can, it is stable across the whole range.
              * Prefer `PotionEffectType.SPEED`-style constants that predate 1.21 and the
                Registry migration; avoid anything you only remember from 1.21.4+ release
                notes.
              * `Enchantment` constants are still fields on `Enchantment` (e.g.
                `Enchantment.DURABILITY` era naming may differ) - if you are not certain of
                an enchantment's constant on 1.20.6, avoid enchantments entirely rather than
                guessing.
            - Do NOT call `ItemMeta#setEnchantmentGlintOverride(...)`, `ItemMeta#setItemModel`,
              `ItemMeta#setTooltipStyle` or any other data-component setter introduced after
              1.20.6. For a "shiny" item, apply a real (hidden) enchantment or skip the effect.
            - Do NOT use `Registry`-based lookups for Sound/Particle/Enchantment; use the
              plain constants.
            """ + PAPER_CRAFT;

    private static final String PAPER_ICON_INSTRUCTION = """
            - "icon" is ONE thematic, obtainable ITEM Material name from vanilla Minecraft,
              in UPPER_SNAKE_CASE, that best represents the mod to a player browsing a menu
              (e.g. "CHICKEN" for a mod about chickens, "SUGAR" for a speed effect, "TNT" for
              an explosion mod, "DIAMOND_SWORD" for a combat mod). It must be a real obtainable
              item a player could hold, NEVER a block-only or technical/internal material (e.g.
              never "BEDROCK", "COMMAND_BLOCK", "AIR", "STRUCTURE_VOID"), and NEVER "AIR" itself.""";

    /**
     * Paper 1.21.7+. The exported descriptor still declares
     * {@code api-version: '1.20'} (§6.3): {@code api-version} governs legacy
     * data conversion, not which API exists, and declaring a floor above the
     * running server makes Paper refuse the plugin outright — so the lowest
     * honest floor is the one that lets an export travel.
     */
    public static final PlatformProfile PAPER_MODERN = new PlatformProfile(
            PAPER_MODERN_ID,
            "Paper 1.21.7+",
            PAPER_ROLE_MODERN,
            PAPER_API_SOURCE_BLOCK,
            PAPER_IMPORT_RULES,
            PAPER_CHEAT_SHEET_MODERN,
            PAPER_THREADING,
            PromptExamples.PAPER_FEW_SHOTS,
            "1.20",
            PAPER_ICON_INSTRUCTION,
            "Mod",
            CTX_CONFIG_CONTRACT);

    /**
     * Paper 1.20.6-1.21.6. {@code api-version: '1.20'} — the floor VibeMod
     * itself now declares (§6.3), and the only honest claim for a jar built out
     * of code that compiled against a pre-1.21.7 server.
     */
    public static final PlatformProfile PAPER_LEGACY = new PlatformProfile(
            PAPER_LEGACY_ID,
            "Paper 1.20.6-1.21.6",
            PAPER_ROLE_LEGACY,
            PAPER_API_SOURCE_BLOCK,
            PAPER_IMPORT_RULES,
            PAPER_CHEAT_SHEET_LEGACY,
            PAPER_THREADING,
            PromptExamples.PAPER_FEW_SHOTS,
            "1.20",
            PAPER_ICON_INSTRUCTION,
            "Mod",
            CTX_CONFIG_CONTRACT);

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

    private static final String LOADER_CHEAT_SHEET = """
            - THE ctx.on* HOOKS ARE THE ENTIRE EVENT SURFACE. There is no listener object
              and no event bus. They are: onPlayerJoin, onPlayerQuit, onServerTick, onChat,
              onBlockBreak, onUseBlock, onUseItem, onEntityDeath, onPlayerDeath, onRespawn.
              If a request needs an event that is not on that list, build the closest
              tasteful approximation out of the ones that are (onServerTick can poll for a
              great deal) rather than inventing a hook.
            - The cancelling hooks (onChat, onBlockBreak, onUseBlock, onUseItem) return
              `boolean`: return `true` to let it happen, `false` to cancel. There is no
              `setCancelled`.
            - MC 26.x renamed things you may remember differently:
              * `ResourceLocation` is now `net.minecraft.resources.Identifier`
                (`Identifier.withDefaultNamespace("stone")`,
                `Identifier.fromNamespaceAndPath("minecraft", "stone")`).
              * Text is `net.minecraft.network.chat.Component`: `Component.literal("hi")`,
                `Component.translatable("key")`. Send it with
                `player.sendSystemMessage(component)` or, in a command,
                `src.sendSuccess(() -> component, false)`.
              * A command's source is `CommandSourceStack`: `src.getPlayer()` (null for the
                console), `src.getServer()`, `src.sendSystemMessage(...)`.
              * Permission levels are gone from CommandSourceStack; do not check permissions
                yourself, the host already did.
            - Client features go inside `ctx.client(c -> { ... })` and NOWHERE else. That
              block does not run at all on a dedicated server, so a mod with client
              features still works there - it just has no HUD. Never import
              `net.minecraft.client.*` yourself: use the ClientContext getters shown below,
              which are the same on every version.
            - `c.hud(id, (canvas, tickDelta) -> ...)` draws with the HudCanvas shown below.
              Keep it cheap: it runs every frame and the host times it.
            - `c.key(label, "G", () -> ...)` leases one of 8 shared key slots. There are 8
              in total across ALL loaded mods, so lease one only if the mod is really about
              a key.
            - If your mod uses `ctx.client(...)`, say so in the manual: "This is a client
              feature: it draws on your own screen and does nothing on a dedicated server."
            """;

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
            LOADER_CHEAT_SHEET,
            LOADER_THREADING,
            LoaderExamples.LOADER_FEW_SHOTS,
            "",
            LOADER_ICON_INSTRUCTION,
            "Mod",
            CTX_CONFIG_CONTRACT);

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
            LOADER_CHEAT_SHEET,
            LOADER_THREADING,
            LoaderExamples.LOADER_FEW_SHOTS,
            "",
            LOADER_ICON_INSTRUCTION,
            "Mod",
            CTX_CONFIG_CONTRACT);

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
            - NEVER register content: no items, no blocks, no entity types, no recipes, no
              `Registry.register` of any kind. Registries are frozen long before your mod
              loads.""";

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
              for, so say so in the manual and never tell the player a specific key without
              adding that they can rebind it in Options -> Controls.
            - HUD: `HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("yourmod",
              "thing"), (graphics, delta) -> ...)` from `onInitializeClient()`. Draw with
              `graphics.fill(x1, y1, x2, y2, argb)` and
              `graphics.text(client.font, "text", x, y, argb)`; sizes come from
              `graphics.guiWidth()`/`guiHeight()`. Keep it cheap: it runs every frame and a
              renderer that overruns its time budget gets your mod disabled.
            - SCREENS: you may subclass `net.minecraft.client.gui.screens.Screen` and open it
              from client code. The host closes it for you if your mod is disabled while it
              is open.
            - STILL NOT AVAILABLE: `Registry.register` of any kind (items, blocks, entity
              types, recipes), resource packs, and `ClientCommandRegistrationCallback`. The
              host refuses these today.
            - If your mod does its setup when the server starts, register
              `ServerLifecycleEvents.SERVER_STARTING` (or `SERVER_STARTED`) normally: the host
              replays it for you if the server is already running when you are loaded.
            - There is no per-tick scheduler. Count ticks in an `END_SERVER_TICK` handler
              (`if (++ticks % 200 == 0) { ... }` is once every ten seconds).
            - MOJANG NAMES, 26.x SPELLINGS. You may remember some of these differently:
              * `World` is `Level`, `PlayerEntity` is `Player`, `ServerPlayerEntity` is
                `ServerPlayer`, `Text` is `Component`, `ItemStack`/`BlockPos`/`BlockState`/
                `MinecraftServer` keep their names.
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
            - Use `net.minecraft.*` types as the game hands them to you: inspect them and act
              on them, do not extend or replace them.""";

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
            NATIVE_FABRIC_CHEAT_SHEET,
            NATIVE_FABRIC_THREADING,
            NativeFabricExamples.NATIVE_FABRIC_FEW_SHOTS,
            "",
            LOADER_ICON_INSTRUCTION,
            "net.fabricmc.api.ModInitializer",
            NATIVE_FABRIC_CONFIG_CONTRACT);

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
