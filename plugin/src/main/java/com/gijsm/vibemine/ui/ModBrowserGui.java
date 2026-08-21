package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.runtime.ModRegistry;
import com.gijsm.vibemine.store.ModStore;

/**
 * A chest-inventory GUI listing every stored mod, one item per mod, with
 * click-driven management: left-click toggles enabled/disabled, right-click
 * rolls back to the previous version, shift-right-click deletes (after a
 * second confirming click within 5 seconds), and dropping (Q) exports the
 * mod as a standalone jar.
 *
 * <p>Note: the frozen 4-argument constructor listed in ARCHITECTURE.md predates
 * the rollback confirmation flow; a 5th constructor argument,
 * {@code rollbackAction}, was added with architect approval so rollback can
 * trigger a recompile+reload seam cleanly instead of leaving the GUI to poke
 * the registry directly.
 */
public final class ModBrowserGui implements Listener {

    private final Plugin plugin;
    private final ModRegistry registry;
    private final ModStore store;
    private final BiConsumer<Player, String> exportAction;
    private final BiConsumer<Player, String> rollbackAction;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    private static final long DELETE_CONFIRM_MS = 5000L;
    private static final Component TITLE = Component.text("VibeMine Mods");

    /**
     * @param exportAction   invoked with (player, modName) when a player drops a mod item to export it
     * @param rollbackAction invoked with (player, modName) after a successful store rollback so the
     *                       caller can recompile+reload the mod's now-current version; if {@code null},
     *                       the player is simply told to run {@code /vibe enable <mod>} themselves
     */
    public ModBrowserGui(Plugin plugin, ModRegistry registry, ModStore store,
                          BiConsumer<Player, String> exportAction, BiConsumer<Player, String> rollbackAction) {
        this.plugin = plugin;
        this.registry = registry;
        this.store = store;
        this.exportAction = exportAction;
        this.rollbackAction = rollbackAction;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Opens (or refreshes/reopens) the mod browser for {@code p}. */
    public void open(Player p) {
        List<ModStore.StoredMod> mods = store.all();
        int size = idealSize(mods.size());
        Inventory inv = plugin.getServer().createInventory(null, size, TITLE);
        List<String> slotMods = new ArrayList<>(Collections.nCopies(size, null));
        for (int i = 0; i < mods.size(); i++) {
            ModStore.StoredMod mod = mods.get(i);
            inv.setItem(i, buildItem(mod));
            slotMods.set(i, mod.name());
        }
        sessions.put(p.getUniqueId(), new Session(inv, slotMods));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(session.inventory)) {
            // Click was in the player's own inventory half - leave it alone.
            return;
        }
        event.setCancelled(true);

        int slot = event.getSlot();
        if (slot < 0 || slot >= session.slotMods.size()) {
            return;
        }
        String modName = session.slotMods.get(slot);
        if (modName == null) {
            return;
        }

        ClickType click = event.getClick();
        switch (click) {
            case LEFT, SHIFT_LEFT -> toggleEnabled(player, modName);
            case RIGHT -> rollback(player, modName);
            case SHIFT_RIGHT -> confirmedDelete(player, session, modName);
            case DROP, CONTROL_DROP -> exportAction.accept(player, modName);
            default -> {
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.inventory.equals(event.getInventory())) {
            sessions.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private void toggleEnabled(Player player, String modName) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(player, "Unknown mod: " + modName);
            return;
        }
        if (mod.enabled()) {
            registry.disable(modName);
            store.setEnabled(modName, false);
            info(player, modName + " disabled.");
        } else {
            if (registry.get(modName) == null) {
                warn(player, modName + " isn't loaded - run /vibe enable " + modName + " to compile and enable it.");
                return;
            }
            try {
                registry.enable(modName);
            } catch (ModRegistry.ModLoadException e) {
                error(player, "Failed to enable " + modName + ": " + e.getMessage());
                return;
            }
            store.setEnabled(modName, true);
            info(player, modName + " enabled.");
        }
        refresh(player);
    }

    private void rollback(Player player, String modName) {
        boolean ok = store.rollback(modName);
        if (!ok) {
            warn(player, "Can't roll back " + modName + " (already at v1 or unknown).");
            return;
        }
        ModStore.StoredMod mod = store.get(modName);
        int version = mod != null ? mod.currentVersion() : -1;
        if (rollbackAction != null) {
            rollbackAction.accept(player, modName);
            info(player, "Rolled back " + modName + " to v" + version + " and reapplied.");
        } else {
            info(player, "Rolled back " + modName + " to v" + version
                    + " - run /vibe enable " + modName + " to apply.");
        }
        refresh(player);
    }

    private void confirmedDelete(Player player, Session session, String modName) {
        long now = System.currentTimeMillis();
        if (modName.equals(session.pendingDelete) && now < session.pendingDeleteExpiresAt) {
            registry.unload(modName);
            store.delete(modName);
            session.pendingDelete = null;
            info(player, "Deleted " + modName + ".");
            refresh(player);
            return;
        }
        session.pendingDelete = modName;
        session.pendingDeleteExpiresAt = now + DELETE_CONFIRM_MS;
        warn(player, "Shift-right-click " + modName + " again within 5s to permanently delete it.");
    }

    /** Rebuilds the open GUI's contents for a player; reopens a fresh one if the mod count changed size class. */
    private void refresh(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        List<ModStore.StoredMod> mods = store.all();
        int idealSize = idealSize(mods.size());
        if (session.inventory.getSize() != idealSize) {
            open(player);
            return;
        }
        session.inventory.clear();
        List<String> slotMods = new ArrayList<>(Collections.nCopies(idealSize, null));
        for (int i = 0; i < mods.size(); i++) {
            session.inventory.setItem(i, buildItem(mods.get(i)));
            slotMods.set(i, mods.get(i).name());
        }
        session.slotMods = slotMods;
    }

    private ItemStack buildItem(ModStore.StoredMod mod) {
        boolean enabled = mod.enabled();
        ItemStack item = new ItemStack(enabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(mod.name(), NamedTextColor.WHITE));

        ModHandle handle = registry.get(mod.name());
        int listeners = handle != null ? handle.listenerCount() : 0;
        int tasks = handle != null ? handle.taskCount() : 0;

        List<Component> lore = new ArrayList<>();
        lore.add(plain(mod.description(), NamedTextColor.GRAY));
        lore.add(plain("v" + mod.currentVersion(), NamedTextColor.DARK_GRAY));
        lore.add(plain("listeners: " + listeners + "  tasks: " + tasks, NamedTextColor.DARK_GRAY));
        if (handle != null) {
            List<String> commands = handle.commandNames();
            List<String> actions = handle.actionNames();
            if (!commands.isEmpty()) {
                lore.add(plain("commands: " + String.join(", ", commands), NamedTextColor.DARK_GRAY));
            }
            if (!actions.isEmpty()) {
                lore.add(plain("actions: " + String.join(", ", actions), NamedTextColor.DARK_GRAY));
            }
        }
        lore.add(Component.empty());
        lore.add(plain("Left: toggle", NamedTextColor.YELLOW));
        lore.add(plain("Right: rollback", NamedTextColor.YELLOW));
        lore.add(plain("Shift-Right: delete", NamedTextColor.YELLOW));
        lore.add(plain("Drop (Q): export", NamedTextColor.YELLOW));
        meta.lore(lore);

        item.setItemMeta(meta);
        return item;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static int idealSize(int modCount) {
        int rows = (modCount + 8) / 9;
        return Math.max(27, rows * 9);
    }

    private static void info(Player player, String msg) {
        player.sendMessage(Component.text(msg, NamedTextColor.GREEN));
    }

    private static void warn(Player player, String msg) {
        player.sendMessage(Component.text(msg, NamedTextColor.YELLOW));
    }

    private static void error(Player player, String msg) {
        player.sendMessage(Component.text(msg, NamedTextColor.RED));
    }

    /** Per-player GUI state: the open inventory, its slot->modname map, and a pending delete confirmation. */
    private static final class Session {
        final Inventory inventory;
        List<String> slotMods;
        String pendingDelete;
        long pendingDeleteExpiresAt;

        Session(Inventory inventory, List<String> slotMods) {
            this.inventory = inventory;
            this.slotMods = slotMods;
        }
    }
}
