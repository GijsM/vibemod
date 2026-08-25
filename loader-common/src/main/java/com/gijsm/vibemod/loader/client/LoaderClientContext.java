package com.gijsm.vibemod.loader.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import com.gijsm.vibemod.api.client.ClientCommandHandler;
import com.gijsm.vibemod.api.client.ClientContext;
import com.gijsm.vibemod.api.client.ClientTickHandler;
import com.gijsm.vibemod.api.client.HudRenderer;
import com.gijsm.vibemod.api.client.KeyLease;
import com.gijsm.vibemod.platform.ClientEventBridge;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModHandle;

/**
 * The {@link ClientContext} a generated mod is handed inside
 * {@code ctx.client(c -> ...)}.
 *
 * <p>Two halves with different jobs. The registration methods delegate to the
 * host's {@link ClientEventBridge} and track what comes back on the mod's
 * {@link ModHandle} as {@link ModHandle.Kind#CLIENT}, so disabling the mod
 * drains its HUD elements, tick handlers, key leases and client commands
 * exactly like its server listeners — one revocation model everywhere (§0#10).
 * The state getters read {@code Minecraft.getInstance()} directly and are
 * render-thread-safe by construction: they only read fields the render thread
 * itself writes, and every one of them answers a sane zero when there is no
 * world.
 *
 * <p>A {@code handle} of {@code null} makes a registration-less context — what
 * tick and command callbacks are handed, since their registrations were tracked
 * when they were made, not when they fire.
 */
public final class LoaderClientContext implements ClientContext {

    private final ClientEventBridge bridge;
    private final ModHandle handle;

    public LoaderClientContext(ClientEventBridge bridge, ModHandle handle) {
        this.bridge = bridge;
        this.handle = handle;
    }

    // ---- registration ----

    @Override
    public void hud(String id, HudRenderer renderer) {
        track(bridge.hud(modName(), id, renderer));
    }

    @Override
    public KeyLease key(String label, String defaultKey, Runnable onPress) {
        KeyLease lease = bridge.leaseKey(modName(), label, defaultKey, onPress);
        // A lease is revocable but is not a Registration; wrap it so the handle's
        // drain releases the slot back to the pool on disable.
        track(Registration.of(lease::release));
        return lease;
    }

    @Override
    public void tick(ClientTickHandler handler) {
        track(bridge.clientTick(modName(), handler));
    }

    @Override
    public void clientCommand(String name, String description, ClientCommandHandler handler) {
        track(bridge.clientCommand(modName(), name, description, handler));
    }

    @Override
    public void sound(String soundId, float volume, float pitch) {
        bridge.playUiSound(soundId, volume, pitch);
    }

    @Override
    public void toast(String title, String body) {
        bridge.toast(title, body);
    }

    private String modName() {
        return handle == null ? "vibemod" : handle.name();
    }

    private void track(Registration registration) {
        if (handle != null) {
            handle.track(ModHandle.Kind.CLIENT, registration);
        }
    }

    // ---- render-thread-safe state ----

    @Override
    public boolean inGame() {
        return bridge.inGame();
    }

    @Override
    public double playerX() {
        LocalPlayer player = player();
        return player == null ? 0 : player.getX();
    }

    @Override
    public double playerY() {
        LocalPlayer player = player();
        return player == null ? 0 : player.getY();
    }

    @Override
    public double playerZ() {
        LocalPlayer player = player();
        return player == null ? 0 : player.getZ();
    }

    @Override
    public float playerHealth() {
        LocalPlayer player = player();
        return player == null ? 0 : player.getHealth();
    }

    @Override
    public float playerMaxHealth() {
        LocalPlayer player = player();
        return player == null ? 0 : player.getMaxHealth();
    }

    @Override
    public String dimension() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? "" : level.dimension().identifier().toString();
    }

    /**
     * The block under the crosshair within reach, or {@code ""}.
     *
     * <p>Reads the client's own cached {@code hitResult} rather than raycasting:
     * the game computes it once per frame anyway, and a HUD renderer doing its
     * own ray trace every frame is exactly the kind of cost the watchdog would
     * eventually trip on.
     */
    @Override
    public String targetedBlock() {
        Minecraft client = Minecraft.getInstance();
        HitResult hit = client.hitResult;
        ClientLevel level = client.level;
        if (level == null || !(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            return "";
        }
        try {
            var state = level.getBlockState(block.getBlockPos());
            var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return id == null ? "" : id.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public int fps() {
        return Minecraft.getInstance().getFps();
    }

    /**
     * Day-time ticks, or -1 when not in a world.
     *
     * <p>{@code Level#getDayTime()} is gone in 26.x; the clock is per-dimension
     * now, and {@code getDefaultClockTime()} is this dimension's own — the
     * closest thing to what a HUD asking for "world time" means. (The other
     * option, {@code getOverworldClockTime()}, is what a clock ITEM shows and
     * deliberately reports the overworld's time from anywhere.)
     */
    @Override
    public long worldTime() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? -1 : level.getDefaultClockTime();
    }

    @Override
    public Object minecraftHandle() {
        return Minecraft.getInstance();
    }

    private static LocalPlayer player() {
        return Minecraft.getInstance().player;
    }
}
