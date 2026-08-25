package com.gijsm.vibemod.api;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.gijsm.vibemod.api.client.ClientContext;

/**
 * Everything a generated mod may touch. All registrations are tracked per mod
 * and undone exactly when the mod is disabled or unloaded.
 *
 * All methods must be called from the main server thread.
 *
 * <p>This is the MOD flavor of the v2 contract (ARCHITECTURE-V2 §4.1), shared
 * verbatim by the Fabric and NeoForge hosts — both run official Mojang names
 * on MC 26.1+, so one Mojang-typed surface serves both. The Paper flavor has
 * the same FQCN with Bukkit-typed members and the same semantics; generated
 * code compiles against whichever flavor the running host provides.
 *
 * <p><b>The event hooks below are the whole event surface.</b> They are not a
 * convenience layer over something larger — a mod may not register a listener
 * with the loader itself. Fabric's events cannot be unregistered, so a mod that
 * subscribed directly could never be torn down; the host therefore owns one
 * permanent subscription per hook and dispatches through a registry it can
 * empty. A curated list is the honest surface that falls out of that, and it is
 * v1-frozen.
 */
public interface VibeContext {

    /** The running server. In singleplayer this is the integrated server. */
    MinecraftServer server();

    /** This mod's name. */
    String modName();

    /** Logger prefixed with the mod name. */
    Logger log();

    /** Per-mod data directory, created on first call. */
    Path dataFolder();

    // ---- scheduling (main server thread) ----

    /** Schedule a repeating main-thread task with an initial delay. Tracked. */
    TaskHandle repeat(long delayTicks, long periodTicks, Runnable task);

    /** Schedule a repeating main-thread task starting after one period. Tracked. */
    default TaskHandle repeat(long periodTicks, Runnable task) {
        return repeat(periodTicks, periodTicks, task);
    }

    /** Schedule a one-shot delayed main-thread task. Tracked. */
    TaskHandle later(long delayTicks, Runnable task);

    // ---- commands ----

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
    // Config reads are thread-safe (callable from client callbacks).

    /** Current value of a boolean knob. */
    boolean configBool(String key);

    /** Current value of an integer knob. */
    long configInt(String key);

    /** Current value of a decimal knob. */
    double configDouble(String key);

    /** Current value of a text or choice knob. */
    String configString(String key);

    // ---- client (physical client only: singleplayer / LAN host) ----

    /** True when this process has a physical client. False on dedicated servers. */
    boolean hasClient();

    /**
     * Runs {@code setup} iff a physical client is present — registrations made
     * inside (HUD, keybinds, client tick, /vibec commands) are tracked and
     * revoked with the mod. No-op on dedicated servers, so mods declaring client
     * features degrade gracefully. Client callbacks run on the RENDER thread and
     * must only use {@link ClientContext} state — never server state (see the
     * ClientContext javadoc).
     */
    void client(Consumer<ClientContext> setup);

    // ---- server event hooks ----
    // Every handler runs on the main server thread and is revoked with the mod.
    // Anything a handler throws is caught, journalled against the mod, and
    // counted towards its error storm; it never reaches the server.

    /** A player finished joining. */
    void onPlayerJoin(Consumer<ServerPlayer> handler);

    /** A player disconnected. */
    void onPlayerQuit(Consumer<ServerPlayer> handler);

    /** End of every server tick (20/s). Keep it cheap — the watchdog is timing you. */
    void onServerTick(Consumer<MinecraftServer> handler);

    /** A player sent a chat message. */
    void onChat(ChatHandler handler);

    /** A player is about to break a block. */
    void onBlockBreak(BlockHandler handler);

    /** A player right-clicked a block. {@code pos} is the block they clicked. */
    void onUseBlock(UseHandler handler);

    /** A player right-clicked with an item in hand. {@code pos} is null. */
    void onUseItem(UseHandler handler);

    /** Any living entity died. */
    void onEntityDeath(BiConsumer<LivingEntity, DamageSource> handler);

    /** A player died. */
    void onPlayerDeath(Consumer<ServerPlayer> handler);

    /** A player respawned; the argument is the NEW player entity. */
    void onRespawn(Consumer<ServerPlayer> handler);

    /** Handles a chat message. Return {@code false} to cancel it (the message is not broadcast). */
    @FunctionalInterface
    interface ChatHandler {
        boolean handle(ServerPlayer player, String message);
    }

    /** Handles a block break. Return {@code false} to cancel it (the block survives). */
    @FunctionalInterface
    interface BlockHandler {
        boolean handle(ServerPlayer player, BlockPos pos, BlockState state);
    }

    /**
     * Handles a use (right-click) interaction. Return {@code false} to cancel it.
     *
     * @param pos the block that was clicked for {@code onUseBlock}; {@code null} for {@code onUseItem}
     */
    @FunctionalInterface
    interface UseHandler {
        boolean handle(ServerPlayer player, InteractionHand hand, BlockPos pos);
    }
}
