package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

/**
 * Hands a player a written book containing a mod's generated source, paginated
 * to fit Minecraft's book UI.
 */
public final class SourceBook {

    private static final int MAX_PAGE_CHARS = 256;
    private static final int MAX_LINES_PER_PAGE = 13;
    private static final int MAX_PAGES = 90;

    private SourceBook() {
    }

    /** Builds and gives a written book of {@code sources} named after the mod. */
    public static void give(Player p, String modName, Map<String, String> sources) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.title(Component.text(modName));
        meta.author(Component.text("VibeMine"));

        List<Component> pages = new ArrayList<>();
        pages.add(firstPage(modName, sources));
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            pages.addAll(splitIntoPages(entry.getKey(), entry.getValue()));
        }

        if (pages.size() > MAX_PAGES) {
            pages = new ArrayList<>(pages.subList(0, MAX_PAGES - 1));
            pages.add(Component.text("... truncated"));
        }

        meta.pages(pages);
        book.setItemMeta(meta);

        Map<Integer, ItemStack> overflow = p.getInventory().addItem(book);
        if (!overflow.isEmpty()) {
            p.getWorld().dropItemNaturally(p.getLocation(), book);
        }
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
    }

    private static Component firstPage(String modName, Map<String, String> sources) {
        StringBuilder sb = new StringBuilder();
        sb.append(modName).append("\n\nFiles:\n");
        for (String fqcn : sources.keySet()) {
            sb.append("- ").append(fqcn).append('\n');
        }
        return Component.text(sb.toString());
    }

    private static List<Component> splitIntoPages(String fqcn, String source) {
        List<Component> pages = new ArrayList<>();
        String[] lines = source.split("\n", -1);

        StringBuilder current = new StringBuilder();
        int lineCount = 0;
        boolean firstPageOfFile = true;

        for (String line : lines) {
            String candidateLine = line + "\n";
            boolean wouldOverflow = current.length() + candidateLine.length() > MAX_PAGE_CHARS
                    || lineCount + 1 > MAX_LINES_PER_PAGE;
            if (wouldOverflow && current.length() > 0) {
                pages.add(Component.text(current.toString()));
                current = new StringBuilder();
                lineCount = 0;
                firstPageOfFile = false;
            }
            if (firstPageOfFile && current.length() == 0) {
                current.append("// ").append(fqcn).append('\n');
                lineCount++;
                firstPageOfFile = false;
            }
            current.append(candidateLine);
            lineCount++;
        }
        if (current.length() > 0) {
            pages.add(Component.text(current.toString()));
        }
        return pages;
    }
}
