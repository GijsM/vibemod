package com.gijsm.vibemod.fabric.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;

import com.gijsm.vibemod.fabric.net.BindAck;
import com.gijsm.vibemod.fabric.net.ContentAck;
import com.gijsm.vibemod.fabric.net.ContentBind;
import com.gijsm.vibemod.fabric.net.ContentManifest;
import com.gijsm.vibemod.fabric.shim.RegistrySeam;
import com.gijsm.vibemod.store.RegistryLedger;

/**
 * The client half of Lane A: registering a server's content, in its order,
 * before Fabric's registry sync runs (V4 Phase 2).
 *
 * <p>Client-only by necessity rather than by taste —
 * {@code ClientConfigurationNetworking} and {@code Minecraft} do not exist on a
 * dedicated server — which is why this lives beside the client entrypoint and
 * is installed from it, exactly as the HUD bridge and the runtime resource pack
 * already are.
 *
 * <h2>What this refuses, and why refusing is the feature</h2>
 *
 * <p>A client cannot un-register anything. {@code MappedRegistry} has no
 * {@code remove} and neither does the {@code IdMapper} behind
 * {@code Block.BLOCK_STATE_REGISTRY}, so every id this class takes is taken for
 * the life of the process. That makes every check here a check that has to
 * happen <em>before</em> the first registration, and makes a clean refusal at
 * the door strictly better than any recovery:
 *
 * <ul>
 *   <li><b>Protocol.</b> Two versions of the manifest format reading the same
 *       bytes differently is the one failure that looks like success.</li>
 *   <li><b>Blockstate baseline.</b> Checked only when the manifest carries
 *       blocks, and then checked hard. Chunk section palettes index
 *       {@code BLOCK_STATE_REGISTRY} globally and Fabric's remap does not touch
 *       it, so if this client's baseline is not the server's — because it
 *       already visited another VibeMod server this session — the two ends
 *       disagree about what a chunk means and no later negotiation can fix
 *       it.</li>
 *   <li><b>Foreign installations.</b> Handled inside
 *       {@link RegistrySeam#registerFromManifest}, which remembers which
 *       installation each id came from. Namespaces are per mod, not per server;
 *       two servers whose mods share a name mint the same id.</li>
 * </ul>
 *
 * <p>In all three cases the answer is a {@link ContentAck} carrying the reason,
 * and the server turns it into a disconnect message a player can act on.
 *
 * <h2>Where the work happens</h2>
 *
 * <p>On the client thread, via {@code Minecraft.execute}, never on the netty
 * thread the payload arrives on. Registration unfreezes three registries and
 * appends to a fourth; doing that underneath a render thread that is iterating
 * them is a race with no diagnostic. The configuration task on the server is
 * blocked until the ack, so the hop costs nothing but a tick.
 */
public final class ClientContentSync {

    private static final Logger LOG = Logger.getLogger("VibeMod.Lane");

    private static volatile ClientContentSync installed;

    private final RegistrySeam seam;
    /**
     * Set when a {@link ContentBind} could not be honoured during configuration
     * and is waiting for play join; see {@link ContentBind}'s class comment for
     * the queue arithmetic that makes this the normal path rather than a
     * fallback.
     */
    private final AtomicBoolean bindPending = new AtomicBoolean();

    private ClientContentSync(RegistrySeam seam) {
        this.seam = seam;
    }

    /** The installed instance, for the client gate, or null before {@link #install}. */
    public static ClientContentSync installed() {
        return installed;
    }

