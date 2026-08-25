package com.gijsm.vibemod.neoforge;

import java.util.function.Consumer;
import java.util.function.Supplier;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import net.minecraft.server.level.ServerPlayer;

import com.gijsm.vibemod.loader.LoaderEventBridge;
import com.gijsm.vibemod.runtime.ModDispatch;

/**
 * The NeoForge half of {@link LoaderEventBridge}: the ten curated hooks (§4.1)
 * wired to {@code NeoForge.EVENT_BUS}.
 *
 * <p><b>Registered once per process, never per world</b> — the §10.3 trap,
 * which NeoForge does not exempt anyone from even though its bus, unlike
 * Fabric's, can unregister. The VibeMod host lives with a server, and a client
 * starts and stops a whole host every time the player loads a world; subscribing
 * from that bootstrap would leave one listener per world ever loaded, all but
 * the last dispatching into a dead bridge. So these subscriptions live for the
 * process and resolve the live bridge — which IS per server — each time they
 * fire, exactly as on Fabric. That the two loaders' hosts are then structurally
 * identical is the point: one shape to reason about, not two.
 *
 * <p>The event names below are the §4.1 table's NeoForge column, each verified
 * with {@code javap} against neoforge-26.2.0.67 rather than recalled. Two of
 * them have moved since the doc was written: block breaking is
 * {@code net.neoforged.neoforge.event.level.block.BreakBlockEvent}, not
 * {@code BlockEvent.BreakEvent}, and ticks live under
 * {@code net.neoforged.neoforge.event.tick}.
 */
public final class NeoForgeEventBridge extends LoaderEventBridge {

    public NeoForgeEventBridge(ModDispatch dispatch) {
        super(dispatch);
    }

    /**
     * Subscribes the host's one permanent listener to each NeoForge event.
     *
     * @param bus  {@code NeoForge.EVENT_BUS}
     * @param live the current bridge, or null between worlds
     */
    public static void installDispatchers(IEventBus bus, Supplier<NeoForgeEventBridge> live) {
        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, event ->
                withPlayer(live, event.getEntity(), (b, player) -> b.dispatchPlayerJoin(player)));

        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event ->
                withPlayer(live, event.getEntity(), (b, player) -> b.dispatchPlayerQuit(player)));

        bus.addListener(ServerTickEvent.Post.class, event ->
                with(live, b -> b.dispatchServerTick(event.getServer())));

        bus.addListener(PlayerEvent.PlayerRespawnEvent.class, event ->
                withPlayer(live, event.getEntity(), (b, player) -> b.dispatchRespawn(player)));

        // NeoForge has no player-specific death event; LivingDeathEvent covers
        // both, so onPlayerDeath is the ServerPlayer subset of onEntityDeath.
        // Neither cancels (§4.1: they are Consumers), so the event is never
        // cancelled here whatever a handler does.
        bus.addListener(LivingDeathEvent.class, event -> {
            NeoForgeEventBridge bridge = live.get();
            if (bridge == null) {
                return;
            }
            bridge.dispatchEntityDeath(event.getEntity(), event.getSource());
            if (event.getEntity() instanceof ServerPlayer player) {
                bridge.dispatchPlayerDeath(player);
            }
        });

        bus.addListener(ServerChatEvent.class, event -> {
            NeoForgeEventBridge bridge = live.get();
            if (bridge != null && !bridge.dispatchChat(event.getPlayer(), event.getRawText())) {
                event.setCanceled(true);
            }
        });

        bus.addListener(BreakBlockEvent.class, event -> {
            NeoForgeEventBridge bridge = live.get();
            if (bridge == null || !(event.getPlayer() instanceof ServerPlayer player)) {
                return;
            }
            if (!bridge.dispatchBlockBreak(player, event.getPos(), event.getState())) {
                event.setCanceled(true);
            }
        });

        bus.addListener(PlayerInteractEvent.RightClickBlock.class, event -> {
            NeoForgeEventBridge bridge = live.get();
            if (bridge == null || !(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            if (!bridge.dispatchUseBlock(player, event.getHand(), event.getPos())) {
                event.setCanceled(true);
            }
        });

        bus.addListener(PlayerInteractEvent.RightClickItem.class, event -> {
            NeoForgeEventBridge bridge = live.get();
            if (bridge == null || !(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            if (!bridge.dispatchUseItem(player, event.getHand())) {
                event.setCanceled(true);
            }
        });
    }

    /** Runs {@code body} against the live bridge, or does nothing when there is none. */
    private static void with(Supplier<NeoForgeEventBridge> live, Consumer<NeoForgeEventBridge> body) {
        NeoForgeEventBridge bridge = live.get();
        if (bridge != null) {
            body.accept(bridge);
        }
    }

    /**
     * The player events are typed on {@code Player}, not {@code ServerPlayer} —
     * they fire on the client too, where there is no host to dispatch into.
     */
    private static void withPlayer(Supplier<NeoForgeEventBridge> live,
                                   net.minecraft.world.entity.player.Player player,
                                   java.util.function.BiConsumer<NeoForgeEventBridge, ServerPlayer> body) {
        NeoForgeEventBridge bridge = live.get();
        if (bridge != null && player instanceof ServerPlayer serverPlayer) {
            body.accept(bridge, serverPlayer);
        }
    }
}
