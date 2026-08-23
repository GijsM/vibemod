package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.List;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemine.runtime.DebugEcho;
import com.gijsm.vibemine.runtime.ModErrors;
import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.runtime.ModRegistry;
import com.gijsm.vibemine.store.ModStore;

/**
 * The per-mod action hub: one rich, permission-aware native dialog per mod —
 * icon item, description, live state, current version + changelog, creator,
 * knob count, lifetime generation cost — with every action routed through its
 * real {@code /vibe} subcommand via command-running buttons (the
 * {@link InfoDialogs} navButton idiom), so permissions are re-checked per
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
 * only custom-click button (Done) is a no-op.
 */
@SuppressWarnings("UnstableApiUsage")
public final class ModHubDialog {

    /** Prose bodies: same width as {@link InfoDialogs}' manual/errors viewers. */
    private static final int PROSE_WIDTH = 400;
    /** Browser mod buttons: generous fixed width so names align ({@code InfoDialogs}' FILE_BUTTON_WIDTH idiom). */
    private static final int MOD_BUTTON_WIDTH = 300;

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
                .base(DialogBase.builder(Component.text("⬡ " + mod.name()))
                        .body(buildBody(mod, enabled, degraded))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(buildButtons(mod, enabled, degraded, admin))
                        .exitAction(navButton("← Back to list", "/vibe list", "Back to the mod browser"))
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
                    "No mods yet — /vibe make \"something wonderful\"", NamedTextColor.GRAY), PROSE_WIDTH));
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
            body.add(DialogBody.plainMessage(Component.text(summary, NamedTextColor.GRAY), PROSE_WIDTH));
        }
        if (admin) {
            buttons.add(navButton("⚙ Settings", "/vibe settings", "Open the plugin settings"));
        }

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("⬡ VibeMod"))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(buttons.isEmpty()
                        ? DialogType.notice(doneButton())
                        : DialogType.multiAction(buttons).exitAction(doneButton()).columns(1).build()));
        show(p, dialog);
    }

    /**
     * One browser row: {@code "● Name vN"} — dot and name colored by state (the
     * same green/gold/gray as {@link #stateLine}), version dark-gray; the
     * tooltip carries the detail.
     */
    private ActionButton modButton(ModStore.StoredMod mod, boolean enabled, boolean degraded) {
        NamedTextColor stateColor = degraded ? NamedTextColor.GOLD
                : (enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY);
        Component label = Component.text("● ", stateColor)
                .append(Component.text(mod.name(), stateColor))
                .append(Component.text(" v" + mod.currentVersion(), NamedTextColor.DARK_GRAY));
        return ActionButton.builder(label)
                .tooltip(modTooltip(mod, enabled, degraded))
                .width(MOD_BUTTON_WIDTH)
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
        return Component.text(desc, NamedTextColor.GRAY)
                .append(Component.newline())
                .append(Component.text(String.join(" · ", meta), NamedTextColor.DARK_GRAY));
    }

    // ---- body ----

    private List<DialogBody> buildBody(ModStore.StoredMod mod, boolean enabled, boolean degraded) {
        NamedTextColor nameColor = degraded ? NamedTextColor.GOLD
                : (enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.item(new ItemStack(resolveIcon(mod.icon())))
                .description(DialogBody.plainMessage(Component.text(mod.name(), nameColor)))
                .build());
        body.add(DialogBody.plainMessage(Component.text(mod.description(), NamedTextColor.GRAY), PROSE_WIDTH));
        if (mod.usage() != null && !mod.usage().isBlank()) {
            body.add(DialogBody.plainMessage(Component.text("Try: ", NamedTextColor.YELLOW)
                    .append(Component.text(mod.usage(), NamedTextColor.WHITE)), PROSE_WIDTH));
        }
        body.add(DialogBody.plainMessage(stateLine(mod, enabled, degraded), PROSE_WIDTH));
        body.add(DialogBody.plainMessage(versionLine(mod), PROSE_WIDTH));
        body.add(DialogBody.plainMessage(Component.text("by " + mod.creator(), NamedTextColor.DARK_GRAY),
                PROSE_WIDTH));
        int knobs = mod.config().size();
        body.add(DialogBody.plainMessage(Component.text(
                knobs == 0 ? "No configurable settings" : knobs + " config knob(s)",
                NamedTextColor.DARK_GRAY), PROSE_WIDTH));
        body.add(DialogBody.plainMessage(Component.text(costLine(mod), NamedTextColor.DARK_GRAY), PROSE_WIDTH));
        return body;
    }

    /**
     * Resolves a mod's icon Material from its stored {@code icon} name, falling back to
     * {@link Material#PAPER} when the name is blank, unrecognized, or not a real item.
     */
    private static Material resolveIcon(String icon) {
        if (icon != null && !icon.isBlank()) {
            Material m = Material.matchMaterial(icon);
            if (m != null && m.isItem()) {
                return m;
            }
        }
        return Material.PAPER;
    }

    /** {@code "● running" / "● degraded (n errors)" / "● off"} — same wording as the list items. */
    private Component stateLine(ModStore.StoredMod mod, boolean enabled, boolean degraded) {
        Component dot = Style.dot(enabled, degraded);
        if (degraded) {
            int n = errors.distinctCount(mod.name());
            return dot.append(Component.text(" degraded (" + n + " errors)", NamedTextColor.GOLD));
        }
        return dot.append(Component.text(enabled ? " running" : " off",
                enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY));
    }

    /** {@code "vN of M"} (bare {@code "vN"} for single-version mods) plus the current version's changelog. */
    private static Component versionLine(ModStore.StoredMod mod) {
        String versionText = mod.versions().size() > 1
                ? "v" + mod.currentVersion() + " of " + mod.versions().size()
                : "v" + mod.currentVersion();
        Component line = Component.text(versionText, NamedTextColor.DARK_GRAY);
        ModStore.StoredVersion current = currentVersionEntry(mod);
        if (current != null) {
            String changelog = InfoDialogs.changelogOrPrompt(current);
            if (!changelog.isBlank()) {
                line = line.append(Component.text(" — " + changelog, NamedTextColor.GRAY));
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
     * The action grid: read-only viewers for everyone with {@code vibe.use}, the
     * management actions only for {@code vibe.admin} (each button's command
     * re-checks the permission anyway — this just keeps the grid honest).
     */
    private List<ActionButton> buildButtons(ModStore.StoredMod mod, boolean enabled, boolean degraded,
                                             boolean admin) {
        String m = mod.name();
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(navButton("📖 Manual", "/vibe manual " + m, "Open the player manual"));
        buttons.add(navButton("⌨ Source", "/vibe source " + m, "Read the generated source"));
        buttons.add(navButton("History", "/vibe history " + m, "Browse and activate previous versions"));
        buttons.add(navButton("⚠ Errors", "/vibe errors " + m, "View recent error records"));
        if (admin) {
            buttons.add(navButton("⚙ Configure", "/vibe config " + m, "Open the config dialog"));
            buttons.add(navButton("✎ Edit…", "/vibe edit " + m, "Open the edit-request dialog"));
            if (degraded) {
                buttons.add(navButton("🔧 Fix", "/vibe fix " + m,
                        "Send recent errors to the model for a repair round"));
            }
            buttons.add(navButton("Export", "/vibe export " + m, "Export a standalone plugin jar"));
            buttons.add(navButton("Debug: " + (debug.enabled(m) ? "ON" : "OFF"), "/vibe debug " + m,
                    "Toggle live ctx.log()/exception echo to ops"));
            buttons.add(enabled
                    ? navButton("Disable", "/vibe disable " + m, "Disable this mod")
                    : navButton("Enable", "/vibe enable " + m, "Enable this mod"));
            buttons.add(navButton("⟳ Reload", "/vibe reload " + m,
                    "Recompile and apply the current stored version"));
            buttons.add(navButton("✖ Delete…", "/vibe delete " + m, "Permanently delete this mod (asks to confirm)"));
        }
        return buttons;
    }

    // ---- shared plumbing (same idioms as InfoDialogs) ----

    /** A button that runs a {@code /vibe} subcommand, which re-checks permissions and re-fetches data. */
    private static ActionButton navButton(String label, String command, String tooltip) {
        return ActionButton.builder(Component.text(label))
                .tooltip(Component.text(tooltip, NamedTextColor.GRAY))
                .action(DialogAction.staticAction(ClickEvent.runCommand(command)))
                .build();
    }

    /** A no-op close button, mirroring {@link Dialogs}' Cancel. */
    private static ActionButton doneButton() {
        return ActionButton.builder(Component.text("Done"))
                .action(DialogAction.customClick((view, audience) -> {
                }, ClickCallback.Options.builder().uses(1).build()))
                .build();
    }

    /**
     * Show next tick (never inside an inventory-click handler); same rationale
     * as {@link Dialogs}: showDialog replaces the client screen on its own.
     */
    private void show(Player p, Dialog dialog) {
        Bukkit.getScheduler().runTask(plugin, () -> p.showDialog(dialog));
    }
}
