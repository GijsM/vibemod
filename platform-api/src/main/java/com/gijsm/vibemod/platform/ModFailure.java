package com.gijsm.vibemod.platform;

/**
 * Where a caught mod failure goes: core's mod lifecycle implements this, and
 * every host bridge that dispatches into generated code ({@link EventBridge},
 * {@link CommandBridge}, {@link ClientEventBridge}) reports through it.
 *
 * <p>The sink owns the policy — mark the mod degraded, record the error,
 * announce the degrade episode once, count towards the error storm. Bridges
 * only decide the {@code where} label ({@code "listener:PlayerJoinEvent"},
 * {@code "command:boom"}, {@code "client"}, …), which is the dimension the
 * client dispatch needs (ARCHITECTURE-V2 §8.3).
 *
 * <p>Never throws.
 */
@FunctionalInterface
public interface ModFailure {

    void markFailure(String modName, Throwable cause, String where);
}
