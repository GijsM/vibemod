package com.gijsm.vibemod.ui.chat;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import com.gijsm.vibemod.platform.ChatBridge;
import com.gijsm.vibemod.platform.Messenger;
import com.gijsm.vibemod.platform.TickScheduler;
import com.gijsm.vibemod.platform.ui.BodyBlock;
import com.gijsm.vibemod.platform.ui.Button;
import com.gijsm.vibemod.platform.ui.Input;
import com.gijsm.vibemod.platform.ui.Screen;
import com.gijsm.vibemod.platform.ui.UiAction;
import com.gijsm.vibemod.platform.ui.UiCallback;
import com.gijsm.vibemod.platform.ui.UiRenderer;
import com.gijsm.vibemod.platform.ui.UiResponse;
import com.gijsm.vibemod.ui.Style;

/**
 * The universal screen renderer: every {@link Screen} rendered as a block of
 * clickable chat lines (ARCHITECTURE-V2 §3.2). It is the only renderer that
 * works everywhere — Paper below the dialog floor, dedicated loader servers,
 * consoles-with-a-player — so it lives in platform-free {@code core} and needs
 * nothing but a {@link Messenger}, a {@link ChatBridge} and a
 * {@link TickScheduler}.
 *
 * <p>The load-bearing design decision: <b>forms render as edit-in-place lists,
 * not sequential wizards.</b> A wizard would have to own the player's chat for
 * the whole form and could not show them what they had already set; instead
 * every input is one line with its current value and a {@code [✎ change]} /
 * {@code [toggle]} / option affordance, and any click reprints the whole block
 * with the new value. Chat is line-oriented and lossy — there is no editing a
 * message that has already been sent — so "re-render" always means "print the
 * block again", and the previous copy simply scrolls away.
 *
 * <p>Click plumbing goes through a hidden {@code /vibe ui <token>} subcommand
 * that the host routes into {@link #handleToken}. Adventure's
 * {@code ClickCallback} would do this on Paper, but only on Paper; one uniform
 * token path is what lets Fabric and NeoForge reuse this class unchanged.
 * Tokens are {@link SecureRandom}, single-use, bound to one player and to one
 * render of one screen, and expire after five minutes.
 *
 * <p>{@code Screen.columns} and every {@link com.gijsm.vibemod.platform.ui.WidthHint}
 * are ignored here (chat has one column of unbounded width), as is
 * {@code Input.Number.labelFormat} — that is the dialog slider's label
 * template, and chat shows the raw value instead.
 */
public final class ChatRenderer implements UiRenderer {

    private static final Logger LOG = Logger.getLogger(ChatRenderer.class.getName());

    /** Sessions and tokens both die after this long — the §3.2 TTL. */
    private static final long TTL_MILLIS = 5L * 60L * 1000L;

    /** 16 random bytes: url-safe-base64'd to 22 chars, far past guessing range. */
    private static final int TOKEN_BYTES = 16;

    /** The command the host must route into {@link #handleToken}. */
    private static final String TOKEN_COMMAND = "/vibe ui ";

    private static final String CANCEL_WORD = "cancel";
    private static final String DONE_WORD = "done";

    /** Half of the `── title ──` rule. */
    private static final String RULE = "────────";

    /** How much of a value we echo on its input line before eliding. */
    private static final int VALUE_PREVIEW_CHARS = 44;

    private final Messenger messenger;
    private final ChatBridge chat;
    private final TickScheduler scheduler;
    private final SecureRandom random = new SecureRandom();

    /** At most one open screen per player (§3.2); the map identity is also the token guard. */
    private final Map<UUID, ChatFormSession> sessions = new ConcurrentHashMap<>();

    /** Live click tokens. Concurrent because {@code handleToken} may arrive off-main. */
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    /**
     * @param messenger where rendered lines go (and the only way this class
     *                  reaches a player — no platform player type in core)
     * @param chat      the text-input mechanism: {@code [✎ change]} opens a capture
     * @param scheduler used solely to satisfy the §3 renderer obligation that
     *                  rendering and callbacks happen on the main thread
     */
    public ChatRenderer(Messenger messenger, ChatBridge chat, TickScheduler scheduler) {
        this.messenger = messenger;
        this.chat = chat;
        this.scheduler = scheduler;
    }

