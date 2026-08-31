package com.gijsm.vibemod.fabric.pack;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.config.ServerResourcePackConfigurationTask;

/**
 * Offers the served pack to a joining client, during configuration and nowhere
 * else (V4 Phase 3).
 *
 * <p><b>Configuration, not play.</b> A {@code ClientboundResourcePackPushPacket}
 * that lands while somebody is playing triggers a full client resource-stack
 * reload — not a delta, the whole stack, 2 to 30 seconds of frozen game
 * (MC-12257). During configuration the client is on a loading screen and expects
 * to wait, which is why every server that pushes a pack pushes it here. So a
 * rebuild never reaches a player mid-session; it reaches them at their next
 * join, and {@code FabricPackServer.rebuild} says so in the log rather than
 * leaving somebody to wonder why their texture has not appeared.
 *
 * <p><b>Vanilla's own task, on purpose.</b> This adds a
 * {@link ServerResourcePackConfigurationTask} rather than an interesting one of
 * its own, and that is forced rather than lazy.
 * {@code ServerConfigurationPacketListenerImpl.handleResourcePackResponse}
 * finishes the current task with a hardcoded
 * {@code ServerResourcePackConfigurationTask.TYPE} on any terminal ack
 * (disassembled), and {@code finishCurrentTask} throws
 * {@code IllegalStateException} when the running task's type is not the one it
 * was handed. A VibeMod-typed task that pushed a pack would therefore blow up
 * the connection the moment the client answered. Using vanilla's type also gets
 * the blocking for free: the configuration queue does not advance until the ack
 * arrives, which is exactly "block the task until the client acks", implemented
 * by the code that owns the invariant.
 *
 * <p><b>Optional by default.</b> {@code required=true} disconnects a player who
 * declines, which is a hostile default for a server whose content is
 * vibe-coded — a declining client sees untextured models, which is ugly rather
 * than broken. {@code packserver.required} exists for operators who mean it.
 *
 * <p>The subscription is process-lived and made at most once, because a Fabric
 * {@code Event} cannot be unregistered and a client can load and unload several
 * worlds in one JVM. It resolves {@link FabricPackServer#current()} when it
 * fires and does nothing when that is null — the same shape every other
 * process-lived subscription in this mod has.
 */
public final class FabricPackPush {

    private static final Logger LOG = Logger.getLogger("VibeMod.PackServer");

    /** What a player is told when they are asked to accept the pack. */
    private static final String DEFAULT_PROMPT =
            "This server generates its own blocks and items. The pack carries their textures, models "
            + "and names. It is optional — declining just means some things look untextured.";

    private static volatile boolean installed;

    private FabricPackPush() {
    }

    /**
     * Subscribes to {@code BEFORE_CONFIGURE}, once per process.
     *
     * <p>Left in {@code Event.DEFAULT_PHASE} deliberately. Phase 2's Lane-A
     * manifest task registers in a VibeMod phase ordered <em>before</em> the
     * default so that it beats fabric-api's registry sync; the pack push has no
     * such requirement and must not acquire one, because it is the slowest task
     * in the queue (a real download over somebody's real connection) and putting
     * it in front of registration would make every join wait for a texture
     * before it waits for the content the texture is of.
     */
    public static synchronized void installOnce() {
        if (installed) {
            return;
        }
        installed = true;
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register(FabricPackPush::offerTo);
        LOG.info("Pack pushes will be offered during the configuration phase");
    }

    private static void offerTo(ServerConfigurationPacketListenerImpl handler, MinecraftServer server) {
        FabricPackServer packs = FabricPackServer.current();
        if (packs == null) {
            return;
        }
        FabricPackServer.Offer offer;
        try {
            offer = packs.offer();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Could not build a resource pack offer", t);
            return;
        }
        if (offer == null) {
            return;
        }
        try {
            UUID previous = packs.previousId();
            if (previous != null && !previous.equals(offer.id())) {
                // Pop before push. Without it a client that reconfigures holds
                // both packs at once and the older one's models win wherever the
                // newer one has stopped defining them — which is the failure
                // mode that looks like "the texture did not update" and is
                // actually "the texture updated underneath an older copy".
                handler.send(new ClientboundResourcePackPopPacket(Optional.of(previous)));
            }
            handler.addTask(new ServerResourcePackConfigurationTask(
                    new MinecraftServer.ServerResourcePackInfo(offer.id(), offer.url(), offer.sha1(),
                            offer.required(), promptOf(offer))));
        } catch (Throwable t) {
            // A failed offer must never cost somebody their join. The pack is a
            // presentation layer; the content it decorates is already there.
            LOG.log(Level.WARNING, "Could not offer the resource pack to a joining client", t);
        }
    }

    private static Component promptOf(FabricPackServer.Offer offer) {
        String text = offer.prompt() == null || offer.prompt().isBlank()
                ? DEFAULT_PROMPT : offer.prompt();
        return Component.literal(text);
    }
}
