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
    /** {@code PlatformInfo.profileId()} value for the Fabric host (MC 26.1+). */
    public static final String FABRIC_ID = "fabric";
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
              Adventure `Audience`s, so `sendMessage(Component)` just works.""";

    private static final String PAPER_THREADING = """
            - Event handler methods and Runnables passed to ctx.repeat/ctx.later already run
              on the main server thread — do not spawn your own threads and do not attempt to
              hop threads yourself.""";

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
              methods are available.""";

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
              plain constants.""";

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
            PAPER_ICON_INSTRUCTION);

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
            PAPER_ICON_INSTRUCTION);

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
     * Fabric on MC 26.1+. The {@code pluginDescriptor} is deliberately empty:
     * {@code /vibe export} is not supported on the loaders in v1 (§6.3), and
     * {@link com.gijsm.vibemod.store.JarExporter#supported()} gates on the
     * profile id rather than on this field, so there is nothing honest to put
     * here.
     */
    public static final PlatformProfile FABRIC = new PlatformProfile(
            FABRIC_ID,
            "Fabric 26.1+",
            loaderRole("Fabric", "fabric.mod.json"),
            LOADER_API_SOURCE_BLOCK,
            LOADER_IMPORT_RULES,
            LOADER_CHEAT_SHEET,
            LOADER_THREADING,
            LoaderExamples.LOADER_FEW_SHOTS,
            "",
            LOADER_ICON_INSTRUCTION);

    /**
     * NeoForge on MC 26.1+ (§6.2, Phase E).
     *
     * <p>Identical to {@link #FABRIC} but for the role line's one word, and
     * that is the finding rather than a shortcut — see the section comment
     * above. In particular the cheat sheet deliberately does NOT list NeoForge
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
            LOADER_ICON_INSTRUCTION);

    /** Every profile this build knows. */
    public static List<PlatformProfile> all() {
        return List.of(PAPER_MODERN, PAPER_LEGACY, FABRIC, NEOFORGE);
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
