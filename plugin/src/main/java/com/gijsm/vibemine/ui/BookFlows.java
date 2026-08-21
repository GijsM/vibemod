package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Writable-book workflows: a prompt book for {@code /vibe make}-by-mail, an
 * edit book for change requests against an existing mod, and a config book
 * for editing a mod's tunable knobs as plain {@code key: value} text.
 *
 * <p>Every book carries its identity in its {@link org.bukkit.persistence.PersistentDataContainer}
 * ({@code book-kind}, {@code book-mod}, {@code book-mod-version}, {@code book-owner},
 * {@code book-id}) so capture works purely from the signed/saved item, even
 * across a server restart. The in-memory {@code sessions} map is a soft
 * cache of give-time hints only - it is never consulted to decide whether a
 * book is valid.
 *
 * <p><b>Known gap vs. the design notes:</b> comparing a book's captured
 * {@code book-mod-version} against the mod's live current version would
 * require a version supplier this class's frozen constructor does not
 * receive ({@code schemaLookup} only returns {@link ConfigEntry} rows, which
 * carry no version). The version is still stored in the PDC/session for a
 * future extension, but no mismatch warning is emitted today - only the
 * "does the mod still exist" check ({@code schemaLookup} returning
 * {@code null}) is implemented.
 */
public final class BookFlows implements Listener {

    /** Submits a free-text change request against an existing mod. */
    public interface EditSubmit {
        void submit(Player p, String modName, String changeRequest);
    }

    /** Applies parsed config values; returns one human-readable error string per rejected key. */
    public interface ConfigSubmit {
        List<String> apply(Player p, String modName, Map<String, String> values);
    }

    /** One config knob as shown to the player: its key, description, and current value. */
    public record ConfigEntry(String key, String description, String currentValue) {
    }

    private static final String KIND_PROMPT = "prompt";
    private static final String KIND_EDIT = "edit";
    private static final String KIND_CONFIG = "config";

    private static final int MAX_LINES_PER_PAGE = 13;
    private static final int MAX_CHARS_PER_PAGE = 230;
    private static final int WRAP_WIDTH = 24;
    private static final int MAX_KNOBS_PER_PAGE = 4;
    private static final int BLANK_PAGES = 3;

    private final Plugin plugin;
    private final BiConsumer<Player, String> onPromptSubmit;
    private final EditSubmit onEditSubmit;
    private final ConfigSubmit onConfigSubmit;
    private final Function<String, List<ConfigEntry>> schemaLookup;

    private final NamespacedKey keyKind;
    private final NamespacedKey keyMod;
    private final NamespacedKey keyModVersion;
    private final NamespacedKey keyOwner;
    private final NamespacedKey keyId;

    /** bookId -> give-time hint. Soft cache only; never authoritative. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(UUID owner, String kind, String modName, int modVersion) {
    }

    public BookFlows(Plugin plugin,
                      BiConsumer<Player, String> onPromptSubmit,
                      EditSubmit onEditSubmit,
                      ConfigSubmit onConfigSubmit,
                      Function<String, List<ConfigEntry>> schemaLookup) {
        this.plugin = plugin;
        this.onPromptSubmit = onPromptSubmit;
        this.onEditSubmit = onEditSubmit;
        this.onConfigSubmit = onConfigSubmit;
        this.schemaLookup = schemaLookup;
        this.keyKind = new NamespacedKey(plugin, "book-kind");
        this.keyMod = new NamespacedKey(plugin, "book-mod");
        this.keyModVersion = new NamespacedKey(plugin, "book-mod-version");
        this.keyOwner = new NamespacedKey(plugin, "book-owner");
        this.keyId = new NamespacedKey(plugin, "book-id");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Hands {@code p} a writable book for a fresh {@code /vibe make} prompt. */
    public void givePromptBook(Player p) {
        String instructions = "Write your mod idea below, then sign the book to submit it.\n\n"
                + "Be specific about what should happen and when. "
                + "Example: sheep occasionally fly for a few seconds after being hit.";
        List<String> pages = new ArrayList<>(paginateLines(wrapText(instructions, WRAP_WIDTH)));
        addBlankPages(pages);
        giveBook(p, pages, KIND_PROMPT, null, null);
    }

    /** Hands {@code p} a writable book pre-filled with {@code modName}'s manual, for a change request. */
    public void giveEditBook(Player p, String modName, int modVersion, String manualText, List<ConfigEntry> entries) {
        String manual = (manualText == null || manualText.isBlank()) ? "(no manual available)" : manualText;
        List<String> pages = new ArrayList<>(paginateLines(wrapText(manual, WRAP_WIDTH)));
        pages.addAll(paginateLines(wrapText(
                "== Changes: ==\n\nDescribe what you want changed, then sign.", WRAP_WIDTH)));
        addBlankPages(pages);
        giveBook(p, pages, KIND_EDIT, modName, modVersion);
    }

