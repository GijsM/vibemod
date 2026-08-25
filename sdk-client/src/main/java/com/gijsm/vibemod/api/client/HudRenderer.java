package com.gijsm.vibemod.api.client;

/**
 * Draws one HUD overlay. Called every frame on the RENDER thread while
 * in-game. Must be fast; a renderer that throws is disabled with its mod
 * (error-storm), never allowed to crash the render loop.
 */
@FunctionalInterface
public interface HudRenderer {

    /** @param tickDelta partial tick in [0,1) for smooth interpolation */
    void render(HudCanvas canvas, float tickDelta);
}
