package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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

import com.gijsm.vibemine.runtime.ModErrors;
import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.runtime.ModRegistry;
import com.gijsm.vibemine.store.ModStore;

/**
 * The playful, colorful chest-inventory GUI: ONLY the paginated mod list (the
 * general plugin overview). It shows one glinting item per mod, colored and
 * dotted by live state; left-clicking one opens that mod's native
 * {@link ModHubDialog} via the injected {@code openHub} callback (the old
 * chest DETAIL screen is gone — every per-mod action now lives in the hub),
 * and the admin-only [⚙ Settings] entry in the bottom border opens the native
 * {@link SettingsDialog} form via {@code openSettings}. Borders and filler
 * panes are tinted by aggregate state (orange-ish when anything is degraded)
 * so the screen never shows a bare slot.
 */
public final class ModBrowserGui implements Listener {

    private static final Component LIST_TITLE = Component.text("⬡ VibeMod");

    private final Plugin plugin;
    private final ModRegistry registry;
    private final ModStore store;
    private final ModErrors errors;
    private final Consumer<Player> openSettings;
    private final BiConsumer<Player, String> openHub;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /**
     * The {@code configs}/{@code debug} params (and the old callbacks-bundle record)
     * the old chest DETAIL screen consumed were dropped along with that screen — the
     * two callbacks left are the two things the list itself can open.
     */
    public ModBrowserGui(Plugin plugin, ModRegistry registry, ModStore store, ModErrors errors,
                          Consumer<Player> openSettings, BiConsumer<Player, String> openHub) {
        this.plugin = plugin;
        this.registry = registry;
        this.store = store;
        this.errors = errors;
        this.openSettings = openSettings;
        this.openHub = openHub;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ---- opening the list ----

    /** Opens the main mod list for {@code p}, resuming the page a previous session was on. */
    public void open(Player p) {
        List<ModStore.StoredMod> mods = store.all();
        int size = mods.size() > singlePageCapacity() ? 6 * 9 : idealListSize(mods.size());

        Inventory inv = plugin.getServer().createInventory(null, size, LIST_TITLE);
        Session previous = sessions.get(p.getUniqueId());
        Session session = new Session(inv);
        session.listPage = previous != null ? previous.listPage : 0; // reopening resumes the page
        sessions.put(p.getUniqueId(), session);
        populateList(p, session);
        p.openInventory(inv);
    }

    /**
     * (Re)fills the LIST inventory in place — populate-without-reopen: the arrow
     * buttons call this instead of {@link #open}.
     */
    private void populateList(Player p, Session session) {
        Inventory inv = session.inventory;
        inv.clear();

        List<ModStore.StoredMod> mods = store.all();
        boolean admin = p.hasPermission("vibe.admin");
        int size = inv.getSize();
        int perPage = size - 18; // minus the top and bottom border rows
        int totalPages = Math.max(1, (mods.size() + perPage - 1) / perPage);
        session.listPage = Math.max(0, Math.min(session.listPage, totalPages - 1)); // clamp when mods shrink

        fillBorder(inv, size, anyDegradedLive());

        List<String> slotMods = new ArrayList<>(Collections.nCopies(size, null));
        int from = session.listPage * perPage;
        int slot = firstContentSlot(size);
        for (ModStore.StoredMod mod : mods.subList(from, Math.min(mods.size(), from + perPage))) {
            slot = nextContentSlot(slot, size);
            if (slot < 0) {
                break;
            }
            inv.setItem(slot, buildListItem(mod));
            slotMods.set(slot, mod.name());
            slot++;
        }
        session.slotMods = slotMods;

        session.settingsSlot = -1;
        if (admin) {
            session.settingsSlot = size - 5;
            inv.setItem(session.settingsSlot, buildSettingsEntryItem());
        }

        // Pagination furniture, only when more than one page exists: arrows sit
        // symmetrically around the ⚙ Settings slot, the page indicator bottom-left.
        session.prevSlot = -1;
        session.nextSlot = -1;
        if (totalPages > 1) {
            inv.setItem(size - 9, buildPageIndicatorItem(session.listPage + 1, totalPages, mods.size()));
            if (session.listPage > 0) {
                session.prevSlot = size - 6;
                inv.setItem(session.prevSlot, buildPageArrowItem(false, session.listPage));
            }
            if (session.listPage < totalPages - 1) {
                session.nextSlot = size - 4;
                inv.setItem(session.nextSlot, buildPageArrowItem(true, session.listPage + 2));
            }
        }
        fillerRow(inv, 9, size - 10); // no bare slots among the unused content rows
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
        onListClick(player, session, event.getSlot());
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

    private void onListClick(Player player, Session session, int slot) {
        if (slot == session.settingsSlot) {
            click(player);
            openSettings.accept(player);
            return;
        }
        if (slot == session.prevSlot || slot == session.nextSlot) {
            click(player);
            session.listPage += slot == session.nextSlot ? 1 : -1;
            populateList(player, session); // in place, no reopen
            return;
        }
        if (slot < 0 || slot >= session.slotMods.size()) {
            return;
        }
        String modName = session.slotMods.get(slot);
        if (modName != null) {
            click(player);
            // The hub dialog shows next tick, so opening from a click handler is safe.
            openHub.accept(player, modName);
        }
    }

    // ---- items ----

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
     * Package-private: {@link ModHubDialog} renders the same icon as its item body.
     */
    static Material resolveIcon(String icon) {
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
        meta.lore(List.of(plain("Model, thinking, timeouts, watchdog, reload", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildPageArrowItem(boolean next, int targetPage) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(next ? "[next →]" : "[← prev]", NamedTextColor.YELLOW));
        meta.lore(List.of(plain("Go to page " + targetPage, NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildPageIndicatorItem(int page, int totalPages, int modCount) {
        ItemStack item = new ItemStack(Material.MAP);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain("Page " + page + "/" + totalPages, NamedTextColor.AQUA));
        meta.lore(List.of(plain(modCount + " mods total", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    /** Single-page sizing for collections of up to {@link #singlePageCapacity()} mods. */
    private static int idealListSize(int modCount) {
        // Layout = top border row + N full-width content rows + bottom border row
        // (the settings button lives IN the bottom border, costing no content slot).
        int contentRows = Math.max(1, (modCount + 8) / 9);
        int rows = Math.min(6, contentRows + 2);
        return rows * 9;
    }

    /** Most mods a single page can show: 4 content rows in the biggest (6-row) GUI. */
    private static int singlePageCapacity() {
        return 6 * 9 - 18;
    }

    private boolean anyDegradedLive() {
        for (ModHandle h : registry.mods()) {
            if (h.degraded()) {
                return true;
            }
        }
        return false;
    }

    // ---- shared helpers ----

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

    /** Per-player GUI state: the open list inventory and its paging bookkeeping. */
    private static final class Session {
        final Inventory inventory;

        List<String> slotMods;
        int settingsSlot = -1;
        int listPage = 0;
        int prevSlot = -1;
        int nextSlot = -1;

        Session(Inventory inventory) {
            this.inventory = inventory;
        }
    }
}
