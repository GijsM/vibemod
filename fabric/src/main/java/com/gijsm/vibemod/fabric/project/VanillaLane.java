package com.gijsm.vibemod.fabric.project;

import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

import io.netty.channel.Channel;

import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import com.gijsm.vibemod.fabric.mixin.ConnectionAccessor;
import com.gijsm.vibemod.fabric.mixin.ServerCommonPacketListenerAccessor;
import com.gijsm.vibemod.fabric.net.ContentManifest;
import com.gijsm.vibemod.fabric.net.ContentSync;
import com.gijsm.vibemod.fabric.shim.RegistrySeam;

/**
 * Lane B, wired up: a vanilla client joins a server full of generated content
 * and is neither kicked nor lied to badly (V4 Phase 4).
 *
 * <p>{@code ContentSync} decides the lane at {@code BEFORE_CONFIGURE} and, until
 * this phase, left Lane B exactly where V3 did — refused. This class is the
 * other half of that fork. It does four things, once per process:
 *
 * <ol>
 *   <li>Runs {@link RegistryHiding#selfCheck()}, which proves the
 *       {@code configureClient} redirect is really in place. Everything else
 *       here is contingent on that, because without it fabric-api disconnects a
 *       vanilla client during configuration before a single game packet is
 *       sent.</li>
 *   <li>Hands {@link Projection} the server's {@code RegistryAccess}, which the
 *       component round trip needs and which is per-server rather than
 *       per-process — hence a supplier.</li>
 *   <li>Registers a {@code BEFORE_CONFIGURE} listener <b>in
 *       {@code ContentSync.PHASE}</b>, so it runs before fabric-api's registry
 *       sync and can refuse a connection with our own sentence rather than
 *       letting fabric's kick land first.</li>
 *   <li>Installs a {@link ProjectionHandler} on each Lane B channel, and none
 *       on a Lane A one.</li>
 * </ol>
 *
 * <h2>The fallback, and why it is loud</h2>
 *
 * <p>If the self-check fails — a fabric-api bump moved the method the redirect
 * names — Lane B does not degrade quietly into "projection off". It degrades
 * into <b>the V3 refusal, by name</b>: a vanilla client joining a server that
 * holds VibeMod content is disconnected here, during configuration, with a
 * message that says VibeMod, says what happened, and says what to do. The
 * alternative is fabric-api disconnecting the same player two milliseconds later
 * with a message that names neither VibeMod nor the item, which is exactly the
 * bug report this whole phase exists to stop.
 *
 * <h2>What Lane B does not get, said out loud</h2>
 *
 * <p>Items, item text, item particles and command suggestions are projected.
 * Entities are <b>withheld</b> ({@link EntityRefusal}). Blocks are not projected
 * at all ({@link BlockProjectionSeam}). Recipes and advancements are withheld
 * while content exists, because their stacks sit inside record trees this
 * version does not rewrite and an unprojected id is a disconnect rather than a
 * wrong texture. {@link ProjectedPackets} is the full list and
 * {@code :fabric:packetGate} fails the build if it goes out of date.
 */
public final class VanillaLane {

    private static final Logger LOG = Logger.getLogger("VibeMod.Lane");

    /**
     * How long {@link #contentExists()} may believe a "no" for.
     *
     * <p>The question is asked from the packet path, and the honest answer costs
     * a copy of the seam's order list — which is fine once and not fine per
     * packet. Registries never shrink (there is no {@code MappedRegistry.remove}),
     * so a "yes" latches forever and only a "no" is ever re-asked. A quarter of a
     * second of staleness on the very first item a server ever registers is the
     * whole cost, and what it buys is that a server with no content pays nothing.
     */
    private static final long RECHECK_NANOS = 250_000_000L;

    private static volatile VanillaLane installed;

    private final RegistrySeam seam;
    private final Supplier<MinecraftServer> server;

    private static volatile boolean contentLatched;
    /**
     * When the last "no" was decided, and whether one ever was.
     *
     * <p>The pair, rather than a deadline: {@code System.nanoTime()}'s origin is
     * arbitrary and is negative on some JVMs, so a deadline initialised to zero
     * would compare wrong for the whole first stretch of a process's life. An
     * elapsed-time comparison against a flag has no origin to be wrong about.
     */
    private static volatile boolean contentChecked;
    private static volatile long lastContentCheck;

    private VanillaLane(RegistrySeam seam, Supplier<MinecraftServer> server) {
        this.seam = seam;
        this.server = server;
    }

    /** The installed instance, for the gates, or null before {@link #install}. */
    public static VanillaLane installed() {
        return installed;
    }

    /**
     * Arms Lane B for the life of the process.
     *
     * <p>Must be called <b>after</b> {@code ContentSync.install}, because the
     * listener below registers into {@code ContentSync.PHASE} and that phase's
     * ordering against {@code Event.DEFAULT_PHASE} is established there. Calling
     * it first would register into a phase with no declared order and lose the
     * one guarantee this listener needs.
     */
    public static VanillaLane install(RegistrySeam seam, Supplier<MinecraftServer> server) {
        if (installed != null) {
            throw new IllegalStateException("VanillaLane is process-lived and was already installed");
        }
        VanillaLane lane = new VanillaLane(seam, server);

        RegistryHiding.selfCheck();

        Projection.useRegistries(() -> {
            MinecraftServer live = server.get();
            return live == null ? null : live.registryAccess();
        });

        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register(ContentSync.PHASE,
                lane::beforeConfigure);

        installed = lane;
        LOG.info("Lane B armed: vanilla-client projection " + (RegistryHiding.available()
                ? "on" : "OFF (registry hiding failed its self-check)")
                + ", packet coverage " + ProjectedPackets.describeCoverage());
        return lane;
    }

