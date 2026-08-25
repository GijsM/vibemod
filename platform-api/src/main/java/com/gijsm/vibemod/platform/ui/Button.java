package com.gijsm.vibemod.platform.ui;

import net.kyori.adventure.text.Component;

/** One screen button. {@code tooltip} may be null. */
public record Button(Component label, Component tooltip, WidthHint width, UiAction action) {

    /**
     * A navigation button. {@link WidthHint#BODY}, not {@code ROW}: renderers
     * read {@code ROW} as "this is a list row, pin it to the row width so the
     * column aligns", which is wrong for a grid button — those size to their
     * label. Use {@code new Button(..., WidthHint.ROW, ...)} for actual rows.
     */
    public static Button command(Component label, Component tooltip, String command) {
        return new Button(label, tooltip, WidthHint.BODY, new UiAction.RunCommand(command));
    }
}
