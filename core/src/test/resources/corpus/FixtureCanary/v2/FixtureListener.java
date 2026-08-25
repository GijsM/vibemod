package vibemod.fixturecanary;

import com.gijsm.vibemod.api.VibeContext;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * The second source file, so the fixture proves a multi-file mod still resolves
 * across its own compilation unit — and the Bukkit-typed half of the api, so a
 * paper-api that moved {@code PlayerJoinEvent} would be a corpus failure.
 */
public final class FixtureListener implements Listener {

    private final VibeContext ctx;
    private final AtomicLong seen = new AtomicLong();

    public FixtureListener(VibeContext ctx) {
        this.ctx = ctx;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        seen.incrementAndGet();
        ctx.log().info("FixtureCanary saw " + event.getPlayer().getName() + " join");
    }

    public void tick() {
        seen.incrementAndGet();
    }

    public long seen() {
        return seen.get();
    }
}
