package com.gijsm.vibemod.loader;

import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * Item ids into game items.
 *
 * <p>Two callers with the same problem from opposite directions. The screen
 * model's icons are Bukkit-shaped {@code Material} names — {@code "CHICKEN"},
 * {@code "DIAMOND_SWORD"} — because the screen builders in {@code core} predate
 * the loaders and the 49 stored mods all declare their icons that way. The
 * client HUD's {@code HudCanvas.item(...)} takes namespaced ids like
 * {@code "minecraft:diamond"}, because that is what the loader prompt teaches.
 * Both are accepted: an id with no colon is lowercased and read as a vanilla
 * item, which happens to make the two spellings the same lookup.
 *
 * <p>Every lookup can fail — an icon from a mod generated on Paper 1.20.6 may
 * name an item that never existed here — and every caller treats failure as
 * "draw nothing", never as an error. An icon is never the point of a screen.
 */
public final class LoaderItems {

    private LoaderItems() {
    }

    /** The item for {@code "CHICKEN"} / {@code "minecraft:chicken"}, or null. */
    public static Item itemOrNull(String rawId) {
        Identifier id = LoaderText.idOrNull(rawId == null ? null : rawId.toLowerCase(Locale.ROOT));
        if (id == null) {
            return null;
        }
        Optional<Holder.Reference<Item>> found = BuiltInRegistries.ITEM.get(id);
        return found.map(Holder::value).orElse(null);
    }

    /**
     * A dialog {@code ItemBody}'s item, or null when the id resolves to nothing.
     *
     * <p>{@code glint} forces the enchantment shimmer on — the dialog renderer's
     * "this mod is running" cue, resurrected from the retired chest list. It is a
     * data component on 26.x, so unlike Paper (where it needed a 1.20.5+
     * capability gate) there is nothing to probe: MC 26.1+ is the floor here.
     */
    public static ItemStackTemplate template(String rawId, boolean glint) {
        Item item = itemOrNull(rawId);
        if (item == null) {
            return null;
        }
        if (!glint) {
            return new ItemStackTemplate(item);
        }
        return new ItemStackTemplate(item, DataComponentPatch.builder()
                .set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE)
                .build());
    }
}
