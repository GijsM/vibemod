package com.gijsm.vibemine.ui;

import java.util.Map;
import java.util.TreeMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.command.CommandSender;

import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.store.ModStore;

/**
 * Console reading surfaces: plain chat dumps of a mod's manual, source, and
 * error report for senders with no dialog UI (console/RCON). The player-facing
 * viewers these used to back (virtual books opened via
 * {@link org.bukkit.entity.Player#openBook}) moved to native dialogs in
 * {@link InfoDialogs}, taking the book pagination with them.
 */
public final class VirtualBooks {

    private VirtualBooks() {
    }

    /** Console equivalent of {@link InfoDialogs#openManual}: a plain chat dump. */
    public static void dumpManual(CommandSender sender, ModStore.StoredMod mod, ModHandle liveOrNull,
                                   Map<String, String> values) {
        String manual = mod.manual() == null || mod.manual().isBlank() ? mod.description() : mod.manual();
        sender.sendMessage(Component.text(mod.name() + " - manual", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(manual, NamedTextColor.GRAY));
        sender.sendMessage(InstallCard.verifiedFooter(mod, liveOrNull, values));
        if (values != null && !values.isEmpty()) {
            sender.sendMessage(Component.text("Config:", NamedTextColor.GOLD));
            for (Map.Entry<String, String> e : new TreeMap<>(values).entrySet()) {
                sender.sendMessage(Component.text("  " + e.getKey() + " = " + e.getValue(), NamedTextColor.GRAY));
            }
        }
    }

    /** Console equivalent of {@link InfoDialogs#openSource}: a plain chat dump. */
    public static void dumpSource(CommandSender sender, String name, Map<String, String> sources) {
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            sender.sendMessage(Component.text("== " + entry.getKey() + " ==", NamedTextColor.GOLD));
            for (String line : entry.getValue().split("\n", -1)) {
                sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
            }
        }
    }

    /**
     * Console equivalent of {@link InfoDialogs#openErrors}: {@code report} is
     * {@link com.gijsm.vibemine.runtime.ModErrors#report}'s output, dumped as-is.
     */
    public static void dumpErrors(CommandSender sender, String report) {
        for (String line : report.split("\n", -1)) {
            sender.sendMessage(Component.text(line, line.startsWith("==") ? NamedTextColor.GOLD : NamedTextColor.GRAY));
        }
    }
}
