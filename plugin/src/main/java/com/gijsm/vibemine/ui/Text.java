package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Shared word-wrap helper for GUI lore and the install card. Wraps at spaces,
 * hard-splitting any single token longer than the target width, and never
 * emits italic text (chest GUI item names/lore render italic by default
 * otherwise, which reads as a bug rather than a style).
 */
public final class Text {

    /** Default wrap width in characters, tuned for chest-GUI lore lines. */
    public static final int DEFAULT_WIDTH = 38;

    private Text() {
    }

    /** Wraps {@code s} to {@code width} chars/line and colors every resulting line {@code c}, un-italicized. */
    public static List<Component> wrap(String s, int width, NamedTextColor c) {
        List<Component> out = new ArrayList<>();
        for (String line : wrapLines(s, width)) {
            out.add(plain(line, c));
        }
        return out;
    }

    /** Convenience overload using {@link #DEFAULT_WIDTH}. */
    public static List<Component> wrap(String s, NamedTextColor c) {
        return wrap(s, DEFAULT_WIDTH, c);
    }

    /**
     * Plain-string word wrap, reused internally by {@link #wrap} and by other
     * ui classes (e.g. book pagination) that need lines without color/decoration.
     */
    static List<String> wrapLines(String s, int width) {
        List<String> lines = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return lines;
        }
        int w = Math.max(1, width);
        for (String paragraph : s.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ", -1)) {
                String token = word;
                while (token.length() > w) {
                    if (current.length() > 0) {
                        lines.add(current.toString());
                        current.setLength(0);
                    }
                    lines.add(token.substring(0, w));
                    token = token.substring(w);
                }
                if (current.length() == 0) {
                    current.append(token);
                } else if (current.length() + 1 + token.length() <= w) {
                    current.append(' ').append(token);
                } else {
                    lines.add(current.toString());
                    current.setLength(0);
                    current.append(token);
                }
            }
            lines.add(current.toString());
        }
        return lines;
    }

    private static Component plain(String text, NamedTextColor c) {
        return Component.text(text, c).decoration(TextDecoration.ITALIC, false);
    }
}
