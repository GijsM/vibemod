package com.gijsm.vibemod.llm;

import java.util.List;

/**
 * The worked examples for the native Fabric profile (V3 Phase 0 §E, Phase 1 §F,
 * Phase 2 §E).
 *
 * <p>Three, and each earns its place by showing a shape the others cannot. There is
 * no VibeMod API left to demonstrate — a generated mod is a plain Fabric mod —
 * so what a few-shot is for here is the <em>arrangement</em>: which entrypoint
 * does what, and where the two halves of a mod live.
 *
 * <p>{@code BlockTally} is deliberately the same mod as the loader profile's
 * gameplay few-shot, rewritten. Seeing the identical behaviour expressed both
 * ways is the clearest possible statement of what changed, and it keeps the two
 * profiles honestly comparable.
 *
 * <p>{@code CoordToggle} is the same trick applied to the client half: it is the
 * legacy profile's keybind few-shot, rewritten as an ordinary Fabric mod that
 * implements <em>both</em> entrypoints. It shows all four Phase 1 surfaces in one
 * file — {@code CommandRegistrationCallback} in {@code onInitialize},
 * {@code KeyMappingHelper} and {@code HudElementRegistry} in
 * {@code onInitializeClient} — because the mistake a model actually makes is not
 * getting one of them wrong, it is putting client code in the server entrypoint.
 *
 * <p>Every signature in both was read off the game and API jars rather than
 * recalled, and {@code CoordToggle} is compiled against the real classpath by
 * {@code :fabric:runClientGameTest}, whose {@code NativeClientCanary} is this
 * same mod with marker files added. Notably:
 * {@code AttackBlockCallback.interact(Player, Level, InteractionHand, BlockPos,
 * Direction) -> InteractionResult}; {@code ServerTickEvents.EndTick
 * .onEndTick(MinecraftServer)}; {@code CommandRegistrationCallback.register(
 * CommandDispatcher, CommandBuildContext, Commands.CommandSelection)};
 * {@code KeyMappingHelper.registerKeyMapping(KeyMapping) -> KeyMapping} (in
 * {@code net.fabricmc.fabric.api.client.keymapping.v1} — there is no
 * {@code KeyBindingHelper} in this era); {@code HudElementRegistry.addLast(
 * Identifier, HudElement)} with {@code HudElement.extractRenderState(
 * GuiGraphicsExtractor, DeltaTracker)}; and
 * {@code GuiGraphicsExtractor.text(Font, String, int, int, int)}.
 *
 * <p>{@code RubySword} is Phase 2's resource example and Phase 3's registry one
 * at once, and merging them was deliberate rather than economical. It replaced
 * {@code RubyCharm}, which taught the same six file shapes around a
 * <em>renamed vanilla item</em>; {@code RubySword} teaches them around a real
 * registered one and carries every JSON shape {@code RubyCharm} carried — the
 * recipe, the advancement, the two-file item model, the lang file, the pixel
 * grid — plus {@code Registry.register}, {@code Item.Properties.setId} and an
 * {@code Item} subclass with a {@code use} override. Two few-shots differing
 * only in whether the item is real would have cost 4.5k characters of prompt on
 * every round of every generation to say one thing twice. The one shape that
 * did NOT survive the merge — a recipe result wearing
 * {@code minecraft:custom_name}/{@code lore}/{@code item_model} components,
 * which is still the ONLY answer on a dedicated server — moved into the cheat
 * sheet as a literal, and {@code LlmSelfTest} asserts it is there.
 *
 * <p>Every JSON shape in it was read out of the running game's own data rather
 * than recalled: the recipe from {@code data/minecraft/recipe/golden_apple.json};
 * the advancement from {@code data/minecraft/advancement/story/mine_stone.json};
 * the {@code recipe_id} criterion field off
 * {@code RecipeCraftedTrigger$TriggerInstance}'s codec; and the two-file item
 * model layout ({@code assets/<ns>/items/<name>.json} pointing at
 * {@code assets/<ns>/models/item/<name>.json}) from
 * {@code assets/minecraft/items/diamond_sword.json}, which is a 26.x
 * arrangement a model trained on 1.20 gets wrong.
 *
 * <p>Its Java was compiled against the real Loom classpath with {@code javac}
 * before being embedded, and every 26.2 signature in it was javap'd:
 * {@code Item.Properties.setId(ResourceKey<Item>)} and
 * {@code .sword(ToolMaterial, float, float)} (there is no {@code SwordItem}
 * class in this era at all); {@code Item.use(Level, Player, InteractionHand)
 * -> InteractionResult}; {@code InteractionResult.SUCCESS};
 * {@code Entity.setRemainingFireTicks(int)}.
 */
