package vibemod.parkourcheckpoints;

import com.gijsm.vibemine.api.VibeContext;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ParkourCommands {

    private final ParkourCheckpoints mod;
    private final CourseManager courses;
    private final VibeContext ctx;

    public ParkourCommands(ParkourCheckpoints mod, CourseManager courses, VibeContext ctx) {
        this.mod = mod;
        this.courses = courses;
        this.ctx = ctx;
    }

    public void handle(CommandSender sender, String[] args) {
        if (args.length == 0) {
            help(sender);
            return;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create": create(sender, args); break;
            case "delete": delete(sender, args); break;
            case "setstart": setStart(sender, args); break;
            case "setend": setEnd(sender, args); break;
            case "addcheckpoint": addCheckpoint(sender, args); break;
            case "clearcheckpoints": clearCheckpoints(sender, args); break;
            case "list": list(sender); break;
            case "info": info(sender, args); break;
            case "leaderboard": leaderboard(sender, args); break;
            case "leave": leave(sender); break;
            default: help(sender); break;
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "Only operators can manage parkour courses.");
            return false;
        }
        return true;
    }

    private Block feetBlock(Player player) {
        Location loc = player.getLocation().subtract(0, 1, 0);
        return player.getWorld().getBlockAt(loc);
    }

    private Course requireCourse(CommandSender sender, String name) {
        Course c = courses.getCourse(name);
        if (c == null) {
            sender.sendMessage(ChatColor.RED + "No course named '" + name + "'. Use /parkour list.");
        }
        return c;
    }

    private void create(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /parkour create <name>"); return; }
        Course c = courses.createCourse(args[1]);
        if (c == null) { sender.sendMessage(ChatColor.RED + "A course named '" + args[1] + "' already exists."); return; }
        sender.sendMessage(ChatColor.GREEN + "Created course '" + args[1] + "'. Now use setstart/setend/addcheckpoint.");
    }

    private void delete(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /parkour delete <name>"); return; }
        boolean ok = courses.deleteCourse(args[1]);
        sender.sendMessage(ok ? ChatColor.GREEN + "Deleted course '" + args[1] + "'." : ChatColor.RED + "No such course.");
    }

    private void setStart(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + "Only players can do this."); return; }
        if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /parkour setstart <name>"); return; }
        Course c = requireCourse(sender, args[1]);
        if (c == null) return;
        courses.setStart(c, feetBlock(player).getLocation());
        sender.sendMessage(ChatColor.GREEN + "Start of '" + c.name + "' set at your feet.");
    }

    private void setEnd(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + "Only players can do this."); return; }
        if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /parkour setend <name>"); return; }
        Course c = requireCourse(sender, args[1]);
        if (c == null) return;
        courses.setEnd(c, feetBlock(player).getLocation());
        sender.sendMessage(ChatColor.GREEN + "End of '" + c.name + "' set at your feet.");
    }

    private void addCheckpoint(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + "Only players can do this."); return; }
        if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /parkour addcheckpoint <name>"); return; }
        Course c = requireCourse(sender, args[1]);
        if (c == null) return;
        courses.addCheckpoint(c, feetBlock(player).getLocation());
        sender.sendMessage(ChatColor.GREEN + "Added checkpoint #" + c.checkpoints.size() + " to '" + c.name + "'.");
    }

    private void clearCheckpoints(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /parkour clearcheckpoints <name>"); return; }
        Course c = requireCourse(sender, args[1]);
        if (c == null) return;
        courses.clearCheckpoints(c);
        sender.sendMessage(ChatColor.GREEN + "Cleared checkpoints for '" + c.name + "'.");
    }

    private void list(CommandSender sender) {
        List<Course> all = courses.listCourses();
        if (all.isEmpty()) { sender.sendMessage(ChatColor.YELLOW + "No parkour courses exist yet."); return; }
        sender.sendMessage(ChatColor.GOLD + "=== Parkour Courses ===");
        for (Course c : all) {
            boolean ready = c.start != null && c.end != null;
            sender.sendMessage(ChatColor.AQUA + "- " + c.name + ChatColor.GRAY + " (" + c.checkpoints.size()
                    + " checkpoints)" + (ready ? ChatColor.GREEN + " [ready]" : ChatColor.RED + " [incomplete]"));
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /parkour info <name>"); return; }
        Course c = requireCourse(sender, args[1]);
        if (c == null) return;
        sender.sendMessage(ChatColor.GOLD + "Course '" + c.name + "':");
        sender.sendMessage(ChatColor.GRAY + " Start: " + (c.start != null ? "set" : "not set"));
        sender.sendMessage(ChatColor.GRAY + " End: " + (c.end != null ? "set" : "not set"));
        sender.sendMessage(ChatColor.GRAY + " Checkpoints: " + c.checkpoints.size());
    }

    private void leaderboard(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /parkour leaderboard <name>"); return; }
        Course c = requireCourse(sender, args[1]);
        if (c == null) return;
        Map<UUID, LeaderboardEntry> board = mod.getLeaderboard(c.name.toLowerCase());
        if (board == null || board.isEmpty()) { sender.sendMessage(ChatColor.YELLOW + "No times recorded for '" + c.name + "' yet."); return; }
        List<LeaderboardEntry> entries = new ArrayList<>(board.values());
        entries.sort(Comparator.comparingLong(e -> e.bestTimeMillis));
        int limit = (int) ctx.configInt("leaderboard-size");
        if (limit < 1) limit = 10;
        sender.sendMessage(ChatColor.GOLD + "=== Leaderboard: " + c.name + " ===");
        int rank = 1;
        for (LeaderboardEntry e : entries) {
            if (rank > limit) break;
            sender.sendMessage(ChatColor.AQUA + "#" + rank + " " + ChatColor.WHITE + e.playerName
                    + ChatColor.GRAY + " - " + ChatColor.YELLOW + Utils.formatTime(e.bestTimeMillis));
            rank++;
        }
    }

    private void leave(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + "Only players can do this."); return; }
        mod.leaveRun(player);
    }

    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Parkour Commands ===");
        sender.sendMessage(ChatColor.AQUA + "/parkour list" + ChatColor.GRAY + " - list all courses");
        sender.sendMessage(ChatColor.AQUA + "/parkour info <name>" + ChatColor.GRAY + " - course details");
        sender.sendMessage(ChatColor.AQUA + "/parkour leaderboard <name>" + ChatColor.GRAY + " - best times");
        sender.sendMessage(ChatColor.AQUA + "/parkour leave" + ChatColor.GRAY + " - abandon your current run");
        if (sender.isOp()) {
            sender.sendMessage(ChatColor.AQUA + "/parkour create|delete <name>");
            sender.sendMessage(ChatColor.AQUA + "/parkour setstart|setend|addcheckpoint <name>");
            sender.sendMessage(ChatColor.AQUA + "/parkour clearcheckpoints <name>");
        }
    }
}
