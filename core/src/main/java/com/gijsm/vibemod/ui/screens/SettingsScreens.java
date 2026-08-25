package com.gijsm.vibemod.ui.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.gijsm.vibemod.platform.Messenger;
import com.gijsm.vibemod.platform.ui.Button;
import com.gijsm.vibemod.platform.ui.Input;
import com.gijsm.vibemod.platform.ui.Screen;
import com.gijsm.vibemod.platform.ui.UiAction;
import com.gijsm.vibemod.platform.ui.UiRenderer;
import com.gijsm.vibemod.platform.ui.WidthHint;
import com.gijsm.vibemod.ui.Style;

/**
 * The plugin-settings screen (ARCHITECTURE-V2 §3.3, screen 17): every reloadable
 * {@code config.yml} knob (thinking effort, streaming, timeout, max tokens,
 * retries, concurrency, watchdog budgets, debug echo default) as a real form
 * input, plus buttons to open the model picker, re-read {@code config.yml} from
 * disk, or save.
 *
 * <p>This was v1's {@code ui/SettingsDialog}. It is the one screen that is a
 * {@code MENU} carrying inputs — three sibling actions on a form — which is why
 * the screen model keeps {@code inputs} and {@code kind} independent rather than
 * implying one from the other.
 *
 * <p>Every submitted number is clamped here, server-side, before it reaches the
 * save callback. A client can report whatever it likes for a slider, and on the
 * chat renderer the "slider" is a line the player typed by hand.
 */
public final class SettingsScreens {

    private static final List<String> EFFORTS = List.of("off", "low", "medium", "high");

    private static final double TIMEOUT_MIN = 30;
    private static final double TIMEOUT_MAX = 600;
    private static final double TIMEOUT_STEP = 15;
    private static final double MAXTOKENS_MIN = 0;
    private static final double MAXTOKENS_MAX = 131072;
    private static final double MAXTOKENS_STEP = 1024;
    private static final double RETRIES_MIN = 0;
    private static final double RETRIES_MAX = 10;
    private static final double RETRIES_STEP = 1;
    private static final double CONCURRENCY_MIN = 1;
    private static final double CONCURRENCY_MAX = 8;
    private static final double CONCURRENCY_STEP = 1;
    private static final double WATCHDOG_MS_MIN = 50;
    private static final double WATCHDOG_MS_MAX = 1000;
    private static final double WATCHDOG_MS_STEP = 25;
    private static final double WATCHDOG_BUDGET_MIN = 100;
    private static final double WATCHDOG_BUDGET_MAX = 2000;
    private static final double WATCHDOG_BUDGET_STEP = 50;

    /** Snapshot of the current settings, both what the form shows and what Save submits. */
    public record Values(String model, String modelPriceLabel, double sessionCostUsd,
                         String effort, boolean streaming, long timeoutSeconds, int maxTokens,
                         int maxRetries, int concurrency, boolean watchdogEnabled,
                         long watchdogSingleMs, long watchdogBudgetMs, boolean debugEcho) {
    }

    /** Persists and live-applies a submitted settings snapshot. */
    @FunctionalInterface
    public interface SaveSubmit {
        void save(UUID player, Values values);
    }

    private final Messenger messenger;
    private final UiRenderer renderer;
    private final Supplier<Values> snapshot;
    private final SaveSubmit onSave;
    private final Consumer<UUID> pickModel;
    private final Runnable reloadConfig;

    public SettingsScreens(Messenger messenger, UiRenderer renderer, Supplier<Values> snapshot,
                           SaveSubmit onSave, Consumer<UUID> pickModel, Runnable reloadConfig) {
        this.messenger = messenger;
        this.renderer = renderer;
        this.snapshot = snapshot;
        this.onSave = onSave;
        this.pickModel = pickModel;
        this.reloadConfig = reloadConfig;
    }

    /** A fresh snapshot of the current values — the console's {@code /vibe settings} dump uses this. */
    public Values currentValues() {
        return snapshot.get();
    }