    /** Hands {@code p} a writable book pre-filled with {@code modName}'s current config values. */
    public void giveConfigBook(Player p, String modName, int modVersion, List<ConfigEntry> entries) {
        List<String> pages = paginateEntries(entries == null ? List.of() : entries);
        giveBook(p, pages, KIND_CONFIG, modName, modVersion);
    }

    // ---- event handling ----

    @EventHandler
    public void onEditBook(PlayerEditBookEvent event) {
        BookMeta meta = event.getNewBookMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String kind = pdc.get(keyKind, PersistentDataType.STRING);
        if (kind == null) {
            return; // not one of ours
        }

        Player player = event.getPlayer();
        String ownerStr = pdc.get(keyOwner, PersistentDataType.STRING);
        boolean ownerMismatch = ownerStr != null && !ownerStr.equals(player.getUniqueId().toString());

        String bookId = pdc.get(keyId, PersistentDataType.STRING);
        String modName = pdc.get(keyMod, PersistentDataType.STRING);
        Integer modVersion = pdc.get(keyModVersion, PersistentDataType.INTEGER);
        List<String> pages = new ArrayList<>(meta.getPages());
        String title = meta.hasTitle() ? meta.getTitle() : null;
        boolean signing = event.isSigning();

        if (ownerMismatch) {
            // Refuse politely; leave the event uncancelled so vanilla behaves as if
            // we were never involved.
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                    Component.text("This book isn't yours to submit.", NamedTextColor.RED)));
            return;
        }

        if (!signing) {
            if (KIND_CONFIG.equals(kind)) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> handleConfigCapture(player, modName, pages, bookId, false));
            }
            return; // prompt/edit Done: ignore, vanilla saves the draft
        }

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (kind) {
                case KIND_PROMPT -> handlePromptCapture(player, bookId, pages, title);
                case KIND_EDIT -> handleEditCapture(player, modName, bookId, pages);
                case KIND_CONFIG -> handleConfigCapture(player, modName, pages, bookId, true);
                default -> {
                    // unrecognised kind written by a future/older version; nothing to do
                }
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sessions.values().removeIf(session -> session.owner().equals(id));
    }

    // ---- capture handlers (always run on the main thread, next tick) ----

    private void handlePromptCapture(Player player, String bookId, List<String> pages, String title) {
        consumeBook(player, bookId);
        String text = joinPages(pages);
        if (title != null && !title.isBlank()) {
            text = "Name hint: " + title + "\n" + text;
        }
        onPromptSubmit.accept(player, text);
    }

    private void handleEditCapture(Player player, String modName, String bookId, List<String> pages) {
        if (modName == null) {
            consumeBook(player, bookId);
            player.sendMessage(Component.text("This edit book isn't linked to a mod.", NamedTextColor.RED));
            return;
        }
        List<ConfigEntry> entries = schemaLookup.apply(modName);
        if (entries == null) {
            consumeBook(player, bookId);
            player.sendMessage(Component.text(modName + " no longer exists - nothing to edit.",
                    NamedTextColor.RED));
            return;
        }
        consumeBook(player, bookId);
        onEditSubmit.submit(player, modName, joinPages(pages));
    }

    private void handleConfigCapture(Player player, String modName, List<String> pages, String bookId,
                                      boolean consume) {
        if (modName == null) {
            if (consume) {
                consumeBook(player, bookId);
            }
            player.sendMessage(Component.text("This config book isn't linked to a mod.", NamedTextColor.RED));
            return;
        }
        List<ConfigEntry> entries = schemaLookup.apply(modName);
        if (entries == null) {
            if (consume) {
                consumeBook(player, bookId);
            }
            player.sendMessage(Component.text(modName + " no longer exists - nothing to configure.",
                    NamedTextColor.RED));
            return;
        }

        Set<String> knownKeys = new LinkedHashSet<>();
        for (ConfigEntry entry : entries) {
            knownKeys.add(entry.key());
        }

        ConfigBookParser.ParseResult parsed = ConfigBookParser.parse(pages, knownKeys);
        List<String> applyErrors = onConfigSubmit.apply(player, modName, parsed.values());

        if (consume) {
            consumeBook(player, bookId);
        }

        sendConfigFeedback(player, modName, parsed, applyErrors);
    }

    private void sendConfigFeedback(Player player, String modName, ConfigBookParser.ParseResult parsed,
                                     List<String> applyErrors) {
        Set<String> erroredKeys = new LinkedHashSet<>();
        for (String err : applyErrors) {
            int idx = err.indexOf(':');
            if (idx > 0) {
                erroredKeys.add(err.substring(0, idx).trim().toLowerCase(Locale.ROOT));
            }
        }

        Component msg = Component.text("Config update for " + modName + ":", NamedTextColor.GRAY);
        for (Map.Entry<String, String> entry : parsed.values().entrySet()) {
            if (erroredKeys.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            msg = msg.append(Component.newline())
                    .append(Component.text("  " + entry.getKey() + " = " + entry.getValue(),
                            NamedTextColor.GREEN));
        }
        for (String err : parsed.errors()) {
            msg = msg.append(Component.newline()).append(Component.text("  " + err, NamedTextColor.RED));
        }
        for (String err : applyErrors) {
            msg = msg.append(Component.newline()).append(Component.text("  " + err, NamedTextColor.RED));
        }
        msg = msg.append(Component.newline()).append(Component.text("[fresh config book]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/vibe config " + modName))
                .hoverEvent(HoverEvent.showText(Component.text("Get an updated config book"))));

        player.sendMessage(msg);
    }

    /** Scans the player's inventory for the first item carrying {@code bookId} and removes one. */
    private void consumeBook(Player player, String bookId) {
        if (bookId == null) {
            return;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || !(stack.getItemMeta() instanceof BookMeta bookMeta)) {
                continue;
            }
            String id = bookMeta.getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
            if (!bookId.equals(id)) {
                continue;
            }
            if (stack.getAmount() <= 1) {
                player.getInventory().setItem(i, null);
            } else {
                ItemStack reduced = stack.clone();
                reduced.setAmount(stack.getAmount() - 1);
                player.getInventory().setItem(i, reduced);
            }
            break;
        }
        player.updateInventory();
        sessions.remove(bookId);
    }

    // ---- book construction ----

    private void giveBook(Player p, List<String> pages, String kind, String modName, Integer modVersion) {
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setPages(pages);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyKind, PersistentDataType.STRING, kind);
        pdc.set(keyOwner, PersistentDataType.STRING, p.getUniqueId().toString());
        String bookId = UUID.randomUUID().toString();
        pdc.set(keyId, PersistentDataType.STRING, bookId);
        if (modName != null) {
            pdc.set(keyMod, PersistentDataType.STRING, modName);
        }
        if (modVersion != null) {
            pdc.set(keyModVersion, PersistentDataType.INTEGER, modVersion);
        }
        book.setItemMeta(meta);

        sessions.put(bookId, new Session(p.getUniqueId(), kind, modName, modVersion == null ? -1 : modVersion));

        Map<Integer, ItemStack> overflow = p.getInventory().addItem(book);
        if (!overflow.isEmpty()) {
            p.getWorld().dropItemNaturally(p.getLocation(), book);
        }
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
    }

    private static void addBlankPages(List<String> pages) {
        for (int i = 0; i < BLANK_PAGES; i++) {
            pages.add("");
        }
    }

    private static String joinPages(List<String> pages) {
        StringBuilder sb = new StringBuilder();
        for (String page : pages) {
            if (page != null) {
                sb.append(page);
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Word-wraps {@code text} (honoring existing newlines as forced breaks) to {@code width} columns. */
    private static List<String> wrapText(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (line.length() == 0) {
                    line.append(word);
                } else if (line.length() + 1 + word.length() <= width) {
                    line.append(' ').append(word);
                } else {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** Packs already-wrapped {@code lines} into pages honoring the line/char budget. */
    private static List<String> paginateLines(List<String> lines) {
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        int lineCount = 0;
        for (String line : lines) {
            int addedLen = (lineCount == 0 ? 0 : 1) + line.length();
            boolean overflow = lineCount >= MAX_LINES_PER_PAGE || page.length() + addedLen > MAX_CHARS_PER_PAGE;
            if (overflow && page.length() > 0) {
                pages.add(page.toString());
                page = new StringBuilder();
                lineCount = 0;
            }
            if (lineCount > 0) {
                page.append('\n');
            }
            page.append(line);
            lineCount++;
        }
        if (page.length() > 0 || pages.isEmpty()) {
            pages.add(page.toString());
        }
        return pages;
    }

    /** Formats up to {@link #MAX_KNOBS_PER_PAGE} knobs per page as {@code "# description\nkey: value"}. */
    private static List<String> paginateEntries(List<ConfigEntry> entries) {
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += MAX_KNOBS_PER_PAGE) {
            List<ConfigEntry> chunk = entries.subList(i, Math.min(i + MAX_KNOBS_PER_PAGE, entries.size()));
            StringBuilder sb = new StringBuilder();
            for (ConfigEntry entry : chunk) {
                String description = entry.description() == null ? "" : entry.description();
                String value = entry.currentValue() == null ? "" : entry.currentValue();
                sb.append("# ").append(description).append('\n');
                sb.append(entry.key()).append(": ").append(value).append('\n');
            }
            String page = sb.toString();
            if (page.endsWith("\n")) {
                page = page.substring(0, page.length() - 1);
            }
            pages.add(page);
        }
        if (pages.isEmpty()) {
            pages.add("(no configurable settings)");
        }
        return pages;
    }
}
