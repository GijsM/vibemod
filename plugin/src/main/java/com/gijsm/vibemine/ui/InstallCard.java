package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.store.ModStore;

/**
 * Builds the chat-printable "install card" shown after a generation succeeds
 * or on {@code /vibe info <mod>}: a compact summary plus clickable follow-up
 * buttons, and a separate "verified facts" footer built from live
 * introspection (falling back to what's on record when the mod isn't
 * loaded). Degraded mods render their state in gold and gain
 * {@code [fix]}/{@code [errors]} buttons alongside the usual ones.
 */
public final class InstallCard {

    private InstallCard() {
    }

    /**
     * Name/version/state line, wrapped description, a "Try:" usage hint, and follow-up buttons:
     * {@code [manual][config][open][off]} always, plus {@code [fix][errors]} when degraded.
     */
    public static Component build(ModStore.StoredMod mod, ModHandle liveOrNull) {
        boolean enabled = liveOrNull != null ? liveOrNull.enabled() : mod.enabled();
        boolean degraded = liveOrNull != null && liveOrNull.degraded();
        NamedTextColor stateColor = Style.stateColor(enabled, degraded);
        String stateText = degraded ? "[DEGRADED" + errorSuffix(liveOrNull) + "]" : (enabled ? "[ON]" : "[OFF]");

        Component out = Style.prefix()
                .append(Component.text(mod.name(), NamedTextColor.GOLD))
                .append(Component.text(" v" + mod.currentVersion() + " ", NamedTextColor.DARK_GRAY))
                .append(Component.text(stateText, stateColor));

        for (Component line : Text.wrap(mod.description(), Text.DEFAULT_WIDTH, NamedTextColor.GRAY)) {
            out = out.append(Component.newline()).append(line);
        }

        if (mod.usage() != null && !mod.usage().isBlank()) {
            out = out.append(Component.newline())
                    .append(Component.text("Try: ", NamedTextColor.YELLOW))
                    .append(Component.text(mod.usage(), NamedTextColor.WHITE));
        }

        out = out.append(Component.newline()).append(buttons(mod.name(), degraded));
        return out;
    }

    private static String errorSuffix(ModHandle live) {
        if (live == null) {
            return "";
        }
        int n = live.errorCount();
        return n > 0 ? " ·" + n : "";
    }

    /** {@link #verifiedFooter(ModStore.StoredMod, ModHandle, Map, String)} with no errors line. */
    public static Component verifiedFooter(ModStore.StoredMod mod, ModHandle liveOrNull, Map<String, String> values) {
        return verifiedFooter(mod, liveOrNull, values, null);
    }

    /** Introspected facts (commands/actions/listener/task counts, knob values, creator) plus an optional errors line. */
    public static Component verifiedFooter(ModStore.StoredMod mod, ModHandle liveOrNull, Map<String, String> values,
                                            String errorsLine) {
        Component out = Component.text("Verified facts", NamedTextColor.DARK_AQUA);
        for (String line : verifiedFactLines(mod, liveOrNull, values)) {
            out = out.append(Component.newline()).append(Component.text(line, NamedTextColor.GRAY));
        }
        if (errorsLine != null && !errorsLine.isBlank()) {
            out = out.append(Component.newline()).append(Component.text(errorsLine, Style.WARN));
        }
        return out;
    }

    /**
     * Plain-text lines behind {@link #verifiedFooter}, reused by {@link InfoDialogs} for the
     * manual dialog's "Verified facts" section. When {@code live} is {@code null} (mod not currently
     * loaded), introspected counts aren't available from stored data alone, so that's said plainly
     * rather than guessed.
     */
    static List<String> verifiedFactLines(ModStore.StoredMod mod, ModHandle live, Map<String, String> values) {
        List<String> lines = new ArrayList<>();
        if (live != null) {
            lines.add("commands: " + joinOrNone(live.commandNames()));
            lines.add("actions: " + joinOrNone(live.actionNames()));
            lines.add("listeners: " + live.listenerCount() + "  tasks: " + live.taskCount());
            if (live.degraded()) {
                lines.add("state: DEGRADED (" + live.errorCount() + " error(s))");
            }
        } else {
            lines.add("(not currently loaded - live counts unavailable)");
        }
        if (values != null && !values.isEmpty()) {
            lines.add("knobs:");
            for (Map.Entry<String, String> e : new TreeMap<>(values).entrySet()) {
                lines.add("  " + e.getKey() + " = " + e.getValue());
            }
        }
        lines.add("creator: " + mod.creator());
        return lines;
    }

    private static String joinOrNone(List<String> items) {
        return items.isEmpty() ? "none" : String.join(", ", items);
    }

    private static Component buttons(String modName, boolean degraded) {
        Component out = Style.button("manual", "/vibe manual " + modName, "View the player manual", Style.ACTION)
                .append(Component.text(" "))
                .append(Style.button("config", "/vibe config " + modName, "Tune this mod's settings", Style.ACTION))
                .append(Component.text(" "))
                .append(Style.button("open", "/vibe info " + modName, "Open the mod hub", Style.ACTION))
                .append(Component.text(" "))
                .append(Style.button("off", "/vibe disable " + modName, "Disable this mod", Style.ERROR));
        if (degraded) {
            out = out.append(Component.text(" "))
                    .append(Style.button("🔧 fix", "/vibe fix " + modName, "Send errors to the model", Style.WARN))
                    .append(Component.text(" "))
                    .append(Style.button("⚠ errors", "/vibe errors " + modName, "View the error log", Style.WARN));
        }
        return out;
    }
}
