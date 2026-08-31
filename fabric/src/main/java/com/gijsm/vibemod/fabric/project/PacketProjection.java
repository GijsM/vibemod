package com.gijsm.vibemod.fabric.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.mojang.datafixers.util.Pair;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.core.component.DataComponentExactPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * The packet table: one shape at a time, from a server that has content to a
 * client that does not (V4 Phase 4).
 *
 * <p>{@link Projection} is the interesting half and this is the tedious one, and
 * the tedium is the point. Every clientbound path that can carry a VibeMod id is
 * rewritten here or is declared in {@link ProjectedPackets} as not rewritten,
 * and {@code :fabric:packetGate} fails the build if a third possibility appears.
 *
 * <h2>What an unprojected id costs — two answers, not one</h2>
 *
 * <p>Which one applies decides whether a hole in the table is a bad afternoon or
 * a bug report, so it is worth reading off the codecs rather than guessing.
 *
 * <p><b>A binary field is a kick.</b> {@code ItemStack.STREAM_CODEC} writes the
 * item as a <b>raw numeric registry id</b>. A vanilla client whose item registry
 * stops where vanilla's does reads an id past its own end, and the decode does
 * not degrade — it throws, and the client is disconnected mid-play with
 * "Invalid packet". Container slots, equipment, recipe displays and advancement
 * icons are all on this path.
 *
 * <p><b>An NBT field is a shrug.</b> {@code ComponentSerialization.STREAM_CODEC}
 * is {@code ByteBufCodecs.fromCodecWithRegistries(CODEC)} (verified), so a
 * {@code Component} travels as NBT and a {@code HoverEvent.ShowItem} inside it
 * is written by {@code ItemStackTemplate}'s <em>map</em> codec — the item as a
 * <b>string</b> id. And {@code Style$Serializer} declares the field as
 * {@code HoverEvent.CODEC.optionalFieldOf("hover_event")}, whose DFU
 * implementation returns {@code Optional.empty()} when the inner decode fails.
 * So an unknown item in a hover loses the hover and nothing else. The same is
 * true of a stack inside a {@code CompoundTag}: an empty lectern, not a decoder
 * exception.
 *
 * <p>That split is the whole policy. {@link #project} <b>withholds</b> the
 * binary-path packets it cannot rewrite, because an empty recipe book beats a
 * disconnect loop; it lets the NBT-path ones through uncovered, because a
 * dropped hover beats withholding every scoreboard in the game. It withholds
 * <em>only while VibeMod content exists</em>
 * ({@link VanillaLane#contentExists()}), so a server with no runtime items runs
 * Lane B players on an untouched protocol.
 *
 * <h2>Identity on the free path</h2>
 *
 * <p>Every method here returns the packet it was given, by reference, when
 * nothing in it was projectable. A Lane B player standing in a vanilla world
 * pays one {@code instanceof} chain and no allocation per packet.
 */
public final class PacketProjection {

    private static final Logger LOG = Logger.getLogger("VibeMod.Lane");

    /** Packets rewritten on the way out, since boot. */
    private static final AtomicInteger REWRITTEN = new AtomicInteger();

    /** Packets not sent to a Lane B client at all, since boot. */
    private static final AtomicInteger WITHHELD = new AtomicInteger();

    /** Packets rewritten on the way in, since boot. */
    private static final AtomicInteger RETURNED = new AtomicInteger();

    private PacketProjection() {
    }

    /**
     * A clientbound packet as a Lane B client may receive it.
     *
     * @return the same packet when nothing needed doing, a rewritten one when
     *         something did, or <b>null</b> when the packet must not be sent at
     *         all — the caller drops it and says so
     */
    public static Packet<?> project(Packet<?> packet) {
        // ---- containers and inventories: the common case, and the one a
        //      creative player's items live or die by.
        if (packet instanceof ClientboundContainerSetSlotPacket p) {
            ItemStack projected = Projection.project(p.getItem());
            return projected == p.getItem() ? p : rewritten(new ClientboundContainerSetSlotPacket(
                    p.getContainerId(), p.getStateId(), p.getSlot(), projected));
        }
        if (packet instanceof ClientboundContainerSetContentPacket p) {
            List<ItemStack> items = Projection.projectAll(p.items());
            ItemStack carried = Projection.project(p.carriedItem());
            return items == p.items() && carried == p.carriedItem() ? p
                    : rewritten(new ClientboundContainerSetContentPacket(
                            p.containerId(), p.stateId(), items, carried));
        }
        if (packet instanceof ClientboundSetCursorItemPacket p) {
            ItemStack projected = Projection.project(p.contents());
            return projected == p.contents() ? p
                    : rewritten(new ClientboundSetCursorItemPacket(projected));
        }
        if (packet instanceof ClientboundSetPlayerInventoryPacket p) {
            ItemStack projected = Projection.project(p.contents());
            return projected == p.contents() ? p
                    : rewritten(new ClientboundSetPlayerInventoryPacket(p.slot(), projected));
        }
        if (packet instanceof ClientboundSetEquipmentPacket p) {
            return projectEquipment(p);
        }
        if (packet instanceof ClientboundMerchantOffersPacket p) {
            return projectOffers(p);
        }

        // ---- the world
        if (packet instanceof ClientboundSetEntityDataPacket p) {
            return projectEntityData(p);
        }
        if (packet instanceof ClientboundLevelParticlesPacket p) {
            return projectParticles(p);
        }
        if (packet instanceof ClientboundAddEntityPacket p) {
            return withholdVibeModEntity(p);
        }

        // ---- text: any Component may carry a ShowItem hover
        if (packet instanceof ClientboundSystemChatPacket p) {
            Component text = Projection.projectText(p.content());
            return text == p.content() ? p
                    : rewritten(new ClientboundSystemChatPacket(text, p.overlay()));
        }
        if (packet instanceof ClientboundDisguisedChatPacket p) {
            Component text = Projection.projectText(p.message());
            return text == p.message() ? p
                    : rewritten(new ClientboundDisguisedChatPacket(text, p.chatType()));
        }
        if (packet instanceof ClientboundPlayerChatPacket p) {
            // The SIGNED body is not touched — rewriting it would invalidate the
            // signature the client verifies. `unsignedContent` is the
            // server-decorated copy and is not covered by the signature, which is
            // exactly where a plugin's ShowItem hover lives.
            Component unsigned = Projection.projectText(p.unsignedContent());
            return unsigned == p.unsignedContent() ? p : rewritten(new ClientboundPlayerChatPacket(
                    p.globalIndex(), p.sender(), p.index(), p.signature(), p.body(),
                    unsigned, p.filterMask(), p.chatType()));
        }
        if (packet instanceof ClientboundSetTitleTextPacket p) {
            Component text = Projection.projectText(p.text());
            return text == p.text() ? p : rewritten(new ClientboundSetTitleTextPacket(text));
        }
        if (packet instanceof ClientboundSetSubtitleTextPacket p) {
            Component text = Projection.projectText(p.text());
            return text == p.text() ? p : rewritten(new ClientboundSetSubtitleTextPacket(text));
        }
        if (packet instanceof ClientboundSetActionBarTextPacket p) {
            Component text = Projection.projectText(p.text());
            return text == p.text() ? p : rewritten(new ClientboundSetActionBarTextPacket(text));
        }
        if (packet instanceof ClientboundTabListPacket p) {
            Component header = Projection.projectText(p.header());
            Component footer = Projection.projectText(p.footer());
            return header == p.header() && footer == p.footer() ? p
                    : rewritten(new ClientboundTabListPacket(header, footer));
        }
        if (packet instanceof ClientboundPlayerCombatKillPacket p) {
            // The death screen's message. Vanilla's own wording is "X was slain
            // by Y using [Ruby Sword]", and the bracketed part is a ShowItem
            // hover built from the killer's weapon — so this is the one text
            // path where a VibeMod stack reaches a client without anybody
            // deciding to put it there. The packet gate found it missing.
            Component text = Projection.projectText(p.message());
            return text == p.message() ? p
                    : rewritten(new ClientboundPlayerCombatKillPacket(p.playerId(), text));
        }
        if (packet instanceof ClientboundOpenScreenPacket p) {
            Component title = Projection.projectText(p.getTitle());
            return title == p.getTitle() ? p : rewritten(new ClientboundOpenScreenPacket(
                    p.getContainerId(), p.getType(), title));
        }

        // ---- suggestions
        if (packet instanceof ClientboundCommandSuggestionsPacket p) {
            return projectSuggestions(p);
        }

        // ---- advancements
        if (packet instanceof ClientboundUpdateAdvancementsPacket p) {
            return projectAdvancements(p);
        }

        // ---- the ones we cannot rewrite and therefore do not send
        if (packet instanceof ClientboundUpdateRecipesPacket
                || packet instanceof ClientboundRecipeBookAddPacket
                || packet instanceof ClientboundPlaceGhostRecipePacket) {
            return withholdUnprojectable(packet);
        }

        // ---- bundles carry other packets; recurse rather than guess
        if (packet instanceof ClientboundBundlePacket p) {
            return projectBundle(p);
        }

        return packet;
    }

    /**
     * A serverbound packet as the server should read it — the echo turned back
     * into the truth.
     *
     * <p>This is why the seam is duplex and not a {@code Connection.send} mixin.
     */
    public static Packet<?> unproject(Packet<?> packet) {
        if (packet instanceof ServerboundSetCreativeModeSlotPacket p) {
            ItemStack real = Projection.unproject(p.itemStack());
            return real == p.itemStack() ? p : returned(
                    new ServerboundSetCreativeModeSlotPacket(p.slotNum(), real));
        }
        if (packet instanceof ServerboundContainerClickPacket p) {
            return unprojectClick(p);
        }
        return packet;
    }

    // ------------------------------------------------------------------ shapes

    private static Packet<?> projectEquipment(ClientboundSetEquipmentPacket packet) {
        List<Pair<EquipmentSlot, ItemStack>> slots = packet.getSlots();
        List<Pair<EquipmentSlot, ItemStack>> out = null;
        for (int i = 0; i < slots.size(); i++) {
            Pair<EquipmentSlot, ItemStack> pair = slots.get(i);
            ItemStack projected = Projection.project(pair.getSecond());
            if (projected != pair.getSecond()) {
                if (out == null) {
                    out = new ArrayList<>(slots);
                }
                out.set(i, Pair.of(pair.getFirst(), projected));
            }
        }
        return out == null ? packet
                : rewritten(new ClientboundSetEquipmentPacket(packet.getEntity(), out));
    }

    /**
     * Trades, which are three stacks each and one awkward record.
     *
     * <p>{@code ItemCost}'s wire form is {@code (Holder<Item>, count, predicate)}
     * — {@code itemStack()} is <em>derived</em> on the far side and is not sent —
     * so projecting a cost means rebuilding the predicate, not swapping the
     * stack. {@code someOf(..., ITEM_MODEL, ITEM_NAME, CUSTOM_DATA)} names
     * exactly the three components that make a projection a projection, and
     * nothing else: copying the whole component map would put the base item's
     * prototype on the wire for no benefit.
     *
     * <p>An offer with no projectable stack is reused by reference, so a vanilla
     * villager's trades are untouched even on a server full of content.
     */
    private static Packet<?> projectOffers(ClientboundMerchantOffersPacket packet) {
        MerchantOffers offers = packet.getOffers();
        MerchantOffers out = null;
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            ItemStack result = Projection.project(offer.getResult());
            ItemCost costA = projectCost(offer.getItemCostA());
            Optional<ItemCost> costB = offer.getItemCostB().map(PacketProjection::projectCost);
            boolean changed = result != offer.getResult()
                    || costA != offer.getItemCostA()
                    || !costB.equals(offer.getItemCostB());
            if (!changed) {
                if (out != null) {
                    out.add(offer);
                }
                continue;
            }
            if (out == null) {
                out = new MerchantOffers();
                for (int j = 0; j < i; j++) {
                    out.add(offers.get(j));
                }
            }
            MerchantOffer rebuilt = new MerchantOffer(costA, costB, result, offer.getUses(),
                    offer.getMaxUses(), offer.getXp(), offer.getPriceMultiplier(), offer.getDemand());
            rebuilt.setSpecialPriceDiff(offer.getSpecialPriceDiff());
            out.add(rebuilt);
        }
        return out == null ? packet : rewritten(new ClientboundMerchantOffersPacket(
                packet.getContainerId(), out, packet.getVillagerLevel(), packet.getVillagerXp(),
                packet.showProgress(), packet.canRestock()));
    }

    private static ItemCost projectCost(ItemCost cost) {
        ItemStack stack = cost.itemStack();
        ItemStack projected = Projection.project(stack);
        if (projected == stack) {
            return cost;
        }
        return new ItemCost(projected.typeHolder(), projected.getCount(),
                DataComponentExactPredicate.someOf(projected.getComponents(),
                        DataComponents.ITEM_MODEL, DataComponents.ITEM_NAME,
                        DataComponents.CUSTOM_DATA));
    }

    /**
     * Item entities, item frames and armour stands.
     *
     * <p>{@code SynchedEntityData} entries are {@code (id, serializer, value)}
     * triples and the serializer is written as its own numeric id, so projecting
     * an {@code ITEM_STACK}-valued entry needs no new serializer and no new
     * entity type: only the value changes. That is the difference between this
     * being nine lines and entity projection being a subproject — the entity
     * type is unchanged, so the id space the client resolves against is
     * unchanged too.
     */
    private static Packet<?> projectEntityData(ClientboundSetEntityDataPacket packet) {
        List<SynchedEntityData.DataValue<?>> values = packet.packedItems();
        List<SynchedEntityData.DataValue<?>> out = null;
        for (int i = 0; i < values.size(); i++) {
            SynchedEntityData.DataValue<?> value = values.get(i);
            SynchedEntityData.DataValue<?> projected = projectDataValue(value);
            if (projected != value) {
                if (out == null) {
                    out = new ArrayList<>(values);
                }
                out.set(i, projected);
            }
        }
        return out == null ? packet
                : rewritten(new ClientboundSetEntityDataPacket(packet.id(), out));
    }

    private static SynchedEntityData.DataValue<?> projectDataValue(
            SynchedEntityData.DataValue<?> value) {
        EntityDataSerializer<?> serializer = value.serializer();
        if (serializer == EntityDataSerializers.ITEM_STACK && value.value() instanceof ItemStack stack) {
            ItemStack projected = Projection.project(stack);
            if (projected != stack) {
                return new SynchedEntityData.DataValue<>(value.id(),
                        EntityDataSerializers.ITEM_STACK, projected);
            }
        }
        return value;
    }

    private static Packet<?> projectParticles(ClientboundLevelParticlesPacket packet) {
        ParticleOptions particle = packet.getParticle();
        if (!(particle instanceof ItemParticleOption item)) {
            return packet;
        }
        ItemStack shown = item.getItem().create();
        ItemStack projected = Projection.project(shown);
        if (projected == shown) {
            return packet;
        }
        ItemParticleOption replacement = new ItemParticleOption(item.getType(),
                ItemStackTemplate.fromStack(projected));
        return rewritten(new ClientboundLevelParticlesPacket(replacement,
                packet.isOverrideLimiter(), packet.alwaysShow(),
                packet.getX(), packet.getY(), packet.getZ(),
                packet.getXDist(), packet.getYDist(), packet.getZDist(),
                packet.getMaxSpeed(), packet.getCount()));
    }

    /**
     * Drops {@code vibemod_*} ids out of a suggestion list.
     *
     * <p>Not a projection — there is nothing to project a completion string
     * onto. Offering a vanilla player {@code vibemod_rubycharm:ruby} in
     * {@code /give} is offering them a command that cannot succeed for them, so
     * the honest answer is not to offer it.
     */
    private static Packet<?> projectSuggestions(ClientboundCommandSuggestionsPacket packet) {
        List<ClientboundCommandSuggestionsPacket.Entry> entries = packet.suggestions();
        List<ClientboundCommandSuggestionsPacket.Entry> kept = null;
        for (int i = 0; i < entries.size(); i++) {
            ClientboundCommandSuggestionsPacket.Entry entry = entries.get(i);
            if (RegistryHiding.isVibeModNamespace(namespaceOf(entry.text()))) {
                if (kept == null) {
                    kept = new ArrayList<>(entries.subList(0, i));
                }
                continue;
            }
            if (kept != null) {
                kept.add(entry);
            }
        }
        return kept == null ? packet : rewritten(new ClientboundCommandSuggestionsPacket(
                packet.id(), packet.start(), packet.length(), kept));
    }

    /** {@code "vibemod_x:ruby"} → {@code "vibemod_x"}; anything without a colon → null. */
    private static String namespaceOf(String suggestion) {
        int colon = suggestion.indexOf(':');
        return colon <= 0 ? null : suggestion.substring(0, colon);
    }

    private static Packet<?> projectBundle(ClientboundBundlePacket packet) {
        List<Packet<? super ClientGamePacketListener>> out = null;
        int i = 0;
        for (Packet<? super ClientGamePacketListener> sub : packet.subPackets()) {
            Packet<?> projected = project(sub);
            if (projected != sub && out == null) {
                out = new ArrayList<>();
                int j = 0;
                for (Packet<? super ClientGamePacketListener> earlier : packet.subPackets()) {
                    if (j++ >= i) {
                        break;
                    }
                    out.add(earlier);
                }
            }
            if (out != null && projected != null) {
                @SuppressWarnings("unchecked")
                Packet<? super ClientGamePacketListener> cast =
                        (Packet<? super ClientGamePacketListener>) projected;
                out.add(cast);
            }
            i++;
        }
        return out == null ? packet : rewritten(new ClientboundBundlePacket(out));
    }

    /**
     * Advancement toasts, which are three records deep and worth the trip.
     *
     * <p>Only four of {@code Advancement}'s seven components go on the wire —
     * read off {@code Advancement.write}, which encodes {@code parent},
     * {@code display}, {@code requirements} and {@code sendsTelemetryEvent} and
     * nothing else; the reader rebuilds criteria as {@code Map.of()} and rewards
     * as {@code AdvancementRewards.EMPTY}. So the icon inside {@code DisplayInfo}
     * is the <em>only</em> thing here that can carry a VibeMod id, and projecting
     * it is complete rather than partial.
     *
     * <p>That is why this one is rewritten where the recipe packets are
     * withheld: the tree is three records deep but it is finite and every
     * constructor on the path is public. The title and description are
     * Components and get the ShowItem treatment for free.
     *
     * <p>{@code setLocation} after construction, because the x/y are the two
     * non-final fields on {@code DisplayInfo} and the constructor does not take
     * them — miss it and every advancement piles up at the origin of its tab.
     */
    private static Packet<?> projectAdvancements(ClientboundUpdateAdvancementsPacket packet) {
        List<AdvancementHolder> added = packet.getAdded();
        List<AdvancementHolder> out = null;
        for (int i = 0; i < added.size(); i++) {
            AdvancementHolder holder = added.get(i);
            AdvancementHolder projected = projectAdvancement(holder);
            if (projected != holder) {
                if (out == null) {
                    out = new ArrayList<>(added);
                }
                out.set(i, projected);
            }
        }
        return out == null ? packet : rewritten(new ClientboundUpdateAdvancementsPacket(
                packet.shouldReset(), out, packet.getRemoved(), packet.getProgress(),
                packet.shouldShowAdvancements()));
    }

    private static AdvancementHolder projectAdvancement(AdvancementHolder holder) {
        Advancement advancement = holder.value();
        Optional<DisplayInfo> display = advancement.display();
        if (display.isEmpty()) {
            return holder;
        }
        DisplayInfo info = display.get();
        ItemStack icon = info.getIcon().create();
        ItemStack projectedIcon = Projection.project(icon);
        Component title = Projection.projectText(info.getTitle());
        Component description = Projection.projectText(info.getDescription());
        if (projectedIcon == icon && title == info.getTitle()
                && description == info.getDescription()) {
            return holder;
        }
        DisplayInfo rebuilt = new DisplayInfo(ItemStackTemplate.fromStack(projectedIcon), title,
                description, info.getBackground(), info.getType(), info.shouldShowToast(),
                info.shouldAnnounceChat(), info.isHidden());
        rebuilt.setLocation(info.getX(), info.getY());
        return new AdvancementHolder(holder.id(), new Advancement(advancement.parent(),
                Optional.of(rebuilt), advancement.rewards(), advancement.criteria(),
                advancement.requirements(), advancement.sendsTelemetryEvent(), advancement.name()));
    }

    // ------------------------------------------------------------------ refusals

    /**
     * Entities: refused, not projected.
     *
     * <p>Projecting the type would mean re-synthesising the whole of
     * {@code SynchedEntityData} for the stand-in, because a serializer id is
     * per-type and the stand-in's accessors do not line up with ours. That is
     * the module Polymer needed a whole virtual-entity subproject for, and this
     * phase does not pretend otherwise: a VibeMod entity is simply not spawned
     * for a Lane B client, and the mod is told at load.
     */
    private static Packet<?> withholdVibeModEntity(ClientboundAddEntityPacket packet) {
        EntityType<?> type = packet.getType();
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (id == null || !RegistryHiding.isVibeModNamespace(id.getNamespace())) {
            return packet;
        }
        WITHHELD.incrementAndGet();
        return null;
    }

    /**
     * A packet whose content we cannot rewrite, dropped rather than sent.
     *
     * <p>Only while VibeMod content exists. On a server with no runtime items a
     * recipe list cannot contain one, so nothing is withheld and Lane B is
     * ordinary vanilla — that condition is the difference between a bound and a
     * regression.
     */
    private static Packet<?> withholdUnprojectable(Packet<?> packet) {
        if (!VanillaLane.contentExists()) {
            return packet;
        }
        if (WITHHELD.getAndIncrement() % 64 == 0) {
            LOG.info("withholding " + packet.getClass().getSimpleName() + " from a vanilla client: "
                    + "its stacks sit inside record trees this version does not rewrite, and an "
                    + "unprojected item id is written as a raw registry id that disconnects the "
                    + "client rather than rendering wrong. See ProjectedPackets for the declared bound");
        }
        return null;
    }

    // ------------------------------------------------------------------ inbound

    /**
     * The click echo, answered by lying consistently rather than by rewriting.
     *
     * <p>Since 1.21.5 a click carries {@code HashedStack}s, not stacks: the
     * client hashes what <em>it</em> holds and the server asks
     * {@code hashed.matches(serverStack, generator)}. The client holds the
     * projection, the server holds the truth, and the two hashes disagree — so
     * every click on a VibeMod item would resync the whole container and throw
     * the client's prediction away.
     *
     * <p>There is nothing to un-hash. What there is, is a seam:
     * {@code HashedStack} is an interface and is <b>not sealed</b> (verified —
     * no {@code PermittedSubclasses} attribute on the 26.2 class), so each one is
     * wrapped in a {@link ProjectedHash} that answers
     * {@code matches(trueStack, gen)} by asking the original about
     * {@code project(trueStack)}. The server's own desync check then agrees with
     * a client that was told exactly the truth we chose to tell it.
     */
    private static Packet<?> unprojectClick(ServerboundContainerClickPacket packet) {
        if (!VanillaLane.contentExists()) {
            return packet;
        }
        Int2ObjectMap<HashedStack> slots = packet.changedSlots();
        Int2ObjectMap<HashedStack> wrapped = new Int2ObjectOpenHashMap<>(slots.size());
        for (Int2ObjectMap.Entry<HashedStack> entry : slots.int2ObjectEntrySet()) {
            wrapped.put(entry.getIntKey(), new ProjectedHash(entry.getValue()));
        }
        return returned(new ServerboundContainerClickPacket(packet.containerId(), packet.stateId(),
                packet.slotNum(), packet.buttonNum(), packet.containerInput(), wrapped,
                new ProjectedHash(packet.carriedItem())));
    }

    /**
     * A {@code HashedStack} that is asked about the projection of the stack it
     * is handed.
     *
     * <p>Free for a vanilla stack: {@link Projection#project} returns the same
     * instance, so the delegate sees exactly what it would have seen.
     *
     * <p>That this is enough is read off {@code RemoteSlot$Synchronized} rather
     * than assumed. Disassembled, {@code receive(hashed)} nulls
     * {@code remoteStack} and stores the hash; {@code matches(stack)} then falls
     * through to {@code remoteHash.matches(stack, hasher)} and, on a match,
     * caches {@code stack.copy()} as the new {@code remoteStack}. So the one
     * question ever asked of a {@code HashedStack} is the one this record
     * answers — and the stack the server remembers afterwards is the true one,
     * not the projection.
     */
    private record ProjectedHash(HashedStack delegate) implements HashedStack {

        @Override
        public boolean matches(ItemStack stack, HashedPatchMap.HashGenerator generator) {
            return delegate.matches(Projection.project(stack), generator);
        }
    }

    // ------------------------------------------------------------------ counters

    private static Packet<?> rewritten(Packet<?> packet) {
        REWRITTEN.incrementAndGet();
        return packet;
    }

    private static Packet<?> returned(Packet<?> packet) {
        RETURNED.incrementAndGet();
        return packet;
    }

    /** {@code "packetsRewritten=41 packetsWithheld=0 packetsReturned=2"}. */
    public static String describeState() {
        return "packetsRewritten=" + REWRITTEN.get()
                + " packetsWithheld=" + WITHHELD.get()
                + " packetsReturned=" + RETURNED.get();
    }
}
