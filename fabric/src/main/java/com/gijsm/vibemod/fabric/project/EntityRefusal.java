package com.gijsm.vibemod.fabric.project;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/**
 * Entities are refused for Lane B, not projected — and this is the sentence
 * that says so (V4 Phase 4).
 *
 * <h2>Why refusing is the right answer and not a shortcut</h2>
 *
 * <p>Projecting an item means swapping its identity and keeping its components.
 * Projecting an <em>entity</em> means swapping its {@code EntityType} for a
 * vanilla stand-in — and the moment you do, its {@code SynchedEntityData} stops
 * meaning anything. A {@code DataValue} is {@code (id, serializer, value)} and
 * the accessor ids are allocated <b>per type</b>, walking up the class
 * hierarchy: id 8 on our entity and id 8 on the stand-in are different fields,
 * often of different serializers. So a projected entity needs its whole synched
 * data re-synthesised into the stand-in's layout, plus its own equipment,
 * passengers, attributes and animations re-derived. That is not a table of
 * packet shapes; that is a virtual-entity engine, which is precisely the
 * subproject Polymer had to write.
 *
 * <p>So a VibeMod entity is simply <b>not spawned</b> for a Lane B client:
 * {@code PacketProjection} drops its {@code ClientboundAddEntityPacket}, and the
 * client never learns the entity id, so every later packet about it is about an
 * entity the client does not track — which vanilla already ignores.
 *
 * <p>The player sees nothing where a VibeMod mob is standing. That is a
 * disappointing outcome and it is the honest one; the alternative on offer was a
 * client that decodes a raw entity-type id past the end of its own registry and
 * is disconnected.
 *
 * <p>{@link #refusalFor} is the sentence a mod is told at load. It is public and
 * unused inside this package on purpose — the registration seam is what should
 * say it, at the moment the entity type is registered, and this class is where
 * the wording lives so there is one of it.
 */
public final class EntityRefusal {

    private EntityRefusal() {
    }

    /**
     * What to tell a mod that registers an entity type, when a vanilla client
     * may connect.
     *
     * <p>Phrased as something a model can act on: it names the mechanism, the
     * consequence, and the two ways out.
     */
    public static String refusalFor(Identifier entityType) {
        return entityType + " will not be visible to players who are not running VibeMod. Lane B "
                + "projects items onto vanilla stand-ins, but it refuses to project entities: the "
                + "stand-in would need its SynchedEntityData re-synthesised, because serializer and "
                + "accessor ids are allocated per entity type and do not line up between two types. "
                + "The entity exists and behaves normally on the server; it is simply not spawned "
                + "for those clients. Ask them to install VibeMod, or build this mod around items "
                + "and blocks instead";
    }

    /** Every entity type VibeMod has registered, for the one line said at install. */
    public static List<Identifier> vibeModEntityTypes() {
        List<Identifier> ours = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id != null && RegistryHiding.isVibeModNamespace(id.getNamespace())) {
                ours.add(id);
            }
        }
        return ours;
    }
}
