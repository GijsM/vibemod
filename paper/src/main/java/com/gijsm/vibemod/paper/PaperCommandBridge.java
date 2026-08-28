package com.gijsm.vibemod.paper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemod.platform.CommandBridge;
import com.gijsm.vibemod.platform.PlatformInfo;
import com.gijsm.vibemod.platform.TickScheduler;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModDispatch;

/**
 * {@link CommandBridge} over Paper's {@link CommandMap}: v1's
 * {@code runtime/DynamicCommands}, now the one place in the codebase that knows
 * command-map reflection exists (ARCHITECTURE-V2 §1.1).
 *
 * <p>Never overrides a command it did not itself register, and never throws —
 * callers fall back to a {@code /vibe do} action on failure.
 *
 * <p>Paper offers no supported way to remove a command from the map (and its
 * {@code getKnownCommands()} may return an immutable view), so correctness does
 * not depend on map surgery: every command we register is a
 * {@link VibeModCommand} whose handler can be swapped or nulled. Unregister
 * neuters the handler (the command then reports itself as removed) and only
 * best-effort-removes the map entry; re-registering the same name revives the
 * existing instance in place.
 */
public final class PaperCommandBridge implements CommandBridge {

    private final Plugin plugin;
    private final PlatformInfo platform;
    private final ModDispatch dispatch;
    private final TickScheduler scheduler;
    private volatile boolean allowTopLevel;
    private final Map<String, VibeModCommand> ours = new ConcurrentHashMap<>();

    public PaperCommandBridge(Plugin plugin, PlatformInfo platform, ModDispatch dispatch,
                              TickScheduler scheduler, boolean allowTopLevel) {
        this.plugin = plugin;
        this.platform = platform;
        this.dispatch = dispatch;
        this.scheduler = scheduler;
        this.allowTopLevel = allowTopLevel;
    }

    /** Change whether future {@link #register} calls may create real top-level commands. */
    public void setAllowTopLevel(boolean allow) {
        this.allowTopLevel = allow;
    }

    @Override
    public Registration register(String name, String description, String modName, CommandHandler handler) {
        if (!allowTopLevel || !platform.hasNativeCommandMap()) {
            return Registration.inactive();
        }
        String key = name.toLowerCase(Locale.ROOT);
        // Every invocation of a generated command goes through ModDispatch: timed by
        // the watchdog, guarded, and journalled against the mod (§2).
        //
        // The runOnMain wrapper is what makes the prompt's threading promise true
        // on a regionised server. Folia delivers a player's command on the region
        // thread owning that player, so without this a generated command handler
        // would run somewhere the prompt told it it never runs. On Paper
        // runOnMain executes inline — same thread, same tick, no behaviour change.
        CommandExecutorLike wrapped = (sender, label, args) -> {
            com.gijsm.vibemod.platform.Sender s = PaperSender.of(sender);
            scheduler.runOnMain(() ->
                    dispatch.run(modName, s, "command:" + name, () -> handler.run(s, args)));
        };
        try {
            CommandMap map = Bukkit.getCommandMap();
            if (map == null) {
                return Registration.inactive();
            }
            Command existing = map.getCommand(key);
            VibeModCommand mine = ours.get(key);
            if (existing instanceof VibeModCommand zombie && (mine == null || mine == zombie)) {
                // A previously-unregistered instance is still in the map: revive it.
                zombie.handler = wrapped;
                zombie.setDescription(description == null ? "" : description);
                ours.put(key, zombie);
                resyncAll();
                return Registration.of(() -> unregister(key));
            }
            if (existing != null && existing != mine) {
                return Registration.inactive();
            }
            VibeModCommand cmd = new VibeModCommand(plugin, key, description == null ? "" : description, wrapped);
            map.register("vibemod", cmd);
            ours.put(key, cmd);
            resyncAll();
            return Registration.of(() -> unregister(key));
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to register dynamic command /" + name, t);
            return Registration.inactive();
        }
    }

    /** Remove {@code /name} + resync clients. Never throws. */
    private void unregister(String key) {
        try {
            VibeModCommand cmd = ours.remove(key);
            if (cmd == null) {
                return;
            }
            cmd.handler = null; // the command is dead even if map surgery fails below
            CommandMap map = Bukkit.getCommandMap();
            if (map == null) {
                return;
            }
            Map<String, Command> known = knownCommands(map);
            if (known != null) {
                try {
                    known.entrySet().removeIf(e -> {
                        String k = e.getKey();
                        return (k.equals(key) || k.equals("vibemod:" + key)) && e.getValue() == cmd;
                    });
                } catch (UnsupportedOperationException immutableView) {
                    plugin.getLogger().fine("Command map view is immutable; /" + key
                            + " stays mapped but is neutered");
                }
            }
            try {
                cmd.unregister(map);
            } catch (Throwable ignored) {
                // best-effort
            }
            resyncAll();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to unregister dynamic command /" + key, t);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> knownCommands(CommandMap map) {
        if (!(map instanceof SimpleCommandMap scm)) {
            return null;
        }
        // Field first: it is the live mutable map, while Paper's public
        // getKnownCommands() may hand back an immutable view.
        try {
            Field f = SimpleCommandMap.class.getDeclaredField("knownCommands");
            f.setAccessible(true);
            Object result = f.get(scm);
            if (result instanceof Map) {
                return (Map<String, Command>) result;
            }
        } catch (Throwable ignored) {
            // fall through to the public getter
        }
        try {
            Method getter = SimpleCommandMap.class.getMethod("getKnownCommands");
            Object result = getter.invoke(scm);
            if (result instanceof Map) {
                return (Map<String, Command>) result;
            }
        } catch (Throwable ignored) {
            // give up silently
        }
        return null;
    }

    /**
     * Pushes the command tree to every online player so a freshly registered
     * command tab-completes without a rejoin. Capability-gated: a fork without
     * {@code Player#updateCommands} simply does not get the refresh, which costs
     * a rejoin rather than a crash.
     */
    @Override
    public void resyncAll() {
        if (!platform.hasCommandResync()) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                p.updateCommands();
            } catch (Throwable ignored) {
                // best-effort
            }
        }
    }

    /** A runtime-registered command whose handler can be swapped or neutered. */
    private static final class VibeModCommand extends Command {

        private final Plugin plugin;
        private volatile CommandExecutorLike handler;

        private VibeModCommand(Plugin plugin, String name, String description, CommandExecutorLike handler) {
            super(name, description, "/" + name, List.of());
            this.plugin = plugin;
            this.handler = handler;
            setPermission("vibe.use");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            CommandExecutorLike current = handler;
            if (current == null) {
                sender.sendMessage(Component.text("Unknown command: /" + label, NamedTextColor.RED));
                return true;
            }
            try {
                current.run(sender, label, args);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Command /" + label + " failed", t);
                sender.sendMessage(Component.text("Something went wrong running /" + label + ".",
                        NamedTextColor.RED));
            }
            return true;
        }
    }

    /** A mod command handler bound to a runtime command registration. */
    @FunctionalInterface
    private interface CommandExecutorLike {
        void run(CommandSender sender, String label, String[] args);
    }
}
