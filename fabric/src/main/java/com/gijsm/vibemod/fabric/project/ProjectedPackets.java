package com.gijsm.vibemod.fabric.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The declared coverage of Lane B's projection, one row per game packet that
 * can reach content (V4 Phase 4).
 *
 * <p>This table is not documentation. It is the <b>input to a build gate</b>:
 * {@code :fabric:packetGate} reflects over every {@code Clientbound*} and
 * {@code Serverbound*} in {@code net.minecraft.network.protocol.game}, walks
 * each one's record components and fields transitively, and fails the build on
 * any packet that reaches {@code ItemStack}, {@code Holder<Item>},
 * {@code BlockState}, {@code EntityType}, {@code ParticleOptions} or
 * {@code Component} and has no row here. It also fails on a row whose packet no
 * longer reaches any of those, so a row cannot quietly outlive the thing it was
 * written for.
 *
 * <p><b>A rule is required for an opaque boundary too</b>, and that turned out
 * to be the load-bearing half. A type walk stops at a non-sealed interface — it
 * cannot enumerate the implementors — and at an encoding: a {@code Tag} or a
 * {@code byte[]}. Those are precisely where content hides. The gate's first run
 * proved it by asking for the deletion of the two most important rows in this
 * file: {@code ClientboundSetEntityDataPacket}, whose stack sits behind
 * {@code SynchedEntityData.DataValue}'s erased {@code T value} and a non-sealed
 * {@code EntityDataSerializer}, and {@code ServerboundContainerClickPacket},
 * whose stacks are {@code HashedStack}s. Both "reach nothing" by type and carry
 * everything in practice. So a boundary demands a declaration rather than
 * excusing the absence of one.
 *
 * <p>The point is that "did we miss a packet" and "did the next Minecraft
 * version add one" stop being a player's bug report and become a compile-time
 * diagnostic. Lane B is a projection of a live protocol onto a client that does
 * not know about half of it; without this table it rots on every game update,
 * silently, and the symptom is one player holding a piece of paper.
 *
 * <p><b>Every row is a claim about what a Lane B player sees</b>, which is why
 * {@link Coverage#UNCOVERED} exists. A packet whose content is not projected is
 * declared here with the reason and the symptom, so the bound is stated rather
 * than discovered. That is the "no silent caps" rule applied to a table.
 *
 * <p>The projection reads this table at install time and logs its own
 * coverage, so the runtime and the gate cannot disagree about what is claimed:
 * there is one list and both sides read it.
 */
public final class ProjectedPackets {

    private ProjectedPackets() {
    }

    /** What Lane B does with one packet that reaches content. */
    public enum Coverage {

        /**
         * Rewritten on the way out (or on the way in) so a vanilla client sees
         * a stack, a name and a model it can actually resolve.
         */
        PROJECTED,

        /**
         * Not sent to a Lane B client at all. Reserved for the content whose
         * projection is refused rather than attempted — entities, whose
         * stand-in would need {@code SynchedEntityData} re-synthesised against
         * a different type's serializer ids.
         */
        WITHHELD,

        /**
         * Reaches content and needs nothing, because the wire form already
         * works on a vanilla client. Sounds are the whole of this category and
         * the one genuinely lossless win in the phase.
         *
         * <p>The only coverage the gate does <b>not</b> require to reach one of
         * the six sentinel types: a {@code Holder<SoundEvent>} is not on that
         * list, and the row exists to write the mechanism down rather than to
         * claim a rewrite.
         */
        LOSSLESS,

        /**
         * Reaches one of the sentinel types by reflection, but cannot carry
         * VibeMod content: the value is always a vanilla one, or the field is
         * server-authoritative and never round-trips an id.
         */
        EXEMPT,

        /**
         * Reaches content that this version does <b>not</b> project. The
         * {@code why} says what a Lane B player sees instead. Declared so the
         * bound is visible in one place rather than inferred from an absence.
         */
        UNCOVERED
    }

    /**
     * One packet's coverage.
     *
     * @param packet   the simple class name, e.g. {@code ClientboundSetCursorItemPacket}
     * @param coverage what Lane B does with it
     * @param why      the mechanism, in one sentence — for {@code UNCOVERED},
     *                 what the player sees instead
     */
    public record Rule(String packet, Coverage coverage, String why) {
    }

    private static final Map<String, Rule> RULES = new LinkedHashMap<>();

    private static void rule(String packet, Coverage coverage, String why) {
        RULES.put(packet, new Rule(packet, coverage, why));
    }

    static {
        // ------------------------------------------------------- items, projected

        rule("ClientboundContainerSetSlotPacket", Coverage.PROJECTED,
                "one stack into an open container; the single most common way a VibeMod item reaches a screen");
        rule("ClientboundContainerSetContentPacket", Coverage.PROJECTED,
                "a whole container plus the carried stack; every slot is projected and so is the cursor");
        rule("ClientboundSetCursorItemPacket", Coverage.PROJECTED,
                "the stack on the mouse; unprojected it is the one a click echoes straight back");
        rule("ClientboundSetPlayerInventoryPacket", Coverage.PROJECTED,
                "a single player-inventory slot, sent outside any container");
        rule("ClientboundSetEquipmentPacket", Coverage.PROJECTED,
                "armour and held items on any entity, including other players");
        rule("ClientboundMerchantOffersPacket", Coverage.PROJECTED,
                "trade costs and results, each of which is a stack");
        rule("ClientboundSetEntityDataPacket", Coverage.PROJECTED,
                "item entities, item frames and armour stands carry their stack in synched data; "
                        + "ItemStack-valued entries are projected in place, which needs no new serializer id "
                        + "because the entity type is unchanged");
        rule("ClientboundLevelParticlesPacket", Coverage.PROJECTED,
                "ItemParticleOption carries a stack; the base item's texture is what breaks and eats");

        // ------------------------------------------------------- text, projected

        rule("ClientboundSystemChatPacket", Coverage.PROJECTED,
                "any Component may carry HoverEvent.ShowItem; the hovered stack is projected like any other");
        rule("ClientboundDisguisedChatPacket", Coverage.PROJECTED,
                "same, for unsigned chat routed through the chat type decoration");
        rule("ClientboundSetTitleTextPacket", Coverage.PROJECTED, "title text may carry a ShowItem hover");
        rule("ClientboundSetSubtitleTextPacket", Coverage.PROJECTED, "as above");
        rule("ClientboundSetActionBarTextPacket", Coverage.PROJECTED, "as above");
        rule("ClientboundOpenScreenPacket", Coverage.PROJECTED, "the screen title may carry a ShowItem hover");
        rule("ClientboundPlayerCombatKillPacket", Coverage.PROJECTED,
                "the death message, and not a theoretical one: \"X was slain by Y using [Ruby Sword]\" is "
                        + "vanilla's own wording and the bracketed part IS a ShowItem hover. The packet gate "
                        + "found this row's absence, which is the gate paying for itself");
        rule("ClientboundTabListPacket", Coverage.PROJECTED, "header and footer may carry a ShowItem hover");
        rule("ClientboundBossEventPacket", Coverage.UNCOVERED,
                "the bar name may carry a ShowItem hover, and it cannot be rewritten: every "
                        + "constructor is private and the Operation record it wraps is private too, so "
                        + "the only way to build one is a createXPacket(BossEvent) static that needs a "
                        + "live BossEvent we do not have on the packet path. Sent anyway, because a "
                        + "Component's hover is NBT and a lenient optional field — see "
                        + "ClientboundSetObjectivePacket for the codec argument. The row is here so the "
                        + "gate keeps the private Operation visible as the boundary it is");

        rule("ClientboundPlayerChatPacket", Coverage.PROJECTED,
                "half of it. unsignedContent is the server-decorated copy and is NOT covered by the "
                        + "signature, so that is where a plugin's ShowItem hover lives and that is what is "
                        + "projected. The signed body is left alone on purpose: rewriting it would "
                        + "invalidate the signature the client verifies, which is a refusal rather than a "
                        + "gap");

        // ------------------------------------------------------- suggestions

        rule("ClientboundCommandSuggestionsPacket", Coverage.PROJECTED,
                "vibemod_* ids are dropped from the list so /give does not offer a vanilla player an id its "
                        + "own registries do not have");

        // ------------------------------------------------------- sound: the lossless win

        rule("ClientboundSoundPacket", Coverage.LOSSLESS,
                "the wire form is Holder<SoundEvent> and vanilla accepts an INLINE holder carrying a bare "
                        + "Identifier, which the client resolves against its own sound atlas. A vanilla client "
                        + "with Phase 3's pack therefore plays a genuinely new sound with no projection at all — "
                        + "the one thing in this phase that loses nothing");
        rule("ClientboundSoundEntityPacket", Coverage.LOSSLESS, "as above, positioned on an entity");
        rule("ClientboundStopSoundPacket", Coverage.LOSSLESS, "names a sound by Identifier; nothing to project");

        // ------------------------------------------------------- entities: withheld

        rule("ClientboundAddEntityPacket", Coverage.WITHHELD,
                "an entity of a VibeMod type is not spawned for a Lane B client. Projecting the type would "
                        + "mean re-synthesising SynchedEntityData for the stand-in, because serializer ids are "
                        + "per-type — this is the module Polymer needed a whole subproject for. The mod is told "
                        + "at load; see EntityRefusal. Vanilla bundles a spawn with its data packets, so the "
                        + "client also receives synched data for an entity it never learned about — which is "
                        + "harmless rather than untidy: ClientPacketListener.handleSetEntityData opens with a "
                        + "null check on level.getEntity(id) and drops it");

        // ------------------------------------------------------- blocks: reserved

        rule("ClientboundBlockUpdatePacket", Coverage.UNCOVERED,
                "block projection is a reserved seam and nothing is built; blocks are singleplayer/LAN only "
                        + "and a Lane B client is refused content it would need. See BlockProjectionSeam");
        rule("ClientboundSectionBlocksUpdatePacket", Coverage.UNCOVERED, "as ClientboundBlockUpdatePacket");
        rule("ClientboundLevelChunkWithLightPacket", Coverage.UNCOVERED, "as ClientboundBlockUpdatePacket");
        rule("ClientboundBlockEntityDataPacket", Coverage.UNCOVERED,
                "lecterns, jukeboxes and decorated pots carry their stack inside a CompoundTag rather than "
                        + "an ItemStack field, so projecting it means editing NBT per block-entity type. It is "
                        + "the ONE uncovered item path that is not a disconnect: NBT writes a stack by STRING "
                        + "id, and an unknown string fails the codec softly — the field is dropped and the "
                        + "lectern is empty. Sent rather than withheld for exactly that reason");
        rule("ClientboundLevelChunkPacketData", Coverage.UNCOVERED,
                "the chunk payload itself: `buffer` is the packed blockstate and biome palettes and "
                        + "`blockEntitiesData` is a CompoundTag per block entity. Same seam, same absence "
                        + "as ClientboundLevelChunkWithLightPacket, and it earns its own row because the "
                        + "gate scans it as a packet in its own right");
        rule("ClientboundChunksBiomesPacket", Coverage.UNCOVERED,
                "biome ids, packed into a byte[] the same way blockstates are. Nothing today can put a "
                        + "VibeMod id in one — biomes are Phase 5's dynamic registries, not this phase's — "
                        + "but the day they can, this is where a vanilla client reads a raw id past the end "
                        + "of its own biome registry. Declared now so the seam is named before it is used");
        rule("ClientboundExplodePacket", Coverage.EXEMPT, "particles and a sound, both vanilla by construction");
        rule("ClientboundLightUpdatePacket", Coverage.EXEMPT,
                "sky and block light as nibble arrays. A byte[] is an encoding boundary and the gate is "
                        + "right to stop there, but this one holds light LEVELS — there is no palette and no "
                        + "id anywhere in it");
        rule("ClientboundLightUpdatePacketData", Coverage.EXEMPT, "as ClientboundLightUpdatePacket");

        // ------------------------------------------------------- recipes and advancements

        rule("ClientboundUpdateRecipesPacket", Coverage.WITHHELD,
                "recipe displays are a tree of SlotDisplay and RecipeDisplay records and this version does "
                        + "not walk it. Not sending it is the safe half of that: an ItemStack on the wire is "
                        + "a RAW registry id, so an unprojected VibeMod result would not render wrong, it "
                        + "would fail the client's decoder and disconnect it. A Lane B player gets an empty "
                        + "recipe book while VibeMod content exists, and an ordinary one when it does not");
        rule("ClientboundRecipeBookAddPacket", Coverage.WITHHELD, "as ClientboundUpdateRecipesPacket");
        rule("ClientboundPlaceGhostRecipePacket", Coverage.WITHHELD, "as ClientboundUpdateRecipesPacket");
        rule("ClientboundUpdateAdvancementsPacket", Coverage.PROJECTED,
                "the display icon, title and description, three records deep (AdvancementHolder -> "
                        + "Advancement -> DisplayInfo). Rewritten rather than withheld because the tree is "
                        + "finite and every constructor on the path is public — and because it is provably "
                        + "complete: Advancement.write encodes only parent, display, requirements and "
                        + "sendsTelemetryEvent, rebuilding criteria as Map.of() and rewards as EMPTY on the "
                        + "far side, so the icon is the only id that ever goes out");

        // ------------------------------------------------------- vanilla-only reaches

        rule("ClientboundCommandsPacket", Coverage.EXEMPT,
                "argument types by id; a VibeMod command's arguments are all vanilla brigadier types");
        rule("ClientboundMapItemDataPacket", Coverage.EXEMPT, "map decorations and pixels");
        rule("ClientboundSetObjectivePacket", Coverage.UNCOVERED,
                "an objective display name may carry a ShowItem hover. Sent rather than withheld, and the "
                        + "reason is the codec: a Component travels as NBT, so the hovered item is a STRING id, "
                        + "and Style$Serializer declares hover_event with Codec.optionalFieldOf — DFU's lenient "
                        + "one, which yields Optional.empty() on an inner failure. The symptom is a hover that "
                        + "does not appear, not a disconnect. Withholding every scoreboard packet to save one "
                        + "tooltip would be the worse trade");
        rule("ClientboundSetScorePacket", Coverage.UNCOVERED, "as ClientboundSetObjectivePacket");
        rule("ClientboundSetPlayerTeamPacket", Coverage.UNCOVERED, "as ClientboundSetObjectivePacket");
        rule("ClientboundPlayerInfoUpdatePacket", Coverage.UNCOVERED, "as ClientboundSetObjectivePacket");
        rule("ClientboundServerDataPacket", Coverage.EXEMPT,
                "the MOTD, which is server.properties text and never built from a stack");
        rule("ClientboundAwardStatsPacket", Coverage.EXEMPT,
                "Stat carries a StatFormatter, which is a non-sealed interface and therefore an opaque "
                        + "boundary — but every Stat is a built-in registry entry, so nothing VibeMod mints can "
                        + "be in it");
        rule("ClientboundTrackedWaypointPacket", Coverage.EXEMPT,
                "TrackedWaypoint is a non-sealed interface and so an opaque boundary; its implementations "
                        + "carry a position, a colour and a UUID, and no path from a waypoint to a stack exists");
        rule("ClientboundTestInstanceBlockStatus", Coverage.EXEMPT,
                "a gametest block's status line, written by the test framework and reachable only by an "
                        + "operator in a test world");
        rule("ServerboundTestInstanceBlockActionPacket", Coverage.EXEMPT,
                "as ClientboundTestInstanceBlockStatus, and serverbound besides — the server re-derives "
                        + "everything it acts on");
        rule("ClientboundTagQueryPacket", Coverage.EXEMPT,
                "an operator's /data get result. It is a CompoundTag, so the gate stops at it correctly, "
                        + "but the client never decodes the contents — ClientPacketListener hands the tag "
                        + "to NbtUtils.toPrettyComponent and prints it. A VibeMod id in there is read by a "
                        + "human, not by a registry");

        // ------------------------------------------------------- signatures: byte[] with nothing in it
        //
        // The gate treats a bare byte[] as an encoding boundary, which is the
        // right default — it is where a chunk hides a whole palette. These four
        // are the cases where it is a false positive, and they are declared
        // rather than carved out of the rule: narrowing the rule to spare four
        // rows would also spare whatever the next version puts in a byte[].

        rule("ClientboundDeleteChatPacket", Coverage.EXEMPT,
                "an Ed25519 message signature. Fixed-length bytes over a fixed preimage; there is no id "
                        + "in it and nowhere to put one");
        rule("ServerboundChatPacket", Coverage.EXEMPT, "as ClientboundDeleteChatPacket");
        rule("ServerboundChatCommandSignedPacket", Coverage.EXEMPT, "as ClientboundDeleteChatPacket");
        rule("ServerboundChatSessionUpdatePacket", Coverage.EXEMPT,
                "the player's profile public key and its Mojang signature — both opaque bytes by design");

        // ------------------------------------------------------- serverbound: the un-projection

        rule("ServerboundSetCreativeModeSlotPacket", Coverage.PROJECTED,
                "THE reason the seam is duplex. A creative client echoes the projected stack straight back; "
                        + "without un-projection the server writes a piece of paper into the slot and the "
                        + "player's item is destroyed on contact");
        rule("ServerboundContainerClickPacket", Coverage.PROJECTED,
                "changedSlots and carriedItem are HashedStacks hashed over what the CLIENT holds, which is the "
                        + "projected stack. Each is wrapped so matches(trueStack, gen) is asked about "
                        + "project(trueStack) instead — the server's desync check then agrees with a client "
                        + "that was told the truth we chose to tell it");
    }

    /** Every declared row, in the order it was written. */
    public static List<Rule> rules() {
        return new ArrayList<>(RULES.values());
    }

    /** The row for one packet's simple class name, or null when none is declared. */
    public static Rule ruleFor(String simpleName) {
        return RULES.get(simpleName);
    }

    /** How many rows carry one coverage — the one line the projection logs at install. */
    public static int count(Coverage coverage) {
        int n = 0;
        for (Rule rule : RULES.values()) {
            if (rule.coverage() == coverage) {
                n++;
            }
        }
        return n;
    }

    /**
     * {@code "projected=21 withheld=1 lossless=3 exempt=24 uncovered=11"} — said
     * out loud at install, because a projection that bounds its coverage has to
     * say so.
     */
    public static String describeCoverage() {
        return "projected=" + count(Coverage.PROJECTED)
                + " withheld=" + count(Coverage.WITHHELD)
                + " lossless=" + count(Coverage.LOSSLESS)
                + " exempt=" + count(Coverage.EXEMPT)
                + " uncovered=" + count(Coverage.UNCOVERED);
    }
}
