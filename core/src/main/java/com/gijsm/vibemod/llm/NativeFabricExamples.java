package com.gijsm.vibemod.llm;

import java.util.List;

/**
 * The worked example for the native Fabric profile (V3 Phase 0 §E).
 *
 * <p>One example, not three, and that is the point of the profile: there is no
 * VibeMod API left to demonstrate. A generated mod is now a plain Fabric mod,
 * so the only thing worth showing is the shape of one — a {@code ModInitializer}
 * registering to two real Fabric events with no VibeMod import anywhere in the
 * file. Everything the model needs beyond that it already knows, because it is
 * ordinary Fabric.
 *
 * <p>{@code BlockTally} is deliberately the same mod as the loader profile's
 * gameplay few-shot, rewritten. Seeing the identical behaviour expressed both
 * ways is the clearest possible statement of what changed, and it keeps the two
 * profiles honestly comparable.
 *
 * <p>Every signature in it was read off the game and API jars rather than
 * recalled: {@code AttackBlockCallback.interact(Player, Level, InteractionHand,
 * BlockPos, Direction) -> InteractionResult}, {@code ServerTickEvents.EndTick
 * .onEndTick(MinecraftServer)}, {@code PlayerList.getPlayers()},
 * {@code Entity.getUUID()}, {@code ServerPlayer.sendSystemMessage(Component)}.
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

    /** The native Fabric profile's few-shots, in prompt order. */
    static final List<PlatformProfile.FewShot> NATIVE_FABRIC_FEW_SHOTS = List.of(
            new PlatformProfile.FewShot(BLOCK_TALLY_USER, BLOCK_TALLY_ASSISTANT));
}
