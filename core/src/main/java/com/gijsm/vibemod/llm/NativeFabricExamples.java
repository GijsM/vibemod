package com.gijsm.vibemod.llm;

import java.util.List;

/**
 * The worked examples for the native Fabric profile (V3 Phase 0 §E, Phase 1 §F).
 *
 * <p>Two, and each earns its place by showing a shape the other cannot. There is
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

    /** The native Fabric profile's few-shots, in prompt order. */
    static final List<PlatformProfile.FewShot> NATIVE_FABRIC_FEW_SHOTS = List.of(
            new PlatformProfile.FewShot(BLOCK_TALLY_USER, BLOCK_TALLY_ASSISTANT),
            new PlatformProfile.FewShot(COORD_TOGGLE_USER, COORD_TOGGLE_ASSISTANT));
}
