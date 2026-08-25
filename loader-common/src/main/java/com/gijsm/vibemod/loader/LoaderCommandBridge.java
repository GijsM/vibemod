package com.gijsm.vibemod.loader;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.gijsm.vibemod.command.VibeRouter;
import com.gijsm.vibemod.platform.CommandBridge;
import com.gijsm.vibemod.platform.Messenger;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModDispatch;

/**
 * {@link CommandBridge} over Brigadier, plus the {@code /vibe} root itself.
 *
 * <p>Two registration paths, and they are genuinely different problems.
 *
 * <p><b>{@code /vibe} is static.</b> It is one literal with a greedy string
 * argument rather than a Brigadier tree of 27 subcommands, because
 * {@link VibeRouter} already owns the routing, the permission table and the
 * completion table, and re-expressing them as nodes would be a second copy that
 * drifts. Completions are served by asking the router, so tab-complete and
 * execution can never disagree.
 *
 * <p><b>Generated-mod commands are dynamic</b>, and Brigadier was not built for
 * that: {@link CommandNode} has {@code addChild} and no remove. So this follows
 * the same shape as Paper's command-map bridge — correctness never depends on
 * removing the node. Every command we register is a {@link Live} whose handler
 * can be nulled; unregister neuters it first (the command then reports itself
 * gone) and only then attempts the reflective node removal. Re-registering the
 * same name revives the existing node in place.
 *
 * <p>{@code CommandRegistrationCallback} also fires again on every datapack
 * reload, with a <em>fresh</em> dispatcher. That would silently drop every
 * generated-mod command, so {@link #reinstallInto} re-adds all live ones each
 * time the callback runs.
 */
public final class LoaderCommandBridge implements CommandBridge {

    private static final Logger LOG = Logger.getLogger(LoaderCommandBridge.class.getName());

    private final MinecraftServer server;
    private final Messenger messenger;
    private final ModDispatch dispatch;
    private volatile boolean allowTopLevel;
    private final Map<String, Live> ours = new ConcurrentHashMap<>();
    private volatile VibeRouter router;

    public LoaderCommandBridge(MinecraftServer server, Messenger messenger, ModDispatch dispatch,
                               boolean allowTopLevel) {
        this.server = server;
        this.messenger = messenger;
        this.dispatch = dispatch;
        this.allowTopLevel = allowTopLevel;
    }

    /** The router the {@code /vibe} node hands work to. Set once, right after construction. */
    public void setRouter(VibeRouter router) {
        this.router = router;
    }

    /** Change whether future {@link #register} calls may create real top-level commands. */
    public void setAllowTopLevel(boolean allow) {
        this.allowTopLevel = allow;
    }

    // ---------------------------------------------------------------- /vibe

