package com.gijsm.vibemod.fabric.mixin;

import java.util.Map;

import it.unimi.dsi.fastutil.objects.ObjectList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.component.DataComponentLookup;

/**
 * The unfreeze path (V3 Phase 3 §A), and the three fields it needs.
 *
 * <p>Every one of these was read off the 26.2 jar with {@code javap}, and each
 * exists for a reason the disassembly makes unavoidable:
 *
 * <pre>
 * public class net.minecraft.core.MappedRegistry&lt;T&gt; implements WritableRegistry&lt;T&gt; {
 *   private boolean frozen;
 *   private Map&lt;T, Holder$Reference&lt;T&gt;&gt; unregisteredIntrusiveHolders;
 *   private DataComponentLookup&lt;T&gt; componentLookup;
 *   public Holder$Reference&lt;T&gt; register(ResourceKey&lt;T&gt;, T, RegistrationInfo);   // the ONLY register
 *   public Registry&lt;T&gt; freeze();
 *   private void refreshTagsInHolders();
 * }
 * </pre>
 *
 * <p><b>{@code frozen}</b> is the obvious one: {@code register} calls
 * {@code validateWrite}, which throws once the registry is frozen.
 *
 * <p><b>{@code unregisteredIntrusiveHolders} is the non-obvious one, and it is
 * why unfreezing at the {@code Registry.register} call site could never have
 * worked.</b> {@code BuiltInRegistries.ITEM}, {@code .BLOCK} and
 * {@code .ENTITY_TYPE} are all built by
 * {@code registerDefaultedWithIntrusiveHolders}, and {@code Item.<init>} calls
 * {@code BuiltInRegistries.ITEM.createIntrusiveHolder(this)} — so the
 * <em>constructor</em> writes to the registry, long before the
 * {@code Registry.register(…, new Item(…))} call it is an argument to. And
 * {@code freeze()} sets this field to null, so after boot
 * {@code createIntrusiveHolder} throws "This registry can't create intrusive
 * holders" before anything of ours is reached. Restoring the map is what makes
 * constructing an {@code Item} at runtime legal at all.
 *
 * <p><b>{@code refreshTagsInHolders}</b> and <b>{@code componentLookup}</b> are
 * the two things {@code freeze()} does that a newly registered holder needs and
 * that {@code register()} does not do: a {@code Holder.Reference} with no tags
 * bound throws "Tags not bound" from {@code is(TagKey)}, and the component
 * lookup caches per component type. Refreezing by calling {@code freeze()}
 * again is NOT an option — it would rerun the "Tags already present before
 * freezing" check against an {@code allTags} the first freeze already bound and
 * throw — so the refreeze is this field plus these two repairs, done
 * explicitly.
 *
 * <p>Common, not client-only: registries are the server's, and the client gate
 * runs an integrated server that shares them.
 */
@Mixin(MappedRegistry.class)
public interface MappedRegistryAccessor {

    @Accessor("frozen")
    boolean isFrozen();

    @Accessor("frozen")
    void setFrozen(boolean frozen);

    @Accessor("unregisteredIntrusiveHolders")
    Map<Object, Holder.Reference<Object>> getUnregisteredIntrusiveHolders();

    @Accessor("unregisteredIntrusiveHolders")
    void setUnregisteredIntrusiveHolders(Map<Object, Holder.Reference<Object>> holders);

    @Accessor("componentLookup")
    void setComponentLookup(DataComponentLookup<Object> lookup);

    /** The live id list {@code freeze()} builds the component lookup over. */
    @Accessor("byId")
    ObjectList<Holder.Reference<Object>> getById();

    @Invoker("refreshTagsInHolders")
    void invokeRefreshTagsInHolders();
}
