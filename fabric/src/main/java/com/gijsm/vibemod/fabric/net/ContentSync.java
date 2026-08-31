package com.gijsm.vibemod.fabric.net;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;

import com.gijsm.vibemod.fabric.shim.RegistrySeam;
import com.gijsm.vibemod.fabric.shim.StubBlock;
import com.gijsm.vibemod.store.RegistryLedger;

/**
 * Lane A: a dedicated server telling a VibeMod client what to register, before
 * fabric-api gets a chance to renumber anything (V4 Phase 2).
 *
 * <h2>The one mechanism the whole phase rests on</h2>
 *
 * <p>{@code fabric.mod.json} declares {@code depends: fabric-api}, so
 * fabric-api's entrypoint runs first and its {@code BEFORE_CONFIGURE} listener
 * — the one that queues {@code RegistrySyncManager}'s
 * {@code SyncConfigurationTask} — is registered before ours. Fabric's own
 * {@code Event} phases invert that without touching anybody else's code:
 * {@link #PHASE} is ordered before {@code Event.DEFAULT_PHASE}, so our
 * configuration task is queued first and runs first.
 *
 * <p>{@code Event.addPhaseOrdering} is on §10's "do not add" list, and that list
 * is about <b>generated mods</b>: phase order is global and cannot be undone
 * when a mod is disabled, which is disqualifying for code that is loaded and
 * unloaded at a player's whim. The host is not that. This ordering is
 * established once, at mod init, for the life of the process, and there is
 * never a moment at which VibeMod wants it gone.
 *
 * <h2>Where the tasks land in the queue</h2>
 *
 * <p>Read off {@code ServerConfigurationPacketListenerImplMixin}: Fabric injects
 * at the head of {@code startConfiguration}, fires {@code BEFORE_CONFIGURE},
 * drains everything queued by it as "early tasks" while <b>cancelling</b> the
 * vanilla body, and only re-enters — firing {@code CONFIGURE} and then running
 * the vanilla body — once they finish. So:
 *
 * <pre>
 * BEFORE_CONFIGURE  vibemod phase  →  vibemod:content/register   ← the manifest
 * BEFORE_CONFIGURE  default phase  →  fabric registry_sync       ← raw ids settled here
 * CONFIGURE         default phase  →  vibemod:content/bind
 * vanilla body                     →  SynchronizeRegistriesTask, …, JoinWorldTask
 * </pre>
 *
 * <p>The bind task therefore sits <em>before</em> vanilla's registry sync rather
 * than after it, which is not where the plan expected it; {@link ContentBind}'s
 * class comment has the consequence and what the client does about it.
 *
 * <h2>We never touch raw ids</h2>
 *
 * <p>Fabric already solved numeric id negotiation and does it well, including
 * the asymmetric case. This class's entire job is to get VibeMod's entries onto
 * the client <em>before</em> {@code SyncConfigurationTask} runs and then get out
 * of the way. Nothing here reads or writes a raw id. What it does check is the
 * one id space Fabric's remap cannot help with — {@code Block.BLOCK_STATE_REGISTRY},
 * an {@code IdMapper} that appends and is never renumbered — via the manifest's
 * blockstate baseline.
 */
public final class ContentSync implements RegistrySeam.DedicatedPolicy {

    private static final Logger LOG = Logger.getLogger("VibeMod.Lane");

    /**
     * The event phase our {@code BEFORE_CONFIGURE} listener registers under,
     * ordered before {@code Event.DEFAULT_PHASE}. See the class comment.
     */
    public static final Identifier PHASE = Identifier.parse("vibemod:content_first");

    /** {@code vibemod:content/register} — sends the manifest, waits for the ack. */
    public static final ConfigurationTask.Type REGISTER_TASK =
            new ConfigurationTask.Type("vibemod:content/register");

    /** {@code vibemod:content/bind} — tells the client to bind data components. */
    public static final ConfigurationTask.Type BIND_TASK =
            new ConfigurationTask.Type("vibemod:content/bind");

    /**
     * Which run of this installation the content belongs to.
     *
     * <p>Process start, because the id space this describes is per-JVM: it is
     * appended to from the registration window and never reclaimed, world or no
     * world. A client that reconnects within the same epoch and finds the same
     * ids already registered is looking at exactly the registries it was told
     * about last time.
     */
    private static final long EPOCH = System.currentTimeMillis();

    private static volatile ContentSync installed;

