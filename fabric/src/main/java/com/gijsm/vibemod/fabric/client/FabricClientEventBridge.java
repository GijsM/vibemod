package com.gijsm.vibemod.fabric.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import com.gijsm.vibemod.api.client.ClientCommandHandler;
import com.gijsm.vibemod.api.client.ClientContext;
import com.gijsm.vibemod.api.client.ClientTickHandler;
import com.gijsm.vibemod.api.client.HudRenderer;
import com.gijsm.vibemod.api.client.KeyLease;
import com.gijsm.vibemod.fabric.FabricText;
import com.gijsm.vibemod.platform.ClientEventBridge;
import com.gijsm.vibemod.platform.ModFailure;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.Watchdog;

/**
 * The client half of the generated-mod surface (ARCHITECTURE-V2 §8).
 *
 * <p><b>Host-owned dispatchers (§8.1).</b> Exactly one permanent registration
 * per surface is made at client init — one {@code HudElement}, one
 * {@code END_CLIENT_TICK} listener, eight {@code KeyMapping}s, one
 * {@code /vibec} root — and generated mods attach to and detach from mutable
 * registries behind them. This is not indirection for its own sake: a Fabric
 * event cannot be unregistered, HUD elements and key mappings can only be
 * registered during client startup, and mods load and unload at any moment. The
 * indirection is the only shape that satisfies both.
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
 */
public final class FabricClientEventBridge implements ClientEventBridge {

    private static final Logger LOG = Logger.getLogger(FabricClientEventBridge.class.getName());

    /** The key pool's size (§8.2). Eight is the doc's number and the Controls screen's patience. */
    public static final int KEY_SLOTS = 8;

    private static final Identifier HUD_ELEMENT_ID = Identifier.fromNamespaceAndPath("vibemod", "mods");
    private static final String KEY_CATEGORY = "vibemod";

    private final ModFailure failure;
    private final Watchdog watchdog;

    private final List<Bound<HudRenderer>> huds = new CopyOnWriteArrayList<>();
    private final List<Bound<ClientTickHandler>> tickers = new CopyOnWriteArrayList<>();
    private final Map<String, Bound<ClientCommandHandler>> clientCommands = new ConcurrentHashMap<>();
    private final Slot[] slots = new Slot[KEY_SLOTS];

    private final FabricHudCanvas canvas = new FabricHudCanvas();
    /** The context handed to tick and command callbacks; stateless, so one is enough. */
    private final ClientContext sharedContext;

    public FabricClientEventBridge(ModFailure failure, Watchdog watchdog) {
        this.failure = failure;
        this.watchdog = watchdog;
        this.sharedContext = new FabricClientContext(this, null);
    }

