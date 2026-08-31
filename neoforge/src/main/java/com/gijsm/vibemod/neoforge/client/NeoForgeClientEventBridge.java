package com.gijsm.vibemod.neoforge.client;

import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

import net.minecraft.client.KeyMapping;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import com.gijsm.vibemod.loader.client.LoaderClientEventBridge;
import com.gijsm.vibemod.platform.ModFailure;
import com.gijsm.vibemod.runtime.Watchdog;

/**
 * The NeoForge half of {@link LoaderClientEventBridge} (§8).
 *
 * <p>Four permanent registrations, on two different buses, and which bus each
 * one goes on is the thing to get right. {@code RegisterGuiLayersEvent} and
 * {@code RegisterKeyMappingsEvent} are {@code IModBusEvent}s: they fire once
 * during mod loading, on the MOD bus, and are the only moment a HUD layer or a
 * key mapping can be created at all — which is precisely why the key pool has to
 * be a pool (§8.2) and the HUD a single dispatching layer (§8.1). The client
 * tick and {@code /vibec} go on the game bus.
 *
 * <p>{@code RegisterClientCommandsEvent} <b>re-fires on every connection</b>,
 * unlike Fabric's callback which fires once per client. That costs nothing here:
 * the tree is static and its suggestion providers read the live registry, so
 * re-registering is idempotent and the subtree stays dynamic (§8.3).
 */
public final class NeoForgeClientEventBridge extends LoaderClientEventBridge {

    private static final Identifier HUD_LAYER_ID = Identifier.fromNamespaceAndPath("vibemod", "mods");

    private final IEventBus modBus;

    public NeoForgeClientEventBridge(IEventBus modBus, ModFailure failure, Watchdog watchdog) {
        super(failure, watchdog);
        this.modBus = modBus;
    }

    @Override
    public void install() {
        // Above the chat layer, per §8.1: a generated HUD belongs over the world
        // and under nothing the player is reading.
        modBus.addListener(RegisterGuiLayersEvent.class, event ->
                event.registerAbove(VanillaGuiLayers.CHAT, HUD_LAYER_ID,
                        this::renderHuds));

        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            KeyMapping.Category category = KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath(KEY_CATEGORY, "slots"));
            event.registerCategory(category);
            initSlots(mapping -> {
                event.register(mapping);
                return mapping;
            }, category);
        });

        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> clientTick());

        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class,
                event -> registerVibec(event.getDispatcher()));
    }

    /**
     * The static root plus the live subtree (§8.3).
     *
     * <p>NeoForge's client commands use the ordinary {@code CommandSourceStack}
     * and {@code Commands.literal}, which is the one place this differs from
     * Fabric's {@code FabricClientCommandSource}. It is also why the
     * {@code /vibec} tree is the one part of the client bridge that is not
     * shared between the two hosts.
     */
    private void registerVibec(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vibec")
                .executes(ctx -> list(ctx.getSource()))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.argument("mod", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (String key : clientCommandKeys()) {
                                builder.suggest(key.substring(0, key.indexOf(' ')));
                            }
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("command", StringArgumentType.word())
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
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "mod"),
                                                StringArgumentType.getString(ctx, "command"),
                                                StringArgumentType.getString(ctx, "args")
                                                        .trim().split(" +")))))));
    }

    private int list(CommandSourceStack source) {
        source.sendSystemMessage(Component.literal(listCommandsText()));
        return 1;
    }

    private int run(CommandSourceStack source, String mod, String command, String[] args) {
        if (!runClientCommand(mod, command, args)) {
            source.sendFailure(Component.literal(
                    "No such client command: /vibec " + mod + " " + command));
            return 0;
        }
        return 1;
    }

    /**
     * Nothing to do, and that is the honest implementation.
     *
     * <p>Fabric needs {@code ClientCommands.refreshCommandCompletions()} here
     * because that call is what merges the client command tree into the
     * connection's dispatcher, so a newly registered ROOT would otherwise not
     * exist until the next connection. NeoForge merges client commands at
     * {@code RegisterClientCommandsEvent} time, which re-fires per connection.
     *
     * <p>Either way the {@code /vibec} root is static (§8.3) and never changes:
     * the mod and command arguments are plain words served by suggestion
     * providers that read the live registry, and a client command's suggestions
     * are computed locally on every keystroke. A mod registering a client
     * command mid-session is therefore already tab-completable, with nothing
     * cached to invalidate. Calling a getter here to look busy would be worse
     * than saying so.
     */
    @Override
    protected void refreshCompletions() {
        // Intentionally empty; see javadoc.
    }
}
