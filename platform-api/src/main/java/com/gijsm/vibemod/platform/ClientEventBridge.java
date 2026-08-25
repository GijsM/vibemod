package com.gijsm.vibemod.platform;

import com.gijsm.vibemod.api.client.ClientCommandHandler;
import com.gijsm.vibemod.api.client.ClientTickHandler;
import com.gijsm.vibemod.api.client.HudRenderer;
import com.gijsm.vibemod.api.client.KeyLease;

/**
 * Client-side hooks for generated mods (fabric/neoforge hosts on a physical
 * client only; absent on Paper and dedicated servers — probe
 * {@link PlatformInfo#hasClient()}).
 *
 * <p>Architecture (ARCHITECTURE-V2 §8): the host registers exactly ONE
 * permanent platform hook per surface at client init and dispatches through
 * mutable registries — Fabric events cannot unregister and NeoForge's
 * HUD/keybind registration fires only at startup, so indirection is the
 * cross-loader design, not a workaround. Every dispatched callback is
 * try/catch-wrapped into the mod's error-storm accounting: a throwing HUD
 * renderer auto-disables its mod and must never crash the render loop.
 *
 * <p>Registration methods are thread-safe. Callbacks run on the RENDER
 * thread — they must touch only {@code ClientContext} state, never server
 * state (ARCHITECTURE-V2 §8.4).
 */
public interface ClientEventBridge {

    /** Adds a HUD element drawn every frame while in-game. */
    Registration hud(String modName, String elementId, HudRenderer renderer);

    /** Runs at the end of every client tick. */
    Registration clientTick(String modName, ClientTickHandler handler);

    /**
     * Leases one of the 8 pooled key slots ("VibeMod Slot 1–8", pre-registered
     * at client init, default unbound). The host auto-binds {@code defaultKey}
     * only when the user never manually rebound that slot; user rebinds always
     * win. {@code onPress} fires on the render thread.
     *
     * @throws IllegalStateException when all slots are leased — surfaces as a
     *         normal mod-load diagnostic
     */
    KeyLease leaseKey(String modName, String label, String defaultKey, Runnable onPress);

    /** Registers {@code /vibec <mod> <name> [args]} (static root, dynamic subtree — §8.3). */
    Registration clientCommand(String modName, String name, String description, ClientCommandHandler handler);

    /** Fire-and-forget UI-positioned sound for the local player, e.g. {@code "minecraft:ui.button.click"}. */
    void playUiSound(String soundId, float volume, float pitch);

    /** Pops a toast notification. */
    void toast(String title, String body);

    /** True when the local player is in a world. */
    boolean inGame();
}