    /**
     * Installs the permanent hooks. Called once, from the client entrypoint —
     * before a world exists and before any mod is loaded, because HUD elements
     * and key mappings can only be registered then.
     */
    public void install() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(KEY_CATEGORY, "slots"));
        for (int i = 0; i < KEY_SLOTS; i++) {
            KeyMapping mapping = new KeyMapping("key.vibemod.slot" + (i + 1),
                    InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category);
            slots[i] = new Slot(i + 1, KeyMappingHelper.registerKeyMapping(mapping));
        }

        HudElementRegistry.addLast(HUD_ELEMENT_ID, (graphics, delta) -> {
            if (huds.isEmpty()) {
                return;
            }
            canvas.bind(graphics);
            try {
                float partial = delta.getGameTimeDeltaPartialTick(false);
                for (Bound<HudRenderer> bound : huds) {
                    guard(bound, huds, "client:hud", () -> bound.handler.render(canvas, partial));
                }
            } finally {
                canvas.unbind();
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            pollKeys();
            for (Bound<ClientTickHandler> bound : tickers) {
                guard(bound, tickers, "client:tick", () -> bound.handler.tick(sharedContext));
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> registerVibec(dispatcher));
    }

    // ------------------------------------------------------------------ HUD

    @Override
    public Registration hud(String modName, String elementId, HudRenderer renderer) {
        return attach(huds, modName, renderer);
    }

    @Override
    public Registration clientTick(String modName, ClientTickHandler handler) {
        return attach(tickers, modName, handler);
    }

    // ------------------------------------------------------------- key pool

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
    public synchronized KeyLease leaseKey(String modName, String label, String defaultKey, Runnable onPress) {
        for (Slot slot : slots) {
            if (slot.lease == null) {
                return slot.lease(modName, label, defaultKey, onPress);
            }
        }
        throw new IllegalStateException("All " + KEY_SLOTS + " VibeMod key slots are in use; "
                + "disable a mod that uses a keybind first");
    }

    /** Reads every leased slot once per client tick and fires the presses. */
    private void pollKeys() {
        for (Slot slot : slots) {
            Slot.Lease lease = slot.lease;
            if (lease == null) {
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

    // --------------------------------------------------------------- /vibec

    /**
     * The static root plus the live subtree (§8.3).
     *
     * <p>{@code /vibec <mod> <command> [args]} is one literal with two greedy-ish
     * arguments rather than a node per mod, because {@code ClientCommandRegistrationCallback}
     * fires once per connection and mods load between connections. A suggestion
     * provider reads the live registry, so the tree never needs rebuilding.
     */
    private void registerVibec(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("vibec")
                .executes(ctx -> {
                    listCommands(ctx.getSource());
                    return 1;
                })
                .then(ClientCommands.literal("list").executes(ctx -> {
                    listCommands(ctx.getSource());
                    return 1;
                }))
                .then(ClientCommands.argument("mod", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (String key : clientCommands.keySet()) {
                                builder.suggest(key.substring(0, key.indexOf(' ')));
                            }
                            return builder.buildFuture();
                        })
                        .then(ClientCommands.argument("command", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String mod = StringArgumentType.getString(ctx, "mod")
                                            .toLowerCase(Locale.ROOT);
                                    for (String key : clientCommands.keySet()) {
                                        if (key.startsWith(mod + " ")) {
                                            builder.suggest(key.substring(mod.length() + 1));
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> runClientCommand(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "mod"),
                                        StringArgumentType.getString(ctx, "command"),
                                        new String[0]))
                                .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                                        .executes(ctx -> runClientCommand(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "mod"),
                                                StringArgumentType.getString(ctx, "command"),
                                                StringArgumentType.getString(ctx, "args")
                                                        .trim().split(" +")))))));
    }

    private void listCommands(FabricClientCommandSource source) {
        if (clientCommands.isEmpty()) {
            source.sendFeedback(net.minecraft.network.chat.Component.literal(
                    "No mod client commands are registered."));
            return;
        }
        List<String> keys = new ArrayList<>(clientCommands.keySet());
        keys.sort(null);
        source.sendFeedback(net.minecraft.network.chat.Component.literal(
                "VibeMod client commands: " + String.join(", ", keys.stream()
                        .map(k -> "/vibec " + k).toList())));
    }

    private int runClientCommand(FabricClientCommandSource source, String mod, String command, String[] args) {
        String key = key(mod, command);
        Bound<ClientCommandHandler> bound = clientCommands.get(key);
        if (bound == null) {
            source.sendError(net.minecraft.network.chat.Component.literal(
                    "No such client command: /vibec " + mod + " " + command));
            return 0;
        }
        guard(bound, null, "client:command:" + command,
                () -> bound.handler.run(sharedContext, args));
        return 1;
    }

    @Override
    public Registration clientCommand(String modName, String name, String description,
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

    private static String key(String modName, String command) {
        return modName.toLowerCase(Locale.ROOT) + " " + command.toLowerCase(Locale.ROOT);
    }

    private static void refreshCompletions() {
        try {
            ClientCommands.refreshCommandCompletions();
        } catch (Throwable ignored) {
            // Not connected yet: there is nothing to refresh, which is fine.
        }
    }

    // ------------------------------------------------------------ odds and ends

    @Override
    public void playUiSound(String soundId, float volume, float pitch) {
        Identifier id = FabricText.idOrNull(soundId);
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
        client.execute(() -> client.getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, pitch, volume)));
    }

    @Override
    public void toast(String title, String body) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.gui.toastManager().addToast(new SystemToast(
                new SystemToast.SystemToastId(),
                net.minecraft.network.chat.Component.literal(title == null ? "VibeMod" : title),
                net.minecraft.network.chat.Component.literal(body == null ? "" : body))));
    }

    @Override
    public boolean inGame() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && client.player != null;
    }

    /** How many entries each surface currently holds. The acceptance gate asserts on these. */
    public String describeState() {
        int leased = 0;
        for (Slot slot : slots) {
            if (slot.lease != null) {
                leased++;
            }
        }
        return "huds=" + huds.size() + " tickers=" + tickers.size()
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
