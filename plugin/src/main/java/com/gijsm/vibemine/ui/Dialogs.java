package com.gijsm.vibemine.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemine.llm.ModelCatalog;

/**
 * Native Minecraft Dialog UI ({@code io.papermc.paper.dialog}, Paper 1.21.8)
 * replacing the retired book-and-quill flows: a multiline prompt dialog for
 * {@code /vibe make}, an edit-request dialog, a per-knob config dialog, and a
 * fix confirmation.
 *
 * <p>The dialog API is {@code @Experimental} on this Paper version; those
 * warnings are suppressed file-wide (there is no {@code -Werror} anywhere in
 * this build, so they are purely informational) rather than annotated on
 * every method individually.
 *
 * <p>Every submit callback hops back to the main thread before touching any
 * Bukkit API - dialog callbacks are not guaranteed to run there - and
 * re-validates submitted values server-side, since a client can report
 * whatever it likes for a slider or checkbox. Nothing thrown out of a
 * callback is ever allowed to propagate into Bukkit: it is caught, logged,
 * and reported to the player via {@link Style#err}.
 *
 * <p>{@link #openFixConfirm} has no injected "run the fix" callback (the
 * constructor below is the frozen v3 surface and only carries the make/edit/
 * config callbacks reused from the old book flows) - its confirm button
 * instead runs {@code /vibe fix <mod> confirm} as a real command, which
 * {@code VibeCommand} treats as "already confirmed, run it now" instead of
 * reopening this same dialog.
 */
@SuppressWarnings("UnstableApiUsage")
public final class Dialogs {

    private static final Logger LOG = Logger.getLogger(Dialogs.class.getName());

    /** Submits a free-text change request against an existing mod. */
    @FunctionalInterface
    public interface EditSubmit {
        void submit(Player p, String modName, String changeRequest);
    }

    /** Applies parsed config values; returns one human-readable error string per rejected key. */
    @FunctionalInterface
    public interface ConfigSubmit {
        List<String> apply(Player p, String modName, Map<String, String> values);
    }

    /** One config knob as shown to the player: its schema plus the value to prefill. */
    public record Knob(String key, String type, String description, String value,
                       Double min, Double max, Double step, List<String> choices) {
    }

    private static final int PROMPT_MAX_LENGTH = 2000;
    /** Wider inputs + body lines so labels/values do not clip. */
    private static final int INPUT_WIDTH = 350;
    private static final int BODY_WIDTH = 400;

    private static final int KNOB_TEXT_MAX_LENGTH = 256;
    private static final int NAME_HINT_MAX_LENGTH = 32;
    private static final int CUSTOM_MODEL_MAX_LENGTH = 80;
    private static final int MULTILINE_HEIGHT = 160;
    private static final double DEFAULT_MIN = 0.0;
    private static final double DEFAULT_MAX = 100.0;
    private static final double DEFAULT_STEP = 1.0;

    private final Plugin plugin;
    private final BiConsumer<Player, String> onPrompt;
    private final EditSubmit onEdit;
    private final ConfigSubmit onConfig;

    public Dialogs(Plugin plugin, BiConsumer<Player, String> onPrompt, EditSubmit onEdit, ConfigSubmit onConfig) {
        this.plugin = plugin;
        this.onPrompt = onPrompt;
        this.onEdit = onEdit;
        this.onConfig = onConfig;
    }

    // ---- make ----

