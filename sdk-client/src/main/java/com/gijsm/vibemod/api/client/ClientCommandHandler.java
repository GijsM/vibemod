package com.gijsm.vibemod.api.client;

/**
 * Handles {@code /vibec <mod> <name> [args]}. Runs on the render thread.
 * Reply to the player via {@code ctx.toast(...)} or a HUD element.
 */
@FunctionalInterface
public interface ClientCommandHandler {

    void run(ClientContext ctx, String[] args) throws Exception;
}
