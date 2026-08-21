package vibemod.balastic;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public final class Balastic implements VibeMod {

    public static final String LAUNCHER_TAG = "balastic_launcher";

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        BallisticLauncher launcher = new BallisticLauncher(ctx);
        NamespacedKey key = new NamespacedKey(ctx.plugin(), LAUNCHER_TAG);

        ctx.listen(new BallisticItemListener(ctx, launcher, key));

        ctx.command("balastic", "Fires a ballistic bullet of explosions, or gives you a launcher item", (sender, args) -> {
            handle(ctx, launcher, key, sender, args);
        });
        ctx.action("balastic", (sender, args) -> {
            handle(ctx, launcher, key, sender, args);
        });
        ctx.log().info("Balastic enabled.");
    }

    private void handle(VibeContext ctx, BallisticLauncher launcher, NamespacedKey key, CommandSender sender, String[] args) {
        if (args == null || args.length < 1) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can receive the launcher item. Use /balastic <player> instead.");
                return;
            }
            Player player = (Player) sender;
            player.getInventory().addItem(createLauncherItem(key));
            player.sendMessage("You received a Balastic Missile Launcher! Right-click to fire where you're aiming.");
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

    private ItemStack createLauncherItem(NamespacedKey key) {
        ItemStack item = new ItemStack(Material.NETHER_STAR, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "Balastic Missile Launcher");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Right-click to fire a ballistic missile");
            lore.add(ChatColor.GRAY + "at whatever you're aiming at.");
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }
}
