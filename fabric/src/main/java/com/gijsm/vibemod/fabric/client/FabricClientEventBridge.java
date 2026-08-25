package com.gijsm.vibemod.fabric.client;

import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import com.gijsm.vibemod.loader.client.LoaderClientEventBridge;
import com.gijsm.vibemod.platform.ModFailure;
import com.gijsm.vibemod.runtime.Watchdog;

/**
 * The Fabric half of {@link LoaderClientEventBridge} (§8): the four permanent
 * hooks, and the {@code /vibec} tree that has to be written against Fabric's own
 * client-command source.
 */
public final class FabricClientEventBridge extends LoaderClientEventBridge {

    private static final Identifier HUD_ELEMENT_ID = Identifier.fromNamespaceAndPath("vibemod", "mods");

    public FabricClientEventBridge(ModFailure failure, Watchdog watchdog) {
        super(failure, watchdog);
    }

    @Override
    public void install() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(KEY_CATEGORY, "slots"));
        initSlots(KeyMappingHelper::registerKeyMapping, category);

        // 26.x: a HudElement extracts render state rather than drawing, so the
        // callback's first argument is a GuiGraphicsExtractor.
        HudElementRegistry.addLast(HUD_ELEMENT_ID, (graphics, delta) ->
                renderHuds(graphics, delta.getGameTimeDeltaPartialTick(false)));

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
