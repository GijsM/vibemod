package com.gijsm.vibemine.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import com.gijsm.vibemine.llm.ModelCatalog;
import com.gijsm.vibemine.runtime.DebugEcho;
import com.gijsm.vibemine.runtime.ModErrors;
import com.gijsm.vibemine.runtime.ModHandle;
import com.gijsm.vibemine.runtime.ModRegistry;
import com.gijsm.vibemine.store.JarExporter;
import com.gijsm.vibemine.store.ModConfigs;
import com.gijsm.vibemine.store.ModStore;
import com.gijsm.vibemine.ui.ChatMode;
import com.gijsm.vibemine.ui.Dialogs;
import com.gijsm.vibemine.ui.InfoDialogs;
import com.gijsm.vibemine.ui.InstallCard;
import com.gijsm.vibemine.ui.ModHubDialog;
import com.gijsm.vibemine.ui.Progress;
import com.gijsm.vibemine.ui.SettingsDialog;
import com.gijsm.vibemine.ui.Style;
import com.gijsm.vibemine.ui.VirtualBooks;

/**
 * The {@code /vibe} command: generate, edit, tune, and manage in-game mods.
 * Read-only subcommands (list/source/info/manual/history/errors/help) require
 * {@code vibe.use}; everything else requires {@code vibe.admin}.
 *
 * <p>Typing UX moved to native dialogs in v3: {@code make}/{@code edit} with
 * no free-text description, {@code config}, and {@code book} all open a
 * {@link Dialogs} popup for players (console/RCON keeps working exactly as
 * before by supplying the text inline). Reading ({@code manual}/{@code source}/
 * {@code errors}/{@code history}) opens native dialogs via {@link InfoDialogs}
 * for players; console gets the same content as {@link VirtualBooks}-style
 * chat dumps.
 */
