package com.gijsm.vibemine.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The single source of truth for VibeMod's palette — every user-facing line
 * in {@code ui/} and {@code command/} routes through these semantic colors,
 * one meaning per color:
 *
 * <ul>
 *   <li>{@link #OK} green — running / success / positive confirm buttons</li>
 *   <li>{@link #WARN} gold — degraded state and warnings ONLY</li>
 *   <li>{@link #ERROR} red — errors, destructive consequences, destructive
 *       confirm buttons</li>
 *   <li>{@link #ACTION} aqua — interactive accents and inline code</li>
 *   <li>{@link #INFO} gray — prose</li>
 *   <li>{@link #HEADING} dark aqua (+bold via {@link #heading}) — all
 *       section headings</li>
 *   <li>{@link #META} dark gray — metadata: versions, creator, cost lines,
 *       tooltip second lines</li>
 *   <li>{@link #HINT} yellow — "Try:" lines, tips, empty-state calls to
 *       action</li>
 * </ul>
 *
 * <p>Also hosts the two-tone {@code ⬡ vibe} chat prefix and the shared
 * builders: prefixed message lines, clickable chat buttons, the state dot,
 * and cost formatting.
 */
public final class Style {

    /** Success/OK: running / success / positive confirm buttons. */
    public static final NamedTextColor OK = NamedTextColor.GREEN;
    /** Degraded state / warning — nothing else renders gold. */
    public static final NamedTextColor WARN = NamedTextColor.GOLD;
    /** Error / failure / destructive consequences and confirm buttons. */
    public static final NamedTextColor ERROR = NamedTextColor.RED;
    /** Clickable buttons, interactive accents, inline code. */
    public static final NamedTextColor ACTION = NamedTextColor.AQUA;
    /** Plain informational prose. */
    public static final NamedTextColor INFO = NamedTextColor.GRAY;
    /** Section headings (render bold via {@link #heading}). */
    public static final NamedTextColor HEADING = NamedTextColor.DARK_AQUA;
    /** Metadata: versions, creator, cost lines, tooltips' second line. */
    public static final NamedTextColor META = NamedTextColor.DARK_GRAY;
    /** Tips, "Try:" lines, empty-state calls to action. */
    public static final NamedTextColor HINT = NamedTextColor.YELLOW;

    private Style() {
    }

    /** A bold {@link #HEADING} section heading, never italic. */
    public static Component heading(String text) {
        return plain(text, HEADING).decoration(TextDecoration.BOLD, true);
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

    /**
     * A colored {@code ●}: green when enabled and healthy, gold when degraded, gray when off.
     * The ONLY state-dot builder — no hand-rolled ternaries elsewhere.
     */
    public static Component dot(boolean enabled, boolean degraded) {
        return plain("●", stateColor(enabled, degraded));
    }

    /** The state color behind {@link #dot}: {@link #WARN} degraded, {@link #OK} running, gray off. */
    public static NamedTextColor stateColor(boolean enabled, boolean degraded) {
        return degraded ? WARN : (enabled ? OK : NamedTextColor.GRAY);
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
