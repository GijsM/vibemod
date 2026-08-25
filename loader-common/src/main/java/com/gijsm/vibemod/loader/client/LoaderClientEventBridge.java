package com.gijsm.vibemod.loader.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import com.gijsm.vibemod.api.client.ClientCommandHandler;
import com.gijsm.vibemod.api.client.ClientContext;
import com.gijsm.vibemod.api.client.ClientTickHandler;
import com.gijsm.vibemod.api.client.HudRenderer;
import com.gijsm.vibemod.api.client.KeyLease;
import com.gijsm.vibemod.loader.LoaderText;
import com.gijsm.vibemod.platform.ClientEventBridge;
import com.gijsm.vibemod.platform.ModFailure;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.Watchdog;

/**
 * The client half of the generated-mod surface (ARCHITECTURE-V2 §8) — the part
 * that is identical on Fabric and NeoForge.
 *
 * <p><b>Host-owned dispatchers (§8.1).</b> Exactly one permanent registration
 * per surface is made at client init — one HUD element/layer, one client-tick
 * listener, eight {@code KeyMapping}s, one {@code /vibec} root — and generated
 * mods attach to and detach from mutable registries behind them. This is not
 * indirection for its own sake: HUD elements and key mappings can only be
 * registered during client startup, Fabric events cannot be unregistered at all,
 * and mods load and unload at any moment. The indirection is the only shape that
 * satisfies all three.
 *
 * <p><b>Nothing a mod does may crash the render loop.</b> Every dispatch is
 * wrapped: the exception is journalled against the mod through {@link ModFailure}
 * with {@code where="client"}, the mod's error-storm counter trips, and the storm
 * handler disables it. A HUD renderer that throws on every frame therefore
 * disables its own mod within a second or so instead of taking the client with
 * it. On top of that, an entry that throws is dropped from the dispatch list
 * <em>immediately</em> — waiting for the storm threshold would mean up to ten
 * more throwing frames, and the render thread runs at sixty of them a second.
 *
 * <p><b>Threading (§8.4).</b> Registration methods are called from the server
 * thread (inside {@code ctx.client(...)}) and are all thread-safe; callbacks run
 * on the render thread and are timed by a render-thread {@link Watchdog} with
 * the same budgets and the same trip path as the server one.
 *
 * <p>A subclass supplies three things: the loader's permanent hooks (which call
 * {@link #renderHuds} and {@link #clientTick}), the key-mapping registration
 * {@link #initSlots} is handed, and the {@code /vibec} Brigadier tree — the one
 * place the two loaders genuinely differ, because Fabric's client commands take
 * a {@code FabricClientCommandSource} and NeoForge's take a plain
 * {@code CommandSourceStack}.
 */
public abstract class LoaderClientEventBridge implements ClientEventBridge {

    private static final Logger LOG = Logger.getLogger(LoaderClientEventBridge.class.getName());

    /** The key pool's size (§8.2). Eight is the doc's number and the Controls screen's patience. */
    public static final int KEY_SLOTS = 8;

    /** The namespace/category id both loaders register the pool under. */
    protected static final String KEY_CATEGORY = "vibemod";

    private final ModFailure failure;
    private final Watchdog watchdog;

    private final List<Bound<HudRenderer>> huds = new CopyOnWriteArrayList<>();
    /**
     * The V3 native HUD list (Phase 1 §D): entries that draw with the game's own
     * arguments rather than through {@link com.gijsm.vibemod.api.client.HudCanvas}.
     * A separate list rather than an adapter into {@link #huds} because the two
     * take different arguments and because the gate wants to see them counted
     * apart.
     */
    private final List<Bound<RawHudRenderer>> rawHuds = new CopyOnWriteArrayList<>();
    private final List<Bound<ClientTickHandler>> tickers = new CopyOnWriteArrayList<>();
    private final Map<String, Bound<ClientCommandHandler>> clientCommands = new ConcurrentHashMap<>();
    private final Slot[] slots = new Slot[KEY_SLOTS];

