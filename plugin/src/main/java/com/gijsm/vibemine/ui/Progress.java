package com.gijsm.vibemine.ui;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Visual + chat feedback for one in-flight generation, with vibe-coding
 * energy: the boss bar animates like a coding-agent spinner — whimsical
 * cycling verbs per phase ("Conjuring…", "Forging bytecode…"), a sparkle
 * glyph that twinkles, animated ellipsis, and a warm purple→pink→orange
 * color shimmer — while chat gets exactly one line per phase (no spam).
 * Console viewers get the plain chat lines only.
 *
 * <p>Public surface is unchanged from v1: {@code phase/detail/succeed/fail}.
 * All Bukkit/Adventure mutations hop to the main thread.
 */
public final class Progress {

    private static final float STEP = 0.25f;
    private static final long ANIMATION_PERIOD_TICKS = 6;
    private static final int TICKS_PER_VERB = 8;      // in animation frames: ~2.4s per verb
    private static final long MAX_ANIMATION_TICKS = 20 * 60 * 5; // stop after 5 minutes

    /** Claude-ish warm shimmer for the label text. */
    private static final List<TextColor> SHIMMER = List.of(
            TextColor.color(0xC084FC),  // lavender
            TextColor.color(0xE879F9),  // orchid
            TextColor.color(0xF472B6),  // pink
            TextColor.color(0xFB7185),  // rose
            TextColor.color(0xFB923C),  // amber
            TextColor.color(0xF472B6),
            TextColor.color(0xE879F9));

    private static final List<BossBar.Color> BAR_CYCLE =
            List.of(BossBar.Color.PURPLE, BossBar.Color.PINK, BossBar.Color.BLUE);

    private static final String[] SPARKLES = {"✦", "✧", "✶", "✧"};
    private static final String[] DOTS = {"", ".", "..", "..."};

    private static final List<String> THINKING = List.of(
            "Vibing", "Conjuring", "Pondering", "Scheming", "Divining",
            "Summoning ideas", "Consulting the model", "Dreaming it up");
    private static final List<String> REPAIRING = List.of(
            "Unbreaking", "Debugging the vibes", "Re-rolling", "Negotiating with javac",
            "Applying duct tape", "Convincing it to work");
    private static final List<String> WRITING = List.of(
            "Scribing", "Weaving code", "Spellcrafting", "Typing furiously", "Inking the scrolls");
    private static final List<String> COMPILING = List.of(
            "Forging bytecode", "Smelting classes", "Feeding javac", "Crunching");
    private static final List<String> LOADING = List.of(
            "Breathing life into it", "Hatching", "Plugging it in", "Waking it up");

    private final Plugin plugin;
    private final CommandSender viewer;
    private final String title;
    private final Player player;
    private BossBar bossBar;
    private BukkitTask animator;
    private float progress = 0f;
    private long frame = 0;
    private int verbOffset = 0;
    private List<String> verbs = THINKING;
    private boolean finished = false;

    public Progress(Plugin plugin, CommandSender viewer, String title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.title = title;
        this.player = (viewer instanceof Player p) ? p : null;
    }

    /** Advance to a new phase: the bar animation switches verb pools, chat gets one line. */
    public void phase(String label) {
        runOnMain(() -> {
            if (finished) {
                return;
            }
            progress = Math.min(1f, progress + STEP);
            verbs = verbPool(label);
            verbOffset = ThreadLocalRandom.current().nextInt(verbs.size());
            String verb = verbs.get(verbOffset);
            if (player != null) {
                ensureBossBar();
                startAnimation();
                bossBar.progress(progress);
            }
            chat(Style.prefix().append(Component.text(verb + "… ", SHIMMER.get(0)))
                    .append(Component.text("(" + title + ")", NamedTextColor.DARK_GRAY)));
        });
    }

    /** A gray, low-priority informational chat line. */
    public void detail(String line) {
        runOnMain(() -> chat(Style.info(line)));
    }

    /** Complete successfully: color-flash finale, full green bar, sound + firework, then hide. */
    public void succeed(String message) {
        runOnMain(() -> {
            finished = true;
            stopAnimation();
            if (player != null) {
                ensureBossBar();
                bossBar.progress(1f);
                // three-frame celebration flash before settling on green
                BossBar.Color[] flash = {BossBar.Color.PINK, BossBar.Color.YELLOW, BossBar.Color.GREEN};
                for (int i = 0; i < flash.length; i++) {
                    BossBar.Color c = flash[i];
                    boolean last = i == flash.length - 1;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        bossBar.color(c);
                        bossBar.name(Component.text("✔ " + message,
                                last ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                    }, i * 3L);
                }
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                Location loc = player.getLocation();
                player.getWorld().spawnParticle(Particle.FIREWORK, loc, 30, 0.5, 0.5, 0.5);
                hideAfter(70);
            }
            chat(Style.ok(message));
        });
    }

    /** Complete with failure: full red bar, failure sound, then hide. */
    public void fail(String message) {
        runOnMain(() -> {
            finished = true;
            stopAnimation();
            if (player != null) {
                ensureBossBar();
                bossBar.progress(1f);
                bossBar.color(BossBar.Color.RED);
                bossBar.name(Component.text(message, NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                hideAfter(100);
            }
            chat(Style.err(message));
        });
    }

    // ---- animation ----

    private void startAnimation() {
        if (animator != null) {
            return;
        }
        animator = Bukkit.getScheduler().runTaskTimer(plugin, this::animate, 0, ANIMATION_PERIOD_TICKS);
    }

    private void animate() {
        if (bossBar == null || finished) {
            stopAnimation();
            return;
        }
        frame++;
        if (frame * ANIMATION_PERIOD_TICKS > MAX_ANIMATION_TICKS) {
            stopAnimation();
            return;
        }
        String sparkle = SPARKLES[(int) (frame % SPARKLES.length)];
        String dots = DOTS[(int) (frame % DOTS.length)];
        String verb = verbs.get((int) ((verbOffset + frame / TICKS_PER_VERB) % verbs.size()));
        TextColor color = SHIMMER.get((int) (frame % SHIMMER.size()));

        bossBar.name(Component.text(sparkle + " ", SHIMMER.get((int) ((frame + 3) % SHIMMER.size())))
                .append(Component.text(verb + dots, color))
                .append(Component.text("  ⬡ " + title, NamedTextColor.DARK_GRAY)));
        if (frame % 5 == 0) {
            bossBar.color(BAR_CYCLE.get((int) ((frame / 5) % BAR_CYCLE.size())));
        }
        // subtle breathing on the progress bar around the phase's true progress
        float wobble = (float) (Math.sin(frame / 3.0) * 0.02);
        bossBar.progress(Math.max(0f, Math.min(1f, progress + wobble)));
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private static List<String> verbPool(String label) {
        if (label == null) {
            return THINKING;
        }
        if (label.startsWith("Thinking (repair")) {
            return REPAIRING;
        }
        return switch (label) {
            case "Thinking" -> THINKING;
            case "Writing" -> WRITING;
            case "Compiling" -> COMPILING;
            case "Loading" -> LOADING;
            default -> THINKING;
        };
    }

    private void ensureBossBar() {
        if (bossBar == null) {
            bossBar = BossBar.bossBar(Component.text(title), progress,
                    BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
            player.showBossBar(bossBar);
        }
    }

    private void hideAfter(long ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (bossBar != null && player != null) {
                player.hideBossBar(bossBar);
            }
        }, ticks);
    }

    private void chat(Component line) {
        viewer.sendMessage(line);
    }

    private void runOnMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }
}
