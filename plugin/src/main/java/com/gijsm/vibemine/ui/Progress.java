package com.gijsm.vibemine.ui;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Visual + chat feedback for one in-flight generation. Shows an Adventure boss
 * bar to player viewers (progressing in quarters across the Thinking / Writing
 * / Compiling / Loading phases) and always mirrors progress as plain chat
 * lines, since generation work happens off the main thread and console
 * senders have no boss bar. All Bukkit/Adventure mutations are hopped onto
 * the main thread.
 */
public final class Progress {

    private static final float STEP = 0.25f;

    private final Plugin plugin;
    private final CommandSender viewer;
    private final String title;
    private final Player player;
    private BossBar bossBar;
    private float progress = 0f;

    public Progress(Plugin plugin, CommandSender viewer, String title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.title = title;
        this.player = (viewer instanceof Player p) ? p : null;
    }

    /** Advance to a new phase: player gets a boss bar update, everyone gets a chat line. */
    public void phase(String label) {
        runOnMain(() -> {
            progress = Math.min(1f, progress + STEP);
            NamedTextColor textColor = flavorTextColor(label);
            BossBar.Color barColor = flavorBarColor(label);
            Component name = Component.text(title + " - " + label, textColor);
            if (player != null) {
                ensureBossBar(name, barColor);
                bossBar.progress(progress);
                bossBar.name(name);
                bossBar.color(barColor);
            }
            chat(name);
        });
    }

    /** A gray, low-priority informational chat line. */
    public void detail(String line) {
        runOnMain(() -> chat(Component.text(line, NamedTextColor.GRAY)));
    }

    /** Complete successfully: full green bar, success sound + firework, then hide. */
    public void succeed(String message) {
        runOnMain(() -> {
            Component name = Component.text(message, NamedTextColor.GREEN);
            if (player != null) {
                ensureBossBar(name, BossBar.Color.GREEN);
                bossBar.progress(1f);
                bossBar.color(BossBar.Color.GREEN);
                bossBar.name(name);
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                Location loc = player.getLocation();
                player.getWorld().spawnParticle(Particle.FIREWORK, loc, 30, 0.5, 0.5, 0.5);
                hideAfter(60);
            }
            chat(name);
        });
    }

    /** Complete with failure: full red bar, failure sound, then hide. */
    public void fail(String message) {
        runOnMain(() -> {
            Component name = Component.text(message, NamedTextColor.RED);
            if (player != null) {
                ensureBossBar(name, BossBar.Color.RED);
                bossBar.progress(1f);
                bossBar.color(BossBar.Color.RED);
                bossBar.name(name);
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                hideAfter(100);
            }
            chat(name);
        });
    }

    private void ensureBossBar(Component name, BossBar.Color color) {
        if (bossBar == null) {
            bossBar = BossBar.bossBar(name, progress, color, BossBar.Overlay.PROGRESS);
            player.showBossBar(bossBar);
        }
    }

    private void hideAfter(long ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (bossBar != null && player != null) {
                player.hideBossBar(bossBar);
            }
        }, ticks);
    }

    private void chat(Component line) {
        viewer.sendMessage(line);
    }

    /** Known phase labels get flavor text color; unknown labels are plain white. */
    private static NamedTextColor flavorTextColor(String label) {
        if (label == null) {
            return NamedTextColor.WHITE;
        }
        return switch (label) {
            case "Thinking" -> NamedTextColor.LIGHT_PURPLE;
            case "Writing" -> NamedTextColor.BLUE;
            case "Compiling" -> NamedTextColor.YELLOW;
            case "Loading" -> NamedTextColor.GREEN;
            default -> NamedTextColor.WHITE;
        };
    }

    /** Known phase labels get a matching boss bar color; unknown labels default to white. */
    private static BossBar.Color flavorBarColor(String label) {
        if (label == null) {
            return BossBar.Color.WHITE;
        }
        return switch (label) {
            case "Thinking" -> BossBar.Color.PURPLE;
            case "Writing" -> BossBar.Color.BLUE;
            case "Compiling" -> BossBar.Color.YELLOW;
            case "Loading" -> BossBar.Color.GREEN;
            default -> BossBar.Color.WHITE;
        };
    }

    private void runOnMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }
}
