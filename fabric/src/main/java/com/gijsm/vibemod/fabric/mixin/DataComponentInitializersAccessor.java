package com.gijsm.vibemod.fabric.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.core.component.DataComponentInitializers;

/**
 * The undo for a half-built item (V3 Phase 3 §A), and the smoke gate found the
 * bug that made it necessary.
 *
 * <p>{@code Item.<init>} does two things to global state, in this order
 * (disassembled):
 *
 * <pre>
 * BuiltInRegistries.ITEM.createIntrusiveHolder(this);                       // an unregistered holder
 * BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.add(props.itemIdOrThrow(), …);  // an entry keyed by an id
 * </pre>
 *
 * <p>The first is undone by registering the item — {@code MappedRegistry
 * .register} takes the holder out of {@code unregisteredIntrusiveHolders} — or,
 * when the registration is refused, by VibeMod discarding it at window close.
 * <b>The second is undone by nothing at all.</b> And it is the dangerous one:
 * {@code DataComponentInitializers.build(provider)} runs
 * {@code lookupOrThrow(Registries.ITEM).getOrThrow(key)} for every entry, and
 * {@code ReloadableServerResources} calls {@code build} on <em>every datapack
 * reload</em>. So one item that was constructed and never registered turns
 * every future reload in that session into
 * {@code IllegalStateException: Missing element ResourceKey[minecraft:item / …]}
 * — which is exactly what the dedicated-server smoke gate produced the first
 * time the refusal fired: the mod was refused correctly, and the world could
 * never reload a datapack again.
 *
 * <p>So the window snapshots this list's size on the way in and truncates back
 * to it on the way out whenever anything was left unregistered. All-or-nothing
 * per window: an item with empty components is visibly broken, while a world
 * that can no longer complete a reload is not obviously broken at all until
 * somebody types {@code /reload}.
 */
@Mixin(DataComponentInitializers.class)
public interface DataComponentInitializersAccessor {

    /** The live list — an {@code ArrayList}, so {@code subList(…).clear()} works on it. */
    @Accessor("initializers")
    List<Object> getInitializers();
}
