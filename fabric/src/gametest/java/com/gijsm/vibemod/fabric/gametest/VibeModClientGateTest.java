package com.gijsm.vibemod.fabric.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.gui.screens.dialog.DialogScreen;

import com.gijsm.vibemod.fabric.VibeModFabric;
import com.gijsm.vibemod.fabric.client.FabricClientEventBridge;
import com.gijsm.vibemod.fabric.client.VibeModFabricClient;
import com.gijsm.vibemod.runtime.ModHandle;

/**
 * The CLIENT half of the Phase D acceptance gate (ARCHITECTURE-V2 §9), driving a
 * real Minecraft client through fabric-client-gametest.
 *
 * <p>Four things are proved here, and each is something the dedicated-server
 * gate structurally cannot show:
 *
 * <ol>
 *   <li>the host initialises inside a client process and a singleplayer world's
 *       integrated server ({@code hasClient()} true, unlike the dedicated case);</li>
 *   <li>a canned client-flavor mod — HUD, keybind, client tick, {@code /vibec}
 *       command — compiles, loads, and lands in the live dispatchers and the key
 *       pool, asserted by reading the bridge's own state rather than by looking
 *       at pixels;</li>
 *   <li>disabling it drains every one of those, so the pool and the dispatch
 *       lists come back empty — the §0#10 revocation model, on the surface where
 *       it is hardest;</li>
 *   <li>a deliberately-throwing HUD renderer is detached and its mod
 *       auto-disabled, with the client still running afterwards. §8.1 calls this
 *       out specifically: "a throwing HUD renderer must never crash the render
 *       loop", and the only honest way to test it is to throw on the render
 *       thread of a real client.</li>
 * </ol>
 *
 * <p>Both canned mods are written into the store <em>before</em> the world is
 * created, so restore-on-boot compiles and hot-loads them exactly as generated
 * ones would — no LLM and no API key, the same trick the server gate uses.
 */
public final class VibeModClientGateTest implements FabricClientGameTest {

    /** How long to give an async compile + hot-load before calling it a failure. */
    private static final int LOAD_TIMEOUT_TICKS = 400;

    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        seedMods();

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(20);

            check("the host initialised inside the client's integrated server",
                    VibeModFabric.services() != null);
            if (VibeModFabric.services() == null) {
                report();
                return;
            }
            check("the platform reports a physical client",
                    VibeModFabric.services().platform().hasClient());
            check("the platform does NOT report a dedicated server",
                    !VibeModFabric.services().platform().isDedicatedServer());

            FabricClientEventBridge bridge = VibeModFabricClient.bridge();
            check("the client bridge was installed at client init", bridge != null);
            if (bridge == null) {
                report();
                return;
            }

            awaitLoaded(context, "HudCanary");
            awaitLoaded(context, "AngryHud");

