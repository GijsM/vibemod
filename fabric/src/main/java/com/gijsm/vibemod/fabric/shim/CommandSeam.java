package com.gijsm.vibemod.fabric.shim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;

import com.gijsm.vibemod.loader.LoaderCommandBridge;
import com.gijsm.vibemod.loader.ModAttribution;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModDispatch;
import com.gijsm.vibemod.runtime.ModHandle;

/**
 * {@code CommandRegistrationCallback}, made hot and revocable (V3 Phase 1 §A).
 *
 * <p>Phase 0 refused this event outright, and the refusal named the real
 * problem: a registration pass is not a game event. Fabric fires it exactly once
 * per {@code Commands} object — from the constructor, at server start and again
 * after every datapack reload — so a mod hot-loaded twenty minutes into a
 * session has missed it, and whatever it registered during one would outlive
 * {@code /vibe disable} because Brigadier trees are built once and never pruned.
 *
 * <p>Three mechanisms answer those three problems, and each is the smallest
 * thing that can:
 *
 * <ul>
 *   <li><b>Immediate invocation.</b> When a live mod registers the callback, it
 *       is called <em>now</em>, against the running server's real dispatcher and
 *       the real {@code CommandBuildContext} the game handed the host at the
 *       last firing. The mod's command therefore works on the tick it loads,
 *       with no {@code /reload} in the loop.</li>
 *   <li><b>A before/after diff of the dispatcher root.</b> The callback is
 *       opaque — it can register anything, under any name — so what it added is
 *       discovered rather than declared: snapshot the root's children, invoke,
 *       and compare. New literals become the mod's, tracked on its handle and
 *       removed on disable through the same reflective surgery
 *       {@code LoaderCommandBridge} already uses for {@code /vibe}'s dynamic
 *       commands.</li>
 *   <li><b>Replay on reload.</b> The host's own callback fires again with a
 *       fresh dispatcher; every live mod's stored callback is replayed into it,
 *       under attribution, before the clients are resynced.</li>
 * </ul>
 *
 * <p><b>Collisions are journalled, never silent.</b> Brigadier's
 * {@code addChild} <em>merges</em> onto an existing literal rather than
 * replacing it: a mod registering {@code /vibe} would quietly overwrite the
 * host's executor and add its own subtree, and there would be no new node to
 * remove on disable. So the snapshot records each existing root child's executor
 * and children, the diff detects a merge, the executor is put back, the added
 * grandchildren are removed, and the mod gets an error in its journal saying
 * which command it lost. First registration wins.
 */
public final class CommandSeam {

    private static final Logger LOG = Logger.getLogger("VibeMod.CommandSeam");

    /** The one callback type this seam handles. */
    static final String CALLBACK = "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback";

    private final Supplier<MinecraftServer> server;
    private final Supplier<ModDispatch> dispatch;

    /** Every live mod's callback, in registration order — the replay list. */
    private final List<Bound> live = new CopyOnWriteArrayList<>();

    /**
     * The last {@code CommandBuildContext} and {@code CommandSelection} the game
     * handed the host.
     *
     * <p>Captured rather than reconstructed. {@code Commands.createValidationContext}
     * would build a plausible one, but the real object is the one vanilla passed
     * every other mod on this server, wrapping this server's registries — and a
     * mod using it to build an {@code ItemArgument} deserves the same registries
     * everyone else got. The reconstruction is kept as a fallback for the case
     * that cannot happen but would be silent if it did.
     */
    private volatile CommandBuildContext buildContext;
    private volatile Commands.CommandSelection selection;

    public CommandSeam(Supplier<MinecraftServer> server, Supplier<ModDispatch> dispatch) {
        this.server = server;
        this.dispatch = dispatch;
    }

    // ------------------------------------------------------------ host callback

