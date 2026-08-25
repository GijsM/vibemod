package com.gijsm.vibemod.ui.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.gijsm.vibemod.platform.ui.BodyBlock;
import com.gijsm.vibemod.platform.ui.Button;
import com.gijsm.vibemod.platform.ui.Screen;
import com.gijsm.vibemod.platform.ui.UiAction;
import com.gijsm.vibemod.platform.ui.UiRenderer;
import com.gijsm.vibemod.runtime.ModErrors;
import com.gijsm.vibemod.runtime.ModHandle;
import com.gijsm.vibemod.store.ModStore;
import com.gijsm.vibemod.ui.InstallCard;
import com.gijsm.vibemod.ui.MarkdownMini;
import com.gijsm.vibemod.ui.Style;

/**
 * The read-only screens (ARCHITECTURE-V2 §3.3, screens 10-16): the player
 * manual, the source index and one source file, the error log, the version
 * timeline and one version's detail, and the cost dashboard.
 *
 * <p>These were v1's {@code ui/InfoDialogs}. Cross-navigation between them stays
 * command-routed, so the screens need no data suppliers of their own beyond what
 * each call passes in. The two exceptions are the in-place drill-downs — a
 * source file from the file index, a version detail from the timeline — which
 * genuinely cannot be a command round-trip: the file's whole text and the
 * version's entry are already in hand, and inventing
 * {@code /vibe source <mod> <fqcn>} just to re-fetch them would add two
 * subcommands and a parsing surface for nothing. Those two use a
 * {@link UiAction.Callback} that shows the next screen through the injected
 * {@link UiRenderer}.
 */
public final class InfoScreens {

    private final UiRenderer renderer;

    public InfoScreens(UiRenderer renderer) {
        this.renderer = renderer;
    }

    // ---- 10. manual ----

    /** {@code mod}'s manual: Markdown prose, verified facts, and the config table. */
    public Screen manual(ModStore.StoredMod mod, ModHandle liveOrNull, Map<String, String> values) {
        List<BodyBlock> body = new ArrayList<>();
        body.add(ScreenKit.icon(mod.icon(), false, Component.text(mod.name(), NamedTextColor.WHITE)));
        String manual = mod.manual() == null || mod.manual().isBlank() ? mod.description() : mod.manual();
        for (Component block : MarkdownMini.render(manual, Style.INFO)) {
            body.add(ScreenKit.text(block));
        }
        body.add(ScreenKit.text(Style.heading("Verified facts")));
        body.add(ScreenKit.text(
                ScreenKit.joined(InstallCard.verifiedFactLines(mod, liveOrNull, values), Style.INFO)));
        body.add(ScreenKit.text(Style.heading("Config")));
        // Uniform font so the key = value table column-aligns.
        body.add(ScreenKit.text(
                ScreenKit.joined(configTableLines(values), Style.INFO).font(ScreenKit.UNIFORM_FONT)));

        return new Screen(ScreenKit.title(mod.name() + " — manual"), Screen.Kind.MENU,
                List.copyOf(body), List.of(),
                List.of(ScreenKit.source(mod.name()), ScreenKit.errors(mod.name())),
                ScreenKit.backToHub(mod.name()), 2);
    }

