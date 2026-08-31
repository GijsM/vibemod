package vibemod.meteorstorm;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Meteors. Every {@link #INTERVAL_TICKS} ticks a burning block falls out of the
 * sky near somebody and explodes where it lands, leaving a ring of fire.
 *
 * <p>Impact detection is a per-tick sweep of the meteors this mod spawned: a
 * {@link FallingBlockEntity} removes itself the moment it settles, so
 * "isRemoved() and I was tracking it" is exactly the landing event, and it
 * costs one list walk per tick rather than a listener on every entity.
 */
public final class MeteorStorm implements ModInitializer {

    /** How often a meteor falls, in ticks (30 seconds). */
    private static final int INTERVAL_TICKS = 600;
    /** How high above the target a meteor spawns. */
    private static final int DROP_HEIGHT = 40;
    /** Explosion power on impact. */
    private static final float POWER = 3.0F;
    /** How far a meteor may land from its target. */
    private static final int SCATTER = 8;
    /** Ticks before a meteor may be considered landed - see {@link #sweep}. */
    private static final int GRACE_TICKS = 4;
    /** A meteor that has not settled by now is treated as landed anyway. */
    private static final int MAX_FLIGHT_TICKS = 200;

    /** Server thread only. */
    private final List<Meteor> inFlight = new ArrayList<>();
    private int ticks;
    private int impacts;

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            sweep();
            if (++ticks % INTERVAL_TICKS == 0) {
                drop(server);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("meteor")
                        .then(Commands.literal("now").executes(ctx -> {
                            BlockPos at = drop(ctx.getSource().getServer());
                            ctx.getSource().sendSystemMessage(Component.literal(at == null
                                    ? "meteor-none no level"
                                    : "meteor-spawned " + at.getX() + " " + at.getY() + " " + at.getZ()));
                            return 1;
                        }))
                        .then(Commands.literal("status").executes(ctx -> {
                            ctx.getSource().sendSystemMessage(Component.literal(
                                    "meteor-status inFlight=" + inFlight.size()
                                            + " impacts=" + impacts + " tick=" + ticks));
                            return 1;
                        }))));
    }

    /** Drops one meteor and returns the block it will fall towards. */
    private BlockPos drop(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level == null) {
            return null;
        }
        BlockPos target = pickTarget(level);
        int x = target.getX() + level.getRandom().nextInt(-SCATTER, SCATTER + 1);
        int z = target.getZ() + level.getRandom().nextInt(-SCATTER, SCATTER + 1);
        BlockPos from = new BlockPos(x, target.getY() + DROP_HEIGHT, z);

        FallingBlockEntity meteor = FallingBlockEntity.fall(
                level, from, Blocks.MAGMA_BLOCK.defaultBlockState());
        meteor.setDeltaMovement(new Vec3(0.0D, -1.2D, 0.0D));
        meteor.hurtMarked = true;
        meteor.setRemainingFireTicks(200);
        meteor.disableDrop();
        inFlight.add(new Meteor(meteor, level, ticks));
        level.sendParticles(ParticleTypes.LARGE_SMOKE,
                from.getX() + 0.5D, from.getY() + 0.5D, from.getZ() + 0.5D,
                20, 0.4D, 0.4D, 0.4D, 0.02D);
        return from;
    }

    /** A random online player's feet, or the world's respawn point if nobody is on. */
    private BlockPos pickTarget(ServerLevel level) {
        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
        if (!players.isEmpty()) {
            return players.get(level.getRandom().nextInt(players.size())).blockPosition();
        }
        return level.getLevelData().getRespawnData().pos();
    }

    /**
     * One walk of the in-flight list: anything that has settled has landed.
     *
     * <p>The grace period is not defensive padding. A meteor is spawned from
     * {@code FallingBlockEntity.fall}, which puts the entity in the world in the
     * same tick, and the entity is not yet moving when this sweep next runs -
     * so without it every meteor "lands" one tick after it is created, at the
     * height it was created.
     */
    private void sweep() {
        if (inFlight.isEmpty()) {
            return;
        }
        for (int i = inFlight.size() - 1; i >= 0; i--) {
            Meteor meteor = inFlight.get(i);
            int age = ticks - meteor.bornTick;
            if (age < GRACE_TICKS) {
                continue;
            }
            boolean settled = meteor.entity.isRemoved() || meteor.entity.onGround();
            if (!settled && age < MAX_FLIGHT_TICKS) {
                continue;
            }
            inFlight.remove(i);
            impact(meteor.level, meteor.entity.blockPosition(), age, meteor.entity.isRemoved(),
                    meteor.entity.onGround());
        }
    }

    private void impact(ServerLevel level, BlockPos at, int age, boolean removed, boolean grounded) {
        impacts++;
        level.explode(null, at.getX() + 0.5D, at.getY() + 0.5D, at.getZ() + 0.5D,
                POWER, Level.ExplosionInteraction.MOB);
        // A ring of scorched ground with fire on it. The magma underneath is not
        // decoration: the explosion has just removed the blocks around the crater,
        // and fire with nothing under it is deleted by the very next block update.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) != 2 && Math.abs(dz) != 2) {
                    continue;
                }
                level.setBlock(at.offset(dx, -1, dz), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
                BlockPos fire = at.offset(dx, 0, dz);
                if (level.getBlockState(fire).isAir()) {
                    level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 3);
                }
            }
        }
        level.getServer().sendSystemMessage(Component.literal(
                "meteor-impact " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " total=" + impacts + " age=" + age
                        + " removed=" + removed + " grounded=" + grounded));
    }

    private record Meteor(FallingBlockEntity entity, ServerLevel level, int bornTick) {
    }
}
