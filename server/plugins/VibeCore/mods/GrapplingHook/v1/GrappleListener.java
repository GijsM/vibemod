package vibemod.grapplinghook;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.UUID;

public final class GrappleListener implements Listener {

    private final VibeContext ctx;
    private final HashMap<UUID, BukkitTask> activeGrapples = new HashMap<>();

    public GrappleListener(VibeContext ctx) {
        this.ctx = ctx;
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.FISHING) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!player.isSneaking()) {
            return;
        }
        if (!isHoldingRod(player)) {
            return;
        }
        FishHook hook = event.getHook();
        if (hook == null) {
            return;
        }

        cancelGrapple(player);
        player.playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.8f);

        final int[] waited = {0};
        BukkitTask task = ctx.repeat(2L, 2L, () -> {
            waited[0] += 2;
            if (!player.isOnline() || hook.isDead() || !hook.isValid()) {
                cancelGrapple(player);
                return;
            }
            long maxWait = ctx.configInt("max-wait-ticks");
            if (maxWait < 1) {
                maxWait = 1;
            }
            if (hook.isOnGround()) {
                pullPlayer(player, hook.getLocation());
                cancelGrapple(player);
                return;
            }
            if (waited[0] >= maxWait) {
                pullPlayer(player, hook.getLocation());
                cancelGrapple(player);
            }
        });
        activeGrapples.put(player.getUniqueId(), task);
    }

    private boolean isHoldingRod(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        return (main != null && main.getType() == Material.FISHING_ROD)
                || (off != null && off.getType() == Material.FISHING_ROD);
    }

    private void cancelGrapple(Player player) {
        BukkitTask task = activeGrapples.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    private void pullPlayer(Player player, Location hookLoc) {
        if (hookLoc == null || hookLoc.getWorld() == null) {
            return;
        }
        Location playerLoc = player.getLocation();
        if (playerLoc.getWorld() == null || !playerLoc.getWorld().equals(hookLoc.getWorld())) {
            return;
        }
        Vector direction = hookLoc.toVector().subtract(playerLoc.toVector());
        double distance = direction.length();
        if (distance < 0.5) {
            return;
        }
        direction.normalize();
        double strength = ctx.configDouble("pull-strength");
        double lift = ctx.configDouble("vertical-lift");
        Vector velocity = direction.multiply(strength);
        velocity.setY(velocity.getY() + lift);
        player.setVelocity(velocity);
        player.setFallDistance(0f);
        player.getWorld().spawnParticle(Particle.CLOUD, playerLoc.clone().add(0, 1, 0), 15, 0.3, 0.3, 0.3, 0.02);
        player.playSound(playerLoc, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.2f);
    }
}
