package com.gijsm.vibemod.fabric.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import com.gijsm.vibemod.store.BlockSchema;
import com.gijsm.vibemod.store.RegistryLedger;

/**
 * What a VibeMod server tells a joining VibeMod client to register, in the
 * order to register it (V4 Phase 2, Lane A).
 *
 * <h2>Declarative, and never source</h2>
 *
 * <p>VibeMod could ship the mod's Java to the client and let it compile —
 * every piece needed is already there. It must not, and the reason is not a
 * technical one. A joining client asked for nothing; "we {@code defineClass}
 * arbitrary Java handed to us by whatever server you typed into the multiplayer
 * screen" is not a claim VibeMod can honestly make to a stranger's server, and
 * no amount of sandboxing makes it one. The client needs <b>identity and
 * presentation</b>; every behaviour a generated mod has already runs on the
 * server, where it is watchdogged, attributed and revocable.
 *
 * <p>It also makes registration order an explicit ordered list rather than an
 * emergent property of somebody's branching {@code onInitialize()} — which is
 * the whole reason Lane A can promise that both ends agree.
 *
 * <h2>Why the presentation fields are scalars and not a component patch</h2>
 *
 * <p>The natural thing to send for an item is its {@code DataComponentPatch},
 * and the jar says no twice. {@code DataComponentPatch.STREAM_CODEC} is a
 * {@code StreamCodec<RegistryFriendlyByteBuf, …>}, while
 * {@code PayloadTypeRegistry.clientboundConfiguration()} is a
 * {@code PayloadTypeRegistry<FriendlyByteBuf>} (both verified) — there is no
 * registry-bound buffer during configuration. And that is not an accident of
 * typing: a component patch encodes registry references as <b>raw ids</b>, and
 * raw ids are exactly what has not been negotiated yet at the moment this
 * payload is sent. Fabric's {@code SyncConfigurationTask} does that
 * negotiation, and it runs <em>after</em> this manifest by design.
 *
 * <p>So what travels is the registry-free subset that presentation actually
 * needs: for an item, the numbers a client uses to draw a stack; for an entity
 * type, the size and tracking figures the client's tracker reads; for a block,
 * its {@link BlockSchema}, which already exists and already round-trips because
 * pinned stubs needed exactly the same thing.
 *
 * <h2>The two integrity fields</h2>
 *
 * <p>{@link #orderHash} is the agreement check — the client hashes what it
 * actually registered and sends it back, and a mismatch is a refusal at the
 * door instead of a kick ten minutes later.
 *
 * <p>{@link #blockStateBaseline} is the sharper one, and it exists because
 * Fabric's remap does not cover {@code Block.BLOCK_STATE_REGISTRY}: that is an
 * {@code IdMapper}, it appends at {@code nextId++}, it is never renumbered, and
 * chunk section palettes index it globally. A client that already holds runtime
 * blocks from <em>another</em> VibeMod server has a larger baseline than this
 * server's and cannot give the difference back — {@code MappedRegistry} has no
 * {@code remove} and neither does {@code IdMapper}. Sending the baseline lets
 * that be refused with a message a player can act on ("restart your client")
 * rather than becoming scrambled terrain.
 *
 * @param protocol           the manifest protocol version; a mismatch is a
 *                           refusal, because two versions of this format
 *                           disagreeing about a field is a silent wrong id
 * @param installationId     which VibeMod installation this content belongs to
 *                           ({@link RegistryLedger#installationId()})
 * @param epoch              which run of that installation, so a client can
 *                           tell a reconnect from a different content set
 * @param orderHash          {@link RegistryLedger#orderHash} over
 *                           {@link Entry#orderKey()} for every entry, in order
 * @param blockStateBaseline what {@code Block.BLOCK_STATE_REGISTRY.size()} was
 *                           on the server before VibeMod appended anything
 * @param blockStateTotal    and what it is now, so the client can check its own
 *                           arithmetic after it has appended
 * @param entries            the ordered content; register it exactly in this
 *                           order and nothing else
 */
