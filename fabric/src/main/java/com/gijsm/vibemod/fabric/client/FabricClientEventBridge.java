package com.gijsm.vibemod.fabric.client;

import java.util.Locale;
import java.util.logging.Logger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import com.gijsm.vibemod.fabric.shim.ClientRegistrations;
import com.gijsm.vibemod.fabric.shim.ClientSeam;
import com.gijsm.vibemod.loader.client.LoaderClientEventBridge;
import com.gijsm.vibemod.platform.ModFailure;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModLifecycle;
import com.gijsm.vibemod.runtime.Watchdog;

/**
 * The Fabric half of {@link LoaderClientEventBridge} (§8): the four permanent
 * hooks, and the {@code /vibec} tree that has to be written against Fabric's own
 * client-command source.
 *
 * <p>V3 Phase 1 makes it the render thread's representative as well. It
 * implements {@link ClientSeam} — which the dedicated-server half of the host
 * holds as an interface, so it can ask about the render thread without naming a
 * client class — and {@link ClientRegistrations}, which is where a native mod's
 * rewritten {@code KeyMappingHelper}/{@code HudElementRegistry} calls land. Both
 * are answered by the pool and the HUD pipeline that were already here: a native
 * keybind is one of the same eight slots, and a native {@code HudElement} is an
 * entry behind the same single permanent element.
 */
public final class FabricClientEventBridge extends LoaderClientEventBridge
        implements ClientSeam, ClientRegistrations {

    private static final Logger LOG = Logger.getLogger("VibeMod");

    private static final Identifier HUD_ELEMENT_ID = Identifier.fromNamespaceAndPath("vibemod", "mods");

    public FabricClientEventBridge(ModFailure failure, Watchdog watchdog) {
        super(failure, watchdog);
    }

    // ----------------------------------------------------------- ClientSeam

    @Override
    public boolean onRenderThread() {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.isSameThread();
    }

    @Override
    public void runOnRenderThread(Runnable body) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        // execute() runs inline when already on the render thread and queues
        // otherwise, which is exactly the contract; the host never blocks the
        // server thread waiting for a frame.
        client.execute(body);
    }

    /**
     * Closes the open screen when the mod that defined it is going away
     * (§E).
     *
     * <p>Identity on the class loader, not a name check: two versions of the
     * same mod have identically-named classes and only one of them is being
     * unloaded. {@code setScreenAndShow(null)} is legal and does the right thing
     * — {@code Gui.setScreen} explicitly handles null by returning to the
     * in-game GUI, or to the title screen when there is no level — and the one
     * case it refuses (a null during client-level teardown, which throws
     * {@code IllegalStateException}) is exactly the case where the screen is
     * being replaced anyway.
     */
    @Override
    public void closeScreensFrom(ClassLoader modLoader) {
        runOnRenderThread(() -> {
            Minecraft client = Minecraft.getInstance();
            Screen open = client == null ? null : client.gui.screen();
            if (open == null) {
                return;
            }
            ClassLoader owner = open.getClass().getClassLoader();
            if (!(owner instanceof ModLifecycle.BytesClassLoader) || owner != modLoader) {
                return;
            }
            LOG.info("Closing " + open.getClass().getName() + ": the mod that defined it was unloaded");
            try {
                client.setScreenAndShow(null);
            } catch (Throwable t) {
                // Mid-disconnect the game refuses a null screen because it is
                // already putting one up. Nothing to do, and nothing broken.
                LOG.fine("Could not close a departing mod's screen: " + t);
            }
        });
    }

    // -------------------------------------------------- ClientRegistrations

    @Override
    public Leased leaseKeyMapping(String modName, KeyMapping requested) {
        NativeKeyLease lease = leaseSlotFor(modName, requested);
        return new Leased(lease.mapping(), Registration.of(lease.release()));
    }

    @Override
    public Registration addHud(String modName, HudElement element) {
        return rawHud(modName, element::extractRenderState);
    }

    @Override
    public void install() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(KEY_CATEGORY, "slots"));
        initSlots(KeyMappingHelper::registerKeyMapping, category);

        // 26.x: a HudElement extracts render state rather than drawing, so the
        // callback's first argument is a GuiGraphicsExtractor. The delta tracker
        // is passed through rather than reduced to a partial tick, because a
        // native mod's own HudElement is entitled to the object the game hands
        // out (V3 Phase 1 §D).
        HudElementRegistry.addLast(HUD_ELEMENT_ID, this::renderHuds);

        ClientTickEvents.END_CLIENT_TICK.register(client -> clientTick());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> registerVibec(dispatcher));
    }

    /**
     * The static root plus the live subtree (§8.3).
     *
     * <p>{@code /vibec <mod> <command> [args]} is one literal with two word
     * arguments rather than a node per mod, because
     * {@code ClientCommandRegistrationCallback} fires once per connection and
     * mods load between connections. Suggestion providers read the live registry,
     * so the tree never needs rebuilding.
     */
    private void registerVibec(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("vibec")
                .executes(ctx -> list(ctx.getSource()))
                .then(ClientCommands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(ClientCommands.argument("mod", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (String key : clientCommandKeys()) {
                                builder.suggest(key.substring(0, key.indexOf(' ')));
                            }
                            return builder.buildFuture();
                        })
                        .then(ClientCommands.argument("command", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String mod = StringArgumentType.getString(ctx, "mod")
                                            .toLowerCase(Locale.ROOT);
                                    for (String key : clientCommandKeys()) {
                                        if (key.startsWith(mod + " ")) {
                                            builder.suggest(key.substring(mod.length() + 1));
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "mod"),
                                        StringArgumentType.getString(ctx, "command"),
                                        new String[0]))
                                .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "mod"),
                                                StringArgumentType.getString(ctx, "command"),
                                                StringArgumentType.getString(ctx, "args")
                                                        .trim().split(" +")))))));
    }

    private int list(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal(listCommandsText()));
        return 1;
    }

    private int run(FabricClientCommandSource source, String mod, String command, String[] args) {
        if (!runClientCommand(mod, command, args)) {
            source.sendError(Component.literal("No such client command: /vibec " + mod + " " + command));
            return 0;
        }
        return 1;
    }

    @Override
    protected void refreshCompletions() {
        try {
            ClientCommands.refreshCommandCompletions();
        } catch (Throwable ignored) {
            // Not connected yet: there is nothing to refresh, which is fine.
        }
    }
}
