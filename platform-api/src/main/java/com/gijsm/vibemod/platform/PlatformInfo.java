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
     * The highest {@code --release} generated code may target on this host.
     *
     * <p>Not the same question as "what can this JVM run". A server's bytecode
     * tooling can be much older than the JVM it happens to be launched on:
     * Paper 1.20.6 pipes every dynamically defined class through the ASM 9.7 in
     * its plugin remapper, and ASM 9.7 throws
     * {@code IllegalArgumentException: Unsupported class file major version 69}
     * on anything compiled for Java 25. Targeting the JVM's own feature version
     * therefore produces mods a 1.20.6 server cannot ingest, even though it
     * could execute them — which is precisely the hazard the 1.20.6 floor drop
     * introduced. Hosts answer with the release their own class files use.
     */
    default int maxTargetRelease() {
        return Runtime.version().feature();
    }

    /**
     * What this host's API actually declares, measured off its own classpath at
     * boot.
     *
     * <p>This is the probe that retires an error class rather than an error. The
     * capability booleans above answer questions someone thought to ask; a
     * vocabulary answers the ones nobody did, which is how the prompt came to
     * teach {@code Attribute.GENERIC_MAX_HEALTH} on four versions where only the
     * short form compiles (docs/API-VOCABULARY.md). The prompt builder consumes
     * it instead of asserting era prose, and the pre-compile repair pass will
     * consume the same object.
     *
     * <p>Building one walks a few thousand reflected members, so a host builds
     * it ONCE at boot and returns the cached instance — never per generation.
     *
     * <p>The default is {@link ApiVocabulary#empty()}: a host that has not
     * implemented this measured nothing, and every query answers
     * {@code UNKNOWN}. That degrades correctly — capability-predicated prompt
     * text drops out and a repair pass changes nothing — whereas a partial guess
     * would report {@code NO} for types it simply never looked at.
     */
    default ApiVocabulary vocabulary() {
        return ApiVocabulary.empty();
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
