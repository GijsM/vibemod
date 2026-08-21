package com.gijsm.vibemine.runtime;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Live echo of a mod's {@code ctx.log()} output (including caught exceptions,
 * with zero {@code VibeContext} API change) to online operators, gated per
 * mod by {@link #enabled}. Attaches one {@link java.util.logging.Handler} per
 * tracked mod to {@code Logger.getLogger("VibeMod.<mod>")}.
 *
 * Both the {@link Logger} and its {@link Handler} are held here with a
 * STRONG reference: {@code java.util.logging}'s {@code LogManager} holds
 * loggers only weakly, so without an external strong reference the logger
 * (and any handler attached to it) can be silently garbage-collected.
 */
public final class DebugEcho {

    private static final int RATE_LIMIT = 10;
    private static final long RATE_WINDOW_MS = 5_000L;

    private final Plugin plugin;
    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();
    private final Map<String, EchoHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Boolean> overrides = new ConcurrentHashMap<>();
    private volatile boolean defaultEnabled;

    public DebugEcho(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Sets the fallback used by {@link #enabled} for any mod without an explicit override. */
    public void setDefault(boolean on) {
        this.defaultEnabled = on;
    }

    /** Starts tracking a mod: attaches its (idempotent) log handler. Call on load. */
    public void track(String mod) {
        ensureAttached(mod);
    }

    /** Whether debug echo is currently on for this mod: its explicit override, else the default. */
    public boolean enabled(String mod) {
        Boolean override = overrides.get(lower(mod));
        return override != null ? override : defaultEnabled;
    }

    /** Flips the mod's echo state and returns the new value. */
    public boolean toggle(String mod) {
        boolean next = !enabled(mod);
        set(mod, next);
        return next;
    }

    /** Sets an explicit per-mod echo override. */
    public void set(String mod, boolean on) {
        ensureAttached(mod);
        overrides.put(lower(mod), on);
    }

    /** Detaches the handler and drops all state for a mod. Call on unload. */
    public void forget(String mod) {
        String key = lower(mod);
        overrides.remove(key);
        Logger logger = loggers.remove(key);
        EchoHandler handler = handlers.remove(key);
        if (logger != null && handler != null) {
            logger.removeHandler(handler);
        }
    }

    private void ensureAttached(String mod) {
        String key = lower(mod);
        handlers.computeIfAbsent(key, k -> {
            Logger logger = Logger.getLogger("VibeMod." + mod);
            loggers.put(key, logger);
            EchoHandler handler = new EchoHandler(mod);
            logger.addHandler(handler);
            return handler;
        });
    }

    private void deliver(Component message) {
        Runnable task = () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.hasPermission("vibe.admin")) {
                    p.sendMessage(message);
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String formatMessage(LogRecord record) {
        String msg = record.getMessage();
        if (msg == null) {
            return "";
        }
        Object[] params = record.getParameters();
        if (params != null && params.length > 0 && msg.indexOf('{') >= 0) {
            try {
                return MessageFormat.format(msg, params);
            } catch (IllegalArgumentException e) {
                return msg;
            }
        }
        return msg;
    }

    /** One mod's JUL handler: formats + rate-limits + delivers to online {@code vibe.admin} players. */
    private final class EchoHandler extends Handler {

        private final String mod;
        private long windowStart = System.currentTimeMillis();
        private int windowCount;
        private int suppressedInWindow;

        EchoHandler(String mod) {
            this.mod = mod;
        }

        @Override
        public void publish(LogRecord record) {
            if (!isLoggable(record) || !enabled(mod)) {
                return;
            }
            List<Component> toSend = new ArrayList<>(2);
            synchronized (this) {
                long now = System.currentTimeMillis();
                if (now - windowStart > RATE_WINDOW_MS) {
                    if (suppressedInWindow > 0) {
                        toSend.add(line("… suppressed " + suppressedInWindow + " lines"));
                    }
                    windowStart = now;
                    windowCount = 0;
                    suppressedInWindow = 0;
                }
                windowCount++;
                if (windowCount > RATE_LIMIT) {
                    suppressedInWindow++;
                } else {
                    toSend.add(line(formatLine(record)));
                }
            }
            for (Component c : toSend) {
                deliver(c);
            }
        }

        private String formatLine(LogRecord record) {
            String msg = formatMessage(record);
            Throwable thrown = record.getThrown();
            if (thrown == null) {
                return msg;
            }
            String tmsg = thrown.getMessage();
            return msg + " (" + thrown.getClass().getSimpleName() + (tmsg != null ? ": " + tmsg : "") + ")";
        }

        private Component line(String text) {
            return Component.text("⚙ " + mod + ": " + text, NamedTextColor.GOLD);
        }

        @Override
        public void flush() {
            // No buffering to flush; every publish() call delivers (or rate-limit-drops) immediately.
        }

        @Override
        public void close() {
            // Nothing to release; forget(mod) removes this handler from its logger.
        }
    }
}
