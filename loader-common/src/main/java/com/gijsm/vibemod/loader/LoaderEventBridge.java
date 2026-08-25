package com.gijsm.vibemod.loader;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.gijsm.vibemod.platform.EventBridge;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModDispatch;

/**
 * The curated server-event surface (ARCHITECTURE-V2 §4.1), as host-owned
 * dispatchers — the half that is identical on Fabric and NeoForge.
 *
 * <p><b>Why a curated list at all.</b> A Fabric {@code Event} cannot be
 * unsubscribed — {@code register} is one-way, by design. A generated mod that
 * subscribed directly could therefore never be torn down, and VibeMod's entire
 * teardown model (§0#10) is that a disabled mod leaves nothing behind. So the
 * host subscribes exactly once per hook, forever, and dispatches to a mutable
 * per-mod registry it can empty. Once you are doing that, enumerating the hooks
 * is not a limitation you impose — it is the shape the design already has, and a
 * curated list is the honest way to say so.
 *
 * <p>NeoForge's bus <em>does</em> support removal, and this class keeps the
 * indirection anyway. Two reasons, and neither is inertia. The host is per-world
 * but a subscription is per-process (§10.3): on a client that loads world A,
 * quits to the menu and loads world B, a per-world subscription leaves one dead
 * listener per world ever loaded. And the mods themselves come and go dozens of
 * times per session while the world stands — subscribing and unsubscribing the
 * bus on every {@code /vibe enable} would make hot-loading a bus mutation rather
 * than a map mutation, for no gain.
 *
 * <p>Every dispatch goes through {@link ModDispatch}: watchdog-timed, guarded,
 * and journalled against the mod. A handler that throws costs its mod a degrade
 * episode; it never reaches the server.
 *
 * <p>A subclass supplies exactly one thing: the loader-specific subscriptions
 * that call the {@code dispatch*} methods below. Everything else — the ten
 * registration methods {@link LoaderModHost}'s {@code VibeContext} calls, the
 * revocable per-mod registries, and the dispatch policy — lives here.
 */
public abstract class LoaderEventBridge implements EventBridge {

    private final ModDispatch dispatch;

    /** One list per hook, per §4.1's table. Copy-on-write: read every tick, written rarely. */
    private final List<Bound<Consumer<ServerPlayer>>> joins = new CopyOnWriteArrayList<>();
    private final List<Bound<Consumer<ServerPlayer>>> quits = new CopyOnWriteArrayList<>();
    private final List<Bound<Consumer<MinecraftServer>>> ticks = new CopyOnWriteArrayList<>();
    private final List<Bound<ChatHook>> chats = new CopyOnWriteArrayList<>();
    private final List<Bound<BlockHook>> breaks = new CopyOnWriteArrayList<>();
    private final List<Bound<UseHook>> useBlocks = new CopyOnWriteArrayList<>();
    private final List<Bound<UseHook>> useItems = new CopyOnWriteArrayList<>();
    private final List<Bound<BiConsumer<LivingEntity, DamageSource>>> deaths = new CopyOnWriteArrayList<>();
    private final List<Bound<Consumer<ServerPlayer>>> playerDeaths = new CopyOnWriteArrayList<>();
    private final List<Bound<Consumer<ServerPlayer>>> respawns = new CopyOnWriteArrayList<>();

    protected LoaderEventBridge(ModDispatch dispatch) {
        this.dispatch = dispatch;
    }

    // ---- what a loader's process-wide subscriptions call ----

    public void dispatchPlayerJoin(ServerPlayer player) {
        fire(joins, "onPlayerJoin", hook -> hook.accept(player));
    }

    public void dispatchPlayerQuit(ServerPlayer player) {
        fire(quits, "onPlayerQuit", hook -> hook.accept(player));
    }

    public void dispatchServerTick(MinecraftServer server) {
        fire(ticks, "onServerTick", hook -> hook.accept(server));
    }

    public void dispatchRespawn(ServerPlayer player) {
        fire(respawns, "onRespawn", hook -> hook.accept(player));
    }

    public void dispatchPlayerDeath(ServerPlayer player) {
        fire(playerDeaths, "onPlayerDeath", hook -> hook.accept(player));
    }

    public void dispatchEntityDeath(LivingEntity entity, DamageSource source) {
        fire(deaths, "onEntityDeath", hook -> hook.accept(entity, source));
    }

    /** @return false when a mod voted to cancel the message */
    public boolean dispatchChat(ServerPlayer player, String text) {
        return every(chats, "onChat", hook -> hook.handle(player, text));
    }

    /** @return false when a mod voted to cancel the break */
    public boolean dispatchBlockBreak(ServerPlayer player, BlockPos pos, BlockState state) {
        return every(breaks, "onBlockBreak", hook -> hook.handle(player, pos, state));
    }

