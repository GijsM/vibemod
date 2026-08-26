package vibemod.skygrid;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Builds a floating grid of blocks: one block every {@link #SPACING} on all
 * three axes, out to a radius the caller picks.
 *
 * <p>There is no scheduler in a mod like this, so the work is a queue drained
 * from {@code END_SERVER_TICK} at {@link #PER_TICK} placements a tick. That is
 * the whole point of the mod: a job far bigger than one tick's budget, done
 * without ever exceeding it.
 *
 * <p>{@code /skygrid_greedy} is the same grid built inside a single command
 * invocation, and exists to find out what the host does to a mod that will not
 * yield.
 */
public final class SkyGrid implements ModInitializer {

    /** Blocks between grid nodes. */
    private static final int SPACING = 4;
    /** Where the grid is centred. */
    private static final int BASE_Y = 100;
    /** How many blocks are placed per tick by the batched build. */
    private static final int PER_TICK = 2000;

    /** What the grid is made of. */
    private static final List<Block> PALETTE = List.of(
            Blocks.STONE, Blocks.OAK_LOG, Blocks.SAND, Blocks.GLASS,
            Blocks.COAL_ORE, Blocks.MOSS_BLOCK, Blocks.BRICKS, Blocks.PACKED_ICE);

    /** Server thread only. */
    private final Deque<BlockPos> pending = new ArrayDeque<>();
    private ServerLevel target;
    private int placed;
    private int batches;
    private long slowestBatchMs;

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (pending.isEmpty() || target == null) {
                return;
            }
            batches++;
            long start = System.nanoTime();
            int budget = PER_TICK;
            while (budget-- > 0 && !pending.isEmpty()) {
                place(target, pending.poll());
            }
            slowestBatchMs = Math.max(slowestBatchMs, (System.nanoTime() - start) / 1_000_000L);
            if (pending.isEmpty()) {
                server.sendSystemMessage(Component.literal(
                        "skygrid-done placed=" + placed + " batches=" + batches
                                + " slowestBatchMs=" + slowestBatchMs));
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("skygrid")
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                            .executes(ctx -> queue(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "radius"))))
                    .executes(ctx -> {
                        ctx.getSource().sendSystemMessage(Component.literal(
                                "skygrid-status pending=" + pending.size()
                                        + " placed=" + placed + " batches=" + batches));
                        return 1;
                    }));
            dispatcher.register(Commands.literal("skygrid_greedy")
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                            .executes(ctx -> greedy(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "radius")))));
        });
    }

    /** The well-behaved build: fill the queue and let the tick handler drain it. */
    private int queue(CommandSourceStack source, int radius) {
        target = source.getLevel();
        pending.clear();
        placed = 0;
        batches = 0;
        slowestBatchMs = 0L;
        for (BlockPos pos : nodes(radius)) {
            pending.add(pos);
        }
        source.sendSystemMessage(Component.literal("skygrid-queued nodes=" + pending.size()
                + " radius=" + radius + " perTick=" + PER_TICK));
        return 1;
    }

    /**
     * The badly behaved build: every placement inside one command. Nothing here
     * yields, so the host's watchdog is the only thing between this and a frozen
     * server.
     *
     * <p>It times itself, and that number is the point of the whole command:
     * "the server did not visibly stutter" is a claim about this machine, while
     * "one invocation took N milliseconds and nothing stopped it" is a claim
     * about the host.
     */
    private int greedy(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        long start = System.nanoTime();
        int count = 0;
        for (BlockPos pos : nodes(radius)) {
            place(level, pos);
            count++;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        source.sendSystemMessage(Component.literal(
                "skygrid-greedy-done placed=" + count + " elapsedMs=" + elapsedMs));
        return 1;
    }

    /** Every grid node inside {@code radius}, as a plain list. */
    private List<BlockPos> nodes(int radius) {
        java.util.ArrayList<BlockPos> out = new java.util.ArrayList<>();
        for (int x = -radius; x <= radius; x += SPACING) {
            for (int z = -radius; z <= radius; z += SPACING) {
                for (int y = -radius; y <= radius; y += SPACING) {
                    out.add(new BlockPos(x, BASE_Y + y, z));
                }
            }
        }
        return out;
    }

    /** One node. The block is a pure function of the position, so it is repeatable. */
    private void place(ServerLevel level, BlockPos pos) {
        int hash = Math.abs(pos.getX() * 31 + pos.getY() * 17 + pos.getZ() * 7);
        Block block = PALETTE.get(hash % PALETTE.size());
        // Flag 2 = send to clients, do not trigger neighbour updates. A grid of
        // 20k blocks that each poked its neighbours would be a different mod.
        level.setBlock(pos, block.defaultBlockState(), 2);
        placed++;
    }
}
