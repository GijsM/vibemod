package com.gijsm.vibemod.platform;

/**
 * Dynamic top-level command registration (server side).
 *
 * <p>Paper: command-map insertion + {@code Player#updateCommands} (the v1
 * {@code DynamicCommands} logic). Loaders: live Brigadier dispatcher
 * injection + command-tree resync to online players. Handlers run on the
 * main server thread; exceptions are caught by the bridge and routed to the
 * mod's error accounting, never propagated into the platform.
 */
public interface CommandBridge {

    /** Handler for one dynamic command invocation. Main thread. */
    @FunctionalInterface
    interface CommandHandler {
        void run(Sender sender, String[] args) throws Exception;
    }

    /**
     * Registers {@code /name}. The returned registration removes the command
     * and resyncs clients. When top-level registration is unavailable the
     * implementation may register nothing and return an inactive registration —
     * callers fall back to {@code /vibe do} actions (v1 behavior).
     */
    Registration register(String name, String description, String modName, CommandHandler handler);

    /** Pushes the current command tree to all online players. */
    void resyncAll();
}
