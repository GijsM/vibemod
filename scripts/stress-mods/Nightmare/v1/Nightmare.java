package vibemod.nightmare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Nights are worse now.
 *
 * <p>Everything about this mod is a lifecycle question rather than a mechanic:
 * it has to find out whether the server is already running (it registers
 * {@code SERVER_STARTED} and the host replays it), it has to remember a toggle
 * across a disable, and it has to hand out an advancement on first join.
 */
public final class Nightmare implements ModInitializer {

    private static final Logger LOG = Logger.getLogger("VibeMod.Nightmare");
    private static final String NS = "vibemod_nightmare";
    /** How much harder a hit lands after dark. */
    private static final float NIGHT_MULTIPLIER = 2.0F;

    private MinecraftServer server;
    private Path flagFile;
    private boolean harsh = true;
    private int scaled;
    private int startedCallbacks;

    @Override
    public void onInitialize() {
        LOG.info("nightmare-init");

        // The host replays this if the server is already up, which is the only
        // way a mod loaded mid-session ever learns the server exists.
        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            startedCallbacks++;
            this.server = started;
            this.flagFile = started.getWorldPath(LevelResource.ROOT).resolve(NS + "-harsh.flag");
            this.harsh = readFlag();
            LOG.info("nightmare-started harsh=" + harsh + " calls=" + startedCallbacks
                    + " flag=" + flagFile);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(stopping -> writeFlag());

        // Nights hurt more. ALLOW_DAMAGE cannot change the amount, so the extra
        // damage is dealt as a second, unscaled hit - and only to mobs, so a
        // player is never killed by a rounding error in somebody's mod.
        ServerLivingEntityEvents.AFTER_DAMAGE.register(
                (entity, source, blocked, taken, blockedByShield) -> {
                    if (!harsh || entity instanceof ServerPlayer) {
                        return;
                    }
                    if (!(entity.level() instanceof ServerLevel level) || !level.isDarkOutside()) {
                        return;
                    }
                    scaled++;
                    entity.setHealth(Math.max(1.0F,
                            entity.getHealth() - taken * (NIGHT_MULTIPLIER - 1.0F)));
                });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, joined) -> {
            AdvancementHolder advancement = joined.getAdvancements()
                    .get(Identifier.fromNamespaceAndPath(NS, "first_night"));
            if (advancement == null) {
                return;
            }
            ServerPlayer player = handler.getPlayer();
            player.getAdvancements().award(advancement, "joined");
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("nightmare")
                        .then(Commands.literal("toggle").executes(ctx -> {
                            harsh = !harsh;
                            writeFlag();
                            ctx.getSource().sendSystemMessage(Component.literal(
                                    "nightmare-toggled harsh=" + harsh));
                            return 1;
                        }))
                        .executes(ctx -> {
                            ServerLevel level = ctx.getSource().getLevel();
                            ctx.getSource().sendSystemMessage(Component.literal(
                                    "nightmare-status harsh=" + harsh
                                            + " dark=" + level.isDarkOutside()
                                            + " scaled=" + scaled
                                            + " startedCallbacks=" + startedCallbacks
                                            + " hasServer=" + (server != null)));
                            return 1;
                        })));
    }

    private boolean readFlag() {
        if (flagFile == null || !Files.exists(flagFile)) {
            return true;
        }
        try {
            return Files.readString(flagFile, StandardCharsets.UTF_8).trim().equals("harsh");
        } catch (IOException failed) {
            LOG.warning("nightmare could not read its flag: " + failed);
            return true;
        }
    }

    private void writeFlag() {
        if (flagFile == null) {
            return;
        }
        try {
            Files.writeString(flagFile, harsh ? "harsh" : "gentle", StandardCharsets.UTF_8);
        } catch (IOException failed) {
            LOG.warning("nightmare could not write its flag: " + failed);
        }
    }
}