final class NativeFabricExamples {

    private NativeFabricExamples() {
    }

    static final String BLOCK_TALLY_USER =
            "Create a mod: count how many blocks each player hits and tell them every so often "
                    + "(requested by Steve)";

    static final String BLOCK_TALLY_ASSISTANT = """
            {"plan":{"name":"BlockTally","files":[{"path":"BlockTally.java","purpose":"Mod entry point: counts block hits and announces totals on a timer."}]},"name":"BlockTally","description":"Counts the blocks each player hits and reminds them of the total every ten seconds.","usage":"Punch a few blocks and wait","manual":"## BlockTally\\n\\nCounts every block each player hits and sends them their running total every ten seconds.\\n\\nCounts reset when the server restarts.","changelog":"Players now get a periodic block-hitting tally.","icon":"IRON_PICKAXE","mainClass":"BlockTally","files":[{"path":"BlockTally.java","content":"package vibemod.blocktally;\\n\\nimport java.util.Map;\\nimport java.util.UUID;\\nimport java.util.concurrent.ConcurrentHashMap;\\n\\nimport net.fabricmc.api.ModInitializer;\\nimport net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;\\nimport net.fabricmc.fabric.api.event.player.AttackBlockCallback;\\n\\nimport net.minecraft.network.chat.Component;\\nimport net.minecraft.server.level.ServerPlayer;\\nimport net.minecraft.world.InteractionResult;\\n\\npublic final class BlockTally implements ModInitializer {\\n\\n    private final Map<UUID, Integer> hits = new ConcurrentHashMap<>();\\n    private int ticks;\\n\\n    @Override\\n    public void onInitialize() {\\n        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {\\n            if (player instanceof ServerPlayer serverPlayer) {\\n                hits.merge(serverPlayer.getUUID(), 1, Integer::sum);\\n            }\\n            // PASS lets vanilla carry on; return InteractionResult.FAIL to cancel the hit.\\n            return InteractionResult.PASS;\\n        });\\n        ServerTickEvents.END_SERVER_TICK.register(server -> {\\n            if (++ticks % 200 != 0) {\\n                return;\\n            }\\n            for (ServerPlayer player : server.getPlayerList().getPlayers()) {\\n                int total = hits.getOrDefault(player.getUUID(), 0);\\n                if (total > 0) {\\n                    player.sendSystemMessage(Component.literal(\\"You have hit \\" + total + \\" blocks.\\"));\\n                }\\n            }\\n        });\\n    }\\n}\\n"}]}
            """;

    static final String COORD_TOGGLE_USER =
            "Create a mod: a key that toggles a coordinates readout on screen, plus a command "
                    + "that reminds me which key it is (requested by Alex)";