            // Order matters, and not for convenience. Both canned mods register a
            // HUD renderer, and the angry one un-registers ITSELF the first frame
            // it throws — so any assertion on the dispatcher's size has to happen
            // on a known side of that event. The storm test waits for it; only
            // then is "huds" a number about HudCanary alone. (The first version of
            // this test asserted huds=2 and failed, because by the time it looked
            // the system had already done the right thing.)
            testThrowingHudAutoDisables(context, bridge);
            testLeasedKeyFires(context);
            testVibecRoutes(context);
            testRegistrationAndTeardown(context, bridge);
            testKeySlotIsReusableAfterReload(context, bridge);
            testDialogOpensForARealPlayer(context);
            testRenderWatchdogTrips(context, bridge);
        } finally {
            report();
        }
    }

    // ------------------------------------------------------------------ (2)+(3)

    /**
     * The happy path: a client mod's four registration kinds land in the live
     * dispatchers, and disabling it drains all four.
     *
     * <p>Asserted against the bridge's own counters rather than against the
     * screen. Pixel verification of a HUD is possible (the gametest api can
     * screenshot) but it would assert that Minecraft draws text, which is not
     * what is under test — what is under test is that VibeMod's dispatcher holds
     * the mod's renderer and lets go of it again.
     */
    private void testRegistrationAndTeardown(ClientGameTestContext context, FabricClientEventBridge bridge) {
        String state = bridge.describeState();
        check("the HUD dispatcher holds the mod's renderer (" + state + ")", state.contains("huds=1"));
        check("the client-tick dispatcher holds the mod's handler (" + state + ")",
                state.contains("tickers=1"));
        check("the mod's /vibec command registered (" + state + ")", state.contains("clientCommands=1"));
        check("the mod leased exactly one key slot (" + state + ")",
                state.contains("keysLeased=1/" + FabricClientEventBridge.KEY_SLOTS));

        ModHandle handle = VibeModFabric.services().lifecycle().get("HudCanary");
        check("the mod's client registrations are tracked on its handle",
                handle != null && handle.registrationCount() >= 4);

        // Let it actually render for a while: a HUD that is registered but never
        // asked to draw has not been shown to work.
        context.waitTicks(40);
        check("the client survived rendering the mod's HUD",
                context.computeOnClient(client -> client.getFps() >= 0));

        VibeModFabric.services().scheduler().runOnMain(() ->
                VibeModFabric.services().lifecycle().disable("HudCanary"));
        context.waitTicks(20);

        String drained = bridge.describeState();
        // Zero, not one: the angry mod's renderer detached itself before this
        // phase began, so HudCanary's was the only one left to drain.
        check("disabling drained the HUD dispatcher (" + drained + ")", drained.contains("huds=0"));
        check("disabling drained the client-tick dispatcher (" + drained + ")",
                drained.contains("tickers=0"));
        check("disabling removed the /vibec command (" + drained + ")",
                drained.contains("clientCommands=0"));
        check("disabling returned the key slot to the pool (" + drained + ")",
                drained.contains("keysLeased=0/" + FabricClientEventBridge.KEY_SLOTS));
    }

    // ---------------------------------------------------------------------- (4)

    /**
     * The §8.1 requirement, tested the only way it can honestly be tested: a HUD
     * renderer that throws on every frame, on the render thread of a real client.
     *
     * <p>The bridge detaches a throwing entry immediately rather than after the
     * error-storm threshold, so one frame is enough to unhook it; the storm
     * counter still trips and disables the mod. Both halves are asserted, and
     * then the client is asked to keep running — because "did not crash" is the
     * actual requirement.
     */
    private void testThrowingHudAutoDisables(ClientGameTestContext context, FabricClientEventBridge bridge) {
        context.waitTicks(60);

        // That it threw at all proves it was attached AND actually drawn: nothing
        // but the HUD dispatcher ever calls a HudRenderer.
        check("the throwing renderer really ran, and its failure was journalled",
                VibeModFabric.services().errors().distinctCount("AngryHud") > 0);
        ModHandle angry = VibeModFabric.services().lifecycle().get("AngryHud");
        check("the throwing mod was marked degraded", angry != null && angry.degraded());

        String state = bridge.describeState();
        check("the throwing renderer was detached, leaving only the healthy one (" + state + ")",
                state.contains("huds=1"));

        // The whole point: the client is still here.
        context.waitTicks(40);
        check("the client is still running after a HUD renderer threw",
                context.computeOnClient(client -> client.player != null));
    }

    // ---------------------------------------------------------------------- (5)

    /**
     * The singleplayer dialog path, end to end, with a real player.
     *
     * <p>Bare {@code /vibe} needs only {@code vibe.use}, which maps to
     * {@code PermissionLevel.ALL} — so this works without cheats. The player runs
     * it, the host builds a {@code Screen}, {@code LoaderDialogRenderer} turns it
     * into a vanilla {@code Dialog} and sends {@code ClientboundShowDialogPacket}
     * with the dialog inlined as a direct {@code Holder}, and the client puts a
     * {@code DialogScreen} on screen. Every layer of §8.5, with no packet-level
     * inference anywhere.
     */
    private void testDialogOpensForARealPlayer(ClientGameTestContext context) {
        context.runOnClient(client -> client.player.connection.sendCommand("vibe"));
        boolean opened = true;
        try {
            context.waitForScreen(DialogScreen.class);
        } catch (Throwable neverAppeared) {
            opened = false;
        }
        check("bare /vibe opened a native dialog on the client", opened);
        // Leave the screen closed so nothing downstream inherits it. Through the
        // test context, not Minecraft: 26.x has no public Minecraft#setScreen.
        context.setScreen(() -> null);
        context.waitTicks(5);
    }

    /**
     * Pressing the leased key really reaches the mod (§8.2).
     *
     * <p>Three things at once, and none of them provable from the pool's
     * bookkeeping: that {@code "G"} was actually parsed and auto-bound to the
     * slot, that the client-tick dispatcher's {@code consumeClick()} polling sees
     * the press, and that it lands in the mod's own handler. Observed through a
     * marker file for the same reason as {@code /vibec} — the visible effect is
     * a character on the HUD.
     */
    private void testLeasedKeyFires(ClientGameTestContext context) {
        Path marker = FabricLoader.getInstance().getGameDir()
                .resolve("vibemod").resolve("moddata").resolve("HudCanary").resolve("key-pressed");
        try {
            Files.deleteIfExists(marker);
        } catch (IOException ignored) {
            // never written yet
        }
        context.getInput().pressKey(InputConstants.getKey("key.keyboard.g"));
        boolean fired = false;
        for (int i = 0; i < 40 && !fired; i++) {
            context.waitTick();
            fired = Files.isRegularFile(marker);
        }
        check("pressing the auto-bound key reached the mod's handler", fired);
    }

    // ---------------------------------------------------------------------- (6)

    /**
     * {@code /vibec <mod> <command>} actually routes into the mod's handler
     * (§8.3), rather than merely appearing in the registry.
     *
     * <p>Observed through a marker file the canary's handler writes, because the
     * alternative — a toast — is a thing on a screen, and asserting on pixels
     * would be testing that Minecraft draws toasts. The file proves the handler
     * body ran, which is the whole question.
     */
    private void testVibecRoutes(ClientGameTestContext context) {
        Path marker = FabricLoader.getInstance().getGameDir()
                .resolve("vibemod").resolve("moddata").resolve("HudCanary").resolve("vibec-ran");
        try {
            Files.deleteIfExists(marker);
        } catch (IOException ignored) {
            // never written yet
        }
        context.runOnClient(client -> client.player.connection.sendCommand("vibec HudCanary status"));
        boolean ran = false;
        for (int i = 0; i < 60 && !ran; i++) {
            context.waitTick();
            ran = Files.isRegularFile(marker);
        }
        check("/vibec <mod> <command> routed into the mod's handler", ran);
    }

    // ---------------------------------------------------------------------- (7)

    /**
     * The key slot released on disable is genuinely re-leasable (§9: "key pool
     * leases/releases across mod reload").
     *
     * <p>A pool that hands a slot back but never hands it out again would pass
     * the teardown assertions above and still exhaust itself after eight mod
     * reloads.
     */
    private void testKeySlotIsReusableAfterReload(ClientGameTestContext context,
                                                  FabricClientEventBridge bridge) {
        VibeModFabric.services().scheduler().runOnMain(() -> {
            try {
                VibeModFabric.services().lifecycle().enable("HudCanary");
            } catch (Exception e) {
                check("re-enabling the mod after a disable did not throw: " + e, false);
            }
        });
        context.waitTicks(20);
        String state = bridge.describeState();
        check("re-enabling re-leased a key slot from the pool (" + state + ")",
                state.contains("keysLeased=1/" + FabricClientEventBridge.KEY_SLOTS));
        check("re-enabling re-attached the HUD renderer (" + state + ")", state.contains("huds=1"));
    }

    // ---------------------------------------------------------------------- (8)

    /**
     * The render-thread watchdog trips on a slow HUD renderer (§9, §8.4).
     *
     * <p>The distinct failure mode from a throwing renderer: this one never
     * errors, it is simply too slow, and left alone it would drag the frame rate
     * down forever. {@code SlowHud} busy-waits well past the 250ms
     * single-invocation budget, so one frame is enough.
     *
     * <p>This also exercises the piece that made the render watchdog need a
     * scheduler at all: the trip handler disables the mod, and
     * {@code ModLifecycle.disable} asserts it is on the server thread — so the
     * trip has to hop off the render thread to do its job.
     */
    private void testRenderWatchdogTrips(ClientGameTestContext context, FabricClientEventBridge bridge) {
        seedOne("SlowHud", SLOW_HUD_SOURCE, SLOW_HUD_META);
        VibeModFabric.services().scheduler().runOnMain(() ->
                VibeModFabric.services().router().run(consoleSender(), new String[] {"enable", "SlowHud"}));

        awaitLoaded(context, "SlowHud");
        context.waitTicks(80);

        check("the render watchdog auto-disabled the slow mod",
                !VibeModFabric.services().lifecycle().get("SlowHud").enabled());
        check("the watchdog trip was journalled",
                VibeModFabric.services().errors().distinctCount("SlowHud") > 0);
        String state = bridge.describeState();
        check("the slow renderer was drained with its mod (" + state + ")", state.contains("huds=1"));
        check("the client is still running after a HUD renderer overran its budget",
                context.computeOnClient(client -> client.player != null));
    }

    /** A console-shaped {@link com.gijsm.vibemod.platform.Sender} for driving the router. */
    private static com.gijsm.vibemod.platform.Sender consoleSender() {
        return com.gijsm.vibemod.loader.LoaderSender.of(
                VibeModFabric.services().server().createCommandSourceStack(),
                VibeModFabric.services().messenger());
    }

    // ------------------------------------------------------------------ seeding

    /**
     * Writes the two boot-time canned mods into the store before any world
     * exists, so restore-on-boot picks them up. {@code SlowHud} is seeded later,
     * mid-test, because a mod that eats 300ms of every frame from boot would make
     * the rest of the gate crawl.
     */
    private static void seedMods() {
        Path mods = FabricLoader.getInstance().getGameDir().resolve("vibemod").resolve("mods");
        write(mods, "HudCanary", HUD_CANARY_SOURCE, HUD_CANARY_META);
        write(mods, "AngryHud", ANGRY_HUD_SOURCE, ANGRY_HUD_META);
    }

    private static void seedOne(String name, String source, String meta) {
        write(FabricLoader.getInstance().getGameDir().resolve("vibemod").resolve("mods"),
                name, source, meta);
    }

    private static void write(Path mods, String name, String source, String meta) {
        try {
            Path version = mods.resolve(name).resolve("v1");
            Files.createDirectories(version);
            Files.writeString(version.resolve(name + ".java"), source, StandardCharsets.UTF_8);
            Files.writeString(mods.resolve(name).resolve("meta.json"), meta, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void awaitLoaded(ClientGameTestContext context, String modName) {
        for (int i = 0; i < LOAD_TIMEOUT_TICKS; i++) {
            if (VibeModFabric.services().lifecycle().get(modName) != null) {
                return;
            }
            context.waitTick();
        }
        check("the canned mod " + modName + " compiled and hot-loaded", false);
    }

    // ------------------------------------------------------------------ reporting

    private void check(String what, boolean ok) {
        if (ok) {
            System.out.println("  ok: " + what);
        } else {
            System.out.println("  FAIL: " + what);
            failures.add(what);
        }
    }

    private void report() {
        if (failures.isEmpty()) {
            System.out.println("PHASE D CLIENT GATE PASSED");
            return;
        }
        System.out.println(failures.size() + " CLIENT CHECK(S) FAILED");
        throw new AssertionError("Phase D client gate: " + String.join("; ", failures));
    }

    // ------------------------------------------------------------------ the mods

    private static final String HUD_CANARY_SOURCE = """
            package vibemod.hudcanary;

            import java.nio.file.Files;
            import java.nio.file.Path;

            import com.gijsm.vibemod.api.Mod;
            import com.gijsm.vibemod.api.VibeContext;

            /**
             * The client canary: every ClientContext registration kind, once.
             * Exactly the shape the fabric prompt profile's few-shots teach.
             */
            public final class HudCanary implements Mod {

                private volatile long ticks;
                private volatile boolean toggled;

                @Override
                public void onEnable(VibeContext ctx) throws Exception {
                    Path marker = ctx.dataFolder().resolve("vibec-ran");
                    ctx.client(client -> {
                        client.tick(c -> ticks++);
                        client.key("Toggle the canary", "G", () -> {
                            toggled = !toggled;
                            try {
                                Files.writeString(ctx.dataFolder().resolve("key-pressed"),
                                        Boolean.toString(toggled));
                            } catch (Exception ignored) {
                                // the marker is for the gate, not for the mod
                            }
                        });
                        client.clientCommand("status", "Show the canary's state", (c, args) -> {
                            c.toast("HudCanary", "ticks=" + ticks);
                            // A marker the gate can see without looking at pixels.
                            Files.writeString(marker, "ticks=" + ticks);
                        });
                        client.hud("canary", (canvas, tickDelta) -> {
                            String text = "canary " + ticks + (toggled ? " *" : "");
                            canvas.box(2, 2, canvas.textWidth(text) + 6, 14, 0x80000000);
                            canvas.text(text, 4, 4, 0xFFFFFFFF);
                            canvas.outline(2, 2, canvas.textWidth(text) + 6, 14, 0xFF55FF55);
                            canvas.item("minecraft:feather", 2, 16);
                        });
                    });
                    ctx.log().info("HudCanary enabled (hasClient=" + ctx.hasClient() + ")");
                }
            }
            """;

    private static final String SLOW_HUD_SOURCE = """
            package vibemod.slowhud;

            import com.gijsm.vibemod.api.Mod;
            import com.gijsm.vibemod.api.VibeContext;

            /**
             * A HUD renderer that never throws and is simply far too slow — the
             * other half of the §8 crash-proofing, and the one a try/catch cannot
             * catch. It busy-waits well past the 250ms single-invocation budget,
             * so the render-thread watchdog trips on the very first frame.
             */
            public final class SlowHud implements Mod {

                @Override
                public void onEnable(VibeContext ctx) throws Exception {
                    ctx.client(client -> client.hud("molasses", (canvas, tickDelta) -> {
                        long until = System.nanoTime() + 400_000_000L;
                        while (System.nanoTime() < until) {
                            // deliberately burning the render thread
                        }
                        canvas.text("slow", 2, 40, 0xFFFF5555);
                    }));
                    ctx.log().info("SlowHud enabled");
                }
            }
            """;

    private static final String ANGRY_HUD_SOURCE = """
            package vibemod.angryhud;

            import com.gijsm.vibemod.api.Mod;
            import com.gijsm.vibemod.api.VibeContext;

            /**
             * A HUD renderer that throws on every single frame. The point of the
             * gate: the client must survive this, and the mod must not.
             */
            public final class AngryHud implements Mod {

                @Override
                public void onEnable(VibeContext ctx) throws Exception {
                    ctx.client(client -> client.hud("boom", (canvas, tickDelta) -> {
                        throw new IllegalStateException("this renderer is deliberately broken");
                    }));
                    ctx.log().info("AngryHud enabled");
                }
            }
            """;

    private static String meta(String name, String description) {
        return """
                {
                  "schema": 3,
                  "platform": "fabric",
                  "mcVersion": "26.2",
                  "side": "both",
                  "name": "%s",
                  "description": "%s",
                  "usage": "",
                  "manual": "",
                  "icon": "FEATHER",
                  "mainClass": "vibemod.%s.%s",
                  "currentVersion": 1,
                  "enabled": true,
                  "creator": "gate",
                  "versions": [{"version": 1, "prompt": "p", "model": "none", "createdAt": 1,
                                "changelog": "", "kind": "create", "costUsd": 0.0, "requester": "gate"}],
                  "config": [],
                  "configValues": {}
                }
                """.formatted(name, description, name.toLowerCase(java.util.Locale.ROOT), name);
    }

    private static final String HUD_CANARY_META =
            meta("HudCanary", "Every ClientContext registration kind, once.");
    private static final String ANGRY_HUD_META =
            meta("AngryHud", "A HUD renderer that throws on every frame.");
    private static final String SLOW_HUD_META =
            meta("SlowHud", "A HUD renderer that overruns the render-thread budget.");
}