    private final LoaderHudCanvas canvas = new LoaderHudCanvas();
    /** The context handed to tick and command callbacks; stateless, so one is enough. */
    private final ClientContext sharedContext;

    protected LoaderClientEventBridge(ModFailure failure, Watchdog watchdog) {
        this.failure = failure;
        this.watchdog = watchdog;
        this.sharedContext = new LoaderClientContext(this, null);
    }

    /**
     * Installs the permanent hooks. Called once, from the client entrypoint —
     * before a world exists and before any mod is loaded, because HUD elements
     * and key mappings can only be registered then.
     */
    public abstract void install();

    /**
     * The render-thread watchdog, for host code that dispatches into mod code on
     * the render thread from outside this class (V3 Phase 1 §B: the event
     * fanout's client half).
     */
    public final Watchdog renderWatchdog() {
        return watchdog;
    }

    /** Where a render-thread failure is journalled. Same sink, same storm counter. */
    public final ModFailure failures() {
        return failure;
    }

    // -------------------------------------------------------- the key pool

    /**
     * Builds the eight pooled {@code KeyMapping}s and hands each to the loader's
     * own registration.
     *
     * @param register the loader's registrar; returns the mapping to keep
     * @param category the already-registered category the slots belong to
     */
    protected final void initSlots(UnaryOperator<KeyMapping> register, KeyMapping.Category category) {
        for (int i = 0; i < KEY_SLOTS; i++) {
            KeyMapping mapping = new KeyMapping("key.vibemod.slot" + (i + 1),
                    InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category);
            slots[i] = new Slot(i + 1, register.apply(mapping));
        }
    }

    /**
     * Leases the lowest free slot (§8.2).
     *
     * <p>{@code defaultKey} is auto-bound only when the user has never touched
     * that slot themselves. A rebind the player made in the Controls screen
     * always wins — a generated mod grabbing a key someone deliberately assigned
     * elsewhere would be a genuinely infuriating bug — and releasing a lease
     * only unbinds what we ourselves bound.
     */
    @Override
    public final synchronized KeyLease leaseKey(String modName, String label, String defaultKey,
                                                Runnable onPress) {
        for (Slot slot : slots) {
            if (slot != null && slot.lease == null) {
                return slot.lease(modName, label, defaultKey, onPress);
            }
        }
        throw new IllegalStateException("All " + KEY_SLOTS + " VibeMod key slots are in use; "
                + "disable a mod that uses a keybind first");
    }

    /**
     * Leases a slot for a mod that built its <em>own</em> {@code KeyMapping}
     * (V3 Phase 1 §C) and hands back the pool's mapping.
     *
     * <p>Returning the slot's real {@code KeyMapping} rather than the mod's own
     * is the entire trick. A {@code KeyMapping} only reads as pressed if the
     * game knows about it, and the game's registry is closed long before a
     * generated mod exists — so the mod's own instance would poll {@code false}
     * forever. Handing back a pre-registered slot means the mod's ordinary
     * {@code consumeClick()}/{@code isDown()} polling simply works, at the price
     * the prompt states plainly: the physical key may not be the one it asked
     * for.
     *
     * <p>The requested mapping's default key is honoured on exactly the same
     * terms as {@link #leaseKey}'s {@code defaultKey}: only when the slot is
     * still unbound or was last bound by us, never over a binding the player
     * chose.
     *
     * @param modName    who to journal against and to release with
     * @param requested  the mapping the mod constructed — read for its
     *                   translation key, category and default binding, never
     *                   registered
     * @return the pooled mapping the mod should poll, and the lease that frees it
     */
    public final synchronized NativeKeyLease leaseSlotFor(String modName, KeyMapping requested) {
        for (Slot slot : slots) {
            if (slot != null && slot.lease == null) {
                Slot.Lease lease = slot.leaseNative(modName, requested);
                return new NativeKeyLease(slot.mapping, lease::release);
            }
        }
        throw new IllegalStateException("All " + KEY_SLOTS + " VibeMod key slots are in use; "
                + "disable a mod that uses a keybind first");
    }

