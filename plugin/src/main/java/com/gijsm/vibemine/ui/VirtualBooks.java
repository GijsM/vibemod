package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.gijsm.vibemine.runtime.ModErrors;
import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.store.ModStore;

/**
 * Virtual (non-item) reading surfaces: manuals, source, and error reports open
 * straight into the player's book UI via {@link Player#openBook}, replacing
 * the old {@code ManualBook}/{@code SourceBook} item-giving classes - no
 * {@link org.bukkit.inventory.ItemStack} is ever created here. Console senders
 * (who have no book UI) instead get the equivalent content as a plain chat
 * dump, via the {@code dump*} overloads.
 */
public final class VirtualBooks {

    private static final int MAX_PAGE_CHARS = 256;
    private static final int MAX_LINES_PER_PAGE = 13;
    private static final int WRAP_WIDTH = 40;
    private static final int MAX_SOURCE_PAGES = 90;

    private static final Component AUTHOR = Component.text("VibeMod");

    private VirtualBooks() {
    }

    // ---- manual ----

    /** Opens a virtual book with {@code mod}'s player manual, verified facts, and config table. */
    public static void openManual(Player p, ModStore.StoredMod mod, ModHandle liveOrNull, Map<String, String> values) {
        openBook(p, mod.name(), manualPages(mod, liveOrNull, values));
    }

    /** Console equivalent of {@link #openManual}: a plain chat dump. */
    public static void dumpManual(CommandSender sender, ModStore.StoredMod mod, ModHandle liveOrNull,
                                   Map<String, String> values) {
        String manual = mod.manual() == null || mod.manual().isBlank() ? mod.description() : mod.manual();
        sender.sendMessage(Component.text(mod.name() + " - manual", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(manual, NamedTextColor.GRAY));
        sender.sendMessage(InstallCard.verifiedFooter(mod, liveOrNull, values));
        if (values != null && !values.isEmpty()) {
            sender.sendMessage(Component.text("Config:", NamedTextColor.GOLD));
            for (Map.Entry<String, String> e : new TreeMap<>(values).entrySet()) {
                sender.sendMessage(Component.text("  " + e.getKey() + " = " + e.getValue(), NamedTextColor.GRAY));
            }
        }
    }

    private static List<Component> manualPages(ModStore.StoredMod mod, ModHandle liveOrNull, Map<String, String> values) {
        List<Component> pages = new ArrayList<>();
        String manual = mod.manual() == null || mod.manual().isBlank() ? mod.description() : mod.manual();
        pages.addAll(proseToPages(mod.name() + " - manual", manual));
        pages.addAll(linesToPages("Verified facts", InstallCard.verifiedFactLines(mod, liveOrNull, values)));
        pages.addAll(linesToPages("Config", configTableLines(values)));
        return pages;
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

    // ---- source ----

    /** Opens a virtual book with {@code name}'s generated source. */
    public static void openSource(Player p, String name, Map<String, String> sources) {
        openBook(p, name, sourcePages(name, sources));
    }

    /** Console equivalent of {@link #openSource}: a plain chat dump. */
    public static void dumpSource(CommandSender sender, String name, Map<String, String> sources) {
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            sender.sendMessage(Component.text("== " + entry.getKey() + " ==", NamedTextColor.GOLD));
            for (String line : entry.getValue().split("\n", -1)) {
                sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
            }
        }
    }

    private static List<Component> sourcePages(String name, Map<String, String> sources) {
        List<Component> pages = new ArrayList<>();
        pages.add(sourceFirstPage(name, sources));
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            pages.addAll(splitSourceIntoPages(entry.getKey(), entry.getValue()));
        }
        if (pages.size() > MAX_SOURCE_PAGES) {
            pages = new ArrayList<>(pages.subList(0, MAX_SOURCE_PAGES - 1));
            pages.add(Component.text("... truncated"));
        }
        return pages;
    }

    private static Component sourceFirstPage(String name, Map<String, String> sources) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("\n\nFiles:\n");
        for (String fqcn : sources.keySet()) {
            sb.append("- ").append(fqcn).append('\n');
        }
        return Component.text(sb.toString());
    }

    private static List<Component> splitSourceIntoPages(String fqcn, String source) {
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

    // ---- errors ----

    /** Opens a virtual book of {@code name}'s recent deduped error records, one section per error. */
    public static void openErrors(Player p, String name, List<ModErrors.ErrorRecord> records) {
        openBook(p, name + " errors", errorPages(name, records));
    }

    /** Console equivalent of {@link #openErrors}: {@code report} is {@link ModErrors#report}'s output, dumped as-is. */
    public static void dumpErrors(CommandSender sender, String report) {
        for (String line : report.split("\n", -1)) {
            sender.sendMessage(Component.text(line, line.startsWith("==") ? NamedTextColor.GOLD : NamedTextColor.GRAY));
        }
    }

    private static List<Component> errorPages(String name, List<ModErrors.ErrorRecord> records) {
        List<String> lines = new ArrayList<>();
        lines.add(name + " - errors");
        lines.add("");
        if (records == null || records.isEmpty()) {
            lines.add("(no errors recorded)");
        }
        for (ModErrors.ErrorRecord r : records == null ? List.<ModErrors.ErrorRecord>of() : records) {
            lines.add(r.count() + "x " + r.exceptionClass() + ": " + r.message());
            lines.add("  at " + r.topFrame() + " (" + r.where() + ", last " + relativeTime(r.lastSeen()) + ")");
            for (String frame : r.stack() == null ? List.<String>of() : r.stack()) {
                lines.add("  " + frame);
            }
            lines.add("");
        }
        return linesToPages("", lines);
    }

    /** Coarse "Ns/Nm/Nh/Nd ago" relative-time formatting for a {@code lastSeen} epoch millis. */
    private static String relativeTime(long epochMillis) {
        long deltaMs = System.currentTimeMillis() - epochMillis;
        if (deltaMs < 0) {
            deltaMs = 0;
        }
        long seconds = deltaMs / 1000;
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = hours / 24;
        return days + "d ago";
    }

    // ---- shared pagination/opening ----

    private static void openBook(Player p, String title, List<Component> pages) {
        Book book = Book.book(Component.text(title), AUTHOR, pages.isEmpty() ? List.of(Component.text("")) : pages);
        p.openBook(book);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
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
        if (heading != null && !heading.isEmpty()) {
            lines.add(heading);
            lines.add("");
        }
        lines.addAll(body);
        return paginate(lines);
    }

    /** Groups pre-wrapped lines into pages of at most {@link #MAX_LINES_PER_PAGE} lines / {@link #MAX_PAGE_CHARS} chars. */
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
