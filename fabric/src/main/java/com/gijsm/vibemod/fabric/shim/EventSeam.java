package com.gijsm.vibemod.fabric.shim;

import net.fabricmc.fabric.api.event.Event;

/**
 * What {@link Shims} delegates a rewritten {@code Event.register} call to
 * (V3 Phase 0 §B).
 *
 * <p>A one-method interface rather than a direct reference to
 * {@link EventFanout}, for a reason that is entirely about testability: the
 * surgeon self-test rewrites a real class, defines it, and runs it, and it
 * needs somewhere for the registration to land that is not a live server. With
 * this seam it installs a recorder; without it the test could only assert on
 * the constant pool, which proves the rewrite happened but not that it
 * <em>works</em>.
 */
@FunctionalInterface
public interface EventSeam {

    /**
     * Called instead of {@code event.register(listener)} in a generated mod.
     *
     * @param event    the loader event the mod named
     * @param listener the mod's callback, typically a lambda
     */
    void register(Event<?> event, Object listener);
}
