package com.gijsm.vibemod.ui;

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
 * energy — and, since v3.1, actual knowledge of what is being built: the
 * model streams its output and declares a plan manifest up front, so the bar
 * names the mod within seconds ("Conjuring MazeHedge…"), advances file-by-file
 * against the plan, and a scrolling ticker inside the bar name shows the file
 * currently being written with live volume ("✍ CourseManager.java 3/5 · 6.2k chars").
 *
 * <p>Progress is mapped onto honest spans: Thinking 0→.05, the streamed
 * Writing span .05→.70 (plan-fraction when a plan exists, char-asymptote
 * otherwise), Compiling .70→.90, Loading .90→1. Repair rounds restart the
 * Writing span under repair verbs. Without streaming (config off, fallback,
 * or console viewers) everything degrades to the previous behavior.
 *
 * <p>{@code streamStats} is volatile-write only (called from HTTP threads at
 * up to ~4/s); the animator task — main thread, every 6 ticks — is the single
 * renderer for the bar. Chat receives only milestones.
 */
public final class Progress {

    private static final long ANIMATION_PERIOD_TICKS = 6;
    private static final int TICKS_PER_VERB = 8;
    private static final long MAX_ANIMATION_TICKS = 20 * 60 * 10; // 10 minutes
    private static final int TICKER_WINDOW = 32;
    private static final String TICKER_LOOP_GAP = "  ·  ";

