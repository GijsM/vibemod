package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemine.runtime.DebugEcho;
import com.gijsm.vibemine.runtime.ModErrors;
import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.runtime.ModRegistry;
import com.gijsm.vibemine.store.ModConfigs;
import com.gijsm.vibemine.store.ModStore;

/**
 * The playful, colorful chest-inventory GUI for browsing and managing mods.
 * The main list shows one glinting item per mod, colored and dotted by live
 * state; left-clicking one opens a detail panel whose buttons are expressive
 * items (anvil reload, blaze powder fix, lever debug toggle, comparator
 * configure, etc.) rather than per-knob steppers - all knob editing now goes
 * through {@link Dialogs} via {@link GuiCallbacks#configure}. Borders and
 * filler panes are tinted by aggregate/mod state (orange-ish when anything is
 * degraded) so no screen ever shows a bare slot.
 */
public final class ModBrowserGui implements Listener {

    private enum Screen { LIST, DETAIL, SETTINGS }

    private static final long DELETE_CONFIRM_MS = 5000L;
    private static final Component LIST_TITLE = Component.text("⬡ VibeMod");
    private static final Component SETTINGS_TITLE = Component.text("⬡ VibeMod Settings");

    private static final List<String> KNOWN_MODELS = List.of(
            "anthropic/claude-sonnet-5", "anthropic/claude-opus-5", "anthropic/claude-haiku-5",
            "openai/gpt-5", "google/gemini-3-pro");

    private static final int DETAIL_SIZE = 54;
    private static final int SLOT_RELOAD = 10;
    private static final int SLOT_FIX = 11;
    private static final int SLOT_DEBUG = 12;
    private static final int SLOT_HEADER = 13;
    private static final int SLOT_CONFIGURE = 14;
    private static final int SLOT_EDIT = 15;
    private static final int SLOT_TOGGLE = 16;
    private static final int SLOT_MANUAL = 19;
    private static final int SLOT_SOURCE = 20;
    private static final int SLOT_ERRORS = 21;
    private static final int SLOT_ROLLBACK = 22;
    private static final int SLOT_EXPORT = 23;
    private static final int SLOT_DELETE = 24;
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
    private final ModErrors errors;
    private final DebugEcho debug;
    private final GuiCallbacks cb;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public ModBrowserGui(Plugin plugin, ModRegistry registry, ModStore store, ModConfigs configs,
                          ModErrors errors, DebugEcho debug, GuiCallbacks cb) {
        this.plugin = plugin;
        this.registry = registry;
        this.store = store;
        this.configs = configs;
        this.errors = errors;
        this.debug = debug;
        this.cb = cb;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ---- opening screens ----

    /** Opens the main mod list for {@code p}. */
    public void open(Player p) {
        List<ModStore.StoredMod> mods = store.all();
        boolean admin = p.hasPermission("vibe.admin");
        int size = idealListSize(mods.size(), admin);
        boolean anyDegraded = anyDegradedLive();

        Inventory inv = plugin.getServer().createInventory(null, size, LIST_TITLE);
        fillBorder(inv, size, anyDegraded);

        List<String> slotMods = new ArrayList<>(Collections.nCopies(size, null));
        int slot = firstContentSlot(size);
        for (ModStore.StoredMod mod : mods) {
            slot = nextContentSlot(slot, size);
            if (slot < 0) {
                break;
            }
            inv.setItem(slot, buildListItem(mod));
            slotMods.set(slot, mod.name());
            slot++;
        }

        int settingsSlot = -1;
        if (admin) {
            settingsSlot = size - 5;
            inv.setItem(settingsSlot, buildSettingsEntryItem());
        }
        fillerRow(inv, 9, size - 10); // no bare slots among the unused content rows

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
        populateDetail(p, session, mod);
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
            case DETAIL -> onDetailClick(player, session, event.getSlot());
            case SETTINGS -> onSettingsClick(player, session, event.getSlot());
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
            click(player);
            openSettings(player);
            return;
        }
        if (slot < 0 || slot >= session.slotMods.size()) {
            return;
        }
        String modName = session.slotMods.get(slot);
        if (modName != null) {
            click(player);
            openDetail(player, modName);
        }
    }

