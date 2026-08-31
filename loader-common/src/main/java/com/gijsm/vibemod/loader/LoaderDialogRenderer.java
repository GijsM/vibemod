package com.gijsm.vibemod.loader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.InputControl;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;

import com.gijsm.vibemod.platform.TickScheduler;
import com.gijsm.vibemod.platform.ui.BodyBlock;
import com.gijsm.vibemod.platform.ui.Button;
import com.gijsm.vibemod.platform.ui.Screen;
import com.gijsm.vibemod.platform.ui.UiAction;
import com.gijsm.vibemod.platform.ui.UiCallback;
import com.gijsm.vibemod.platform.ui.UiRenderer;
import com.gijsm.vibemod.platform.ui.UiResponse;
import com.gijsm.vibemod.platform.ui.WidthHint;
import com.gijsm.vibemod.ui.Style;

/**
 * The native-dialog renderer for the loaders: any {@code Screen} into a vanilla
 * {@code net.minecraft.server.dialog.Dialog}, shown with
 * {@code ServerPlayer#openDialog} (ARCHITECTURE-V2 §3.1, §8.5).
 *
 * <p>The same mapping as {@code PaperDialogRenderer}, against the API Paper's
 * dialog builders wrap: {@code FORM → ConfirmationDialog},
 * {@code MENU → MultiActionDialog}, {@code NOTICE → NoticeDialog}; inputs map
 * 1:1 onto {@code TextInput}/{@code BooleanInput}/{@code SingleOptionInput}/
 * {@code NumberRangeInput}; {@code WidthHint} onto the same four pixel widths.
 * Screens are data, so both renderers on both platforms consume identical input
 * and the only thing that differs is the vocabulary they emit.
 *
 * <p><b>No registry entry.</b> Dialogs are normally datapack content. A hot-load
 * plugin cannot ship datapack content, and reloading the registry to show a menu
 * would be absurd — so the dialog is passed inline as {@code Holder.direct(...)},
 * which {@code Dialog.STREAM_CODEC} encodes in place. Nothing is registered and
 * nothing needs a resource pack.
 *
 * <p><b>Responses.</b> Vanilla has no callback channel: a button's
 * {@code CustomAll} action makes the client send a {@code custom_click_action}
 * packet carrying an Identifier plus every input's value as NBT. Each
 * Submit/Callback button therefore gets a one-shot token from
 * {@link DialogClicks}, and one mixin routes the packet back. Values arrive as
 * NBT keyed by the (sanitized) input key, which {@link Bindings} reverse-maps
 * before any callback sees them.
 *
 * <p><b>Renderer obligations</b> (normative, §3): callbacks run on the main
 * thread; a callback exception is caught, logged and reported to the player via
 * {@link Style#err} rather than propagating into packet handling; callbacks are
 * one-shot ({@link DialogClicks} removes the token on use); submitted values are
 * re-validated by the callback, not here — this class guarantees type shape
 * only.
 */
public final class LoaderDialogRenderer implements UiRenderer {

    private static final Logger LOG = Logger.getLogger(LoaderDialogRenderer.class.getName());

    // The v1 pixel scale, lifted from DialogKit so both renderers agree.
    private static final int BODY_WIDTH = 320;
    private static final int WIDE_WIDTH = 400;
    private static final int INPUT_WIDTH = 300;
    private static final int ROW_WIDTH = 300;
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

    private final MinecraftServer server;
    private final TickScheduler scheduler;
    private final LoaderMessenger messenger;

    public LoaderDialogRenderer(MinecraftServer server, TickScheduler scheduler, LoaderMessenger messenger) {
        this.server = server;
        this.scheduler = scheduler;
        this.messenger = messenger;
    }

    /**
     * Builds {@code screen} and shows it to {@code player} next tick. An offline
     * or unknown player is silently skipped — screens are frequently shown from
     * the tail of an async generation, by which time the player may be gone.
     */
    @Override
    public void show(UUID player, Screen screen) {
        scheduler.runOnMain(() -> {
            ServerPlayer target = server.getPlayerList().getPlayer(player);
            if (target == null) {
                return;
            }
            try {
                target.openDialog(Holder.direct(build(player, screen)));
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Could not show a dialog to " + target.getName().getString(), t);
            }
        });
    }

    // ---- assembly ----

