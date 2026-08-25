package com.gijsm.vibemod.ui.screens;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.gijsm.vibemod.llm.ModelCatalog;
import com.gijsm.vibemod.platform.Messenger;
import com.gijsm.vibemod.platform.ui.BodyBlock;
import com.gijsm.vibemod.platform.ui.Button;
import com.gijsm.vibemod.platform.ui.Input;
import com.gijsm.vibemod.platform.ui.Screen;
import com.gijsm.vibemod.platform.ui.UiAction;
import com.gijsm.vibemod.platform.ui.UiRenderer;
import com.gijsm.vibemod.platform.ui.WidthHint;
import com.gijsm.vibemod.ui.Style;

/**
 * The typing and confirming screens (ARCHITECTURE-V2 §3.3, screens 1-7): make,
 * edit, configure, pick a model, and the fix / rollback / delete confirmations.
 *
 * <p>These were v1's {@code ui/Dialogs}. Every one of them is now a
 * {@link Screen} — data, with no idea whether it will end up as a vanilla dialog
 * or as a block of clickable chat lines. The submit callbacks are the same ones
 * the dialog class took; what changed is that they receive a player {@code UUID}
 * and a {@link com.gijsm.vibemod.platform.ui.UiResponse} instead of a
 * {@code Player} and a {@code DialogResponseView}.
 *
 * <p>The three confirmations carry no "run it" callback on purpose, exactly as
 * in v1: their confirm buttons run {@code /vibe fix <mod> confirm} /
 * {@code /vibe rollback <mod> <version> confirm} /
 * {@code /vibe delete <mod> confirm} as real commands, which {@code VibeCommand}
 * treats as "already confirmed, do it now" rather than reopening the same
 * screen. That keeps the destructive paths permission-checked at the command
 * layer and re-entrant from chat, console and dialog alike.
 *
 * <p>An instance rather than a bag of statics because two of these screens have
 * to talk back: the config screen reopens itself with a banner when a value is
 * rejected, and every form rejects empty input with a message. Both need the
 * live {@link UiRenderer} and {@link Messenger} the host built at boot, and
 * carrying them as fields beats threading them through seven signatures.
 */
public final class FormScreens {

    private static final int PROMPT_MAX_LENGTH = 2000;
    private static final int KNOB_TEXT_MAX_LENGTH = 256;
    private static final int NAME_HINT_MAX_LENGTH = 32;
    private static final int CUSTOM_MODEL_MAX_LENGTH = 80;
    private static final double DEFAULT_MIN = 0.0;
    private static final double DEFAULT_MAX = 100.0;
    private static final double DEFAULT_STEP = 1.0;

    private final Messenger messenger;
    private final UiRenderer renderer;

    public FormScreens(Messenger messenger, UiRenderer renderer) {
        this.messenger = messenger;
        this.renderer = renderer;
    }

    /** A rejected-input message to the player: the same red line every other refusal uses. */
    private void reject(UUID player, String message) {
        messenger.player(player).sendMessage(Style.err(message));
    }

    /** Submits a free-text mod request. */
    @FunctionalInterface
    public interface PromptSubmit {
        void submit(UUID player, String prompt);
    }

    /** Submits a free-text change request against an existing mod. */
    @FunctionalInterface
    public interface EditSubmit {
        void submit(UUID player, String modName, String changeRequest);
    }

    /** Applies parsed config values; returns one human-readable error string per rejected key. */
    @FunctionalInterface
    public interface ConfigSubmit {
        List<String> apply(UUID player, String modName, Map<String, String> values);
    }

    /** One config knob as shown to the player: its schema plus the value to prefill. */
    public record Knob(String key, String type, String description, String value,
                       Double min, Double max, Double step, List<String> choices) {
    }

    // ---- 1. make ----

