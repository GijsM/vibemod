package vibemod.titanforge;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.Level;

/**
 * The roster's registry half, written the way the profile teaches: a real
 * registered item with a {@code use} override, and a real registered
 * {@code EntityType} built on a vanilla mob so a renderer already exists.
 *
 * <p>This is the mod the stress brief asked for verbatim, and it is here to find
 * out what this host does with it rather than to work.
 */
public final class TitanForge implements ModInitializer {

    private static final String NS = "vibemod_titanforge";

    public static final Identifier HOOK_ID = Identifier.fromNamespaceAndPath(NS, "grappling_hook");
    public static final Identifier TITAN_ID = Identifier.fromNamespaceAndPath(NS, "zombie_titan");

    public static Item hook;
    public static EntityType<Zombie> titan;

    @Override
    public void onInitialize() {
        ResourceKey<Item> hookKey = ResourceKey.create(Registries.ITEM, HOOK_ID);
        hook = Registry.register(BuiltInRegistries.ITEM, HOOK_ID, new HookItem(
                new Item.Properties().sword(ToolMaterial.IRON, 2.0F, -2.0F).setId(hookKey)));
        System.out.println("titan-forge-item-registered");

        ResourceKey<EntityType<?>> titanKey = ResourceKey.create(Registries.ENTITY_TYPE, TITAN_ID);
        // The explicit witness is not optional: Zombie's constructor takes
        // EntityType<? extends Zombie>, so `of(Zombie::new, ...)` infers
        // EntityType<Entity> and does not compile.
        titan = EntityType.Builder.<Zombie>of(Zombie::new, MobCategory.MONSTER)
                .sized(1.8F, 5.85F)
                .clientTrackingRange(10)
                .build(titanKey);
        Registry.register(BuiltInRegistries.ENTITY_TYPE, TITAN_ID, titan);
        FabricDefaultAttributeRegistry.register(titan, Zombie.createAttributes());
        System.out.println("titan-forge-entity-registered");
    }

    /** The hook's behaviour lives on the item, as the profile's example does. */
    public static final class HookItem extends Item {

        public HookItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            if (!level.isClientSide()) {
                player.setDeltaMovement(player.getLookAngle().scale(1.4D));
                player.hurtMarked = true;
            }
            return InteractionResult.SUCCESS;
        }
    }
}
