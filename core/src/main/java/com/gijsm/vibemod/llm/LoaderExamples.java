package com.gijsm.vibemod.llm;

import java.util.List;

/**
 * The three worked examples the loader profiles show the model
 * (ARCHITECTURE-V2 §6.2): a HUD timer, a keybind toggle, and a gameplay
 * {@code onBlockBreak} counter.
 *
 * <p>They are chosen to cover exactly the three things a loader mod can do that
 * a Paper mod cannot, and to demonstrate the two rules that catch a model out
 * most often. The HUD and keybind examples both live inside
 * {@code ctx.client(...)} — the only place client code may appear — and both
 * keep their state in a field the render thread and the server thread never
 * share badly. The block-break example shows a curated hook returning
 * {@code false} to cancel, which is how every cancelling hook works and is
 * nothing like Bukkit's {@code setCancelled}.
 *
 * <p>Kept next to {@link PromptExamples} and in the same shape, so
 * {@code LlmSelfTest}'s compile check covers both sets identically.
 */
final class LoaderExamples {

    private LoaderExamples() {
    }

    static final String HUD_USER =
            "Create a mod: show a HUD timer counting how long I've been in this world (requested by Steve)";

    static final String HUD_ASSISTANT = """
            {"plan":{"name":"WorldTimer","files":[{"path":"WorldTimer.java","purpose":"Mod entry point: registers the client HUD and the tick that advances the timer."}]},"name":"WorldTimer","description":"A small HUD timer in the corner counting how long you have been playing this session.","usage":"Look at the top-left corner of the screen","manual":"## WorldTimer\\n\\nDraws a small **session timer** in the corner of the screen, counting up from the moment the mod loaded.\\n\\n### Settings\\n\\n- `x` / `y` - where the timer sits on screen (default **4**, **4**).\\n- `show-background` - draw a translucent box behind the text (default **on**).\\n\\nThis is a client feature: it draws on your own screen and does nothing on a dedicated server.","changelog":"Added a session timer to the HUD.","icon":"CLOCK","mainClass":"WorldTimer","files":[{"path":"WorldTimer.java","content":"package vibemod.worldtimer;\\n\\nimport com.gijsm.vibemod.api.Mod;\\nimport com.gijsm.vibemod.api.VibeContext;\\n\\npublic final class WorldTimer implements Mod {\\n\\n    /** Ticks counted on the client. Only ever touched from client callbacks. */\\n    private volatile long ticks;\\n\\n    @Override\\n    public void onEnable(VibeContext ctx) throws Exception {\\n        ctx.client(client -> {\\n            client.tick(c -> {\\n                if (c.inGame()) {\\n                    ticks++;\\n                }\\n            });\\n            client.hud(\\"timer\\", (canvas, tickDelta) -> {\\n                long seconds = ticks / 20L;\\n                String text = String.format(\\"%d:%02d\\", seconds / 60L, seconds % 60L);\\n                int x = (int) ctx.configInt(\\"x\\");\\n                int y = (int) ctx.configInt(\\"y\\");\\n                if (ctx.configBool(\\"show-background\\")) {\\n                    canvas.box(x - 2, y - 2, x + canvas.textWidth(text) + 2, y + 10, 0x80000000);\\n                }\\n                canvas.text(text, x, y, 0xFFFFFFFF);\\n            });\\n        });\\n        ctx.log().info(\\"WorldTimer enabled.\\");\\n    }\\n}\\n"}],"config":[{"key":"x","type":"integer","default":"4","description":"Horizontal position of the timer.","min":0,"max":400,"step":1},{"key":"y","type":"integer","default":"4","description":"Vertical position of the timer.","min":0,"max":400,"step":1},{"key":"show-background","type":"boolean","default":"true","description":"Draw a translucent box behind the timer."}]}
            """;

    static final String KEYBIND_USER =
            "Create a mod: a key that toggles a coordinates readout on screen (requested by Alex)";

