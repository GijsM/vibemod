package com.gijsm.vibemod.ui.screens;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.gijsm.vibemod.platform.ui.BodyBlock;
import com.gijsm.vibemod.platform.ui.Button;
import com.gijsm.vibemod.platform.ui.Screen;
import com.gijsm.vibemod.platform.ui.UiAction;
import com.gijsm.vibemod.runtime.DebugEcho;
import com.gijsm.vibemod.runtime.ModErrors;
import com.gijsm.vibemod.runtime.ModHandle;
import com.gijsm.vibemod.runtime.ModLifecycle;
import com.gijsm.vibemod.store.ModStore;
import com.gijsm.vibemod.ui.Style;

/**
 * The two navigational screens (ARCHITECTURE-V2 §3.3, screens 8 and 9): the
 * per-mod action hub and the mod browser behind bare {@code /vibe}.
 *
 * <p>These were v1's {@code ui/ModHubDialog}, and they keep its defining
 * property: every action is a real {@code /vibe} subcommand, so permissions are
 * re-checked per click and every button re-fetches fresh data. The {@code admin}
 * flag the builders take only keeps the grid honest — each button's command
 * enforces its own permission anyway.
 *
 * <p>Live wiring (lifecycle/store/errors/debug) is injected once and read fresh
 * on every build, which is why the live registry state can win over the stored
 * {@code enabled} flag after a watchdog trip.
 */
public final class HubScreens {

    private final ModLifecycle lifecycle;
    private final ModStore store;
    private final ModErrors errors;
    private final DebugEcho debug;
    /** The running host's platform name, for the meta.json v3 "(other platform)" badge (§5). */
    private final String platformName;

    public HubScreens(ModLifecycle lifecycle, ModStore store, ModErrors errors, DebugEcho debug,
                      String platformName) {
        this.lifecycle = lifecycle;
        this.store = store;
        this.errors = errors;
        this.debug = debug;
        this.platformName = platformName == null ? "" : platformName;
    }

    /** True when {@code mod} was generated somewhere this host cannot run it (§5). */
    private boolean foreign(ModStore.StoredMod mod) {
        return !mod.platform().equalsIgnoreCase(platformName);
    }

    // ---- 8. mod hub ----

    /**
     * The hub for {@code name}, assembled from fresh store/lifecycle state, or
     * {@code null} when no such mod exists (the caller reports that).
     */
    public Screen modHub(String name, boolean admin) {
        ModStore.StoredMod mod = store.get(name);
        if (mod == null) {
            return null;
        }
        // Live lifecycle state wins over the stored flag (e.g. a watchdog trip).
        ModHandle live = lifecycle.get(mod.name());
        boolean enabled = live != null ? live.enabled() : mod.enabled();
        boolean degraded = live != null && live.degraded();

        return new Screen(ScreenKit.title(mod.name()), Screen.Kind.MENU,
                hubBody(mod, enabled, degraded),
                List.of(),
                hubButtons(mod, enabled, degraded, admin),
                ScreenKit.backToList(),
                3);
    }

    private List<BodyBlock> hubBody(ModStore.StoredMod mod, boolean enabled, boolean degraded) {
        List<BodyBlock> body = new ArrayList<>();
        // The icon glints while the mod is running — the retired chest list's cue, resurrected.
        body.add(ScreenKit.icon(mod.icon(), enabled && !degraded,
                Component.text(mod.name(), Style.stateColor(enabled, degraded))));
        body.add(ScreenKit.text(Component.text(mod.description(), Style.INFO)));
        if (mod.usage() != null && !mod.usage().isBlank()) {
            body.add(ScreenKit.text(Component.text("Try: ", Style.HINT)
                    .append(Component.text(mod.usage(), NamedTextColor.WHITE))));
        }
        body.add(ScreenKit.text(stateLine(mod, enabled, degraded)));
        body.add(ScreenKit.text(versionLine(mod)));
        body.add(ScreenKit.text(Component.text("by " + mod.creator(), Style.META)));
        int knobs = mod.config().size();
        body.add(ScreenKit.text(Component.text(
                knobs == 0 ? "No configurable settings" : knobs + " config knob(s)", Style.META)));
        body.add(ScreenKit.text(Component.text(lifetimeCostLine(mod), Style.META)));
        return List.copyOf(body);
    }

