package com.gijsm.vibemod.fabric.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "Bind the components of the content you just registered" (V4 Phase 2, the
 * second of the two configuration tasks).
 *
 * <h2>Why there are two tasks and not one</h2>
 *
 * <p>Not style — a real gap, and one this codebase already had written down.
 * {@code RegistrySeam.bindComponents()} returns early when there is no
 * {@code MinecraftServer}, because it needs a {@code HolderLookup.Provider} to
 * run {@code DataComponentInitializers.build} against. A client connected to a
 * remote server is exactly that case: {@code VibeModFabric.start} only runs on
 * {@code SERVER_STARTING}, so on a Lane A client there is no server, nothing
 * binds, and the first time an item registered from a manifest is put into a
 * stack {@code Holder.Reference.components()} throws "Components not bound
 * yet". Registration alone does not produce a usable item.
 *
 * <h2>Where in the queue this actually lands, which is not where the plan
 * assumed</h2>
 *
 * <p>The intended shape was: register → Fabric's {@code registry_sync} →
 * bind. The first two are exactly that. The third is not, and the bytecode
 * says why. Fabric's {@code ServerConfigurationPacketListenerImplMixin}
 * injects at the <em>head</em> of {@code startConfiguration}: it fires
 * {@code BEFORE_CONFIGURE}, then drains every task queued by it as "early
 * tasks" and <b>cancels</b> the vanilla body; when those finish it re-enters,
 * fires {@code CONFIGURE}, and only then runs the vanilla body — which is
 * where {@code SynchronizeRegistriesTask} is added
 * ({@code ServerConfigurationPacketListenerImpl.startConfiguration}, verified).
 * So a task queued from {@code CONFIGURE} sits <em>before</em> vanilla's
 * registry sync, and there is no later hook: vanilla adds
 * {@code SynchronizeRegistriesTask}, the optional tasks, {@code PrepareSpawnTask}
 * and {@code JoinWorldTask} in one synchronous run with nothing in between.
 *
 * <p>Which matters because {@code DataComponentInitializers.build} walks
 * {@code provider.listRegistryKeys()} and resolves every
 * {@code delayedHolderComponent} — vanilla's own jukebox songs, trim materials
 * and instruments among them, all of which live in <b>dynamic</b> registries a
 * client does not have until {@code ClientboundRegistryDataPacket} arrives. A
 * bind attempted at this point would fail on vanilla's items, not on ours.
 *
 * <p>So this payload is an <b>arming signal</b>: the client acknowledges it,
 * and does the bind at {@code ClientPlayConnectionEvents.JOIN}, where
 * {@code ClientPacketListener.registryAccess()} is the complete, synced
 * provider. That is still strictly before an item can enter a stack — the
 * player's inventory arrives after the login packet — and it is the earliest
 * moment at which the bind can succeed rather than the earliest moment at which
 * it can be attempted. The task still blocks the configuration queue until the
 * client answers, so a client that never answers never reaches
 * {@code JoinWorldTask}.
 *
 * <p>The client tries an immediate bind first anyway, because there is one case
 * where it works: a reconfiguration bounce, where the connection already has
 * its registries. {@link BindAck#deferred()} says which of the two happened.
 */
public record ContentBind(int protocol) implements CustomPacketPayload {

    /** The channel. Clientbound, configuration phase. */
    public static final Type<ContentBind> TYPE =
            CustomPacketPayload.createType("vibemod:content_bind");

    public static final StreamCodec<FriendlyByteBuf, ContentBind> STREAM_CODEC =
            StreamCodec.of((buf, bind) -> buf.writeVarInt(bind.protocol()),
                    buf -> new ContentBind(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
