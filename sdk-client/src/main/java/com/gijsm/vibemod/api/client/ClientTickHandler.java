package com.gijsm.vibemod.api.client;

/** Runs at the end of every client tick, on the RENDER thread. */
@FunctionalInterface
public interface ClientTickHandler {

    void tick(ClientContext ctx);
}
