package com.gijsm.vibemod.platform;

import java.util.UUID;

import net.kyori.adventure.audience.Audience;

/**
 * Whoever invoked a command: a player or the console. Adventure is the
 * message currency on every platform.
 */
public interface Sender {

    /** Where replies go. */
    Audience audience();

    /** Display name ("Console" for the console). */
    String name();

    /** Permission check; console always passes. Loaders map to their permission API or op level. */
    boolean hasPermission(String permission);

    /** The player's UUID, or {@code null} for the console. */
    UUID idOrNull();
}