    private Dialog build(UUID viewer, Screen screen) {
        Bindings bindings = Bindings.of(screen.inputs());
        CommonDialogData common = new CommonDialogData(
                vanilla(screen.title()),
                Optional.empty(),
                true,
                // pause=false deliberately: a server-side dialog that paused a
                // singleplayer world would freeze the very server generating the
                // mod behind it. The codec also rejects pause=true with an
                // after-action that never unpauses, so false is the safe default.
                false,
                DialogAction.CLOSE,
                body(screen.body()),
                inputs(screen.inputs(), bindings));

        List<ActionButton> buttons = buttons(viewer, screen.buttons(), bindings);
        ActionButton exit = screen.exit() == null ? null : button(viewer, screen.exit(), bindings);

        return switch (screen.kind()) {
            case FORM -> {
                ActionButton cancel = exit != null ? exit : plain("Cancel");
                if (buttons.size() == 1) {
                    yield new ConfirmationDialog(common, buttons.get(0), cancel);
                }
                // Defensive: the 17 committed builders never produce these, but
                // dropping a button (and with it, possibly the only way to act on
                // the screen) is a far worse failure than an odd-looking dialog.
                yield buttons.isEmpty()
                        ? new NoticeDialog(common, cancel)
                        : new MultiActionDialog(common, buttons, Optional.of(cancel),
                                Math.max(1, screen.columns()));
            }
            // A button-less MENU is the empty mod browser: a body and a way out.
            case MENU -> buttons.isEmpty()
                    ? new NoticeDialog(common, exit != null ? exit : plain("Done"))
                    : new MultiActionDialog(common, buttons,
                            Optional.of(exit != null ? exit : plain("Done")),
                            Math.max(1, screen.columns()));
            case NOTICE -> new NoticeDialog(common, exit != null ? exit : plain("Done"));
        };
    }

    // ---- body ----

    private List<DialogBody> body(List<BodyBlock> blocks) {
        List<DialogBody> out = new ArrayList<>();
        if (blocks == null) {
            return out;
        }
        for (BodyBlock block : blocks) {
            switch (block) {
                case BodyBlock.Text text ->
                        out.add(new PlainMessage(vanilla(text.text()), px(text.width())));
                case BodyBlock.Icon icon -> {
                    // The decorative item plus its caption. An unknown or
                    // unresolvable item id degrades to the caption alone rather
                    // than to an exception: an icon is never the point.
                    var stack = LoaderItems.template(icon.iconId(), icon.glint());
                    if (stack == null) {
                        out.add(new PlainMessage(vanilla(icon.beside()), BODY_WIDTH));
                    } else {
                        out.add(new ItemBody(stack,
                                Optional.of(new PlainMessage(vanilla(icon.beside()), BODY_WIDTH)),
                                // No decorations, no tooltip: it is a picture, not an item.
                                false, false, 16, 16));
                    }
                }
            }
        }
        return out;
    }

    /** {@code WidthHint} → the v1 pixel scale. A null hint reads as prose. */
    private static int px(WidthHint hint) {
        if (hint == null) {
            return BODY_WIDTH;
        }
        return switch (hint) {
            case BODY -> BODY_WIDTH;
            case WIDE -> WIDE_WIDTH;
            case INPUT -> INPUT_WIDTH;
            case ROW -> ROW_WIDTH;
        };
    }

    // ---- inputs ----

    private List<Input> inputs(List<com.gijsm.vibemod.platform.ui.Input> inputs, Bindings bindings) {
        List<Input> out = new ArrayList<>();
        if (inputs == null) {
            return out;
        }
        for (com.gijsm.vibemod.platform.ui.Input input : inputs) {
            out.add(new Input(bindings.dialogKey(input.key()), control(input)));
        }
        return out;
    }

    private InputControl control(com.gijsm.vibemod.platform.ui.Input input) {
        return switch (input) {
            case com.gijsm.vibemod.platform.ui.Input.Text text -> new TextInput(
                    INPUT_WIDTH,
                    vanilla(text.label()),
                    // v1's single-line fields (name hint, custom model id) show their label.
                    !text.multiline(),
                    text.initial() == null ? "" : text.initial(),
                    text.maxLength() > 0 ? text.maxLength() : 32,
                    text.multiline()
                            ? Optional.of(new TextInput.MultilineOptions(
                                    Optional.empty(), Optional.of(MULTILINE_HEIGHT)))
                            : Optional.empty());
            case com.gijsm.vibemod.platform.ui.Input.Bool bool -> new BooleanInput(
                    vanilla(bool.label()), bool.initial(), "true", "false");
            case com.gijsm.vibemod.platform.ui.Input.Choice choice -> {
                List<SingleOptionInput.Entry> entries = new ArrayList<>();
                for (var option : choice.options()) {
                    entries.add(new SingleOptionInput.Entry(
                            Bindings.sanitize(option.id()),
                            Optional.of(vanilla(option.label())),
                            option.selected()));
                }
                yield new SingleOptionInput(INPUT_WIDTH, entries, vanilla(choice.label()), true);
            }
            case com.gijsm.vibemod.platform.ui.Input.Number number -> {
                float min = (float) number.min();
                float max = (float) number.max();
                yield new NumberRangeInput(
                        INPUT_WIDTH,
                        vanilla(number.label()),
                        number.labelFormat() == null || number.labelFormat().isBlank()
                                ? "options.generic_value" : number.labelFormat(),
                        new NumberRangeInput.RangeInfo(min, max,
                                // Clamped on the way in as well as on the way out: an
                                // out-of-range initial makes the client render a slider
                                // it cannot represent.
                                Optional.of((float) Math.max(min, Math.min(max, number.initial()))),
                                number.step() > 0 ? Optional.of((float) number.step()) : Optional.empty()));
            }
        };
    }