    /**
     * What a native keybind registration gets back: the pooled mapping to poll,
     * and the revocation the mod's handle tracks.
     */
    public record NativeKeyLease(KeyMapping mapping, Runnable release) {
    }

    // ------------------------------------------------------ the dispatchers

    /**
     * One frame of the host's single HUD element/layer. Render thread only.
     *
     * <p>Takes the {@link DeltaTracker} rather than a pre-computed partial tick
     * since V3 Phase 1: a native {@code HudElement} is handed the tracker itself
     * by the loader, and reducing it to a float here would mean the host could
     * not pass on what the game gave it.
     */
    public final void renderHuds(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        // The native entries first: they are ordinary Fabric HUD elements and
        // expect to draw underneath anything the curated canvas layers on top.
        for (Bound<RawHudRenderer> bound : rawHuds) {
            guard(bound, rawHuds, "client:hud", () -> bound.handler.render(graphics, delta));
        }
        if (huds.isEmpty()) {
            return;
        }
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        canvas.bind(graphics);
        try {
            for (Bound<HudRenderer> bound : huds) {
                guard(bound, huds, "client:hud", () -> bound.handler.render(canvas, partialTick));
            }
        } finally {
            canvas.unbind();
        }
    }

    /** One client tick: drain the key pool, then run every attached tick handler. */
    public final void clientTick() {
        pollKeys();
        for (Bound<ClientTickHandler> bound : tickers) {
            guard(bound, tickers, "client:tick", () -> bound.handler.tick(sharedContext));
        }
    }

    /** Reads every leased slot once per client tick and fires the presses. */
    private void pollKeys() {
        for (Slot slot : slots) {
            if (slot == null) {
                continue;
            }
            Slot.Lease lease = slot.lease;
            // A native lease has no onPress: the mod polls consumeClick() itself,
            // and draining the queue here would eat every press before it got
            // there.
            if (lease == null || lease.onPress == null) {
                continue;
            }
            // consumeClick() drains the queued presses: a key tapped three times
            // between ticks fires three times, which is what a player expects.
            while (slot.mapping.consumeClick()) {
                Bound<Runnable> bound = new Bound<>(lease.modName, lease.onPress);
                guard(bound, null, "client:key", lease.onPress::run);
            }
        }
    }

    // ------------------------------------------------------- registrations

    @Override
    public final Registration hud(String modName, String elementId, HudRenderer renderer) {
        return attach(huds, modName, renderer);
    }

    /**
     * Attaches a native HUD element to the same permanent pipeline (V3 Phase 1
     * §D).
     *
     * <p>Same tracking, same watchdog, same instant-detach-on-throw as every
     * other client dispatch: the whole point of routing a real
     * {@code HudElementRegistry.addLast} through here is that a HUD element a
     * mod wrote itself must be as revocable and as harmless as one it asked the
     * host for.
     */
    public final Registration rawHud(String modName, RawHudRenderer renderer) {
        return attach(rawHuds, modName, renderer);
    }

    @Override
    public final Registration clientTick(String modName, ClientTickHandler handler) {
        return attach(tickers, modName, handler);
    }

    @Override
    public final Registration clientCommand(String modName, String name, String description,
                                            ClientCommandHandler handler) {
        String key = key(modName, name);
        Bound<ClientCommandHandler> bound = new Bound<>(modName, handler);
        clientCommands.put(key, bound);
        // The tree itself never changes (the suggestion providers read the live
        // map), but the client's cached completions do need a nudge.
        refreshCompletions();
        return Registration.of(() -> {
            clientCommands.remove(key, bound);
            refreshCompletions();
        });
    }

    /** Asks the loader to re-send the client command tree; must never throw. */
    protected abstract void refreshCompletions();

    // --------------------------------------------------------------- /vibec

    /** Every registered {@code "<mod> <command>"} key, sorted. Drives the suggestion providers. */
    public final List<String> clientCommandKeys() {
        List<String> keys = new ArrayList<>(clientCommands.keySet());
        keys.sort(null);
        return keys;
    }