    private static List<String> configTableLines(Map<String, String> values) {
        List<String> lines = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            lines.add("(no configurable settings)");
            return lines;
        }
        for (Map.Entry<String, String> e : new TreeMap<>(values).entrySet()) {
            lines.add(e.getKey() + " = " + e.getValue());
        }
        return lines;
    }

    // ---- 11 + 12. source ----

    /**
     * {@code name}'s generated source: a single-file mod goes straight to the
     * file screen, a multi-file mod to the index. {@code null} when there are no
     * sources on disk (the caller reports that).
     */
    public Screen source(String name, int version, Map<String, String> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        if (sources.size() == 1) {
            Map.Entry<String, String> only = sources.entrySet().iterator().next();
            return sourceFile(name, version, only.getKey(), only.getValue(),
                    List.of(ScreenKit.manual(name), ScreenKit.errors(name)));
        }
        return sourceIndex(name, version, sources);
    }

    /** The multi-file index: one row per file, plus the cross-navigation buttons. */
    private Screen sourceIndex(String name, int version, Map<String, String> sources) {
        List<Button> buttons = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String fqcn = entry.getKey();
            String text = entry.getValue();
            int lineCount = text.split("\n", -1).length;
            Component tooltip = Component.text(fqcn, Style.INFO)
                    .append(Component.newline())
                    .append(Component.text(lineCount + " lines", Style.META));
            buttons.add(ScreenKit.row(Component.text(ScreenKit.simpleName(fqcn)), tooltip,
                    new UiAction.Callback((response, player) -> renderer.show(player,
                            sourceFile(name, version, fqcn, text,
                                    List.of(ScreenKit.nav("← Back to files", "/vibe source " + name,
                                            "Back to the file list")))))));
        }
        buttons.add(ScreenKit.manual(name));
        buttons.add(ScreenKit.errors(name));
        return new Screen(ScreenKit.title(name + " — source v" + version), Screen.Kind.MENU,
                List.of(ScreenKit.text(Component.text(
                        sources.size() + " files — pick one to read.", Style.INFO))),
                List.of(), List.copyOf(buttons), ScreenKit.backToHub(name), 1);
    }

    /** One source file, whole, in the uniform font; {@code nav} supplies Back or cross-navigation. */
    private static Screen sourceFile(String name, int version, String fqcn, String text, List<Button> nav) {
        return new Screen(ScreenKit.title(name + " — source v" + version), Screen.Kind.MENU,
                List.of(ScreenKit.wide(Component.text("// " + fqcn, Style.META)),
                        ScreenKit.wide(Component.text(text, Style.INFO).font(ScreenKit.UNIFORM_FONT))),
                List.of(), List.copyOf(nav), ScreenKit.backToHub(name), Math.max(1, nav.size()));
    }

    // ---- 13. errors ----

    /** {@code name}'s recent deduped error records, one body block per record. */
    public Screen errors(String name, List<ModErrors.ErrorRecord> records) {
        List<BodyBlock> body = new ArrayList<>();
        if (records == null || records.isEmpty()) {
            body.add(ScreenKit.text(Component.text("No recent errors.", Style.INFO)));
        }
        for (ModErrors.ErrorRecord r : records == null ? List.<ModErrors.ErrorRecord>of() : records) {
            Component block = Component.text(r.count() + "× " + r.exceptionClass() + ": " + r.message(),
                            Style.ERROR)
                    .append(Component.newline())
                    .append(Component.text("at " + r.topFrame() + " (" + r.where()
                            + ", last " + ScreenKit.relativeTime(r.lastSeen()) + ")", Style.INFO));
            for (String frame : r.stack() == null ? List.<String>of() : r.stack()) {
                block = block.append(Component.newline())
                        .append(Component.text("  " + frame, Style.META));
            }
            body.add(ScreenKit.text(block));
        }
        return new Screen(ScreenKit.title(name + " — errors"), Screen.Kind.MENU,
                List.copyOf(body), List.of(),
                List.of(ScreenKit.manual(name), ScreenKit.source(name), ScreenKit.history(name)),
                ScreenKit.backToHub(name), 3);
    }

    // ---- 14 + 15. history ----

    /**
     * {@code mod}'s version timeline: one row per stored version, newest first,
     * ● marking the active version; a row opens that version's detail with an
     * Activate flow. {@code onDisk} holds the version numbers whose {@code v<N>/}
     * sources still exist, gating Activate.
     */
    public Screen history(ModStore.StoredMod mod, Set<Integer> onDisk) {
        List<ModStore.StoredVersion> versions = mod.versions();
        List<Button> buttons = new ArrayList<>();
        for (int i = versions.size() - 1; i >= 0; i--) {
            ModStore.StoredVersion v = versions.get(i);
            boolean active = v.version() == mod.currentVersion();
            boolean present = onDisk.contains(v.version());
            buttons.add(ScreenKit.row(historyRowLabel(v, active), versionTooltip(v, present),
                    new UiAction.Callback((response, player) ->
                            renderer.show(player, versionDetail(mod, v, active, present)))));
        }
        return new Screen(ScreenKit.title(mod.name() + " — history"), Screen.Kind.MENU,
                List.of(ScreenKit.text(Component.text(
                        versions.size() + " versions · v" + mod.currentVersion() + " active", Style.INFO))),
                List.of(), List.copyOf(buttons), ScreenKit.backToHub(mod.name()), 1);
    }

    /**
     * One timeline row: {@code "● ✨ v1 · create · 3d ago"} — the active row's dot green and
     * text white, inactive rows metadata-gray; the kind rendered glyph-first
     * (✨ create · ✎ edit · 🔧 fix · ⟳ again).
     */
    private static Component historyRowLabel(ModStore.StoredVersion v, boolean active) {
        StringBuilder text = new StringBuilder();
        String glyph = ScreenKit.kindGlyph(v.kind());
        if (!glyph.isEmpty()) {
            text.append(glyph).append(' ');
        }
        text.append('v').append(v.version());
        if (v.kind() != null && !v.kind().isBlank()) {
            text.append(" · ").append(v.kind());
        }
        text.append(" · ").append(ScreenKit.relativeTime(v.createdAt()));
        if (active) {
            return Component.text("● ", Style.OK)
                    .append(Component.text(text.toString(), NamedTextColor.WHITE));
        }
        return Component.text(text.toString(), Style.META);
    }

    /** Timeline row tooltip: changelog (fallback: truncated prompt) plus blank-safe metadata. */
    private static Component versionTooltip(ModStore.StoredVersion v, boolean onDisk) {
        Component tip = Component.text(ScreenKit.changelogOrPrompt(v), Style.INFO);
        String meta = ScreenKit.joinedMeta(v, false);
        if (!meta.isEmpty()) {
            tip = tip.append(Component.newline()).append(Component.text(meta, Style.META));
        }
        if (!onDisk) {
            tip = tip.append(Component.newline())
                    .append(Component.text("(sources missing on disk)", Style.ERROR));
        }
        return tip;
    }

    /**
     * One version's detail: changelog, prompt, and joined metadata as body blocks;
     * {@code ⚡ Activate} routes through {@code /vibe rollback <mod> <n>} (confirm
     * screen + permission check included) and is omitted when the version is
     * already active or its sources are gone.
     */
    private static Screen versionDetail(ModStore.StoredMod mod, ModStore.StoredVersion v,
                                        boolean active, boolean onDisk) {
        List<BodyBlock> body = new ArrayList<>();
        body.add(ScreenKit.text(Component.text(ScreenKit.changelogOrPrompt(v), Style.INFO)));
        if (v.prompt() != null && !v.prompt().isBlank()) {
            body.add(ScreenKit.text(Component.text("Prompt: " + v.prompt(), Style.META)));
        }
        String meta = ScreenKit.joinedMeta(v, true);
        if (!onDisk) {
            meta = meta.isEmpty() ? "(sources missing on disk)" : meta + " · (sources missing on disk)";
        }
        if (!meta.isEmpty()) {
            body.add(ScreenKit.text(Component.text(meta, Style.META)));
        }

        List<Button> nav = new ArrayList<>();
        if (!active && onDisk) {
            nav.add(ScreenKit.nav("⚡ Activate…", "/vibe rollback " + mod.name() + " " + v.version(),
                    "Recompile and hot-load this version (asks to confirm)"));
        }
        nav.add(ScreenKit.nav("← Back to history", "/vibe history " + mod.name(),
                "Back to the version timeline"));
        return new Screen(ScreenKit.title(mod.name() + " — v" + v.version() + (active ? " (active)" : "")),
                Screen.Kind.MENU, List.copyOf(body), List.of(), List.copyOf(nav),
                ScreenKit.backToHub(mod.name()), nav.size());
    }

    // ---- 16. costs ----

    /**
     * The read-only cost dashboard: session spend plus one line per mod,
     * costliest first ({@code rows} arrives pre-sorted). Mods that never cost
     * anything are folded into a footnote instead of listed. Done-only: unlike
     * the other viewers there is nowhere to navigate to — history covers the
     * per-mod drilldown.
     */
    public Screen costs(double sessionCostUsd, List<ScreenKit.ModCost> rows) {
        List<BodyBlock> body = new ArrayList<>();
        body.add(ScreenKit.icon("GOLD_INGOT", false,
                Component.text("Session spend: ", Style.INFO)
                        .append(Component.text(Style.fmtCost(sessionCostUsd), NamedTextColor.WHITE))));
        List<String> lines = new ArrayList<>();
        int zeroMods = 0;
        boolean anyPreTracking = false;
        for (ScreenKit.ModCost row : rows) {
            anyPreTracking |= row.preTracking() > 0;
            if (row.lifetimeUsd() <= 0) {
                zeroMods++;
                continue;
            }
            lines.add(ScreenKit.costLine(row));
        }
        if (lines.isEmpty()) {
            body.add(ScreenKit.text(Component.text("No paid generations yet.", Style.INFO)));
        } else {
            body.add(ScreenKit.text(ScreenKit.joined(lines, Style.INFO).font(ScreenKit.UNIFORM_FONT)));
        }
        if (zeroMods > 0) {
            body.add(ScreenKit.text(Component.text(zeroMods + " mod(s) at $0 not shown", Style.META)));
        }
        if (anyPreTracking) {
            body.add(ScreenKit.text(Component.text(
                    "versions from before cost tracking count as $0", Style.META)));
        }
        return new Screen(ScreenKit.title("VibeMod — costs"), Screen.Kind.NOTICE,
                List.copyOf(body), List.of(), List.of(), ScreenKit.done(), 1);
    }
}
