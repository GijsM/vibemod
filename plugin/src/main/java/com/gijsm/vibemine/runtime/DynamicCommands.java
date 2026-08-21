package com.gijsm.vibemine.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Registers and unregisters real top-level {@code /name} commands at runtime
 * via Paper's {@link CommandMap}. Never overrides a command it did not itself
 * register, and never throws — callers fall back to a mod action on failure.
 *
 * <p>Paper offers no supported way to remove a command from the map (and its
 * {@code getKnownCommands()} may return an immutable view), so correctness
 * does not depend on map surgery: every command we register is a
 * {@link VibeModCommand} whose handler can be swapped or nulled. Unregister
 * neuters the handler (the command then reports itself as removed) and only
 * best-effort-removes the map entry; re-registering the same name revives the
 * existing instance in place.
 */
public final class DynamicCommands {

    private final Plugin plugin;
    private final boolean allowTopLevel;
    private final Map<String, VibeModCommand> ours = new ConcurrentHashMap<>();

    public DynamicCommands(Plugin plugin, boolean allowTopLevel) {
        this.plugin = plugin;
        this.allowTopLevel = allowTopLevel;
    }

    /**
     * Register {@code /name} at runtime. Returns true if a real top-level command
     * was registered, false if unavailable/failed (caller then falls back to an
     * action). Never throws.
     */
    public boolean register(String name, String description, CommandSender feedbackTarget, CommandExecutorLike handler) {
        if (!allowTopLevel) {
            return false;
        }
        try {
            String key = name.toLowerCase(java.util.Locale.ROOT);
            CommandMap map = Bukkit.getCommandMap();
            if (map == null) {
                return false;
            }
            Command existing = map.getCommand(key);
            VibeModCommand mine = ours.get(key);
            if (existing instanceof VibeModCommand zombie && (mine == null || mine == zombie)) {
                // A previously-unregistered instance is still in the map: revive it.
                zombie.handler = handler;
                zombie.setDescription(description == null ? "" : description);
                ours.put(key, zombie);
                resync();
                return true;
            }
            if (existing != null && existing != mine) {
                return false;
            }
            VibeModCommand cmd = new VibeModCommand(plugin, key, description == null ? "" : description, handler);
            map.register("vibemine", cmd);
            ours.put(key, cmd);
            resync();
            return true;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to register dynamic command /" + name, t);
            return false;
        }
    }

    /** Remove {@code /name} + resync clients. Never throws. */
    public void unregister(String name) {
        try {
            String key = name.toLowerCase(java.util.Locale.ROOT);
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
                        return (k.equals(key) || k.equals("vibemine:" + key)) && e.getValue() == cmd;
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
            resync();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to unregister dynamic command /" + name, t);
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

    private void resync() {
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
    public interface CommandExecutorLike {
        void run(CommandSender sender, String label, String[] args);
    }
}
