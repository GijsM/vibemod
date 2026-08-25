package com.gijsm.vibemod.loader;

import java.util.Locale;
import java.util.UUID;

import net.kyori.adventure.audience.Audience;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import com.gijsm.vibemod.platform.Messenger;
import com.gijsm.vibemod.platform.Sender;

/**
 * A Brigadier {@link CommandSourceStack} seen as the platform-neutral
 * {@link Sender} core code talks to.
 *
 * <p>{@link #source()} is the escape hatch that makes the generated-code
 * contract work: the mod-flavor sdk hands mods a real {@code CommandSourceStack}
 * (§4.1), so the host has to be able to unwrap what core handed it. Nothing in
 * core ever calls it.
 */
public final class LoaderSender implements Sender {

    /**
     * VibeMod's permission strings mapped onto MC 26.x permissions.
     *
     * <p>Two things changed under this method in 26.x and both matter.
     * {@code CommandSourceStack#hasPermission(int)} is gone — permissions are a
     * {@code PermissionSet} of typed {@code Permission}s now, not an op level.
     * And both loaders ship a node-based permission API that asks any installed
     * permission manager about a real node first and only falls back to an op
     * level when nothing answers — {@code fabric-permission-api-v1} on Fabric,
     * {@code PermissionAPI} on NeoForge. So {@code vibe.admin} becomes the node
     * {@code vibemod:admin} with a GAMEMASTERS (op level 2) fallback: a server
     * with LuckPerms grants it by node, a vanilla-ish server by op, and neither
     * needs VibeMod to know which. Which of the two APIs answers is the
     * {@link PermissionOracle} the host installs at boot.
     */
    public static final Identifier USE_NODE = Identifier.fromNamespaceAndPath("vibemod", "use");
    public static final Identifier ADMIN_NODE = Identifier.fromNamespaceAndPath("vibemod", "admin");

    /**
     * How this loader answers "may this source do X".
     *
     * <p>The one place the two loaders genuinely disagree about permissions, so
     * it is a parameter rather than a branch. Both answer the same QUESTION —
     * "does an installed permission manager grant this node, and if none does,
     * is this source at least at this op level" — they just have different
     * plumbing for it: fabric-permission-api injects
     * {@code CommandSourceStack#checkPermission}, NeoForge has
     * {@code PermissionAPI} plus pre-registered {@code PermissionNode}s.
     */
    @FunctionalInterface
    public interface PermissionOracle {
        boolean check(CommandSourceStack source, Identifier node, PermissionLevel fallback);
    }

    private static volatile PermissionOracle oracle = (source, node, fallback) ->
            fallback == PermissionLevel.ALL;

    /** Installed once at boot by the loader host, before any command can run. */
    public static void setPermissionOracle(PermissionOracle installed) {
        oracle = installed;
    }

    private final CommandSourceStack source;
    private final Messenger messenger;

    private LoaderSender(CommandSourceStack source, Messenger messenger) {
        this.source = source;
        this.messenger = messenger;
    }

    public static Sender of(CommandSourceStack source, Messenger messenger) {
        return new LoaderSender(source, messenger);
    }

    /**
     * The wrapped Brigadier source. Used by {@code LoaderModHost} to satisfy the
     * {@code CommandSourceStack}-typed {@code ModCommandHandler} the mod-flavor
     * sdk declares; throws when handed a {@link Sender} from another platform,
     * which would be a wiring bug.
     */
    public static CommandSourceStack unwrap(Sender sender) {
        if (sender instanceof LoaderSender loader) {
            return loader.source;
        }
        throw new IllegalArgumentException("Not a loader sender: "
                + (sender == null ? "null" : sender.getClass().getName()));
    }

    /** The wrapped source, for host code that already knows what it has. */
    public CommandSourceStack source() {
        return source;
    }

    /**
     * Where replies go.
     *
     * <p>For a player this is the {@link Messenger}'s audience, so a reply takes
     * the same path as everything else core sends them (and so boss bars work).
     *
     * <p>For the console it writes back to the {@code CommandSourceStack} — and
     * that detail is load-bearing. This first routed console replies to
     * {@code messenger.console()}, which logs; the acceptance gate then found
     * that every single RCON command answered "(no reply)", because RCON's reply
     * IS the command's feedback and nothing else. Sending through the source
     * gives the console operator a log line and the RCON caller a response, which
     * is what both of them are asking for.
     */
    @Override
    public Audience audience() {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return messenger.player(player.getUUID());
        }
        return new Audience() {
            @Override
            public void sendMessage(net.kyori.adventure.text.Component message) {
                source.sendSystemMessage(LoaderText.toVanilla(message, source.getServer()));
            }
        };
    }

    @Override
    public String name() {
        return source.getTextName();
    }

    @Override
    public boolean hasPermission(String permission) {
        String want = permission == null ? "" : permission.toLowerCase(Locale.ROOT);
        return switch (want) {
            // Every player may look; the read-only half of /vibe is deliberately open.
            case "vibe.use" -> oracle.check(source, USE_NODE, PermissionLevel.ALL);
            case "vibe.admin" -> oracle.check(source, ADMIN_NODE, PermissionLevel.GAMEMASTERS);
            // An unknown permission string is a mod asking about something we do
            // not model. Op level 2 is the conservative answer.
            default -> oracle.check(source,
                    Identifier.fromNamespaceAndPath("vibemod", sanitize(want)), PermissionLevel.GAMEMASTERS);
        };
    }

    @Override
    public UUID idOrNull() {
        ServerPlayer player = source.getPlayer();
        return player == null ? null : player.getUUID();
    }

    /** {@code "some.permission"} to a legal Identifier path ({@code [a-z0-9_.-/]}). */
    private static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '/' ? c : '_');
        }
        String path = sb.toString();
        return path.isEmpty() ? "unknown" : path;
    }
}