    /** @return false when a mod voted to cancel the interaction */
    public boolean dispatchUseBlock(ServerPlayer player, InteractionHand hand, BlockPos pos) {
        return every(useBlocks, "onUseBlock", hook -> hook.handle(player, hand, pos));
    }

    /** @return false when a mod voted to cancel the interaction */
    public boolean dispatchUseItem(ServerPlayer player, InteractionHand hand) {
        return every(useItems, "onUseItem", hook -> hook.handle(player, hand, null));
    }

    // ---- the ten registration methods LoaderModHost's VibeContext calls ----

    public Registration onPlayerJoin(String modName, Consumer<ServerPlayer> handler) {
        return add(joins, modName, handler);
    }

    public Registration onPlayerQuit(String modName, Consumer<ServerPlayer> handler) {
        return add(quits, modName, handler);
    }

    public Registration onServerTick(String modName, Consumer<MinecraftServer> handler) {
        return add(ticks, modName, handler);
    }

    public Registration onChat(String modName, ChatHook handler) {
        return add(chats, modName, handler);
    }

    public Registration onBlockBreak(String modName, BlockHook handler) {
        return add(breaks, modName, handler);
    }

    public Registration onUseBlock(String modName, UseHook handler) {
        return add(useBlocks, modName, handler);
    }

    public Registration onUseItem(String modName, UseHook handler) {
        return add(useItems, modName, handler);
    }

    public Registration onEntityDeath(String modName, BiConsumer<LivingEntity, DamageSource> handler) {
        return add(deaths, modName, handler);
    }

    public Registration onPlayerDeath(String modName, Consumer<ServerPlayer> handler) {
        return add(playerDeaths, modName, handler);
    }

    public Registration onRespawn(String modName, Consumer<ServerPlayer> handler) {
        return add(respawns, modName, handler);
    }

    /**
     * Not supported on either loader, on purpose.
     *
     * <p>There is no platform-native listener object to hand over: Fabric's
     * events are static fields taking lambdas, and NeoForge's bus takes either a
     * lambda or an annotation-scanned object whose subscriptions VibeMod could
     * not attribute to a mod. Generated mods use the curated {@code ctx.on*}
     * hooks, and the prompt says so. This throws rather than no-ops because the
     * SPI's javadoc requires a wrong-typed listener to be rejected loudly, and
     * silently dropping a mod's only event registration would be the worst
     * possible failure.
     */
    @Override
    public Registration listen(Object nativeListener, String modName) {
        throw new IllegalArgumentException(
                "This loader has no registrable listener objects; generated mods use the curated "
                        + "ctx.on* hooks (ARCHITECTURE-V2 §4.1). Got: "
                        + (nativeListener == null ? "null" : nativeListener.getClass().getName()));
    }

    // ---- dispatch plumbing ----

    private <H> Registration add(List<Bound<H>> list, String modName, H handler) {
        if (handler == null) {
            return Registration.inactive();
        }
        Bound<H> bound = new Bound<>(modName, handler);
        list.add(bound);
        return Registration.of(() -> list.remove(bound));
    }

    /** Fire-and-forget dispatch: every handler runs, guarded and timed. */
    private <H> void fire(List<Bound<H>> list, String where, Consumer<H> call) {
        if (list.isEmpty()) {
            return;
        }
        for (Bound<H> bound : list) {
            dispatch.run(bound.modName, null, where, () -> call.accept(bound.handler));
        }
    }

    /**
     * Cancellable dispatch: every handler runs, and the result is the AND of
     * their votes. A handler that throws is treated as no vote — a mod that just
     * broke should not also silently start cancelling other players' actions.
     *
     * <p>Every handler runs even after one has voted to cancel. Short-circuiting
     * would make a mod's behaviour depend on the order mods happen to have
     * loaded, which is exactly the kind of irreproducible bug the error journal
     * cannot explain.
     */
    private <H> boolean every(List<Bound<H>> list, String where, Predicate<H> call) {
        if (list.isEmpty()) {
            return true;
        }
        boolean allow = true;
        for (Bound<H> bound : list) {
            boolean[] vote = {true};
            dispatch.run(bound.modName, null, where, () -> vote[0] = call.test(bound.handler));
            allow &= vote[0];
        }
        return allow;
    }

    /** One mod's handler for one hook. */
    private record Bound<H>(String modName, H handler) {
    }

    /** {@code VibeContext.ChatHandler}, restated so this class need not import the sdk flavor. */
    @FunctionalInterface
    public interface ChatHook {
        boolean handle(ServerPlayer player, String message);
    }

    /** {@code VibeContext.BlockHandler}. */
    @FunctionalInterface
    public interface BlockHook {
        boolean handle(ServerPlayer player, BlockPos pos, BlockState state);
    }

    /** {@code VibeContext.UseHandler}; {@code pos} is null for item use. */
    @FunctionalInterface
    public interface UseHook {
        boolean handle(ServerPlayer player, InteractionHand hand, BlockPos pos);
    }
}
