package com.gijsm.vibemod.fabric;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;

import com.gijsm.vibemod.fabric.shim.ClientSeam;
import com.gijsm.vibemod.fabric.shim.RegistryTarget;
import com.gijsm.vibemod.fabric.shim.Shims;
import com.gijsm.vibemod.loader.EntrypointAdapter;
import com.gijsm.vibemod.loader.ModAttribution;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModHandle;
import com.gijsm.vibemod.runtime.ModLoadException;

/**
 * The Fabric half of the V3 entrypoint check (Phase 0 §C, both entrypoints in
 * Phase 1 §B, §E).
 *
 * <p>Everything Fabric-shaped about activating a native mod is here rather than
 * in {@code LoaderModHost}, because that class is compiled into the NeoForge jar
 * too and may not name {@code net.fabricmc.*} (§10.4) — and because the client
 * half needs the render thread, which the shared host must not know about
 * either.
 *
 * <p>A Fabric mod has two entrypoints and a real one uses both. The
 * {@code main} half runs synchronously, on the server thread, inside the load
 * that is rolling back if it throws — the caller's contract. The {@code client}
 * half cannot: {@code onInitializeClient} registers HUD elements, keybinds and
 * client events, all of which belong to the render thread, and hopping there
 * synchronously from inside a server-thread load would either deadlock or block
 * the tick. So it is a <em>deferred tracked step</em>: queued onto the client,
 * run under the mod's attribution, and journalled as {@code onInitializeClient}
 * if it throws. The mod is live either way; a client half that fails degrades
 * the mod rather than failing the load, which is the same bargain
 * {@code ctx.client(...)} already makes.
 *
 * <p>A class implementing only {@code ClientModInitializer} on a dedicated
 * server is an error, not a no-op: there is nothing for it to do there and
 * saying so beats a mod that loads, reports success, and does nothing forever.
 * A class implementing both simply skips the client half, exactly as
 * {@code ctx.client(...)} is inert on a server.
 */
public final class FabricEntrypointAdapter implements EntrypointAdapter {

    private static final Logger LOG = Logger.getLogger("VibeMod");

    @Override
    public Runnable adapt(ModHandle handle, ClassLoader loader, Object instance)
            throws ModLoadException {
        boolean common = instance instanceof ModInitializer;
        boolean client = instance instanceof ClientModInitializer;
        if (!common && !client) {
            return null;
        }
        // Null here IS the dedicated-server test: the seam is installed by the
        // client entrypoint, which only exists on a physical client.
        ClientSeam seam = Shims.clientSeam();
        if (client && !common && seam == null) {
            throw new ModLoadException(instance.getClass().getName() + " implements only "
                    + ClientModInitializer.class.getName() + ", and this is a dedicated server: "
                    + "there is no client half to run. Implement "
                    + ModInitializer.class.getName() + " as well, or generate this mod on a client.",
                    null);
        }
        return () -> {
            if (common) {
                // V3 Phase 3 §A: the registration window. It is around the WHOLE
                // entrypoint rather than around Registry.register, and it has to
                // be — Item.<init> writes to BuiltInRegistries.ITEM itself
                // (createIntrusiveHolder), so the constructor of the argument
                // runs, and throws, before the register call it is an argument
                // to is even entered. See RegistrySeam for the disassembly.
                RegistryTarget registries = Shims.registries();
                if (registries == null) {
                    ((ModInitializer) instance).onInitialize();
                } else {
                    registries.withWindow(((ModInitializer) instance)::onInitialize);
                }
            }
            if (seam == null) {
                if (client) {
                    // Inert, and said out loud. A silent skip here is how you get
                    // a bug report that says "the keybind does nothing on my
                    // server" with nothing in the log to explain it.
                    LOG.info("Mod " + handle.name() + " has a ClientModInitializer half; "
                            + "skipping it on a dedicated server");
                }
                return;
            }
            // Tracked whether or not the mod has a client entrypoint: any mod
            // can open a Screen from a client callback it registered later. The
            // flag is the same registration's other half — see deferClientInit.
            AtomicBoolean live = new AtomicBoolean(true);
            handle.track(ModHandle.Kind.CLIENT, Registration.of(() -> {
                live.set(false);
                seam.closeScreensFrom(loader);
            }));
            if (client) {
                deferClientInit(handle, (ClientModInitializer) instance, seam, live);
            }
        };
    }

    /**
     * Queues {@code onInitializeClient()} onto the render thread.
     *
     * <p>The liveness check is not belt-and-braces: a mod can be disabled
     * between the load finishing on the server thread and this running a frame
     * later (a watchdog trip on another mod's tick is enough), and initialising
     * a mod that is already torn down would register client callbacks onto a
     * handle nothing will ever drain again.
     *
     * <p>It reads a flag the teardown clears rather than {@code handle.enabled()},
     * and the difference is a real race rather than pedantry: this runnable is
     * queued from <em>inside</em> the activation, and the lifecycle sets
     * {@code enabled} only after the activation returns. Asking the handle could
     * therefore skip the client half of a perfectly healthy mod, purely on
     * thread timing.
     */
    private static void deferClientInit(ModHandle handle, ClientModInitializer instance,
                                        ClientSeam seam, AtomicBoolean live) {
        seam.runOnRenderThread(() -> {
            if (!live.get()) {
                LOG.fine(() -> "Skipping onInitializeClient for " + handle.name()
                        + ": it was disabled before the render thread got to it");
                return;
            }
            ModAttribution.runAs(handle, () -> {
                try {
                    instance.onInitializeClient();
                    LOG.info("Mod " + handle.name() + " initialised its client half");
                } catch (Throwable t) {
                    LOG.log(java.util.logging.Level.WARNING,
                            "onInitializeClient failed for mod " + handle.name(), t);
                    seam.failures().markFailure(handle.name(), t, "onInitializeClient");
                }
            });
        });
    }

    @Override
    public String describe() {
        return ModInitializer.class.getName() + " (or " + ClientModInitializer.class.getName() + ")";
    }
}
