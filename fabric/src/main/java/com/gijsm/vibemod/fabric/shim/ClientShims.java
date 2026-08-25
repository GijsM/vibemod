package com.gijsm.vibemod.fabric.shim;

import java.util.logging.Logger;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import com.gijsm.vibemod.loader.ModAttribution;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModHandle;

/**
 * The client half of {@link Shims} (V3 Phase 1 §C, §D): the static methods a
 * generated mod's rewritten bytecode calls instead of
 * {@code KeyMappingHelper.registerKeyMapping} and
 * {@code HudElementRegistry.add*}.
 *
 * <p>A separate class from {@link Shims} on purpose. {@code Shims} is loaded on
 * every dedicated server (the event seam lives there), and every method
 * descriptor in this file names a class that exists only on a physical client.
 * Splitting them means the server never has a reason to resolve one — and the
 * mod that tries anyway does not get a {@code NoClassDefFoundError} either,
 * because on a dedicated server those classes are not on the compile classpath
 * and javac refuses the source long before the surgeon sees it.
 *
 * <p>Unlike {@code Event.register}, both of these are {@code invokestatic} call
 * sites with no receiver, so the seam entries are
 * {@link com.gijsm.vibemod.loader.surgeon.Seam#staticCall} and the descriptors
 * below are the originals, unchanged.
 */
public final class ClientShims {

    private static final Logger LOG = Logger.getLogger(ClientShims.class.getName());

    /** Installed once per process by the client entrypoint; null on a dedicated server. */
    private static volatile ClientRegistrations registrations;

    private ClientShims() {
    }

    /** Installs the process-lived client registrations. Called from {@code onInitializeClient()}. */
    public static void install(ClientRegistrations client) {
        ClientShims.registrations = client;
    }

    /** The installed client registrations, or null before client init / on a server. */
    public static ClientRegistrations registrations() {
        return registrations;
    }

    /**
     * {@code KeyMappingHelper.registerKeyMapping(KeyMapping) -> KeyMapping}.
     *
     * <p>Returns a <em>different</em> mapping than it was given, and that is the
     * seam's whole point rather than a wart. The game's key registry is closed
     * long before a generated mod exists, so the mapping the mod constructed can
     * never read as pressed; the host hands back one of eight slots it
     * pre-registered at client startup, which polls normally and can be handed
     * back when the mod is disabled. The mod's own
     * {@code consumeClick()}/{@code isDown()} code is unchanged — it is simply
     * asking a different object, and the prompt tells the model the physical key
     * may not be the one it asked for.
     */
    public static KeyMapping registerKeyMapping(KeyMapping requested) {
        ModHandle handle = require();
        ClientRegistrations.Leased leased = require(handle).leaseKeyMapping(handle.name(), requested);
        handle.track(ModHandle.Kind.NATIVE, leased.release());
        LOG.fine(() -> "Mod " + handle.name() + " leased key slot " + leased.mapping().getName());
        return leased.mapping();
    }

    /**
     * {@code HudElementRegistry.addFirst/addLast(Identifier, HudElement)}.
     *
     * <p>All the ordering overloads land here and the ordering is dropped, which
     * is not a silent drop: the host owns exactly one permanent HUD element
     * (§8.1) and a mod's element is a revocable entry behind it, so "first" and
     * "last" would be claims about a list the mod is not in. What is preserved
     * is everything that matters — the element draws every frame, is watchdogged,
     * is detached the moment it throws, and disappears when the mod does.
     */
    public static void hudAdd(Identifier id, HudElement element) {
        ModHandle handle = require();
        Registration attached = require(handle).addHud(handle.name(), element);
        handle.track(ModHandle.Kind.NATIVE, attached);
        LOG.fine(() -> "Mod " + handle.name() + " attached HUD element " + id);
    }

    /**
     * {@code HudElementRegistry.attachElementBefore/After(Identifier, Identifier, HudElement)}.
     *
     * <p>Rewritten too, because generated code plausibly calls it and the
     * alternative is a mod attaching an element the host cannot revoke. The
     * anchor is dropped for the same reason the ordering above is.
     */
    public static void hudAttach(Identifier anchor, Identifier id, HudElement element) {
        LOG.fine(() -> "Ignoring HUD anchor " + anchor + " (mod HUD elements share one host element)");
        hudAdd(id, element);
    }

    private static ModHandle require() {
        ModHandle handle = ModAttribution.current();
        if (handle == null) {
            // Never a drop, for the same reason Event.register is not: a client
            // registration VibeMod cannot attribute is one it could never revoke.
            throw new IllegalStateException(
                    "A client registration reached VibeMod outside any mod's own code, so it could "
                            + "never be revoked. Register from onInitializeClient() or from inside "
                            + "one of your own client callbacks.");
        }
        return handle;
    }

    private static ClientRegistrations require(ModHandle handle) {
        ClientRegistrations live = registrations;
        if (live == null) {
            throw new UnsupportedOperationException("Mod " + handle.name()
                    + " tried to register a client feature (keybind or HUD) where there is no "
                    + "client. Put client code in a ClientModInitializer; on a dedicated server it "
                    + "is simply not run.");
        }
        return live;
    }
}
