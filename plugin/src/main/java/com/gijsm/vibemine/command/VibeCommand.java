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

import com.gijsm.vibemine.gen.ModGenerator;
import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.runtime.ModRegistry;
import com.gijsm.vibemine.store.JarExporter;
import com.gijsm.vibemine.store.ModStore;
import com.gijsm.vibemine.ui.ChatMode;
import com.gijsm.vibemine.ui.ModBrowserGui;
import com.gijsm.vibemine.ui.Progress;
import com.gijsm.vibemine.ui.SourceBook;

/**
 * The {@code /vibe} command: generate, edit, and manage in-game mods. Read-only
 * subcommands (list/source/help) require {@code vibe.use}; everything else
 * requires {@code vibe.admin}.
 */
public final class VibeCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of(
            "make", "edit", "again", "list", "source", "rollback", "enable",
            "disable", "delete", "export", "do", "model", "chat", "gui", "panic", "help");
    private static final Set<String> READ_ONLY = Set.of("list", "source", "help");
    private static final Set<String> MOD_ARG_SUBS = Set.of(
            "edit", "again", "source", "rollback", "enable", "disable", "delete", "export", "do");
    private static final List<String> KNOWN_MODELS = List.of(
            "anthropic/claude-sonnet-5", "anthropic/claude-opus-5", "anthropic/claude-haiku-5",
            "openai/gpt-5", "google/gemini-3-pro");

    private final Plugin plugin;
    private final ModGenerator generator;
    private final ModRegistry registry;
    private final ModStore store;
    private final JarExporter exporter;
    private final ModBrowserGui gui;
    private final ChatMode chatMode;
    private final Supplier<String> getModel;
    private final Consumer<String> setModel;
    private final BiConsumer<CommandSender, String> applyVersion;

    public VibeCommand(Plugin plugin, ModGenerator generator, ModRegistry registry, ModStore store,
                        JarExporter exporter, ModBrowserGui gui, ChatMode chatMode,
                        Supplier<String> getModel, Consumer<String> setModel,
                        BiConsumer<CommandSender, String> applyVersion) {
        this.plugin = plugin;
        this.generator = generator;
        this.registry = registry;
        this.store = store;
        this.exporter = exporter;
        this.gui = gui;
        this.chatMode = chatMode;
        this.getModel = getModel;
        this.setModel = setModel;
        this.applyVersion = applyVersion;
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
            case "rollback" -> cmdRollback(sender, rest);
            case "enable" -> cmdEnable(sender, rest);
            case "disable" -> cmdDisable(sender, rest);
            case "delete" -> cmdDelete(sender, rest);
            case "export" -> cmdExport(sender, rest);
            case "do" -> cmdDo(sender, rest);
            case "model" -> cmdModel(sender, rest);
            case "chat" -> cmdChat(sender);
            case "gui" -> cmdGui(sender);
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
            } else {
                progress.fail(result.message());
            }
        });
    }

    // ---- listing / source ----

    private void cmdList(CommandSender sender) {
        List<ModStore.StoredMod> mods = store.all();
        if (mods.isEmpty()) {
            sender.sendMessage(Component.text("No mods yet - try /vibe make <description>.", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("VibeMine mods:", NamedTextColor.GOLD));
        for (ModStore.StoredMod mod : mods) {
            // Live registry state wins over the stored flag (e.g. a watchdog trip).
            com.gijsm.vibemine.runtime.ModHandle live = registry.get(mod.name());
            boolean enabled = live != null ? live.enabled() : mod.enabled();
            NamedTextColor color = enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY;
            Component hover = Component.text(mod.description()).append(Component.newline())
                    .append(Component.text("v" + mod.currentVersion() + " by " + mod.creator(), NamedTextColor.GRAY));
            Component line = Component.text(mod.name(), color)
                    .hoverEvent(HoverEvent.showText(hover))
                    .clickEvent(ClickEvent.runCommand("/vibe source " + mod.name()))
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
        sender.sendMessage(helpLine("/vibe rollback <mod>", "revert to the previous version"));
        sender.sendMessage(helpLine("/vibe enable|disable <mod>", "toggle a mod"));
        sender.sendMessage(helpLine("/vibe delete <mod>", "permanently remove a mod"));
        sender.sendMessage(helpLine("/vibe export <mod>", "export a standalone plugin jar"));
        sender.sendMessage(helpLine("/vibe do <mod> <action> [args]", "run a mod action"));
        sender.sendMessage(helpLine("/vibe model [id]", "view/set the LLM model"));
        sender.sendMessage(helpLine("/vibe chat", "toggle chat-as-prompt mode"));
        sender.sendMessage(helpLine("/vibe gui", "open the mod browser"));
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
        return List.of();
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