    /**
     * The action grid (columns of 3, reading order): viewers first —
     * {@code Manual · Source · History / Errors} — then for admins
     * {@code Configure… · Edit… / Enable|Disable · Debug · Reload / Export ·
     * [Fix… when degraded] · Delete…}, delete always last.
     */
    private List<Button> hubButtons(ModStore.StoredMod mod, boolean enabled, boolean degraded, boolean admin) {
        String m = mod.name();
        List<Button> buttons = new ArrayList<>();
        buttons.add(ScreenKit.manual(m));
        buttons.add(ScreenKit.source(m));
        buttons.add(ScreenKit.history(m));
        buttons.add(ScreenKit.errors(m));
        if (admin) {
            buttons.add(ScreenKit.nav("⚙ Configure…", "/vibe config " + m, "Open the config screen"));
            buttons.add(ScreenKit.nav("✎ Edit…", "/vibe edit " + m, "Open the edit-request screen"));
            if (enabled) {
                buttons.add(ScreenKit.nav("■ Disable", "/vibe disable " + m, "Disable this mod"));
            } else if (!foreign(mod)) {
                buttons.add(ScreenKit.nav("▶ Enable", "/vibe enable " + m, "Enable this mod"));
            }
            boolean debugOn = debug.enabled(m);
            buttons.add(ScreenKit.nav(
                    Style.dot(debugOn, false).append(Component.text(" Debug " + (debugOn ? "on" : "off"))),
                    "/vibe debug " + m, "Toggle live ctx.log()/exception echo to ops"));
            buttons.add(ScreenKit.nav("⟳ Reload", "/vibe reload " + m,
                    "Recompile and apply the current stored version"));
            buttons.add(ScreenKit.nav("📦 Export", "/vibe export " + m, "Export a standalone plugin jar"));
            if (degraded) {
                buttons.add(ScreenKit.nav("🔧 Fix…", "/vibe fix " + m,
                        "Send recent errors to the model for a repair round (asks to confirm)"));
            }
            buttons.add(ScreenKit.nav("✖ Delete…", "/vibe delete " + m,
                    "Permanently delete this mod (asks to confirm)"));
        }
        return List.copyOf(buttons);
    }

    /** {@code "● running" / "● degraded (n errors)" / "● off"} — same wording as the list items. */
    private Component stateLine(ModStore.StoredMod mod, boolean enabled, boolean degraded) {
        Component dot = Style.dot(enabled, degraded);
        if (foreign(mod)) {
            return Style.dot(false, false).append(Component.text(
                    " (other platform: " + mod.platform() + ") — /vibe make it again to run it here",
                    Style.META));
        }
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
            String changelog = ScreenKit.changelogOrPrompt(current);
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
    private static String lifetimeCostLine(ModStore.StoredMod mod) {
        ScreenKit.ModCost cost = ScreenKit.ModCost.of(mod);
        StringBuilder line = new StringBuilder("Lifetime cost: ").append(Style.fmtCost(cost.lifetimeUsd()));
        if (cost.preTracking() > 0) {
            line.append(" (").append(cost.preTracking()).append(" pre-tracking)");
        }
        return line.toString();
    }

    // ---- 9. browser ----

    /**
     * The mod browser: one summary line plus one command-routed row per stored
     * mod (store order), each opening that mod's hub via {@code /vibe info} —
     * stateless navigation, so the hub's own permission handling applies. Admins
     * get a trailing ⚙ Settings button.
     */
    public Screen browser(boolean admin) {
        List<ModStore.StoredMod> mods = store.all();
        List<BodyBlock> body = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        if (mods.isEmpty()) {
            body.add(ScreenKit.text(Component.text(
                    "No mods yet — /vibe make \"something wonderful\"", Style.HINT)));
        } else {
            int running = 0;
            int degradedCount = 0;
            for (ModStore.StoredMod mod : mods) {
                // Live lifecycle state wins over the stored flag (e.g. a watchdog trip).
                ModHandle live = lifecycle.get(mod.name());
                boolean enabled = live != null ? live.enabled() : mod.enabled();
                boolean degraded = live != null && live.degraded();
                if (degraded) {
                    degradedCount++;
                } else if (enabled) {
                    running++;
                }
                buttons.add(modRow(mod, enabled, degraded));
            }
            String summary = mods.size() + " mods · " + running + " running"
                    + (degradedCount > 0 ? " · " + degradedCount + " degraded" : "");
            body.add(ScreenKit.text(Component.text(summary, Style.INFO)));
        }
        if (admin) {
            buttons.add(ScreenKit.nav("⚙ Settings", "/vibe settings", "Open the plugin settings"));
        }

        // No mods and no admin button: nothing to click, so this is a notice.
        Screen.Kind kind = buttons.isEmpty() ? Screen.Kind.NOTICE : Screen.Kind.MENU;
        return new Screen(ScreenKit.title("VibeMod"), kind, List.copyOf(body), List.of(),
                List.copyOf(buttons), ScreenKit.done(), 1);
    }

    /**
     * One browser row: {@code "● Name vN"} — dot and name colored by state (the
     * same green/gold/gray as {@link #stateLine}), version dark-gray; the
     * tooltip carries the detail.
     */
    private Button modRow(ModStore.StoredMod mod, boolean enabled, boolean degraded) {
        Component label = Style.dot(enabled, degraded)
                .append(Component.text(" " + mod.name(), Style.stateColor(enabled, degraded)))
                .append(Component.text(" v" + mod.currentVersion(), Style.META));
        if (foreign(mod)) {
            label = label.append(Component.text(" (other platform)", Style.META));
        }
        return ScreenKit.row(label, modTooltip(mod, enabled, degraded),
                new UiAction.RunCommand("/vibe info " + mod.name()));
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
}
