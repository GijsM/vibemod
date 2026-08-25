package com.gijsm.vibemod.paper;

import java.util.UUID;

import net.kyori.adventure.audience.Audience;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.gijsm.vibemod.platform.Sender;

/**
 * A Bukkit {@link CommandSender} seen as the platform-neutral {@link Sender}
 * core code talks to.
 *
 * <p>{@link #bukkit()} is the escape hatch that makes the generated-code
 * contract work: the Paper sdk flavor hands mods a real {@code CommandSender}
 * (frozen v3 surface), so the host has to be able to unwrap what core handed it.
 * Nothing in core ever calls it.
 */
public final class PaperSender implements Sender {

    private final CommandSender sender;

    private PaperSender(CommandSender sender) {
        this.sender = sender;
    }

    public static Sender of(CommandSender sender) {
        return new PaperSender(sender);
    }

    /**
     * The wrapped Bukkit sender. Used by {@code PaperModHost} to satisfy the
     * Bukkit-typed {@code ModCommandHandler} the sdk freezes; throws when handed
     * a {@link Sender} from another platform, which would be a wiring bug.
     */
    public static CommandSender unwrap(Sender sender) {
        if (sender instanceof PaperSender paper) {
            return paper.sender;
        }
        throw new IllegalArgumentException("Not a Paper sender: "
                + (sender == null ? "null" : sender.getClass().getName()));
    }

    @Override
    public Audience audience() {
        return sender;
    }

    @Override
    public String name() {
        return sender.getName();
    }

    @Override
    public boolean hasPermission(String permission) {
        return sender.hasPermission(permission);
    }

    @Override
    public UUID idOrNull() {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }
}
