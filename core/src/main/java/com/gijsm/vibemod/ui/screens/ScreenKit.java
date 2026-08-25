package com.gijsm.vibemod.ui.screens;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import com.gijsm.vibemod.platform.ui.BodyBlock;
import com.gijsm.vibemod.platform.ui.Button;
import com.gijsm.vibemod.platform.ui.UiAction;
import com.gijsm.vibemod.platform.ui.WidthHint;
import com.gijsm.vibemod.store.ModStore;
import com.gijsm.vibemod.ui.Style;

/**
 * The shared vocabulary every screen builder speaks: titles, the cross-navigation
 * button quartet, the exits, and the small formatters the screens print.
 *
 * <p>This is v1's {@code ui/DialogKit} with the dialog half removed
 * (ARCHITECTURE-V2 §1.1, §3): DialogKit's pixel widths, item bodies and click
 * plumbing were rendering concerns and stayed in {@code PaperDialogRenderer},
 * while its wording, its navigation grammar and its glyphs are screen *content*
 * and live here — so a chat-rendered menu says exactly what a dialog-rendered
 * one says.
 *
 * <p>Navigation stays command-routed ({@link UiAction.RunCommand}) wherever it
 * possibly can: a real {@code /vibe} subcommand re-checks permissions and
 * re-fetches data on every click, which is why the hub can be a page of buttons
 * with no state of its own. Callbacks are only used where a command round-trip
 * cannot express the step (form submits, and the two in-place drill-downs into a
 * source file or a version detail).
 */
public final class ScreenKit {

    /** Vanilla's "Force Unicode" font: uniform glyph sizing that makes code and tables column-align. */
    public static final Key UNIFORM_FONT = Key.key("minecraft", "uniform");

    private ScreenKit() {
    }

    // ---- titles ----

    /** The one title grammar: aqua {@code ⬡ } + {@code rest} in white (the screen cousin of {@link Style#prefix()}). */
    public static Component title(String rest) {
        return Component.text("⬡ ", NamedTextColor.AQUA)
                .append(Component.text(rest, NamedTextColor.WHITE));
    }

    // ---- buttons ----

    /**
     * A button that runs a {@code /vibe} subcommand, which re-checks permissions
     * and reopens the target screen with freshly assembled data.
     */
    public static Button nav(String label, String command, String tooltip) {
        return nav(Component.text(label), command, tooltip);
    }

    /**
     * {@link #nav(String, String, String)} with a pre-styled label component.
     *
     * <p>{@link WidthHint#BODY}, not {@code ROW}: {@code ROW} is the renderer's
     * signal for "this is a list row, give it a fixed 300px so the column
     * aligns". A grid button sizes to its label, which is what v1 did by simply
     * never setting a width on one.
     */
    public static Button nav(Component label, String command, String tooltip) {
        return new Button(label, Component.text(tooltip, Style.INFO), WidthHint.BODY,
                new UiAction.RunCommand(command));
    }

    /** A list row: an explicitly ROW-wide button so labels align down the column. */
    public static Button row(Component label, Component tooltip, UiAction action) {
        return new Button(label, tooltip, WidthHint.ROW, action);
    }

    /** The canonical manual cross-nav button. */
    public static Button manual(String mod) {
        return nav("📖 Manual", "/vibe manual " + mod, "Open the player manual");
    }

    /** The canonical source cross-nav button. */
    public static Button source(String mod) {
        return nav("⌨ Source", "/vibe source " + mod, "Read the generated source");
    }

    /** The canonical errors cross-nav button. */
    public static Button errors(String mod) {
        return nav("⚠ Errors", "/vibe errors " + mod, "View recent error records");
    }

    /** The canonical history cross-nav button. */
    public static Button history(String mod) {
        return nav("⏳ History", "/vibe history " + mod, "Browse and activate previous versions");
    }

    /** Viewer exit: {@code ← Back} to {@code mod}'s hub — every viewer climbs the hierarchy, never dead-ends. */
    public static Button backToHub(String mod) {
        return nav("← Back", "/vibe info " + mod, "Back to the " + mod + " hub");
    }

    /** Hub exit: {@code ← Back to list} to the mod browser. */
    public static Button backToList() {
        return nav("← Back to list", "/vibe list", "Back to the mod browser");
    }

    /** Top-of-hierarchy exit (browser, costs): a {@code Done} that just closes. */
    public static Button done() {
        return new Button(Component.text("Done"), Component.text("Close", Style.INFO), WidthHint.BODY,
                new UiAction.Callback((response, player) -> {
                    // Nothing to do: closing is the renderer's job. Deliberately a
                    // Callback rather than a RunCommand so no chat noise is produced.
                }));
    }

    /** Form/confirm negative: a {@code Cancel} that closes without acting. */
    public static Button cancel() {
        return cancel("Close without saving");
    }

