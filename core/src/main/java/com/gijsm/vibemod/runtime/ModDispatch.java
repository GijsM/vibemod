package com.gijsm.vibemod.runtime;

import java.util.logging.Level;
import java.util.logging.Logger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.gijsm.vibemod.platform.ModFailure;
import com.gijsm.vibemod.platform.Sender;

/**
 * The one way generated-mod code is ever entered: timed by the
 * {@link Watchdog}, wrapped so nothing thrown escapes into the platform, and
 * reported through a {@link ModFailure} sink.
 *
 * <p>In v1 this logic was copy-pasted three times inside {@code ModRegistry}
 * ({@code runWrapped} for commands and actions, {@code wrapTask} for scheduled
 * work, an inline {@code EventExecutor} for listeners). It is one class now
 * because the host bridges need it too: {@code EventBridge} and
 * {@code CommandBridge} are contractually required to do exactly this
 * (ARCHITECTURE-V2 §2), and on the loaders so is every client dispatch entry
 * (§8.1).
 */
public final class ModDispatch {

    private final Watchdog watchdog;
    private final ModFailure failure;

    public ModDispatch(Watchdog watchdog, ModFailure failure) {
        this.watchdog = watchdog;
        this.failure = failure;
    }

    /** A mod entry point that may throw a checked exception. */
    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    /**
     * Runs {@code body} on behalf of {@code modName}, timed and guarded.
     *
     * @param sender who to apologize to when it throws, or null for
     *               fire-and-forget entry points (tasks, listeners)
     * @param where  the error journal's {@code where} dimension, e.g.
     *               {@code "command:boom"}, {@code "listener:PlayerJoinEvent"},
     *               {@code "task"}, {@code "client"}
     */
    public void run(String modName, Sender sender, String where, Body body) {
        try {
            watchdog.time(modName, () -> {
                try {
                    body.run();
                } catch (Throwable t) {
                    throw new HandlerFailure(t);
                }
            });
        } catch (HandlerFailure f) {
            report(modName, sender, where, f.getCause());
        } catch (Throwable t) {
            report(modName, sender, where, t);
        }
    }

    private void report(String modName, Sender sender, String where, Throwable cause) {
        Logger.getLogger("VibeMod." + modName)
                .log(Level.WARNING, "Mod " + modName + " threw in " + where, cause);
        if (sender != null) {
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            try {
                sender.audience().sendMessage(
                        Component.text("Error in mod command: " + msg, NamedTextColor.RED));
            } catch (Throwable ignored) {
                // the apology is best-effort; never let it mask the original failure
            }
        }
        failure.markFailure(modName, cause, where);
    }

    /** Unchecked carrier so a checked mod exception can cross the watchdog's {@link Runnable} boundary. */
    private static final class HandlerFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        HandlerFailure(Throwable cause) {
            super(cause);
        }
    }
}