public record ContentManifest(int protocol, String installationId, long epoch, String orderHash,
                              int blockStateBaseline, int blockStateTotal, List<Entry> entries)
        implements CustomPacketPayload {

    /**
     * Bumped whenever anything in this file's wire format changes.
     *
     * <p>Checked on both ends and refused on mismatch rather than tolerated:
     * the whole promise of Lane A is that two processes built the same
     * registries, and two processes reading the same bytes differently is the
     * one failure that would look exactly like success.
     */
    public static final int PROTOCOL = 1;

    /** The channel. Clientbound, configuration phase. */
    public static final Type<ContentManifest> TYPE =
            new Type<>(Identifier.parse("vibemod:content_manifest"));

    /**
     * The split threshold handed to {@code registerLarge}.
     *
     * <p>Fabric's clientbound configuration registry only splits a payload
     * whose declared maximum exceeds 1 MiB ({@code minimalSplittableSize},
     * verified in {@code PayloadTypeRegistryImpl.<init>}), and a server running
     * a few dozen generated mods will pass that. 32 MiB is a ceiling, not an
     * allocation: nothing is reserved, it only tells the splitter that a
     * payload this size is expected rather than a protocol error.
     */
    public static final int MAX_BYTES = 32 * 1024 * 1024;

    public static final StreamCodec<FriendlyByteBuf, ContentManifest> STREAM_CODEC =
            StreamCodec.of(ContentManifest::write, ContentManifest::read);

    /** Which registry an entry goes into, on the wire, as one byte. */
    private static final int KIND_ITEM = 0;
    private static final int KIND_BLOCK = 1;
    private static final int KIND_ENTITY = 2;

    public ContentManifest {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** True when any entry lands in {@code minecraft:block}; see {@link #blockStateBaseline}. */
    public boolean hasBlocks() {
        return entries.stream().anyMatch(entry -> entry.block() != null);
    }

    /** Every entry's {@link Entry#orderKey()}, in order — what the hash is taken over. */
    public List<String> orderKeys() {
        return entries.stream().map(Entry::orderKey).toList();
    }

    /**
     * One id the client is to register, and everything the client needs about
     * it that is not derivable from the id itself.
     *
     * <p>Exactly one of {@code item}, {@code block} and {@code entity} is
     * non-null; which one is a function of {@code registry}, and the wire
     * format carries it as a discriminator byte rather than trusting the two to
     * agree.
     *
     * <p>{@code modName} and {@code modVersion} are not used to register
     * anything — they are what a client-side log line and a refusal message
     * name, so a player who is told a join was refused can be told <em>which
     * mod's</em> id did it.
     */
    public record Entry(String registry, String id, String modName, int modVersion,
                        ItemFacts item, BlockSchema block, EntityFacts entity) {

        /** {@code "minecraft:item|vibemod_x:ruby"}; see {@link RegistryLedger#orderKey}. */
        public String orderKey() {
            return RegistryLedger.orderKey(registry, id);
        }
    }

    /**
     * What a client needs to draw a stack of an item it has never seen.
     *
     * <p>Every field here is a component of the item's base
     * {@code DataComponentMap} that the client reads for itself rather than
     * receiving per stack — which is exactly the set that would be silently
     * wrong if the two ends disagreed. Everything else about an item (what it
     * does, what it is worth, what it drops) is server-side and stays there.
     *
     * @param maxStackSize  {@code MAX_STACK_SIZE}; a client with the wrong one
     *                      draws the wrong stack count and refuses legal merges
     *                      in its own inventory
     * @param maxDamage     {@code MAX_DAMAGE}, or 0 for an unbreakable item —
     *                      the durability bar is drawn from it
     * @param rarity        the name colour, as {@code Rarity}'s serialized name
     *                      rather than its ordinal, so a reordered enum in a
     *                      future version is a miss rather than a silent shift
     * @param fireResistant whether {@code DAMAGE_RESISTANT} is set. Sent as a
     *                      boolean rather than as the component, because the
     *                      component holds a {@code TagKey<DamageType>} and
     *                      {@code Item.Properties.fireResistant()} is the only
     *                      way a generated mod can set one — a lossy encoding of
     *                      a surface that has exactly one member
     * @param descriptionId the item's <b>resolved</b> description id, sent whole
     *                      rather than derived. Derivation is where a client
     *                      quietly disagrees: a {@code BlockItem} takes the
     *                      {@code block.} prefix, a plain item the {@code item.}
     *                      one, and an override takes neither
     * @param itemModel     the resolved {@code ITEM_MODEL} identifier, sent for
     *                      the same reason — usually the id, but a mod may point
     *                      one item at another's model
     * @param blockId       the block a {@code BlockItem} places, or null for a
     *                      plain item. Without it the client builds a plain
     *                      {@code Item}, {@code Item.BY_BLOCK} has no entry and
     *                      {@code Block.asItem()} answers air — and pick-block is
     *                      resolved <em>client-side</em>, so that is a hole only
     *                      the client can fall into
     */
    public record ItemFacts(int maxStackSize, int maxDamage, String rarity, boolean fireResistant,
                            String descriptionId, String itemModel, String blockId) {
    }

    /**
     * What a client's entity tracker needs about an entity type.
     *
     * <p>The dimensions and the two tracking figures are the fields that make
     * the client's copy of the type agree with the server's about when an
     * entity is in range and how big its bounding box is. Behaviour, AI,
     * attributes and the entity class itself stay on the server; see
     * {@code ClientContentSync} for what the client actually builds, and for
     * the limitation that comes with it.
     *
     * @param category the {@code MobCategory} serialized name
     */
    public record EntityFacts(String category, float width, float height, float eyeHeight,
                              int clientTrackingRange, int updateInterval, boolean fireImmune,
                              boolean saveable, boolean summonable) {
    }

    // ------------------------------------------------------------------ the wire

    private static void write(FriendlyByteBuf buf, ContentManifest manifest) {
        buf.writeVarInt(manifest.protocol());
        buf.writeUtf(manifest.installationId());
        buf.writeLong(manifest.epoch());
        buf.writeUtf(manifest.orderHash());
        buf.writeVarInt(manifest.blockStateBaseline());
        buf.writeVarInt(manifest.blockStateTotal());
        buf.writeVarInt(manifest.entries().size());
        for (Entry entry : manifest.entries()) {
            buf.writeUtf(entry.registry());
            buf.writeUtf(entry.id());
            buf.writeUtf(entry.modName());
            buf.writeVarInt(entry.modVersion());
            if (entry.item() != null) {
                buf.writeByte(KIND_ITEM);
                writeItem(buf, entry.item());
            } else if (entry.block() != null) {
                buf.writeByte(KIND_BLOCK);
                writeBlock(buf, entry.block());
            } else if (entry.entity() != null) {
                buf.writeByte(KIND_ENTITY);
                writeEntity(buf, entry.entity());
            } else {
                // Unreachable through the server-side builder, which derives the
                // kind from the live object. Thrown rather than written as a
                // fourth "unknown" kind, because a client that received one
                // could only guess, and a guessed registration is the exact
                // thing this manifest exists to make impossible.
                throw new IllegalStateException("manifest entry " + entry.id() + " in "
                        + entry.registry() + " carries no presentation facts, so a client could "
                        + "not register it — refusing to encode it rather than sending a hole");
            }
        }
    }

    private static ContentManifest read(FriendlyByteBuf buf) {
        int protocol = buf.readVarInt();
        String installationId = buf.readUtf();
        long epoch = buf.readLong();
        String orderHash = buf.readUtf();
        int baseline = buf.readVarInt();
        int total = buf.readVarInt();
        int count = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(Math.min(count, 4096));
        for (int i = 0; i < count; i++) {
            String registry = buf.readUtf();
            String id = buf.readUtf();
            String modName = buf.readUtf();
            int modVersion = buf.readVarInt();
            int kind = buf.readByte();
            ItemFacts item = kind == KIND_ITEM ? readItem(buf) : null;
            BlockSchema block = kind == KIND_BLOCK ? readBlock(buf) : null;
            EntityFacts entity = kind == KIND_ENTITY ? readEntity(buf) : null;
            if (item == null && block == null && entity == null) {
                throw new IllegalStateException("manifest entry " + id + " carries kind " + kind
                        + ", which this VibeMod does not know how to register");
            }
            entries.add(new Entry(registry, id, modName, modVersion, item, block, entity));
        }
        return new ContentManifest(protocol, installationId, epoch, orderHash, baseline, total,
                entries);
    }

    private static void writeItem(FriendlyByteBuf buf, ItemFacts facts) {
        buf.writeVarInt(facts.maxStackSize());
        buf.writeVarInt(facts.maxDamage());
        buf.writeUtf(facts.rarity());
        buf.writeBoolean(facts.fireResistant());
        buf.writeUtf(facts.descriptionId());
        buf.writeUtf(facts.itemModel());
        writeNullableUtf(buf, facts.blockId());
    }

    private static ItemFacts readItem(FriendlyByteBuf buf) {
        return new ItemFacts(buf.readVarInt(), buf.readVarInt(), buf.readUtf(), buf.readBoolean(),
                buf.readUtf(), buf.readUtf(), readNullableUtf(buf));
    }

    private static void writeEntity(FriendlyByteBuf buf, EntityFacts facts) {
        buf.writeUtf(facts.category());
        buf.writeFloat(facts.width());
        buf.writeFloat(facts.height());
        buf.writeFloat(facts.eyeHeight());
        buf.writeVarInt(facts.clientTrackingRange());
        buf.writeVarInt(facts.updateInterval());
        buf.writeBoolean(facts.fireImmune());
        buf.writeBoolean(facts.saveable());
        buf.writeBoolean(facts.summonable());
    }

    private static EntityFacts readEntity(FriendlyByteBuf buf) {
        return new EntityFacts(buf.readUtf(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean());
    }

    /**
     * The block schema, field by field rather than through a generic codec.
     *
     * <p>Value order inside a property is written and read as written, because
     * it is load-bearing: a saved chunk records a state by property name and
     * value string, and a client whose rebuilt block offers the same values in
     * a different order builds a different state list. {@code BlockSchema}'s
     * own class comment has the same argument for the same reason.
     */
    private static void writeBlock(FriendlyByteBuf buf, BlockSchema schema) {
        buf.writeUtf(schema.id());
        buf.writeVarInt(schema.properties().size());
        for (BlockSchema.Prop prop : schema.properties()) {
            buf.writeUtf(prop.name());
            buf.writeVarInt(prop.values().size());
            for (String value : prop.values()) {
                buf.writeUtf(value);
            }
        }
        buf.writeVarInt(schema.stateCount());
    }

    private static BlockSchema readBlock(FriendlyByteBuf buf) {
        String id = buf.readUtf();
        int propertyCount = buf.readVarInt();
        List<BlockSchema.Prop> properties = new ArrayList<>(Math.min(propertyCount, 64));
        for (int i = 0; i < propertyCount; i++) {
            String name = buf.readUtf();
            int valueCount = buf.readVarInt();
            List<String> values = new ArrayList<>(Math.min(valueCount, 256));
            for (int v = 0; v < valueCount; v++) {
                values.add(buf.readUtf());
            }
            properties.add(new BlockSchema.Prop(name, values));
        }
        return new BlockSchema(id, properties, buf.readVarInt());
    }

    private static void writeNullableUtf(FriendlyByteBuf buf, String value) {
        buf.writeBoolean(value != null);
        if (value != null) {
            buf.writeUtf(value);
        }
    }

    private static String readNullableUtf(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUtf() : null;
    }
}
