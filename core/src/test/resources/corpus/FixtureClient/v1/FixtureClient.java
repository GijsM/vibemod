package vibemod.fixtureclient;

import com.gijsm.vibemod.api.Mod;
import com.gijsm.vibemod.api.VibeContext;
import com.gijsm.vibemod.api.client.KeyLease;

/**
 * Fixture corpus, the client half. Everything inside {@code ctx.client(...)}
 * comes from {@code sdk-client}, which is otherwise never compiled by a
 * generated source in the headless matrix: the two real client gates need a
 * display, so on a plain CI runner this file is the only thing keeping the
 * client contract honest.
 *
 * <p>Note the shape, which is the shape the prompt teaches: the client block is
 * guarded by {@code hasClient()}, everything it registers is registered through
 * the {@code ClientContext} so the host can tear it down, and the HUD renderer
 * does nothing but draw.
 */
public final class FixtureClient implements Mod {

    private volatile KeyLease lease;

    @Override
    public void onEnable(VibeContext ctx) throws Exception {
        if (!ctx.hasClient()) {
            ctx.log().info("FixtureClient: server-side only here, nothing to do");
            return;
        }

        ctx.client(client -> {
            client.hud("fixture-readout", (canvas, tickDelta) -> {
                String line = String.format("%.1f,%.1f,%.1f  %d fps",
                        client.playerX(), client.playerY(), client.playerZ(), client.fps());
                int width = canvas.textWidth(line);
                canvas.box(4, 4, 8 + width, 18, 0x80000000);
                canvas.outline(4, 4, 8 + width, 18, 0xFFFFFFFF);
                canvas.text(line, 6, 8, 0xFFFFFFFF, true);
                canvas.item("minecraft:compass", canvas.width() - 20, 4);
            });

            lease = client.key("Where am I", "G", () ->
                    client.toast("FixtureClient", client.dimension() + " @ " + client.targetedBlock()));

            client.tick(c -> {
                if (c.inGame() && c.playerHealth() < c.playerMaxHealth() / 4.0f) {
                    c.sound("minecraft:block.note_block.pling", 1.0f, 2.0f);
                }
            });

            client.clientCommand("where", "Print where you are", (c, args) ->
                    c.toast("FixtureClient", "time " + c.worldTime() + " in " + c.dimension()));
        });

        ctx.log().info("FixtureClient enabled");
    }

    @Override
    public void onDisable(VibeContext ctx) {
        KeyLease held = lease;
        if (held != null && held.active()) {
            held.release();
        }
        ctx.log().info("FixtureClient disabled");
    }
}
