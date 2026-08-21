package com.gijsm.vibemine.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemine.gen.GeneratedProject;
import com.gijsm.vibemine.gen.ModGenerator;
import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.runtime.ModRegistry;
import com.gijsm.vibemine.store.JarExporter;
import com.gijsm.vibemine.store.ModConfigs;
import com.gijsm.vibemine.store.ModStore;
import com.gijsm.vibemine.ui.BookFlows;
import com.gijsm.vibemine.ui.ChatMode;
import com.gijsm.vibemine.ui.InstallCard;
import com.gijsm.vibemine.ui.ManualBook;
import com.gijsm.vibemine.ui.ModBrowserGui;
import com.gijsm.vibemine.ui.Progress;
import com.gijsm.vibemine.ui.SourceBook;

/**
 * The {@code /vibe} command: generate, edit, tune, and manage in-game mods.
 * Read-only subcommands (list/source/info/manual/help) require
 * {@code vibe.use}; everything else requires {@code vibe.admin}.
 */
public final class VibeCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of(
            "make", "edit", "again", "list", "source", "info", "manual", "config", "set", "book",
            "rollback", "enable", "disable", "delete", "export", "do", "model", "chat", "gui",
            "reload", "panic", "help");
    private static final Set<String> READ_ONLY = Set.of("list", "source", "info", "manual", "help");
    private static final Set<String> MOD_ARG_SUBS = Set.of(
            "edit", "again", "source", "info", "manual", "config", "set", "book",
            "rollback", "enable", "disable", "delete", "export", "do");
    private static final List<String> KNOWN_MODELS = List.of(
            "anthropic/claude-sonnet-5", "anthropic/claude-opus-5", "anthropic/claude-haiku-5",
            "openai/gpt-5", "google/gemini-3-pro");

    private final Plugin plugin;
    private final ModGenerator generator;
    private final ModRegistry registry;
    private final ModStore store;
    private final ModConfigs configs;
    private final JarExporter exporter;
    private final ModBrowserGui gui;
    private final ChatMode chatMode;
    private final BookFlows books;
    private final Supplier<String> getModel;
    private final Consumer<String> setModel;
    private final BiConsumer<CommandSender, String> applyVersion;
    private final Runnable reloadConfig;

    public VibeCommand(Plugin plugin, ModGenerator generator, ModRegistry registry, ModStore store,
                        ModConfigs configs, JarExporter exporter, ModBrowserGui gui, ChatMode chatMode,
                        BookFlows books, Supplier<String> getModel, Consumer<String> setModel,
                        BiConsumer<CommandSender, String> applyVersion, Runnable reloadConfig) {
        this.plugin = plugin;
        this.generator = generator;
        this.registry = registry;
        this.store = store;
        this.configs = configs;
        this.exporter = exporter;
        this.gui = gui;
        this.chatMode = chatMode;
        this.books = books;
        this.getModel = getModel;
        this.setModel = setModel;
        this.applyVersion = applyVersion;
        this.reloadConfig = reloadConfig;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        if (!SUBCOMMANDS.contains(sub)) {
            error(sender, "Unknown subcommand '" + sub + "'. Try /vibe help.");
            return true;
        }
        String requiredPermission = READ_ONLY.contains(sub) ? "vibe.use" : "vibe.admin";
        if (!sender.hasPermission(requiredPermission)) {
            error(sender, "You don't have permission for that.");
            return true;
        }

        switch (sub) {
            case "make" -> cmdMake(sender, rest);
            case "edit" -> cmdEdit(sender, rest);
            case "again" -> cmdAgain(sender, rest);
            case "list" -> cmdList(sender);
            case "source" -> cmdSource(sender, rest);
            case "info" -> cmdInfo(sender, rest);
            case "manual" -> cmdManual(sender, rest);
            case "config" -> cmdConfig(sender, rest);
            case "set" -> cmdSet(sender, rest);
            case "book" -> cmdBook(sender, rest);
            case "rollback" -> cmdRollback(sender, rest);
            case "enable" -> cmdEnable(sender, rest);
            case "disable" -> cmdDisable(sender, rest);
            case "delete" -> cmdDelete(sender, rest);
            case "export" -> cmdExport(sender, rest);
            case "do" -> cmdDo(sender, rest);
            case "model" -> cmdModel(sender, rest);
            case "chat" -> cmdChat(sender);
            case "gui" -> cmdGui(sender);
            case "reload" -> cmdReload(sender);
            case "panic" -> cmdPanic(sender);
            case "help" -> sendHelp(sender);
            default -> error(sender, "Unknown subcommand '" + sub + "'. Try /vibe help.");
        }
        return true;
    }

    // ---- generation ----

    private void cmdMake(CommandSender sender, String[] args) {
        if (args.length == 0) {
            error(sender, "Usage: /vibe make <description>");
            return;
        }
        String prompt = String.join(" ", args);
        Progress progress = new Progress(plugin, sender, "vibe: " + truncate(prompt, 40));
        generator.make(prompt, sender.getName(), bridge(progress))
                .whenComplete((result, ex) -> onGenerationDone(sender, progress, result, ex));
    }

    private void cmdEdit(CommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "Usage: /vibe edit <mod> <description>");
            return;
        }
        String modName = args[0];
        String prompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Progress progress = new Progress(plugin, sender, "vibe: " + truncate(prompt, 40));
        generator.edit(modName, prompt, sender.getName(), bridge(progress))
                .whenComplete((result, ex) -> onGenerationDone(sender, progress, result, ex));
    }

    private void cmdAgain(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe again <mod>");
            return;
        }
        String modName = args[0];
        Progress progress = new Progress(plugin, sender, "vibe: remake " + modName);
        generator.remake(modName, sender.getName(), bridge(progress))
                .whenComplete((result, ex) -> onGenerationDone(sender, progress, result, ex));
    }

    private ModGenerator.ProgressListener bridge(Progress progress) {
        return new ModGenerator.ProgressListener() {
            @Override
            public void phase(String label) {
                progress.phase(label);
            }

            @Override
            public void detail(String line) {
                progress.detail(line);
            }
        };
    }

    private void onGenerationDone(CommandSender sender, Progress progress, ModGenerator.Result result, Throwable ex) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                progress.fail("Generation failed: " + rootMessage(ex));
                return;
            }
            if (result.success()) {
                String retrySuffix = result.retries() > 0 ? " (self-healed x" + result.retries() + ")" : "";
                progress.succeed("✔ " + result.modName() + " v" + result.version() + " installed" + retrySuffix);
                ModStore.StoredMod mod = result.modName() != null ? store.get(result.modName()) : null;
                if (mod != null) {
                    ModHandle live = registry.get(mod.name());
                    sender.sendMessage(InstallCard.build(mod, live));
                }
            } else {
                progress.fail(result.message());
            }
        });
    }

    // ---- listing / source / info / manual ----

    private void cmdList(CommandSender sender) {
        List<ModStore.StoredMod> mods = store.all();
        if (mods.isEmpty()) {
            sender.sendMessage(Component.text("No mods yet - try /vibe make <description>.", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("VibeMine mods:", NamedTextColor.GOLD));
        for (ModStore.StoredMod mod : mods) {
            // Live registry state wins over the stored flag (e.g. a watchdog trip).
            ModHandle live = registry.get(mod.name());
            boolean enabled = live != null ? live.enabled() : mod.enabled();
            NamedTextColor color = enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY;
            Component hover = Component.text(mod.description()).append(Component.newline())
                    .append(Component.text("v" + mod.currentVersion() + " by " + mod.creator(), NamedTextColor.GRAY));
            if (mod.usage() != null && !mod.usage().isBlank()) {
                hover = hover.append(Component.newline())
                        .append(Component.text("Try: " + mod.usage(), NamedTextColor.YELLOW));
            }
            Component line = Component.text(mod.name(), color)
                    .hoverEvent(HoverEvent.showText(hover))
                    .clickEvent(ClickEvent.runCommand("/vibe info " + mod.name()))
                    .append(Component.text(enabled ? " [on]" : " [off]", NamedTextColor.DARK_GRAY));
            sender.sendMessage(line);
        }
    }

    private void cmdSource(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe source <mod>");
            return;
        }
        String modName = args[0];
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(sender, "Unknown mod: " + modName);
            return;
        }
        Map<String, String> sources = store.sources(modName, mod.currentVersion());
        if (sender instanceof Player player) {
            SourceBook.give(player, modName, sources);
        } else {
            for (Map.Entry<String, String> entry : sources.entrySet()) {
                sender.sendMessage(Component.text("== " + entry.getKey() + " ==", NamedTextColor.GOLD));
                for (String line : entry.getValue().split("\n", -1)) {
                    sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
                }
            }
        }
    }

    private void cmdInfo(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe info <mod>");
            return;
        }
        String modName = args[0];
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(sender, "Unknown mod: " + modName);
            return;
        }
        ModHandle live = registry.get(modName);
        sender.sendMessage(InstallCard.build(mod, live));
        sender.sendMessage(InstallCard.verifiedFooter(mod, live, configs.values(modName)));
    }

    private void cmdManual(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe manual <mod>");
            return;
        }
        String modName = args[0];
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(sender, "Unknown mod: " + modName);
            return;
        }
        ModHandle live = registry.get(modName);
        Map<String, String> values = configs.values(modName);
        if (sender instanceof Player player) {
            ManualBook.give(player, mod, live, values);
            return;
        }
        String manual = mod.manual() == null || mod.manual().isBlank() ? mod.description() : mod.manual();
        sender.sendMessage(Component.text(modName + " - manual", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(manual, NamedTextColor.GRAY));
        sender.sendMessage(InstallCard.verifiedFooter(mod, live, values));
        if (!values.isEmpty()) {
            sender.sendMessage(Component.text("Config:", NamedTextColor.GOLD));
            for (Map.Entry<String, String> e : values.entrySet()) {
                sender.sendMessage(Component.text("  " + e.getKey() + " = " + e.getValue(), NamedTextColor.GRAY));
            }
        }
    }

    private void cmdConfig(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can use config books.");
            return;
        }
        if (args.length < 1) {
            error(sender, "Usage: /vibe config <mod>");
            return;
        }
        String modName = args[0];
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(sender, "Unknown mod: " + modName);
            return;
        }
        books.giveConfigBook(player, modName, mod.currentVersion(), configEntries(modName));
    }

    private void cmdSet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            error(sender, "Usage: /vibe set <mod> <key> <value>");
            return;
        }
        String modName = args[0];
        String key = args[1];
        String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        String oldValue = configs.values(modName).get(key);
        try {
            configs.set(modName, key, value);
        } catch (IllegalArgumentException e) {
            error(sender, e.getMessage());
            return;
        }
        String newValue = configs.values(modName).get(key);
        sender.sendMessage(Component.text(modName + "." + key + ": ", NamedTextColor.GRAY)
                .append(Component.text(oldValue != null ? oldValue : "(default)", NamedTextColor.RED))
                .append(Component.text(" -> ", NamedTextColor.GRAY))
                .append(Component.text(newValue != null ? newValue : "", NamedTextColor.GREEN)));
    }

    private void cmdBook(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can use books.");
            return;
        }
        if (args.length == 0) {
            books.givePromptBook(player);
            return;
        }
        String modName = args[0];
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(sender, "Unknown mod: " + modName);
            return;
        }
        String manual = mod.manual() == null || mod.manual().isBlank() ? mod.description() : mod.manual();
        books.giveEditBook(player, modName, mod.currentVersion(), manual, configEntries(modName));
    }

    private List<BookFlows.ConfigEntry> configEntries(String modName) {
        List<BookFlows.ConfigEntry> entries = new ArrayList<>();
        Map<String, String> values = configs.values(modName);
        for (GeneratedProject.ConfigKnob knob : configs.schema(modName)) {
            String current = values.getOrDefault(knob.key(), knob.def());
            entries.add(new BookFlows.ConfigEntry(knob.key(), knob.description(), current));
        }
        return entries;
    }

    // ---- lifecycle management ----

    private void cmdRollback(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe rollback <mod>");
            return;
        }
        String modName = args[0];
        if (!store.rollback(modName)) {
            error(sender, "Can't roll back " + modName + " (already at v1 or unknown).");
            return;
        }
        applyVersion.accept(sender, modName);
        ModStore.StoredMod mod = store.get(modName);
        int version = mod != null ? mod.currentVersion() : -1;
        sender.sendMessage(Component.text("Rolled back " + modName + " to v" + version + ".", NamedTextColor.GREEN));
    }

    private void cmdEnable(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe enable <mod>");
            return;
        }
        String modName = args[0];
        if (store.get(modName) == null) {
            error(sender, "Unknown mod: " + modName);
            return;
        }
        if (registry.get(modName) != null) {
            try {
                registry.enable(modName);
            } catch (ModRegistry.ModLoadException e) {
                error(sender, "Failed to enable " + modName + ": " + rootMessage(e));
                return;
            }
            store.setEnabled(modName, true);
            sender.sendMessage(Component.text(modName + " enabled.", NamedTextColor.GREEN));
        } else {
            applyVersion.accept(sender, modName);
        }
    }

    private void cmdDisable(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe disable <mod>");
            return;
        }
        String modName = args[0];
        boolean wasRunning = registry.disable(modName);
        store.setEnabled(modName, false);
        if (wasRunning) {
            sender.sendMessage(Component.text(modName + " disabled.", NamedTextColor.YELLOW));
        } else {
            sender.sendMessage(Component.text(modName + " was already disabled or not loaded.", NamedTextColor.GRAY));
        }
    }

    private void cmdDelete(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe delete <mod>");
            return;
        }
        String modName = args[0];
        if (store.get(modName) == null) {
            error(sender, "Unknown mod: " + modName);
            return;
        }
        registry.unload(modName);
        store.delete(modName);
        sender.sendMessage(Component.text("Deleted " + modName + ".", NamedTextColor.YELLOW));
    }

    private void cmdExport(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe export <mod>");
            return;
        }
        String modName = args[0];
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(sender, "Unknown mod: " + modName);
            return;
        }
        sender.sendMessage(Component.text("Exporting " + modName + "...", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, String> sources = store.sources(modName, mod.currentVersion());
                Path outDir = plugin.getDataFolder().toPath().resolve("exports");
                Path jar = exporter.export(mod, sources, outDir);
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(Component.text("Exported to " + jar, NamedTextColor.GREEN)));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> error(sender, "Export failed: " + rootMessage(e)));
            }
        });
    }

    private void cmdDo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "Usage: /vibe do <mod> <action> [args...]");
            return;
        }
        String modName = args[0];
        String action = args[1];
        String[] actionArgs = Arrays.copyOfRange(args, 2, args.length);
        boolean ok = registry.runAction(modName, action, sender, actionArgs);
        if (!ok) {
            error(sender, "No such mod/action: " + modName + " " + action);
        }
    }

    // ---- misc ----

    private void cmdModel(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Current model: " + getModel.get(), NamedTextColor.GRAY));
            return;
        }
        setModel.accept(args[0]);
        sender.sendMessage(Component.text("Model set to " + args[0], NamedTextColor.GREEN));
    }

    private void cmdChat(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can use chat mode.");
            return;
        }
        boolean on = chatMode.toggle(player);
        if (on) {
            player.sendMessage(Component.text(
                    "Chat mode ON - just type to generate/edit mods. Type 'off' to stop.", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Chat mode off.", NamedTextColor.GRAY));
        }
    }

    private void cmdGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can open the mod browser.");
            return;
        }
        gui.open(player);
    }

    private void cmdReload(CommandSender sender) {
        reloadConfig.run();
        sender.sendMessage(Component.text(
                "Config reloaded (model/timeout, watchdog budgets, top-level commands, retry count re-read).",
                NamedTextColor.GREEN));
    }

    private void cmdPanic(CommandSender sender) {
        registry.panic();
        Component msg = Component.text("[VibeMine] PANIC triggered by " + sender.getName()
                + " - all mods disabled.", NamedTextColor.RED);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(msg);
        }
        Bukkit.getConsoleSender().sendMessage(msg);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("VibeMine - " + "turn prompts into mods:", NamedTextColor.GOLD));
        sender.sendMessage(helpLine("/vibe make <description>", "generate a new mod"));
        sender.sendMessage(helpLine("/vibe edit <mod> <description>", "revise a mod"));
        sender.sendMessage(helpLine("/vibe again <mod>", "rerun a mod's last prompt"));
        sender.sendMessage(helpLine("/vibe list", "list stored mods"));
        sender.sendMessage(helpLine("/vibe source <mod>", "view a mod's source"));
        sender.sendMessage(helpLine("/vibe info <mod>", "show the install card + verified facts"));
        sender.sendMessage(helpLine("/vibe manual <mod>", "get the player manual"));
        sender.sendMessage(helpLine("/vibe config <mod>", "get a writable config book"));
        sender.sendMessage(helpLine("/vibe set <mod> <key> <value>", "set one config knob"));
        sender.sendMessage(helpLine("/vibe book [mod]", "get a prompt book, or an edit book for a mod"));
        sender.sendMessage(helpLine("/vibe rollback <mod>", "revert to the previous version"));
        sender.sendMessage(helpLine("/vibe enable|disable <mod>", "toggle a mod"));
        sender.sendMessage(helpLine("/vibe delete <mod>", "permanently remove a mod"));
        sender.sendMessage(helpLine("/vibe export <mod>", "export a standalone plugin jar"));
        sender.sendMessage(helpLine("/vibe do <mod> <action> [args]", "run a mod action"));
        sender.sendMessage(helpLine("/vibe model [id]", "view/set the LLM model"));
        sender.sendMessage(helpLine("/vibe chat", "toggle chat-as-prompt mode"));
        sender.sendMessage(helpLine("/vibe gui", "open the mod browser"));
        sender.sendMessage(helpLine("/vibe reload", "re-read config.yml"));
        sender.sendMessage(helpLine("/vibe panic", "disable all mods"));
    }

    private static Component helpLine(String cmd, String desc) {
        return Component.text(cmd, NamedTextColor.AQUA).append(Component.text(" - " + desc, NamedTextColor.GRAY));
    }

    private static void error(CommandSender sender, String msg) {
        sender.sendMessage(Component.text(msg, NamedTextColor.RED));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg != null ? msg : cur.getClass().getSimpleName();
    }

    // ---- tab completion ----

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return startsWithFilter(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (MOD_ARG_SUBS.contains(sub)) {
                return startsWithFilter(modNames(), args[1]);
            }
            if (sub.equals("model")) {
                return startsWithFilter(KNOWN_MODELS, args[1]);
            }
        }
        if (args.length == 3 && sub.equals("do")) {
            ModHandle handle = registry.get(args[1]);
            if (handle != null) {
                return startsWithFilter(handle.actionNames(), args[2]);
            }
        }
        if (args.length == 3 && sub.equals("set")) {
            List<String> keys = new ArrayList<>();
            for (GeneratedProject.ConfigKnob knob : configs.schema(args[1])) {
                keys.add(knob.key());
            }
            return startsWithFilter(keys, args[2]);
        }
        if (args.length == 4 && sub.equals("set")) {
            GeneratedProject.ConfigKnob knob = findKnob(args[1], args[2]);
            List<String> candidates = new ArrayList<>();
            if (knob != null) {
                String current = configs.values(args[1]).get(knob.key());
                if (current != null) {
                    candidates.add(current);
                }
                if ("choice".equals(knob.type()) && knob.choices() != null) {
                    candidates.addAll(knob.choices());
                }
            }
            return startsWithFilter(candidates, args[3]);
        }
        return List.of();
    }

    private GeneratedProject.ConfigKnob findKnob(String modName, String key) {
        for (GeneratedProject.ConfigKnob knob : configs.schema(modName)) {
            if (knob.key().equalsIgnoreCase(key)) {
                return knob;
            }
        }
        return null;
    }

    private List<String> modNames() {
        List<String> names = new ArrayList<>();
        for (ModStore.StoredMod mod : store.all()) {
            names.add(mod.name());
        }
        return names;
    }

    private static List<String> startsWithFilter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
