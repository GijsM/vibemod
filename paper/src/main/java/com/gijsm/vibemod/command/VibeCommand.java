package com.gijsm.vibemod.command;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import com.gijsm.vibemod.paper.PaperSender;

/**
 * Bukkit's {@code /vibe} entry point: a {@link TabExecutor} that turns a
 * {@link CommandSender} into a platform-neutral
 * {@link com.gijsm.vibemod.platform.Sender} and hands the rest to
 * {@link VibeRouter}.
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

    public VibeCommand(VibeRouter router) {
        this.router = router;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return router.run(PaperSender.of(sender), args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return router.complete(PaperSender.of(sender), args);
    }
}