    // ---- buttons ----

    private List<ActionButton> buttons(UUID viewer, List<Button> buttons, Bindings bindings) {
        List<ActionButton> out = new ArrayList<>();
        if (buttons == null) {
            return out;
        }
        for (Button button : buttons) {
            out.add(button(viewer, button, bindings));
        }
        return out;
    }

    /**
     * One button.
     *
     * <p>An explicit width is set only for {@code ROW}: in v1 exactly the list
     * rows (browser mods, source files, history versions) pinned a width so
     * their labels align, and everything else took its natural size. See
     * {@code PaperDialogRenderer#button} — the contract for screen builders is
     * identical, and deliberately so.
     */
    private ActionButton button(UUID viewer, Button button, Bindings bindings) {
        CommonButtonData data = new CommonButtonData(
                vanilla(button.label()),
                button.tooltip() == null ? Optional.empty() : Optional.of(vanilla(button.tooltip())),
                button.width() == WidthHint.ROW ? ROW_WIDTH : CommonButtonData.DEFAULT_WIDTH);
        return new ActionButton(data, Optional.of(action(viewer, button.action(), bindings)));
    }

    /**
     * {@code RunCommand} is a plain client-side run-command click — stateless
     * navigation, so the command re-checks permissions and re-fetches data.
     * {@code Submit} and {@code Callback} become one-shot custom clicks; they
     * differ only in whether the callback is handed the screen's values or an
     * empty response.
     */
    private Action action(UUID viewer, UiAction action, Bindings bindings) {
        return switch (action) {
            case UiAction.RunCommand run -> new StaticAction(new ClickEvent.RunCommand(run.command()));
            case UiAction.Submit submit -> new CustomAll(
                    DialogClicks.mint(viewer, (values, player) ->
                            invoke(submit.callback(), bindings.read(values), player)),
                    Optional.empty());
            case UiAction.Callback callback -> new CustomAll(
                    DialogClicks.mint(viewer, (values, player) ->
                            invoke(callback.callback(), NO_VALUES, player)),
                    Optional.empty());
        };
    }

