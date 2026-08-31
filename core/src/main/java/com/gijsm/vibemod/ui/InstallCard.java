package com.gijsm.vibemod.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.gijsm.vibemod.runtime.ModHandle;
import com.gijsm.vibemod.store.ModStore;

/**
 * Builds the chat-printable "install card" shown after a generation succeeds
 * or on {@code /vibe info <mod>}: a compact summary plus clickable follow-up
 * buttons, and a separate "verified facts" footer built from live
 * introspection (falling back to what's on record when the mod isn't
 * loaded). Degraded mods render their state in gold and gain
 * {@code [fix]}/{@code [errors]} buttons alongside the usual ones.
 */
public final class InstallCard {

    /**
     * Mod name -&gt; the registry ids it owns (V3 Phase 3 §A).
     *
     * <p>A static hook rather than a constructor parameter because the answer
     * lives on the loader side (a registry ledger the {@code core} module must
     * not know the shape of) and the question is asked from three UI surfaces
     * that would each have to thread it through. Empty by default, so Paper and
     * every self-test see exactly the card they saw before.
     *
     * <p>It is on the card at all because this is the one thing a mod acquires
     * that {@code /vibe disable} does not take away: the id stays registered
     * for the life of the world, and a player deserves to be told that by the
     * screen that lists everything else the mod owns.
     */
    private static volatile java.util.function.Function<String, List<String>> registeredContent =
            name -> List.of();

    /**
     * Mod name -&gt; the {@code minecraft:block} ids it owns (V4 Phase 1).
     *
     * <p>A second hook rather than a richer return type on the first, because
     * the first one's shape is what three UI surfaces and two hosts already
     * agree on, and because the question is genuinely a different one. "What did
     * this mod register" is a list for the player to read. "Did it register a
     * block" decides whether deleting it is reversible at all — a block id can
     * never be released, so a mod that has one is pinned forever rather than
     * tombstoned, and the delete confirmation has to say so before the click,
     * not after.
     */
    private static volatile java.util.function.Function<String, List<String>> registeredBlocks =
            name -> List.of();

    private InstallCard() {
    }

    /** Installs the registry-ledger lookup. Hosts with no registry channel never call this. */
    public static void setRegisteredContent(java.util.function.Function<String, List<String>> lookup) {
        registeredContent = lookup == null ? name -> List.of() : lookup;
    }

    /** Installs the block-id lookup, alongside {@link #setRegisteredContent}. */
    public static void setRegisteredBlocks(java.util.function.Function<String, List<String>> lookup) {
        registeredBlocks = lookup == null ? name -> List.of() : lookup;
    }

    /**
     * The block ids {@code modName} registered; empty on a host with no registry
     * channel, and empty for the overwhelmingly common mod that registered none.
     *
     * <p>Read by the delete confirmation, which is the one screen that has to
     * tell the truth about this before the player commits.
     */
    public static List<String> registeredBlocks(String modName) {
        return registeredBlocks.apply(modName);
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
     * Plain-text lines behind {@link #verifiedFooter}, reused by the manual screen's
     * "Verified facts" section. When {@code live} is {@code null} (mod not currently
     * loaded), introspected counts aren't available from stored data alone, so that's said plainly
     * rather than guessed.
     */
    public static List<String> verifiedFactLines(ModStore.StoredMod mod, ModHandle live, Map<String, String> values) {
        List<String> lines = new ArrayList<>();
        if (live != null) {
            // V3 Phase 4: a native mod is not a VibeContext mod wearing zeroes.
            // It has no curated listeners and no tasks by construction, so
            // "listeners: 0  tasks: 0" is not a thin answer, it is the wrong
            // question — the counter that means something for it is the number
            // of loader-event subscriptions standing behind the host's fanout.
            // The entrypoint line is what tells the two kinds of mod apart, so
            // it comes first and only a native mod has one.
            String entrypoints = live.entrypoints();
            if (entrypoints != null) {
                lines.add("entrypoints: " + entrypoints);
            }
            lines.add("commands: " + joinOrNone(live.commandNames()));
            lines.add("actions: " + joinOrNone(live.actionNames()));
            if (entrypoints == null) {
                lines.add("listeners: " + live.listenerCount() + "  tasks: " + live.taskCount());
            } else {
                lines.add("event subscriptions: " + live.nativeCount());
            }
            // Loader-neutral and kind-neutral: a curated mod may ship data/**
            // too, and when it does the tree is exactly as real as a native
            // mod's. Omitted rather than printed as zero, because "0" here
            // would be a claim about a feature the mod simply did not use.
            if (live.contentCount() > 0) {
                lines.add("resource trees: " + live.contentCount()
                        + "  (data/ and assets/, removed on disable)");
            }
            if (live.degraded()) {
                lines.add("state: DEGRADED (" + live.errorCount() + " error(s))");
            }
        } else {
            lines.add("(not currently loaded - live counts unavailable)");
        }
        // Deliberately outside the `live != null` branch: a registry id survives
        // the mod being disabled, so it is exactly the fact that is still true
        // when the live counts are not.
        List<String> registered = registeredContent.apply(mod.name());
        if (!registered.isEmpty()) {
            lines.add("registered content: " + String.join(", ", registered)
                    + "  (stays registered until the world is restarted)");
        }
        // And a block does not even come back at the restart: the id is claimed
        // for good. Said here, beside the line above, because the two facts
        // differ only in how permanent they are and a player reading one should
        // not have to go looking for the other.
        List<String> blocks = registeredBlocks.apply(mod.name());
        if (!blocks.isEmpty()) {
            lines.add("blocks: " + String.join(", ", blocks)
                    + "  (deleting this mod keeps these ids claimed forever - they come back as "
                    + "inert stubs, because releasing them would corrupt the chunks they sit in)");
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