    /**
     * "Make a mod": a multiline prompt plus an optional name hint. The name hint
     * is folded into the prompt text rather than passed separately, because the
     * model treats it as one more instruction and the generator has no name
     * parameter (its {@code make} signature is the frozen v3 surface).
     */
    public Screen makePrompt(PromptSubmit onPrompt) {
        Input prompt = new Input.Text("prompt", Component.text("What should the mod do?"),
                "", PROMPT_MAX_LENGTH, true);
        Input name = new Input.Text("name", Component.text("Name hint (optional)"),
                "", NAME_HINT_MAX_LENGTH, false);

        Button create = new Button(Component.text("✨ Create", Style.OK),
                Component.text("Generate the mod with the model", Style.INFO),
                WidthHint.BODY,
                new UiAction.Submit((response, player) -> {
                    String text = response.text("prompt");
                    if (text == null || text.isBlank()) {
                        reject(player, "Nothing to make - the prompt was empty.");
                        return;
                    }
                    String hint = response.text("name");
                    String finalText = (hint != null && !hint.isBlank())
                            ? "Name hint: " + hint.trim() + "\n" + text : text;
                    onPrompt.submit(player, finalText);
                }));

        return new Screen(ScreenKit.title("Make a mod"), Screen.Kind.FORM,
                List.of(ScreenKit.icon("CRAFTING_TABLE", false,
                        Component.text("Describe what the mod should do. Be specific.", Style.INFO))),
                List.of(prompt, name),
                List.of(create),
                ScreenKit.cancel("Close without creating anything"),
                1);
    }

    // ---- 2. edit ----

    /** The edit-request screen for {@code mod}, showing {@code manualSummary} as context. */
    public Screen edit(String mod, String manualSummary, EditSubmit onEdit) {
        String summary = (manualSummary == null || manualSummary.isBlank())
                ? "(no manual available)" : manualSummary;
        Input change = new Input.Text("change", Component.text("What should change?"),
                "", PROMPT_MAX_LENGTH, true);

        Button update = new Button(Component.text("Update", Style.OK),
                Component.text("Send the change request to the model", Style.INFO),
                WidthHint.BODY,
                new UiAction.Submit((response, player) -> {
                    String text = response.text("change");
                    if (text == null || text.isBlank()) {
                        reject(player, "Nothing to change - the request was empty.");
                        return;
                    }
                    onEdit.submit(player, mod, text);
                }));

        return new Screen(ScreenKit.title("Edit " + mod), Screen.Kind.FORM,
                List.of(ScreenKit.text(Component.text(summary, Style.INFO))),
                List.of(change),
                List.of(update),
                ScreenKit.cancel("Close without changing anything"),
                1);
    }

    // ---- 3. config ----

    /** The config screen for {@code mod}: one input per knob, Save/Cancel. */
    public Screen config(String mod, List<Knob> knobs, ConfigSubmit onConfig) {
        return config(mod, knobs, null, onConfig);
    }

    /**
     * The config screen, optionally reopened after a validation failure:
     * {@code errorMessage} shows as a red banner and {@code knobs} already
     * carries the player's just-submitted (rejected) values as initials, so
     * nothing typed is lost.
     */
    private Screen config(String mod, List<Knob> knobs, String errorMessage, ConfigSubmit onConfig) {
        List<BodyBlock> body = new ArrayList<>();
        if (errorMessage != null && !errorMessage.isBlank()) {
            body.add(ScreenKit.text(Component.text(errorMessage, Style.ERROR)));
        }
        // Inputs get short key-only labels (long text clips inside sliders/fields);
        // the descriptions live up here as one readable block instead.
        for (Knob knob : knobs) {
            String desc = knob.description();
            if (desc != null && !desc.isBlank()) {
                body.add(ScreenKit.text(Component.text(knob.key(), Style.ACTION)
                        .append(Component.text(" — " + desc, Style.INFO))));
            }
        }

        List<Input> inputs = new ArrayList<>();
        for (Knob knob : knobs) {
            inputs.add(knobInput(knob));
        }

        Button save = new Button(Component.text("Save", Style.OK),
                Component.text("Apply and persist these values", Style.INFO),
                WidthHint.BODY,
                new UiAction.Submit((response, player) -> {
                    Map<String, String> submitted = new LinkedHashMap<>();
                    for (Knob knob : knobs) {
                        submitted.put(knob.key(), readValue(knob, response));
                    }
                    List<String> errors = onConfig.apply(player, mod, submitted);
                    if (errors == null || errors.isEmpty()) {
                        messenger.player(player).sendMessage(Style.ok(mod + " configuration saved."));
                        return;
                    }
                    StringBuilder msg = new StringBuilder("Some values were rejected:");
                    for (String e : errors) {
                        msg.append('\n').append(e);
                    }
                    renderer.show(player, config(mod, withValues(knobs, submitted), msg.toString(), onConfig));
                }));

        return new Screen(ScreenKit.title("Configure " + mod), Screen.Kind.FORM,
                List.copyOf(body), List.copyOf(inputs), List.of(save), ScreenKit.cancel(), 1);
    }

