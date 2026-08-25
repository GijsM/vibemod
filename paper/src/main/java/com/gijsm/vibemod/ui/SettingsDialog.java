package com.gijsm.vibemod.ui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The native plugin-settings dialog: every reloadable {@code config.yml} knob
 * (thinking effort, streaming, timeout, max tokens, retries, concurrency,
 * watchdog budgets, debug echo default) as a real form input, plus buttons to
 * open the model picker, re-read config.yml from disk, or save.
 *
 * <p>Its own class rather than another method on {@link Dialogs} because that
 * constructor is a frozen v3 surface (see its class javadoc) — same route
 * {@link InfoDialogs} took. Wiring is injected at construction: a snapshot
 * supplier for current values, a save consumer, the model-picker opener
 * (which the wiring layer arranges to reopen this dialog after a pick), and
 * the reload-config runnable.
 *
 * <p>Same experimental-API and threading posture as {@link Dialogs}:
 * {@code UnstableApiUsage} suppressed file-wide, dialogs shown next tick,
 * every button callback hops to the main thread, never lets an exception
 * escape into Bukkit, and re-validates (clamps) every submitted number
 * server-side, since a client can report whatever it likes for a slider.
 */
@SuppressWarnings("UnstableApiUsage")
public final class SettingsDialog {

    private static final Logger LOG = Logger.getLogger(SettingsDialog.class.getName());

    /** Snapshot of the current settings, both what the form shows and what Save submits. */
    public record Values(String model, String modelPriceLabel, double sessionCostUsd,
                         String effort, boolean streaming, long timeoutSeconds, int maxTokens,
                         int maxRetries, int concurrency, boolean watchdogEnabled,
                         long watchdogSingleMs, long watchdogBudgetMs, boolean debugEcho) {
    }

    /** Persists and live-applies a submitted settings snapshot. */
    @FunctionalInterface
    public interface SaveSubmit {
        void save(Player p, Values values);
    }

    private static final List<String> EFFORTS = List.of("off", "low", "medium", "high");

    private static final float TIMEOUT_MIN = 30, TIMEOUT_MAX = 600, TIMEOUT_STEP = 15;
    private static final float MAXTOKENS_MIN = 0, MAXTOKENS_MAX = 131072, MAXTOKENS_STEP = 1024;
    private static final float RETRIES_MIN = 0, RETRIES_MAX = 10, RETRIES_STEP = 1;
    private static final float CONCURRENCY_MIN = 1, CONCURRENCY_MAX = 8, CONCURRENCY_STEP = 1;
    private static final float WATCHDOG_MS_MIN = 50, WATCHDOG_MS_MAX = 1000, WATCHDOG_MS_STEP = 25;
    private static final float WATCHDOG_BUDGET_MIN = 100, WATCHDOG_BUDGET_MAX = 2000, WATCHDOG_BUDGET_STEP = 50;

    private final Plugin plugin;
    private final Supplier<Values> snapshot;
    private final SaveSubmit onSave;
    private final Consumer<Player> pickModel;
    private final Runnable reloadConfig;

    public SettingsDialog(Plugin plugin, Supplier<Values> snapshot, SaveSubmit onSave,
                          Consumer<Player> pickModel, Runnable reloadConfig) {
        this.plugin = plugin;
        this.snapshot = snapshot;
        this.onSave = onSave;
        this.pickModel = pickModel;
        this.reloadConfig = reloadConfig;
    }

    /** A fresh snapshot of the current values - the console's {@code /vibe settings} dump uses this. */
    public Values currentValues() {
        return snapshot.get();
    }

