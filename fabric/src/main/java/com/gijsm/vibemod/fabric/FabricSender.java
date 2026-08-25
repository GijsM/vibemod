package com.gijsm.vibemod.fabric;

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
public final class FabricSender implements Sender {

    /**
     * VibeMod's permission strings mapped onto MC 26.x permissions.
     *
     * <p>Two things changed under this method in 26.x and both matter.
     * {@code CommandSourceStack#hasPermission(int)} is gone — permissions are a
     * {@code PermissionSet} of typed {@code Permission}s now, not an op level.
     * And fabric-api ships {@code fabric-permission-api-v1}, whose
     * {@code checkPermission(Identifier, PermissionLevel)} asks any installed
     * permission manager about a real node first and only falls back to the
     * level when nothing answers. So {@code vibe.admin} becomes the node
     * {@code vibemod:admin} with a GAMEMASTERS (op level 2) fallback: a server
     * with LuckPerms grants it by node, a vanilla-ish server by op, and neither
     * needs VibeMod to know which.
     */
    private static final Identifier USE_NODE = Identifier.fromNamespaceAndPath("vibemod", "use");
    private static final Identifier ADMIN_NODE = Identifier.fromNamespaceAndPath("vibemod", "admin");

    private final CommandSourceStack source;
    private final Messenger messenger;

    private FabricSender(CommandSourceStack source, Messenger messenger) {
        this.source = source;
        this.messenger = messenger;
    }

    public static Sender of(CommandSourceStack source, Messenger messenger) {
        return new FabricSender(source, messenger);
    }

    /**
     * The wrapped Brigadier source. Used by {@code FabricModHost} to satisfy the
     * {@code CommandSourceStack}-typed {@code ModCommandHandler} the mod-flavor
     * sdk declares; throws when handed a {@link Sender} from another platform,
     * which would be a wiring bug.
     */
    public static CommandSourceStack unwrap(Sender sender) {
        if (sender instanceof FabricSender fabric) {
            return fabric.source;
        }
        throw new IllegalArgumentException("Not a Fabric sender: "
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
     * For the console it is an audience that logs — {@code CommandSourceStack}
     * would swallow it into command feedback, which RCON and the console reader
     * do not both see.
     */
    @Override
    public Audience audience() {
        ServerPlayer player = source.getPlayer();
        return player == null ? messenger.console() : messenger.player(player.getUUID());
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
            case "vibe.use" -> source.checkPermission(USE_NODE, PermissionLevel.ALL);
            case "vibe.admin" -> source.checkPermission(ADMIN_NODE, PermissionLevel.GAMEMASTERS);
            // An unknown permission string is a mod asking about something we do
            // not model. Op level 2 is the conservative answer.
            default -> source.checkPermission(
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
