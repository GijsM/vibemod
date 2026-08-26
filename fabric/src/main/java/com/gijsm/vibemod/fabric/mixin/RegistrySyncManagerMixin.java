package com.gijsm.vibemod.fabric.mixin;

import java.util.Map;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import com.gijsm.vibemod.fabric.project.RegistryHiding;

/**
 * Step zero of Lane B, without which nothing else in the phase matters
 * (V4 Phase 4, finding 11a).
 *
 * <p>A vanilla client is kicked <b>during the configuration phase</b> the moment
 * any VibeMod registry entry exists, and the kick is not ours. Disassembled from
 * {@code fabric-registry-sync-v0} 7.1.0, {@code configureClient} reads:
 *
 * <pre>
 * 25: invokestatic  createAndPopulateRegistryMap:()Ljava/util/Map;
 * 34: aload_0 … invokestatic ServerConfigurationNetworking.canSend(handler, RegistrySyncPayload.ID)
 * 41: ifne          70                       // client CAN receive Fabric's sync payload
 * 44: aload_2 … invokestatic areAllRegistriesOptional:(Ljava/util/Map;)Z
 * 48: ifeq          52
 * 51: return                                 // all optional: let it in
 * 52: … getIncompatibleClientComponent(brand, map)
 * 66: invokevirtual ServerConfigurationPacketListenerImpl.disconnect:(…)V
 * 70: … new SyncConfigurationTask(handler, map) … addTask
 * </pre>
 *
 * <p>{@code createAndPopulateRegistryMap()} walks {@code BuiltInRegistries}
 * <em>live</em> and keeps everything {@code SYNCED} and {@code MODDED}, and
 * fabric's own {@code MappedRegistryMixin} marks a registry MODDED on any
 * non-{@code minecraft} namespace registration — so VibeMod's runtime entries
 * land in that map automatically and there is no per-entry opt-out. The vanilla
 * client cannot receive Fabric's payload, so unless <em>every</em> affected
 * registry is {@code OPTIONAL} it is disconnected, with a message naming neither
 * VibeMod nor the item.
 *
 * <p>This redirect returns a filtered copy — VibeMod-namespaced entries stripped,
 * and a registry left with nothing dropped entirely — for exactly the
 * connections that cannot receive our manifest. Lane A connections get the map
 * untouched, because Fabric's raw-id remap is the thing that makes Lane A work
 * and must not be interfered with.
 *
 * <p><b>The rejected alternative, recorded because it is the tempting one:</b>
 * marking {@code Registries.ITEM} globally {@code OPTIONAL} would take the same
 * branch at offset 48 and cost no mixin at all. It is per-<em>registry</em>, not
 * per-<em>entry</em>, so it would also wave through every <em>other</em> content
 * mod's entries to a vanilla client that then desyncs on them. Making somebody
 * else's failure mode worse to fix ours is not an acceptable trade.
 *
 * <p><b>This targets another mod's implementation class, so it is hardened
 * twice</b> (Decision 10, applied to a dependency we do not control):
 * {@code vibemod.mixins.json} declares {@code "required": true}, so a failed
 * apply is a startup crash rather than a runtime mystery; and
 * {@link RegistryHiding#selfCheck()} proves at boot that this handler is really
 * on {@code RegistrySyncManager} and that the filter really strips a synthetic
 * entry, falling back loudly to the dedicated-server refusal if it does not.
 * Without both, a fabric-api bump that renames the method degrades into every
 * vanilla client being kicked with a message that names nobody.
 */
@Mixin(RegistrySyncManager.class)
public class RegistrySyncManagerMixin {

    /**
     * Mixin appends the enclosing method's arguments to the handler, which is
     * the whole reason a {@code @Redirect} works here where an
     * {@code @Inject} on the return value would not: the map has to be filtered
     * <em>per connection</em>, and this is where the connection is in scope.
     *
     * <p>Static, because {@code configureClient} is.
     */
    @Redirect(
            method = "configureClient",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/fabricmc/fabric/impl/registry/sync/RegistrySyncManager;"
                            + "createAndPopulateRegistryMap()Ljava/util/Map;"))
    private static Map<Identifier, Object2IntMap<Identifier>> vibemod$hideContentFromVanillaClients(
            ServerConfigurationPacketListenerImpl handler, MinecraftServer server) {
        return RegistryHiding.filterForConnection(
                RegistrySyncManager.createAndPopulateRegistryMap(), handler);
    }
}
