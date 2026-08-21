package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.store.ModStore;

/**
 * Builds the chat-printable "install card" shown after a generation succeeds
 * or on {@code /vibe info <mod>}: a compact summary plus clickable follow-up
 * buttons, and a separate "verified facts" footer built from live
 * introspection (falling back to what's on record when the mod isn't loaded).
 */
public final class InstallCard {

    private InstallCard() {
    }

    /** Name/version/state line, wrapped description, a "Try:" usage hint, and [manual][config][info][off] buttons. */
    public static Component build(ModStore.StoredMod mod, ModHandle liveOrNull) {
        boolean enabled = liveOrNull != null ? liveOrNull.enabled() : mod.enabled();
        NamedTextColor stateColor = enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY;

        Component out = Component.text(mod.name(), NamedTextColor.GOLD)
                .append(Component.text(" v" + mod.currentVersion() + " ", NamedTextColor.DARK_GRAY))
                .append(Component.text(enabled ? "[ON]" : "[OFF]", stateColor));

        for (Component line : Text.wrap(mod.description(), Text.DEFAULT_WIDTH, NamedTextColor.GRAY)) {
            out = out.append(Component.newline()).append(line);
        }

        if (mod.usage() != null && !mod.usage().isBlank()) {
            out = out.append(Component.newline())
                    .append(Component.text("Try: ", NamedTextColor.YELLOW))
                    .append(Component.text(mod.usage(), NamedTextColor.WHITE));
        }

        out = out.append(Component.newline()).append(buttons(mod.name()));
        return out;
    }

    /** Introspected facts: commands, actions, listener/task counts, current knob values, creator. */
    public static Component verifiedFooter(ModStore.StoredMod mod, ModHandle liveOrNull, Map<String, String> values) {
        Component out = Component.text("Verified facts", NamedTextColor.DARK_AQUA);
        for (String line : verifiedFactLines(mod, liveOrNull, values)) {
            out = out.append(Component.newline()).append(Component.text(line, NamedTextColor.GRAY));
        }
        return out;
    }

    /**
     * Plain-text lines behind {@link #verifiedFooter}, reused by {@link ManualBook} for its
     * written-book "Verified facts" page. When {@code live} is {@code null} (mod not currently
     * loaded), introspected counts aren't available from stored data alone, so that's said plainly
     * rather than guessed.
     */
    static List<String> verifiedFactLines(ModStore.StoredMod mod, ModHandle live, Map<String, String> values) {
        List<String> lines = new ArrayList<>();
        if (live != null) {
            lines.add("commands: " + joinOrNone(live.commandNames()));
            lines.add("actions: " + joinOrNone(live.actionNames()));
            lines.add("listeners: " + live.listenerCount() + "  tasks: " + live.taskCount());
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

    private static Component buttons(String modName) {
        return button("[manual]", "/vibe manual " + modName, "View the player manual", NamedTextColor.AQUA)
                .append(Component.text(" "))
                .append(button("[config]", "/vibe config " + modName, "Tune this mod's settings", NamedTextColor.AQUA))
                .append(Component.text(" "))
                .append(button("[info]", "/vibe info " + modName, "Show this install card again", NamedTextColor.AQUA))
                .append(Component.text(" "))
                .append(button("[off]", "/vibe disable " + modName, "Disable this mod", NamedTextColor.RED));
    }

    private static Component button(String label, String command, String hover, NamedTextColor color) {
        return Component.text(label, color)
                .decoration(TextDecoration.ITALIC, false)
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)))
                .clickEvent(ClickEvent.runCommand(command));
    }
}