    /** Opens the "make a new mod" dialog: a multiline prompt plus an optional name hint. */
    public void openPrompt(Player p) {
        DialogInput textInput = DialogInput.text("prompt", Component.text("What should the mod do?"))
                .maxLength(PROMPT_MAX_LENGTH)
                .multiline(TextDialogInput.MultilineOptions.create(null, MULTILINE_HEIGHT))
                .build();
        DialogInput nameInput = DialogInput.text("name", Component.text("Name hint (optional)"))
                .maxLength(NAME_HINT_MAX_LENGTH)
                .labelVisible(true)
                .build();

        ActionButton create = ActionButton.builder(Component.text("Create ✨"))
                .action(mainThreadClick(this::handlePromptSubmit))
                .build();
        ActionButton cancel = ActionButton.builder(Component.text("Cancel")).action(noOp()).build();

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Make a mod"))
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                "Describe what the mod should do. Be specific.", NamedTextColor.GRAY))))
                        .inputs(List.of(textInput, nameInput))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(create, cancel)));
        show(p, dialog);
    }

    private void handlePromptSubmit(DialogResponseView view, Audience audience) {
        if (!(audience instanceof Player player)) {
            return;
        }
        String text = view.getText("prompt");
        if (text == null || text.isBlank()) {
            player.sendMessage(Style.err("Nothing to make - the prompt was empty."));
            return;
        }
        String name = view.getText("name");
        String finalText = (name != null && !name.isBlank()) ? "Name hint: " + name.trim() + "\n" + text : text;
        onPrompt.accept(player, finalText);
    }

    // ---- edit ----

    /** Opens the edit-request dialog for {@code mod}, showing {@code manualSummary} as context. */
    public void openEdit(Player p, String mod, String manualSummary) {
        String summary = (manualSummary == null || manualSummary.isBlank()) ? "(no manual available)" : manualSummary;
        DialogInput changeInput = DialogInput.text("change", Component.text("What should change?"))
                .maxLength(PROMPT_MAX_LENGTH)
                .multiline(TextDialogInput.MultilineOptions.create(null, MULTILINE_HEIGHT))
                .build();

        ActionButton update = ActionButton.builder(Component.text("Update"))
                .action(mainThreadClick((view, audience) -> handleEditSubmit(mod, view, audience)))
                .build();
        ActionButton cancel = ActionButton.builder(Component.text("Cancel")).action(noOp()).build();

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Edit " + mod))
                        .body(List.of(DialogBody.plainMessage(Component.text(summary, NamedTextColor.GRAY))))
                        .inputs(List.of(changeInput))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(update, cancel)));
        show(p, dialog);
    }

    private void handleEditSubmit(String mod, DialogResponseView view, Audience audience) {
        if (!(audience instanceof Player player)) {
            return;
        }
        String change = view.getText("change");
        if (change == null || change.isBlank()) {
            player.sendMessage(Style.err("Nothing to change - the request was empty."));
            return;
        }
        onEdit.submit(player, mod, change);
    }

    // ---- config ----

    /** Opens the config dialog for {@code mod}: one input per knob, Save/Cancel. */
    public void openConfig(Player p, String mod, List<Knob> knobs) {
        openConfig(p, mod, knobs, null);
    }

    /**
     * Reopens the config dialog after a validation failure: {@code errorMessage} is shown as a red
     * banner and {@code knobs} already carries the player's just-submitted (rejected) values as
     * initials, so nothing typed is lost.
     */
    private void openConfig(Player p, String mod, List<Knob> knobs, String errorMessage) {
        if (knobs == null || knobs.isEmpty()) {
            p.sendMessage(Style.info(mod + " has no configurable settings."));
            return;
        }
        List<DialogBody> body = new ArrayList<>();
        if (errorMessage != null && !errorMessage.isBlank()) {
            body.add(DialogBody.plainMessage(Component.text(errorMessage, NamedTextColor.RED)));
        }
        // Inputs get short key-only labels (long text clips inside sliders/fields);
        // the descriptions live up here as one readable block instead.
        for (Knob knob : knobs) {
            String desc = knob.description();
            if (desc != null && !desc.isBlank()) {
                body.add(DialogBody.plainMessage(
                        Component.text(knob.key(), NamedTextColor.AQUA)
                                .append(Component.text(" — " + desc, NamedTextColor.GRAY)),
                        BODY_WIDTH));
            }
        }

        List<DialogInput> inputs = new ArrayList<>();
        for (Knob knob : knobs) {
            inputs.add(buildKnobInput(knob));
        }

        ActionButton save = ActionButton.builder(Component.text("Save"))
                .action(mainThreadClick((view, audience) -> handleConfigSubmit(mod, knobs, view, audience)))
                .build();
        ActionButton cancel = ActionButton.builder(Component.text("Cancel")).action(noOp()).build();

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Configure " + mod))
                        .body(body)
                        .inputs(inputs)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(save, cancel)));
        show(p, dialog);
    }

    private DialogInput buildKnobInput(Knob knob) {
        String type = knob.type() == null ? "text" : knob.type().toLowerCase(Locale.ROOT);
        Component label = labelFor(knob);
        String current = knob.value();
        switch (type) {
            case "boolean":
                return DialogInput.bool(inputKey(knob.key()), label)
                        .initial(current != null && Boolean.parseBoolean(current.trim()))
                        .build();
            case "choice": {
                List<String> choices = knob.choices() == null ? List.of() : knob.choices();
                List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                for (String choice : choices) {
                    boolean initial = choice.equalsIgnoreCase(current);
                    entries.add(SingleOptionDialogInput.OptionEntry.create(inputKey(choice), Component.text(choice), initial));
                }
                return DialogInput.singleOption(inputKey(knob.key()), label, entries).build();
            }
            case "integer":
            case "decimal": {
                double min = knob.min() != null ? knob.min() : DEFAULT_MIN;
                double max = knob.max() != null ? knob.max() : DEFAULT_MAX;
                double step = knob.step() != null ? knob.step() : DEFAULT_STEP;
                double initial = parseDouble(current, min);
                return DialogInput.numberRange(inputKey(knob.key()), label, (float) min, (float) max)
                        .width(INPUT_WIDTH)
                        .labelFormat("%s: %s")
                        .initial((float) initial)
                        .step((float) step)
                        .build();
            }
            case "text":
            default:
                return DialogInput.text(inputKey(knob.key()), label)
                        .width(INPUT_WIDTH)
                        .maxLength(KNOB_TEXT_MAX_LENGTH)
                        .initial(current == null ? "" : current)
                        .build();
        }
    }

    /**
     * Vanilla dialog input keys allow only letters, digits and underscores -
     * knob keys like "chicken-count" must be sanitized on the way in and
     * reverse-mapped on the way out.
     */
    private static String inputKey(String knobKey) {
        return knobKey.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    /**
     * Show next tick (never inside an inventory-click handler). Deliberately no
     * closeInventory(): showDialog replaces the client screen on its own, and a
     * close-container packet from here has stalled the main thread before.
     */
    private void show(Player p, Dialog dialog) {
        Bukkit.getScheduler().runTask(plugin, () -> p.showDialog(dialog));
    }

    /** Short label only — full descriptions render in the dialog body above the inputs. */
    private static Component labelFor(Knob knob) {
        return Component.text(knob.key());
    }

    private void handleConfigSubmit(String mod, List<Knob> knobs, DialogResponseView view, Audience audience) {
        if (!(audience instanceof Player player)) {
            return;
        }
        Map<String, String> submitted = new LinkedHashMap<>();
        for (Knob knob : knobs) {
            submitted.put(knob.key(), readValue(knob, view));
        }
        List<String> errors = onConfig.apply(player, mod, submitted);
        if (errors == null || errors.isEmpty()) {
            player.sendMessage(Style.ok(mod + " configuration saved."));
            return;
        }
        StringBuilder msg = new StringBuilder("Some values were rejected:");
        for (String e : errors) {
            msg.append('\n').append(e);
        }
        openConfig(player, mod, withValues(knobs, submitted), msg.toString());
    }

    /** Reads one knob's submitted value from the dialog response, converted to its canonical string form. */
    private static String readValue(Knob knob, DialogResponseView view) {
        String type = knob.type() == null ? "text" : knob.type().toLowerCase(Locale.ROOT);
        switch (type) {
            case "boolean": {
                Boolean b = view.getBoolean(inputKey(knob.key()));
                return Boolean.toString(b != null && b);
            }
            case "integer":
            case "decimal": {
                Float f = view.getFloat(inputKey(knob.key()));
                double raw = f == null ? 0.0 : f;
                double step = knob.step() != null ? knob.step() : DEFAULT_STEP;
                double rounded = roundToStep(raw, step);
                return "integer".equals(type) ? Long.toString(Math.round(rounded)) : Double.toString(rounded);
            }
            case "choice": {
                String picked = view.getText(inputKey(knob.key()));
                if (picked == null) {
                    return "";
                }
                if (knob.choices() != null) {
                    for (String choice : knob.choices()) {
                        if (inputKey(choice).equals(picked)) {
                            return choice; // reverse-map sanitized option id to the real value
                        }
                    }
                }
                return picked;
            }
            case "text":
            default: {
                String text = view.getText(inputKey(knob.key()));
                return text == null ? "" : text;
            }
        }
    }

    private static double roundToStep(double raw, double step) {
        if (step <= 0) {
            return raw;
        }
        return Math.round(raw / step) * step;
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Same knob schemas with {@code values}' entries as the new prefill values (fallback: unchanged). */
    private static List<Knob> withValues(List<Knob> knobs, Map<String, String> values) {
        List<Knob> out = new ArrayList<>(knobs.size());
        for (Knob k : knobs) {
            String v = values.getOrDefault(k.key(), k.value());
            out.add(new Knob(k.key(), k.type(), k.description(), v, k.min(), k.max(), k.step(), k.choices()));
        }
        return out;
    }

    // ---- model picker ----

    /**
     * Opens the model picker: a dropdown of {@code models} (id + live price, cheapest-first
     * per {@link ModelCatalog#featured}) plus a "custom model id" text field that wins over
     * the dropdown when non-blank, so a player is never limited to the curated list.
     */
    public void openModelPicker(Player p, List<ModelCatalog.ModelInfo> models, String currentId,
                                double sessionCostUsd, java.util.function.Consumer<String> onPick) {
        List<ModelCatalog.ModelInfo> options = (models == null || models.isEmpty())
                ? List.of(new ModelCatalog.ModelInfo(currentId, 0, -1.0, -1.0, false))
                : models;

        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            ModelCatalog.ModelInfo m = options.get(i);
            boolean initial = m.id().equals(currentId);
            entries.add(SingleOptionDialogInput.OptionEntry.create(
                    inputKey("m" + i), Component.text(m.id() + " — " + m.priceLabel()), initial));
        }
        DialogInput dropdown = DialogInput.singleOption("model", Component.text("Model"), entries).build();
        DialogInput customInput = DialogInput.text("custom", Component.text("Custom model id (optional)"))
                .width(INPUT_WIDTH)
                .maxLength(CUSTOM_MODEL_MAX_LENGTH)
                .labelVisible(true)
                .build();

        String currentPrice = options.stream().filter(m -> m.id().equals(currentId)).findFirst()
                .map(ModelCatalog.ModelInfo::priceLabel).orElse("price unknown");
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text(
                        "Current: " + currentId + " (" + currentPrice + ")", NamedTextColor.GRAY), BODY_WIDTH),
                DialogBody.plainMessage(Component.text(
                        "Spent this session: " + Style.fmtCost(sessionCostUsd), NamedTextColor.GRAY), BODY_WIDTH));

        ActionButton use = ActionButton.builder(Component.text("Use"))
                .action(mainThreadClick((view, audience) -> handleModelPick(options, view, audience, onPick)))
                .build();
        ActionButton cancel = ActionButton.builder(Component.text("Cancel")).action(noOp()).build();

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Choose a model"))
                        .body(body)
                        .inputs(List.of(dropdown, customInput))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(use, cancel)));
        show(p, dialog);
    }

    private void handleModelPick(List<ModelCatalog.ModelInfo> options, DialogResponseView view, Audience audience,
                                  java.util.function.Consumer<String> onPick) {
        if (!(audience instanceof Player player)) {
            return;
        }
        String custom = view.getText("custom");
        if (custom != null && !custom.isBlank()) {
            onPick.accept(custom.trim());
            return;
        }
        String picked = view.getText("model");
        if (picked != null) {
            for (int i = 0; i < options.size(); i++) {
                if (inputKey("m" + i).equals(picked)) {
                    onPick.accept(options.get(i).id());
                    return;
                }
            }
        }
        player.sendMessage(Style.err("No model selected."));
    }

    // ---- fix confirm ----

    /**
     * Confirms sending {@code mod}'s recent errors to the model for a repair round. The confirm
     * button runs {@code /vibe fix <mod> confirm} rather than calling back into this class, since
     * this class carries no fix callback (see the class javadoc).
     */
    public void openFixConfirm(Player p, String mod, String lastError) {
        String summary = (lastError == null || lastError.isBlank()) ? "(no error recorded)" : lastError;
        ActionButton fix = ActionButton.builder(Component.text("Fix it 🔧"))
                .action(DialogAction.staticAction(ClickEvent.runCommand("/vibe fix " + mod + " confirm")))
                .build();
        ActionButton notNow = ActionButton.builder(Component.text("Not now")).action(noOp()).build();

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text(mod + " is degraded"))
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                "Send the recent errors to the model to attempt a fix?\n\n" + summary,
                                NamedTextColor.GRAY))))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(fix, notNow)));
        show(p, dialog);
    }

    // ---- shared plumbing ----

    /** A no-op click action for buttons (e.g. Cancel) that should just close the dialog. */
    private static DialogAction noOp() {
        return DialogAction.customClick((view, audience) -> {
        }, ClickCallback.Options.builder().uses(1).build());
    }

    /**
     * Wraps a dialog callback so it always hops to the main thread before running, and never lets
     * an exception escape into Bukkit - it is logged and reported to the player instead.
     */
    private DialogAction mainThreadClick(java.util.function.BiConsumer<DialogResponseView, Audience> body) {
        return DialogAction.customClick((view, audience) ->
                        Bukkit.getScheduler().runTask(plugin, () -> runSafely(view, audience, body)),
                ClickCallback.Options.builder().uses(1).build());
    }

    private static void runSafely(DialogResponseView view, Audience audience,
                                   java.util.function.BiConsumer<DialogResponseView, Audience> body) {
        try {
            body.accept(view, audience);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Dialog callback failed", e);
            if (audience instanceof Player player) {
                player.sendMessage(Style.err("Something went wrong: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            }
        }
    }
}
