package com.gijsm.vibemine.api;

import org.bukkit.command.CommandSender;

/** Handler for a mod-registered command or named action. Runs on the main thread. */
@FunctionalInterface
public interface ModCommandHandler {
    void run(CommandSender sender, String[] args) throws Exception;
}
