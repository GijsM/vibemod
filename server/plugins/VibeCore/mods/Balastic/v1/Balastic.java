package vibemod.balastic;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class Balastic implements VibeMod {

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        BallisticLauncher launcher = new BallisticLauncher(ctx);
        ctx.command("balastic", "Fires a ballistic bullet of explosions at a player", (sender, args) -> {
            handle(ctx, launcher, sender, args);
        });
        ctx.action("balastic", (sender, args) -> {
            handle(ctx, launcher, sender, args);
        });
        ctx.log().info("Balastic enabled.");
    }

    private void handle(VibeContext ctx, BallisticLauncher launcher, CommandSender sender, String[] args) {
        if (args == null || args.length < 1) {
            sender.sendMessage("Usage: /balastic <player>");
            return;
        }
        Player target = ctx.server().getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage("Player '" + args[0] + "' not found or offline.");
            return;
        }
        sender.sendMessage("Launching a ballistic bullet at " + target.getName() + "!");
        target.sendMessage("Something is falling from the sky towards you!");
        launcher.launch(target);
    }
}
