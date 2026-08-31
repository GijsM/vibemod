package vibemod.grapplinghook;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A grappling hook: look at a block up to REACH away, right-click, and get
 * flung at it.
 *
 * <p>The hook is a renamed vanilla fishing rod (see the recipe), because this
 * host may be a dedicated server where a registered item is refused. The launch
 * maths lives in {@link #launch}, which is called from BOTH the right-click
 * path and the {@code /grapple test} command, so the behaviour can be checked
 * on a server with nobody on it.
 */
public final class GrapplingHook implements ModInitializer {

    private static final String NS = "vibemod_grapplinghook";
    /** How far the hook reaches, in blocks. */
    private static final double REACH = 40.0D;
    /** How hard it pulls. */
    private static final double PULL = 1.4D;
    /** Cooldown between grapples, in ticks (3 seconds). */
    private static final int COOLDOWN_TICKS = 60;

    /** Server thread only: last grapple tick per entity. */
    private final Map<UUID, Integer> lastUse = new HashMap<>();
    private int ticks;

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> ticks++);

        // The real path. Needs a player holding the hook, so it never fires on a
        // headless server - the command below runs the same code instead.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack held = player.getItemInHand(hand);
            if (!(level instanceof ServerLevel serverLevel) || held.getItem() != Items.FISHING_ROD) {
                return InteractionResult.PASS;
            }
            Result result = launch(serverLevel, player, player.getLookAngle());
            player.sendSystemMessage(Component.literal(
                    result.hit() ? "Grapple!" : "Nothing in range."));
            return result.hit() ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("grapple")
                        .then(Commands.literal("test")
                                .executes(ctx -> test(ctx, 12))
                                .then(Commands.argument("height", IntegerArgumentType.integer(1, 60))
                                        .executes(ctx -> test(ctx,
                                                IntegerArgumentType.getInteger(ctx, "height")))))
                        .then(Commands.literal("cooldowns").executes(ctx -> {
                            ctx.getSource().sendSystemMessage(Component.literal(
                                    "grapple-cooldowns tracked=" + lastUse.size() + " tick=" + ticks));
                            return 1;
                        }))));
    }

    /**
     * Puts a stone block {@code height} blocks up, finds or spawns an armour
     * stand under it, and grapples the stand at it. That is the whole
     * right-click path with the player swapped out for something the console can
     * create.
     *
     * <p>The probe is REUSED rather than spawned fresh each time. The cooldown is
     * keyed by UUID, so a new stand every call would be a new player every call
     * and the cooldown could never be observed.
     */
    private int test(CommandContext<CommandSourceStack> ctx, int height) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos here = BlockPos.containing(source.getPosition());
        BlockPos anchor = here.above(height);
        level.setBlock(anchor, Blocks.STONE.defaultBlockState(), 3);

        ArmorStand stand = existingProbe(level);
        if (stand == null) {
            stand = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
            if (stand == null) {
                source.sendSystemMessage(Component.literal("grapple-test could not spawn a probe"));
                return 0;
            }
            stand.addTag(NS + "_probe");
            level.addFreshEntity(stand);
        }
        stand.setPos(here.getX() + 0.5D, here.getY() + 1.0D, here.getZ() + 0.5D);
        stand.setDeltaMovement(Vec3.ZERO);

        Result result = launch(level, stand, new Vec3(0.0D, 1.0D, 0.0D));
        Vec3 v = stand.getDeltaMovement();
        // Locale.ROOT, and not out of pedantry: the default locale on this
        // machine formats 11.22 as "11,22", which turns a three-part vector into
        // six comma-separated fields and breaks anything reading the line.
        source.sendSystemMessage(Component.literal("grapple-test hit=" + result.hit()
                + " dist=" + String.format(Locale.ROOT, "%.2f", result.distance())
                + " vel=" + String.format(Locale.ROOT, "%.3f,%.3f,%.3f", v.x, v.y, v.z)
                + " hurtMarked=" + stand.hurtMarked));
        return 1;
    }

    /** The probe this mod spawned earlier, if it is still in the world. */
    private ArmorStand existingProbe(ServerLevel level) {
        List<? extends ArmorStand> found = level.getEntities(
                EntityTypes.ARMOR_STAND, stand -> stand.entityTags().contains(NS + "_probe"));
        return found.isEmpty() ? null : found.get(0);
    }

    /** The launch itself: raytrace along {@code look}, then fling. */
    private Result launch(ServerLevel level, Entity who, Vec3 look) {
        Integer last = lastUse.get(who.getUUID());
        if (last != null && ticks - last < COOLDOWN_TICKS) {
            return new Result(false, 0.0D);
        }
        Vec3 from = who.getEyePosition();
        Vec3 to = from.add(look.normalize().scale(REACH));
        BlockHitResult hit = level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, who));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return new Result(false, 0.0D);
        }
        Vec3 pull = hit.getLocation().subtract(from);
        who.setDeltaMovement(pull.normalize().scale(PULL));
        // Without this the server never sends the new velocity to the client.
        who.hurtMarked = true;
        lastUse.put(who.getUUID(), ticks);
        trail(level, from, hit.getLocation());
        return new Result(true, pull.length());
    }

    /** A line of crit particles from the hand to the anchor. */
    private void trail(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 step = to.subtract(from).scale(1.0D / 16.0D);
        for (int i = 0; i <= 16; i++) {
            Vec3 at = from.add(step.scale(i));
            level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    private record Result(boolean hit, double distance) {
    }
}