    private static Input knobInput(Knob knob) {
        String type = knob.type() == null ? "text" : knob.type().toLowerCase(Locale.ROOT);
        Component label = Component.text(knob.key());
        String current = knob.value();
        switch (type) {
            case "boolean":
                return new Input.Bool(knob.key(), label,
                        current != null && Boolean.parseBoolean(current.trim()));
            case "choice": {
                List<String> choices = knob.choices() == null ? List.of() : knob.choices();
                List<Input.Choice.Option> options = new ArrayList<>();
                for (String choice : choices) {
                    options.add(new Input.Choice.Option(choice, Component.text(choice),
                            choice.equalsIgnoreCase(current)));
                }
                return new Input.Choice(knob.key(), label, options);
            }
            case "integer":
            case "decimal": {
                double min = knob.min() != null ? knob.min() : DEFAULT_MIN;
                double max = knob.max() != null ? knob.max() : DEFAULT_MAX;
                double step = knob.step() != null ? knob.step() : DEFAULT_STEP;
                return new Input.Number(knob.key(), label, min, max, step,
                        parseDouble(current, min), "%s: %s");
            }
            case "text":
            default:
                return new Input.Text(knob.key(), label, current == null ? "" : current,
                        KNOB_TEXT_MAX_LENGTH, false);
        }
    }

