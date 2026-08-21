package vibemod.doublejump;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;

public final class DoubleJumpListener implements Listener {

    private final VibeContext ctx;
    private final Map<UUID, Boolean> airJumpUsed;

    public DoubleJumpListener(VibeContext ctx, Map<UUID, Boolean> airJumpUsed) {
        this.ctx = ctx;
        this.airJumpUsed = airJumpUsed;
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }
        if (player.isOnGround()) {
            return;
        }
        UUID id = player.getUniqueId();
        boolean used = airJumpUsed.getOrDefault(id, false);
        if (used) {
            return;
        }
        airJumpUsed.put(id, true);
        performDoubleJump(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            airJumpUsed.remove(player.getUniqueId());
        }
    }

    private void performDoubleJump(Player player) {
        double vertical = ctx.configDouble("vertical-boost");
        if (vertical <= 0) {
            vertical = 0.9;
        }
        double horizontalMult = ctx.configDouble("horizontal-boost");
        if (horizontalMult < 0) {
            horizontalMult = 0;
        }

        Vector direction = player.getLocation().getDirection();
        Vector horizontal = new Vector(direction.getX(), 0, direction.getZ());
        if (horizontal.lengthSquared() > 0.0001) {
            horizontal.normalize();
        }
        Vector boost = horizontal.multiply(horizontalMult);
        boost.setY(vertical);
        player.setVelocity(boost);

        if (ctx.configBool("effects-enabled")) {
            World world = player.getWorld();
            if (world != null) {
                Location loc = player.getLocation();
                world.spawnParticle(Particle.CLOUD, loc, 20, 0.3, 0.1, 0.3, 0.05);
                world.playSound(loc, Sound.ENTITY_BAT_TAKEOFF, 1.0f, 1.3f);
            }
        }
    }
}
