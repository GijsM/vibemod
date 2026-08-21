package vibemod.primeburn;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class PrimeBurn implements VibeMod {

    private static final long DURATION_NANOS = 2_000_000_000L;

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.action("burn", (sender, args) -> runBurn(ctx, sender));
        ctx.log().info("PrimeBurn enabled. Use /vibe do primeburn burn");
    }

    private void runBurn(VibeContext ctx, CommandSender sender) {
        if (sender != null) {
            sender.sendMessage("[PrimeBurn] Starting a 2-second prime-finding burn on the main thread...");
        }
        long start = System.nanoTime();
        long deadline = start + DURATION_NANOS;
        int primeCount = 0;
        long candidate = 2L;
        long lastChecked = 1L;

        while (System.nanoTime() < deadline) {
            if (isPrime(candidate)) {
                primeCount++;
            }
            lastChecked = candidate;
            candidate++;
        }

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        String report = "[PrimeBurn] Found " + primeCount + " primes up to " + lastChecked
                + " in " + elapsedMillis + "ms.";

        if (sender != null) {
            sender.sendMessage(report);
        }
        ctx.log().info(report);

        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (player.isOnline()) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            }
        }
    }

    private boolean isPrime(long n) {
        if (n < 2L) {
            return false;
        }
        if (n == 2L) {
            return true;
        }
        if (n % 2L == 0L) {
            return false;
        }
        long limit = (long) Math.sqrt((double) n);
        for (long i = 3L; i <= limit; i += 2L) {
            if (n % i == 0L) {
                return false;
            }
        }
        return true;
    }
}