    static final String COORD_TOGGLE_ASSISTANT = """
            {"plan":{"name":"CoordToggle","files":[{"path":"CoordToggle.java","purpose":"Mod entry point: the /coords command on the server side, and the keybind plus HUD readout on the client side."}]},"name":"CoordToggle","description":"Press a key to show or hide a compact XYZ readout on screen.","usage":"Press the bound key, or run /coords","manual":"## CoordToggle\\n\\nPress the bound key to toggle a compact **XYZ readout** in the corner of the screen. The key comes from a shared pool, so the physical key may not be the one the mod asked for - look under *VibeMod Slot 1* in **Options -> Controls** to see or rebind it.\\n\\n`/coords` works on any side and reminds you which key to press.\\n\\nThe readout is a client feature: it draws on your own screen and does nothing on a dedicated server.","changelog":"Added a toggleable coordinate readout and the /coords reminder command.","icon":"COMPASS","mainClass":"CoordToggle","files":[{"path":"CoordToggle.java","content":"package vibemod.coordtoggle;\\n\\nimport com.mojang.blaze3d.platform.InputConstants;\\n\\nimport net.fabricmc.api.ClientModInitializer;\\nimport net.fabricmc.api.ModInitializer;\\nimport net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;\\nimport net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;\\nimport net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;\\n\\nimport net.minecraft.client.KeyMapping;\\nimport net.minecraft.client.Minecraft;\\nimport net.minecraft.commands.Commands;\\nimport net.minecraft.network.chat.Component;\\nimport net.minecraft.resources.Identifier;\\n\\npublic final class CoordToggle implements ModInitializer, ClientModInitializer {\\n\\n    private static final Identifier HUD_ID =\\n            Identifier.fromNamespaceAndPath(\\"coordtoggle\\", \\"readout\\");\\n\\n    /** Only ever touched on the render thread. */\\n    private boolean visible = true;\\n    private KeyMapping toggle;\\n\\n    @Override\\n    public void onInitialize() {\\n        // Server side. The command is live the moment the mod loads, and gone when it is disabled.\\n        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->\\n                dispatcher.register(Commands.literal(\\"coords\\").executes(ctx -> {\\n                    ctx.getSource().sendSuccess(\\n                            () -> Component.literal(\\"Press your coordinate key to toggle the readout.\\"),\\n                            false);\\n                    return 1;\\n                })));\\n    }\\n\\n    @Override\\n    public void onInitializeClient() {\\n        // Client side, on the render thread. Never touch server state from here.\\n        toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(\\n                \\"key.coordtoggle.toggle\\", InputConstants.Type.KEYSYM,\\n                InputConstants.KEY_G, KeyMapping.Category.MISC));\\n        HudElementRegistry.addLast(HUD_ID, (graphics, delta) -> {\\n            while (toggle.consumeClick()) {\\n                visible = !visible;\\n            }\\n            Minecraft client = Minecraft.getInstance();\\n            if (!visible || client.player == null) {\\n                return;\\n            }\\n            String text = String.format(\\"%.1f %.1f %.1f\\",\\n                    client.player.getX(), client.player.getY(), client.player.getZ());\\n            graphics.fill(2, 2, client.font.width(text) + 6, 14, 0x80000000);\\n            graphics.text(client.font, text, 4, 4, 0xFF55FF55);\\n        });\\n    }\\n}\\n"}]}
            """;



    static final String RUBY_SWORD_USER =
            "Create a mod: a ruby sword that is as strong as iron and sets me on fire when I "
                    + "right-click it (requested by Alex)";

