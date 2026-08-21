package vibemod.parkourcheckpoints;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public final class CheckpointListener implements Listener {

    private final ParkourCheckpoints mod;

    public CheckpointListener(ParkourCheckpoints mod) {
        this.mod = mod;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (player == null || !player.isOnline() || to == null) {
            return;
        }

        World world = to.getWorld();
        if (world == null) {
            return;
        }

        if (to.getY() < 0) {
            mod.onFall(player);
            return;
        }

        Location feet = to.clone().subtract(0, 1, 0);
        Block block = world.getBlockAt(feet);
        if (block != null && block.getType() == Material.GOLD_BLOCK) {
            mod.onCheckpoint(player, block.getLocation());
        }
    }
}
