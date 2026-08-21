package vibemod.parkourcheckpoints;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public final class CheckpointListener implements Listener {

    private final ParkourCheckpoints mod;
    private final VibeContext ctx;

    public CheckpointListener(ParkourCheckpoints mod, VibeContext ctx) {
        this.mod = mod;
        this.ctx = ctx;
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

        long minY = ctx.configInt("min-y");
        if (to.getY() < minY) {
            mod.onFall(player);
            return;
        }

        Location feet = to.clone().subtract(0, 1, 0);
        Block block = world.getBlockAt(feet);
        if (block == null) {
            return;
        }
        CourseManager.BlockMatch match = mod.getCourses().findMatch(block.getLocation());
        if (match == null) {
            return;
        }
        switch (match.type) {
            case 0:
                mod.onStart(player, match.course);
                break;
            case 1:
                mod.onCheckpoint(player, match.course, match.checkpointIndex);
                break;
            case 2:
                mod.onFinish(player, match.course);
                break;
            default:
                break;
        }
    }
}