    /** The reply to a bare {@code /vibec} or {@code /vibec list}. */
    public final String listCommandsText() {
        List<String> keys = clientCommandKeys();
        if (keys.isEmpty()) {
            return "No mod client commands are registered.";
        }
        return "VibeMod client commands: "
                + String.join(", ", keys.stream().map(k -> "/vibec " + k).toList());
    }

    /**
     * Routes {@code /vibec <mod> <command> [args]} into the live registry.
     *
     * @return false when no such command is registered, so the caller can report it
     */
    public final boolean runClientCommand(String mod, String command, String[] args) {
        Bound<ClientCommandHandler> bound = clientCommands.get(key(mod, command));
        if (bound == null) {
            return false;
        }
        guard(bound, null, "client:command:" + command, () -> bound.handler.run(sharedContext, args));
        return true;
    }

    private static String key(String modName, String command) {
        return modName.toLowerCase(Locale.ROOT) + " " + command.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------ odds and ends

    @Override
    public final void playUiSound(String soundId, float volume, float pitch) {
        Identifier id = LoaderText.idOrNull(soundId);
        if (id == null) {
            return;
        }
        Optional<Holder.Reference<SoundEvent>> event = BuiltInRegistries.SOUND_EVENT.get(id);
        if (event.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        // forUI(SoundEvent, pitch, volume) — only the two-arg overload takes a Holder.
        SoundEvent sound = event.get().value();
        client.execute(() -> client.getSoundManager()
                .play(SimpleSoundInstance.forUI(sound, pitch, volume)));
    }

    @Override
    public final void toast(String title, String body) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.gui.toastManager().addToast(new SystemToast(
                new SystemToast.SystemToastId(),
                Component.literal(title == null ? "VibeMod" : title),
                Component.literal(body == null ? "" : body))));
    }

