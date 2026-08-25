package com.gijsm.vibemod.paper;

import java.util.UUID;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemod.platform.Messenger;

/**
 * {@link Messenger} over Bukkit. Almost transparent: on Paper every
 * {@code CommandSender} already <em>is</em> an Adventure {@link Audience}, which
 * is why so much of core could be de-Bukkited by simply changing a parameter
 * type.
 */
public final class PaperMessenger implements Messenger {

    /** The celebration burst: same particle count and spread as the v1 install flourish. */
    private static final int CELEBRATION_PARTICLES = 30;
    private static final double CELEBRATION_SPREAD = 0.5;

    private final Plugin plugin;

    public PaperMessenger(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Audience player(UUID playerId) {
        Player player = playerId == null ? null : plugin.getServer().getPlayer(playerId);
        return player == null ? Audience.empty() : player;
    }

    @Override
    public Audience console() {
        return plugin.getServer().getConsoleSender();
    }

    @Override
    public boolean online(UUID playerId) {
        Player player = playerId == null ? null : plugin.getServer().getPlayer(playerId);
        return player != null && player.isOnline();
    }

    @Override
    public void broadcast(Component message) {
        plugin.getServer().broadcast(message);
    }

    @Override
    public void broadcast(Component message, String permission) {
        plugin.getServer().broadcast(message, permission);
    }

    @Override
    public void broadcastToPlayers(Component message, String permission) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission(permission)) {
                player.sendMessage(message);
            }
        }
    }

    /**
     * The firework puff at the player after a successful generation. Purely
     * cosmetic, so any failure (an odd world state, a fork without the particle)
     * is swallowed rather than turning a celebration into an error.
     */
    @Override
    public void celebrate(UUID playerId) {
        Player player = playerId == null ? null : plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            Location loc = player.getLocation();
            player.getWorld().spawnParticle(Particle.FIREWORK, loc, CELEBRATION_PARTICLES,
                    CELEBRATION_SPREAD, CELEBRATION_SPREAD, CELEBRATION_SPREAD);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }
}
