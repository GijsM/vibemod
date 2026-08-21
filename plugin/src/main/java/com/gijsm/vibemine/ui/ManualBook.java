package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.store.ModStore;

/**
 * Hands a player a written book containing a mod's player-facing manual: the
 * model-written prose (falling back to the description for older/v1 mods
 * that never got one), a "Verified facts" page mirroring
 * {@link InstallCard#verifiedFooter}, and a config table of current knob
 * values. Follows {@link SourceBook}'s give/drop/sound pattern.
 */
public final class ManualBook {

    private static final int MAX_PAGE_CHARS = 256;
    private static final int MAX_LINES_PER_PAGE = 13;
    private static final int WRAP_WIDTH = 40;

    private ManualBook() {
    }

    /** Builds and gives a written book manual for {@code mod}, using live introspection when available. */
    public static void give(Player p, ModStore.StoredMod mod, ModHandle liveOrNull, Map<String, String> values) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.title(Component.text(mod.name()));
        meta.author(Component.text("VibeMine"));

        List<Component> pages = new ArrayList<>();

        String manual = mod.manual() == null || mod.manual().isBlank() ? mod.description() : mod.manual();
        pages.addAll(proseToPages(mod.name() + " - manual", manual));

        List<String> facts = InstallCard.verifiedFactLines(mod, liveOrNull, values);
        pages.addAll(linesToPages("Verified facts", facts));

        pages.addAll(linesToPages("Config", configTableLines(values)));

        meta.pages(pages);
        book.setItemMeta(meta);

        Map<Integer, ItemStack> overflow = p.getInventory().addItem(book);
        if (!overflow.isEmpty()) {
            p.getWorld().dropItemNaturally(p.getLocation(), book);
        }
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
    }

    private static List<String> configTableLines(Map<String, String> values) {
        List<String> lines = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            lines.add("(no configurable settings)");
            return lines;
        }
        for (Map.Entry<String, String> e : new TreeMap<>(values).entrySet()) {
            lines.add(e.getKey() + " = " + e.getValue());
        }
        return lines;
    }

    private static List<Component> proseToPages(String heading, String prose) {
        List<String> lines = new ArrayList<>();
        lines.add(heading);
        lines.add("");
        lines.addAll(Text.wrapLines(prose, WRAP_WIDTH));
        return paginate(lines);
    }

    private static List<Component> linesToPages(String heading, List<String> body) {
        List<String> lines = new ArrayList<>();
        lines.add(heading);
        lines.add("");
        lines.addAll(body);
        return paginate(lines);
    }

    /** Groups pre-wrapped lines into pages of at most {@link #MAX_LINES_PER_PAGE} lines and {@link #MAX_PAGE_CHARS} chars. */
    private static List<Component> paginate(List<String> lines) {
        List<Component> pages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int lineCount = 0;
        for (String line : lines) {
            int extra = line.length() + (current.length() > 0 ? 1 : 0);
            if (current.length() > 0 && (lineCount + 1 > MAX_LINES_PER_PAGE || current.length() + extra > MAX_PAGE_CHARS)) {
                pages.add(Component.text(current.toString()));
                current.setLength(0);
                lineCount = 0;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
            lineCount++;
        }
        if (current.length() > 0) {
            pages.add(Component.text(current.toString()));
        }
        if (pages.isEmpty()) {
            pages.add(Component.text(""));
        }
        return pages;
    }
}