    // ------------------------------------------------------------------ per connection

    /**
     * Everything Lane B does at the door.
     *
     * <p>The lane test is {@code ServerConfigurationNetworking.canSend}, the same
     * one {@code ContentSync} uses one listener earlier and the same one
     * fabric-api's registry sync uses one phase later. Asking the same question
     * three times is deliberate: it is a cheap map lookup, and the alternative is
     * three classes sharing a mutable per-connection verdict that has to be
     * cleaned up on every disconnect path.
     */
    private void beforeConfigure(ServerConfigurationPacketListenerImpl handler,
                                 MinecraftServer running) {
        if (ServerConfigurationNetworking.canSend(handler, ContentManifest.TYPE)) {
            // Lane A. No handler, no branch, no cost.
            return;
        }
        String who = handler.getOwner().name();

        if (!RegistryHiding.available()) {
            if (!contentExists()) {
                // Nothing to hide and nothing to project. An ordinary vanilla
                // join on an ordinary vanilla server.
                return;
            }
            refuse(handler, who, "VibeMod could not hide its runtime content from your client's "
                    + "registry sync: " + RegistryHiding.unavailableReason() + ". Rather than let "
                    + "fabric-api disconnect you a moment from now with a message that names "
                    + "neither VibeMod nor the content, the join is refused here. Install VibeMod "
                    + "on your client to see this server's content properly, or ask the operator "
                    + "to check their fabric-api version against VibeMod's");
            return;
        }

        Connection connection = ((ServerCommonPacketListenerAccessor) handler).getConnection();
        Channel channel = ((ConnectionAccessor) connection).getChannel();
        if (ProjectionHandler.install(channel, who)) {
            noteRefusedContent(who);
            return;
        }
        if (!contentExists()) {
            // No projection and nothing to project. Let them in.
            return;
        }
        refuse(handler, who, "VibeMod could not install its vanilla-client projection on your "
                + "connection, and this server holds content your client has no ids for. Sending "
                + "it unprojected would disconnect you mid-game on an unreadable packet, so the "
                + "join is refused here instead. Install VibeMod on your client, or ask the "
                + "operator to check for a proxy or another mod that rebuilds the Netty pipeline");
    }

    private static void refuse(ServerConfigurationPacketListenerImpl handler, String who, String why) {
        LOG.warning("Refusing " + who + " at configuration: " + why);
        handler.disconnect(Component.literal(why));
    }

    /**
     * Says once, per Lane B join, what this player will not be shown.
     *
     * <p>A projection that bounds its coverage has to say so, and the operator's
     * log is where that belongs — the player has no way to tell a missing mob
     * from a mob that has not spawned.
     */
    private void noteRefusedContent(String who) {
        List<Identifier> entities = EntityRefusal.vibeModEntityTypes();
        if (!entities.isEmpty()) {
            LOG.info("Lane B: " + who + " will not see " + entities.size()
                    + " VibeMod entity type(s) — " + entities
                    + ". They are withheld rather than projected; see EntityRefusal for why");
        }
        int blocks = 0;
        for (RegistrySeam.Live live : seam.liveOrder()) {
            if ("minecraft:block".equals(live.registry())) {
                blocks++;
            }
        }
        if (blocks > 0) {
            LOG.info("Lane B: " + who + " will see " + blocks + " VibeMod block(s) as whatever the "
                    + "world already had there. Block projection is a reserved seam and nothing is "
                    + "built; see BlockProjectionSeam");
        }
    }

    // ------------------------------------------------------------------ the cheap question

    /**
     * Whether this server has any runtime content at all.
     *
     * <p>Read from the packet path, so see {@link #RECHECK_NANOS} for why the
     * "no" is cached and the "yes" is latched.
     */
    public static boolean contentExists() {
        if (contentLatched) {
            return true;
        }
        VanillaLane lane = installed;
        if (lane == null) {
            return false;
        }
        long now = System.nanoTime();
        if (contentChecked && now - lastContentCheck < RECHECK_NANOS) {
            return false;
        }
        lastContentCheck = now;
        contentChecked = true;
        if (!lane.seam.liveOrder().isEmpty()) {
            contentLatched = true;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ state, for the gates

    /**
     * One line, joining the four counters this phase keeps.
     *
     * <p>Its own string rather than an addition to {@code ContentSync}'s or
     * {@code RegistrySeam}'s, for the reason {@code ContentSync.describeState}
     * already gives: the gates prefix-match those, and a new field in the middle
     * of a string somebody is prefix-matching is a broken gate with no error
     * message.
     */
    public String describeState() {
        return "laneB=" + (RegistryHiding.available() ? "projecting" : "refusing")
                + " " + RegistryHiding.describeState()
                + " " + ProjectionHandler.describeState()
                + " " + PacketProjection.describeState()
                + " " + Projection.describeState()
                + " " + ProjectedPackets.describeCoverage();
    }
}