    /**
     * Called from the host's own process-lived
     * {@code CommandRegistrationCallback} — at server start, and again after
     * every {@code /reload} with a brand new dispatcher.
     *
     * <p>Must run <em>after</em> the host has reinstalled {@code /vibe} and its
     * own dynamic commands, so that "first registration wins" means the host
     * wins: a generated mod that names {@code /vibe} loses it and is told so.
     */
    public void hostCallbackFired(CommandDispatcher<CommandSourceStack> dispatcher,
                                  CommandBuildContext context, Commands.CommandSelection environment) {
        this.buildContext = context;
        this.selection = environment;
        if (live.isEmpty()) {
            return;
        }
        LOG.info("Replaying " + live.size() + " mod command registration(s) into a fresh dispatcher");
        for (Bound bound : live) {
            // The nodes in the old dispatcher went with it; this is a fresh
            // install, not an addition to the previous one.
            bound.installed.clear();
            install(bound, dispatcher, context, environment);
        }
        resync();
    }

    // -------------------------------------------------------------- registration

    /**
     * A live mod registering {@code CommandRegistrationCallback.EVENT}.
     *
     * <p>Reached from {@link EventFanout#register}, which recognises the
     * callback type and hands it here instead of building a fanout for it — a
     * registration pass has no permanent proxy to stand behind, because there is
     * nothing to dispatch to later.
     */
    public void register(Object listener) {
        ModHandle handle = ModAttribution.current();
        if (handle == null) {
            throw new IllegalStateException(
                    "CommandRegistrationCallback reached VibeMod outside any mod's own code, so the "
                            + "commands it registers could never be removed. Register from "
                            + "onInitialize.");
        }
        if (!(listener instanceof CommandRegistrationCallback callback)) {
            throw new IllegalArgumentException(listener.getClass().getName()
                    + " is not a CommandRegistrationCallback");
        }
        Bound bound = new Bound(handle.name(), handle, callback);
        live.add(bound);
        handle.track(ModHandle.Kind.COMMAND, Registration.of(() -> revoke(bound)));

        MinecraftServer running = server.get();
        if (running == null) {
            // Before a server exists there is no dispatcher to install into;
            // hostCallbackFired will pick this mod up when one is built.
            LOG.fine(() -> "Deferring " + bound.modName + "'s command registration (no server yet)");
            return;
        }
        install(bound, running.getCommands().getDispatcher(), contextFor(running), selectionFor(running));
        resync();
    }

    /** Every command a mod currently owns, for the acceptance gates. */
    public String describeState() {
        List<String> names = new ArrayList<>();
        for (Bound bound : live) {
            synchronized (bound.installed) {
                for (String name : bound.installed) {
                    names.add("/" + name);
                }
            }
        }
        names.sort(String::compareTo);
        return "CommandRegistrationCallback=" + live.size()
                + " modCommands=" + (names.isEmpty() ? "-" : String.join(",", names));
    }

    // -------------------------------------------------------------------- install

    /**
     * Invokes one mod's callback and works out what it added.
     *
     * <p>The invocation goes through {@link ModDispatch}: it is a call into mod
     * code, so it is timed by the watchdog and journalled against the mod like
     * every other one. A callback that throws halfway leaves whatever it managed
     * to register — which the diff below still finds and still tracks, so the
     * teardown is complete either way.
     */
    private void install(Bound bound, CommandDispatcher<CommandSourceStack> dispatcher,
                         CommandBuildContext context, Commands.CommandSelection environment) {
        ModDispatch guarded = dispatch.get();
        CommandNode<CommandSourceStack> root = dispatcher.getRoot();
        Map<String, Snapshot> before = snapshot(root);

        Runnable body = () -> bound.callback.register(dispatcher, context, environment);
        if (guarded == null) {
            ModAttribution.runAs(bound.handle, body);
        } else {
            ModAttribution.runAs(bound.handle, () -> guarded.run(bound.modName, null,
                    "commands:register", body::run));
        }

        for (CommandNode<CommandSourceStack> child : List.copyOf(root.getChildren())) {
            String name = child.getName();
            Snapshot old = before.get(name);
            if (old == null) {
                synchronized (bound.installed) {
                    bound.installed.add(name);
                }
                LOG.info("Mod " + bound.modName + " registered /" + name);
                continue;
            }
            if (old.node != child) {
                // Brigadier merges rather than replaces, so this cannot happen
                // through addChild; if some other path ever swapped a root node
                // wholesale, restoring it is still the right answer.
                restoreNode(root, old);
                collision(bound, name, "the node was replaced");
                continue;
            }
            if (old.command != child.getCommand() || !old.children.containsAll(childNames(child))) {
                restoreNode(root, old);
                collision(bound, name, "another mod or the host owns it");
            }
        }
    }