public final class VibeCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of(
            "make", "edit", "again", "list", "source", "info", "manual", "config", "set", "book",
            "rollback", "history", "enable", "disable", "delete", "export", "do", "model", "costs", "chat",
            "settings", "reload", "panic", "errors", "fix", "debug", "help");
    private static final Set<String> READ_ONLY = Set.of(
            "list", "source", "info", "manual", "history", "errors", "help");
    private static final Set<String> MOD_ARG_SUBS = Set.of(
            "edit", "again", "source", "info", "manual", "config", "set", "book",
            "rollback", "history", "enable", "disable", "delete", "export", "do", "errors", "fix", "debug",
            "reload");
    private static final int FIX_ERROR_LINES = 8;
    private static final int CONSOLE_ERROR_LINES = 25;

    private final Plugin plugin;
    private final ModGenerator generator;
    private final ModRegistry registry;
    private final ModStore store;
    private final ModConfigs configs;
    private final ModErrors errors;
    private final DebugEcho debug;
    private final ModelCatalog catalog;
    private final JarExporter exporter;
    private final ChatMode chatMode;
    private final Dialogs dialogs;
    private final SettingsDialog settingsDialog;
    private final ModHubDialog hub;
    private final InfoDialogs infoDialogs;
    private final Supplier<String> getModel;
    private final Consumer<String> setModel;
    private final java.util.function.DoubleSupplier sessionCost;
    private final ApplyVersion applyVersion;
    private final Runnable reloadConfig;

    /**
     * Recompiles a mod's current stored version and hot-loads it, asynchronously;
     * {@code onLive} (nullable) runs on the main thread once the version is actually
     * live — the compile is async, so "after the call returns" is too early for
     * anything that wants to show the result (e.g. reopening the mod hub).
     */
    @FunctionalInterface
    public interface ApplyVersion {
        void apply(CommandSender feedback, String modName, Runnable onLive);
    }

    /**
     * {@code catalog} was added (dynamic model picker + cost visibility feature) right
     * after {@code debug}; {@code sessionCost} was added right after {@code setModel}
     * since it is model-related, like {@code getModel}/{@code setModel}; {@code hub}
     * (mod hub dialog feature) sits right after {@code settingsDialog} since it is the
     * next dialog surface.
     */
    public VibeCommand(Plugin plugin, ModGenerator generator, ModRegistry registry, ModStore store,
                        ModConfigs configs, ModErrors errors, DebugEcho debug, ModelCatalog catalog,
                        JarExporter exporter, ChatMode chatMode, Dialogs dialogs,
                        SettingsDialog settingsDialog, ModHubDialog hub,
                        Supplier<String> getModel, Consumer<String> setModel,
                        java.util.function.DoubleSupplier sessionCost,
                        ApplyVersion applyVersion, Runnable reloadConfig) {
        this.plugin = plugin;
        this.generator = generator;
        this.registry = registry;
        this.store = store;
        this.configs = configs;
        this.errors = errors;
        this.debug = debug;
        this.catalog = catalog;
        this.exporter = exporter;
        this.chatMode = chatMode;
        this.dialogs = dialogs;
        this.settingsDialog = settingsDialog;
        this.hub = hub;
        this.infoDialogs = new InfoDialogs(plugin);
        this.getModel = getModel;
        this.setModel = setModel;
        this.sessionCost = sessionCost;
        this.applyVersion = applyVersion;
        this.reloadConfig = reloadConfig;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // Bare /vibe is the front door: players who may use the plugin get
            // the mod browser; everyone else (and console) gets the help text.
            if (sender instanceof Player player && player.hasPermission("vibe.use")) {
                hub.openBrowser(player);
            } else {
                sendHelp(sender);
            }
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
            case "history" -> cmdHistory(sender, rest);
            case "enable" -> cmdEnable(sender, rest);
            case "disable" -> cmdDisable(sender, rest);
            case "delete" -> cmdDelete(sender, rest);
            case "export" -> cmdExport(sender, rest);
            case "do" -> cmdDo(sender, rest);
            case "model" -> cmdModel(sender, rest);
            case "costs" -> cmdCosts(sender);
            case "chat" -> cmdChat(sender);
            case "settings" -> cmdSettings(sender);
            case "reload" -> cmdReload(sender, rest);
            case "panic" -> cmdPanic(sender);
            case "errors" -> cmdErrors(sender, rest);
            case "fix" -> cmdFix(sender, rest);
            case "debug" -> cmdDebug(sender, rest);
            case "help" -> sendHelp(sender);
            default -> error(sender, "Unknown subcommand '" + sub + "'. Try /vibe help.");
        }
        return true;
    }

    // ---- generation ----

    private void cmdMake(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                dialogs.openPrompt(player);
                return;
            }
            error(sender, "Usage: /vibe make <description>");
            return;
        }
        String prompt = String.join(" ", args);
        Progress progress = new Progress(plugin, sender, "vibe: " + truncate(prompt, 40));
        generator.make(prompt, sender.getName(), bridge(progress))
                .whenComplete((result, ex) -> onGenerationDone(sender, progress, result, ex));
    }

    private void cmdEdit(CommandSender sender, String[] args) {
        if (args.length == 0) {
            error(sender, "Usage: /vibe edit <mod> <description>");
            return;
        }
        if (args.length == 1) {
            if (sender instanceof Player player) {
                openEditDialog(player, args[0]);
                return;
            }
            error(sender, "Usage: /vibe edit <mod> <description>");
            return;
        }
        String modName = args[0];
        String prompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Progress progress = new Progress(plugin, sender, "vibe: " + truncate(prompt, 40));
        generator.edit(modName, prompt, sender.getName(), bridge(progress))
                .whenComplete((result, ex) -> onGenerationDone(sender, progress, result, ex));
    }

    private void openEditDialog(Player player, String modName) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(player, "Unknown mod: " + modName);
            return;
        }
        String manual = mod.manual() == null || mod.manual().isBlank() ? mod.description() : mod.manual();
        dialogs.openEdit(player, mod.name(), manual);
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

            @Override
            public void planReady(String name, java.util.List<String> files) {
                progress.planReady(name, files);
            }

            @Override
            public void fileStarted(String path, int index, int total) {
                progress.fileStarted(path, index, total);
            }

            @Override
            public void streamStats(int chars, int approxTokens) {
                progress.streamStats(chars, approxTokens);
            }

            @Override
            public void queued(int position, int running) {
                progress.queued(position, running);
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
                String costSuffix = result.costUsd() <= 0 ? "" : " · " + Style.fmtCost(result.costUsd());
                progress.succeed("✔ " + result.modName() + " v" + result.version() + " installed"
                        + retrySuffix + costSuffix);
                ModStore.StoredMod mod = result.modName() != null ? store.get(result.modName()) : null;
                if (mod != null) {
                    ModHandle live = registry.get(mod.name());
                    sender.sendMessage(InstallCard.build(mod, live));
                }
            } else {
                String costNote = result.costUsd() > 0 ? " (spent " + Style.fmtCost(result.costUsd()) + ")" : "";
                progress.fail(result.message() + costNote);
            }
        });
    }

    // ---- listing / source / info / manual ----

    private void cmdList(CommandSender sender) {
        // Players get the native browser dialog (read-only navigation — the hub
        // and its commands enforce their own permissions); console keeps the
        // chat list below.
        if (sender instanceof Player player) {
            hub.openBrowser(player);
            return;
        }
        List<ModStore.StoredMod> mods = store.all();
        if (mods.isEmpty()) {
            sender.sendMessage(Style.info("No mods yet - try /vibe make <description>."));
            return;
        }
        sender.sendMessage(Component.text("VibeMod mods:", NamedTextColor.GOLD));
        for (ModStore.StoredMod mod : mods) {
            // Live registry state wins over the stored flag (e.g. a watchdog trip).
            ModHandle live = registry.get(mod.name());
            boolean enabled = live != null ? live.enabled() : mod.enabled();
            boolean degraded = live != null && live.degraded();
            NamedTextColor color = degraded ? Style.WARN : (enabled ? Style.OK : NamedTextColor.GRAY);
            Component hover = Component.text(mod.description()).append(Component.newline())
                    .append(Component.text("v" + mod.currentVersion() + " by " + mod.creator(), NamedTextColor.GRAY));
            if (mod.usage() != null && !mod.usage().isBlank()) {
                hover = hover.append(Component.newline())
                        .append(Component.text("Try: " + mod.usage(), NamedTextColor.YELLOW));
            }
            String stateSuffix = degraded ? " [degraded]" : (enabled ? " [on]" : " [off]");
            Component line = Style.dot(enabled, degraded).append(Component.text(" "))
                    .append(Component.text(mod.name(), color)
                            .hoverEvent(HoverEvent.showText(hover))
                            .clickEvent(ClickEvent.runCommand("/vibe info " + mod.name())))
                    .append(Component.text(stateSuffix, NamedTextColor.DARK_GRAY));
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
            infoDialogs.openSource(player, mod.name(), mod.currentVersion(), sources);
        } else {
            VirtualBooks.dumpSource(sender, modName, sources);
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
        if (sender instanceof Player player) {
            hub.open(player, mod.name());
            return;
        }
        ModHandle live = registry.get(modName);
        sender.sendMessage(InstallCard.build(mod, live));
        sender.sendMessage(InstallCard.verifiedFooter(mod, live, configs.values(modName), errorsLineFor(modName)));
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
            infoDialogs.openManual(player, mod, live, values);
            return;
        }
        VirtualBooks.dumpManual(sender, mod, live, values);
    }

    private void cmdConfig(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can use the config dialog.");
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
        dialogs.openConfig(player, mod.name(), knobsFor(modName));
    }

    /** Schema + current values -> the knob list the config dialog renders. Store-side, so it works for unloaded mods. */
    private List<Dialogs.Knob> knobsFor(String modName) {
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            return List.of();
        }
        Map<String, String> values = store.resolvedConfigValues(mod.name());
        List<Dialogs.Knob> knobs = new ArrayList<>();
        for (GeneratedProject.ConfigKnob k : mod.config()) {
            knobs.add(new Dialogs.Knob(k.key(), k.type(), k.description(),
                    values.getOrDefault(k.key(), k.def()), k.min(), k.max(), k.step(), k.choices()));
        }
        return knobs;
    }

    private void cmdSet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            error(sender, "Usage: /vibe set <mod> <key> <value>");
            return;
        }
        String modName = args[0];
        String key = args[1];
        String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        // Unloaded mods have no live config cache: fall back to the disk store
        // (same validation - ModStore.validateKnobValue backs both paths).
        boolean unloaded = configs.schema(modName).isEmpty() && registry.get(modName) == null;
        String oldValue = (unloaded ? store.resolvedConfigValues(modName) : configs.values(modName)).get(key);
        try {
            if (unloaded) {
                store.setConfigValue(modName, key, value);
            } else {
                configs.set(modName, key, value);
            }
        } catch (IllegalArgumentException e) {
            error(sender, e.getMessage());
            return;
        }
        String newValue = (unloaded ? store.resolvedConfigValues(modName) : configs.values(modName)).get(key);
        sender.sendMessage(Component.text(modName + "." + key + ": ", NamedTextColor.GRAY)
                .append(Component.text(oldValue != null ? oldValue : "(default)", NamedTextColor.RED))
                .append(Component.text(" -> ", NamedTextColor.GRAY))
                .append(Component.text(newValue != null ? newValue : "", Style.OK)));
    }

    private void cmdBook(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can use the prompt/edit dialogs.");
            return;
        }
        if (args.length == 0) {
            dialogs.openPrompt(player);
            return;
        }
        openEditDialog(player, args[0]);
    }

    // ---- lifecycle management ----

    private void cmdRollback(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe rollback <mod> [version]");
            return;
        }
        String modName = args[0];
        if (args.length == 1) {
            // The classic one-step rollback, byte-identical in behavior.
            if (!store.rollback(modName)) {
                error(sender, "Can't roll back " + modName + " (already at v1 or unknown).");
                return;
            }
            applyVersion.apply(sender, modName, null);
            ModStore.StoredMod mod = store.get(modName);
            int version = mod != null ? mod.currentVersion() : -1;
            sender.sendMessage(Style.ok("Rolled back " + modName + " to v" + version + "."));
            return;
        }
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(sender, "Unknown mod: " + modName);
            return;
        }
        int version;
        try {
            version = Integer.parseInt(args[1]);
        } catch (NumberFormatException bad) {
            error(sender, "Usage: /vibe rollback <mod> [version]");
            return;
        }
        ModStore.StoredVersion target = null;
        for (ModStore.StoredVersion v : mod.versions()) {
            if (v.version() == version) {
                target = v;
            }
        }
        if (target == null) {
            error(sender, mod.name() + " has no v" + version + ".");
            return;
        }
        if (version == mod.currentVersion()) {
            sender.sendMessage(Style.info(mod.name() + " v" + version + " is already active."));
            return;
        }
        if (!store.versionsOnDisk(mod.name()).contains(version)) {
            error(sender, mod.name() + " v" + version + "'s sources are missing on disk.");
            return;
        }
        boolean confirmed = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
        if (sender instanceof Player player && !confirmed) {
            dialogs.openRollbackConfirm(player, mod.name(), version, InfoDialogs.changelogOrPrompt(target));
            return;
        }
        store.setCurrentVersion(mod.name(), version);
        applyVersion.apply(sender, modName, null);
        sender.sendMessage(Style.ok("Activated " + mod.name() + " v" + version + "."));
    }

    private void cmdHistory(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe history <mod>");
            return;
        }
        String modName = args[0];
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(sender, "Unknown mod: " + modName);
            return;
        }
        if (sender instanceof Player player) {
            infoDialogs.openHistory(player, mod, store.versionsOnDisk(mod.name()));
            return;
        }
        sender.sendMessage(Component.text(mod.name() + " — " + mod.versions().size()
                + " version(s), v" + mod.currentVersion() + " active:", NamedTextColor.GOLD));
        List<ModStore.StoredVersion> versions = mod.versions();
        for (int i = versions.size() - 1; i >= 0; i--) {
            ModStore.StoredVersion v = versions.get(i);
            List<String> segments = new ArrayList<>();
            segments.add((v.version() == mod.currentVersion() ? "● " : "") + "v" + v.version());
            if (!v.kind().isBlank()) {
                segments.add(v.kind());
            }
            segments.add(InfoDialogs.relativeTime(v.createdAt()));
            if (v.costUsd() > 0) {
                segments.add(Style.fmtCost(v.costUsd()));
            }
            if (!v.requester().isBlank()) {
                segments.add("by " + v.requester());
            }
            sender.sendMessage(Component.text(
                    String.join(" · ", segments) + " — " + InfoDialogs.changelogOrPrompt(v),
                    NamedTextColor.GRAY));
        }
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
            sender.sendMessage(Style.ok(modName + " enabled."));
        } else {
            applyVersion.apply(sender, modName, null);
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
            sender.sendMessage(Style.warn(modName + " disabled."));
        } else {
            sender.sendMessage(Style.info(modName + " was already disabled or not loaded."));
        }
    }

    private void cmdDelete(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe delete <mod>");
            return;
        }
        String modName = args[0];
        ModStore.StoredMod mod = store.get(modName);
        if (mod == null) {
            error(sender, "Unknown mod: " + modName);
            return;
        }
        boolean confirmed = args.length >= 2 && args[1].equalsIgnoreCase("confirm");
        if (sender instanceof Player player && !confirmed) {
            dialogs.openDeleteConfirm(player, mod.name(), mod.versions().size());
            return;
        }
        registry.unload(modName);
        store.delete(modName);
        sender.sendMessage(Style.warn("Deleted " + modName + "."));
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
        sender.sendMessage(Style.info("Exporting " + modName + "..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, String> sources = store.sources(modName, mod.currentVersion());
                Path outDir = plugin.getDataFolder().toPath().resolve("exports");
                Path jar = exporter.export(mod, sources, outDir);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Style.ok("Exported to " + jar)));
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

    // ---- debuggability: errors / fix / debug ----

    private void cmdErrors(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe errors <mod>");
            return;
        }
        String modName = args[0];
        int distinct = errors.distinctCount(modName);
        if (sender instanceof Player player) {
            infoDialogs.openErrors(player, modName, errors.recent(modName));
            player.sendMessage(Style.warn(modName + ": " + distinct + " distinct error(s) - see the dialog."));
            return;
        }
        VirtualBooks.dumpErrors(sender, errors.report(modName, CONSOLE_ERROR_LINES));
    }

    private void cmdFix(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe fix <mod>");
            return;
        }
        String modName = args[0];
        boolean confirmed = args.length >= 2 && args[1].equalsIgnoreCase("confirm");
        if (sender instanceof Player player && !confirmed) {
            dialogs.openFixConfirm(player, modName, lastErrorSummary(modName));
            return;
        }
        String report = errors.report(modName, FIX_ERROR_LINES);
        Progress progress = new Progress(plugin, sender, "vibe: fix " + modName);
        generator.fix(modName, report, sender.getName(), bridge(progress))
                .whenComplete((result, ex) -> onGenerationDone(sender, progress, result, ex));
    }

    private String lastErrorSummary(String modName) {
        List<ModErrors.ErrorRecord> recent = errors.recent(modName);
        if (recent == null || recent.isEmpty()) {
            return "";
        }
        ModErrors.ErrorRecord last = recent.get(recent.size() - 1);
        return last.exceptionClass() + ": " + last.message();
    }

    private String errorsLineFor(String modName) {
        int distinct = errors.distinctCount(modName);
        if (distinct <= 0) {
            return null;
        }
        return "errors: " + distinct + " distinct - /vibe errors " + modName;
    }

    private void cmdDebug(CommandSender sender, String[] args) {
        if (args.length < 1) {
            error(sender, "Usage: /vibe debug <mod> [on|off]");
            return;
        }
        String modName = args[0];
        boolean now;
        if (args.length >= 2) {
            String want = args[1].toLowerCase(Locale.ROOT);
            if (!want.equals("on") && !want.equals("off")) {
                error(sender, "Usage: /vibe debug <mod> [on|off]");
                return;
            }
            now = want.equals("on");
            debug.set(modName, now);
        } else {
            now = debug.toggle(modName);
        }
        sender.sendMessage(Style.ok(modName + " debug echo " + (now ? "ON" : "OFF") + "."));
    }

    // ---- misc ----

    private void cmdModel(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                dialogs.openModelPicker(player, catalog.featured(getModel.get()), getModel.get(),
                        sessionCost.getAsDouble(), modelId -> applyModelSet(player, modelId));
                return;
            }
            String current = getModel.get();
            String price = catalog.find(current).map(ModelCatalog.ModelInfo::priceLabel).orElse("price unknown");
            sender.sendMessage(Style.info("Current model: " + current + " (" + price + ")"));
            sender.sendMessage(Style.info("Session spent: " + Style.fmtCost(sessionCost.getAsDouble())));
            return;
        }
        applyModelSet(sender, args[0]);
    }

    private void applyModelSet(CommandSender sender, String modelId) {
        setModel.accept(modelId);
        java.util.Optional<ModelCatalog.ModelInfo> info = catalog.find(modelId);
        Component msg = Style.ok("Model set to " + modelId
                + (info.isPresent() ? " (" + info.get().priceLabel() + ")" : ""));
        if (info.isEmpty()) {
            msg = msg.append(Component.text(
                    " (unknown to the catalog - hope you know what you're doing)", NamedTextColor.GRAY));
        }
        sender.sendMessage(msg);
    }

    /**
     * The cost dashboard: lifetime generation spend per mod (costliest first) plus the
     * session total. Versions saved before cost tracking existed are recognized by their
     * blank {@code kind} - the reliable marker, since their {@code costUsd} just reads 0.0.
     */
    private void cmdCosts(CommandSender sender) {
        List<InfoDialogs.ModCost> rows = new ArrayList<>();
        for (ModStore.StoredMod mod : store.all()) {
            rows.add(InfoDialogs.ModCost.of(mod));
        }
        rows.sort(java.util.Comparator.comparingDouble(InfoDialogs.ModCost::lifetimeUsd).reversed());

        if (sender instanceof Player player) {
            infoDialogs.openCosts(player, sessionCost.getAsDouble(), rows);
            return;
        }
        sender.sendMessage(Component.text("VibeMod — costs:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Session spend: " + Style.fmtCost(sessionCost.getAsDouble()),
                NamedTextColor.GOLD));
        int zeroMods = 0;
        boolean anyPreTracking = false;
        for (InfoDialogs.ModCost row : rows) {
            anyPreTracking |= row.preTracking() > 0;
            if (row.lifetimeUsd() <= 0) {
                zeroMods++;
                continue;
            }
            sender.sendMessage(Component.text(InfoDialogs.costLine(row), NamedTextColor.GRAY));
        }
        if (zeroMods > 0) {
            sender.sendMessage(Component.text(zeroMods + " mod(s) at $0 not shown", NamedTextColor.DARK_GRAY));
        }
        if (anyPreTracking) {
            sender.sendMessage(Component.text("versions from before cost tracking count as $0",
                    NamedTextColor.GRAY));
        }
    }

    private void cmdChat(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "Only players can use chat mode.");
            return;
        }
        boolean on = chatMode.toggle(player);
        if (on) {
            player.sendMessage(Style.ok("Chat mode ON - just type to generate/edit mods. Type 'off' to stop."));
        } else {
            player.sendMessage(Style.info("Chat mode off."));
        }
    }

    /** Players get the native settings form; console gets a plain dump of the current values. */
    private void cmdSettings(CommandSender sender) {
        if (sender instanceof Player player) {
            settingsDialog.open(player);
            return;
        }
        SettingsDialog.Values v = settingsDialog.currentValues();
        sender.sendMessage(Style.info("model: " + v.model() + " (" + v.modelPriceLabel() + ")"));
        sender.sendMessage(Style.info("session spent: " + Style.fmtCost(v.sessionCostUsd())));
        sender.sendMessage(Style.info("thinking (openrouter.reasoning-effort): " + v.effort()));
        sender.sendMessage(Style.info("openrouter.streaming: " + v.streaming()));
        sender.sendMessage(Style.info("openrouter.timeout-seconds: " + v.timeoutSeconds()));
        sender.sendMessage(Style.info("openrouter.max-tokens: " + v.maxTokens() + (v.maxTokens() == 0 ? " (model ceiling)" : "")));
        sender.sendMessage(Style.info("generation.max-retries: " + v.maxRetries()));
        sender.sendMessage(Style.info("generation.concurrency: " + v.concurrency() + " (applies on next reload)"));
        sender.sendMessage(Style.info("watchdog.enabled: " + v.watchdogEnabled()));
        sender.sendMessage(Style.info("watchdog.single-invocation-ms: " + v.watchdogSingleMs()));
        sender.sendMessage(Style.info("watchdog.per-second-budget-ms: " + v.watchdogBudgetMs()));
        sender.sendMessage(Style.info("debug.default-echo: " + v.debugEcho()));
    }

    /**
     * No args: re-read config.yml, exactly as before. With a mod name: recompile and
     * hot-load that mod's current stored version; a player who asked gets the hub
     * reopened once the version is actually live (the compile is async).
     */
    private void cmdReload(CommandSender sender, String[] args) {
        if (args.length == 0) {
            reloadConfig.run();
            sender.sendMessage(Style.ok(
                    "Config reloaded (model/timeout, watchdog budgets, top-level commands, error/debug limits re-read)."));
            return;
        }
        String modName = args[0];
        if (store.get(modName) == null) {
            error(sender, "Unknown mod: " + modName);
            return;
        }
        applyVersion.apply(sender, modName,
                sender instanceof Player player ? () -> hub.open(player, modName) : null);
    }

    private void cmdPanic(CommandSender sender) {
        registry.panic();
        Component msg = Style.err("PANIC triggered by " + sender.getName() + " - all mods disabled.");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(msg);
        }
        Bukkit.getConsoleSender().sendMessage(msg);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("VibeMod - turn prompts into mods:", NamedTextColor.GOLD));
        sender.sendMessage(helpLine("/vibe make [description]", "generate a new mod (no text: opens a dialog)"));
        sender.sendMessage(helpLine("/vibe edit <mod> [description]", "revise a mod (no text: opens a dialog)"));
        sender.sendMessage(helpLine("/vibe again <mod>", "rerun a mod's last prompt"));
        sender.sendMessage(helpLine("/vibe list", "browse your mods (console: text list)"));
        sender.sendMessage(helpLine("/vibe source <mod>", "view a mod's source"));
        sender.sendMessage(helpLine("/vibe info <mod>", "open the mod hub (console: install card)"));
        sender.sendMessage(helpLine("/vibe manual <mod>", "open the player manual"));
        sender.sendMessage(helpLine("/vibe config <mod>", "open the config dialog"));
        sender.sendMessage(helpLine("/vibe set <mod> <key> <value>", "set one config knob (console/RCON)"));
        sender.sendMessage(helpLine("/vibe book [mod]", "open the prompt dialog, or an edit dialog for a mod"));
        sender.sendMessage(helpLine("/vibe errors <mod>", "view a mod's deduped error log"));
        sender.sendMessage(helpLine("/vibe fix <mod>", "send recent errors to the model for a repair round"));
        sender.sendMessage(helpLine("/vibe debug <mod> [on|off]", "toggle live ctx.log()/exception echo to ops"));
        sender.sendMessage(helpLine("/vibe rollback <mod> [version]",
                "revert one version back, or activate a specific one"));
        sender.sendMessage(helpLine("/vibe history <mod>", "browse and activate previous versions"));
        sender.sendMessage(helpLine("/vibe enable|disable <mod>", "toggle a mod"));
        sender.sendMessage(helpLine("/vibe delete <mod>", "permanently remove a mod (asks to confirm)"));
        sender.sendMessage(helpLine("/vibe export <mod>", "export a standalone plugin jar"));
        sender.sendMessage(helpLine("/vibe do <mod> <action> [args]", "run a mod action"));
        sender.sendMessage(helpLine("/vibe model [id]", "view/set the LLM model"));
        sender.sendMessage(helpLine("/vibe costs", "what generation has cost, per mod"));
        sender.sendMessage(helpLine("/vibe chat", "toggle chat-as-prompt mode"));
        sender.sendMessage(helpLine("/vibe settings", "open the plugin settings"));
        sender.sendMessage(helpLine("/vibe reload [mod]", "re-read config.yml, or recompile one mod"));
        sender.sendMessage(helpLine("/vibe panic", "disable all mods"));
    }

    private static Component helpLine(String cmd, String desc) {
        return Component.text(cmd, Style.ACTION).append(Component.text(" - " + desc, NamedTextColor.GRAY));
    }

    private static void error(CommandSender sender, String msg) {
        sender.sendMessage(Style.err(msg));
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
                // The full uncurated catalog; the curated list only until the first fetch lands.
                List<String> ids = catalog.allIds();
                if (ids.isEmpty()) {
                    ids = new ArrayList<>();
                    for (ModelCatalog.ModelInfo m : catalog.featured(getModel.get())) {
                        ids.add(m.id());
                    }
                }
                return startsWithFilter(ids, args[1]);
            }
        }
        if (args.length == 3 && sub.equals("rollback")) {
            List<String> versions = new ArrayList<>();
            ModStore.StoredMod mod = store.get(args[1]);
            if (mod != null) {
                for (ModStore.StoredVersion v : mod.versions()) {
                    versions.add(Integer.toString(v.version()));
                }
            }
            return startsWithFilter(versions, args[2]);
        }
        if (args.length == 4 && sub.equals("rollback")) {
            return startsWithFilter(List.of("confirm"), args[3]);
        }
        if (args.length == 3 && sub.equals("delete")) {
            return startsWithFilter(List.of("confirm"), args[2]);
        }
        if (args.length == 3 && sub.equals("do")) {
            ModHandle handle = registry.get(args[1]);
            if (handle != null) {
                return startsWithFilter(handle.actionNames(), args[2]);
            }
        }
        if (args.length == 3 && sub.equals("debug")) {
            return startsWithFilter(List.of("on", "off"), args[2]);
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
