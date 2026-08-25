package com.gijsm.vibemod.ui;

import java.util.ArrayList;
import java.util.List;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemod.runtime.DebugEcho;
import com.gijsm.vibemod.runtime.ModErrors;
import com.gijsm.vibemod.runtime.ModHandle;
import com.gijsm.vibemod.runtime.ModRegistry;
import com.gijsm.vibemod.store.ModStore;

/**
 * The per-mod action hub: one rich, permission-aware native dialog per mod —
 * icon item, description, live state, current version + changelog, creator,
 * knob count, lifetime generation cost — with every action routed through its
 * real {@code /vibe} subcommand via command-running buttons (the
 * {@link DialogKit#navButton} idiom), so permissions are re-checked per
 * action and every button re-fetches fresh data. Opened by {@code /vibe info}
 * for players, by clicking a mod in {@link #openBrowser}'s list, and by the
 * install card's {@code [open]} button.
 *
 * <p>Also hosts {@link #openBrowser}, the native mod-browser dialog behind
 * {@code /vibe list} (and bare {@code /vibe}) for players — the plugin's main
 * surface: the entire UI is dialogs. It lives here rather than in its own
 * class because it needs the same live wiring (registry/store/errors, read
 * fresh on every open) and it is the hub's natural sibling: every row
 * navigates into the hub.
 *
 * <p>Its own class rather than another method on {@link InfoDialogs} because
 * unlike the read-only viewers it needs live wiring (registry/store/errors/
 * debug are injected at construction and read fresh on every {@link #open},
 * the same posture as {@link SettingsDialog}).
 *
 * <p>Same experimental-API and threading posture as {@link Dialogs}:
 * {@code UnstableApiUsage} suppressed file-wide, dialogs shown next tick, the
 * only custom-click button (the browser's Done) is a no-op.
 */
@SuppressWarnings("UnstableApiUsage")
public final class ModHubDialog {

    private final Plugin plugin;
    private final ModRegistry registry;
    private final ModStore store;
    private final ModErrors errors;
    private final DebugEcho debug;

    public ModHubDialog(Plugin plugin, ModRegistry registry, ModStore store, ModErrors errors, DebugEcho debug) {
        this.plugin = plugin;
        this.registry = registry;
        this.store = store;
        this.errors = errors;
        this.debug = debug;
    }

