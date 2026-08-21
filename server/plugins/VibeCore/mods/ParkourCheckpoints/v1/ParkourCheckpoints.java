package vibemod.parkourcheckpoints;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ParkourCheckpoints implements VibeMod {

    private final Map<UUID, PlayerRunData> runs = new HashMap<>();
    private final Map<UUID, LeaderboardEntry> leaderboard = new HashMap<>();

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        ctx.listen(new CheckpointListener(this));
        ctx.command("leaderboard", "Shows the parkour checkpoint leaderboard", (sender, args) -> showLeaderboard(sender));
        ctx.log().info("ParkourCheckpoints enabled.");
    }

    public void onCheckpoint(Player player, Location blockLoc) {
        if (player == null || blockLoc == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        PlayerRunData data = runs.computeIfAbsent(uuid, k -> new PlayerRunData());

        if (data.lastTriggerBlock != null && sameBlock(data.lastTriggerBlock, blockLoc)) {
            return;
        }

        data.lastTriggerBlock = blockLoc.clone();
        data.checkpointCount++;
        Location playerLoc = player.getLocation();
        Location checkpoint = blockLoc.clone().add(0.5, 1.0, 0.5);
        checkpoint.setYaw(playerLoc.getYaw());
        checkpoint.setPitch(playerLoc.getPitch());
        data.checkpoint = checkpoint;

        if (data.checkpointCount == 1) {
            data.runStartMillis = System.currentTimeMillis();
        }

        long elapsed = System.currentTimeMillis() - data.runStartMillis;
        player.spawnParticle(Particle.HAPPY_VILLAGER, checkpoint, 12, 0.3, 0.3, 0.3, 0.01);
        player.playSound(checkpoint, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.4f);
        player.sendMessage(ChatColor.GOLD + "Checkpoint " + data.checkpointCount + " set! Time: " + formatTime(elapsed));

        updateLeaderboard(player, data.checkpointCount, elapsed);
    }

    public void onFall(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        PlayerRunData data = runs.get(uuid);
        Location target;
        if (data != null && data.checkpoint != null) {
            target = data.checkpoint;
        } else {
            target = player.getWorld() != null ? player.getWorld().getSpawnLocation() : null;
        }
        if (target == null) {
            return;
        }
        player.teleport(target);
        player.spawnParticle(Particle.CLOUD, target, 20, 0.4, 0.2, 0.4, 0.02);
        player.playSound(target, Sound.ENTITY_PLAYER_HURT, 0.8f, 0.8f);
        player.sendMessage(ChatColor.RED + "You fell! Sent back to your last checkpoint.");
    }

    private void updateLeaderboard(Player player, int count, long elapsed) {
        UUID uuid = player.getUniqueId();
        LeaderboardEntry entry = leaderboard.get(uuid);
        if (entry == null || count > entry.bestCheckpointCount
                || (count == entry.bestCheckpointCount && elapsed < entry.bestTimeMillis)) {
            LeaderboardEntry updated = new LeaderboardEntry();
            updated.playerName = player.getName();
            updated.bestCheckpointCount = count;
            updated.bestTimeMillis = elapsed;
            leaderboard.put(uuid, updated);
        }
    }

    private void showLeaderboard(CommandSender sender) {
        if (leaderboard.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No parkour times recorded yet.");
            return;
        }
        List<LeaderboardEntry> entries = new ArrayList<>(leaderboard.values());
        entries.sort(Comparator.comparingInt((LeaderboardEntry e) -> -e.bestCheckpointCount)
                .thenComparingLong(e -> e.bestTimeMillis));
        sender.sendMessage(ChatColor.GOLD + "=== Parkour Leaderboard ===");
        int rank = 1;
        for (LeaderboardEntry e : entries) {
            if (rank > 10) {
                break;
            }
            sender.sendMessage(ChatColor.AQUA + "#" + rank + " " + ChatColor.WHITE + e.playerName
                    + ChatColor.GRAY + " - " + ChatColor.GREEN + e.bestCheckpointCount + " checkpoints"
                    + ChatColor.GRAY + " in " + ChatColor.YELLOW + formatTime(e.bestTimeMillis));
            rank++;
        }
    }

    private boolean sameBlock(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private String formatTime(long millis) {
        long minutes = (millis / 1000) / 60;
        long seconds = (millis / 1000) % 60;
        long ms = millis % 1000;
        return String.format("%02d:%02d.%03d", minutes, seconds, ms);
    }
}
