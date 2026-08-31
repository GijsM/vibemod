package vibemod.arenamaster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * Arenas: rings of escalating mobs, with a scoreboard that counts what dies in
 * them.
 *
 * <p>Everything an arena spawns carries this mod's tag, which is how
 * {@code /arena stop} cleans up without keeping references to entities that may
 * have been unloaded with their chunk.
 */
public final class ArenaMaster implements ModInitializer {

    private static final String NS = "vibemod_arenamaster";
    private static final String TAG = NS + "_mob";
    private static final String OBJECTIVE = "arena_kills";
    /** Ticks between waves. */
    private static final int WAVE_TICKS = 60;
    /** Mobs in wave 1; each wave adds this many again. */
    private static final int WAVE_SIZE = 2;

    /** The mobs each wave draws from, in order of nastiness. */
    private static final List<EntityType<? extends Mob>> LADDER = List.of(
            EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER, EntityTypes.CREEPER);

    /** Server thread only. */
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private int ticks;
    private int spawned;
    private int kills;

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ticks++;
            for (Arena arena : arenas.values()) {
                if (arena.wavesLeft <= 0) {
                    continue;
                }
                if (ticks - arena.lastWaveTick < WAVE_TICKS) {
                    continue;
                }
                arena.lastWaveTick = ticks;
                arena.wave++;
                arena.wavesLeft--;
                spawnWave(server, arena);
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!entity.entityTags().contains(TAG)) {
                return;
            }
            kills++;
            Scoreboard scoreboard = entity.level().getServer().getScoreboard();
            Objective objective = objective(entity.level().getServer());
            scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly("arena"), objective)
                    .add(1);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("arena")
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(2, 32))
                                                .executes(ctx -> create(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        IntegerArgumentType.getInteger(ctx, "radius"))))))
                        .then(Commands.literal("start")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("waves", IntegerArgumentType.integer(1, 20))
                                                .executes(ctx -> start(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        IntegerArgumentType.getInteger(ctx, "waves"))))))
                        .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())))
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))));
    }

    // ------------------------------------------------------------- subcommands

    private int create(CommandSourceStack source, String name, int radius) {
        BlockPos centre = BlockPos.containing(source.getPosition());
        arenas.put(name, new Arena(name, centre, radius));
        objective(source.getServer());
        source.sendSystemMessage(Component.literal("arena-created " + name
                + " radius=" + radius
                + " at " + centre.getX() + "," + centre.getY() + "," + centre.getZ()));
        return 1;
    }

    private int start(CommandSourceStack source, String name, int waves) {
        Arena arena = arenas.get(name);
        if (arena == null) {
            source.sendSystemMessage(Component.literal("arena-unknown " + name));
            return 0;
        }
        arena.wavesLeft = waves;
        arena.wave = 0;
        // The first wave lands on the next tick rather than in WAVE_TICKS.
        arena.lastWaveTick = ticks - WAVE_TICKS;
        source.sendSystemMessage(Component.literal("arena-started " + name + " waves=" + waves));
        return 1;
    }

    private int stop(CommandSourceStack source) {
        for (Arena arena : arenas.values()) {
            arena.wavesLeft = 0;
        }
        int swept = sweep(source.getLevel());
        source.sendSystemMessage(Component.literal("arena-stopped swept=" + swept
                + " spawned=" + spawned + " kills=" + kills));
        return 1;
    }

    private int list(CommandSourceStack source) {
        StringBuilder line = new StringBuilder("arena-list count=" + arenas.size());
        for (Arena arena : arenas.values()) {
            line.append(' ').append(arena.name).append('/').append(arena.radius)
                    .append("/w").append(arena.wave).append('+').append(arena.wavesLeft);
        }
        line.append(" score=").append(score(source.getServer()));
        source.sendSystemMessage(Component.literal(line.toString()));
        return 1;
    }

    // ---------------------------------------------------------------- internals

    private void spawnWave(MinecraftServer server, Arena arena) {
        ServerLevel level = server.overworld();
        int count = WAVE_SIZE * arena.wave;
        EntityType<? extends Mob> type = LADDER.get(Math.min(arena.wave - 1, LADDER.size() - 1));
        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2.0D * i) / count;
            double x = arena.centre.getX() + 0.5D + Math.cos(angle) * arena.radius;
            double z = arena.centre.getZ() + 0.5D + Math.sin(angle) * arena.radius;
            Mob mob = type.create(level, EntitySpawnReason.COMMAND);
            if (mob == null) {
                continue;
            }
            mob.setPos(x, arena.centre.getY() + 1.0D, z);
            mob.addTag(TAG);
            mob.setPersistenceRequired();
            level.addFreshEntity(mob);
            spawned++;
        }
        server.sendSystemMessage(Component.literal("arena-wave " + arena.name
                + " n=" + arena.wave + " mobs=" + count
                + " type=" + EntityType.getKey(type)));
    }

    /** Removes everything this mod ever spawned, by tag rather than by reference. */
    private int sweep(ServerLevel level) {
        List<Entity> doomed = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity.entityTags().contains(TAG)) {
                doomed.add(entity);
            }
        }
        for (Entity entity : doomed) {
            entity.discard();
        }
        return doomed.size();
    }

    private Objective objective(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        Objective existing = scoreboard.getObjective(OBJECTIVE);
        if (existing != null) {
            return existing;
        }
        return scoreboard.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY,
                Component.literal("Arena kills"), ObjectiveCriteria.RenderType.INTEGER,
                true, null);
    }

    private int score(MinecraftServer server) {
        return server.getScoreboard()
                .getOrCreatePlayerScore(ScoreHolder.forNameOnly("arena"), objective(server))
                .get();
    }

    private static final class Arena {
        final String name;
        final BlockPos centre;
        final int radius;
        int wave;
        int wavesLeft;
        int lastWaveTick;

        Arena(String name, BlockPos centre, int radius) {
            this.name = name;
            this.centre = centre;
            this.radius = radius;
        }
    }
}
