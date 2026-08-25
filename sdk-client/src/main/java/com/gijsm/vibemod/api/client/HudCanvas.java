package com.gijsm.vibemod.api.client;

/**
 * Minimal drawing surface handed to {@link HudRenderer}s — implemented by
 * each host over the game's GUI graphics (~scaled GUI coordinates, origin
 * top-left). Deliberately a drawing API, not a widget API: enough for
 * coordinate HUDs, timers, counters and bars. Colors are ARGB ints,
 * e.g. {@code 0xFFFFFFFF} opaque white, {@code 0x80000000} translucent black.
 */
public interface HudCanvas {

    /** Scaled GUI width in pixels. */
    int width();

    /** Scaled GUI height in pixels. */
    int height();

    /** Draws text with shadow. */
    void text(String s, int x, int y, int argb);

    void text(String s, int x, int y, int argb, boolean shadow);

    /** Rendered width of {@code s} in pixels. */
    int textWidth(String s);

    /** Filled rectangle. */
    void box(int x1, int y1, int x2, int y2, int argb);

    /** 1px rectangle outline. */
    void outline(int x1, int y1, int x2, int y2, int argb);

    /** Draws a 16x16 item icon by id, e.g. {@code "minecraft:diamond"}. Unknown ids draw nothing. */
    void item(String itemId, int x, int y);
}
