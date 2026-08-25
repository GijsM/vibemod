package com.gijsm.vibemod.platform.ui;

import java.util.List;

import net.kyori.adventure.text.Component;

/**
 * One form input. Keys are free-form here; renderers that need restricted
 * keys (vanilla dialogs allow {@code [a-z0-9_]} only) sanitize on the way in
 * and reverse-map on the way out — never the screen builders' concern.
 */
public sealed interface Input {

    String key();

    record Text(String key, Component label, String initial, int maxLength,
                boolean multiline) implements Input {
    }

    record Bool(String key, Component label, boolean initial) implements Input {
    }

    /** Single-choice. Exactly one option should be {@code selected}. */
    record Choice(String key, Component label, List<Option> options) implements Input {
        public record Option(String id, Component label, boolean selected) {
        }
    }

    /**
     * Numeric range (dialog: slider; chat: typed capture clamped to
     * [min, max] and rounded to {@code step}). {@code labelFormat} is the
     * dialog slider's format, e.g. {@code "%s: %ss"}.
     */
    record Number(String key, Component label, double min, double max, double step,
                  double initial, String labelFormat) implements Input {
    }
}