    /**
     * Prints {@code screen} to {@code player} as one chat block, discarding
     * whatever screen they had open (including its pending values and any
     * running capture) — §3.2 allows exactly one session per player.
     *
     * <p>Safe to call from any thread: the work is hopped to the main thread,
     * which is where {@link Messenger} is legal on every platform.
     */
    @Override
    public void show(UUID player, Screen screen) {
        if (player == null || screen == null) {
            return;
        }
        scheduler.runOnMain(() -> {
            prune();
            ChatFormSession session = new ChatFormSession(player, screen, System.currentTimeMillis() + TTL_MILLIS);
            discard(player);
            sessions.put(player, session);
            render(session);
        });
    }

    /**
     * The hidden {@code /vibe ui <token>} route. Returns false when the token is
     * unknown, expired, already used, or belongs to another player — the caller
     * then tells the player the menu expired.
     */
    public boolean handleToken(UUID player, String token) {
        if (player == null || token == null || token.isEmpty()) {
            return false;
        }
        prune();
        Token t = tokens.get(token);
        // Ownership is checked before consumption: otherwise a player who
        // guessed (or was shown) someone else's token could burn it.
        if (t == null || !t.player().equals(player)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (t.expired(now) || sessions.get(player) != t.session() || t.session().expired(now)) {
            tokens.remove(token, t);
            return false;
        }
        // Single-use, atomically: a double click loses the race and is a no-op.
        if (!tokens.remove(token, t)) {
            return false;
        }
        scheduler.runOnMain(() -> guarded(player, t.action()));
        return true;
    }

    /** Drops a player's pending form state and any active capture (call on quit). */
    public void forget(UUID player) {
        if (player == null) {
            return;
        }
        discard(player);
    }

    // ---- rendering ----

    /**
     * Prints the whole block: header, body, interactive input lines, buttons.
     * Every call mints a fresh generation of tokens and kills the previous one,
     * so the copy of the block that just scrolled away goes inert rather than
     * replaying a stale value.
     */
    private void render(ChatFormSession session) {
        Screen screen = session.screen();
        UUID player = session.player();
        tokens.values().removeIf(t -> t.player().equals(player));

        Audience out = messenger.player(player);
        out.sendMessage(header(screen));

        for (BodyBlock block : nullSafe(screen.body())) {
            Component line = switch (block) {
                // Text renders verbatim, newlines included: MarkdownMini already
                // emits one Component per block and chat honors embedded newlines,
                // so re-splitting would only lose the author's grouping.
                case BodyBlock.Text text -> text.text();
                // Icon: chat cannot show an item, so the id is dropped (§3.2).
                case BodyBlock.Icon icon -> icon.beside();
            };
            if (line != null) {
                out.sendMessage(line);
            }
        }

        for (Input input : nullSafe(screen.inputs())) {
            out.sendMessage(inputLine(session, input));
        }

        renderButtons(session, out);
    }

    /**
     * FORM puts its buttons on one row (submit + cancel belong together); MENU
     * gives every row its own click line with the exit last (§3.2); NOTICE has
     * nothing to close in chat, so its exit is rendered only when it is real
     * navigation (a {@code RunCommand}) rather than a dismiss.
     */
    private void renderButtons(ChatFormSession session, Audience out) {
        Screen screen = session.screen();
        List<Button> buttons = nullSafe(screen.buttons());
        Button exit = screen.exit();

        if (screen.kind() == Screen.Kind.FORM) {
            List<Component> row = new ArrayList<>();
            for (Button b : buttons) {
                row.add(bracketed(session, b, Style.ACTION));
            }
            if (exit != null) {
                row.add(bracketed(session, exit, Style.META));
            }
            if (!row.isEmpty()) {
                out.sendMessage(join(row));
            }
            return;
        }

        boolean notice = screen.kind() == Screen.Kind.NOTICE;
        for (Button b : buttons) {
            out.sendMessage(menuRow(session, b, Style.ACTION));
        }
        if (exit != null && (!notice || exit.action() instanceof UiAction.RunCommand)) {
            out.sendMessage(menuRow(session, exit, Style.META));
        }
    }

    /** {@code ──────── title ────────}, the block's only delimiter. */
    private Component header(Screen screen) {
        Component title = screen.title() == null ? Component.empty() : screen.title();
        return plain(RULE + " ", Style.META)
                .append(title.colorIfAbsent(Style.HEADING).decoration(TextDecoration.ITALIC, false))
                .append(plain(" " + RULE, Style.META));
    }

    /**
     * One input as one line: {@code label = value [affordance]} for text,
     * numbers and booleans, {@code label: [a] [b] [c]} for choices (§3.2).
     */
    private Component inputLine(ChatFormSession session, Input input) {
        return switch (input) {
            case Input.Text text -> valueLine(input, currentText(session, text), changeButton(session, input));
            case Input.Number number -> valueLine(input, fmtNumber(currentNumber(session, number)),
                    changeButton(session, input));
            case Input.Bool bool -> {
                boolean on = currentBool(session, bool);
                Component value = plain(on ? "on" : "off", on ? Style.OK : NamedTextColor.GRAY);
                yield label(input).append(plain(" = ", Style.META)).append(value).append(space())
                        .append(token(session, "[toggle]", Style.ACTION, "Flip this setting",
                                () -> {
                                    session.put(bool.key(), !currentBool(session, bool));
                                    render(session);
                                }));
            }
            case Input.Choice choice -> {
                String selected = currentChoice(session, choice);
                Component line = label(input).append(plain(":", Style.META));
                for (Input.Choice.Option option : nullSafe(choice.options())) {
                    boolean isSelected = option.id() != null && option.id().equals(selected);
                    // Selected is the only green thing on the line and the rest go
                    // dim (§3.2). The check glyph carries the same information
                    // without relying on color, which chat cannot guarantee
                    // (colorblind players, plain-text log scrapes).
                    Component opt = isSelected
                            ? bracket(Component.text("✔ ").append(nonNull(option.label())), Style.OK)
                            : tokenComponent(session, bracket(option.label(), Style.META), "Pick this option",
                                    () -> {
                                        session.put(choice.key(), option.id());
                                        render(session);
                                    });
                    line = line.append(space()).append(opt);
                }
                yield line;
            }
        };
    }

    private Component valueLine(Input input, String value, Component affordance) {
        boolean empty = value == null || value.isEmpty();
        Component shown = empty
                ? plain("(empty)", Style.META)
                : plain(preview(value), NamedTextColor.WHITE);
        return label(input).append(plain(" = ", Style.META)).append(shown).append(space()).append(affordance);
    }

    /** The {@code [✎ change]} affordance: one token that opens a chat capture. */
    private Component changeButton(ChatFormSession session, Input input) {
        return token(session, "[✎ change]", Style.ACTION, "Type a new value in chat",
                () -> startCapture(session, input));
    }

    private Component label(Input input) {
        Component label = switch (input) {
            case Input.Text t -> t.label();
            case Input.Bool b -> b.label();
            case Input.Choice c -> c.label();
            case Input.Number n -> n.label();
        };
        Component name = label == null ? Component.text(input.key()) : label;
        return name.colorIfAbsent(Style.INFO).decoration(TextDecoration.ITALIC, false);
    }

    /** A FORM button: {@code [Label]} on the button row. */
    private Component bracketed(ChatFormSession session, Button button, TextColor fallback) {
        return actionable(session, button, bracket(button.label(), fallback));
    }

    /** A MENU row: an arrow plus the row's own label, the whole line clickable. */
    private Component menuRow(ChatFormSession session, Button button, TextColor fallback) {
        Component label = button.label() == null ? Component.empty() : button.label();
        Component row = plain("› ", Style.META)
                .append(label.colorIfAbsent(fallback).decoration(TextDecoration.ITALIC, false));
        return actionable(session, button, row);
    }

    /**
     * Binds a button's action to a rendered component: {@code RunCommand} maps
     * 1:1 onto Adventure's own click event (no token needed — it is stateless
     * navigation), while Submit and Callback need a token so the pending value
     * set survives the round trip.
     */
    private Component actionable(ChatFormSession session, Button button, Component rendered) {
        Component hovered = button.tooltip() == null
                ? rendered
                : rendered.hoverEvent(HoverEvent.showText(
                        button.tooltip().colorIfAbsent(Style.INFO).decoration(TextDecoration.ITALIC, false)));
        UiAction action = button.action();
        if (action == null) {
            return hovered;
        }
        return switch (action) {
            case UiAction.RunCommand run -> hovered.clickEvent(ClickEvent.runCommand(run.command()));
            case UiAction.Submit submit -> tokenComponent(session, hovered, null,
                    () -> invoke(session, submit.callback(), response(session)));
            // "The response carries no values" (UiAction javadoc): a Callback
            // gets an empty response, not the pending set.
            case UiAction.Callback callback -> tokenComponent(session, hovered, null,
                    () -> invoke(session, callback.callback(), EMPTY_RESPONSE));
        };
    }

    /**
     * Submit/Callback are one-shot and close the screen: the session goes away
     * before the callback runs, so its remaining toggles cannot mutate a form
     * that has already been sent, and a callback that opens the next screen
     * simply installs a fresh session.
     */
    private void invoke(ChatFormSession session, UiCallback callback, UiResponse response) {
        UUID player = session.player();
        discard(player);
        if (callback != null) {
            callback.handle(response, player);
        }
    }

    // ---- text capture (`[✎ change]`) ----

    /**
     * Starts a {@link ChatBridge} capture for one input. Single-line inputs
     * finish on the first line; a multiline {@code Input.Text} (the make/edit
     * prompts) collects lines until a lone {@code done} (§3.2). Either way
     * {@code cancel} aborts and the screen is re-rendered afterwards, which is
     * also what hands the player a fresh, live copy of the block.
     */
    private void startCapture(ChatFormSession session, Input input) {
        UUID player = session.player();
        Audience out = messenger.player(player);
        boolean multiline = input instanceof Input.Text text && text.multiline();

        out.sendMessage(Style.prefix()
                .append(plain("Type a value for ", Style.INFO))
                .append(label(input))
                .append(plain(multiline ? " — one line at a time." : ".", Style.INFO)));
        out.sendMessage(plain(multiline
                        ? "  Type `done` on its own line when finished, or `cancel` to keep the old value."
                        : "  Or type `cancel` to keep the old value.",
                Style.META));
        if (input instanceof Input.Number number) {
            out.sendMessage(plain("  Range: " + fmtNumber(number.min()) + " … " + fmtNumber(number.max())
                    + (number.step() > 0 ? " (step " + fmtNumber(number.step()) + ")" : ""), Style.META));
        }

        List<String> collected = new ArrayList<>();
        session.capture(chat.capture(player, line -> {
            String value = line == null ? "" : line;
            String trimmed = value.trim();
            if (trimmed.equalsIgnoreCase(CANCEL_WORD)) {
                finishCapture(session, out, plain("Unchanged.", Style.META));
                return ChatBridge.CaptureResult.CANCELLED;
            }
            if (multiline) {
                if (trimmed.equalsIgnoreCase(DONE_WORD)) {
                    accept(session, out, input, String.join("\n", collected));
                    return ChatBridge.CaptureResult.DONE;
                }
                collected.add(value);
                return ChatBridge.CaptureResult.CONTINUE;
            }
            if (input instanceof Input.Number number && parse(trimmed) == null) {
                // Keep capturing: a typo should not cost the player the whole edit.
                out.sendMessage(Style.err("\"" + preview(trimmed) + "\" is not a number — try again, or `"
                        + CANCEL_WORD + "`."));
                return ChatBridge.CaptureResult.CONTINUE;
            }
            accept(session, out, input, value);
            return ChatBridge.CaptureResult.DONE;
        }));
    }

    /** Validates one captured value into the pending set, then re-renders. */
    private void accept(ChatFormSession session, Audience out, Input input, String raw) {
        switch (input) {
            case Input.Text text -> {
                String value = raw;
                if (text.maxLength() > 0 && value.length() > text.maxLength()) {
                    value = value.substring(0, text.maxLength());
                    out.sendMessage(Style.warn("Trimmed to " + text.maxLength() + " characters."));
                }
                session.put(text.key(), value);
            }
            case Input.Number number -> {
                Double parsed = parse(raw.trim());
                double value = clamp(number, parsed == null ? number.initial() : parsed);
                if (parsed != null && Math.abs(value - parsed) > 1e-9) {
                    out.sendMessage(Style.warn("Adjusted to " + fmtNumber(value) + "."));
                }
                session.put(number.key(), value);
            }
            // Bool and Choice never open a capture; they click through directly.
            default -> LOG.warning("Chat capture completed for a non-editable input " + input.key());
        }
        finishCapture(session, out, null);
    }

    /** Ends a capture: optional note, then the block again with the new value. */
    private void finishCapture(ChatFormSession session, Audience out, Component note) {
        if (note != null) {
            out.sendMessage(note);
        }
        // The bridge closes the registration itself on DONE/CANCELLED; dropping
        // our handle keeps the session from closing an already-dead capture.
        session.capture(null);
        if (sessions.get(session.player()) == session) {
            render(session);
        }
    }

    // ---- current / submitted values ----

    /**
     * The pending value set as a {@link UiResponse}, falling back to each
     * input's {@code initial} for anything the player never touched. Values are
     * already validated (clamped, membership-checked) on the way in — the
     * server-side re-validation §3 demands happens at capture time, not here.
     */
    private UiResponse response(ChatFormSession session) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Input input : nullSafe(session.screen().inputs())) {
            switch (input) {
                case Input.Text text -> values.put(text.key(), currentText(session, text));
                case Input.Bool bool -> values.put(bool.key(), currentBool(session, bool));
                case Input.Choice choice -> values.put(choice.key(), currentChoice(session, choice));
                case Input.Number number -> values.put(number.key(), currentNumber(session, number));
            }
        }
        return new MapResponse(values);
    }

    private String currentText(ChatFormSession session, Input.Text text) {
        Object pending = session.pending(text.key());
        if (pending instanceof String s) {
            return s;
        }
        return text.initial() == null ? "" : text.initial();
    }

    private boolean currentBool(ChatFormSession session, Input.Bool bool) {
        Object pending = session.pending(bool.key());
        return pending instanceof Boolean b ? b : bool.initial();
    }

    private double currentNumber(ChatFormSession session, Input.Number number) {
        Object pending = session.pending(number.key());
        return pending instanceof Double d ? d : number.initial();
    }

    /** The selected option id: pending pick, else the option marked selected, else the first. */
    private String currentChoice(ChatFormSession session, Input.Choice choice) {
        Object pending = session.pending(choice.key());
        if (pending instanceof String s) {
            return s;
        }
        String first = null;
        for (Input.Choice.Option option : nullSafe(choice.options())) {
            if (first == null) {
                first = option.id();
            }
            if (option.selected()) {
                return option.id();
            }
        }
        return first;
    }

    // ---- tokens ----

    /** A {@code [label]}-shaped token click. */
    private Component token(ChatFormSession session, String label, TextColor color, String hover, Runnable action) {
        return tokenComponent(session, plain(label, color), hover, action);
    }

    /**
     * Mints a single-use token for {@code action} and hangs it off
     * {@code rendered} as a {@code /vibe ui <token>} click. The token carries
     * the session identity, so any token from an older screen (or an older
     * render of this one) is rejected by {@link #handleToken} rather than
     * applied to state that has moved on.
     */
    private Component tokenComponent(ChatFormSession session, Component rendered, String hover, Runnable action) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(id, new Token(session.player(), session, System.currentTimeMillis() + TTL_MILLIS, action));
        Component out = rendered.clickEvent(ClickEvent.runCommand(TOKEN_COMMAND + id));
        if (hover != null && !hover.isEmpty()) {
            out = out.hoverEvent(HoverEvent.showText(plain(hover, Style.INFO)));
        }
        return out;
    }

    /**
     * Runs a token action under the §3 obligation that a callback exception
     * never reaches the platform: log it, tell the player, carry on.
     */
    private void guarded(UUID player, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Chat UI action failed for " + player, t);
            messenger.player(player).sendMessage(Style.err("That didn't work — see the server log."));
        }
    }

    /** Forgets a player's session, its capture and every token pointing at it. */
    private void discard(UUID player) {
        ChatFormSession previous = sessions.remove(player);
        if (previous != null) {
            previous.closeCapture();
        }
        tokens.values().removeIf(t -> t.player().equals(player));
    }

    /**
     * Lazy TTL sweep (§3.2 forbids a scheduled task for this): every
     * {@code show} and every token click drops what has aged out, which is
     * often enough on any server where the chat UI is used at all.
     */
    private void prune() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> {
            if (s.expired(now)) {
                s.closeCapture();
                return true;
            }
            return false;
        });
        tokens.values().removeIf(t -> t.expired(now) || sessions.get(t.player()) != t.session());
    }

    // ---- small helpers ----

    private static Component join(List<Component> parts) {
        Component out = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out = out.append(space());
            }
            out = out.append(parts.get(i));
        }
        return out;
    }

    private static Component bracket(Component label, TextColor color) {
        Component inner = nonNull(label).colorIfAbsent(color);
        return plain("[", color).append(inner.decoration(TextDecoration.ITALIC, false)).append(plain("]", color));
    }

    private static Component nonNull(Component component) {
        return component == null ? Component.empty() : component;
    }

    private static Component space() {
        return Component.text(" ");
    }

    private static Component plain(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** Single-line, bounded echo of a value — multiline prompts must not flood the line. */
    private static String preview(String value) {
        String flat = value.replace("\r", "").replace("\n", " ⏎ ");
        return flat.length() <= VALUE_PREVIEW_CHARS ? flat : flat.substring(0, VALUE_PREVIEW_CHARS - 1) + "…";
    }

    private static Double parse(String raw) {
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Clamp to [min, max] and round to {@code step} — the §3.2 number contract. */
    private static double clamp(Input.Number number, double value) {
        double lo = Math.min(number.min(), number.max());
        double hi = Math.max(number.min(), number.max());
        double v = Math.max(lo, Math.min(hi, value));
        if (number.step() > 0) {
            v = Math.max(lo, Math.min(hi, lo + Math.rint((v - lo) / number.step()) * number.step()));
        }
        // Stepping in binary floating point leaves dust (0.30000000000000004);
        // six decimals is well past any knob's precision.
        return Math.rint(v * 1_000_000d) / 1_000_000d;
    }

    /** Integral values print as integers — "timeout = 120", never "120.0". */
    private static String fmtNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        String s = String.format(Locale.ROOT, "%.6f", value).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** What a {@code UiAction.Callback} gets: no values, by contract. */
    private static final UiResponse EMPTY_RESPONSE = new UiResponse() {
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

    /**
     * A submitted value set. Choice picks arrive as their option id via
     * {@link #text}, matching what the dialog renderer's {@code singleOption}
     * returns, so callbacks read the same shape on every platform.
     */
    private record MapResponse(Map<String, Object> values) implements UiResponse {

        @Override
        public String text(String key) {
            return values.get(key) instanceof String s ? s : null;
        }

        @Override
        public Boolean bool(String key) {
            return values.get(key) instanceof Boolean b ? b : null;
        }

        @Override
        public Double number(String key) {
            return values.get(key) instanceof Double d ? d : null;
        }
    }

    /**
     * One live click. {@code session} is compared by identity in
     * {@link #handleToken}: that is what makes a token die when the player
     * opens another screen, without having to hunt down its component.
     */
    private record Token(UUID player, ChatFormSession session, long expiresAt, Runnable action) {

        boolean expired(long nowMillis) {
            return nowMillis > expiresAt;
        }
    }
}
