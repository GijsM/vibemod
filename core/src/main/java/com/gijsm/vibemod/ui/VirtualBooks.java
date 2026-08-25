package com.gijsm.vibemod.ui;

import java.util.Map;
import java.util.TreeMap;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.gijsm.vibemod.runtime.ModHandle;
import com.gijsm.vibemod.store.ModStore;

/**
 * Console reading surfaces: plain chat dumps of a mod's manual, source, and
 * error report for senders with no screen UI at all (console/RCON). Players get
 * the corresponding {@code Screen} instead, rendered as a dialog or as chat
 * (ARCHITECTURE-V2 §3).
 *
 * <p>v2 change (§1.1): the target is an Adventure {@link Audience}, which every
 * platform can produce, rather than a {@code CommandSender}. That is the whole
 * de-Bukkiting of this class — callers pass {@code Messenger.console()} or the
 * invoking sender's audience.
 */
public final class VirtualBooks {

    private VirtualBooks() {
    }

    /** Console equivalent of the manual screen: a plain chat dump. */
    public static void dumpManual(Audience to, ModStore.StoredMod mod, ModHandle liveOrNull,
                                   Map<String, String> values) {
        String manual = mod.manual() == null || mod.manual().isBlank() ? mod.description() : mod.manual();
        to.sendMessage(Component.text(mod.name() + " - manual", NamedTextColor.GOLD));
        to.sendMessage(Component.text(manual, NamedTextColor.GRAY));
        to.sendMessage(InstallCard.verifiedFooter(mod, liveOrNull, values));
        if (values != null && !values.isEmpty()) {
            to.sendMessage(Component.text("Config:", NamedTextColor.GOLD));
            for (Map.Entry<String, String> e : new TreeMap<>(values).entrySet()) {
                to.sendMessage(Component.text("  " + e.getKey() + " = " + e.getValue(), NamedTextColor.GRAY));
            }
        }
    }

    /** Console equivalent of the source screens: a plain chat dump. */
    public static void dumpSource(Audience to, String name, Map<String, String> sources) {
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            to.sendMessage(Component.text("== " + entry.getKey() + " ==", NamedTextColor.GOLD));
            for (String line : entry.getValue().split("\n", -1)) {
                to.sendMessage(Component.text(line, NamedTextColor.GRAY));
            }
        }
    }

    /**
     * Console equivalent of the errors screen: {@code report} is
     * {@link com.gijsm.vibemod.runtime.ModErrors#report}'s output, dumped as-is.
     */
    public static void dumpErrors(Audience to, String report) {
        for (String line : report.split("\n", -1)) {
            to.sendMessage(Component.text(line, line.startsWith("==") ? NamedTextColor.GOLD : NamedTextColor.GRAY));
        }
    }
}
