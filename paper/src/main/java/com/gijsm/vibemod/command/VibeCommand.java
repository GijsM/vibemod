package com.gijsm.vibemod.command;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import com.gijsm.vibemod.paper.PaperSender;
import com.gijsm.vibemod.platform.Sender;
import com.gijsm.vibemod.platform.TickScheduler;

/**
 * Bukkit's {@code /vibe} entry point: a {@link TabExecutor} that turns a
 * {@link CommandSender} into a platform-neutral
 * {@link com.gijsm.vibemod.platform.Sender} and hands the rest to
 * {@link VibeRouter}.
 *
 * <p>On a regionised server this adapter also owns a thread hop, and it is a
 * safety fix rather than a nicety. Folia runs a player-issued command on the
 * region thread that owns the player, but six {@code ModLifecycle} methods —
 * {@code load}, {@code enable}, {@code disable}, {@code unload},
 * {@code runAction} and <strong>{@code panic}</strong> — assert they are on the
 * main thread, which on Folia means the global region thread. Without the hop,
 * {@code /vibe panic} typed by a player throws {@code IllegalStateException}
 * instead of disabling every mod: one of the three safety mechanisms, defeated
 * by the platform it is most needed on. {@code runOnMain} runs inline when it is
 * already on the right thread, so on Paper this is not a hop at all and the
 * behaviour is unchanged.
 *
 * <p>Phase D emptied this class (ARCHITECTURE-V2 §1.1, "Phase D may promote
 * shared routing to core"). All 27 subcommands, their permission table, their
 * console-vs-screen branching and the tab-completion table now live in
 * {@code core}, because Fabric needs every one of them and the only Bukkit in
 * the original was {@code CommandSender}/{@code Player}. What is left here is
 * the adapter — which is all a host should ever own.
 */
public final class VibeCommand implements TabExecutor {

    private final VibeRouter router;
    private final TickScheduler scheduler;

    public VibeCommand(VibeRouter router, TickScheduler scheduler) {
        this.router = router;
        this.scheduler = scheduler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Sender s = PaperSender.of(sender);
        if (scheduler.onMain()) {
            return router.run(s, args);
        }
        // Off the main/global-region thread: hop, and claim the command. The
        // return value only decides whether Bukkit prints the usage line, and
        // the real answer is not knowable until the hop has run.
        scheduler.runOnMain(() -> router.run(s, args));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return router.complete(PaperSender.of(sender), args);
    }
}
