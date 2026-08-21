package com.gijsm.vibemine.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Unified chat styling for VibeMod: a two-tone {@code ⬡ vibe} prefix plus the
 * semantic color scheme every user-facing line in {@code ui/} and
 * {@code command/} routes through - green for success, gold for
 * degraded/warnings, red for errors, aqua for clickable actions, gray for
 * plain information.
 */
public final class Style {

    /** Success/OK. */
    public static final NamedTextColor OK = NamedTextColor.GREEN;
    /** Degraded state / warning. */
    public static final NamedTextColor WARN = NamedTextColor.GOLD;
    /** Error / failure. */
    public static final NamedTextColor ERROR = NamedTextColor.RED;
    /** Clickable buttons and actions. */
    public static final NamedTextColor ACTION = NamedTextColor.AQUA;
    /** Plain informational text. */
    public static final NamedTextColor INFO = NamedTextColor.GRAY;

    private Style() {
    }

    /** The {@code ⬡ vibe } prefix: an aqua hex glyph followed by a dark-aqua "vibe", never italic. */
    public static Component prefix() {
        return plain("⬡", NamedTextColor.AQUA).append(plain(" vibe ", NamedTextColor.DARK_AQUA));
    }

    /** Prefix + a green success message. */
    public static Component ok(String msg) {
        return line(OK, msg);
    }

    /** Prefix + a gold warning/degraded message. */
    public static Component warn(String msg) {
        return line(WARN, msg);
    }

    /** Prefix + a red error message. */
    public static Component err(String msg) {
        return line(ERROR, msg);
    }

    /** Prefix + a gray informational message. */
    public static Component info(String msg) {
        return line(INFO, msg);
    }

    /** A clickable {@code "[label]"} that runs {@code command} and shows {@code hover} on mouseover. */
    public static Component button(String label, String command, String hover, NamedTextColor color) {
        Component c = plain("[" + label + "]", color);
        if (hover != null && !hover.isEmpty()) {
            c = c.hoverEvent(HoverEvent.showText(plain(hover, INFO)));
        }
        if (command != null && !command.isEmpty()) {
            c = c.clickEvent(ClickEvent.runCommand(command));
        }
        return c;
    }

    /** A colored {@code ●}: green when enabled and healthy, gold when degraded, gray when off. */
    public static Component dot(boolean enabled, boolean degraded) {
        NamedTextColor color = degraded ? WARN : (enabled ? OK : NamedTextColor.GRAY);
        return plain("●", color);
    }

    /** {@code "$0.02"} normally, {@code "$0.0004"} (4 decimals) once the amount drops below a cent. */
    public static String fmtCost(double costUsd) {
        return costUsd < 0.01
                ? String.format(java.util.Locale.ROOT, "$%.4f", costUsd)
                : String.format(java.util.Locale.ROOT, "$%.2f", costUsd);
    }

    private static Component line(NamedTextColor color, String msg) {
        return prefix().append(plain(msg, color));
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
