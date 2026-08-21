package com.gijsm.vibemine.ui;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

import com.gijsm.vibemine.gen.GeneratedProject;
import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.runtime.ModRegistry;
import com.gijsm.vibemine.store.ModConfigs;
import com.gijsm.vibemine.store.ModStore;

/**
 * The chest-inventory GUI for browsing and managing mods. The main list shows
 * one item per mod; left-clicking one opens a detail panel with per-knob
 * controls (booleans toggle, choices cycle, numerics step, text hands out a
 * config book) and lifecycle buttons. A settings page (visible only to
 * {@code vibe.admin}) exposes the LLM model picker and a config.yml reload.
 */
public final class ModBrowserGui implements Listener {

    private enum Screen { LIST, DETAIL, SETTINGS }

    private static final long DELETE_CONFIRM_MS = 5000L;
    private static final Component LIST_TITLE = Component.text("VibeMine Mods");
    private static final Component SETTINGS_TITLE = Component.text("VibeMine Settings");

    private static final List<String> KNOWN_MODELS = List.of(
            "anthropic/claude-sonnet-5", "anthropic/claude-opus-5", "anthropic/claude-haiku-5",
            "openai/gpt-5", "google/gemini-3-pro");

    private static final int DETAIL_SIZE = 54;
    private static final int DETAIL_HEADER_SLOT = 4;
    private static final int DETAIL_KNOB_START = 9;
    private static final int DETAIL_KNOB_END = 44; // inclusive
    private static final int SLOT_MANUAL = 45;
    private static final int SLOT_SOURCE = 46;
    private static final int SLOT_CONFIG_BOOK = 47;
    private static final int SLOT_TOGGLE = 48;
    private static final int SLOT_ROLLBACK = 49;
    private static final int SLOT_EXPORT = 50;
    private static final int SLOT_DELETE = 51;
    private static final int SLOT_BACK = 53;

    private static final int SETTINGS_SIZE = 27;
    private static final int SETTINGS_MODEL_SLOT = 11;
    private static final int SETTINGS_WATCHDOG_SLOT = 13;
    private static final int SETTINGS_RETRY_SLOT = 15;
    private static final int SETTINGS_RELOAD_SLOT = 22;
    private static final int SETTINGS_BACK_SLOT = 26;

