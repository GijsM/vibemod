package com.gijsm.vibemod.platform.ui;

import java.util.UUID;

/**
 * A screen button's callback. Renderers invoke it ON THE MAIN THREAD, at most
 * once per shown screen (one-shot), and never let a thrown exception
 * propagate into the platform (catch → log → error message to the player).
 */
@FunctionalInterface
public interface UiCallback {

    void handle(UiResponse response, UUID player);
}
