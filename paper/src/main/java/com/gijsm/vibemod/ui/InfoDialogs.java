package com.gijsm.vibemod.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemod.runtime.ModErrors;
import com.gijsm.vibemod.runtime.ModHandle;
import com.gijsm.vibemod.store.ModStore;

/**
 * Native dialog reading surfaces: the player-facing manual, source,
 * recent-errors, and version-history viewers, replacing the virtual books
 * {@link VirtualBooks} used to open ({@code VirtualBooks} keeps only its
 * console {@code dump*} role). Dialog bodies scroll, so there is no pagination
 * here — the manual renders its Markdown via {@link MarkdownMini}, source
 * files render whole.
 *
 * <p>Same experimental-API and threading posture as {@link Dialogs}:
 * {@code UnstableApiUsage} suppressed file-wide, every dialog shown on the
 * main thread, every callback hops back to the main thread and never lets an
 * exception escape into Bukkit.
 *
 * <p>Cross-navigation between the four viewers (and Back from a source file
 * to the file index, or from a version detail to the timeline) runs the
 * corresponding read-only {@code /vibe} subcommand via
 * {@link DialogAction#staticAction} — the same idiom as
 * {@link Dialogs#openFixConfirm} — so the viewers need no data suppliers of
 * their own beyond what each call passes in.
 */
@SuppressWarnings("UnstableApiUsage")
public final class InfoDialogs {

    private static final Logger LOG = Logger.getLogger(InfoDialogs.class.getName());

    private final Plugin plugin;

    public InfoDialogs(Plugin plugin) {
        this.plugin = plugin;
    }

    // ---- manual ----

