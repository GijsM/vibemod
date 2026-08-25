package com.gijsm.vibemod.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.NumberRangeDialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickEvent;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemod.platform.PlatformInfo;
import com.gijsm.vibemod.platform.ui.BodyBlock;
import com.gijsm.vibemod.platform.ui.Button;
import com.gijsm.vibemod.platform.ui.Input;
import com.gijsm.vibemod.platform.ui.Screen;
import com.gijsm.vibemod.platform.ui.UiAction;
import com.gijsm.vibemod.platform.ui.UiCallback;
import com.gijsm.vibemod.platform.ui.UiRenderer;
import com.gijsm.vibemod.platform.ui.UiResponse;
import com.gijsm.vibemod.platform.ui.WidthHint;

/**
 * The one native-dialog renderer: turns any {@code Screen} into an
 * {@code io.papermc.paper.dialog.Dialog} and shows it (ARCHITECTURE-V2 §3.1).
 *
 * <p>This replaces the four hand-written dialog classes of v1 ({@code Dialogs},
 * {@code SettingsDialog}, {@code InfoDialogs}, {@code ModHubDialog}) — one
 * class per screen family, each rebuilding the same idioms. Screens are data
 * now: core's builders produce {@code Screen}s, this maps them mechanically,
 * and the chat renderer maps the very same data for platforms without dialogs.
 * The mapping below is deliberately a lift, not a redesign: every width
 * constant, tooltip color, the icon-body idiom, the next-tick show and the
 * one-shot main-thread click hop are the v1 behaviour, so the migration is
 * invisible to players.
 *
 * <p><b>Mapping</b> (see {@link #dialogType}, {@link #body}, {@link #inputs}):
 * {@code FORM → DialogType.confirmation}, {@code MENU → multiAction} (or
 * {@code notice} when it has no buttons — today's empty mod browser),
 * {@code NOTICE → notice}. Inputs map 1:1 onto
 * {@code text/bool/singleOption/numberRange}; {@code WidthHint} maps onto the
 * four {@link DialogKit} pixel widths; {@code UiAction.RunCommand} becomes a
 * static run-command click, {@code Submit}/{@code Callback} a one-shot custom
 * click that hops to the main thread.
 *
 * <p><b>Key sanitization.</b> Vanilla dialog input keys (and single-option
 * entry ids) allow only {@code [a-z0-9_]}, while screen builders use whatever
 * reads well ({@code "chicken-count"}). Keys are sanitized on the way in and
 * reverse-mapped on the way out, so a {@link UiCallback} only ever sees the
 * original {@code Input.key()} and the original {@code Option.id()} — the
 * builders never learn this happened. Collisions after sanitization are
 * disambiguated with a numeric suffix rather than silently merged.
 *
 * <p><b>Renderer obligations</b> (normative, ARCHITECTURE-V2 §3): callbacks run
 * on the main thread; a callback exception is caught, logged and reported to the
 * player via {@link Style#err} rather than propagating into Bukkit; callbacks
 * are one-shot ({@code ClickCallback.Options.uses(1)}); submitted values are
 * re-validated by the callback, not here — this class guarantees type shape
 * only, exactly what {@code UiResponse}'s javadoc promises.
 *
 * <p><b>Capability gating.</b> The one call newer than the Paper 1.20.6 floor
 * is {@code ItemMeta#setEnchantmentGlintOverride} (1.20.5+), gated on
 * {@code PlatformInfo.hasItemGlintOverride()} inside
 * {@link DialogKit#iconItem}. Nothing else here needs a gate: this renderer is
 * only ever installed when {@code PlatformInfo.hasDialogs()} is true, which on
 * Paper means 1.21.7+, far above the floor — hosts below it get the chat
 * renderer instead, so there is no need to contort the dialog calls.
 *
 * <p>The dialog API is {@code @Experimental} on this Paper version; those
 * warnings are suppressed file-wide (there is no {@code -Werror} anywhere in
 * this build, so they are purely informational) rather than annotated on every
 * method individually.
 */
