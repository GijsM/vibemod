package vibemod.zombietitan;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A titan: an ordinary zombie with five times the health, three times the size,
 * a boss bar of its own, and a hoard to drop.
 *
 * <p>It is a vanilla {@code Zombie} rather than a registered
 * {@code EntityType}, because a registered type is refused on a dedicated
 * server and a titan that only exists in singleplayer is not a titan. Everything
 * that makes it a titan is attribute modifiers applied at spawn, which need no
 * registry at all.
 */
public final class ZombieTitan implements ModInitializer {

    private static final String NS = "vibemod_zombietitan";
    private static final String TAG = NS + "_titan";
    /** Health multiplier. */
    private static final double HEALTH_SCALE = 5.0D;
    /** How much bigger a titan is. */
    private static final double SIZE_SCALE = 3.0D;

    private static final Identifier HEALTH_MODIFIER =
            Identifier.fromNamespaceAndPath(NS, "titan_health");
    private static final Identifier SIZE_MODIFIER =
            Identifier.fromNamespaceAndPath(NS, "titan_size");

    /** Server thread only. */
    private final List<Titan> titans = new ArrayList<>();
    private int slain;

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (int i = titans.size() - 1; i >= 0; i--) {
                Titan titan = titans.get(i);
                if (titan.zombie.isRemoved() || !titan.zombie.isAlive()) {
                    titan.bar.removeAllPlayers();
                    titan.bar.setVisible(false);
                    titans.remove(i);
                    continue;
                }
                titan.bar.setProgress(
                        titan.zombie.getHealth() / titan.zombie.getMaxHealth());
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    titan.bar.addPlayer(player);
                }
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!entity.entityTags().contains(TAG) || !(entity.level() instanceof ServerLevel level)) {
                return;
            }
            slain++;
            dropHoard(level, entity.blockPosition());
            level.getServer().sendSystemMessage(Component.literal(
                    "titan-slain total=" + slain + " at " + entity.blockPosition().getX()
                            + " " + entity.blockPosition().getY()
                            + " " + entity.blockPosition().getZ()));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("titan")
                        .then(Commands.literal("spawn").executes(ctx -> spawn(ctx.getSource())))
                        .then(Commands.literal("status").executes(ctx -> {
                            ctx.getSource().sendSystemMessage(Component.literal(
                                    "titan-status alive=" + titans.size()
                                            + " bars=" + visibleBars()
                                            + " slain=" + slain));
                            return 1;
                        }))));
    }

    private int visibleBars() {
        int visible = 0;
        for (Titan titan : titans) {
            if (titan.bar.isVisible()) {
                visible++;
            }
        }
        return visible;
    }

    private int spawn(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        if (zombie == null) {
            source.sendSystemMessage(Component.literal("titan-spawn failed"));
            return 0;
        }
        zombie.setPos(at.getX() + 0.5D, at.getY() + 1.0D, at.getZ() + 0.5D);
        zombie.setCustomName(Component.literal("Zombie Titan"));
        zombie.setCustomNameVisible(true);
        zombie.setPersistenceRequired();
        zombie.addTag(TAG);

        scale(zombie, Attributes.MAX_HEALTH, HEALTH_MODIFIER, HEALTH_SCALE);
        scale(zombie, Attributes.SCALE, SIZE_MODIFIER, SIZE_SCALE);
        // The attribute change does not heal it; a titan spawns at full health.
        zombie.setHealth(zombie.getMaxHealth());
        level.addFreshEntity(zombie);

        ServerBossEvent bar = new ServerBossEvent(
                UUID.randomUUID(),
                Component.literal("Zombie Titan"),
                BossEvent.BossBarColor.RED,
                BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(1.0F);
        bar.setVisible(true);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            bar.addPlayer(player);
        }
        titans.add(new Titan(zombie, bar));

        source.sendSystemMessage(Component.literal("titan-spawned health="
                + zombie.getMaxHealth() + " scale=" + zombie.getAttribute(Attributes.SCALE).getValue()
                + " uuid=" + zombie.getUUID() + " alive=" + titans.size()));
        return 1;
    }

    /** A multiplicative modifier, so it stacks with whatever the mob already had. */
    private void scale(LivingEntity entity, net.minecraft.core.Holder<
            net.minecraft.world.entity.ai.attributes.Attribute> attribute,
            Identifier id, double factor) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.addPermanentModifier(new AttributeModifier(
                id, factor - 1.0D, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    private void dropHoard(ServerLevel level, BlockPos at) {
        ItemStack shard = new ItemStack(Items.AMETHYST_SHARD, 3);
        shard.set(DataComponents.CUSTOM_NAME,
                Component.literal("Titan Shard"));
        level.addFreshEntity(new ItemEntity(level,
                at.getX() + 0.5D, at.getY() + 1.0D, at.getZ() + 0.5D, shard));
        level.addFreshEntity(new ItemEntity(level,
                at.getX() + 0.5D, at.getY() + 1.0D, at.getZ() + 0.5D,
                new ItemStack(Items.ROTTEN_FLESH, 12)));
    }

    private record Titan(Zombie zombie, ServerBossEvent bar) {
    }
}
