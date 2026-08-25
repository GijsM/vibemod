package com.gijsm.vibemod.platform.ui;

import java.util.List;

import net.kyori.adventure.text.Component;

/**
 * A declarative screen — the data half of "screens are data, renderers are
 * per-capability" (ARCHITECTURE-V2 §3). Built by core's screen builders,
 * rendered by a {@link UiRenderer} (native dialogs where the platform has
 * them, chat everywhere else).
 *
 * <ul>
 *   <li>{@code FORM} — inputs + one primary submit button (+ cancel as exit).
 *       Dialog: confirmation type. Chat: edit-in-place list.</li>
 *   <li>{@code MENU} — a button grid ({@code columns} wide in dialogs, a
 *       column of click lines in chat) + exit.</li>
 *   <li>{@code NOTICE} — body + a single dismiss (exit) button.</li>
 * </ul>
 *
 * {@code exit} may be null (renderer supplies a bare close).
 */
public record Screen(Component title, Kind kind, List<BodyBlock> body, List<Input> inputs,
                     List<Button> buttons, Button exit, int columns) {

    public enum Kind {
        FORM, MENU, NOTICE
    }
}