    /**
     * Builds the {@code /vibe} node and re-adds every live generated command.
     * Called from {@code CommandRegistrationCallback}, which fires at startup and
     * again after every {@code /reload}.
     */
    public void reinstallInto(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vibe")
                .executes(ctx -> route(ctx, new String[0]))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests(this::suggest)
                        .executes(ctx -> route(ctx, split(StringArgumentType.getString(ctx, "args"))))));

        for (Map.Entry<String, Live> entry : ours.entrySet()) {
            Live live = entry.getValue();
            if (live.handler != null) {
                install(dispatcher, entry.getKey(), live);
            }
        }
    }

    private int route(CommandContext<CommandSourceStack> ctx, String[] args) {
        VibeRouter live = router;
        if (live == null) {
            return 0;
        }
        live.run(LoaderSender.of(ctx.getSource(), messenger), args);
        return 1;
    }

    /**
     * Completions for {@code /vibe}'s greedy argument.
     *
     * <p>A greedy string swallows the rest of the line, so Brigadier offers no
     * per-word structure and the builder's remaining text is everything typed
     * after {@code vibe }. The tokens are reconstructed here and handed to the
     * router's own completion table; the suggestion offset is moved to the start
     * of the word being typed so the client replaces that word, not the lot.
     */
    private java.util.concurrent.CompletableFuture<Suggestions> suggest(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        VibeRouter live = router;
        if (live == null) {
            return builder.buildFuture();
        }
        String remaining = builder.getRemaining();
        String[] typed = split(remaining);
        // A trailing space means a fresh, empty word is being started.
        if (remaining.isEmpty() || remaining.endsWith(" ")) {
            String[] withBlank = Arrays.copyOf(typed, typed.length + 1);
            withBlank[typed.length] = "";
            typed = withBlank;
        }
        int lastWordStart = builder.getStart() + remaining.lastIndexOf(' ') + 1;
        SuggestionsBuilder offset = builder.createOffset(lastWordStart);
        try {
            for (String option : live.complete(LoaderSender.of(ctx.getSource(), messenger), typed)) {
                offset.suggest(option);
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Completion failed", t);
        }
        return offset.buildFuture();
    }

    private static String[] split(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        List<String> parts = new ArrayList<>();
        for (String part : raw.trim().split(" +")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts.toArray(new String[0]);
    }

    // ------------------------------------------------------- dynamic commands

    @Override
    public Registration register(String name, String description, String modName, CommandHandler handler) {
        if (!allowTopLevel) {
            return Registration.inactive();
        }
        String key = name.toLowerCase(Locale.ROOT);
        // Every invocation of a generated command goes through ModDispatch: timed
        // by the watchdog, guarded, and journalled against the mod (§2).
        Runner wrapped = (source, args) -> dispatch.run(modName,
                LoaderSender.of(source, messenger), "command:" + name,
                () -> handler.run(LoaderSender.of(source, messenger), args));
        try {
            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            Live existing = ours.get(key);
            if (existing != null) {
                // A previously-unregistered node may still be in the tree: revive it.
                existing.handler = wrapped;
                if (dispatcher.getRoot().getChild(key) == null) {
                    install(dispatcher, key, existing);
                }
                resyncAll();
                return Registration.of(() -> unregister(key));
            }
            if (dispatcher.getRoot().getChild(key) != null) {
                // Someone else owns /name. Never override a command we did not make.
                return Registration.inactive();
            }
            Live live = new Live(wrapped);
            install(dispatcher, key, live);
            ours.put(key, live);
            resyncAll();
            return Registration.of(() -> unregister(key));
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Failed to register dynamic command /" + name, t);
            return Registration.inactive();
        }
    }

    private void install(CommandDispatcher<CommandSourceStack> dispatcher, String key, Live live) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(key)
                .executes(ctx -> live.run(ctx.getSource(), new String[0]))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> live.run(ctx.getSource(),
                                split(StringArgumentType.getString(ctx, "args")))));
        dispatcher.register(node);
    }

    /** Remove {@code /name} + resync clients. Never throws. */
    private void unregister(String key) {
        try {
            Live live = ours.remove(key);
            if (live == null) {
                return;
            }
            // The command is dead even if the node surgery below fails.
            live.handler = null;
            removeNode(server.getCommands().getDispatcher(), key);
            resyncAll();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Failed to unregister dynamic command /" + key, t);
        }
    }

    /**
     * Best-effort removal of a root literal.
     *
     * <p>Brigadier deliberately has no remove: its trees are built once at
     * startup. The three maps behind {@link CommandNode} ({@code children},
     * {@code literals}, {@code arguments}) are private with no mutator, so this
     * is reflection — isolated here, and never load-bearing, because
     * {@link #unregister} has already nulled the handler.
     */
    private static void removeNode(CommandDispatcher<CommandSourceStack> dispatcher, String key) {
        CommandNode<CommandSourceStack> root = dispatcher.getRoot();
        for (String fieldName : new String[] {"children", "literals", "arguments"}) {
            try {
                Field field = CommandNode.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(root);
                if (value instanceof Map<?, ?> map) {
                    map.remove(key);
                }
            } catch (Throwable t) {
                LOG.fine("Could not clear Brigadier's '" + fieldName + "' for /" + key
                        + " (" + t + "); the command is neutered but still listed");
            }
        }
    }

    /**
     * Pushes the command tree to every online player so a freshly registered
     * command tab-completes without a rejoin.
     */
    @Override
    public void resyncAll() {
        try {
            Commands commands = server.getCommands();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    commands.sendCommands(player);
                } catch (Throwable ignored) {
                    // best-effort
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    /** A runtime-registered command whose handler can be swapped or neutered. */
    private static final class Live {

        private volatile Runner handler;

        Live(Runner handler) {
            this.handler = handler;
        }

        int run(CommandSourceStack source, String[] args) {
            Runner current = handler;
            if (current == null) {
                source.sendFailure(net.minecraft.network.chat.Component.literal("Unknown command."));
                return 0;
            }
            current.run(source, args);
            return 1;
        }
    }

    /** A mod command handler bound to a runtime command registration. */
    @FunctionalInterface
    private interface Runner {
        void run(CommandSourceStack source, String[] args);
    }
}