    /** Claude-ish warm shimmer for the label text. */
    private static final List<TextColor> SHIMMER = List.of(
            TextColor.color(0xC084FC),
            TextColor.color(0xE879F9),
            TextColor.color(0xF472B6),
            TextColor.color(0xFB7185),
            TextColor.color(0xFB923C),
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
    private long frame = 0;
    private int verbOffset = 0;
    private List<String> verbs = THINKING;
    private boolean finished = false;
    /** planName currently shows "in queue (#P)"; cleared when the first real phase fires. */
    private boolean queuedNotice = false;

    // span model (main-thread reads/writes via phase())
    private float phaseFloor = 0f;
    private float phaseCeil = 0.05f;
    private boolean streamedSpan = false;

    // live stream state (written from HTTP threads / runOnMain; read by the animator)
    private volatile String planName;
    private volatile String currentFile;
    private volatile int plannedTotal = -1;
    private volatile int filesStarted = 0;
    private volatile int streamChars = 0;
    private volatile int approxTokens = 0;

    public Progress(Plugin plugin, CommandSender viewer, String title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.title = title;
        this.player = (viewer instanceof Player p) ? p : null;
    }

    /**
     * The run was submitted while all generator threads were busy: one chat milestone,
     * and the boss bar shows the wait via a borrowed planName ("in queue (#P)") until
     * the first real phase (Thinking) arrives and {@link #applySpan} clears it.
     */
    public void queued(int position, int running) {
        runOnMain(() -> {
            if (finished) {
                return;
            }
            queuedNotice = true;
            planName = "in queue (#" + position + ")";
            if (player != null) {
                ensureBossBar();
                startAnimation();
            }
            chat(Style.info("⏳ " + running + " generation" + (running == 1 ? "" : "s")
                    + " running — yours is #" + position + " in line"));
        });
    }

    /** Advance to a new phase: verb pool + honest progress span switch, one chat line. */
    public void phase(String label) {
        runOnMain(() -> {
            if (finished) {
                return;
            }
            verbs = verbPool(label);
            verbOffset = ThreadLocalRandom.current().nextInt(verbs.size());
            applySpan(label);
            if (player != null) {
                ensureBossBar();
                startAnimation();
            }
            chat(Style.prefix().append(Component.text(verbs.get(verbOffset) + "… ", SHIMMER.get(0)))
                    .append(Component.text("(" + title + ")", NamedTextColor.DARK_GRAY)));
        });
    }

    /** A gray, low-priority informational chat line. */
    public void detail(String line) {
        runOnMain(() -> chat(Style.info(line)));
    }

    /** The model declared what it will build: name + planned files, in order. */
    public void planReady(String name, List<String> files) {
        runOnMain(() -> {
            if (finished) {
                return;
            }
            this.planName = name;
            this.plannedTotal = files.size();
            chat(Style.info("Plan: " + (name == null ? title : name) + " — " + files.size()
                    + (files.size() == 1 ? " file" : " files")));
        });
    }

    /** A planned file started streaming ({@code total} <= 0 = plan unknown). */
    public void fileStarted(String path, int index, int total) {
        this.currentFile = path;
        this.filesStarted = total > 0 ? Math.min(index, total) : index;
        if (player == null) {
            // Console narration: one bounded line per file, no char ticks.
            runOnMain(() -> chat(Style.info("→ [" + index + (total > 0 ? "/" + total : "") + "] " + path)));
        }
    }

    /** Rolling stream volume — volatile write only; the animator renders it. */
    public void streamStats(int chars, int approxTokens) {
        this.streamChars = chars;
        this.approxTokens = approxTokens;
    }

    /** Complete successfully: color-flash finale, full green bar, sound + firework, then hide. */
    public void succeed(String message) {
        runOnMain(() -> {
            finished = true;
            stopAnimation();
            if (player != null) {
                ensureBossBar();
                bossBar.progress(1f);
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

    // ---- span model ----

    private void applySpan(String label) {
        streamedSpan = false;
        if (label == null) {
            phaseFloor = 0f;
            phaseCeil = 0.05f;
            return;
        }
        if (label.startsWith("Thinking")) {
            // New round (initial or repair): the streamed span restarts honestly.
            phaseFloor = 0f;
            phaseCeil = 0.05f;
            if (queuedNotice) {
                // The wait is over: give planName back to the real plan.
                queuedNotice = false;
                planName = null;
            }
            currentFile = null;
            filesStarted = 0;
            streamChars = 0;
            approxTokens = 0;
        } else if (label.equals("Writing")) {
            phaseFloor = 0.05f;
            phaseCeil = 0.70f;
            streamedSpan = true;
        } else if (label.equals("Compiling")) {
            phaseFloor = 0.70f;
            phaseCeil = 0.90f;
        } else if (label.equals("Loading")) {
            phaseFloor = 0.90f;
            phaseCeil = 1.0f;
        }
    }

    /** Real progress inside the current span; monotone, clamped, never backward. */
    private float spanProgress() {
        if (!streamedSpan) {
            return phaseFloor + (phaseCeil - phaseFloor) * 0.5f;
        }
        int total = plannedTotal;
        float fraction;
        if (total > 0) {
            fraction = Math.max(0f, Math.min(1f, (filesStarted - 0.5f) / total));
        } else {
            fraction = (float) (1.0 - Math.exp(-streamChars / 20000.0));
        }
        return phaseFloor + (phaseCeil - phaseFloor) * fraction;
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
            hideNow();
            return;
        }
        if (player != null && !player.isOnline()) {
            stopAnimation();
            hideNow();
            return;
        }
        String sparkle = SPARKLES[(int) (frame % SPARKLES.length)];
        String dots = DOTS[(int) (frame % DOTS.length)];
        String verb = verbs.get((int) ((verbOffset + frame / TICKS_PER_VERB) % verbs.size()));
        TextColor color = SHIMMER.get((int) (frame % SHIMMER.size()));

        // The plan name becomes the verb's object the moment we know it.
        String object = planName != null ? " " + planName : "";
        bossBar.name(Component.text(sparkle + " ", SHIMMER.get((int) ((frame + 3) % SHIMMER.size())))
                .append(Component.text(verb + object + dots, color))
                .append(Component.text(" ▸ ", NamedTextColor.DARK_GRAY))
                .append(Component.text(tickerWindow(tickerText()), NamedTextColor.GRAY)));
        if (frame % 5 == 0) {
            bossBar.color(BAR_CYCLE.get((int) ((frame / 5) % BAR_CYCLE.size())));
        }
        float base = spanProgress();
        float wobble = (float) (Math.sin(frame / 3.0) * 0.015);
        bossBar.progress(Math.max(phaseFloor, Math.min(phaseCeil, base + wobble)));
    }

    /** Live ticker content, rebuilt every frame from the volatile stream state. */
    private String tickerText() {
        StringBuilder line = new StringBuilder();
        if (streamedSpan) {
            if (currentFile != null) {
                line.append("✍ ").append(currentFile);
                if (plannedTotal > 0) {
                    line.append(' ').append(filesStarted).append('/').append(plannedTotal);
                }
                line.append(" · ");
            }
            line.append(fmtK(streamChars)).append(" chars");
            if (approxTokens > 0) {
                line.append(" · ~").append(fmtK(approxTokens)).append(" tok");
            }
            line.append(" · ⬡ ").append(title);
        } else {
            line.append("⬡ ").append(title);
            if (phaseFloor >= 0.70f && plannedTotal > 0) {
                line.append(" · ").append(plannedTotal).append(" files");
            }
        }
        return line.toString();
    }

    /** Circular marquee: a fixed window over the ticker, advancing one char per frame. */
    private String tickerWindow(String text) {
        if (text.length() <= TICKER_WINDOW) {
            return text;
        }
        String looped = text + TICKER_LOOP_GAP;
        int start = (int) (frame % looped.length());
        StringBuilder out = new StringBuilder(TICKER_WINDOW);
        for (int i = 0; i < TICKER_WINDOW; i++) {
            out.append(looped.charAt((start + i) % looped.length()));
        }
        return out.toString();
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private static String fmtK(int chars) {
        return chars >= 1000 ? String.format("%.1fk", chars / 1000.0) : String.valueOf(chars);
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
            bossBar = BossBar.bossBar(Component.text(title), phaseFloor,
                    BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
            player.showBossBar(bossBar);
        }
    }

    private void hideNow() {
        if (bossBar != null && player != null) {
            player.hideBossBar(bossBar);
        }
    }

    private void hideAfter(long ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, this::hideNow, ticks);
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
