package vibemod.balastic;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Detects right-clicks with the Balastic launcher item and fires a missile at the aim point. */
public final class BallisticItemListener implements Listener {

    private static final double MAX_DISTANCE = 200.0;
    private static final long COOLDOWN_MILLIS = 750L;

    private final VibeContext ctx;
    private final BallisticLauncher launcher;
    private final NamespacedKey key;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public BallisticItemListener(VibeContext ctx, BallisticLauncher launcher, NamespacedKey key) {
        this.ctx = ctx;
        this.launcher = launcher;
        this.key = key;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        ItemStack item = event.getItem();
        if (!isLauncherItem(item)) {
            return;
        }
        event.setCancelled(true);

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(uuid);
        if (last != null && now - last < COOLDOWN_MILLIS) {
            return;
        }
        cooldowns.put(uuid, now);

        Location aimPoint = findAimPoint(player);
        if (aimPoint == null) {
            return;
        }

        player.sendMessage("Firing a ballistic missile downrange!");
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.0f);
        launcher.launchAt(aimPoint, player);
    }

    private boolean isLauncherItem(ItemStack item) {
        if (item == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte flag = meta.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    private Location findAimPoint(Player player) {
        World world = player.getWorld();
        if (world == null) {
            return null;
        }
        RayTraceResult result = world.rayTraceBlocks(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                MAX_DISTANCE);
        if (result != null && result.getHitPosition() != null) {
            return result.getHitPosition().toLocation(world);
        }
        Vector direction = player.getEyeLocation().getDirection().normalize();
        Location farPoint = player.getEyeLocation().clone().add(direction.multiply(MAX_DISTANCE));
        return farPoint;
    }
}