@SuppressWarnings("UnstableApiUsage")
public final class PaperDialogRenderer implements UiRenderer {

    private static final Logger LOG = Logger.getLogger(PaperDialogRenderer.class.getName());

    /** Multiline text-field height in px — the v1 prompt/edit/change fields all used this. */
    private static final int MULTILINE_HEIGHT = 160;

    /** A response with nothing in it, for {@code UiAction.Callback} (no inputs are read). */
    private static final UiResponse NO_VALUES = new UiResponse() {
        @Override
        public String text(String key) {
            return null;
        }

        @Override
        public Boolean bool(String key) {
            return null;
        }

        @Override
        public Double number(String key) {
            return null;
        }
    };

    private final Plugin plugin;
    private final PlatformInfo platform;

    /**
     * @param plugin   the owning plugin, for the scheduler hops (show next tick, click to main thread)
     * @param platform the capability probes; only {@code hasItemGlintOverride()} is consulted here
     */
    public PaperDialogRenderer(Plugin plugin, PlatformInfo platform) {
        this.plugin = plugin;
        this.platform = platform;
    }

    /**
     * Builds {@code screen} and shows it to {@code player} next tick. An offline
     * or unknown player is silently skipped — screens are frequently shown from
     * the tail of an async generation, by which time the player may be gone.
     */
    @Override
    public void show(UUID player, Screen screen) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || !p.isOnline()) {
            return;
        }
        DialogKit.show(plugin, p, build(screen));
    }

    // ---- assembly ----

    /** One {@code Screen} → one {@code Dialog}: body, inputs, and the kind-specific type. */
    private Dialog build(Screen screen) {
        Bindings bindings = Bindings.of(screen.inputs());
        List<DialogBody> body = body(screen.body());
        List<DialogInput> inputs = inputs(screen.inputs(), bindings);
        DialogType type = dialogType(screen, bindings);
        return Dialog.create(b -> {
            DialogBase.Builder base = DialogBase.builder(screen.title())
                    // Every screen closes on click; nothing here is a wizard step (§3.1).
                    .afterAction(DialogBase.DialogAfterAction.CLOSE);
            if (!body.isEmpty()) {
                base.body(body);
            }
            if (!inputs.isEmpty()) {
                base.inputs(inputs);
            }
            b.empty().base(base.build()).type(type);
        });
    }

    /**
     * The kind mapping. {@code FORM}'s submit is its first button (a form has
     * exactly one primary action) and its negative is {@code exit}, or a bare
     * {@link DialogKit#cancelButton()}. The defensive branches — a {@code FORM}
     * with several buttons, or with none — cannot come out of the 17 committed
     * builders, but dropping a button (and with it, possibly the only way to
     * act on the screen) would be a far worse failure than a slightly
     * differently-shaped dialog, so they render as a {@code multiAction} /
     * {@code notice} instead.
     */
    private DialogType dialogType(Screen screen, Bindings bindings) {
        List<ActionButton> buttons = buttons(screen.buttons(), bindings);
        ActionButton exit = screen.exit() == null ? null : button(screen.exit(), bindings);
        return switch (screen.kind()) {
            case FORM -> {
                ActionButton cancel = exit != null ? exit : DialogKit.cancelButton();
                if (buttons.size() == 1) {
                    yield DialogType.confirmation(buttons.get(0), cancel);
                }
                yield buttons.isEmpty()
                        ? DialogType.notice(cancel)
                        : multiAction(buttons, cancel, screen.columns());
            }
            // A button-less MENU is the empty mod browser: a body and a way out.
            case MENU -> buttons.isEmpty()
                    ? DialogType.notice(exit != null ? exit : DialogKit.doneButton())
                    : multiAction(buttons, exit != null ? exit : DialogKit.doneButton(), screen.columns());
            case NOTICE -> DialogType.notice(exit != null ? exit : DialogKit.doneButton());
        };
    }

    /** {@code multiAction} with the exit and a sane column count (vanilla needs at least one). */
    private static DialogType multiAction(List<ActionButton> buttons, ActionButton exit, int columns) {
        return DialogType.multiAction(buttons)
                .exitAction(exit)
                .columns(Math.max(1, columns))
                .build();
    }

    // ---- body ----

    private List<DialogBody> body(List<BodyBlock> blocks) {
        List<DialogBody> out = new ArrayList<>();
        if (blocks == null) {
            return out;
        }
        for (BodyBlock block : blocks) {
            out.add(switch (block) {
                case BodyBlock.Text text -> DialogBody.plainMessage(text.text(), px(text.width()));
                // Decorative item + its caption, item tooltip suppressed; the glint marks a
                // running mod where the platform supports the override.
                case BodyBlock.Icon icon -> DialogKit.iconBody(
                        DialogKit.iconItem(icon.iconId(), icon.glint(), platform.hasItemGlintOverride()),
                        icon.beside());
            });
        }
        return out;
    }

    /** {@code WidthHint} → the v1 pixel scale. A null hint reads as prose. */
    private static int px(WidthHint hint) {
        if (hint == null) {
            return DialogKit.BODY;
        }
        return switch (hint) {
            case BODY -> DialogKit.BODY;
            case WIDE -> DialogKit.WIDE;
            case INPUT -> DialogKit.INPUT;
            case ROW -> DialogKit.ROW;
        };
    }

    // ---- inputs ----

    private static List<DialogInput> inputs(List<Input> inputs, Bindings bindings) {
        List<DialogInput> out = new ArrayList<>();
        if (inputs == null) {
            return out;
        }
        for (Input input : inputs) {
            out.add(dialogInput(input, bindings));
        }
        return out;
    }

    private static DialogInput dialogInput(Input input, Bindings bindings) {
        String key = bindings.dialogKey(input.key());
        return switch (input) {
            case Input.Text text -> {
                TextDialogInput.Builder b = DialogInput.text(key, text.label()).width(DialogKit.INPUT);
                if (text.maxLength() > 0) {
                    b.maxLength(text.maxLength());
                }
                if (text.multiline()) {
                    b.multiline(TextDialogInput.MultilineOptions.create(null, MULTILINE_HEIGHT));
                } else {
                    // v1's single-line fields (name hint, custom model id) show their label.
                    b.labelVisible(true);
                }
                if (text.initial() != null) {
                    b.initial(text.initial());
                }
                yield b.build();
            }
            case Input.Bool bool -> DialogInput.bool(key, bool.label())
                    .initial(bool.initial())
                    .build();
            case Input.Choice choice -> {
                List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                for (Input.Choice.Option option : choice.options()) {
                    entries.add(SingleOptionDialogInput.OptionEntry.create(
                            bindings.optionId(choice.key(), option.id()), option.label(), option.selected()));
                }
                yield DialogInput.singleOption(key, choice.label(), entries)
                        .width(DialogKit.INPUT)
                        .build();
            }
            case Input.Number number -> {
                float min = (float) number.min();
                float max = (float) number.max();
                NumberRangeDialogInput.Builder b = DialogInput.numberRange(key, number.label(), min, max)
                        .width(DialogKit.INPUT)
                        // Clamped on the way in as well as on the way out: an out-of-range
                        // initial makes the client render a slider it cannot represent.
                        .initial((float) clamp(number.initial(), min, max));
                if (number.labelFormat() != null && !number.labelFormat().isBlank()) {
                    b.labelFormat(number.labelFormat());
                }
                if (number.step() > 0) {
                    b.step((float) number.step());
                }
                yield b.build();
            }
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- buttons ----

    private List<ActionButton> buttons(List<Button> buttons, Bindings bindings) {
        List<ActionButton> out = new ArrayList<>();
        if (buttons == null) {
            return out;
        }
        for (Button button : buttons) {
            out.add(button(button, bindings));
        }
        return out;
    }

    /**
     * One button. The tooltip rides along when the screen supplies one (already
     * a styled Component — builders own the wording and the color). An explicit
     * width is set only for {@code ROW}: in v1 exactly the list rows (browser
     * mods, source files, history versions) pinned a width so their labels
     * align, and everything else took its natural size. {@code ROW} is
     * therefore the screen model's way of saying "this is a list row"; any
     * other hint means "size yourself".
     *
     * <p><b>Contract for screen builders</b>: only give a button
     * {@code WidthHint.ROW} when it really is a list row. A grid button (the
     * hub's 3-column action grid), a confirmation's submit or an exit must
     * carry a non-{@code ROW} hint, or it renders 300px wide where v1 sized it
     * to its label. {@code Button.command(...)} and core's {@code ScreenKit}
     * helpers default to {@code ROW}, so that is where the distinction has to
     * be made — the renderer has nothing else to go on.
     */
    private ActionButton button(Button button, Bindings bindings) {
        ActionButton.Builder b = ActionButton.builder(button.label());
        if (button.tooltip() != null) {
            b.tooltip(button.tooltip());
        }
        if (button.width() == WidthHint.ROW) {
            b.width(DialogKit.ROW);
        }
        return b.action(action(button.action(), bindings)).build();
    }

    /**
     * {@code RunCommand} is a plain client-side run-command click — stateless
     * navigation, so the command re-checks permissions and re-fetches data.
     * {@code Submit} and {@code Callback} are one-shot custom clicks that hop
     * to the main thread; they differ only in whether the callback is handed
     * the screen's values or an empty response.
     */
    private DialogAction action(UiAction action, Bindings bindings) {
        return switch (action) {
            case UiAction.RunCommand run -> DialogAction.staticAction(ClickEvent.runCommand(run.command()));
            case UiAction.Submit submit -> DialogKit.mainThreadClick(plugin, LOG,
                    (view, audience) -> invoke(submit.callback(), bindings.read(view), audience));
            case UiAction.Callback callback -> DialogKit.mainThreadClick(plugin, LOG,
                    (view, audience) -> invoke(callback.callback(), NO_VALUES, audience));
        };
    }

    /**
     * Runs a screen callback. Non-player audiences are ignored: every callback
     * takes the acting player's UUID, and a dialog cannot be shown to anything
     * else. Exceptions are caught one level up, by
     * {@link DialogKit#mainThreadClick}.
     */
    private static void invoke(UiCallback callback, UiResponse response, Audience audience) {
        if (audience instanceof Player player) {
            callback.handle(response, player.getUniqueId());
        }
    }

    // ---- key sanitization + response reverse-mapping ----

    /**
     * The per-screen translation table between the screen model's free-form
     * keys and the {@code [a-z0-9_]} keys vanilla dialogs accept, in both
     * directions: keys go through {@link #dialogKey} when the inputs are built,
     * and {@link #read} maps a {@code DialogResponseView} back to a
     * {@link UiResponse} keyed by the original {@code Input.key()} with the
     * original {@code Option.id()} as the value of a choice.
     *
     * <p>Built once per shown screen and captured by that screen's click
     * callbacks, so nothing here is shared between screens or players.
     */
    private static final class Bindings {

        private final Map<String, Input> byKey;
        private final Map<String, String> dialogKeys;
        /** input key → (sanitized option id → original option id). */
        private final Map<String, Map<String, String>> optionIds;

        private Bindings(Map<String, Input> byKey, Map<String, String> dialogKeys,
                         Map<String, Map<String, String>> optionIds) {
            this.byKey = byKey;
            this.dialogKeys = dialogKeys;
            this.optionIds = optionIds;
        }

        static Bindings of(List<Input> inputs) {
            Map<String, Input> byKey = new LinkedHashMap<>();
            Map<String, String> dialogKeys = new LinkedHashMap<>();
            Map<String, Map<String, String>> optionIds = new HashMap<>();
            if (inputs == null) {
                return new Bindings(byKey, dialogKeys, optionIds);
            }
            Set<String> usedKeys = new HashSet<>();
            for (Input input : inputs) {
                String original = input.key();
                if (original == null || dialogKeys.containsKey(original)) {
                    continue; // a duplicate key would overwrite its own binding; first one wins
                }
                byKey.put(original, input);
                dialogKeys.put(original, unique(sanitize(original), usedKeys));
                if (input instanceof Input.Choice choice) {
                    Set<String> usedIds = new HashSet<>();
                    Map<String, String> ids = new LinkedHashMap<>();
                    for (Input.Choice.Option option : choice.options()) {
                        ids.put(unique(sanitize(option.id()), usedIds), option.id());
                    }
                    optionIds.put(original, ids);
                }
            }
            return new Bindings(byKey, dialogKeys, optionIds);
        }

        /** The vanilla-legal key for {@code originalKey} (the key itself if the screen never declared it). */
        String dialogKey(String originalKey) {
            String mapped = dialogKeys.get(originalKey);
            return mapped != null ? mapped : sanitize(originalKey);
        }

        /** The vanilla-legal option id for one of {@code inputKey}'s options. */
        String optionId(String inputKey, String optionId) {
            Map<String, String> ids = optionIds.get(inputKey);
            if (ids != null) {
                for (Map.Entry<String, String> e : ids.entrySet()) {
                    if (e.getValue().equals(optionId)) {
                        return e.getKey();
                    }
                }
            }
            return sanitize(optionId);
        }

        /**
         * The submitted values, keyed by original key. Accessors return
         * {@code null} unless the screen actually declared an input of that
         * name and that shape — the type-shape guarantee, nothing more: ranges
         * and choice membership are the callback's to re-check, since a client
         * can report whatever it likes for a slider or a dropdown.
         */
        UiResponse read(DialogResponseView view) {
            return new UiResponse() {
                @Override
                public String text(String key) {
                    Input input = byKey.get(key);
                    if (input instanceof Input.Text) {
                        return view.getText(dialogKey(key));
                    }
                    if (input instanceof Input.Choice) {
                        String picked = view.getText(dialogKey(key));
                        if (picked == null) {
                            return null;
                        }
                        Map<String, String> ids = optionIds.get(key);
                        // Unknown ids pass through: the callback re-checks membership anyway.
                        return ids == null ? picked : ids.getOrDefault(picked, picked);
                    }
                    return null;
                }

                @Override
                public Boolean bool(String key) {
                    return byKey.get(key) instanceof Input.Bool ? view.getBoolean(dialogKey(key)) : null;
                }

                @Override
                public Double number(String key) {
                    if (!(byKey.get(key) instanceof Input.Number)) {
                        return null;
                    }
                    Float value = view.getFloat(dialogKey(key));
                    return value == null ? null : value.doubleValue();
                }
            };
        }

        /**
         * Vanilla dialog input keys and option ids allow only lowercase
         * letters, digits and underscores — {@code "chicken-count"} must
         * become {@code "chicken_count"} (lifted verbatim from v1's
         * {@code Dialogs.inputKey}). An id that sanitizes away to nothing gets
         * a placeholder so it stays a legal key.
         */
        private static String sanitize(String raw) {
            if (raw == null) {
                return "input";
            }
            String out = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            return out.isEmpty() ? "input" : out;
        }

        /**
         * {@code candidate}, suffixed until it is unused. Two keys can sanitize
         * to the same string ({@code "max-hp"} and {@code "max.hp"}); merging
         * them would silently feed one input's value to two callbacks' worth of
         * settings, so they get distinct keys and the reverse map stays honest.
         */
        private static String unique(String candidate, Set<String> used) {
            if (used.add(candidate)) {
                return candidate;
            }
            for (int i = 2; ; i++) {
                String next = candidate + "_" + i;
                if (used.add(next)) {
                    return next;
                }
            }
        }
    }
}
