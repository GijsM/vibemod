package com.gijsm.vibemod.ui;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import io.papermc.paper.event.player.AsyncChatEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Lets a player converse in plain chat instead of typing {@code /vibe make}
 * each time. While a player's chat mode is on, their chat lines are cancelled
 * and routed to {@code onPrompt} on the main thread instead of being
 * broadcast; typing {@code off} turns chat mode back off.
 */
public final class ChatMode implements Listener {

    private final Plugin plugin;
    private final BiConsumer<Player, String> onPrompt;
    private final Set<UUID> active = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public ChatMode(Plugin plugin, BiConsumer<Player, String> onPrompt) {
        this.plugin = plugin;
        this.onPrompt = onPrompt;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Flips chat mode for {@code p} and returns the new state. */
    public boolean toggle(Player p) {
        UUID id = p.getUniqueId();
        if (active.remove(id)) {
            return false;
        }
        active.add(id);
        return true;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!active.contains(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.trim().equalsIgnoreCase("off")) {
                active.remove(player.getUniqueId());
                player.sendMessage(Component.text("Chat mode off.", NamedTextColor.GRAY));
                return;
            }
            onPrompt.accept(player, text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
    }
}