    private ItemStack buildListItem(ModStore.StoredMod mod) {
        ModHandle handle = registry.get(mod.name());
        boolean enabled = handle != null ? handle.enabled() : mod.enabled();
        boolean degraded = handle != null && handle.degraded();
        NamedTextColor nameColor = degraded ? NamedTextColor.GOLD : (enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY);

        ItemStack item = new ItemStack(resolveIcon(mod.icon()));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(mod.name(), nameColor));
        meta.setEnchantmentGlintOverride(enabled ? Boolean.TRUE : Boolean.FALSE);

        List<Component> lore = new ArrayList<>(Text.wrap(mod.description(), Text.DEFAULT_WIDTH, NamedTextColor.GRAY));
        lore.add(stateDotLine(enabled, degraded, handle));
        lore.add(plain("v" + mod.currentVersion() + "  ▶ click to open", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** {@code "● running" / "● degraded (n errors)" / "● off"}. */
    private Component stateDotLine(boolean enabled, boolean degraded, ModHandle handle) {
        Component dot = Style.dot(enabled, degraded);
        if (degraded) {
            int n = handle != null ? errors.distinctCount(handle.name()) : 0;
            return dot.append(plain(" degraded (" + n + " errors)", NamedTextColor.GOLD));
        }
        return dot.append(plain(enabled ? " running" : " off", enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY));
    }

    /**
     * Resolves a mod's icon Material from its stored {@code icon} name, falling back to
     * {@link Material#PAPER} when the name is blank, unrecognized, or not a real item.
     */
    private static Material resolveIcon(String icon) {
        if (icon != null && !icon.isBlank()) {
            Material m = Material.matchMaterial(icon);
            if (m != null && m.isItem()) {
                return m;
            }
        }
        return Material.PAPER;
    }

    private ItemStack buildSettingsEntryItem() {
        ItemStack item = new ItemStack(Material.COMMAND_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain("⚙ Settings", NamedTextColor.GOLD));
        meta.lore(List.of(plain("Model, watchdog, reload", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private static int idealListSize(int modCount, boolean reserveSettings) {
        int needed = modCount + (reserveSettings ? 1 : 0) + 9; // reserve a border row
        int rows = Math.max(3, (needed + 8) / 9);
        return Math.min(54, rows * 9);
    }

    private boolean anyDegradedLive() {
        for (ModHandle h : registry.mods()) {
            if (h.degraded()) {
                return true;
            }
        }
        return false;
    }

    // ---- DETAIL screen ----

    private Component detailTitle(ModStore.StoredMod mod) {
        return Component.text("⬡ " + mod.name());
    }

    private void populateDetail(Player player, Session session, ModStore.StoredMod mod) {
        Inventory inv = session.inventory;
        inv.clear();

        ModHandle live = registry.get(mod.name());
        boolean enabled = live != null ? live.enabled() : mod.enabled();
        boolean degraded = live != null && live.degraded();

        // Fix-success jingle: this mod was degraded last time we drew this panel, and now isn't.
        if (session.knownDegraded && !degraded && enabled) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
        session.knownDegraded = degraded;

        fillBorder(inv, DETAIL_SIZE, degraded);

        inv.setItem(SLOT_HEADER, buildHeaderItem(mod, enabled, degraded, live));

        inv.setItem(SLOT_RELOAD, button(Material.ANVIL, "[⟳ reload]", Style.ACTION,
                "Recompile and apply the current stored version"));
        if (degraded) {
            inv.setItem(SLOT_FIX, button(Material.BLAZE_POWDER, "[🔧 fix]", Style.WARN,
                    "Send recent errors to the model for a repair round"));
        }
        inv.setItem(SLOT_DEBUG, debugLeverItem(mod.name()));
        inv.setItem(SLOT_CONFIGURE, button(Material.COMPARATOR, "[⚙ configure]", Style.ACTION,
                "Open the config dialog"));
        inv.setItem(SLOT_EDIT, button(Material.WRITABLE_BOOK, "[✎ edit]", Style.ACTION,
                "Open the edit-request dialog"));
        inv.setItem(SLOT_TOGGLE, button(enabled ? Material.SLIME_BALL : Material.GRAY_DYE,
                enabled ? "[disable]" : "[enable]", enabled ? Style.ERROR : Style.OK,
                enabled ? "Disable this mod" : "Enable this mod"));

        inv.setItem(SLOT_MANUAL, button(Material.BOOK, "[📖 manual]", Style.ACTION, "Open the manual"));
        inv.setItem(SLOT_SOURCE, button(Material.PAPER, "[<> source]", Style.ACTION, "Open the source"));
        inv.setItem(SLOT_ERRORS, button(Material.OBSERVER, "[⚠ errors]", Style.WARN, "Open the error log"));
        inv.setItem(SLOT_ROLLBACK, button(Material.CLOCK, "[rollback]", NamedTextColor.YELLOW,
                "Revert to the previous version"));
        inv.setItem(SLOT_EXPORT, button(Material.CHEST, "[export]", Style.ACTION, "Export a standalone plugin jar"));
        inv.setItem(SLOT_DELETE, deleteButton(session));
        inv.setItem(SLOT_BACK, button(Material.ARROW, "[← back]", NamedTextColor.GRAY, "Back to the mod list"));

        fillerRow(inv, 9, DETAIL_SIZE - 10); // no bare slots among the interior rows
    }

    private ItemStack buildHeaderItem(ModStore.StoredMod mod, boolean enabled, boolean degraded, ModHandle live) {
        ItemStack item = new ItemStack(resolveIcon(mod.icon()));
        ItemMeta meta = item.getItemMeta();
        NamedTextColor nameColor = degraded ? NamedTextColor.GOLD : (enabled ? NamedTextColor.WHITE : NamedTextColor.GRAY);
        meta.displayName(plain(mod.name(), nameColor));
        meta.setEnchantmentGlintOverride(enabled ? Boolean.TRUE : Boolean.FALSE);

        List<Component> lore = new ArrayList<>(Text.wrap(mod.description(), Text.DEFAULT_WIDTH, NamedTextColor.GRAY));
        if (mod.usage() != null && !mod.usage().isBlank()) {
            lore.addAll(Text.wrap("Try: " + mod.usage(), Text.DEFAULT_WIDTH, NamedTextColor.YELLOW));
        }
        lore.add(stateDotLine(enabled, degraded, live));
        lore.add(plain("v" + mod.currentVersion(), NamedTextColor.DARK_GRAY));
        lore.add(plain("by " + mod.creator(), NamedTextColor.DARK_GRAY));
        int knobs = configs.schema(mod.name()).size();
        lore.add(plain(knobs == 0 ? "No configurable settings" : knobs + " config knob(s)", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack debugLeverItem(String modName) {
        boolean on = debug.enabled(modName);
        ItemStack item = new ItemStack(Material.LEVER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain("[debug: " + (on ? "ON" : "OFF") + "]", on ? Style.OK : NamedTextColor.GRAY));
        meta.lore(List.of(plain("Echo ctx.log() + exceptions to ops chat", NamedTextColor.GRAY),
                plain("Click to toggle", NamedTextColor.YELLOW)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack deleteButton(Session session) {
        boolean armed = session.pendingDelete && System.currentTimeMillis() < session.pendingDeleteExpiresAt;
        ItemStack item = new ItemStack(Material.TNT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(armed ? "[✖ delete] click again to confirm!" : "[✖ delete]", Style.ERROR));
        meta.lore(List.of(plain(armed ? "Confirm within 5s" : "Click twice within 5s to delete",
                NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private void onDetailClick(Player player, Session session, int slot) {
        String modName = session.modName;
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(player, "That mod no longer exists.");
            open(player);
            return;
        }

        if (slot != SLOT_DELETE) {
            session.pendingDelete = false;
        }

        switch (slot) {
            case SLOT_RELOAD -> {
                click(player);
                cb.applyVersion().accept(player, modName);
                refreshDetail(player);
            }
            case SLOT_FIX -> {
                click(player);
                cb.fix().accept(player, modName);
            }
            case SLOT_DEBUG -> {
                click(player);
                boolean now = debug.toggle(modName);
                info(player, modName + " debug echo " + (now ? "ON" : "OFF") + ".");
                refreshDetail(player);
            }
            case SLOT_CONFIGURE -> {
                click(player);
                cb.configure().accept(player, modName);
            }
            case SLOT_EDIT -> {
                click(player);
                cb.editMod().accept(player, modName);
            }
            case SLOT_TOGGLE -> {
                click(player);
                toggleEnabled(player, modName);
                refreshDetail(player);
            }
            case SLOT_MANUAL -> {
                click(player);
                cb.openManual().accept(player, modName);
            }
            case SLOT_SOURCE -> {
                click(player);
                cb.openSource().accept(player, modName);
            }
            case SLOT_ERRORS -> {
                click(player);
                cb.openErrors().accept(player, modName);
            }
            case SLOT_ROLLBACK -> {
                click(player);
                rollback(player, modName);
                refreshDetail(player);
            }
            case SLOT_EXPORT -> {
                click(player);
                cb.export().accept(player, modName);
            }
            case SLOT_DELETE -> handleDeleteClick(player, session, modName);
            case SLOT_BACK -> {
                click(player);
                open(player);
            }
            default -> {
            }
        }
    }

    private void handleDeleteClick(Player player, Session session, String modName) {
        long now = System.currentTimeMillis();
        click(player);
        if (session.pendingDelete && now < session.pendingDeleteExpiresAt) {
            registry.unload(modName);
            store.delete(modName);
            info(player, "Deleted " + modName + ".");
            open(player);
            return;
        }
        session.pendingDelete = true;
        session.pendingDeleteExpiresAt = now + DELETE_CONFIRM_MS;
        warn(player, "Click [✖ delete] again within 5s to permanently delete " + modName + ".");
        refreshDetail(player);
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
        populateDetail(player, session, mod);
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
        fillBorder(inv, SETTINGS_SIZE, anyDegradedLive());

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

        inv.setItem(SETTINGS_RELOAD_SLOT, button(Material.EMERALD, "[reload]", Style.OK, "Re-read config.yml"));
        inv.setItem(SETTINGS_BACK_SLOT, button(Material.ARROW, "[← back]", NamedTextColor.GRAY, "Back to the mod list"));
        fillerRow(inv, 9, SETTINGS_SIZE - 10);
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

    private void onSettingsClick(Player player, Session session, int slot) {
        if (!player.hasPermission("vibe.admin")) {
            return;
        }
        switch (slot) {
            case SETTINGS_MODEL_SLOT -> {
                click(player);
                String current = cb.getModel().get();
                int idx = KNOWN_MODELS.indexOf(current);
                String next = KNOWN_MODELS.get((idx + 1) % KNOWN_MODELS.size());
                cb.setModel().accept(next);
                populateSettings(session.inventory);
            }
            case SETTINGS_RELOAD_SLOT -> {
                click(player);
                cb.reloadConfig().run();
                info(player, "Config reloaded.");
                populateSettings(session.inventory);
            }
            case SETTINGS_BACK_SLOT -> {
                click(player);
                open(player);
            }
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

    /** Colors the top and bottom rows with a glass pane tinted by state; degraded skews orange. */
    private static void fillBorder(Inventory inv, int size, boolean degraded) {
        Material pane = degraded ? Material.ORANGE_STAINED_GLASS_PANE : Material.LIGHT_BLUE_STAINED_GLASS_PANE;
        ItemStack filler = fillerPane(pane);
        int rows = size / 9;
        for (int col = 0; col < 9; col++) {
            inv.setItem(col, filler);
            if (rows > 1) {
                inv.setItem(size - 9 + col, filler);
            }
        }
    }

    /** Fills every currently-empty slot in {@code [from, to]} (inclusive) with a plain decorative pane. */
    private static void fillerRow(Inventory inv, int from, int to) {
        ItemStack filler = fillerPane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = from; i <= to && i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }
    }

    private static ItemStack fillerPane(Material paneMaterial) {
        ItemStack item = new ItemStack(paneMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static int firstContentSlot(int size) {
        return 9; // skip the top border row
    }

    /** Next slot after {@code from} that isn't in the bottom border row, or -1 if the screen is full. */
    private static int nextContentSlot(int from, int size) {
        int bottomRowStart = size - 9;
        if (from >= bottomRowStart) {
            return -1;
        }
        return from;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
    }

    private static void info(Player player, String msg) {
        player.sendMessage(Style.ok(msg));
    }

    private static void warn(Player player, String msg) {
        player.sendMessage(Style.warn(msg));
    }

    private static void error(Player player, String msg) {
        player.sendMessage(Style.err(msg));
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
        boolean pendingDelete;
        long pendingDeleteExpiresAt;
        boolean knownDegraded;

        Session(Screen screen, Inventory inventory) {
            this.screen = screen;
            this.inventory = inventory;
        }
    }
}