    /** {@link #cancel()} with a screen-specific tooltip. */
    public static Button cancel(String tooltip) {
        return new Button(Component.text("Cancel"), Component.text(tooltip, Style.INFO), WidthHint.BODY,
                new UiAction.Callback((response, player) -> {
                    // Cancel is a pure close.
                }));
    }

    // ---- body helpers ----

    /** A prose body block. */
    public static BodyBlock text(Component component) {
        return new BodyBlock.Text(component, WidthHint.BODY);
    }

    /** A body block wide enough for source code and tables. */
    public static BodyBlock wide(Component component) {
        return new BodyBlock.Text(component, WidthHint.WIDE);
    }

    /** A decorative item block with {@code beside} rendered next to it. */
    public static BodyBlock icon(String iconId, boolean glint, Component beside) {
        return new BodyBlock.Icon(iconId, glint, beside);
    }

    /** One Component in {@code color} with newlines between {@code lines}. */
    public static Component joined(List<String> lines, TextColor color) {
        Component out = Component.empty().color(color);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out = out.append(Component.newline());
            }
            out = out.append(Component.text(lines.get(i)));
        }
        return out;
    }

    // ---- formatters (shared with the console dumps) ----

    /**
     * Coarse "Ns/Nm/Nh/Nd ago" relative-time formatting for an epoch millis (error
     * records' {@code lastSeen}, version entries' {@code createdAt}). Public so the
     * console history dump can reuse it.
     */
    public static String relativeTime(long epochMillis) {
        long deltaMs = System.currentTimeMillis() - epochMillis;
        if (deltaMs < 0) {
            deltaMs = 0;
        }
        long seconds = deltaMs / 1000;
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = hours / 24;
        return days + "d ago";
    }

    /**
     * The version's changelog, or its stored prompt (truncated to one line) for pre-changelog
     * entries. Public so the rollback confirm and the console history dump can reuse it.
     */
    public static String changelogOrPrompt(ModStore.StoredVersion v) {
        if (v.changelog() != null && !v.changelog().isBlank()) {
            return v.changelog();
        }
        String prompt = v.prompt() == null ? "" : v.prompt().replace('\n', ' ').trim();
        return prompt.length() <= 100 ? prompt : prompt.substring(0, 97) + "…";
    }

    /** The canonical glyph for a stored version's kind ("" for pre-tracking/unknown kinds). */
    public static String kindGlyph(String kind) {
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case "create" -> "✨";
            case "edit" -> "✎";
            case "fix" -> "🔧";
            case "again" -> "⟳";
            default -> "";
        };
    }

    /**
     * " · "-joined blank-safe metadata for one version: kind and relative time only when
     * {@code full} (the tooltip's row label already shows those), then model, cost when
     * paid, requester when known.
     */
    public static String joinedMeta(ModStore.StoredVersion v, boolean full) {
        List<String> meta = new ArrayList<>();
        if (full && v.kind() != null && !v.kind().isBlank()) {
            meta.add(v.kind());
        }
        if (v.model() != null && !v.model().isBlank()) {
            meta.add(v.model());
        }
        if (full) {
            meta.add(relativeTime(v.createdAt()));
        }
        if (v.costUsd() > 0) {
            meta.add(Style.fmtCost(v.costUsd()));
        }
        if (v.requester() != null && !v.requester().isBlank()) {
            meta.add("by " + v.requester());
        }
        return String.join(" · ", meta);
    }

    /** The simple name of a fully-qualified class name. */
    public static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }

    /**
     * One mod's lifetime generation spend, assembled by {@code /vibe costs};
     * {@code preTracking} counts versions saved before cost tracking existed
     * (blank {@code kind} marks those - their {@code costUsd} reads 0.0).
     */
    public record ModCost(String name, double lifetimeUsd, int versions, int preTracking) {

        /** Sums one stored mod's per-version costs (blank {@code kind} = pre-tracking, counts as $0). */
        public static ModCost of(ModStore.StoredMod mod) {
            double lifetime = 0.0;
            int preTracking = 0;
            for (ModStore.StoredVersion v : mod.versions()) {
                lifetime += v.costUsd();
                if (v.kind() == null || v.kind().isBlank()) {
                    preTracking++;
                }
            }
            return new ModCost(mod.name(), lifetime, mod.versions().size(), preTracking);
        }
    }

    /**
     * {@code "ModName — $0.0123 · 5 versions (2 pre-tracking)"}. Public so the console
     * costs dump can reuse it (like {@link #relativeTime}).
     */
    public static String costLine(ModCost row) {
        StringBuilder line = new StringBuilder(row.name())
                .append(" — ").append(Style.fmtCost(row.lifetimeUsd()))
                .append(" · ").append(row.versions()).append(row.versions() == 1 ? " version" : " versions");
        if (row.preTracking() > 0) {
            line.append(" (").append(row.preTracking()).append(" pre-tracking)");
        }
        return line.toString();
    }
}