    /** Reads one knob's submitted value back, converted to its canonical string form. */
    private static String readValue(Knob knob, com.gijsm.vibemod.platform.ui.UiResponse response) {
        String type = knob.type() == null ? "text" : knob.type().toLowerCase(Locale.ROOT);
        switch (type) {
            case "boolean": {
                Boolean b = response.bool(knob.key());
                return Boolean.toString(b != null && b);
            }
            case "integer":
            case "decimal": {
                Double d = response.number(knob.key());
                double raw = d == null ? 0.0 : d;
                double step = knob.step() != null ? knob.step() : DEFAULT_STEP;
                double rounded = roundToStep(raw, step);
                return "integer".equals(type) ? Long.toString(Math.round(rounded)) : Double.toString(rounded);
            }
            case "choice": {
                String picked = response.text(knob.key());
                return picked == null ? "" : picked;
            }
            case "text":
            default: {
                String text = response.text(knob.key());
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

    // ---- 4. model picker ----

    /**
     * The model picker: a choice of {@code models} (id + live price, cheapest-first
     * per {@link ModelCatalog#featured}) plus a "custom model id" text field that
     * wins over the choice when non-blank, so a player is never limited to the
     * curated list.
     */
    public Screen modelPicker(List<ModelCatalog.ModelInfo> models, String currentId,
                                     double sessionCostUsd, Consumer<String> onPick) {
        List<ModelCatalog.ModelInfo> options = (models == null || models.isEmpty())
                ? List.of(new ModelCatalog.ModelInfo(currentId, 0, -1.0, -1.0, false))
                : models;

        List<Input.Choice.Option> entries = new ArrayList<>();
        for (ModelCatalog.ModelInfo m : options) {
            entries.add(new Input.Choice.Option(m.id(),
                    Component.text(m.id() + " — " + m.priceLabel()), m.id().equals(currentId)));
        }
        Input dropdown = new Input.Choice("model", Component.text("Model"), entries);
        Input custom = new Input.Text("custom", Component.text("Custom model id (optional)"),
                "", CUSTOM_MODEL_MAX_LENGTH, false);

        String currentPrice = options.stream().filter(m -> m.id().equals(currentId)).findFirst()
                .map(ModelCatalog.ModelInfo::priceLabel).orElse("price unknown");

        Button use = new Button(Component.text("Use", Style.OK),
                Component.text("Switch generation to the selected model", Style.INFO),
                WidthHint.BODY,
                new UiAction.Submit((response, player) -> {
                    String typed = response.text("custom");
                    if (typed != null && !typed.isBlank()) {
                        onPick.accept(typed.trim());
                        return;
                    }
                    String picked = response.text("model");
                    if (picked == null || picked.isBlank()) {
                        reject(player, "No model selected.");
                        return;
                    }
                    onPick.accept(picked);
                }));

        return new Screen(ScreenKit.title("Choose a model"), Screen.Kind.FORM,
                List.of(ScreenKit.text(Component.text(
                                "Current: " + currentId + " (" + currentPrice + ")", Style.INFO)),
                        ScreenKit.text(Component.text("Spent this session: ", Style.INFO)
                                .append(Component.text(Style.fmtCost(sessionCostUsd), NamedTextColor.WHITE)))),
                List.of(dropdown, custom),
                List.of(use),
                ScreenKit.cancel("Keep the current model"),
                1);
    }

    // ---- 5. fix confirm ----

    /**
     * Confirms sending {@code mod}'s recent errors to the model for a repair round.
     * {@code icon} is the mod's stored icon name (blank-safe), shown so the player
     * instantly recognizes what they are acting on.
     */
    public Screen fixConfirm(String mod, String icon, String lastError) {
        String summary = (lastError == null || lastError.isBlank()) ? "(no error recorded)" : lastError;
        Button fix = new Button(Component.text("🔧 Fix it", Style.WARN),
                Component.text("Send the recent errors to the model for a repair round", Style.INFO),
                WidthHint.BODY,
                new UiAction.RunCommand("/vibe fix " + mod + " confirm"));

        return new Screen(ScreenKit.title("Fix " + mod + "?"), Screen.Kind.FORM,
                List.of(ScreenKit.icon(icon, false,
                                Component.text(mod, NamedTextColor.WHITE)
                                        .append(Component.text(" is degraded", Style.WARN))),
                        ScreenKit.text(Component.text(
                                "Send the recent errors to the model to attempt a fix?", Style.INFO)),
                        ScreenKit.text(Component.text(summary, Style.META))),
                List.of(),
                List.of(fix),
                ScreenKit.cancel("Leave it as it is for now"),
                1);
    }

    // ---- 6. rollback confirm ----

    /** Confirms activating an older stored version of {@code mod}. */
    public Screen rollbackConfirm(String mod, String icon, int version, String changelog) {
        String summary = (changelog == null || changelog.isBlank()) ? "(no changelog)" : changelog;
        Button activate = new Button(Component.text("⚡ Activate v" + version, Style.OK),
                Component.text("Recompile and hot-load v" + version, Style.INFO),
                WidthHint.BODY,
                new UiAction.RunCommand("/vibe rollback " + mod + " " + version + " confirm"));

        return new Screen(ScreenKit.title("Activate " + mod + " v" + version + "?"), Screen.Kind.FORM,
                List.of(ScreenKit.icon(icon, false,
                                Component.text(mod + " v" + version, NamedTextColor.WHITE)),
                        ScreenKit.text(Component.text("Recompile and hot-load this stored version?", Style.INFO)),
                        ScreenKit.text(Component.text(summary, Style.META))),
                List.of(),
                List.of(activate),
                ScreenKit.cancel("Keep the active version"),
                1);
    }

    // ---- 7. delete confirm ----

    /** Confirms permanently deleting {@code mod} and all its stored versions. */
    public Screen deleteConfirm(String mod, String icon, int versionCount) {
        Button delete = new Button(Component.text("✖ Delete forever", Style.ERROR),
                Component.text("Permanently delete this mod", Style.INFO),
                WidthHint.BODY,
                new UiAction.RunCommand("/vibe delete " + mod + " confirm"));

        return new Screen(ScreenKit.title("Delete " + mod + "?"), Screen.Kind.FORM,
                List.of(ScreenKit.icon(icon, false, Component.text(mod, NamedTextColor.WHITE)),
                        ScreenKit.text(Component.text(
                                "This permanently deletes " + mod + " and its " + versionCount
                                        + " stored version" + (versionCount == 1 ? "" : "s")
                                        + ". This cannot be undone.", Style.ERROR))),
                List.of(),
                List.of(delete),
                ScreenKit.cancel("Keep the mod"),
                1);
    }
}
