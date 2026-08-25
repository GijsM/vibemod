package com.gijsm.vibemod.loader;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;

/**
 * An Adventure {@link Audience} backed by one {@link ServerPlayer}, over vanilla.
 *
 * <p>This is the class ARCHITECTURE-V2 §1 assumed adventure-platform-mod would
 * provide. It does not get used, for the reasons in §10.3, so here is the part
 * of it VibeMod actually needs — which is four methods, because that is the
 * whole {@code Audience} surface {@code core} touches: {@code sendMessage},
 * {@code playSound}, {@code showBossBar}, {@code hideBossBar}. (Verified by
 * grepping core and platform-api for Adventure imports; everything else in the
 * UI is {@code Component} construction, which needs no platform at all.)
 *
 * <p>Boss bars are the only interesting one. Adventure's {@link BossBar} is a
 * live, mutable, observable object — {@code Progress} animates one by mutating
 * its name and progress every few ticks — whereas vanilla's
 * {@link ServerBossEvent} is a server-side object you push players into. So each
 * shown bar gets a paired {@code ServerBossEvent} and a {@link BossBar.Listener}
 * that forwards mutations to it, and hiding removes the player and drops the
 * listener. The pairing is keyed per player, because two players can watch the
 * same Adventure bar (they do not today, but a shared bar that silently
 * unregistered the first viewer's listener would be a fine bug to never find).
 */
public final class LoaderAudience implements Audience {

    private static final Logger LOG = Logger.getLogger(LoaderAudience.class.getName());

    /** Whatever a sound with no recognizable id becomes: nothing, silently. */
    private static final long RANDOM_SEED_UNUSED = 0L;

    private final MinecraftServer server;
    private final UUID playerId;
    private final Map<BossBar, Bound> bars = new ConcurrentHashMap<>();

    LoaderAudience(MinecraftServer server, UUID playerId) {
        this.server = server;
        this.playerId = playerId;
    }

    /**
     * The player right now, or null when they are offline.
     *
     * <p>Resolved per call, never held. A {@code ServerPlayer} instance is
     * replaced on respawn and on a dimension change, so an audience that cached
     * one would quietly start messaging a corpse — while the audience OBJECT has
     * to stay stable across a {@code showBossBar}/{@code hideBossBar} pair that
     * {@code Progress} straddles over several seconds. Stable wrapper, fresh
     * lookup.
     */
    private ServerPlayer player() {
        try {
            return server.getPlayerList().getPlayer(playerId);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void sendMessage(Component message) {
        ServerPlayer player = player();
        if (player == null) {
            return;
        }
        try {
            player.sendSystemMessage(LoaderText.toVanilla(message, server));
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Could not deliver a message to " + playerId, t);
        }
    }

    @Override
    public void playSound(Sound sound) {
        ServerPlayer player = player();
        if (player == null) {
            return;
        }
        Identifier id = LoaderText.idOrNull(sound.name().asString());
        if (id == null) {
            return;
        }
        Optional<Holder.Reference<SoundEvent>> event = BuiltInRegistries.SOUND_EVENT.get(id);
        if (event.isEmpty()) {
            return;
        }
        try {
            // Positioned at the player: Adventure's Sound has no location, and
            // "at the listener" is what every VibeMod sound means.
            player.connection.send(new ClientboundSoundPacket(event.get(), sourceOf(sound),
                    player.getX(), player.getY(), player.getZ(),
                    sound.volume(), sound.pitch(), RANDOM_SEED_UNUSED));
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Could not play " + id + " for " + playerId, t);
        }
    }

    @Override
    public void showBossBar(BossBar bar) {
        ServerPlayer player = player();
        if (player == null) {
            return;
        }
        bars.computeIfAbsent(bar, key -> {
            ServerBossEvent event = new ServerBossEvent(UUID.randomUUID(),
                    LoaderText.toVanilla(key.name(), server),
                    colorOf(key), overlayOf(key));
            event.setProgress(clamp01(key.progress()));
            event.addPlayer(player);
            BossBar.Listener listener = new BossBar.Listener() {
                @Override
                public void bossBarNameChanged(BossBar self, Component old, Component now) {
                    event.setName(LoaderText.toVanilla(now, server));
                }

                @Override
                public void bossBarProgressChanged(BossBar self, float old, float now) {
                    event.setProgress(clamp01(now));
                }

                @Override
                public void bossBarColorChanged(BossBar self, BossBar.Color old, BossBar.Color now) {
                    event.setColor(colorOf(self));
                }

                @Override
                public void bossBarOverlayChanged(BossBar self, BossBar.Overlay old, BossBar.Overlay now) {
                    event.setOverlay(overlayOf(self));
                }
            };
            key.addListener(listener);
            return new Bound(event, listener);
        });
    }

    @Override
    public void hideBossBar(BossBar bar) {
        Bound bound = bars.remove(bar);
        if (bound == null) {
            return;
        }
        try {
            bar.removeListener(bound.listener);
            bound.event.removeAllPlayers();
            bound.event.setVisible(false);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Could not hide a boss bar", t);
        }
    }

    /** Drops every bar this audience is showing. The host calls it when the player leaves. */
    void forgetAll() {
        for (BossBar bar : Map.copyOf(bars).keySet()) {
            hideBossBar(bar);
        }
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * Adventure's sound source to vanilla's. Both are the same eight-ish
     * categories under different spellings, so the names match; anything
     * unexpected becomes MASTER rather than throwing.
     */
    private static SoundSource sourceOf(Sound sound) {
        try {
            return SoundSource.valueOf(sound.source().name());
        } catch (IllegalArgumentException unknown) {
            return SoundSource.MASTER;
        }
    }

    private static BossEvent.BossBarColor colorOf(BossBar bar) {
        try {
            return BossEvent.BossBarColor.valueOf(bar.color().name());
        } catch (IllegalArgumentException unknown) {
            return BossEvent.BossBarColor.WHITE;
        }
    }

    private static BossEvent.BossBarOverlay overlayOf(BossBar bar) {
        try {
            return BossEvent.BossBarOverlay.valueOf(bar.overlay().name());
        } catch (IllegalArgumentException unknown) {
            return BossEvent.BossBarOverlay.PROGRESS;
        }
    }

    /** One Adventure bar's vanilla twin plus the listener keeping them in step. */
    private record Bound(ServerBossEvent event, BossBar.Listener listener) {
    }

    /**
     * An audience that logs, for the console. Deliberately not a
     * {@code CommandSourceStack}: command feedback and the log are different
     * channels, and the console operator (and RCON) reads the log.
     */
    static Audience console() {
        Logger log = Logger.getLogger("VibeMod");
        return new Audience() {
            @Override
            public void sendMessage(Component message) {
                log.info(LoaderText.plain(message));
            }
        };
    }
}