    /** Opens {@code mod}'s manual: Markdown prose, verified facts, and the config table. */
    public void openManual(Player p, ModStore.StoredMod mod, ModHandle liveOrNull, Map<String, String> values) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogKit.iconBody(DialogKit.iconItem(mod.icon(), false),
                Component.text(mod.name(), NamedTextColor.WHITE)));
        String manual = mod.manual() == null || mod.manual().isBlank() ? mod.description() : mod.manual();
        for (Component block : MarkdownMini.render(manual, Style.INFO)) {
            body.add(DialogBody.plainMessage(block, DialogKit.BODY));
        }
        body.add(DialogBody.plainMessage(Style.heading("Verified facts"), DialogKit.BODY));
        body.add(DialogBody.plainMessage(
                DialogKit.joined(InstallCard.verifiedFactLines(mod, liveOrNull, values), Style.INFO),
                DialogKit.BODY));
        body.add(DialogBody.plainMessage(Style.heading("Config"), DialogKit.BODY));
        body.add(DialogBody.plainMessage(
                DialogKit.joined(configTableLines(values), Style.INFO).font(DialogKit.UNIFORM_FONT),
                DialogKit.BODY));

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(DialogKit.title(mod.name() + " — manual"))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(List.of(
                                DialogKit.sourceButton(mod.name()), DialogKit.errorsButton(mod.name())))
                        .exitAction(DialogKit.backToHubButton(mod.name()))
                        .columns(2)
                        .build()));
        show(p, dialog);
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

    // ---- source ----

    /**
     * Opens {@code name}'s generated source. A single-file mod opens the file
     * straight away; a multi-file mod opens an index dialog with one button per
     * file, each opening a per-file dialog with a Back button to this index.
     */
    public void openSource(Player p, String name, int version, Map<String, String> sources) {
        if (sources == null || sources.isEmpty()) {
            p.sendMessage(Style.err("No sources on disk for " + name + "."));
            return;
        }
        if (sources.size() == 1) {
            Map.Entry<String, String> only = sources.entrySet().iterator().next();
            show(p, fileDialog(name, version, only.getKey(), only.getValue(),
                    List.of(DialogKit.manualButton(name), DialogKit.errorsButton(name))));
            return;
        }
        show(p, indexDialog(name, version, sources));
    }

    /** The multi-file index: one button per file, plus the cross-navigation buttons. */
    private Dialog indexDialog(String name, int version, Map<String, String> sources) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String fqcn = entry.getKey();
            String source = entry.getValue();
            int lineCount = source.split("\n", -1).length;
            buttons.add(ActionButton.builder(Component.text(simpleName(fqcn)))
                    .tooltip(Component.text(fqcn, Style.INFO)
                            .append(Component.newline())
                            .append(Component.text(lineCount + " lines", Style.META)))
                    .width(DialogKit.ROW)
                    .action(openFileAction(name, version, fqcn, source))
                    .build());
        }
        buttons.add(DialogKit.manualButton(name));
        buttons.add(DialogKit.errorsButton(name));
        return Dialog.create(b -> b.empty()
                .base(DialogBase.builder(DialogKit.title(name + " — source v" + version))
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                sources.size() + " files — pick one to read.", Style.INFO), DialogKit.BODY)))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(buttons)
                        .exitAction(DialogKit.backToHubButton(name))
                        .columns(1)
                        .build()));
    }

    /** Click action for one index button: hop to the main thread and open that file's dialog. */
    private DialogAction openFileAction(String name, int version, String fqcn, String source) {
        return DialogAction.customClick((view, audience) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!(audience instanceof Player player)) {
                                return;
                            }
                            try {
                                player.showDialog(fileDialog(name, version, fqcn, source,
                                        List.of(DialogKit.navButton("← Back to files", "/vibe source " + name,
                                                "Back to the file list"))));
                            } catch (Exception e) {
                                LOG.log(Level.WARNING, "Source dialog failed", e);
                                player.sendMessage(Style.err("Could not open " + simpleName(fqcn) + ": "
                                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                            }
                        }),
                ClickCallback.Options.builder().uses(1).build());
    }

    /** One source file, whole, in the uniform font; {@code nav} supplies Back or cross-navigation. */
    private static Dialog fileDialog(String name, int version, String fqcn, String source, List<ActionButton> nav) {
        return Dialog.create(b -> b.empty()
                .base(DialogBase.builder(DialogKit.title(name + " — source v" + version))
                        .body(List.of(
                                DialogBody.plainMessage(Component.text("// " + fqcn, Style.META),
                                        DialogKit.WIDE),
                                DialogBody.plainMessage(
                                        Component.text(source, Style.INFO).font(DialogKit.UNIFORM_FONT),
                                        DialogKit.WIDE)))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(nav)
                        .exitAction(DialogKit.backToHubButton(name))
                        .columns(nav.size())
                        .build()));
    }

    private static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }

    // ---- errors ----

    /** Opens {@code name}'s recent deduped error records, one body block per record. */
    public void openErrors(Player p, String name, List<ModErrors.ErrorRecord> records) {
        List<DialogBody> body = new ArrayList<>();
        if (records == null || records.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text("No recent errors.", Style.INFO), DialogKit.BODY));
        }
        for (ModErrors.ErrorRecord r : records == null ? List.<ModErrors.ErrorRecord>of() : records) {
            Component block = Component.text(r.count() + "× " + r.exceptionClass() + ": " + r.message(),
                            Style.ERROR)
                    .append(Component.newline())
                    .append(Component.text("at " + r.topFrame() + " (" + r.where()
                            + ", last " + relativeTime(r.lastSeen()) + ")", Style.INFO));
            for (String frame : r.stack() == null ? List.<String>of() : r.stack()) {
                block = block.append(Component.newline())
                        .append(Component.text("  " + frame, Style.META));
            }
            body.add(DialogBody.plainMessage(block, DialogKit.BODY));
        }
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(DialogKit.title(name + " — errors"))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(List.of(
                                DialogKit.manualButton(name), DialogKit.sourceButton(name),
                                DialogKit.historyButton(name)))
                        .exitAction(DialogKit.backToHubButton(name))
                        .columns(3)
                        .build()));
        show(p, dialog);
    }

    /**
     * Coarse "Ns/Nm/Nh/Nd ago" relative-time formatting for an epoch millis (error
     * records' {@code lastSeen}, version entries' {@code createdAt}). Public so
     * {@code VibeCommand}'s console history dump can reuse it.
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

    // ---- history ----

    /**
     * Opens {@code mod}'s version timeline: one button per stored version, newest
     * first, ● marking the active version; clicking a row opens that version's
     * detail dialog with an Activate flow. {@code onDisk} holds the version
     * numbers whose {@code v<N>/} sources still exist, gating Activate.
     */
    public void openHistory(Player p, ModStore.StoredMod mod, Set<Integer> onDisk) {
        List<ModStore.StoredVersion> versions = mod.versions();
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = versions.size() - 1; i >= 0; i--) {
            ModStore.StoredVersion v = versions.get(i);
            boolean active = v.version() == mod.currentVersion();
            buttons.add(ActionButton.builder(historyRowLabel(v, active))
                    .tooltip(versionTooltip(v, onDisk.contains(v.version())))
                    .width(DialogKit.ROW)
                    .action(openVersionAction(mod, v, active, onDisk.contains(v.version())))
                    .build());
        }
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(DialogKit.title(mod.name() + " — history"))
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                versions.size() + " versions · v" + mod.currentVersion() + " active",
                                Style.INFO), DialogKit.BODY)))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(buttons)
                        .exitAction(DialogKit.backToHubButton(mod.name()))
                        .columns(1)
                        .build()));
        show(p, dialog);
    }

    /**
     * One timeline row: {@code "● ✨ v1 · create · 3d ago"} — the active row's dot green and
     * text white, inactive rows metadata-gray; the kind rendered glyph-first
     * (✨ create · ✎ edit · 🔧 fix · ⟳ again).
     */
    private static Component historyRowLabel(ModStore.StoredVersion v, boolean active) {
        StringBuilder text = new StringBuilder();
        String glyph = kindGlyph(v.kind());
        if (!glyph.isEmpty()) {
            text.append(glyph).append(' ');
        }
        text.append('v').append(v.version());
        if (v.kind() != null && !v.kind().isBlank()) {
            text.append(" · ").append(v.kind());
        }
        text.append(" · ").append(relativeTime(v.createdAt()));
        if (active) {
            return Component.text("● ", Style.OK)
                    .append(Component.text(text.toString(), NamedTextColor.WHITE));
        }
        return Component.text(text.toString(), Style.META);
    }

    /** The canonical glyph for a stored version's kind ("" for pre-tracking/unknown kinds). */
    private static String kindGlyph(String kind) {
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

    /** Timeline row tooltip: changelog (fallback: truncated prompt) plus blank-safe metadata. */
    private static Component versionTooltip(ModStore.StoredVersion v, boolean onDisk) {
        Component tip = Component.text(changelogOrPrompt(v), Style.INFO);
        String meta = joinedMeta(v, false);
        if (!meta.isEmpty()) {
            tip = tip.append(Component.newline())
                    .append(Component.text(meta, Style.META));
        }
        if (!onDisk) {
            tip = tip.append(Component.newline())
                    .append(Component.text("(sources missing on disk)", Style.ERROR));
        }
        return tip;
    }

    /** Click action for one timeline row: hop to the main thread and open that version's detail. */
    private DialogAction openVersionAction(ModStore.StoredMod mod, ModStore.StoredVersion v,
                                            boolean active, boolean onDisk) {
        return DialogAction.customClick((view, audience) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!(audience instanceof Player player)) {
                                return;
                            }
                            try {
                                player.showDialog(versionDialog(mod, v, active, onDisk));
                            } catch (Exception e) {
                                LOG.log(Level.WARNING, "Version dialog failed", e);
                                player.sendMessage(Style.err("Could not open v" + v.version() + ": "
                                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                            }
                        }),
                ClickCallback.Options.builder().uses(1).build());
    }

    /**
     * One version's detail: changelog, prompt, and joined metadata as body blocks;
     * {@code ⚡ Activate} routes through {@code /vibe rollback <mod> <n>} (confirm
     * dialog + permission check included) and is omitted when the version is
     * already active or its sources are gone.
     */
    private static Dialog versionDialog(ModStore.StoredMod mod, ModStore.StoredVersion v,
                                         boolean active, boolean onDisk) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(changelogOrPrompt(v), Style.INFO), DialogKit.BODY));
        if (v.prompt() != null && !v.prompt().isBlank()) {
            body.add(DialogBody.plainMessage(
                    Component.text("Prompt: " + v.prompt(), Style.META), DialogKit.BODY));
        }
        String meta = joinedMeta(v, true);
        if (!onDisk) {
            meta = meta.isEmpty() ? "(sources missing on disk)" : meta + " · (sources missing on disk)";
        }
        if (!meta.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text(meta, Style.META), DialogKit.BODY));
        }

        List<ActionButton> nav = new ArrayList<>();
        if (!active && onDisk) {
            nav.add(DialogKit.navButton("⚡ Activate…", "/vibe rollback " + mod.name() + " " + v.version(),
                    "Recompile and hot-load this version (asks to confirm)"));
        }
        nav.add(DialogKit.navButton("← Back to history", "/vibe history " + mod.name(),
                "Back to the version timeline"));
        return Dialog.create(b -> b.empty()
                .base(DialogBase.builder(DialogKit.title(mod.name() + " — v" + v.version()
                                + (active ? " (active)" : "")))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(nav)
                        .exitAction(DialogKit.backToHubButton(mod.name()))
                        .columns(nav.size())
                        .build()));
    }

    /**
     * The version's changelog, or its stored prompt (truncated to one line) for pre-changelog
     * entries. Public so {@code VibeCommand}'s rollback confirm and console history dump can
     * reuse it (like {@link #relativeTime}).
     */
    public static String changelogOrPrompt(ModStore.StoredVersion v) {
        if (v.changelog() != null && !v.changelog().isBlank()) {
            return v.changelog();
        }
        String prompt = v.prompt() == null ? "" : v.prompt().replace('\n', ' ').trim();
        return prompt.length() <= 100 ? prompt : prompt.substring(0, 97) + "…";
    }

    /**
     * " · "-joined blank-safe metadata for one version: kind and relative time only when
     * {@code full} (the tooltip's row label already shows those), then model, cost when
     * paid, requester when known.
     */
    private static String joinedMeta(ModStore.StoredVersion v, boolean full) {
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

    // ---- costs ----

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
     * Opens the read-only cost dashboard: session spend plus one line per mod,
     * costliest first ({@code rows} arrives pre-sorted). Mods that never cost
     * anything are folded into a footnote instead of listed. Done-only: unlike
     * the other viewers there is nowhere to navigate to - history covers the
     * per-mod drilldown.
     */
    public void openCosts(Player p, double sessionCostUsd, List<ModCost> rows) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogKit.iconBody(Material.GOLD_INGOT,
                Component.text("Session spend: ", Style.INFO)
                        .append(Component.text(Style.fmtCost(sessionCostUsd), NamedTextColor.WHITE))));
        List<String> lines = new ArrayList<>();
        int zeroMods = 0;
        boolean anyPreTracking = false;
        for (ModCost row : rows) {
            anyPreTracking |= row.preTracking() > 0;
            if (row.lifetimeUsd() <= 0) {
                zeroMods++;
                continue;
            }
            lines.add(costLine(row));
        }
        if (lines.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text("No paid generations yet.", Style.INFO),
                    DialogKit.BODY));
        } else {
            body.add(DialogBody.plainMessage(
                    DialogKit.joined(lines, Style.INFO).font(DialogKit.UNIFORM_FONT), DialogKit.BODY));
        }
        if (zeroMods > 0) {
            body.add(DialogBody.plainMessage(Component.text(
                    zeroMods + " mod(s) at $0 not shown", Style.META), DialogKit.BODY));
        }
        if (anyPreTracking) {
            body.add(DialogBody.plainMessage(Component.text(
                    "versions from before cost tracking count as $0", Style.META), DialogKit.BODY));
        }
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(DialogKit.title("VibeMod — costs"))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.notice(DialogKit.doneButton())));
        show(p, dialog);
    }

    /**
     * {@code "ModName — $0.0123 · 5 versions (2 pre-tracking)"}. Public so
     * {@code VibeCommand}'s console costs dump can reuse it (like {@link #relativeTime}).
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

    // ---- shared plumbing (thin wrapper over DialogKit with this class's plugin) ----

    /** See {@link DialogKit#show}. */
    private void show(Player p, Dialog dialog) {
        DialogKit.show(plugin, p, dialog);
    }
}
