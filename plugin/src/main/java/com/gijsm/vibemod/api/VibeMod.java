package com.gijsm.vibemod.api;

/**
 * Deprecated bridge kept so mods generated before the v3 rename (which
 * declare {@code implements VibeMod}) keep recompiling from stored source.
 * New code — and the generation prompt — use {@link Mod}.
 */
@Deprecated
public interface VibeMod extends Mod {
}
