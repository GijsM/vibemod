package com.gijsm.vibemod.fabric.shim;

import java.util.logging.Level;
import java.util.logging.Logger;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.gijsm.vibemod.fabric.mixin.CreativeModeTabsAccessor;

/**
 * Where a runtime-registered item shows up in the creative menu (V3 Phase 3 §A).
 *
 * <p>One permanent listener on one vanilla tab, installed at host init and
 * never removed — the same rule §8.1 states for every other loader
 * subscription, and for the same reason: {@code Event.register} cannot be
 * undone, so a per-server or per-mod registration would leave a dead listener
 * behind for every world ever loaded. What changes is the <em>answer</em>: the
 * listener asks {@link RegistrySeam#liveItems()} each time it fires, so a
 * disabled mod's item stops being offered without anything being unsubscribed.
 *
 * <p><b>The API is not the one the brief named.</b> There is no
 * {@code ItemGroupEvents} and no {@code fabric-item-group-api-v1} in this era;
 * {@code javap} over fabric-creative-tab-api-v1 5.0.14 says the class is
 * {@code net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents} and the
 * method is {@code modifyOutputEvent(ResourceKey<CreativeModeTab>)}, whose
 * callback takes a {@code FabricCreativeModeTabOutput}.
 *
 * <p><b>Which tab.</b> {@code minecraft:ingredients}, resolved by
 * {@code ResourceKey} rather than by field — {@code CreativeModeTabs}' own tab
 * keys are all {@code private static final}. It is the tab vanilla itself uses
 * for "a thing that exists and does not fit elsewhere", and one predictable
 * home beats guessing a category per generated item. {@code accept(ItemLike)}
 * defaults to {@code PARENT_AND_SEARCH_TABS}, so the search tab gets it too and
 * a player can type the item's name.
 *
 * <p>A separate class from {@link RegistrySeam} so that a dedicated server —
 * where registration is refused and this is never reached — has no reason to
 * resolve {@code CreativeModeTabEvents} at all.
 */
public final class CreativeTabs {

    private static final Logger LOG = Logger.getLogger("VibeMod.Registry");

    /** The tab every VibeMod item joins. */
    public static final ResourceKey<CreativeModeTab> TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("ingredients"));

    private CreativeTabs() {
    }

    /**
     * Installs the one permanent listener. Called from {@code onInitialize()},
     * once per process, exactly like the event fanout.
     */
    public static void install(RegistrySeam seam) {
        CreativeModeTabEvents.modifyOutputEvent(TAB).register(output -> {
            for (Item item : seam.liveItems()) {
                try {
                    output.accept(new ItemStack(item));
                } catch (Throwable t) {
                    // An item whose components are not bound yet would throw
                    // here and take the whole tab build down with it, which is a
                    // black creative menu for a bug in one generated mod.
                    LOG.log(Level.WARNING, "Skipping a VibeMod item in the creative tab", t);
                }
            }
        });
        LOG.info("Creative tab " + TAB.identifier() + " will carry VibeMod's runtime items");
    }

    /**
     * Drops vanilla's cached parameters, so the next {@link #rebuild} — or the
     * next time a player opens the creative menu — actually rebuilds. See
     * {@link CreativeModeTabsAccessor} for why nothing else would.
     */
    public static void invalidate() {
        CreativeModeTabsAccessor.setCachedParameters(null);
    }

    /**
     * Invalidates and rebuilds now.
     *
     * @return whether vanilla rebuilt (false when there is no server to take
     *         the feature flags and registries from)
     */
    public static boolean rebuild(MinecraftServer server) {
        if (server == null) {
            return false;
        }
        invalidate();
        return CreativeModeTabs.tryRebuildTabContents(server.getWorldData().enabledFeatures(),
                true, server.registryAccess());
    }
}