    /**
     * Registers the two client receivers and the play-join bind, once, for the
     * life of the process.
     *
     * <p>Global receivers and {@code Event.register} calls cannot be undone, and
     * a client can load and unload any number of worlds and connections in one
     * run — the same rule the client entrypoint already states for the HUD
     * bridge.
     */
    public static ClientContentSync install(RegistrySeam seam) {
        if (installed != null) {
            throw new IllegalStateException("ClientContentSync is process-lived and was already "
                    + "installed");
        }
        ClientContentSync sync = new ClientContentSync(seam);
        ClientConfigurationNetworking.registerGlobalReceiver(ContentManifest.TYPE,
                (manifest, context) -> context.client().execute(
                        () -> sync.applyManifest(manifest, context.responseSender())));
        ClientConfigurationNetworking.registerGlobalReceiver(ContentBind.TYPE,
                (bind, context) -> context.client().execute(
                        () -> sync.armBind(context.responseSender())));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sync.bindOnJoin());
        installed = sync;
        LOG.info("Lane A client armed: this client will register a VibeMod server's content "
                + "during configuration, before fabric's registry sync");
        return sync;
    }

    // ------------------------------------------------------------------ the manifest

    private void applyManifest(ContentManifest manifest, PacketSender sender) {
        if (manifest.protocol() != ContentManifest.PROTOCOL) {
            sender.sendPacket(ContentAck.refused("your client speaks VibeMod content protocol "
                    + ContentManifest.PROTOCOL + " and the server speaks " + manifest.protocol()));
            return;
        }

        String baselineProblem = blockStateProblem(manifest);
        if (baselineProblem != null) {
            LOG.warning("Refusing this server's VibeMod content: " + baselineProblem);
            sender.sendPacket(ContentAck.refused(baselineProblem));
            return;
        }

        RegistrySeam.ManifestOutcome outcome =
                seam.registerFromManifest(manifest.installationId(), manifest.entries());
        if (!outcome.clean()) {
            sender.sendPacket(ContentAck.refused(String.join("; ", outcome.problems())));
            return;
        }

        // Checked after, not only before. The baseline said the two ends started
        // level; this says they appended the same number of states, which is the
        // half a differing block schema would break.
        if (manifest.hasBlocks()
                && Block.BLOCK_STATE_REGISTRY.size() != manifest.blockStateTotal()) {
            sender.sendPacket(ContentAck.refused("after registering this server's blocks your "
                    + "client holds " + Block.BLOCK_STATE_REGISTRY.size()
                    + " blockstates and the server holds " + manifest.blockStateTotal()
                    + ". Chunk palettes index blockstate ids globally, so the two ends would "
                    + "disagree about what every section contains"));
            return;
        }

        String hash = RegistryLedger.orderHash(outcome.orderKeys());
        List<String> sample = new ArrayList<>(outcome.orderKeys()
                .subList(0, Math.min(ContentAck.SAMPLE, outcome.orderKeys().size())));
        LOG.info("Registered " + outcome.orderKeys().size() + " id(s) from installation "
                + manifest.installationId() + " (orderHash=" + hash + ")");
        sender.sendPacket(ContentAck.ok(hash, outcome.orderKeys().size(), sample));
    }

    /**
     * Why this client's blockstate id space cannot be made to match the
     * server's, or null when it can.
     *
     * <p>Only asked when the manifest carries blocks. An items-and-entities
     * server is fully served by Fabric's raw-id remap and has no reason to care
     * what else this client has registered; a server with blocks has every
     * reason, because {@code Block.BLOCK_STATE_REGISTRY} is an {@code IdMapper}
     * that appends at {@code nextId++}, is indexed positionally by every chunk
     * section palette, and is never renumbered by anybody.
     */
    private String blockStateProblem(ContentManifest manifest) {
        if (!manifest.hasBlocks()) {
            return null;
        }
        int baseline = seam.blockStateBaseline();
        if (baseline == manifest.blockStateBaseline()) {
            return null;
        }
        if (seam.blockStatesAppended() > 0) {
            return "your client already holds " + seam.blockStatesAppended()
                    + " runtime blockstate(s) from another VibeMod server this session, and they "
                    + "cannot be given back — the blockstate registry is an IdMapper with no "
                    + "remove. Restart your client before joining this server";
        }
        return "your client's blockstate registry starts at " + baseline
                + " and this server's starts at " + manifest.blockStateBaseline()
                + ", so you are running a different set of content mods. Chunk palettes index "
                + "blockstate ids globally and nothing renumbers them, so the two would disagree "
                + "about what every chunk section contains";
    }

    // ------------------------------------------------------------------ the bind

    /**
     * Answers a {@link ContentBind}: bind now if there is anything to bind
     * against, otherwise arm for play join.
     *
     * <p>The immediate attempt is not the expected path and is not treated as
     * one. It succeeds on a reconfiguration bounce, where the connection is
     * already holding a complete registry provider; on a first join there is no
     * {@code ClientPacketListener} yet, and even the configuration listener's
     * own registries are incomplete until vanilla's
     * {@code SynchronizeRegistriesTask} runs — which, per {@link ContentBind},
     * is after this task rather than before it.
     */
    private void armBind(PacketSender sender) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.getConnection() != null
                    && seam.bindComponents(client.getConnection().registryAccess())) {
                bindPending.set(false);
                sender.sendPacket(BindAck.bound());
                return;
            }
            bindPending.set(true);
            sender.sendPacket(BindAck.armed());
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Could not arm the data-component bind", t);
            sender.sendPacket(BindAck.refused("arming the component bind threw: " + t));
        }
    }

    /**
     * The bind itself, at play join, where
     * {@code ClientPacketListener.registryAccess()} is the complete synced
     * provider.
     *
     * <p>Still strictly before an item can enter a stack: the player's
     * inventory arrives after the login packet this event fires on. Unarmed
     * this is one boolean read per world load.
     */
    private void bindOnJoin() {
        if (!bindPending.compareAndSet(true, false)) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            LOG.warning("Play join with no connection to bind data components against; the first "
                    + "stack of a VibeMod item will throw \"Components not bound yet\"");
            return;
        }
        if (seam.bindComponents(client.getConnection().registryAccess())) {
            LOG.info("Bound data components for this server's VibeMod content at play join");
        }
    }
}
