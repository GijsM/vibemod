package com.gijsm.vibemod.fabric;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.gijsm.vibemod.platform.Messenger;
import com.gijsm.vibemod.platform.Sender;

/**
 * {@link Messenger} over vanilla.
 *
 * <p>Unlike Paper — where every {@code CommandSender} already <em>is</em> an
 * {@link Audience} — nothing here is an Audience, so this class owns the
 * per-player {@link FabricAudience} instances. They are cached per UUID rather
 * than made fresh, because a boss bar has to be the same object across the
 * {@code showBossBar}/{@code hideBossBar} pair that {@code Progress} straddles
 * over several seconds.
 *
 * <p>Permission-scoped broadcasts reuse {@link FabricSender}'s node/level
 * mapping by asking each player's own command source, so ops and a permission
 * manager both work without this class knowing which is installed.
 */
public final class FabricMessenger implements Messenger {

    private static final Logger LOG = Logger.getLogger(FabricMessenger.class.getName());

    /** The celebration burst: same particle count and spread as the v1 install flourish. */
    private static final int CELEBRATION_PARTICLES = 30;
    private static final double CELEBRATION_SPREAD = 0.5;

    private final MinecraftServer server;
    private final Map<UUID, FabricAudience> audiences = new ConcurrentHashMap<>();
    private final Audience console;

    public FabricMessenger(MinecraftServer server) {
        this.server = server;
        this.console = FabricAudience.console();
    }

    @Override
    public Audience player(UUID playerId) {
        if (playerOrNull(playerId) == null) {
            return Audience.empty();
        }
        // computeIfAbsent, not compute: the wrapper must be the SAME object across
        // a showBossBar/hideBossBar pair, which Progress straddles over seconds.
        // The wrapper resolves the live ServerPlayer per call, so caching it is safe.
        return audiences.computeIfAbsent(playerId, id -> new FabricAudience(server, id));
    }

    @Override
    public Audience console() {
        return console;
    }

    @Override
    public boolean online(UUID playerId) {
        return playerOrNull(playerId) != null;
    }

    @Override
    public void broadcast(Component message) {
        console.sendMessage(message);
        net.minecraft.network.chat.Component vanilla = FabricText.toVanilla(message, server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(vanilla);
        }
    }

    @Override
    public void broadcast(Component message, String permission) {
        console.sendMessage(message);
        broadcastToPlayers(message, permission);
    }

    @Override
    public void broadcastToPlayers(Component message, String permission) {
        net.minecraft.network.chat.Component vanilla = FabricText.toVanilla(message, server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Sender as = FabricSender.of(player.createCommandSourceStack(), this);
            if (as.hasPermission(permission)) {
                player.sendSystemMessage(vanilla);
            }
        }
    }

    /**
     * The firework puff after a successful generation. Purely cosmetic, so any
     * failure (an odd world state, a particle that moved) is swallowed rather
     * than turning a celebration into an error.
     */
    @Override
    public void celebrate(UUID playerId) {
        ServerPlayer player = playerOrNull(playerId);
        if (player == null) {
            return;
        }
        try {
            ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
            if (level == null) {
                return;
            }
            level.sendParticles(ParticleTypes.FIREWORK,
                    player.getX(), player.getY() + 1, player.getZ(),
                    CELEBRATION_PARTICLES, CELEBRATION_SPREAD, CELEBRATION_SPREAD, CELEBRATION_SPREAD, 0.0);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    /** Drops a leaving player's cached audience (and any boss bar it still shows). */
    public void forget(UUID playerId) {
        FabricAudience audience = audiences.remove(playerId);
        if (audience != null) {
            try {
                audience.forgetAll();
            } catch (Throwable t) {
                LOG.log(Level.FINE, "Could not clean up an audience", t);
            }
        }
    }

    private ServerPlayer playerOrNull(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        try {
            return server.getPlayerList().getPlayer(playerId);
        } catch (Throwable t) {
            return null;
        }
    }
}
