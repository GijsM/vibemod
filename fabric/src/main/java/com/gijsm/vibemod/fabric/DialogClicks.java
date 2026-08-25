package com.gijsm.vibemod.fabric;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where a dialog button's click lands.
 *
 * <p>Vanilla's dialog system has no callback channel. A button carries a
 * {@code CustomAll} action, which the client turns into a
 * {@code ServerboundCustomClickActionPacket} carrying an {@link Identifier} and
 * an NBT tag of every input's value — and vanilla's own handler for that packet
 * is a {@code LOGGER.debug} no-op that does not even receive the player. So
 * VibeMod supplies the missing half: the Identifier's path is a one-shot token
 * minted here, and one mixin on the packet handler routes the click back.
 *
 * <p>Static, because a Mixin's injected code has no instance to reach through.
 * That makes lifetime this class's own problem, so tokens are per player,
 * single-use and TTL'd — the same discipline the chat renderer's
 * {@code /vibe ui <token>} route already uses, and for the same reason: a
 * dialog can sit open on a client forever, and a token that outlived its screen
 * is an action nobody asked for.
 */
public final class DialogClicks {

    private static final Logger LOG = Logger.getLogger(DialogClicks.class.getName());

    /** The namespace that marks a click as ours. */
    public static final String NAMESPACE = "vibemod";

    /** How long an unclicked button stays live. Matches the chat renderer's token TTL. */
    private static final long TTL_MILLIS = 5 * 60 * 1000L;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    private DialogClicks() {
    }

    /**
     * Mints a token for one button of one screen shown to one player.
     *
     * @param onClick receives the click payload (never null; empty when the
     *                button reads no inputs) and the clicking player's id
     */
    public static Identifier mint(UUID player, BiConsumer<CompoundTag, UUID> onClick) {
        sweep();
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        StringBuilder token = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            token.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        String key = token.toString();
        PENDING.put(key, new Pending(player, onClick, System.currentTimeMillis() + TTL_MILLIS));
        return Identifier.fromNamespaceAndPath(NAMESPACE, key);
    }

    /**
     * The mixin's entry point. Returns true when this was one of our clicks, so
     * the caller knows the packet was consumed.
     *
     * <p>Runs on the server thread (the mixin hops). Never throws — this is
     * called from inside packet handling, and an exception here would drop the
     * player's connection.
     */
    public static boolean handle(ServerPlayer player, Identifier id, Optional<Tag> payload) {
        if (player == null || id == null || !NAMESPACE.equals(id.getNamespace())) {
            return false;
        }
        // remove(), not get(): one-shot. A double-click on a laggy connection
        // must not run a delete-confirm twice.
        Pending pending = PENDING.remove(id.getPath());
        if (pending == null) {
            return false;
        }
        if (!pending.player.equals(player.getUUID())) {
            // Tokens are per player, and a client can send any id it likes.
            LOG.warning("Ignoring a dialog click from " + player.getName().getString()
                    + " for a token minted for someone else");
            return true;
        }
        if (pending.expiresAt < System.currentTimeMillis()) {
            return true;
        }
        CompoundTag values = payload.orElse(null) instanceof CompoundTag tag ? tag : new CompoundTag();
        try {
            pending.onClick.accept(values, pending.player);
        } catch (Throwable t) {
            // The renderer's own callback wrapper reports to the player; this is
            // the last line of defence before the connection.
            LOG.log(Level.WARNING, "A dialog callback threw", t);
        }
        return true;
    }

    /** Drops every token minted for a player. Called when they disconnect. */
    public static void forget(UUID player) {
        PENDING.entrySet().removeIf(e -> e.getValue().player.equals(player));
    }

    /** Drops everything. Called at server stop, so a restart in one JVM starts clean. */
    public static void clear() {
        PENDING.clear();
    }

    /** How many tokens are live. The acceptance gate asserts on this. */
    public static int pendingCount() {
        return PENDING.size();
    }

    private static void sweep() {
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(e -> e.getValue().expiresAt < now);
    }

    private record Pending(UUID player, BiConsumer<CompoundTag, UUID> onClick, long expiresAt) {
    }
}
