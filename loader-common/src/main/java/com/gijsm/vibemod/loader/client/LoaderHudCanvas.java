package com.gijsm.vibemod.loader.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.gijsm.vibemod.api.client.HudCanvas;
import com.gijsm.vibemod.loader.LoaderItems;

/**
 * {@link HudCanvas} over the game's GUI surface.
 *
 * <p>Decision 9 (ARCHITECTURE-V2 §4.3) in practice, and this class is the
 * argument for it. The doc's original client design had generated mods draw
 * against a Mojang type directly; the surface that type presents has been
 * rewritten repeatedly, and on MC 26.x it is not even called what it was —
 * {@code GuiGraphics} is gone, replaced by {@code GuiGraphicsExtractor} in a
 * state-extraction rendering model, with {@code drawString} renamed to
 * {@code text} and {@code renderItem} to {@code item}. Every stored mod that had
 * named those would now be a compile error. Instead sixty lines of adapter
 * absorb the churn and the six methods generated code sees have not moved.
 *
 * <p>One instance is reused for every frame and every renderer: the extractor
 * is swapped in before each dispatch rather than allocated per HUD element, so
 * a HUD costs no garbage per frame.
 */
public final class LoaderHudCanvas implements HudCanvas {

    private GuiGraphicsExtractor graphics;

    /** Points this canvas at the frame's extractor. Render thread only. */
    void bind(GuiGraphicsExtractor graphics) {
        this.graphics = graphics;
    }

    /** Drops the reference after the frame, so a stale extractor cannot be drawn to. */
    void unbind() {
        this.graphics = null;
    }

    @Override
    public int width() {
        return graphics == null ? 0 : graphics.guiWidth();
    }

    @Override
    public int height() {
        return graphics == null ? 0 : graphics.guiHeight();
    }

    @Override
    public void text(String s, int x, int y, int argb) {
        text(s, x, y, argb, true);
    }

    @Override
    public void text(String s, int x, int y, int argb, boolean shadow) {
        if (graphics == null || s == null) {
            return;
        }
        graphics.text(Minecraft.getInstance().font, s, x, y, argb, shadow);
    }

    @Override
    public int textWidth(String s) {
        if (s == null) {
            return 0;
        }
        return Minecraft.getInstance().font.width(s);
    }

    @Override
    public void box(int x1, int y1, int x2, int y2, int argb) {
        if (graphics == null) {
            return;
        }
        // fill() takes a half-open rect with x1<x2; a mod that passes the corners
        // the other way round means the same rectangle, so normalize rather than
        // silently drawing nothing.
        graphics.fill(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2), argb);
    }

    @Override
    public void outline(int x1, int y1, int x2, int y2, int argb) {
        if (graphics == null) {
            return;
        }
        int left = Math.min(x1, x2);
        int top = Math.min(y1, y2);
        int right = Math.max(x1, x2);
        int bottom = Math.max(y1, y2);
        // outline(x, y, width, height, color) — position and size, not two corners.
        graphics.outline(left, top, right - left, bottom - top, argb);
    }

    @Override
    public void item(String itemId, int x, int y) {
        if (graphics == null) {
            return;
        }
        Item item = LoaderItems.itemOrNull(itemId);
        if (item == null) {
            // "Unknown ids draw nothing" is the documented contract: a HUD is not
            // the place to learn that a mod's item id was a typo.
            return;
        }
        graphics.item(new ItemStack(item), x, y);
    }
}
