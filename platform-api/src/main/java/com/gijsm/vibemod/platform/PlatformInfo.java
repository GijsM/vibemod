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
}
