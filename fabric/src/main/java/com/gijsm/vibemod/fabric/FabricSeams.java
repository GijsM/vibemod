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

    // ---- V3 Phase 3: registries, items, entity types ----

    /** {@code net.minecraft.core.Registry}, whose five statics are the whole content surface. */
    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String L_REGISTRY = "Lnet/minecraft/core/Registry;";
    private static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String STRING = "Ljava/lang/String;";
    private static final String HOLDER_REF = "Lnet/minecraft/core/Holder$Reference;";

    private static final String ITEM_PROPERTIES = "net/minecraft/world/item/Item$Properties";
    private static final String L_ITEM_PROPERTIES = "Lnet/minecraft/world/item/Item$Properties;";

    /**
     * V4 Phase 1's one new seam owner. {@code javap -s} on the 26.2 jar:
     *
     * <pre>
     * public net.minecraft.world.level.block.state.BlockBehaviour$Properties setId(ResourceKey&lt;Block&gt;);
     *   descriptor: (Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;
     * </pre>
     */
    private static final String BLOCK_PROPERTIES =
            "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String L_BLOCK_PROPERTIES =
            "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";

    private static final String ENTITY_TYPE_BUILDER = "net/minecraft/world/entity/EntityType$Builder";
    private static final String L_ENTITY_TYPE = "Lnet/minecraft/world/entity/EntityType;";

    private static final String ATTRIBUTE_REGISTRY =
            "net/fabricmc/fabric/api/object/builder/v1/entity/FabricDefaultAttributeRegistry";
    private static final String L_ATTRIBUTE_SUPPLIER =
            "Lnet/minecraft/world/entity/ai/attributes/AttributeSupplier;";
    private static final String L_ATTRIBUTE_BUILDER =
            "Lnet/minecraft/world/entity/ai/attributes/AttributeSupplier$Builder;";

    private static final String RENDERER_REGISTRY =
            "net/fabricmc/fabric/api/client/rendering/v1/EntityRendererRegistry";
    /**
     * Vanilla's own {@code EntityRenderers.register}, which is
     * {@code private static} in the jar and made public for every mod by
     * fabric-transitive-access-wideners-v1:
     *
     * <pre>
     * transitive-accessible method net/minecraft/client/renderer/entity/EntityRenderers
     *     register (Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/client/renderer/entity/EntityRendererProvider;)V
     * </pre>
     *
     * <p>That widener is why {@code EntityRendererRegistry} carries
     * {@code @Deprecated} in fabric-rendering-v1 25.3.2 — the fabric wrapper has
     * been superseded by the vanilla method. Both are seamed: the deprecated one
     * because a model trained on older tutorials will write it, the vanilla one
     * because it is what current code writes.
     */
    private static final String VANILLA_RENDERERS = "net/minecraft/client/renderer/entity/EntityRenderers";
    private static final String L_RENDERER_PROVIDER =
            "Lnet/minecraft/client/renderer/entity/EntityRendererProvider;";

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
                        CLIENT_SHIMS, "hudAttach"),

                // ---- V3 Phase 3 §A: all five Registry statics, javap'd:
                //   register(Registry<? super T>, String, T)          -> Object
                //   register(Registry<V>, Identifier, T)              -> Object
                //   register(Registry<V>, ResourceKey<V>, T)          -> Object
                //   registerForHolder(Registry<R>, ResourceKey<R>, T) -> Holder$Reference
                //   registerForHolder(Registry<R>, Identifier, T)     -> Holder$Reference
                // The two ResourceKey overloads share a parameter list and are
                // told apart by their RETURN type, which is why the seam matches
                // on the whole descriptor rather than on name and arity.
                Seam.staticCall(REGISTRY, "register",
                        "(" + L_REGISTRY + STRING + OBJECT + ")" + OBJECT,
                        SHIMS, "registryRegister"),
                Seam.staticCall(REGISTRY, "register",
                        "(" + L_REGISTRY + IDENTIFIER + OBJECT + ")" + OBJECT,
                        SHIMS, "registryRegister"),
                Seam.staticCall(REGISTRY, "register",
                        "(" + L_REGISTRY + RESOURCE_KEY + OBJECT + ")" + OBJECT,
                        SHIMS, "registryRegister"),
                Seam.staticCall(REGISTRY, "registerForHolder",
                        "(" + L_REGISTRY + RESOURCE_KEY + OBJECT + ")" + HOLDER_REF,
                        SHIMS, "registryRegisterForHolder"),
                Seam.staticCall(REGISTRY, "registerForHolder",
                        "(" + L_REGISTRY + IDENTIFIER + OBJECT + ")" + HOLDER_REF,
                        SHIMS, "registryRegisterForHolder"),

                // The id has to be on the Properties BEFORE the Item exists —
                // Item.<init> calls Properties.itemIdOrThrow() for both the
                // description id and the model id — so the namespace rewrite
                // cannot wait for Registry.register.
                Seam.prependingReceiver(ITEM_PROPERTIES, "setId",
                        "(" + RESOURCE_KEY + ")" + L_ITEM_PROPERTIES,
                        SHIMS, "itemId"),

                // V4 Phase 1: the same argument again, one class over.
                // BlockBehaviour.<init> bakes BOTH the descriptionId and the
                // loot-table `drops` key out of the id before Registry.register
                // is reached, so a namespace rewritten at registration time
                // would leave the lang key and the loot path pointing at a
                // namespace nothing writes to.
                //
                // It cannot collide with the Item$Properties row above even
                // though the parameter list is identical: Seam.matches compares
                // owner AND name AND the FULL descriptor, and both the owner and
                // the return type differ. SurgeonSelfTest asserts exactly that.
                Seam.prependingReceiver(BLOCK_PROPERTIES, "setId",
                        "(" + RESOURCE_KEY + ")" + L_BLOCK_PROPERTIES,
                        SHIMS, "blockId"),
                Seam.prependingReceiver(ENTITY_TYPE_BUILDER, "build",
                        "(" + RESOURCE_KEY + ")" + L_ENTITY_TYPE,
                        SHIMS, "entityTypeBuild"),

                // §B. Not because it would fail otherwise — it is a plain map
                // put — but because nothing else could take it away again.
                Seam.staticCall(ATTRIBUTE_REGISTRY, "register",
                        "(" + L_ENTITY_TYPE + L_ATTRIBUTE_BUILDER + ")V",
                        SHIMS, "defaultAttributes"),
                Seam.staticCall(ATTRIBUTE_REGISTRY, "register",
                        "(" + L_ENTITY_TYPE + L_ATTRIBUTE_SUPPLIER + ")V",
                        SHIMS, "defaultAttributes"),
                Seam.staticCall(RENDERER_REGISTRY, "register",
                        "(" + L_ENTITY_TYPE + L_RENDERER_PROVIDER + ")V",
                        CLIENT_SHIMS, "entityRenderer"),
                Seam.staticCall(VANILLA_RENDERERS, "register",
                        "(" + L_ENTITY_TYPE + L_RENDERER_PROVIDER + ")V",
                        CLIENT_SHIMS, "entityRenderer"));
    }

    /** The surgeon this host installs on its {@code InMemoryCompiler}. */
    public static BytecodeSurgeon surgeon() {
        return new BytecodeSurgeon(SurgeonPolicy.defaults(), table());
    }
}