    /** Opens the hub for {@code name}, assembled from fresh store/registry state. */
    public void open(Player p, String name) {
        if (!p.isOnline()) {
            return;
        }
        ModStore.StoredMod mod = store.get(name);
        if (mod == null) {
            p.sendMessage(Style.err("Unknown mod: " + name));
            return;
        }

        // Live registry state wins over the stored flag (e.g. a watchdog trip).
        ModHandle live = registry.get(mod.name());
        boolean enabled = live != null ? live.enabled() : mod.enabled();
        boolean degraded = live != null && live.degraded();
        boolean admin = p.hasPermission("vibe.admin");

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(DialogKit.title(mod.name()))
                        .body(buildBody(mod, enabled, degraded))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(buildButtons(mod, enabled, degraded, admin))
                        .exitAction(DialogKit.backToListButton())
                        .columns(3)
                        .build()));
        show(p, dialog);
    }

    // ---- browser ----

    /**
     * Opens the native mod browser: one summary line plus one command-routed
     * button per stored mod (store order), each opening
     * that mod's hub via {@code /vibe info} — stateless navigation, so the hub's
     * own permission handling applies. Admins get a trailing ⚙ Settings button.
     */
    public void openBrowser(Player p) {
        if (!p.isOnline()) {
            return;
        }
        List<ModStore.StoredMod> mods = store.all();
        boolean admin = p.hasPermission("vibe.admin");

        List<DialogBody> body = new ArrayList<>();
        List<ActionButton> buttons = new ArrayList<>();
        if (mods.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text(
                    "No mods yet — /vibe make \"something wonderful\"", Style.HINT), DialogKit.BODY));
        } else {
            int running = 0;
            int degradedCount = 0;
            for (ModStore.StoredMod mod : mods) {
                // Live registry state wins over the stored flag (e.g. a watchdog trip).
                ModHandle live = registry.get(mod.name());
                boolean enabled = live != null ? live.enabled() : mod.enabled();
                boolean degraded = live != null && live.degraded();
                if (degraded) {
                    degradedCount++;
                } else if (enabled) {
                    running++;
                }
                buttons.add(modButton(mod, enabled, degraded));
            }
            String summary = mods.size() + " mods · " + running + " running"
                    + (degradedCount > 0 ? " · " + degradedCount + " degraded" : "");
            body.add(DialogBody.plainMessage(Component.text(summary, Style.INFO), DialogKit.BODY));
        }
        if (admin) {
            buttons.add(DialogKit.navButton("⚙ Settings", "/vibe settings", "Open the plugin settings"));
        }

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(DialogKit.title("VibeMod"))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(buttons.isEmpty()
                        ? DialogType.notice(DialogKit.doneButton())
                        : DialogType.multiAction(buttons).exitAction(DialogKit.doneButton()).columns(1).build()));
        show(p, dialog);
    }

    /**
     * One browser row: {@code "● Name vN"} — dot and name colored by state (the
     * same green/gold/gray as {@link #stateLine}), version dark-gray; the
     * tooltip carries the detail.
     */
    private ActionButton modButton(ModStore.StoredMod mod, boolean enabled, boolean degraded) {
        Component label = Style.dot(enabled, degraded)
                .append(Component.text(" " + mod.name(), Style.stateColor(enabled, degraded)))
                .append(Component.text(" v" + mod.currentVersion(), Style.META));
        return ActionButton.builder(label)
                .tooltip(modTooltip(mod, enabled, degraded))
                .width(DialogKit.ROW)
                .action(DialogAction.staticAction(ClickEvent.runCommand("/vibe info " + mod.name())))
                .build();
    }

    /** Row tooltip: truncated description, then state · ("vN of M" when multi-version ·) knob count. */
    private Component modTooltip(ModStore.StoredMod mod, boolean enabled, boolean degraded) {
        String desc = mod.description() == null ? "" : mod.description();
        if (desc.length() > 120) {
            desc = desc.substring(0, 117) + "…";
        }
        List<String> meta = new ArrayList<>();
        meta.add(degraded ? "degraded (" + errors.distinctCount(mod.name()) + " errors)"
                : (enabled ? "running" : "off"));
        if (mod.versions().size() > 1) {
            meta.add("v" + mod.currentVersion() + " of " + mod.versions().size());
        }
        int knobs = mod.config().size();
        meta.add(knobs == 0 ? "no config knobs" : knobs + " config knob(s)");
        return Component.text(desc, Style.INFO)
                .append(Component.newline())
                .append(Component.text(String.join(" · ", meta), Style.META));
    }

    // ---- body ----

    private List<DialogBody> buildBody(ModStore.StoredMod mod, boolean enabled, boolean degraded) {
        List<DialogBody> body = new ArrayList<>();
        // The icon glints while the mod is running — the retired chest list's cue, resurrected.
        body.add(DialogKit.iconBody(DialogKit.iconItem(mod.icon(), enabled && !degraded),
                Component.text(mod.name(), Style.stateColor(enabled, degraded))));
        body.add(DialogBody.plainMessage(Component.text(mod.description(), Style.INFO), DialogKit.BODY));
        if (mod.usage() != null && !mod.usage().isBlank()) {
            body.add(DialogBody.plainMessage(Component.text("Try: ", Style.HINT)
                    .append(Component.text(mod.usage(), NamedTextColor.WHITE)), DialogKit.BODY));
        }
        body.add(DialogBody.plainMessage(stateLine(mod, enabled, degraded), DialogKit.BODY));
        body.add(DialogBody.plainMessage(versionLine(mod), DialogKit.BODY));
        body.add(DialogBody.plainMessage(Component.text("by " + mod.creator(), Style.META),
                DialogKit.BODY));
        int knobs = mod.config().size();
        body.add(DialogBody.plainMessage(Component.text(
                knobs == 0 ? "No configurable settings" : knobs + " config knob(s)",
                Style.META), DialogKit.BODY));
        body.add(DialogBody.plainMessage(Component.text(costLine(mod), Style.META), DialogKit.BODY));
        return body;
    }

    /** {@code "● running" / "● degraded (n errors)" / "● off"} — same wording as the list items. */
    private Component stateLine(ModStore.StoredMod mod, boolean enabled, boolean degraded) {
        Component dot = Style.dot(enabled, degraded);
        if (degraded) {
            int n = errors.distinctCount(mod.name());
            return dot.append(Component.text(" degraded (" + n + " errors)", Style.WARN));
        }
        return dot.append(Component.text(enabled ? " running" : " off",
                Style.stateColor(enabled, degraded)));
    }

    /** {@code "vN of M"} (bare {@code "vN"} for single-version mods) plus the current version's changelog. */
    private static Component versionLine(ModStore.StoredMod mod) {
        String versionText = mod.versions().size() > 1
                ? "v" + mod.currentVersion() + " of " + mod.versions().size()
                : "v" + mod.currentVersion();
        Component line = Component.text(versionText, Style.META);
        ModStore.StoredVersion current = currentVersionEntry(mod);
        if (current != null) {
            String changelog = InfoDialogs.changelogOrPrompt(current);
            if (!changelog.isBlank()) {
                line = line.append(Component.text(" — " + changelog, Style.INFO));
            }
        }
        return line;
    }

    /** The version entry the current pointer names, falling back to the last entry. */
    private static ModStore.StoredVersion currentVersionEntry(ModStore.StoredMod mod) {
        for (ModStore.StoredVersion v : mod.versions()) {
            if (v.version() == mod.currentVersion()) {
                return v;
            }
        }
        return mod.versions().isEmpty() ? null : mod.versions().get(mod.versions().size() - 1);
    }

    /** Lifetime generation cost, with the pre-tracking footnote when it applies. */
    private static String costLine(ModStore.StoredMod mod) {
        InfoDialogs.ModCost cost = InfoDialogs.ModCost.of(mod);
        StringBuilder line = new StringBuilder("Lifetime cost: ").append(Style.fmtCost(cost.lifetimeUsd()));
        if (cost.preTracking() > 0) {
            line.append(" (").append(cost.preTracking()).append(" pre-tracking)");
        }
        return line.toString();
    }

    // ---- buttons ----

    /**
     * The action grid (columns of 3, reading order): viewers first —
     * {@code Manual · Source · History / Errors} — then for admins
     * {@code Configure… · Edit… / Enable|Disable · Debug · Reload / Export ·
     * [Fix… when degraded] · Delete…}, delete always last. Read-only viewers
     * for everyone with {@code vibe.use}, the management actions only for
     * {@code vibe.admin} (each button's command re-checks the permission
     * anyway — this just keeps the grid honest).
     */
    private List<ActionButton> buildButtons(ModStore.StoredMod mod, boolean enabled, boolean degraded,
                                             boolean admin) {
        String m = mod.name();
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(DialogKit.manualButton(m));
        buttons.add(DialogKit.sourceButton(m));
        buttons.add(DialogKit.historyButton(m));
        buttons.add(DialogKit.errorsButton(m));
        if (admin) {
            buttons.add(DialogKit.navButton("⚙ Configure…", "/vibe config " + m, "Open the config dialog"));
            buttons.add(DialogKit.navButton("✎ Edit…", "/vibe edit " + m, "Open the edit-request dialog"));
            buttons.add(enabled
                    ? DialogKit.navButton("■ Disable", "/vibe disable " + m, "Disable this mod")
                    : DialogKit.navButton("▶ Enable", "/vibe enable " + m, "Enable this mod"));
            boolean debugOn = debug.enabled(m);
            buttons.add(DialogKit.navButton(
                    Style.dot(debugOn, false).append(Component.text(" Debug " + (debugOn ? "on" : "off"))),
                    "/vibe debug " + m, "Toggle live ctx.log()/exception echo to ops"));
            buttons.add(DialogKit.navButton("⟳ Reload", "/vibe reload " + m,
                    "Recompile and apply the current stored version"));
            buttons.add(DialogKit.navButton("📦 Export", "/vibe export " + m, "Export a standalone plugin jar"));
            if (degraded) {
                buttons.add(DialogKit.navButton("🔧 Fix…", "/vibe fix " + m,
                        "Send recent errors to the model for a repair round (asks to confirm)"));
            }
            buttons.add(DialogKit.navButton("✖ Delete…", "/vibe delete " + m,
                    "Permanently delete this mod (asks to confirm)"));
        }
        return buttons;
    }

    // ---- shared plumbing (thin wrapper over DialogKit with this class's plugin) ----

    /** See {@link DialogKit#show}. */
    private void show(Player p, Dialog dialog) {
        DialogKit.show(plugin, p, dialog);
    }
}
