package com.gijsm.vibemod.platform.ui;

import java.util.UUID;

/**
 * Shows screens to players. Two v1 implementations: the Paper dialog renderer
 * (and its loader sibling) where {@code PlatformInfo.hasDialogs()}, and the
 * universal chat renderer (core) everywhere.
 *
 * <p>Renderer obligations (normative, ARCHITECTURE-V2 §3): show on/hop to the
 * main thread; callbacks one-shot and main-thread; callback exceptions never
 * propagate into the platform; submitted values re-validated server-side.
 */
public interface UiRenderer {

    void show(UUID player, Screen screen);
}
