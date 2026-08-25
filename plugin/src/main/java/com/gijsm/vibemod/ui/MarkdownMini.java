package com.gijsm.vibemod.ui;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Dependency-free renderer for the tiny Markdown subset generated manuals are
 * written in (see the manual rules in {@code llm/PromptLibrary}):
 *
 * <ul>
 *   <li>line-based: {@code "## "} headings (bold, {@link Style#HEADING dark aqua}),
 *       {@code "### "} subheadings (bold white), {@code "- "}/{@code "* "}
 *       bullets (rendered as {@code " • "}), and blank lines as paragraph
 *       breaks;</li>
 *   <li>inline: {@code **bold**}, {@code *italic*} / {@code _italic_}, and
 *       {@code `code`} ({@link Style#ACTION aqua}).</li>
 * </ul>
 *
 * <p>Parsing is a single left-to-right pass with simple delimiter matching:
 * an unclosed or empty delimiter pair renders literally, so malformed input
 * never crashes and never drops text. Plain prose with no markdown at all
 * comes back exactly as written (no wrapping — word-wrap is the dialog
 * client's job). A lone {@code _} only opens italic at a word boundary, so
 * {@code snake_case} identifiers stay literal.
 */
public final class MarkdownMini {

    private MarkdownMini() {
    }

    /**
     * Renders {@code markdown} into one {@link Component} per block (heading,
     * bullet list, or paragraph — multi-line blocks carry their own newlines),
     * with unstyled text in {@code baseColor}. Blank/null input renders to an
     * empty list.
     */
    public static List<Component> render(String markdown, TextColor baseColor) {
        List<Component> blocks = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return blocks;
        }
        Component current = null; // the paragraph / bullet-list block under construction
        for (String raw : markdown.split("\n", -1)) {
            String line = raw.stripTrailing();
            if (line.isBlank()) {
                current = flush(blocks, current);
                continue;
            }
            if (line.startsWith("## ")) {
                current = flush(blocks, current);
                blocks.add(inline(line.substring(3).strip(), Style.HEADING)
                        .decoration(TextDecoration.BOLD, true));
                continue;
            }
            if (line.startsWith("### ")) {
                current = flush(blocks, current);
                blocks.add(inline(line.substring(4).strip(), NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, true));
                continue;
            }
            Component rendered = (line.startsWith("- ") || line.startsWith("* "))
                    ? Component.text(" • ", baseColor).append(inline(line.substring(2), baseColor))
                    : inline(line, baseColor);
            current = current == null ? rendered : current.append(Component.newline()).append(rendered);
        }
        flush(blocks, current);
        return blocks;
    }

    /** Adds the block under construction (if any) to {@code blocks}; returns null as the new "current". */
    private static Component flush(List<Component> blocks, Component current) {
        if (current != null) {
            blocks.add(current);
        }
        return null;
    }

    /**
     * One left-to-right inline pass over a single line: {@code **bold**},
     * {@code *italic*}, {@code _italic_} (word-boundary guarded), {@code `code`}.
     * Spans do not nest; anything unclosed falls through to literal text.
     */
    private static Component inline(String text, TextColor baseColor) {
        Component out = Component.empty().color(baseColor);
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '`') {
                int close = text.indexOf('`', i + 1);
                if (close > i + 1) {
                    out = emit(out, plain)
                            .append(Component.text(text.substring(i + 1, close), Style.ACTION));
                    i = close + 1;
                    continue;
                }
            } else if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int close = text.indexOf("**", i + 2);
                if (close > i + 2) {
                    out = emit(out, plain)
                            .append(Component.text(text.substring(i + 2, close))
                                    .decoration(TextDecoration.BOLD, true));
                    i = close + 2;
                    continue;
                }
            } else if (c == '*') {
                int close = text.indexOf('*', i + 1);
                // Reject space-padded spans so stray asterisks in prose stay literal.
                if (close > i + 1 && text.charAt(i + 1) != ' ' && text.charAt(close - 1) != ' ') {
                    out = emit(out, plain)
                            .append(Component.text(text.substring(i + 1, close))
                                    .decoration(TextDecoration.ITALIC, true));
                    i = close + 1;
                    continue;
                }
            } else if (c == '_' && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) {
                int close = underscoreCloser(text, i + 1);
                if (close > i + 1) {
                    out = emit(out, plain)
                            .append(Component.text(text.substring(i + 1, close))
                                    .decoration(TextDecoration.ITALIC, true));
                    i = close + 1;
                    continue;
                }
            }
            plain.append(c);
            i++;
        }
        return emit(out, plain);
    }

    /** First {@code _} at/after {@code from} that ends a word (not followed by a letter/digit), or -1. */
    private static int underscoreCloser(String text, int from) {
        for (int j = text.indexOf('_', from); j >= 0; j = text.indexOf('_', j + 1)) {
            if (j + 1 >= text.length() || !Character.isLetterOrDigit(text.charAt(j + 1))) {
                return j;
            }
        }
        return -1;
    }

    private static Component emit(Component out, StringBuilder plain) {
        if (plain.length() == 0) {
            return out;
        }
        Component appended = out.append(Component.text(plain.toString()));
        plain.setLength(0);
        return appended;
    }
}
