package com.gijsm.vibemod.util;

import java.util.Locale;

/**
 * The one place a human-written string becomes an identifier the game accepts.
 *
 * <p>Extracted in V3 Phase 2 §A from {@code LoaderDialogRenderer.Bindings
 * .sanitize}, which had exactly this rule for exactly this reason: a dialog
 * input key and a datapack namespace are both "arbitrary text that has to
 * survive being used as a key", and two implementations of that rule would
 * eventually disagree about a character. {@code LoaderDialogRenderer} calls
 * this now; the character class it produces ({@code [a-z0-9_]}) is a strict
 * subset of what a Minecraft namespace allows ({@code [a-z0-9_.-]}), so a
 * sanitized string is always a legal namespace segment.
 */
public final class Ids {

    private Ids() {
    }

    /**
     * {@code raw} lowercased with everything outside {@code [a-z0-9_]} replaced
     * by {@code _}; {@code fallback} when that leaves nothing (or {@code raw} is
     * null). The fallback is a parameter rather than a constant because the two
     * call sites want different words when it fires — {@code "input"} for a
     * dialog field, {@code "mod"} for a namespace.
     */
    public static String sanitize(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String out = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return out.isEmpty() ? fallback : out;
    }
}
