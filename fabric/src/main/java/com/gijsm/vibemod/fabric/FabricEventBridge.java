package com.gijsm.vibemod.fabric;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.gijsm.vibemod.platform.EventBridge;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModDispatch;

/**
 * The curated server-event surface (ARCHITECTURE-V2 §4.1), as host-owned
 * dispatchers.
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
 * <p>Every dispatch goes through {@link ModDispatch}: watchdog-timed, guarded,
 * and journalled against the mod. A handler that throws costs its mod a degrade
 * episode; it never reaches the server.
 *
 * <p>{@link #listen(Object, String)} — the Bukkit-shaped SPI method — is
 * deliberately unsupported here, and its javadoc mandates saying so rather than
 * ignoring it.
 */
public final class FabricEventBridge implements EventBridge {

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

    public FabricEventBridge(ModDispatch dispatch) {
        this.dispatch = dispatch;
    }

    /**
     * Subscribes the host's one permanent listener to each Fabric event. Called
     * exactly once, at mod init — never per mod, and never undone.
     */
    public void installDispatchers() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                fire(joins, "onPlayerJoin", hook -> hook.accept(handler.player)));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                fire(quits, "onPlayerQuit", hook -> hook.accept(handler.player)));

        ServerTickEvents.END_SERVER_TICK.register(server ->
                fire(ticks, "onServerTick", hook -> hook.accept(server)));

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                fire(respawns, "onRespawn", hook -> hook.accept(newPlayer)));

        // ALLOW_DEATH is the only player-death hook Fabric offers. VibeMod's
        // onPlayerDeath does not cancel (§4.1: it is a Consumer), so this always
        // allows the death and just notifies.
        ServerPlayerEvents.ALLOW_DEATH.register((player, source, amount) -> {
            fire(playerDeaths, "onPlayerDeath", hook -> hook.accept(player));
            return true;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) ->
                fire(deaths, "onEntityDeath", hook -> hook.accept(entity, source)));

        // The cancelling hooks below run every handler even after one has voted
        // to cancel. Short-circuiting would make a mod's behaviour depend on the
        // order mods happen to have loaded, which is exactly the kind of
        // irreproducible bug the error journal cannot explain.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.decoratedContent().getString();
            return every(chats, "onChat", hook -> hook.handle(sender, text));
        });

        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return true;
            }
            return every(breaks, "onBlockBreak", hook -> hook.handle(serverPlayer, pos, state));
        });

        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            boolean allow = every(useBlocks, "onUseBlock",
                    hook -> hook.handle(serverPlayer, hand, hitResult.getBlockPos()));
            return allow ? InteractionResult.PASS : InteractionResult.FAIL;
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            boolean allow = every(useItems, "onUseItem", hook -> hook.handle(serverPlayer, hand, null));
            return allow ? InteractionResult.PASS : InteractionResult.FAIL;
        });
    }

    // ---- the ten registration methods FabricModHost's VibeContext calls ----

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
     * Not supported on Fabric, on purpose.
     *
     * <p>There is no platform-native listener object to hand over: Fabric's
     * events are static fields taking lambdas, and they cannot be unregistered.
     * Generated mods use the curated {@code ctx.on*} hooks, and the prompt says
     * so. This throws rather than no-ops because the SPI's javadoc requires a
     * wrong-typed listener to be rejected loudly, and silently dropping a mod's
     * only event registration would be the worst possible failure.
     */
    @Override
    public Registration listen(Object nativeListener, String modName) {
        throw new IllegalArgumentException(
                "Fabric has no registrable listener objects; generated mods use the curated ctx.on* hooks "
                        + "(ARCHITECTURE-V2 §4.1). Got: "
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
     */
    private <H> boolean every(List<Bound<H>> list, String where, java.util.function.Predicate<H> call) {
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
