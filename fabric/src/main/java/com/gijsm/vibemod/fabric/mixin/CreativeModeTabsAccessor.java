package com.gijsm.vibemod.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;

/**
 * The one field that stands between a runtime-registered item and the creative
 * menu (V3 Phase 3 §A).
 *
 * <p>{@code CreativeModeTabs.tryRebuildTabContents(FeatureFlagSet, boolean,
 * HolderLookup.Provider)} is public and does exactly what its name says — but
 * only after {@code CACHED_PARAMETERS.needsUpdate(...)} says something changed,
 * and that comparison is (disassembled):
 *
 * <pre>
 * enabledFeatures.equals(flags) &amp;&amp; hasPermissions == perms &amp;&amp; holders == provider
 * </pre>
 *
 * <p>— the holder provider compared by <em>reference identity</em>
 * ({@code if_acmpeq}). Registering an item changes none of those three: same
 * feature flags, same permissions, same {@code RegistryAccess} object. So the
 * rebuild is skipped, the tab keeps the list it baked before the item existed,
 * and nothing an operator can do from the game will change that.
 *
 * <p>Clearing the cache is therefore the whole mechanism, and it is a smaller
 * intervention than the alternatives (wrapping the provider in a decoy object
 * so identity differs, or reaching into {@code CreativeModeTab.displayItems}
 * directly): after this, vanilla's own rebuild runs vanilla's own way, and the
 * {@code CreativeModeInventoryScreen} that calls it on every open would pick
 * the change up even if VibeMod never asked.
 */
@Mixin(CreativeModeTabs.class)
public interface CreativeModeTabsAccessor {

    @Accessor("CACHED_PARAMETERS")
    static void setCachedParameters(CreativeModeTab.ItemDisplayParameters parameters) {
        throw new AssertionError("mixin did not apply");
    }
}