    private final Plugin plugin;
    private final ModRegistry registry;
    private final ModStore store;
    private final ModConfigs configs;
    private final GuiCallbacks cb;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public ModBrowserGui(Plugin plugin, ModRegistry registry, ModStore store, ModConfigs configs, GuiCallbacks cb) {
        this.plugin = plugin;
        this.registry = registry;
        this.store = store;
        this.configs = configs;
        this.cb = cb;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ---- opening screens ----

    /** Opens the main mod list for {@code p}. */
    public void open(Player p) {
        List<ModStore.StoredMod> mods = store.all();
        boolean admin = p.hasPermission("vibe.admin");
        int size = idealListSize(mods.size(), admin);
        Inventory inv = plugin.getServer().createInventory(null, size, LIST_TITLE);
        List<String> slotMods = new ArrayList<>(Collections.nCopies(size, null));
        for (int i = 0; i < mods.size() && i < size; i++) {
            ModStore.StoredMod mod = mods.get(i);
            inv.setItem(i, buildListItem(mod));
            slotMods.set(i, mod.name());
        }
        int settingsSlot = -1;
        if (admin) {
            settingsSlot = size - 1;
            inv.setItem(settingsSlot, buildSettingsEntryItem());
        }
        Session session = new Session(Screen.LIST, inv);
        session.slotMods = slotMods;
        session.settingsSlot = settingsSlot;
        sessions.put(p.getUniqueId(), session);
        p.openInventory(inv);
    }

    /** Opens the detail panel for one mod. */
    public void openDetail(Player p, String modName) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(p, "Unknown mod: " + modName);
            open(p);
            return;
        }
        Inventory inv = plugin.getServer().createInventory(null, DETAIL_SIZE, detailTitle(mod));
        Session session = new Session(Screen.DETAIL, inv);
        session.modName = modName;
        sessions.put(p.getUniqueId(), session);
        populateDetail(session, mod);
        p.openInventory(inv);
    }

    /** Opens the ops-only settings page. */
    public void openSettings(Player p) {
        if (!p.hasPermission("vibe.admin")) {
            error(p, "You don't have permission for that.");
            return;
        }
        Inventory inv = plugin.getServer().createInventory(null, SETTINGS_SIZE, SETTINGS_TITLE);
        Session session = new Session(Screen.SETTINGS, inv);
        sessions.put(p.getUniqueId(), session);
        populateSettings(inv);
        p.openInventory(inv);
    }

    // ---- click routing ----

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
            return;
        }
        event.setCancelled(true);

        switch (session.screen) {
            case LIST -> onListClick(player, session, event.getSlot());
            case DETAIL -> onDetailClick(player, session, event.getSlot(), event.getClick());
            case SETTINGS -> onSettingsClick(player, session, event.getSlot(), event.getClick());
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

    // ---- LIST screen ----

    private void onListClick(Player player, Session session, int slot) {
        if (slot == session.settingsSlot) {
            openSettings(player);
            return;
        }
        if (slot < 0 || slot >= session.slotMods.size()) {
            return;
        }
        String modName = session.slotMods.get(slot);
        if (modName != null) {
            openDetail(player, modName);
        }
    }

    private ItemStack buildListItem(ModStore.StoredMod mod) {
        ModHandle handle = registry.get(mod.name());
        boolean enabled = handle != null ? handle.enabled() : mod.enabled();
        ItemStack item = new ItemStack(enabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(mod.name(), NamedTextColor.WHITE));

        List<Component> lore = new ArrayList<>(Text.wrap(mod.description(), Text.DEFAULT_WIDTH, NamedTextColor.GRAY));
        lore.add(plain("v" + mod.currentVersion() + " · click for details", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildSettingsEntryItem() {
        ItemStack item = new ItemStack(Material.COMMAND_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain("Settings", NamedTextColor.GOLD));
        meta.lore(List.of(plain("Model, watchdog, reload", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private static int idealListSize(int modCount, boolean reserveSettings) {
        int needed = modCount + (reserveSettings ? 1 : 0);
        int rows = Math.max(3, (needed + 8) / 9);
        return rows * 9;
    }

    // ---- DETAIL screen ----

    private Component detailTitle(ModStore.StoredMod mod) {
        return Component.text("Mod: " + mod.name());
    }

    private void populateDetail(Session session, ModStore.StoredMod mod) {
        Inventory inv = session.inventory;
        inv.clear();
        session.knobSlots = new LinkedHashMap<>();

        ModHandle live = registry.get(mod.name());
        boolean enabled = live != null ? live.enabled() : mod.enabled();

        inv.setItem(DETAIL_HEADER_SLOT, buildHeaderItem(mod, enabled));

        List<GeneratedProject.ConfigKnob> schema = configs.schema(mod.name());
        if (schema.isEmpty()) {
            ItemStack none = new ItemStack(Material.BARRIER);
            ItemMeta meta = none.getItemMeta();
            meta.displayName(plain("No configurable settings", NamedTextColor.GRAY));
            none.setItemMeta(meta);
            inv.setItem(DETAIL_KNOB_START, none);
        } else {
            Map<String, String> values = configs.values(mod.name());
            int slot = DETAIL_KNOB_START;
            for (GeneratedProject.ConfigKnob knob : schema) {
                if (slot > DETAIL_KNOB_END) {
                    break;
                }
                inv.setItem(slot, buildKnobItem(knob, values.get(knob.key())));
                session.knobSlots.put(slot, knob);
                slot++;
            }
        }

        inv.setItem(SLOT_MANUAL, button(Material.WRITTEN_BOOK, "[manual]", NamedTextColor.AQUA,
                "Give a manual book for this mod"));
        inv.setItem(SLOT_SOURCE, button(Material.BOOK, "[source]", NamedTextColor.AQUA,
                "Give the source book for this mod"));
        inv.setItem(SLOT_CONFIG_BOOK, button(Material.WRITABLE_BOOK, "[config book]", NamedTextColor.AQUA,
                "Give a writable config book"));
        inv.setItem(SLOT_TOGGLE, button(enabled ? Material.REDSTONE : Material.LIME_DYE,
                enabled ? "[disable]" : "[enable]", enabled ? NamedTextColor.RED : NamedTextColor.GREEN,
                enabled ? "Disable this mod" : "Enable this mod"));
        inv.setItem(SLOT_ROLLBACK, button(Material.CLOCK, "[rollback]", NamedTextColor.YELLOW,
                "Revert to the previous version"));
        inv.setItem(SLOT_EXPORT, button(Material.CHEST, "[export]", NamedTextColor.AQUA,
                "Export a standalone plugin jar"));
        inv.setItem(SLOT_DELETE, deleteButton(session));
        inv.setItem(SLOT_BACK, button(Material.ARROW, "[back]", NamedTextColor.GRAY, "Back to the mod list"));
    }

    private ItemStack buildHeaderItem(ModStore.StoredMod mod, boolean enabled) {
        ItemStack item = new ItemStack(enabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(mod.name(), NamedTextColor.WHITE));

        List<Component> lore = new ArrayList<>(Text.wrap(mod.description(), Text.DEFAULT_WIDTH, NamedTextColor.GRAY));
        if (mod.usage() != null && !mod.usage().isBlank()) {
            lore.addAll(Text.wrap("Try: " + mod.usage(), Text.DEFAULT_WIDTH, NamedTextColor.YELLOW));
        }
        lore.add(plain(enabled ? "State: ON" : "State: OFF", enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        lore.add(plain("v" + mod.currentVersion(), NamedTextColor.DARK_GRAY));
        lore.add(plain("by " + mod.creator(), NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildKnobItem(GeneratedProject.ConfigKnob knob, String rawValue) {
        String value = rawValue != null ? rawValue : knob.def();
        Material icon = switch (knob.type()) {
            case "boolean" -> "true".equalsIgnoreCase(value) ? Material.LIME_DYE : Material.GRAY_DYE;
            case "choice" -> Material.COMPASS;
            case "integer", "decimal" -> Material.COMPARATOR;
            default -> Material.WRITABLE_BOOK;
        };
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(knob.key() + " = " + value, NamedTextColor.WHITE));

        List<Component> lore = new ArrayList<>();
        if (knob.description() != null && !knob.description().isBlank()) {
            lore.addAll(Text.wrap(knob.description(), Text.DEFAULT_WIDTH, NamedTextColor.GRAY));
        }
        lore.add(plain("type: " + knob.type(), NamedTextColor.DARK_GRAY));
        switch (knob.type()) {
            case "boolean" -> lore.add(plain("Click to toggle", NamedTextColor.YELLOW));
            case "choice" -> {
                if (knob.choices() != null) {
                    lore.add(plain("choices: " + String.join(", ", knob.choices()), NamedTextColor.DARK_GRAY));
                }
                lore.add(plain("Click to cycle", NamedTextColor.YELLOW));
            }
            case "integer", "decimal" -> {
                double step = knob.step() != null ? knob.step() : 1.0;
                lore.add(plain("Left: -" + trim(step) + "  Right: +" + trim(step), NamedTextColor.YELLOW));
                lore.add(plain("Shift: x10", NamedTextColor.YELLOW));
            }
            default -> lore.add(plain("Click for a config book", NamedTextColor.YELLOW));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack deleteButton(Session session) {
        boolean armed = session.pendingDelete && System.currentTimeMillis() < session.pendingDeleteExpiresAt;
        ItemStack item = new ItemStack(Material.TNT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(armed ? "[delete] click again to confirm!" : "[delete]", NamedTextColor.RED));
        meta.lore(List.of(plain(armed ? "Confirm within 5s" : "Shift-click twice within 5s to delete",
                NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private void onDetailClick(Player player, Session session, int slot, ClickType click) {
        String modName = session.modName;
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(player, "That mod no longer exists.");
            open(player);
            return;
        }

        if (session.knobSlots.containsKey(slot)) {
            handleKnobClick(player, session, mod, session.knobSlots.get(slot), click);
            return;
        }

        if (slot != SLOT_DELETE) {
            session.pendingDelete = false;
        }

        switch (slot) {
            case SLOT_MANUAL -> cb.giveManualBook().accept(player, modName);
            case SLOT_SOURCE -> cb.giveSourceBook().accept(player, modName);
            case SLOT_CONFIG_BOOK -> cb.giveConfigBook().accept(player, modName);
            case SLOT_TOGGLE -> {
                toggleEnabled(player, modName);
                refreshDetail(player);
            }
            case SLOT_ROLLBACK -> {
                rollback(player, modName);
                refreshDetail(player);
            }
            case SLOT_EXPORT -> cb.export().accept(player, modName);
            case SLOT_DELETE -> handleDeleteClick(player, session, modName);
            case SLOT_BACK -> open(player);
            default -> {
            }
        }
    }

    private void handleDeleteClick(Player player, Session session, String modName) {
        long now = System.currentTimeMillis();
        if (session.pendingDelete && now < session.pendingDeleteExpiresAt) {
            registry.unload(modName);
            store.delete(modName);
            info(player, "Deleted " + modName + ".");
            open(player);
            return;
        }
        session.pendingDelete = true;
        session.pendingDeleteExpiresAt = now + DELETE_CONFIRM_MS;
        warn(player, "Click [delete] again within 5s to permanently delete " + modName + ".");
        refreshDetail(player);
    }

    private void handleKnobClick(Player player, Session session, ModStore.StoredMod mod,
                                 GeneratedProject.ConfigKnob knob, ClickType click) {
        Map<String, String> values = configs.values(mod.name());
        String current = values.getOrDefault(knob.key(), knob.def());
        String newValue;
        switch (knob.type()) {
            case "boolean" -> newValue = Boolean.toString(!parseBool(current));
            case "choice" -> newValue = nextChoice(knob, current);
            case "integer", "decimal" -> newValue = steppedNumber(knob, current, click);
            default -> {
                cb.giveConfigBook().accept(player, mod.name());
                return;
            }
        }
        try {
            configs.set(mod.name(), knob.key(), newValue);
        } catch (IllegalArgumentException ex) {
            error(player, ex.getMessage());
        }
        refreshDetail(player);
    }

    private static boolean parseBool(String s) {
        return "true".equalsIgnoreCase(s);
    }

    private static String nextChoice(GeneratedProject.ConfigKnob knob, String current) {
        List<String> choices = knob.choices();
        if (choices == null || choices.isEmpty()) {
            return current;
        }
        int idx = choices.indexOf(current);
        int next = (idx + 1) % choices.size();
        return choices.get(next);
    }

    private static String steppedNumber(GeneratedProject.ConfigKnob knob, String current, ClickType click) {
        double step = knob.step() != null ? knob.step() : 1.0;
        double min = knob.min() != null ? knob.min() : 0.0;
        double max = knob.max() != null ? knob.max() : 1e9;
        double multiplier = (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) ? 10.0 : 1.0;
        double sign = (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) ? 1.0 : -1.0;

        double value;
        try {
            value = Double.parseDouble(current);
        } catch (NumberFormatException e) {
            value = 0.0;
        }
        value += sign * step * multiplier;
        value = Math.max(min, Math.min(max, value));
        return "integer".equals(knob.type()) ? Long.toString(Math.round(value)) : trim(value);
    }

    private static String trim(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private void refreshDetail(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.screen != Screen.DETAIL) {
            return;
        }
        ModStore.StoredMod mod = store.get(session.modName);
        if (mod == null) {
            open(player);
            return;
        }
        populateDetail(session, mod);
    }

    private void toggleEnabled(Player player, String modName) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(player, "Unknown mod: " + modName);
            return;
        }
        boolean enabled = registry.get(modName) != null ? registry.get(modName).enabled() : mod.enabled();
        if (enabled) {
            registry.disable(modName);
            store.setEnabled(modName, false);
            info(player, modName + " disabled.");
        } else {
            if (registry.get(modName) == null) {
                cb.applyVersion().accept(player, modName);
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
    }

    private void rollback(Player player, String modName) {
        boolean ok = store.rollback(modName);
        if (!ok) {
            warn(player, "Can't roll back " + modName + " (already at v1 or unknown).");
            return;
        }
        cb.applyVersion().accept(player, modName);
        ModStore.StoredMod mod = store.get(modName);
        int version = mod != null ? mod.currentVersion() : -1;
        info(player, "Rolled back " + modName + " to v" + version + ".");
    }

    // ---- SETTINGS screen ----

    private void populateSettings(Inventory inv) {
        ItemStack model = new ItemStack(Material.NETHER_STAR);
        ItemMeta modelMeta = model.getItemMeta();
        modelMeta.displayName(plain("Model: " + cb.getModel().get(), NamedTextColor.WHITE));
        modelMeta.lore(List.of(plain("Click to cycle the LLM model", NamedTextColor.YELLOW)));
        model.setItemMeta(modelMeta);
        inv.setItem(SETTINGS_MODEL_SLOT, model);

        inv.setItem(SETTINGS_WATCHDOG_SLOT, displayOnly("Watchdog budgets",
                "single-invocation-ms / per-second-budget-ms"));
        inv.setItem(SETTINGS_RETRY_SLOT, displayOnly("Max retries",
                "generation.max-retries"));

        inv.setItem(SETTINGS_RELOAD_SLOT, button(Material.EMERALD, "[reload]", NamedTextColor.GREEN,
                "Re-read config.yml"));
        inv.setItem(SETTINGS_BACK_SLOT, button(Material.ARROW, "[back]", NamedTextColor.GRAY, "Back to the mod list"));
    }

    private ItemStack displayOnly(String name, String key) {
        ItemStack item = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(name, NamedTextColor.GRAY));
        List<Component> lore = new ArrayList<>();
        lore.add(plain(key, NamedTextColor.DARK_GRAY));
        lore.addAll(Text.wrap("Display only in this iteration - edit config.yml, then click [reload].",
                Text.DEFAULT_WIDTH, NamedTextColor.YELLOW));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void onSettingsClick(Player player, Session session, int slot, ClickType click) {
        if (!player.hasPermission("vibe.admin")) {
            return;
        }
        switch (slot) {
            case SETTINGS_MODEL_SLOT -> {
                String current = cb.getModel().get();
                int idx = KNOWN_MODELS.indexOf(current);
                String next = KNOWN_MODELS.get((idx + 1) % KNOWN_MODELS.size());
                cb.setModel().accept(next);
                populateSettings(session.inventory);
            }
            case SETTINGS_RELOAD_SLOT -> {
                cb.reloadConfig().run();
                info(player, "Config reloaded.");
                populateSettings(session.inventory);
            }
            case SETTINGS_BACK_SLOT -> open(player);
            default -> {
            }
        }
    }

    // ---- shared helpers ----

    private static ItemStack button(Material material, String label, NamedTextColor color, String hoverLine) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(label, color));
        meta.lore(List.of(plain(hoverLine, NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
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

    /** Per-player GUI state: which screen is open, in what inventory, and any screen-specific bookkeeping. */
    private static final class Session {
        final Screen screen;
        final Inventory inventory;

        // LIST
        List<String> slotMods;
        int settingsSlot = -1;

        // DETAIL
        String modName;
        Map<Integer, GeneratedProject.ConfigKnob> knobSlots;
        boolean pendingDelete;
        long pendingDeleteExpiresAt;

        Session(Screen screen, Inventory inventory) {
            this.screen = screen;
            this.inventory = inventory;
        }
    }
}