    static final String RUBY_SWORD_ASSISTANT = """
            {"plan":{"name":"RubySword","files":[{"path":"RubySword.java","purpose":"Registers the item and its right-click."},{"path":"data/vibemod_rubysword/recipe/ruby_sword.json","purpose":"How to craft it."},{"path":"data/vibemod_rubysword/advancement/ruby_sword.json","purpose":"Granted on first craft."},{"path":"assets/vibemod_rubysword/lang/en_us.json","purpose":"English text."},{"path":"assets/vibemod_rubysword/items/ruby_sword.json","purpose":"Item model definition."},{"path":"assets/vibemod_rubysword/models/item/ruby_sword.json","purpose":"The model."},{"path":"assets/vibemod_rubysword/textures/item/ruby_sword.png.grid","purpose":"16x16 texture."}]},"name":"RubySword","description":"A real registered Ruby Sword: iron-strength, craftable, and it sets you alight on right-click.","usage":"Craft one, then right-click while holding it","manual":"## RubySword\\n\\nA **Ruby Sword** - a real registered item, craftable from two amethyst shards, a redstone and a stick, or taken from the creative *Ingredients* tab.\\n\\nIt hits like an iron sword, and **right-clicking sets you on fire for two seconds**. Your first craft grants *A Warm Grip*.\\n\\nSingleplayer and LAN worlds only.","changelog":"Added the Ruby Sword item, its recipe, its model and its fiery right-click.","icon":"IRON_SWORD","mainClass":"RubySword","files":[{"path":"RubySword.java","content":"package vibemod.rubysword;\\n\\nimport net.fabricmc.api.ModInitializer;\\n\\nimport net.minecraft.core.Registry;\\nimport net.minecraft.core.registries.BuiltInRegistries;\\nimport net.minecraft.core.registries.Registries;\\nimport net.minecraft.resources.Identifier;\\nimport net.minecraft.resources.ResourceKey;\\nimport net.minecraft.world.InteractionHand;\\nimport net.minecraft.world.InteractionResult;\\nimport net.minecraft.world.entity.player.Player;\\nimport net.minecraft.world.item.Item;\\nimport net.minecraft.world.item.ToolMaterial;\\nimport net.minecraft.world.level.Level;\\n\\npublic final class RubySword implements ModInitializer {\\n\\n    // The namespace here must be the one the resource files use.\\n    public static final Identifier ID =\\n            Identifier.fromNamespaceAndPath(\\"vibemod_rubysword\\", \\"ruby_sword\\");\\n\\n    public static Item rubySword;\\n\\n    @Override\\n    public void onInitialize() {\\n        // setId(...) BEFORE the item exists; .sword(...) instead of a SwordItem class.\\n        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, ID);\\n        rubySword = Registry.register(BuiltInRegistries.ITEM, ID, new RubySwordItem(\\n                new Item.Properties().sword(ToolMaterial.IRON, 4.0F, -2.4F).setId(key)));\\n    }\\n\\n    public static final class RubySwordItem extends Item {\\n\\n        public RubySwordItem(Properties properties) {\\n            super(properties);\\n        }\\n\\n        @Override\\n        public InteractionResult use(Level level, Player player, InteractionHand hand) {\\n            if (!level.isClientSide()) {\\n                player.setRemainingFireTicks(40);\\n            }\\n            return InteractionResult.SUCCESS;\\n        }\\n    }\\n}\\n"},{"path":"data/vibemod_rubysword/recipe/ruby_sword.json","content":"{\\"type\\": \\"minecraft:crafting_shaped\\", \\"key\\": {\\"#\\": \\"minecraft:redstone\\", \\"X\\": \\"minecraft:amethyst_shard\\", \\"S\\": \\"minecraft:stick\\"}, \\"pattern\\": [\\" X \\", \\"X#X\\", \\" S \\"], \\"result\\": {\\"id\\": \\"vibemod_rubysword:ruby_sword\\"}}\\n"},{"path":"data/vibemod_rubysword/advancement/ruby_sword.json","content":"{\\"parent\\": \\"minecraft:adventure/root\\", \\"criteria\\": {\\"crafted\\": {\\"trigger\\": \\"minecraft:recipe_crafted\\", \\"conditions\\": {\\"recipe_id\\": \\"vibemod_rubysword:ruby_sword\\"}}}, \\"display\\": {\\"icon\\": {\\"id\\": \\"vibemod_rubysword:ruby_sword\\"}, \\"title\\": {\\"translate\\": \\"advancements.vibemod_rubysword.ruby_sword.title\\"}, \\"description\\": {\\"translate\\": \\"advancements.vibemod_rubysword.ruby_sword.description\\"}}}\\n"},{"path":"assets/vibemod_rubysword/lang/en_us.json","content":"{\\"item.vibemod_rubysword.ruby_sword\\": \\"Ruby Sword\\", \\"advancements.vibemod_rubysword.ruby_sword.title\\": \\"A Warm Grip\\", \\"advancements.vibemod_rubysword.ruby_sword.description\\": \\"Forge the ruby sword.\\"}\\n"},{"path":"assets/vibemod_rubysword/items/ruby_sword.json","content":"{\\"model\\": {\\"type\\": \\"minecraft:model\\", \\"model\\": \\"vibemod_rubysword:item/ruby_sword\\"}}\\n"},{"path":"assets/vibemod_rubysword/models/item/ruby_sword.json","content":"{\\"parent\\": \\"minecraft:item/handheld\\", \\"textures\\": {\\"layer0\\": \\"vibemod_rubysword:item/ruby_sword\\"}}\\n"},{"path":"assets/vibemod_rubysword/textures/item/ruby_sword.png.grid","content":"{\\"palette\\": {\\".\\": \\"transparent\\", \\"d\\": \\"#5a1010\\", \\"r\\": \\"#b31c1c\\", \\"l\\": \\"#ff6b6b\\", \\"h\\": \\"#8b5a2b\\"}, \\"rows\\": [\\"..............l.\\", \\".............lr.\\", \\"............lrr.\\", \\"...........lrrd.\\", \\"..........lrrd..\\", \\".........lrrd...\\", \\"........lrrd....\\", \\"...h...lrrd.....\\", \\"..hhh.lrrd......\\", \\"...h.lrrd.......\\", \\"..hh.lrd........\\", \\".hh..ldd........\\", \\"hh...d..........\\", \\"h...............\\", \\"................\\", \\"................\\"]}\\n"}]}
            """;

    /** The native Fabric profile's few-shots, in prompt order. */
    static final List<PlatformProfile.FewShot> NATIVE_FABRIC_FEW_SHOTS = List.of(
            new PlatformProfile.FewShot(BLOCK_TALLY_USER, BLOCK_TALLY_ASSISTANT),
            new PlatformProfile.FewShot(COORD_TOGGLE_USER, COORD_TOGGLE_ASSISTANT),
            new PlatformProfile.FewShot(RUBY_SWORD_USER, RUBY_SWORD_ASSISTANT));
}
