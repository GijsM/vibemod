package com.gijsm.vibemod.loader.content;

/**
 * Starts a client resource reload (V3 Phase 2 §C/§D).
 *
 * <p>An interface rather than a direct call because {@code net.minecraft.client.Minecraft}
 * does not exist on a dedicated server, and {@link ReloadCoordinator} is held by
 * code that runs on one. Same rule Phase 1 wrote down for {@code ClientSeam}:
 * a client type in a class a server loads is a {@code NoClassDefFoundError}
 * nothing in this repo would catch.
 */
@FunctionalInterface
public interface ClientReloader {

    /**
     * Reloads the client's resource packs and runs {@code done} when the reload
     * has finished (successfully or not).
     *
     * <p>Called on the server thread; the implementation hops to the render
     * thread itself. {@code done} may be called on either thread.
     */
    void reload(Runnable done);
}
