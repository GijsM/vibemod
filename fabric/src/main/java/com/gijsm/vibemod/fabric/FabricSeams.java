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
    /** The client-only shims, split out so a dedicated server never resolves their descriptors. */
    private static final String CLIENT_SHIMS = "com/gijsm/vibemod/fabric/shim/ClientShims";

    private static final String KEY_HELPER =
            "net/fabricmc/fabric/api/client/keymapping/v1/KeyMappingHelper";
    private static final String HUD_REGISTRY =
            "net/fabricmc/fabric/api/client/rendering/v1/hud/HudElementRegistry";
    private static final String KEY_MAPPING = "Lnet/minecraft/client/KeyMapping;";
    private static final String IDENTIFIER = "Lnet/minecraft/resources/Identifier;";
    private static final String HUD_ELEMENT =
            "Lnet/fabricmc/fabric/api/client/rendering/v1/hud/HudElement;";

    private FabricSeams() {
    }

    /**
     * The table: both {@code Event.register} overloads (Phase 0), the keybind
     * helper and every {@code HudElementRegistry} method that adds an element
     * (Phase 1 §C, §D).
     *
     * <p>Every descriptor below was read off the jars with {@code javap -s}
     * rather than remembered, and two of them are worth stating because the
     * brief guessed differently and the jar won:
     *
     * <pre>
     * // fabric-key-mapping-api-v1 2.0.5 — there is no KeyBindingHelper any more
     * public final class net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper {
     *   public static KeyMapping registerKeyMapping(KeyMapping);   // (Lnet/minecraft/client/KeyMapping;)Lnet/minecraft/client/KeyMapping;
     * }
     * // fabric-rendering-v1 25.3.2
     * public interface net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry {
     *   public static void addFirst(Identifier, HudElement);
     *   public static void addLast(Identifier, HudElement);
     *   public static void attachElementBefore(Identifier, Identifier, HudElement);
     *   public static void attachElementAfter(Identifier, Identifier, HudElement);
     * }
     * </pre>
     *
     * <p>All six are {@code invokestatic} sites with no receiver, so they use
     * {@link Seam#staticCall} and the shim descriptor is the original one — a
     * prepended receiver would be an argument that is not on the stack.
     *
     * <p>{@code removeElement} and {@code replaceElement} are deliberately NOT
     * on the table. They act on other mods' HUD elements, permanently and
     * globally, which is the same objection that keeps
     * {@code Event.addPhaseOrdering} on the deny list; they are refused by the
     * policy's package rules only in the sense that nothing rewrites them, so
     * Phase 2 should deny them explicitly when registries land.
     */
    public static List<Seam> table() {
        return List.of(
                Seam.prependingReceiver(EVENT, "register", "(Ljava/lang/Object;)V",
                        SHIMS, "eventRegister"),
                Seam.prependingReceiver(EVENT, "register",
                        "(Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)V",
                        SHIMS, "eventRegister"),
                Seam.staticCall(KEY_HELPER, "registerKeyMapping",
                        "(" + KEY_MAPPING + ")" + KEY_MAPPING,
                        CLIENT_SHIMS, "registerKeyMapping"),
                Seam.staticCall(HUD_REGISTRY, "addFirst",
                        "(" + IDENTIFIER + HUD_ELEMENT + ")V", CLIENT_SHIMS, "hudAdd"),
                Seam.staticCall(HUD_REGISTRY, "addLast",
                        "(" + IDENTIFIER + HUD_ELEMENT + ")V", CLIENT_SHIMS, "hudAdd"),
                Seam.staticCall(HUD_REGISTRY, "attachElementBefore",
                        "(" + IDENTIFIER + IDENTIFIER + HUD_ELEMENT + ")V",
                        CLIENT_SHIMS, "hudAttach"),
                Seam.staticCall(HUD_REGISTRY, "attachElementAfter",
                        "(" + IDENTIFIER + IDENTIFIER + HUD_ELEMENT + ")V",
                        CLIENT_SHIMS, "hudAttach"));
    }

    /** The surgeon this host installs on its {@code InMemoryCompiler}. */
    public static BytecodeSurgeon surgeon() {
        return new BytecodeSurgeon(SurgeonPolicy.defaults(), table());
    }
}