    /**
     * Runs a screen callback on the main thread, guarded. §3's renderer
     * obligation: a callback exception never propagates, and the player is told
     * something went wrong rather than left staring at a dialog that did nothing.
     */
    private void invoke(UiCallback callback, UiResponse response, UUID player) {
        scheduler.runOnMain(() -> {
            try {
                callback.handle(response, player);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "A screen callback threw", t);
                messenger.player(player).sendMessage(Style.err("Something went wrong handling that."));
            }
        });
    }

    private net.minecraft.network.chat.Component vanilla(net.kyori.adventure.text.Component adventure) {
        return LoaderText.toVanilla(adventure, server);
    }

    /** A plain-label button with no action — the implicit Cancel/Done. */
    private ActionButton plain(String label) {
        return new ActionButton(
                new CommonButtonData(net.minecraft.network.chat.Component.literal(label),
                        Optional.empty(), CommonButtonData.DEFAULT_WIDTH),
                Optional.empty());
    }

    // ---- key sanitization + response reverse-mapping ----

    /**
     * The per-screen translation table between the screen model's free-form keys
     * and the {@code [a-z0-9_]} keys vanilla dialogs accept, in both directions.
     * Identical in intent and behaviour to {@code PaperDialogRenderer.Bindings};
     * what differs is only that the response arrives as an NBT
     * {@link CompoundTag} rather than a {@code DialogResponseView}.
     *
     * <p>Built once per shown screen and captured by that screen's click tokens,
     * so nothing here is shared between screens or players.
     */
    private static final class Bindings {

        private final Map<String, com.gijsm.vibemod.platform.ui.Input> byKey;
        private final Map<String, String> dialogKeys;
        /** input key → (sanitized option id → original option id). */
        private final Map<String, Map<String, String>> optionIds;

        private Bindings(Map<String, com.gijsm.vibemod.platform.ui.Input> byKey,
                         Map<String, String> dialogKeys,
                         Map<String, Map<String, String>> optionIds) {
            this.byKey = byKey;
            this.dialogKeys = dialogKeys;
            this.optionIds = optionIds;
        }

        static Bindings of(List<com.gijsm.vibemod.platform.ui.Input> inputs) {
            Map<String, com.gijsm.vibemod.platform.ui.Input> byKey = new LinkedHashMap<>();
            Map<String, String> dialogKeys = new LinkedHashMap<>();
            Map<String, Map<String, String>> optionIds = new HashMap<>();
            if (inputs == null) {
                return new Bindings(byKey, dialogKeys, optionIds);
            }
            Set<String> usedKeys = new HashSet<>();
            for (com.gijsm.vibemod.platform.ui.Input input : inputs) {
                String original = input.key();
                if (original == null || dialogKeys.containsKey(original)) {
                    continue; // a duplicate key would overwrite its own binding; first one wins
                }
                byKey.put(original, input);
                dialogKeys.put(original, unique(sanitize(original), usedKeys));
                if (input instanceof com.gijsm.vibemod.platform.ui.Input.Choice choice) {
                    Map<String, String> ids = new LinkedHashMap<>();
                    for (var option : choice.options()) {
                        ids.put(sanitize(option.id()), option.id());
                    }
                    optionIds.put(original, ids);
                }
            }
            return new Bindings(byKey, dialogKeys, optionIds);
        }

        String dialogKey(String originalKey) {
            String mapped = dialogKeys.get(originalKey);
            return mapped != null ? mapped : sanitize(originalKey);
        }

        /**
         * The submitted values, keyed by original key.
         *
         * <p>Every value arrives as a {@code StringTag} — that is what
         * {@code Action.ValueGetter.of(String)} produces, and it is the only
         * getter vanilla's controls use — but {@link Tag}'s accessors are
         * tolerant, so this reads correctly even if a control someday reports a
         * numeric tag. Accessors return null unless the screen actually declared
         * an input of that name and that shape: the type-shape guarantee,
         * nothing more. Ranges and choice membership are the callback's to
         * re-check, since a client can report whatever it likes.
         */
        UiResponse read(CompoundTag values) {
            return new UiResponse() {
                @Override
                public String text(String key) {
                    com.gijsm.vibemod.platform.ui.Input input = byKey.get(key);
                    if (input instanceof com.gijsm.vibemod.platform.ui.Input.Text) {
                        return string(key);
                    }
                    if (input instanceof com.gijsm.vibemod.platform.ui.Input.Choice) {
                        String picked = string(key);
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
                    if (!(byKey.get(key) instanceof com.gijsm.vibemod.platform.ui.Input.Bool)) {
                        return null;
                    }
                    Tag tag = values.get(dialogKey(key));
                    if (tag == null) {
                        return null;
                    }
                    // The control reports the BooleanInput's onTrue/onFalse strings.
                    return tag.asBoolean().orElseGet(
                            () -> "true".equalsIgnoreCase(tag.asString().orElse("")));
                }

                @Override
                public Double number(String key) {
                    if (!(byKey.get(key) instanceof com.gijsm.vibemod.platform.ui.Input.Number)) {
                        return null;
                    }
                    Tag tag = values.get(dialogKey(key));
                    if (tag == null) {
                        return null;
                    }
                    return tag.asDouble().orElseGet(() -> {
                        try {
                            return Double.parseDouble(tag.asString().orElse(""));
                        } catch (NumberFormatException notANumber) {
                            return null;
                        }
                    });
                }

                private String string(String key) {
                    Tag tag = values.get(dialogKey(key));
                    return tag == null ? null : tag.asString().orElse(null);
                }
            };
        }

        /**
         * Vanilla dialog input keys and option ids allow only lowercase letters,
         * digits and underscores — {@code "chicken-count"} must become
         * {@code "chicken_count"} (lifted verbatim from v1's
         * {@code Dialogs.inputKey}). An id that sanitizes away to nothing gets a
         * placeholder so it stays a legal key.
         */
        static String sanitize(String raw) {
            // V3 Phase 2 §A moved the rule itself into core so the datapack
            // namespace and the dialog input key cannot drift apart. The
            // behaviour here is unchanged, fallback included.
            return com.gijsm.vibemod.util.Ids.sanitize(raw, "input");
        }

        /**
         * {@code candidate}, suffixed until it is unused. Two keys can sanitize
         * to the same string ({@code "max-hp"} and {@code "max.hp"}); merging
         * them would silently feed one input's value to two callbacks' worth of
         * settings.
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