    /** Opens the settings form for {@code p} (ops only), prefilled from a fresh snapshot. */
    public void open(Player p) {
        if (!p.hasPermission("vibe.admin")) {
            p.sendMessage(Style.err("You don't have permission for that."));
            return;
        }
        Values v = snapshot.get();

        List<DialogBody> body = List.of(
                DialogKit.iconBody(Material.COMPARATOR,
                        Component.text("Generation & runtime settings", Style.INFO)),
                DialogBody.plainMessage(Component.text(
                        "Model: " + v.model() + " (" + v.modelPriceLabel() + ")", Style.INFO), DialogKit.BODY),
                DialogBody.plainMessage(Component.text("Spent this session: ", Style.INFO)
                        .append(Component.text(Style.fmtCost(v.sessionCostUsd()), NamedTextColor.WHITE)),
                        DialogKit.BODY),
                DialogBody.plainMessage(Component.text(
                        "API key and error-storm limits live in config.yml", Style.META), DialogKit.BODY));

        List<DialogInput> inputs = List.of(
                effortInput(v.effort()),
                DialogInput.bool("streaming", Component.text("Streaming (live progress)"))
                        .initial(v.streaming())
                        .build(),
                number("timeout", "Request timeout", "%s: %ss",
                        TIMEOUT_MIN, TIMEOUT_MAX, TIMEOUT_STEP, v.timeoutSeconds()),
                number("maxtokens", "Max tokens", "%s: %s (0 = model ceiling)",
                        MAXTOKENS_MIN, MAXTOKENS_MAX, MAXTOKENS_STEP, v.maxTokens()),
                number("retries", "Max retries", "%s: %s",
                        RETRIES_MIN, RETRIES_MAX, RETRIES_STEP, v.maxRetries()),
                number("concurrency", "Concurrency (applies on next reload)", "%s: %s",
                        CONCURRENCY_MIN, CONCURRENCY_MAX, CONCURRENCY_STEP, v.concurrency()),
                DialogInput.bool("watchdog", Component.text("Watchdog enabled"))
                        .initial(v.watchdogEnabled())
                        .build(),
                number("watchdogms", "Watchdog single-invocation", "%s: %sms",
                        WATCHDOG_MS_MIN, WATCHDOG_MS_MAX, WATCHDOG_MS_STEP, v.watchdogSingleMs()),
                number("watchdogbudget", "Watchdog per-second budget", "%s: %sms",
                        WATCHDOG_BUDGET_MIN, WATCHDOG_BUDGET_MAX, WATCHDOG_BUDGET_STEP, v.watchdogBudgetMs()),
                DialogInput.bool("debugecho", Component.text("Debug echo default (new mods)"))
                        .initial(v.debugEcho())
                        .build());

        ActionButton save = ActionButton.builder(Component.text("Save", Style.OK))
                .tooltip(Component.text("Persist and apply", Style.INFO))
                .action(mainThreadClick(this::handleSave))
                .build();
        ActionButton model = ActionButton.builder(Component.text("Model…"))
                .tooltip(Component.text("Pick from the live OpenRouter catalog", Style.INFO))
                .action(mainThreadClick((view, audience) -> {
                    if (audience instanceof Player player) {
                        pickModel.accept(player);
                    }
                }))
                .build();
        ActionButton reload = ActionButton.builder(Component.text("⟳ Reload from disk"))
                .tooltip(Component.text("Re-read config.yml", Style.INFO))
                .action(mainThreadClick((view, audience) -> {
                    if (audience instanceof Player player) {
                        reloadConfig.run();
                        player.sendMessage(Style.ok("Config reloaded."));
                        open(player);
                    }
                }))
                .build();

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(DialogKit.title("VibeMod — settings"))
                        .body(body)
                        .inputs(inputs)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(List.of(save, model, reload))
                        .exitAction(DialogKit.cancelButton())
                        .columns(3)
                        .build()));
        show(p, dialog);
    }

    // ---- inputs ----

    private static DialogInput effortInput(String current) {
        String initial = EFFORTS.contains(current) ? current : "off";
        List<SingleOptionDialogInput.OptionEntry> entries = EFFORTS.stream()
                .map(e -> SingleOptionDialogInput.OptionEntry.create(e, Component.text(e), e.equals(initial)))
                .toList();
        return DialogInput.singleOption("thinking", Component.text("Thinking effort"), entries)
                .width(DialogKit.INPUT)
                .build();
    }

    private static DialogInput number(String key, String label, String labelFormat,
                                      float min, float max, float step, double current) {
        return DialogInput.numberRange(key, Component.text(label), min, max)
                .width(DialogKit.INPUT)
                .labelFormat(labelFormat)
                .initial((float) clamp(current, min, max))
                .step(step)
                .build();
    }

    // ---- save ----

    private void handleSave(DialogResponseView view, Audience audience) {
        if (!(audience instanceof Player player)) {
            return;
        }
        String effort = view.getText("thinking");
        if (effort == null || !EFFORTS.contains(effort)) {
            effort = "off";
        }
        Values current = snapshot.get();
        Values submitted = new Values(
                current.model(), current.modelPriceLabel(), current.sessionCostUsd(),
                effort,
                bool(view, "streaming"),
                Math.round(clamped(view, "timeout", TIMEOUT_MIN, TIMEOUT_MAX)),
                (int) Math.round(clamped(view, "maxtokens", MAXTOKENS_MIN, MAXTOKENS_MAX)),
                (int) Math.round(clamped(view, "retries", RETRIES_MIN, RETRIES_MAX)),
                (int) Math.round(clamped(view, "concurrency", CONCURRENCY_MIN, CONCURRENCY_MAX)),
                bool(view, "watchdog"),
                Math.round(clamped(view, "watchdogms", WATCHDOG_MS_MIN, WATCHDOG_MS_MAX)),
                Math.round(clamped(view, "watchdogbudget", WATCHDOG_BUDGET_MIN, WATCHDOG_BUDGET_MAX)),
                bool(view, "debugecho"));
        onSave.save(player, submitted);
    }

    private static boolean bool(DialogResponseView view, String key) {
        Boolean b = view.getBoolean(key);
        return b != null && b;
    }

    /** The submitted slider value clamped into its real range - a client can report anything. */
    private static double clamped(DialogResponseView view, String key, float min, float max) {
        Float f = view.getFloat(key);
        return clamp(f == null ? min : f, min, max);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- shared plumbing (thin wrappers over DialogKit with this class's plugin/logger) ----

    /** See {@link DialogKit#show}. */
    private void show(Player p, Dialog dialog) {
        DialogKit.show(plugin, p, dialog);
    }

    /** See {@link DialogKit#mainThreadClick}. */
    private DialogAction mainThreadClick(java.util.function.BiConsumer<DialogResponseView, Audience> body) {
        return DialogKit.mainThreadClick(plugin, LOG, body);
    }
}
