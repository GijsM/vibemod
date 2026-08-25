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

    /** Every profile this build knows. */
    public static List<PlatformProfile> all() {
        return List.of(PAPER_MODERN, PAPER_LEGACY);
    }

    /** The profile for an id, falling back to {@link #PAPER_MODERN} with a logged warning. */
    public static PlatformProfile byId(String id) {
        for (PlatformProfile p : all()) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        java.util.logging.Logger.getLogger(PlatformProfiles.class.getName()).warning(
                "Unknown platform profile '" + id + "' (this build ships "
                        + PAPER_MODERN_ID + " and " + PAPER_LEGACY_ID
                        + "; fabric/neoforge arrive in Phases D/E) - falling back to " + PAPER_MODERN_ID);
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