    @Override
    public final boolean inGame() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && client.player != null;
    }

    /** How many entries each surface currently holds. The acceptance gate asserts on these. */
    public final String describeState() {
        int leased = 0;
        for (Slot slot : slots) {
            if (slot != null && slot.lease != null) {
                leased++;
            }
        }
        return "huds=" + huds.size() + " nativeHuds=" + rawHuds.size()
                + " tickers=" + tickers.size()
                + " clientCommands=" + clientCommands.size()
                + " keysLeased=" + leased + "/" + KEY_SLOTS;
    }

    // ---------------------------------------------------------------- plumbing

    private <H> Registration attach(List<Bound<H>> list, String modName, H handler) {
        if (handler == null) {
            return Registration.inactive();
        }
        Bound<H> bound = new Bound<>(modName, handler);
        list.add(bound);
        return Registration.of(() -> list.remove(bound));
    }

    /**
     * One guarded, timed dispatch into mod code on the render thread.
     *
     * <p>Deliberately not {@code ModDispatch}: that class reports to a
     * {@code Sender} and is written for the server thread's watchdog. The policy
     * is the same — time it, catch everything, journal it against the mod — but
     * with one addition the render thread needs. A throwing entry is removed
     * from its dispatch list on the spot. The error-storm threshold is ten
     * failures in a window; at sixty frames a second, waiting for it means the
     * mod throws sixty times before anything disables it, and every one of those
     * is a stack trace built on the render thread.
     */
    private <H> void guard(Bound<H> bound, List<Bound<H>> removeFrom, String where, Body body) {
        try {
            watchdog.time(bound.modName, () -> {
                try {
                    body.run();
                } catch (Throwable t) {
                    throw new Failure(t);
                }
            });
        } catch (Failure f) {
            report(bound, removeFrom, where, f.getCause());
        } catch (Throwable t) {
            report(bound, removeFrom, where, t);
        }
    }

    private <H> void report(Bound<H> bound, List<Bound<H>> removeFrom, String where, Throwable cause) {
        if (removeFrom != null) {
            removeFrom.remove(bound);
        }
        LOG.log(Level.WARNING, "Mod " + bound.modName + " threw in " + where
                + "; its client callback was detached", cause);
        failure.markFailure(bound.modName, cause, "client");
    }

    /** A client entry point that may throw a checked exception. */
    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    /** Unchecked carrier so a checked exception can cross the watchdog's Runnable boundary. */
    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failure(Throwable cause) {
            super(cause);
        }
    }

    /** One mod's handler for one surface. */
    private record Bound<H>(String modName, H handler) {
    }

    /** One of the eight pooled key slots. */
    private static final class Slot {

        private final int number;
        private final KeyMapping mapping;
        private volatile Lease lease;
        /** True when VibeMod, not the player, chose the current binding. */
        private volatile boolean autoBound;

        Slot(int number, KeyMapping mapping) {
            this.number = number;
            this.mapping = mapping;
        }

        Lease lease(String modName, String label, String defaultKey, Runnable onPress) {
            InputConstants.Key wanted = keyOrNull(defaultKey);
            // The user's own binding always wins: only touch a slot that is
            // still unbound, or that we bound ourselves last time.
            if (wanted != null && (mapping.isUnbound() || autoBound)) {
                mapping.setKey(wanted);
                autoBound = true;
                KeyMapping.resetMapping();
            }
            Lease created = new Lease(modName, label, onPress);
            this.lease = created;
            return created;
        }

        /**
         * The V3 variant: bind from the mapping the mod built, and take no
         * {@code onPress} — the mod polls the returned mapping itself.
         *
         * <p>{@code getDefaultKey()} rather than a parsed string, because a
         * native mod expresses its wish in the ordinary Fabric way (the key it
         * passed to {@code new KeyMapping(...)}), and re-spelling that as text
         * for the host to re-parse would only add a place to be wrong.
         */
        Lease leaseNative(String modName, KeyMapping requested) {
            InputConstants.Key wanted = requested == null ? null : requested.getDefaultKey();
            if (wanted != null && !InputConstants.UNKNOWN.equals(wanted)
                    && (mapping.isUnbound() || autoBound)) {
                mapping.setKey(wanted);
                autoBound = true;
                KeyMapping.resetMapping();
            }
            String label = requested == null ? "keybind" : requested.getName();
            Lease created = new Lease(modName, label, null);
            this.lease = created;
            return created;
        }

        private void release(Lease expected) {
            if (lease != expected) {
                return;
            }
            lease = null;
            if (autoBound) {
                mapping.setKey(InputConstants.UNKNOWN);
                autoBound = false;
                KeyMapping.resetMapping();
            }
        }

        /**
         * {@code "R"}, {@code "F6"}, {@code "MOUSE4"} to a real key, or null.
         *
         * <p>Generated code supplies these as free text, so an unrecognizable one
         * is a normal input: the slot simply stays unbound and the player binds
         * it themselves in the Controls screen.
         */
        private static InputConstants.Key keyOrNull(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            String raw = name.trim().toUpperCase(Locale.ROOT);
            try {
                if (raw.startsWith("MOUSE")) {
                    int button = Integer.parseInt(raw.substring("MOUSE".length())) - 1;
                    return InputConstants.Type.MOUSE.getOrCreate(button);
                }
                // The game's own names are "key.keyboard.r", "key.keyboard.f6".
                return InputConstants.getKey("key.keyboard." + raw.toLowerCase(Locale.ROOT));
            } catch (Throwable unrecognized) {
                return null;
            }
        }

        /** One lease on this slot. */
        final class Lease implements KeyLease {

            private final AtomicBoolean open = new AtomicBoolean(true);
            private final String modName;
            private final String label;
            private final Runnable onPress;

            Lease(String modName, String label, Runnable onPress) {
                this.modName = modName;
                this.label = label;
                this.onPress = onPress;
            }

            @Override
            public void release() {
                if (open.compareAndSet(true, false)) {
                    Slot.this.release(this);
                }
            }

            @Override
            public boolean active() {
                return open.get();
            }

            @Override
            public String slotName() {
                return "VibeMod Slot " + number;
            }

            @Override
            public boolean pressed() {
                return mapping.isDown();
            }

            /** The mod-facing label, for VibeMod's own UI. */
            public String label() {
                return label;
            }
        }
    }
}
