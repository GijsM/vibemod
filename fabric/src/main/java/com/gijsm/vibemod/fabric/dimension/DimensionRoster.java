package com.gijsm.vibemod.fabric.dimension;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Which {@code minecraft:dimension_type} entries each connected client actually
 * holds — the one fact that decides whether a teleport is a doorway or a
 * disconnect (V4 Phase 6).
 *
 * <h2>Why this class has to exist</h2>
 *
 * <p>{@code LEVEL_STEM} is not synced, which is what lets a level be created at
 * runtime at all. {@code DIMENSION_TYPE} <em>is</em> — it is in
 * {@code RegistryDataLoader.SYNCHRONIZED_REGISTRIES}, verified — and it is synced
 * <b>only during configuration</b>. So the two halves of a dimension travel on
 * completely different schedules, and this class tracks the half that lags.
 *
 * <p>The failure it prevents is not subtle. Every dimension change puts a
 * {@code CommonPlayerSpawnInfo} on the wire, whose first component is
 * {@code Holder<DimensionType>}, encoded by
 *
 * <pre>
 * DimensionType.STREAM_CODEC = ByteBufCodecs.holderRegistry(Registries.DIMENSION_TYPE)
 * </pre>
 *
 * <p>— a bare registry index, with no inline-direct-value branch. (Contrast
 * {@code SoundEvent}, where vanilla's holder codec <em>does</em> allow an inline
 * value, which is why §4.11 can give a vanilla client a brand-new sound and
 * cannot give it a brand-new dimension type.) A client whose registry is one
 * entry short cannot decode that index. It does not render a fallback and it does
 * not log a warning; it fails the decode and the connection ends. So sending a
 * player to a dimension whose type they were never sent is a kick, dressed as a
 * teleport.
 *
 * <h2>How the snapshot is taken, and why at that moment</h2>
 *
 * <p>{@code SynchronizeRegistriesTask} is constructed from the server's own
 * {@code registries()} at the head of {@code startConfiguration}, and
 * {@code RegistrySynchronization.packRegistry} then sends
 * {@code registry.listElements()} in full for every entry whose
 * {@code RegistrationInfo.knownPackInfo()} is not one of the client's known packs
 * — which is exactly the {@code RegistrationInfo.BUILT_IN} Phase 5's
 * {@code DynamicSeam} registers under. <b>That is the load-bearing verified
 * fact:</b> a dimension type VibeMod added at runtime is sent to a joining
 * client in full, data and all, including a pure-vanilla client, with no pack and
 * no client mod. It just cannot be sent to one that is already in play.
 *
 * <p>So the snapshot is taken at {@code BEFORE_CONFIGURE}, which Fabric fires
 * before any configuration task is queued. Everything in that snapshot is
 * guaranteed to have been sent; something registered after it may or may not have
 * been. Erring toward the smaller set is the only direction that cannot cost
 * somebody their connection.
 *
 * <p>A player with no snapshot at all — one who was somehow in play before this
 * roster existed — is judged against the <b>boot floor</b>: the dimension types
 * present when the server started, which is everything vanilla and everything the
 * world's datapacks declare. Nothing VibeMod added at runtime is in it, so an
 * unknown player is refused the new content and allowed everything else.
 *
 * <p>A {@code Holder} with no registry key — a direct holder, built in code
 * rather than looked up — is refused outright, because {@code holderRegistry}
 * has no id to write for it either. The refusal and the wire agree.
 */
public final class DimensionRoster {

    private static final Logger LOG = Logger.getLogger("VibeMod.Dimension");

    /** Dimension-type ids present when the server started. The floor for unknown players. */
    private volatile Set<Identifier> bootFloor = Set.of();
    /** Per player, the ids present when their configuration began. */
    private final Map<UUID, Set<Identifier>> sent = new ConcurrentHashMap<>();

    /** Called once at {@code SERVER_STARTED}. */
    public void noteServerStarted(MinecraftServer server) {
        bootFloor = idsOf(server);
        LOG.info("Dimension-type floor at boot: " + bootFloor.size() + " types. A player who joined"
                + " before VibeMod could observe their configuration is judged against this set.");
    }

    /**
     * Called from {@code BEFORE_CONFIGURE}, for a first join and for every
     * reconfiguration bounce.
     *
     * <p>A bounce is the whole point: Phase 5's {@code ReconfigureBouncer} exists
     * to put new dynamic-registry content in front of players who are already
     * connected, and this is where a bounced player's entry is replaced with the
     * larger set they are about to receive.
     */
    public void noteConfiguring(MinecraftServer server, UUID playerId, String playerName) {
        Set<Identifier> ids = idsOf(server);
        Set<Identifier> previous = sent.put(playerId, ids);
        if (previous != null && previous.size() != ids.size()) {
            LOG.info("Reconfiguring " + playerName + " with " + (ids.size() - previous.size())
                    + " dimension type(s) they did not have before");
        }
    }

    /** Called from {@code ServerPlayConnectionEvents.DISCONNECT}. */
    public void forget(UUID playerId) {
        sent.remove(playerId);
    }

    /** Called when a server stops: every snapshot belongs to that world's registries. */
    public void clear() {
        sent.clear();
        bootFloor = Set.of();
    }

    /**
     * Whether this player's client can decode a reference to this dimension type.
     *
     * @param typeId the dimension type's registry id, or null for a direct holder
     * @return null when the player can see it, or the refusal text when they cannot
     */
    public String refusalFor(ServerPlayer player, Identifier typeId) {
        if (typeId == null) {
            return "its dimension type is a direct Holder with no registry key, and"
                    + " DimensionType.STREAM_CODEC is ByteBufCodecs.holderRegistry — it can only put a"
                    + " registry index on the wire, so there is nothing to send for it";
        }
        Set<Identifier> known = sent.get(player.getUUID());
        String source = "sent to them during configuration";
        if (known == null) {
            known = bootFloor;
            source = "present when the server started (VibeMod never observed their configuration)";
        }
        if (known.contains(typeId)) {
            return null;
        }
        return "their client has not been sent the dimension type " + typeId + ". The"
                + " dimension_type registry is synchronised only during configuration, and"
                + " CommonPlayerSpawnInfo encodes it as a bare registry index"
                + " (ByteBufCodecs.holderRegistry), so a client one entry short does not fall back —"
                + " it fails the decode and the connection ends. The " + known.size() + " types their"
                + " client holds are the ones " + source + "; this one reaches them at their next"
                + " join, or at the next reconfiguration bounce";
    }

    /** For the gates: {@code "dimTypeFloor=4 dimTypeTracked=2"}. */
    public String describeState() {
        return "dimTypeFloor=" + bootFloor.size() + " dimTypeTracked=" + sent.size();
    }

    private static Set<Identifier> idsOf(MinecraftServer server) {
        Registry<DimensionType> registry = server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        return Set.copyOf(registry.keySet());
    }
}
