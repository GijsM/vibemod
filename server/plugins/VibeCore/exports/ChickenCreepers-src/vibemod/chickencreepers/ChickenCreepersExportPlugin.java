package vibemod.chickencreepers;

import com.gijsm.vibemine.api.ModCommandHandler;
import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Standalone export wrapper for VibeMine mod "ChickenCreepers": a real Paper plugin
 * hosting a single mod outside VibeCore, with a minimal VibeContext implementation
 * (no watchdog, plain Bukkit registration calls).
 */
public final class ChickenCreepersExportPlugin extends JavaPlugin {

    private final List<Listener> listeners = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final List<Command> commands = new ArrayList<>();
    private VibeMod mod;
    private StandaloneContext ctx;

    @Override
    public void onEnable() {
        try {
            this.ctx = new StandaloneContext(this);
            this.mod = new vibemod.chickencreepers.ChickenCreepers();
            this.mod.onEnable(ctx);
        } catch (Exception e) {
            getLogger().severe("Failed to enable mod ChickenCreepers: " + e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (mod != null && ctx != null) {
            try {
                mod.onDisable(ctx);
            } catch (Exception e) {
                getLogger().warning("Error during mod disable: " + e);
            }
        }
        for (Listener l : listeners) {
            HandlerList.unregisterAll(l);
        }
        listeners.clear();
        for (BukkitTask t : tasks) {
            try {
                t.cancel();
            } catch (Throwable ignored) {
            }
        }
        tasks.clear();
        for (Command c : commands) {
            try {
                c.unregister(Bukkit.getCommandMap());
            } catch (Throwable ignored) {
            }
        }
        commands.clear();
    }

    /** Standalone VibeContext: no watchdog, direct Bukkit registration calls. */
    private static final class StandaloneContext implements VibeContext {
        private final ChickenCreepersExportPlugin plugin;

        StandaloneContext(ChickenCreepersExportPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public Plugin plugin() {
            return plugin;
        }

        @Override
        public Server server() {
            return plugin.getServer();
        }

        @Override
        public String modName() {
            return "ChickenCreepers";
        }

        @Override
        public Logger log() {
            return plugin.getLogger();
        }

        @Override
        public Path dataFolder() {
            plugin.getDataFolder().mkdirs();
            return plugin.getDataFolder().toPath();
        }

        @Override
        public void listen(Listener listener) {
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
            plugin.listeners.add(listener);
        }

        @Override
        public BukkitTask repeat(long delayTicks, long periodTicks, Runnable task) {
            BukkitTask t = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, task, delayTicks, periodTicks);
            plugin.tasks.add(t);
            return t;
        }

        @Override
        public BukkitTask later(long delayTicks, Runnable task) {
            BukkitTask t = plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
            plugin.tasks.add(t);
            return t;
        }

        @Override
        public void command(String name, String description, ModCommandHandler handler) {
            try {
                Command cmd = new Command(name, description, "/" + name, java.util.List.of()) {
                    @Override
                    public boolean execute(CommandSender sender, String label, String[] args) {
                        try {
                            handler.run(sender, args);
                        } catch (Exception e) {
                            sender.sendMessage("Error: " + e.getMessage());
                        }
                        return true;
                    }
                };
                Bukkit.getCommandMap().register("chickencreepers", cmd);
                plugin.commands.add(cmd);
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to register command " + name + ": " + t);
            }
        }

        @Override
        public void action(String name, ModCommandHandler handler) {
            plugin.getLogger().warning("Action '" + name
                    + "' requires VibeCore and is not available in a standalone export.");
        }
    }
}
