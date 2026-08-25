package com.gijsm.vibemod.api.client;

/**
 * Everything a generated mod may touch on the client (Fabric/NeoForge hosts
 * with a physical client — singleplayer or LAN host). Obtained inside
 * {@code ctx.client(c -> ...)}; every registration is tracked and revoked
 * with the mod, same as the server-side context.
 *
 * <p>THREADING: all callbacks registered here ({@code hud}, {@code tick},
 * key presses, client commands) run on the RENDER thread. Use only this
 * context's getters and the mod's own fields inside them — never touch
 * server-side state (worlds, entities, the server context's objects): in
 * singleplayer both sides share one JVM and such races are silent.
 *
 * <p>This interface is deliberately pure JDK (no Minecraft types): it
 * compiles everywhere and shields generated code from client-API churn.
 * {@link #minecraftHandle()} is the escape hatch for advanced use.
 */
public interface ClientContext {

    /** Adds a HUD overlay drawn every frame while in-game. Exceptions auto-disable the mod. */
    void hud(String id, HudRenderer renderer);

    /**
     * Leases one of 8 pooled key slots. The Controls screen shows the slot
     * name ("VibeMod Slot n"); {@code label} is shown in VibeMod's own UI.
     * {@code defaultKey} names a key like {@code "R"}, {@code "F6"},
     * {@code "MOUSE4"} — applied only when the user never rebound that slot.
     *
     * @throws IllegalStateException when all 8 slots are in use
     */
    KeyLease key(String label, String defaultKey, Runnable onPress);

    /** Runs at the end of every client tick (~20/s), render thread. */
    void tick(ClientTickHandler handler);

    /** Client command under {@code /vibec <mod> <name> [args]}. */
    void clientCommand(String name, String description, ClientCommandHandler handler);

    /** Plays a UI-positioned sound for the local player, e.g. {@code "minecraft:ui.button.click"}. */
    void sound(String soundId, float volume, float pitch);

    /** Pops a toast notification. */
    void toast(String title, String body);

    // ---- render-thread-safe state getters (valid inside hud/tick callbacks) ----

    /** True when the local player is in a world. Most getters below return zero/"" when not. */
    boolean inGame();

    double playerX();

    double playerY();

    double playerZ();

    float playerHealth();

    float playerMaxHealth();

    /** Dimension id, e.g. {@code "minecraft:overworld"}; {@code ""} when not in game. */
    String dimension();

    /** Block id under the crosshair within reach, e.g. {@code "minecraft:stone"}; {@code ""} when none. */
    String targetedBlock();

    /** Current frames per second. */
    int fps();

    /** World day-time in ticks; {@code -1} when not in game. */
    long worldTime();

    /**
     * Escape hatch: the {@code net.minecraft.client.Minecraft} instance,
     * typed as Object so this interface stays pure JDK. Cast at your own
     * risk; the stable surface above is preferred.
     */
    Object minecraftHandle();
}