    /** The settings form, prefilled from a fresh snapshot. */
    public Screen settings() {
        Values v = snapshot.get();

        List<Input> inputs = List.of(
                effortInput(v.effort()),
                new Input.Bool("streaming", Component.text("Streaming (live progress)"), v.streaming()),
                number("timeout", "Request timeout", "%s: %ss",
                        TIMEOUT_MIN, TIMEOUT_MAX, TIMEOUT_STEP, v.timeoutSeconds()),
                number("maxtokens", "Max tokens", "%s: %s (0 = model ceiling)",
                        MAXTOKENS_MIN, MAXTOKENS_MAX, MAXTOKENS_STEP, v.maxTokens()),
                number("retries", "Max retries", "%s: %s",
                        RETRIES_MIN, RETRIES_MAX, RETRIES_STEP, v.maxRetries()),
                number("concurrency", "Concurrency (applies on next reload)", "%s: %s",
                        CONCURRENCY_MIN, CONCURRENCY_MAX, CONCURRENCY_STEP, v.concurrency()),
                new Input.Bool("watchdog", Component.text("Watchdog enabled"), v.watchdogEnabled()),
                number("watchdogms", "Watchdog single-invocation", "%s: %sms",
                        WATCHDOG_MS_MIN, WATCHDOG_MS_MAX, WATCHDOG_MS_STEP, v.watchdogSingleMs()),
                number("watchdogbudget", "Watchdog per-second budget", "%s: %sms",
                        WATCHDOG_BUDGET_MIN, WATCHDOG_BUDGET_MAX, WATCHDOG_BUDGET_STEP, v.watchdogBudgetMs()),
                new Input.Bool("debugecho", Component.text("Debug echo default (new mods)"), v.debugEcho()));

        Button save = new Button(Component.text("Save", Style.OK),
                Component.text("Persist and apply", Style.INFO), WidthHint.BODY,
                new UiAction.Submit(this::handleSave));
        Button model = new Button(Component.text("Model…"),
                Component.text("Pick from the live OpenRouter catalog", Style.INFO), WidthHint.BODY,
                new UiAction.Callback((response, player) -> pickModel.accept(player)));
        Button reload = new Button(Component.text("⟳ Reload from disk"),
                Component.text("Re-read config.yml", Style.INFO), WidthHint.BODY,
                new UiAction.Callback((response, player) -> {
                    reloadConfig.run();
                    messenger.player(player).sendMessage(Style.ok("Config reloaded."));
                    renderer.show(player, settings());
                }));

        return new Screen(ScreenKit.title("VibeMod — settings"), Screen.Kind.MENU,
                List.of(ScreenKit.icon("COMPARATOR", false,
                                Component.text("Generation & runtime settings", Style.INFO)),
                        ScreenKit.text(Component.text(
                                "Model: " + v.model() + " (" + v.modelPriceLabel() + ")", Style.INFO)),
                        ScreenKit.text(Component.text("Spent this session: ", Style.INFO)
                                .append(Component.text(Style.fmtCost(v.sessionCostUsd()),
                                        NamedTextColor.WHITE))),
                        ScreenKit.text(Component.text(
                                "API key and error-storm limits live in config.yml", Style.META))),
                inputs,
                List.of(save, model, reload),
                ScreenKit.cancel(), 3);
    }

    // ---- inputs ----

    private static Input effortInput(String current) {
        String initial = EFFORTS.contains(current) ? current : "off";
        List<Input.Choice.Option> options = new ArrayList<>();
        for (String e : EFFORTS) {
            options.add(new Input.Choice.Option(e, Component.text(e), e.equals(initial)));
        }
        return new Input.Choice("thinking", Component.text("Thinking effort"), options);
    }

    private static Input number(String key, String label, String labelFormat,
                                double min, double max, double step, double current) {
        return new Input.Number(key, Component.text(label), min, max, step,
                clamp(current, min, max), labelFormat);
    }

    // ---- save ----

    private void handleSave(com.gijsm.vibemod.platform.ui.UiResponse response, UUID player) {
        String effort = response.text("thinking");
        if (effort == null || !EFFORTS.contains(effort)) {
            effort = "off";
        }
        Values current = snapshot.get();
        Values submitted = new Values(
                current.model(), current.modelPriceLabel(), current.sessionCostUsd(),
                effort,
                bool(response, "streaming"),
                Math.round(clamped(response, "timeout", TIMEOUT_MIN, TIMEOUT_MAX)),
                (int) Math.round(clamped(response, "maxtokens", MAXTOKENS_MIN, MAXTOKENS_MAX)),
                (int) Math.round(clamped(response, "retries", RETRIES_MIN, RETRIES_MAX)),
                (int) Math.round(clamped(response, "concurrency", CONCURRENCY_MIN, CONCURRENCY_MAX)),
                bool(response, "watchdog"),
                Math.round(clamped(response, "watchdogms", WATCHDOG_MS_MIN, WATCHDOG_MS_MAX)),
                Math.round(clamped(response, "watchdogbudget", WATCHDOG_BUDGET_MIN, WATCHDOG_BUDGET_MAX)),
                bool(response, "debugecho"));
        onSave.save(player, submitted);
    }

    private static boolean bool(com.gijsm.vibemod.platform.ui.UiResponse response, String key) {
        Boolean b = response.bool(key);
        return b != null && b;
    }

    /** The submitted value clamped into its real range — a client can report anything. */
    private static double clamped(com.gijsm.vibemod.platform.ui.UiResponse response,
                                  String key, double min, double max) {
        Double d = response.number(key);
        return clamp(d == null ? min : d, min, max);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