    /**
     * Undoes a merge onto an existing literal: the executor goes back, and every
     * child that was not there before is removed.
     */
    private static void restoreNode(CommandNode<CommandSourceStack> root, Snapshot old) {
        LoaderCommandBridge.restoreCommand(old.node, old.command);
        for (String childName : childNames(old.node)) {
            if (!old.children.contains(childName)) {
                LoaderCommandBridge.removeChild(old.node, childName);
            }
        }
    }

    /** One mod losing a name to whoever registered it first. Journalled, never silent. */
    private void collision(Bound bound, String name, String why) {
        String message = "/" + name + " is already registered (" + why
                + "), so " + bound.modName + "'s version was rejected and the original restored. "
                + "Pick a command name nothing else uses.";
        LOG.warning(message);
        ModDispatch guarded = dispatch.get();
        if (guarded != null) {
            // The error journal is the mod's own channel, and going through
            // ModDispatch means this reaches it exactly like a thrown exception
            // would: logged, counted towards the error storm, visible in
            // /vibe errors.
            guarded.run(bound.modName, null, "commands:register", () -> {
                throw new IllegalStateException(message);
            });
        }
    }

    // --------------------------------------------------------------------- revoke

    /** A mod being disabled: remove its literals, forget its callback, resync. */
    private void revoke(Bound bound) {
        live.remove(bound);
        MinecraftServer running = server.get();
        List<String> names;
        synchronized (bound.installed) {
            names = List.copyOf(bound.installed);
            bound.installed.clear();
        }
        if (running == null || names.isEmpty()) {
            return;
        }
        CommandNode<CommandSourceStack> root = running.getCommands().getDispatcher().getRoot();
        for (String name : names) {
            LoaderCommandBridge.removeChild(root, name);
            LOG.info("Removed /" + name + " with mod " + bound.modName);
        }
        resync();
    }

    private void resync() {
        MinecraftServer running = server.get();
        if (running != null) {
            LoaderCommandBridge.resync(running);
        }
    }

    // -------------------------------------------------------------------- helpers

    private CommandBuildContext contextFor(MinecraftServer running) {
        CommandBuildContext captured = buildContext;
        if (captured != null) {
            return captured;
        }
        // Unreachable in practice: the callback fires while Commands is being
        // constructed, long before any mod can be hot-loaded. Reconstructing
        // beats handing a mod a null it would NPE on.
        LOG.warning("No CommandBuildContext was captured; building a validation context instead");
        return Commands.createValidationContext(running.registryAccess());
    }

    private Commands.CommandSelection selectionFor(MinecraftServer running) {
        Commands.CommandSelection captured = selection;
        if (captured != null) {
            return captured;
        }
        return running.isDedicatedServer()
                ? Commands.CommandSelection.DEDICATED : Commands.CommandSelection.INTEGRATED;
    }

    private static Map<String, Snapshot> snapshot(CommandNode<CommandSourceStack> root) {
        Map<String, Snapshot> out = new LinkedHashMap<>();
        for (CommandNode<CommandSourceStack> child : root.getChildren()) {
            out.put(child.getName(), new Snapshot(child, child.getCommand(), childNames(child)));
        }
        return out;
    }

    private static Set<String> childNames(CommandNode<CommandSourceStack> node) {
        Collection<CommandNode<CommandSourceStack>> children = node.getChildren();
        Set<String> names = new LinkedHashSet<>(children.size());
        for (CommandNode<CommandSourceStack> child : children) {
            names.add(child.getName());
        }
        return names;
    }

    /** What one root child looked like before a mod's callback ran. */
    private record Snapshot(CommandNode<CommandSourceStack> node, Command<CommandSourceStack> command,
                            Set<String> children) {
    }

    /** One mod's stored callback, and the root literals it currently owns. */
    private static final class Bound {

        private final String modName;
        private final ModHandle handle;
        private final CommandRegistrationCallback callback;
        private final Set<String> installed = new LinkedHashSet<>();

        Bound(String modName, ModHandle handle, CommandRegistrationCallback callback) {
            this.modName = modName;
            this.handle = handle;
            this.callback = callback;
        }
    }
}
