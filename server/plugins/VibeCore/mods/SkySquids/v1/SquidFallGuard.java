package vibemod.skysquids;

import org.bukkit.entity.Squid;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

public final class SquidFallGuard implements Listener {

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event == null || event.getEntity() == null) {
            return;
        }
        if (!(event.getEntity() instanceof Squid)) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
        }
    }
}
