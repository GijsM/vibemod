package com.gijsm.vibemod.fabric;

import java.util.List;

import com.gijsm.vibemod.loader.surgeon.BytecodeSurgeon;
import com.gijsm.vibemod.loader.surgeon.Seam;
import com.gijsm.vibemod.loader.surgeon.SurgeonPolicy;

/**
 * The Fabric host's half of the bytecode contract (V3 Phase 0 §A): which call
 * sites the surgeon redirects, and where to.
 *
 * <p>Kept here rather than inside {@code loader-common} because the surgeon
 * must know no loader — that is the constraint that lets one class serve Fabric
 * and NeoForge — and because these three strings are exactly the kind of thing
 * that must be verifiable against a real jar. Every one of them was read off
 * {@code javap} rather than remembered:
 *
 * <pre>
 * public abstract class net.fabricmc.fabric.api.event.Event&lt;T&gt; {
 *   public abstract void register(T);                                    // (Ljava/lang/Object;)V
 *   public void register(net.minecraft.resources.Identifier, T);         // (Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)V
 * }
 * </pre>
 *
 * <p>{@code register(T)} erases to {@code (Ljava/lang/Object;)V} and is invoked
 * with {@code invokevirtual} on the static type of the receiver expression —
 * which is always {@code Event}, because every Fabric event is declared as an
 * {@code Event<Callback>} field. That is what makes a single seam entry cover
 * every event in the API.
 */
public final class FabricSeams {

    /** Internal name of {@code net.fabricmc.fabric.api.event.Event}. */
    private static final String EVENT = "net/fabricmc/fabric/api/event/Event";
    /** Internal name of the shim class rewritten call sites point at. */
    private static final String SHIMS = "com/gijsm/vibemod/fabric/shim/Shims";

    private FabricSeams() {
    }

    /**
     * Phase 0's whole table: both {@code Event.register} overloads, both
     * landing on {@code Shims.eventRegister}.
     */
    public static List<Seam> table() {
        return List.of(
                Seam.prependingReceiver(EVENT, "register", "(Ljava/lang/Object;)V",
                        SHIMS, "eventRegister"),
                Seam.prependingReceiver(EVENT, "register",
                        "(Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)V",
                        SHIMS, "eventRegister"));
    }

    /** The surgeon this host installs on its {@code InMemoryCompiler}. */
    public static BytecodeSurgeon surgeon() {
        return new BytecodeSurgeon(SurgeonPolicy.defaults(), table());
    }
}
