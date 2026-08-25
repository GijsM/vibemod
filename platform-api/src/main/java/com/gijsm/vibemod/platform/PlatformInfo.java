package com.gijsm.vibemod.platform;

/**
 * What we are running on, expressed as capabilities. Immutable after boot.
 *
 * <p>Design invariant (ARCHITECTURE-V2 §0#8): core code gates features on
 * these probes, never on version-string comparisons. Version strings exist
 * here only for display, prompt profiles and meta.json stamping.
 */
public interface PlatformInfo {

    /** {@code "paper"}, {@code "fabric"} or {@code "neoforge"} — the meta.json v3 value. */
    String platformName();

    /** The running Minecraft version as reported by the platform, e.g. {@code "1.21.8"} or {@code "26.2"}. */
    String mcVersion();

    /** True when the vanilla dialog UI can be shown to players (MC 1.21.6+ protocol + a server API for it). */
    boolean hasDialogs();

    /** True when {@code javax.tools} resolved a compiler (system javac or bundled ECJ). */
    boolean hasSystemCompiler();

    /** True when this process has a physical client (singleplayer / LAN host). False on dedicated servers and on Paper. */
    boolean hasClient();

    /** True when real top-level dynamic commands can be registered (Paper's command map; Brigadier injection on loaders). */
    boolean hasNativeCommandMap();

    /** True on a dedicated server, false when running inside a client process. */
    boolean isDedicatedServer();

    // ---- era capabilities (Phase C: the 1.20.6 floor) -------------------
    // Probes, never version comparisons (§0#8). Defaults are the conservative
    // answer so a host that does not care need not implement them.

    /**
     * True when an item's enchantment glint can be forced on
     * ({@code ItemMeta#setEnchantmentGlintOverride}, MC 1.20.5+). The dialog
     * renderer's "this mod is running" cue depends on it.
     */
    default boolean hasItemGlintOverride() {
        return false;
    }

    /**
     * True when the command tree can be pushed to a connected player
     * ({@code Player#updateCommands} on Paper, {@code sendCommands} on the
     * loaders) so a freshly registered dynamic command tab-completes without
     * a rejoin.
     */
    default boolean hasCommandResync() {
        return false;
    }

    /**
     * The {@code PlatformProfile} id this host should generate code for —
     * {@code "paper-modern"}, {@code "paper-legacy"}, {@code "fabric"} or
     * {@code "neoforge"} (ARCHITECTURE-V2 §6.2). Kept here, next to
     * {@link #mcVersion()}, because the era split is the host's own knowledge;
     * core only looks the id up in its profile table.
     */
    String profileId();
}