    static final String KEYBIND_ASSISTANT = """
            {"plan":{"name":"CoordToggle","files":[{"path":"CoordToggle.java","purpose":"Mod entry point: leases a key and draws the coordinate readout when it is toggled on."}]},"name":"CoordToggle","description":"Press a key to show or hide a compact XYZ readout on screen.","usage":"Press the bound key (VibeMod Slot 1 in Controls)","manual":"## CoordToggle\\n\\nPress the bound key to toggle a compact **XYZ readout** in the corner of the screen. The key defaults to `G`, and you can rebind it in **Options -> Controls** under *VibeMod Slot 1* - a rebind you make there is never overwritten.\\n\\n### Settings\\n\\n- `start-visible` - whether the readout is on when the mod loads (default **off**).\\n\\nThis is a client feature: it draws on your own screen and does nothing on a dedicated server.","changelog":"Added a toggleable coordinate readout.","icon":"COMPASS","mainClass":"CoordToggle","files":[{"path":"CoordToggle.java","content":"package vibemod.coordtoggle;\\n\\nimport com.gijsm.vibemod.api.Mod;\\nimport com.gijsm.vibemod.api.VibeContext;\\n\\npublic final class CoordToggle implements Mod {\\n\\n    /** Toggled on the render thread, read on the render thread. */\\n    private volatile boolean visible;\\n\\n    @Override\\n    public void onEnable(VibeContext ctx) throws Exception {\\n        visible = ctx.configBool(\\"start-visible\\");\\n        ctx.client(client -> {\\n            client.key(\\"Toggle coordinates\\", \\"G\\", () -> {\\n                visible = !visible;\\n                client.sound(\\"minecraft:ui.button.click\\", 1.0f, 1.0f);\\n            });\\n            client.hud(\\"coords\\", (canvas, tickDelta) -> {\\n                if (!visible || !client.inGame()) {\\n                    return;\\n                }\\n                String text = String.format(\\"%.1f %.1f %.1f\\", client.playerX(), client.playerY(), client.playerZ());\\n                canvas.box(2, canvas.height() - 14, canvas.textWidth(text) + 6, canvas.height() - 2, 0x80000000);\\n                canvas.text(text, 4, canvas.height() - 12, 0xFF55FF55);\\n            });\\n        });\\n        ctx.log().info(\\"CoordToggle enabled.\\");\\n    }\\n}\\n"}],"config":[{"key":"start-visible","type":"boolean","default":"false","description":"Whether the readout is visible when the mod loads."}]}
            """;

    static final String GAMEPLAY_USER =
            "Create a mod: count how many blocks each player breaks and tell them every 100 (requested by Steve)";

    static final String GAMEPLAY_ASSISTANT = """
            {"plan":{"name":"BlockTally","files":[{"path":"BlockTally.java","purpose":"Mod entry point: registers the block-break hook and the /blocks command."}]},"name":"BlockTally","description":"Counts the blocks each player breaks and congratulates them at every milestone.","usage":"/blocks","manual":"## BlockTally\\n\\nKeeps a running count of every block each player breaks, and sends them a short message each time they cross a milestone. Run `/blocks` to see your own total.\\n\\n### Settings\\n\\n- `milestone` - how many blocks between congratulations (default **100**, from 10 up to 10000).\\n\\nCounts reset when the server restarts.","changelog":"Players now get a message every time they cross a block-breaking milestone.","icon":"IRON_PICKAXE","mainClass":"BlockTally","files":[{"path":"BlockTally.java","content":"package vibemod.blocktally;\\n\\nimport java.util.Map;\\nimport java.util.UUID;\\nimport java.util.concurrent.ConcurrentHashMap;\\n\\nimport com.gijsm.vibemod.api.Mod;\\nimport com.gijsm.vibemod.api.VibeContext;\\n\\nimport net.minecraft.network.chat.Component;\\nimport net.minecraft.server.level.ServerPlayer;\\n\\npublic final class BlockTally implements Mod {\\n\\n    private final Map<UUID, Long> broken = new ConcurrentHashMap<>();\\n\\n    @Override\\n    public void onEnable(VibeContext ctx) throws Exception {\\n        ctx.onBlockBreak((player, pos, state) -> {\\n            long total = broken.merge(player.getUUID(), 1L, Long::sum);\\n            long milestone = Math.max(1L, ctx.configInt(\\"milestone\\"));\\n            if (total % milestone == 0L) {\\n                player.sendSystemMessage(Component.literal(\\"That is \\" + total + \\" blocks broken!\\"));\\n            }\\n            // true lets the break happen; return false to cancel it.\\n            return true;\\n        });\\n        ctx.command(\\"blocks\\", \\"Show how many blocks you have broken\\", (src, args) -> {\\n            ServerPlayer player = src.getPlayer();\\n            if (player == null) {\\n                src.sendSystemMessage(Component.literal(\\"Only players have a block tally.\\"));\\n                return;\\n            }\\n            long total = broken.getOrDefault(player.getUUID(), 0L);\\n            src.sendSuccess(() -> Component.literal(\\"You have broken \\" + total + \\" blocks.\\"), false);\\n        });\\n        ctx.log().info(\\"BlockTally enabled.\\");\\n    }\\n}\\n"}],"config":[{"key":"milestone","type":"integer","default":"100","description":"How many blocks between congratulation messages.","min":10,"max":10000,"step":10}]}
            """;

    /** The loader profiles' few-shots, in prompt order. */
    static final List<PlatformProfile.FewShot> LOADER_FEW_SHOTS = List.of(
            new PlatformProfile.FewShot(HUD_USER, HUD_ASSISTANT),
            new PlatformProfile.FewShot(KEYBIND_USER, KEYBIND_ASSISTANT),
            new PlatformProfile.FewShot(GAMEPLAY_USER, GAMEPLAY_ASSISTANT));
}