    private final RegistrySeam seam;
    /**
     * What was sent to each connection still in configuration.
     *
     * <p>The manifest is kept rather than rebuilt when the ack comes back, and
     * that is not an optimisation. A mod can register content between the send
     * and the answer — {@code /vibe make} on a busy server is exactly that — and
     * comparing the client's hash against a manifest it was never sent would
     * refuse a client that did everything right.
     */
    private final Map<ServerConfigurationPacketListenerImpl, ContentManifest> configuring =
            new ConcurrentHashMap<>();
    /** Players who configured as Lane A, for as long as they are connected. */
    private final Set<UUID> laneA = ConcurrentHashMap.newKeySet();
    /** Players who configured as Lane B, likewise. */
    private final Set<UUID> laneB = ConcurrentHashMap.newKeySet();

    private ContentSync(RegistrySeam seam) {
        this.seam = seam;
    }

    /** The installed instance, for the gates, or null before {@link #install}. */
    public static ContentSync installed() {
        return installed;
    }

    /**
     * Registers the payload types, the phase ordering, the two connection
     * listeners and the two ack receivers — all of them process-lived, all of
     * them exactly once.
     *
     * <p>Everything here is an {@code Event.register}, a
     * {@code PayloadTypeRegistry.register} or a global receiver, and none of
     * those can be undone. Calling this per server would leave one dead
     * subscription per world ever loaded, which is the rule
     * {@code VibeModFabric.onInitialize} already states for every other
     * subscription VibeMod makes.
     */
    public static ContentSync install(RegistrySeam seam) {
        if (installed != null) {
            throw new IllegalStateException("ContentSync is process-lived and was already installed");
        }
        ContentSync sync = new ContentSync(seam);

        // registerLarge, not register: a server running a few dozen generated
        // mods will exceed the 1 MiB clientbound payload cap, and Fabric only
        // splits a payload whose declared maximum says so.
        PayloadTypeRegistry.clientboundConfiguration().registerLarge(ContentManifest.TYPE,
                ContentManifest.STREAM_CODEC, ContentManifest.MAX_BYTES);
        PayloadTypeRegistry.clientboundConfiguration().register(ContentBind.TYPE,
                ContentBind.STREAM_CODEC);
        PayloadTypeRegistry.serverboundConfiguration().registerLarge(ContentAck.TYPE,
                ContentAck.STREAM_CODEC, ContentAck.MAX_BYTES);
        PayloadTypeRegistry.serverboundConfiguration().register(BindAck.TYPE,
                BindAck.STREAM_CODEC);

        // THE line. Without it fabric-api's listener queues its sync task first
        // and remaps a registry the client has not been told about yet.
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.addPhaseOrdering(PHASE,
                Event.DEFAULT_PHASE);
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register(PHASE, sync::beforeConfigure);
        ServerConfigurationConnectionEvents.CONFIGURE.register(sync::configure);
        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) ->
                sync.configuring.remove(handler));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                sync.forget(handler.player.getUUID()));

        ServerConfigurationNetworking.registerGlobalReceiver(ContentAck.TYPE, sync::onContentAck);
        ServerConfigurationNetworking.registerGlobalReceiver(BindAck.TYPE, sync::onBindAck);

        seam.setDedicatedPolicy(sync);
        seam.installRemapRepair();
        installed = sync;
        LOG.info("Lane A delivery armed: manifest before fabric's registry sync, phase "
                + PHASE + " ordered before " + Event.DEFAULT_PHASE);
        return sync;
    }

    // ------------------------------------------------------------------ lane detection

    /**
     * Decides this connection's lane and queues the manifest task.
     *
     * <p>{@code canSend} is the whole test, and it is the same one fabric-api's
     * own registry sync uses one phase later: a client that can receive our
     * manifest is running VibeMod, and a client that cannot is not. Fabric has
     * already exchanged {@code minecraft:register} for this connection by the
     * time {@code BEFORE_CONFIGURE} fires — its own early tasks run first —
     * which is what makes the answer meaningful this early.
     *
     * <p>Lane B is logged and left exactly as V3 left it: fabric-api will
     * disconnect it during configuration if any VibeMod content exists, with a
     * message naming neither VibeMod nor the item. Making that honest is the
     * projection phase's job, not this one's.
     */
    private void beforeConfigure(ServerConfigurationPacketListenerImpl handler,
                                 MinecraftServer server) {
        boolean laneAConnection = ServerConfigurationNetworking.canSend(handler,
                ContentManifest.TYPE);
        String who = handler.getOwner().name();
        if (!laneAConnection) {
            laneB.add(handler.getOwner().id());
            laneA.remove(handler.getOwner().id());
            LOG.info("Lane B: " + who + " cannot receive " + ContentManifest.TYPE.id()
                    + ", so no VibeMod content is sent. If this server holds runtime registry "
                    + "content, fabric-api's registry sync will disconnect this client during "
                    + "configuration — that kick is not ours and its message names neither "
                    + "VibeMod nor the content");
            return;
        }
        ContentManifest manifest = buildManifest();
        configuring.put(handler, manifest);
        LOG.info("Lane A: " + who + " will register " + manifest.entries().size()
                + " VibeMod id(s) before fabric's registry sync (orderHash="
                + manifest.orderHash() + ", blockStateBaseline=" + manifest.blockStateBaseline()
                + ")");
        ((FabricServerConfigurationPacketListenerImpl) handler)
                .addTask(new SendManifestTask(manifest));
    }

    /**
     * Queues the bind task, in the default phase, on the second pass.
     *
     * <p>{@code CONFIGURE} rather than {@code BEFORE_CONFIGURE} because a task
     * queued in the latter would sit between the manifest and Fabric's registry
     * sync, which is the one place it must not be. See the class comment for
     * where it does land and {@link ContentBind} for what the client does with
     * the gap.
     */
    private void configure(ServerConfigurationPacketListenerImpl handler, MinecraftServer server) {
        if (!configuring.containsKey(handler)) {
            return;
        }
        ((FabricServerConfigurationPacketListenerImpl) handler).addTask(new BindTask());
    }

    private void forget(UUID playerId) {
        laneA.remove(playerId);
        laneB.remove(playerId);
    }

    // ------------------------------------------------------------------ the two tasks

    /** {@code vibemod:content/register}: send the manifest, wait for the ack. */
    private static final class SendManifestTask implements ConfigurationTask {

        private final ContentManifest manifest;

        private SendManifestTask(ContentManifest manifest) {
            this.manifest = manifest;
        }

        @Override
        public void start(Consumer<Packet<?>> sender) {
            sender.accept(ServerConfigurationNetworking.createClientboundPacket(manifest));
        }

        @Override
        public Type type() {
            return REGISTER_TASK;
        }
    }

    /** {@code vibemod:content/bind}: arm the client's component bind, wait for the ack. */
    private static final class BindTask implements ConfigurationTask {

        @Override
        public void start(Consumer<Packet<?>> sender) {
            sender.accept(ServerConfigurationNetworking.createClientboundPacket(
                    new ContentBind(ContentManifest.PROTOCOL)));
        }

        @Override
        public Type type() {
            return BIND_TASK;
        }
    }

    // ------------------------------------------------------------------ the acks

    /**
     * The client's answer to the manifest: agreement, or a refusal at the door.
     *
     * <p>Three refusals, and every one of them ends the connection here rather
     * than letting it into play. A join refused during configuration costs a
     * player one reconnect and reads as a sentence; the alternative is a kick
     * minutes later over a wire id, with a vanilla message that explains
     * nothing.
     */
    private void onContentAck(ContentAck ack, ServerConfigurationNetworking.Context context) {
        ServerConfigurationPacketListenerImpl handler = context.packetListener();
        String who = handler.getOwner().name();
        ContentManifest manifest = configuring.get(handler);
        if (manifest == null) {
            disconnect(handler, "your client answered a VibeMod content manifest this server did "
                    + "not send it. Nothing was registered on either side; reconnect.");
            return;
        }

        if (ack.protocol() != ContentManifest.PROTOCOL) {
            disconnect(handler, "VibeMod content protocol mismatch: this server speaks version "
                    + ContentManifest.PROTOCOL + " and your client speaks " + ack.protocol()
                    + ". Update whichever is older — the two versions disagree about what the "
                    + "bytes mean, and guessing would register the wrong ids.");
            return;
        }
        if (ack.problem() != null) {
            disconnect(handler, "Your client could not register this server's VibeMod content: "
                    + ack.problem());
            return;
        }
        if (!manifest.orderHash().equals(ack.orderHash())) {
            disconnect(handler, "VibeMod content mismatch: this server built "
                    + manifest.entries().size() + " id(s) and your client built " + ack.count()
                    + ". Server order starts " + sample(manifest.orderKeys())
                    + "; your client's starts " + sample(ack.sampleIds())
                    + ". Registry order decides every wire id, so the join is refused here rather "
                    + "than failing later on a packet nobody could trace back.");
            return;
        }

        laneA.add(handler.getOwner().id());
        laneB.remove(handler.getOwner().id());
        LOG.info("Lane A: " + who + " agrees on " + ack.count() + " VibeMod id(s) (orderHash="
                + ack.orderHash() + "); handing the connection to fabric's registry sync");
        ((FabricServerConfigurationPacketListenerImpl) handler).completeTask(REGISTER_TASK);
    }

    /** The client's answer to the bind arming. */
    private void onBindAck(BindAck ack, ServerConfigurationNetworking.Context context) {
        ServerConfigurationPacketListenerImpl handler = context.packetListener();
        String who = handler.getOwner().name();
        if (ack.problem() != null) {
            disconnect(handler, "Your client could not bind data components for this server's "
                    + "VibeMod content: " + ack.problem() + ". Without that binding, the first "
                    + "stack of one of these items would throw \"Components not bound yet\", so "
                    + "the join is refused instead.");
            return;
        }
        LOG.info("Lane A: " + who + (ack.deferred()
                ? " armed its component bind for play join, which is where its registry provider "
                        + "is complete"
                : " bound components during configuration against a provider it already had"));
        // Last task of ours on this connection; the kept manifest has done its
        // job and holding it would be one live reference per join ever made.
        configuring.remove(handler);
        ((FabricServerConfigurationPacketListenerImpl) handler).completeTask(BIND_TASK);
    }

    private static void disconnect(ServerConfigurationPacketListenerImpl handler, String why) {
        LOG.warning("Refusing " + handler.getOwner().name() + " at configuration: " + why);
        handler.disconnect(Component.literal(why));
    }

    private static String sample(List<String> ids) {
        List<String> few = ids.subList(0, Math.min(ContentAck.SAMPLE, ids.size()));
        return few.isEmpty() ? "(nothing)" : String.join(", ", few);
    }

    // ------------------------------------------------------------------ the dedicated policy

    /**
     * Whether a mod may register content on this dedicated server right now.
     *
     * <p>The rule, and the second clause is the honest half:
     *
     * <ul>
     *   <li>Nobody connected — allowed. This is boot restore and it is the
     *       common case: the ids exist before anyone can be told a wrong one.</li>
     *   <li>Everyone connected is Lane A — allowed, <b>with a warning</b>. Their
     *       clients configured before this content existed, so it is not in
     *       their registries until they reconnect; handing one of these items to
     *       a player who is already in play writes a raw id their client does
     *       not have. Making that safe mid-session needs the reconfiguration
     *       bounce, which is a phase of its own.</li>
     *   <li>Anyone connected is Lane B — refused by name. That client is running
     *       a vanilla (or non-VibeMod) game; it was never told about any of
     *       this and cannot be.</li>
     * </ul>
     */
    @Override
    public String refusalOrNull(MinecraftServer server, String modName) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return null;
        }
        List<String> strangers = new ArrayList<>();
        for (ServerPlayer player : players) {
            if (!laneA.contains(player.getUUID())) {
                strangers.add(player.getName().getString());
            }
        }
        if (!strangers.isEmpty()) {
            return "player(s) " + String.join(", ", strangers) + " are connected and are not "
                    + "running VibeMod, so they were never told about this content and cannot "
                    + "be. Registering it now would leave the server holding ids their client "
                    + "will be kicked over. Ask them to install VibeMod, or add this content "
                    + "while they are offline — it applies to everyone who joins afterwards";
        }
        LOG.warning("Mod " + modName + " is registering content while " + players.size()
                + " VibeMod client(s) are already in play. Their registries were built when they "
                + "joined, so this content does NOT exist for them until they reconnect — do not "
                + "hand it to them before then. Delivering it mid-session needs a reconfiguration "
                + "bounce, which is not this phase");
        return null;
    }

    // ------------------------------------------------------------------ the manifest

    /**
     * The manifest as it stands right now, built from what the registries
     * actually hold rather than from what the ledger says should be in them.
     *
     * <p>{@link RegistrySeam#liveOrder()} is the source because it is the only
     * record of the order things really went in — including pinned stubs, which
     * occupy real ids, and disabled mods' entries, which are still in the
     * registry because nothing can take them out. A manifest derived from the
     * ledger's live mods would be short by exactly those, and a client one entry
     * short of the server is the failure this whole phase exists to prevent.
     */
    public ContentManifest buildManifest() {
        RegistryLedger book = seam.ledger();
        List<ContentManifest.Entry> entries = new ArrayList<>();
        for (RegistrySeam.Live live : seam.liveOrder()) {
            ContentManifest.Entry entry = describe(live, book);
            if (entry != null) {
                entries.add(entry);
            }
        }
        List<String> keys = new ArrayList<>(entries.size());
        for (ContentManifest.Entry entry : entries) {
            keys.add(entry.orderKey());
        }
        return new ContentManifest(ContentManifest.PROTOCOL,
                book == null ? "no-ledger" : book.installationId(),
                EPOCH,
                RegistryLedger.orderHash(keys),
                seam.blockStateBaseline(),
                seam.blockStateBaseline() + seam.blockStatesAppended(),
                entries);
    }

    private static ContentManifest.Entry describe(RegistrySeam.Live live, RegistryLedger book) {
        int version = book == null ? 0 : book.versionOf(live.modName());
        Object value = live.value();
        if (value instanceof Block block) {
            return new ContentManifest.Entry(live.registry(), live.id().toString(), live.modName(),
                    version, null, StubBlock.schemaOf(live.id(), block), null);
        }
        if (value instanceof Item item) {
            return new ContentManifest.Entry(live.registry(), live.id().toString(), live.modName(),
                    version, itemFacts(item), null, null);
        }
        if (value instanceof EntityType<?> type) {
            return new ContentManifest.Entry(live.registry(), live.id().toString(), live.modName(),
                    version, null, null, entityFacts(type));
        }
        // Not reachable while SUPPORTED is three registries; a fourth added
        // without a facts record would otherwise be silently missing from every
        // client's registry, which is the one failure mode with no symptom.
        LOG.warning("No manifest description for " + live.registry() + " " + live.id()
                + "; a Lane A client will not have it and the order hash will not match");
        return null;
    }

    private static ContentManifest.ItemFacts itemFacts(Item item) {
        Integer maxStack = item.components().get(DataComponents.MAX_STACK_SIZE);
        Integer maxDamage = item.components().get(DataComponents.MAX_DAMAGE);
        Rarity rarity = item.components().get(DataComponents.RARITY);
        Identifier model = item.components().get(DataComponents.ITEM_MODEL);
        Block block = item instanceof BlockItem blockItem ? blockItem.getBlock() : null;
        return new ContentManifest.ItemFacts(
                maxStack == null ? item.getDefaultMaxStackSize() : maxStack,
                maxDamage == null ? 0 : maxDamage,
                (rarity == null ? Rarity.COMMON : rarity).getSerializedName(),
                item.components().has(DataComponents.DAMAGE_RESISTANT),
                item.getDescriptionId(),
                model == null ? item.getDescriptionId() : model.toString(),
                block == null ? null : idOf(block));
    }

    private static String idOf(Block block) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static ContentManifest.EntityFacts entityFacts(EntityType<?> type) {
        return new ContentManifest.EntityFacts(type.getCategory().getSerializedName(),
                type.getWidth(), type.getHeight(), type.getDimensions().eyeHeight(),
                type.clientTrackingRange(), type.updateInterval(), type.fireImmune(),
                type.canSerialize(), type.canSummon());
    }

    // ------------------------------------------------------------------ state, for the gates

    /**
     * {@code "laneA=1 laneB=0 manifestEntries=3 manifestOrderHash=… installationId=…"}.
     *
     * <p>Its own line rather than an addition to
     * {@code RegistrySeam.describeState()}: the gates match full prefixes of
     * that string, and the palette guard's counters are already on the end of
     * it. A new field in the middle of a string somebody is prefix-matching is
     * a broken gate with no error message.
     */
    public String describeState() {
        ContentManifest manifest = buildManifest();
        RegistryLedger book = seam.ledger();
        return "laneA=" + laneA.size()
                + " laneB=" + laneB.size()
                + " manifestEntries=" + manifest.entries().size()
                + " manifestOrderHash=" + manifest.orderHash()
                + " manifestBlockStateBaseline=" + manifest.blockStateBaseline()
                + " installationId=" + (book == null ? "none" : book.installationId());
    }

    /** The UUIDs currently known to be on Lane A, for the gates. */
    public Set<UUID> laneAPlayers() {
        return new LinkedHashSet<>(laneA);
    }
}
