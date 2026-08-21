package vibemod.parkourcheckpoints;

import com.gijsm.vibemine.api.VibeContext;
import com.gijsm.vibemine.api.VibeMod;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ParkourCheckpoints implements VibeMod {

    private final CourseManager courses = new CourseManager();
    private final Map<UUID, PlayerRunData> runs = new HashMap<>();
    private final Map<String, Map<UUID, LeaderboardEntry>> leaderboards = new HashMap<>();
    private VibeContext ctx;

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        this.ctx = ctx;
        ctx.listen(new CheckpointListener(this, ctx));
        ParkourCommands commands = new ParkourCommands(this, courses, ctx);
        ctx.command("parkour", "Create and run named parkour courses", (sender, args) -> commands.handle(sender, args));
        ctx.log().info("ParkourCheckpoints enabled.");
    }

    public CourseManager getCourses() {
        return courses;
    }

    public Map<UUID, LeaderboardEntry> getLeaderboard(String courseKey) {
        return leaderboards.get(courseKey);
    }

    public void leaveRun(Player player) {
        PlayerRunData data = runs.get(player.getUniqueId());
        if (data != null) {
            data.activeCourse = null;
            data.checkpointsReached = 0;
        }
        player.sendMessage(ChatColor.YELLOW + "You left your current parkour run.");
    }

    public void onStart(Player player, Course course) {
        if (player == null || course == null || course.start == null) {
            return;
        }
        PlayerRunData data = runs.computeIfAbsent(player.getUniqueId(), k -> new PlayerRunData());
        data.activeCourse = course.name.toLowerCase();
        data.checkpointsReached = 0;
        data.runStartMillis = System.currentTimeMillis();
        Location loc = course.start.clone().add(0.5, 1.0, 0.5);
        data.lastCheckpointLoc = loc;
        player.spawnParticle(Particle.HAPPY_VILLAGER, loc, 10, 0.3, 0.3, 0.3, 0.01);
        player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f);
        player.sendMessage(ChatColor.GOLD + "Started course '" + course.name + "'! Go!");
    }

    public void onCheckpoint(Player player, Course course, int checkpointIndex) {
        if (player == null || course == null) {
            return;
        }
        PlayerRunData data = runs.get(player.getUniqueId());
        if (data == null || data.activeCourse == null || !data.activeCourse.equals(course.name.toLowerCase())) {
            return;
        }
        boolean sequential = ctx.configBool("require-sequential-checkpoints");
        if (sequential) {
            if (checkpointIndex != data.checkpointsReached) {
                return;
            }
        } else if (checkpointIndex < data.checkpointsReached) {
            return;
        }
        data.checkpointsReached = checkpointIndex + 1;
        Location cpLoc = course.checkpoints.get(checkpointIndex).clone().add(0.5, 1.0, 0.5);
        data.lastCheckpointLoc = cpLoc;
        long elapsed = System.currentTimeMillis() - data.runStartMillis;
        player.spawnParticle(Particle.HAPPY_VILLAGER, cpLoc, 12, 0.3, 0.3, 0.3, 0.01);
        player.playSound(cpLoc, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.4f);
        player.sendMessage(ChatColor.AQUA + "Checkpoint " + data.checkpointsReached + "/" + course.checkpoints.size()
                + ChatColor.GRAY + " - " + Utils.formatTime(elapsed));
    }

    public void onFinish(Player player, Course course) {
        if (player == null || course == null || course.end == null) {
            return;
        }
        PlayerRunData data = runs.get(player.getUniqueId());
        if (data == null || data.activeCourse == null || !data.activeCourse.equals(course.name.toLowerCase())) {
            return;
        }
        long elapsed = System.currentTimeMillis() - data.runStartMillis;
        Location loc = course.end.clone().add(0.5, 1.0, 0.5);
        player.spawnParticle(Particle.FIREWORK, loc, 30, 0.5, 0.5, 0.5, 0.05);
        player.playSound(loc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        player.sendMessage(ChatColor.GREEN + "Finished '" + course.name + "' in " + Utils.formatTime(elapsed) + "!");
        updateLeaderboard(course, player, elapsed);
        data.activeCourse = null;
        data.checkpointsReached = 0;
    }

    public void onFall(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        PlayerRunData data = runs.get(player.getUniqueId());
        Location target = data != null ? data.lastCheckpointLoc : null;
        if (target == null) {
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

    private void updateLeaderboard(Course course, Player player, long elapsed) {
        String key = course.name.toLowerCase();
        Map<UUID, LeaderboardEntry> board = leaderboards.computeIfAbsent(key, k -> new HashMap<>());
        UUID uuid = player.getUniqueId();
        LeaderboardEntry entry = board.get(uuid);
        if (entry == null || elapsed < entry.bestTimeMillis) {
            LeaderboardEntry updated = new LeaderboardEntry();
            updated.playerName = player.getName();
            updated.bestTimeMillis = elapsed;
            board.put(uuid, updated);
        }
    }
}
