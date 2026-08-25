package com.gijsm.vibemod.api;

import net.minecraft.commands.CommandSourceStack;

/**
 * Handler for a mod-registered command or named action. Runs on the main
 * server thread.
 *
 * <p>Mod flavor (ARCHITECTURE-V2 §4.1): the source is Minecraft's own
 * {@link CommandSourceStack}, so {@code src.getPlayer()} gives the
 * {@code ServerPlayer} (null for the console) and
 * {@code src.sendSuccess(() -> Component.literal("..."), false)} replies.
 */
@FunctionalInterface
public interface ModCommandHandler {
    void run(CommandSourceStack src, String[] args) throws Exception;
}
