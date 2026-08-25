package com.gijsm.vibemod.platform.ui;

import net.kyori.adventure.text.Component;

/**
 * One read-only block of a screen's body.
 *
 * <p>{@code Icon}'s {@code iconId} is an item id/material name (blank-safe;
 * renderers substitute a fallback item). The dialog renderer shows the item
 * (optionally glinting) beside {@code beside}; the chat renderer drops the
 * item and renders {@code beside} alone.
 */
public sealed interface BodyBlock {

    record Text(Component text, WidthHint width) implements BodyBlock {
    }

    record Icon(String iconId, boolean glint, Component beside) implements BodyBlock {
    }
}
