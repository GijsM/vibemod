package com.gijsm.vibemod.platform.ui;

import net.kyori.adventure.text.Component;

/** One screen button. {@code tooltip} may be null. */
public record Button(Component label, Component tooltip, WidthHint width, UiAction action) {

    public static Button command(Component label, Component tooltip, String command) {
        return new Button(label, tooltip, WidthHint.ROW, new UiAction.RunCommand(command));
    }
}
