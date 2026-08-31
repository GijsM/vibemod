package com.gijsm.vibemod.loader.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A HUD callback the host did not wrap (V3 Phase 1 §D).
 *
 * <p>The v2 client surface hands a mod a {@code HudCanvas} — a small, curated
 * drawing api. A <em>native</em> mod does not want one: it wrote a real Fabric
 * {@code HudElement} and expects the game's own arguments. So the host keeps a
 * second dispatch list whose entries take exactly what the loader's HUD hook
 * hands over, and the loader host adapts its own element type onto this
 * interface.
 *
 * <p>Both parameter types are {@code net.minecraft.client.*}, so this stays
 * inside {@code loader-common}'s client package (which only ever loads on a
 * physical client) and names no loader at all — which is what lets Fabric's
 * {@code HudElement} and NeoForge's GUI layer both land here later.
 */
@FunctionalInterface
public interface RawHudRenderer {

    /**
     * One frame, on the render thread, inside the host's single permanent HUD
     * element.
     *
     * @param graphics the extractor the loader's HUD hook was given
     * @param delta    the frame's delta tracker
     */
    void render(GuiGraphicsExtractor graphics, DeltaTracker delta) throws Exception;
}
