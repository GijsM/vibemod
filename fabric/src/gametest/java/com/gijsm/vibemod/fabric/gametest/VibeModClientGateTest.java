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
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.renderer.entity.PigRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.chunk.Strategy;

import com.gijsm.vibemod.fabric.VibeModFabric;
import com.gijsm.vibemod.fabric.client.FabricClientEventBridge;
import com.gijsm.vibemod.fabric.client.FabricClientPacks;
import com.gijsm.vibemod.fabric.client.VibeModFabricClient;
import com.gijsm.vibemod.fabric.mixin.StrategyAccessor;
import com.gijsm.vibemod.fabric.shim.CreativeTabs;
import com.gijsm.vibemod.fabric.shim.EventFanout;
import com.gijsm.vibemod.fabric.shim.PaletteGuard;
import com.gijsm.vibemod.fabric.shim.RegistrySeam;
import com.gijsm.vibemod.fabric.shim.StubBlock;
import com.gijsm.vibemod.runtime.ModHandle;
import com.gijsm.vibemod.store.BlockSchema;
import com.gijsm.vibemod.store.RegistryLedger;

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
 *
 * <h2>V4 Phase 1: blocks</h2>
 *
 * <p>Four more things were added for blocks, and every one of them is here
 * because it is here or nowhere. {@code smoke-fabric.sh} runs on a dedicated
 * server, where registry content is refused outright, so its entire block
 * coverage is the refusal path; {@code scripts/palette-gate.sh} drives real
 * chunks through a real crossing but its canary bypasses the registry seam on
 * purpose and — as its own header says — cannot see steps 2 and 4 of
 * {@code PaletteGuard.cross()} at all, because {@code Shims.clientSeam()} is
 * null there and {@code level.players()} is empty. So:
 *
 * <ol>
 *   <li>a block registered through the <em>real</em> seam path registers,
 *       places, breaks, drops and survives a save/evict/reload round trip —
 *       the happy path, which exists in no other gate;</li>
 *   <li>{@code initCache()} is not optional, asserted both directly (the fields
 *       it fills are read off a live state) and by placing the block beside a
 *       light source and letting the light engine run over it;</li>
 *   <li>the block renders with its <em>own</em> baked model rather than the
 *       missing one, which only a client with real GL and a real model bake can
 *       say;</li>
 *   <li>a palette crossing is two-sided: the client level's own
 *       {@code Strategy} lands on the new width in lockstep with the server's,
 *       the connection survives, and nothing in the client log says the decoder
 *       disagreed about how many longs a section takes.</li>
 * </ol>
 *
 * <p>And the world-protecting one (§9): a block is placed with a known vanilla
 * block two away, the mod is deleted, the world is <b>closed and reopened off
 * disk</b>, and the neighbour has to be exactly what it was. That neighbour
 * assertion is the palette-shift detector.
 *
 * <p><b>What even this gate cannot see</b>, said out loud rather than skipped:
 * a deleted block mod's id cannot actually be made to <em>disappear</em> inside
 * one JVM — {@code MappedRegistry} has no remove and {@code IdMapper} has no
 * remove — so the reopened world decodes its sections against a registry that
 * still holds the original {@code Block} object. What the restart proves is
 * that the pin survives a real save/load of a real world and that nothing
 * shifted; what it cannot reproduce is a fresh process in which the id would be
 * absent unless {@code replayPinnedBlocks} minted a stub for it. That half is
 * covered instead by registering a pinned stub for an id nothing owns and
 * round-tripping a chosen state of it through disk, which is the same codec
 * path a saved chunk takes. A cross-process version of this needs a second
 * launch, which no gate in this repo has.
 */
public final class VibeModClientGateTest implements FabricClientGameTest {

    /**
     * How long to give an async compile + hot-load before calling it a failure.
     * 400 ticks flaked once under machine contention (another Minecraft running;
     * the mod loaded ~15s past the window — STRESS-RESULT.md). This is a patience
     * budget, not an assertion: a genuinely broken load still fails, just later.
     */
    private static final int LOAD_TIMEOUT_TICKS = 1600;

    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        seedMods();

        // Hoisted out of the try-with-resources because the V4 pin gate needs
        // the world AFTER it has been closed: the only honest way to ask "did
        // the pin survive a restart" is to shut the integrated server down, let
        // it write its chunks, and open the same save again.
        TestWorldSave save = null;

        try {
            try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
                save = singleplayer.getWorldSave();
                context.waitTicks(20);

                check("the host initialised inside the client's integrated server",
                        VibeModFabric.services() != null);
                if (VibeModFabric.services() == null) {
                    return;
                }
                check("the platform reports a physical client",
                        VibeModFabric.services().platform().hasClient());
                check("the platform does NOT report a dedicated server",
                        !VibeModFabric.services().platform().isDedicatedServer());

                FabricClientEventBridge bridge = VibeModFabricClient.bridge();
                check("the client bridge was installed at client init", bridge != null);
                if (bridge == null) {
                    return;
                }

                awaitLoaded(context, "HudCanary");
                awaitLoaded(context, "AngryHud");
                awaitLoaded(context, "NativeCanary");

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
                testNativeModRoundTrips(context);
                testRegistrationAndTeardown(context, bridge);
                testKeySlotIsReusableAfterReload(context, bridge);
                testDialogOpensForARealPlayer(context);
                testRenderWatchdogTrips(context, bridge);
                testNativeClientCanary(context, bridge);
                testResourceCanary(context);
                testRegistryCanary(context);

                // ---- V4 Phase 1 ----
                //
                // Order is load-bearing, for one reason: a palette crossing is
                // IRREVERSIBLE for the life of the JVM, because IdMapper has no
                // remove. Everything that wants to measure an un-widened palette
                // — and everything that has to fit inside 26.2's four hundred
                // spare blockstates — runs before it.
                testBlockCanary(context, singleplayer);
                testBlockRendersWithItsOwnModel(context);
                testPinnedStubRoundTripsThroughDisk(context, singleplayer);
                testDeletingABlockModPinsIt(context, singleplayer);
                testPaletteCrossingReachesTheClient(context, singleplayer);
            }

            // Outside the try-with-resources, and therefore after the integrated
            // server has actually stopped and flushed its chunks: the same save
            // directory is opened again. That is as close to a restart as one
            // JVM gets, and it is what §9 asks for.
            testPinSurvivesAWorldRestart(context, save);
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

    // ------------------------------------------------------------- V3 Phase 0

    /**
     * The V3 thesis, inside a real client (Phase 0 §F.4).
     *
     * <p>{@code NativeCanary} is a PLAIN FABRIC MOD — it implements
     * {@code net.fabricmc.api.ModInitializer}, registers to
     * {@code ServerTickEvents.END_SERVER_TICK} and {@code AttackBlockCallback},
     * and imports nothing from VibeMod. It runs at all only because the host
     * rewrote its {@code Event.register} call sites into a shim before
     * {@code defineClass}.
     *
     * <p>The disable half is the part worth a game test. A Fabric {@code Event}
     * cannot be unsubscribed, so "it stopped ticking" is a claim that is simply
     * false unless the seam and the fanout both work — and the marker file is
     * checked for ABSENCE after a deliberate delete, which no amount of stale
     * state can fake.
     */
    private void testNativeModRoundTrips(ClientGameTestContext context) {
        Path marker = Path.of("vibemod", "native-canary-ticks");
        EventFanout fanout = VibeModFabric.eventFanout();
        check("the process-lived event fanout was installed at mod init", fanout != null);
        if (fanout == null) {
            return;
        }

        check("a plain Fabric mod's END_SERVER_TICK subscription dispatches",
                awaitMarker(context, marker, true));
        String live = fanout.describeState();
        check("the fanout holds its tick subscription (" + live + ")",
                live.contains("ServerTickEvents.EndTick=1"));
        check("the fanout holds its attack-block subscription (" + live + ")",
                live.contains("AttackBlockCallback=1"));

        VibeModFabric.services().scheduler().runOnMain(() ->
                VibeModFabric.services().lifecycle().disable("NativeCanary"));
        context.waitTicks(20);

        String drained = fanout.describeState();
        check("disabling drained the tick subscription to zero (" + drained + ")",
                drained.contains("ServerTickEvents.EndTick=0"));
        check("disabling drained the attack-block subscription to zero (" + drained + ")",
                drained.contains("AttackBlockCallback=0"));

        // Absence, not staleness: delete the marker and give it twice as long as
        // it needs to reappear.
        deleteMarker(marker);
        context.waitTicks(60);
        check("a disabled native mod really stops running", !Files.isRegularFile(marker));

        VibeModFabric.services().scheduler().runOnMain(() -> {
            try {
                VibeModFabric.services().lifecycle().enable("NativeCanary");
            } catch (Exception e) {
                check("re-enabling the native mod did not throw: " + e, false);
            }
        });
        context.waitTicks(20);

        String back = fanout.describeState();
        check("re-enabling re-subscribed through the same permanent fanout (" + back + ")",
                back.contains("ServerTickEvents.EndTick=1"));
        check("and the mod is dispatching again", awaitMarker(context, marker, true));
    }

    /** Waits up to {@link #LOAD_TIMEOUT_TICKS} for the marker to reach {@code present}. */
    private static boolean awaitMarker(ClientGameTestContext context, Path marker, boolean present) {
        for (int i = 0; i < LOAD_TIMEOUT_TICKS; i++) {
            if (Files.isRegularFile(marker) == present) {
                return true;
            }
            context.waitTick();
        }
        return false;
    }

    private static void deleteMarker(Path marker) {
        try {
            Files.deleteIfExists(marker);
        } catch (IOException ignored) {
            // never written yet
        }
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

    // ------------------------------------------------------------- V3 Phase 1

    /**
     * The Phase 1 thesis, in one mod: everything §A–§E promised, on a real
     * client, in a mod with no VibeMod import in it (§G).
     *
     * <p>It is seeded and enabled <b>mid-session</b>, which is not a convenience
     * — it is the load case Phase 0 could implement but not gate. The world has
     * been running for thousands of ticks by now, so {@code SERVER_STARTING} has
     * long since fired and {@code CommandRegistrationCallback} fired before this
     * mod's bytes existed. Both have to be handed over after the fact or the mod
     * is dead on arrival, and both are asserted here by a marker file the mod
     * itself writes.
     *
     * <p>Then, in order: the client entrypoint ran on the render thread; the
     * keybind lease came out of the same eight-slot pool the v2 surface uses;
     * the HUD element draws frames; a client event dispatches through the
     * fanout; the mod's Brigadier command runs; the mod's own {@code Screen}
     * opens. And then the mod is disabled and every one of those goes away —
     * including the screen, which is the only one of them the player would
     * otherwise be left staring at.
     */
    private void testNativeClientCanary(ClientGameTestContext context, FabricClientEventBridge bridge) {
        Path dir = Path.of("vibemod", "nativeclient");
        for (String marker : new String[] {"server-starting", "client-init", "command-ran",
                "key-pressed", "hud-frames"}) {
            deleteMarker(dir.resolve(marker));
        }

        seedOne("NativeClientCanary", NATIVE_CLIENT_SOURCE, NATIVE_CLIENT_META);
        VibeModFabric.services().scheduler().runOnMain(() -> VibeModFabric.services().router()
                .run(consoleSender(), new String[] {"enable", "NativeClientCanary"}));
        awaitLoaded(context, "NativeClientCanary");
        context.waitTicks(40);

        EventFanout fanout = VibeModFabric.eventFanout();

        // §A's Phase-0 leftover, finally gated: a mod loaded mid-session gets
        // SERVER_STARTING replayed, because it genuinely missed it.
        check("SERVER_STARTING was replayed for a mod enabled mid-session",
                awaitMarker(context, dir.resolve("server-starting"), true));
        // §B: the second entrypoint, run on the render thread after the load.
        check("the mod's ClientModInitializer half ran",
                awaitMarker(context, dir.resolve("client-init"), true));

        String state = bridge.describeState();
        check("its HudElement landed in the host's HUD pipeline (" + state + ")",
                state.contains("nativeHuds=1"));
        check("its keybind leased a second slot from the same pool (" + state + ")",
                state.contains("keysLeased=2/" + FabricClientEventBridge.KEY_SLOTS));

        String fan = fanout.describeState();
        check("its client tick event went through the fanout (" + fan + ")",
                fan.contains("ClientTickEvents.EndTick=1"));
        check("its command is tracked by name (" + fan + ")", fan.contains("/nativecmd"));

        // §D: registered is not drawn. The marker is written from inside the
        // element, so it only exists if the host really called it.
        check("the mod's HUD element is being drawn every frame",
                awaitMarker(context, dir.resolve("hud-frames"), true));

        // §C: the pooled mapping the shim handed back really polls.
        context.getInput().pressKey(InputConstants.getKey("key.keyboard.h"));
        check("pressing the leased key reached the mod's own KeyMapping",
                awaitMarker(context, dir.resolve("key-pressed"), true));

        // §A: a Brigadier command the mod built itself, in the live dispatcher.
        context.runOnClient(client -> client.player.connection.sendCommand("nativecmd"));
        check("the mod's own Brigadier command ran",
                awaitMarker(context, dir.resolve("command-ran"), true));
        // §E: and it opened a Screen the mod defined.
        check("the command opened the mod's own Screen",
                awaitScreen(context, "NativeClientCanary$CanaryScreen", true));

        // ---- and now take it all away ----
        VibeModFabric.services().scheduler().runOnMain(() ->
                VibeModFabric.services().lifecycle().disable("NativeClientCanary"));
        context.waitTicks(20);

        check("disabling closed the screen the mod had defined",
                awaitScreen(context, "NativeClientCanary$CanaryScreen", false));

        String drained = bridge.describeState();
        check("disabling detached its HudElement (" + drained + ")", drained.contains("nativeHuds=0"));
        check("disabling returned its key slot to the pool (" + drained + ")",
                drained.contains("keysLeased=1/" + FabricClientEventBridge.KEY_SLOTS));
        String drainedFan = fanout.describeState();
        check("disabling drained its client tick subscription (" + drainedFan + ")",
                drainedFan.contains("ClientTickEvents.EndTick=0"));
        check("disabling forgot its command (" + drainedFan + ")", !drainedFan.contains("/nativecmd"));

        // Absence, not staleness: delete both markers and give the mod every
        // chance to write them again.
        deleteMarker(dir.resolve("hud-frames"));
        deleteMarker(dir.resolve("command-ran"));
        context.runOnClient(client -> client.player.connection.sendCommand("nativecmd"));
        context.waitTicks(60);
        check("a disabled mod's HUD element really stops drawing",
                !Files.isRegularFile(dir.resolve("hud-frames")));
        check("a disabled mod's command is really gone from the dispatcher",
                !Files.isRegularFile(dir.resolve("command-ran")));

        // ---- and give it back ----
        VibeModFabric.services().scheduler().runOnMain(() -> {
            try {
                VibeModFabric.services().lifecycle().enable("NativeClientCanary");
            } catch (Exception e) {
                check("re-enabling the native client mod did not throw: " + e, false);
            }
        });
        context.waitTicks(40);
        context.runOnClient(client -> client.player.connection.sendCommand("nativecmd"));
        check("re-enabling put the command back",
                awaitMarker(context, dir.resolve("command-ran"), true));
        check("re-enabling re-attached the HUD element",
                awaitMarker(context, dir.resolve("hud-frames"), true));
        String back = bridge.describeState();
        check("re-enabling re-leased a key slot (" + back + ")",
                back.contains("keysLeased=2/" + FabricClientEventBridge.KEY_SLOTS));

        // Leave nothing on screen for whatever runs next.
        context.setScreen(() -> null);
        context.waitTicks(5);
    }

    // ------------------------------------------------------------------ V3 Phase 2

    /**
     * The resource half, in a real client (V3 Phase 2 §D/§F).
     *
     * <p>Seeded mid-session, so the whole channel runs live: the mod's
     * {@code data/**} becomes a world datapack, its {@code assets/**} are merged
     * into VibeMod's runtime resource pack, the pack joins the client's
     * {@code PackRepository} through the accessor mixin, the coordinator
     * debounces one reload of each side, and then a model, a texture and a
     * translation that did not exist when the client started are resolvable by
     * the game's own resource manager.
     *
     * <p>Then it is disabled, and all of it goes — asserted as ABSENCE after a
     * second reload, which is the only form of that claim worth anything.
     */
    private void testResourceCanary(ClientGameTestContext context) {
        String ns = "vibemod_resourceclientcanary";

        FabricClientPacks packs = VibeModFabricClient.packs();
        check("the runtime resource pack was built at client init", packs != null);
        if (packs == null) {
            return;
        }
        check("and it started empty (the stale guard ran)", packs.filesOf("ResourceClientCanary").isEmpty());

        seedWithResources("ResourceClientCanary", RESOURCE_CANARY_SOURCE, RESOURCE_CANARY_META,
                java.util.Map.of(
                        "data/" + ns + "/recipe/ruby.json", RESOURCE_CANARY_RECIPE.replace("NS", ns),
                        "assets/" + ns + "/lang/en_us.json",
                        "{\"item." + ns + ".ruby\": \"Client Ruby\"}\n",
                        "assets/" + ns + "/models/item/ruby.json",
                        "{\"parent\":\"minecraft:item/generated\",\"textures\":"
                                + "{\"layer0\":\"" + ns + ":item/ruby\"}}\n",
                        "assets/" + ns + "/textures/item/ruby.png.grid", RESOURCE_CANARY_GRID));

        VibeModFabric.services().scheduler().runOnMain(() -> VibeModFabric.services().router()
                .run(consoleSender(), new String[] {"enable", "ResourceClientCanary"}));
        awaitLoaded(context, "ResourceClientCanary");

        Identifier model = Identifier.fromNamespaceAndPath(ns, "models/item/ruby.json");
        Identifier texture = Identifier.fromNamespaceAndPath(ns, "textures/item/ruby.png");

        check("the pack registered itself in the client's PackRepository",
                awaitClient(context, client -> client.getResourcePackRepository()
                        .getAvailableIds().contains(FabricClientPacks.PACK_ID), true));
        check("and the client SELECTED it (required=true, so no operator has to)",
                context.computeOnClient(client -> client.getResourcePackRepository()
                        .getSelectedIds().contains(FabricClientPacks.PACK_ID)));
        check("the mod's files are tracked for exact cleanup (" + packs.describeState() + ")",
                packs.filesOf("ResourceClientCanary").size() == 3);

        // Waiting for the reload to FINISH, not to start, and the difference is
        // load-bearing: ReloadableResourceManager swaps its pack list at the
        // beginning of a reload, so getResource() answers from the new packs
        // immediately while the reload listeners — LanguageManager among them —
        // have not run yet. The first run of this gate asserted too early and
        // saw a model that resolved next to a translation key that did not.
        check("the client resource reload completed",
                awaitTrue(context, () -> !VibeModFabric.services().reloads()
                        .describeState().contains("clientReloads=0")));
        check("the mod's model resolves through the game's own resource manager",
                awaitClient(context, client -> client.getResourceManager()
                        .getResource(model).isPresent(), true));
        check("its .png.grid was encoded into a real PNG the client can find",
                context.computeOnClient(client -> client.getResourceManager()
                        .getResource(texture).isPresent()));
        check("the PNG really is a PNG (signature read back off the resource)",
                context.computeOnClient(client -> {
                    try (var in = client.getResourceManager().getResourceOrThrow(texture).open()) {
                        byte[] head = in.readNBytes(8);
                        return head.length == 8 && (head[0] & 0xff) == 0x89 && head[1] == 'P'
                                && head[2] == 'N' && head[3] == 'G';
                    } catch (Exception e) {
                        return false;
                    }
                }));
        check("its lang file translates a key that did not exist at client start",
                "Client Ruby".equals(context.computeOnClient(
                        client -> net.minecraft.client.resources.language.I18n.get("item." + ns + ".ruby"))));

        // The server half, in the same process: a datapack in the integrated
        // server's world, and a recipe in the live manager.
        check("its data/** became a world datapack",
                java.nio.file.Files.isDirectory(VibeModFabric.services().server()
                        .getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR)
                        .resolve("vibemod-resourceclientcanary")));
        check("and its recipe reached the integrated server's recipe manager",
                awaitServerRecipe(context, ns + ":ruby", true));

        String state = VibeModFabric.services().reloads().describeState();
        check("the coordinator ran a reload on each side (" + state + ")",
                !state.contains("serverReloads=0") && !state.contains("clientReloads=0"));
        check("and it is idle afterwards (" + state + ")",
                state.contains("serverDirty=false") && state.contains("clientDirty=false"));

        // ---- and now take it all away ----
        VibeModFabric.services().scheduler().runOnMain(() ->
                VibeModFabric.services().lifecycle().disable("ResourceClientCanary"));

        check("disabling forgot the mod's files",
                awaitTrue(context, () -> packs.filesOf("ResourceClientCanary").isEmpty()));
        check("disabling removed the world datapack at once",
                awaitTrue(context, () -> !java.nio.file.Files.isDirectory(VibeModFabric.services().server()
                        .getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR)
                        .resolve("vibemod-resourceclientcanary"))));
        // A SECOND completed client reload, not just a deleted file: without one,
        // "the resource is gone" would only be saying that PathPackResources
        // cannot open a path that no longer exists, which was never in doubt.
        check("the teardown ran a second client reload",
                awaitTrue(context, () -> VibeModFabric.services().reloads()
                        .describeState().contains("clientReloads=2")));
        check("the model is gone from the client after the teardown reload",
                awaitClient(context, client -> client.getResourceManager()
                        .getResource(model).isPresent(), false));
        check("the texture is gone too",
                context.computeOnClient(client -> !client.getResourceManager()
                        .getResource(texture).isPresent()));
        check("and the translation fell back to its key",
                ("item." + ns + ".ruby").equals(context.computeOnClient(
                        client -> net.minecraft.client.resources.language.I18n.get("item." + ns + ".ruby"))));
        check("and the recipe is gone from the integrated server",
                awaitServerRecipe(context, ns + ":ruby", false));
        check("the pack itself is still registered, and simply contributes nothing",
                context.computeOnClient(client -> client.getResourcePackRepository()
                        .getAvailableIds().contains(FabricClientPacks.PACK_ID)));
        String after = VibeModFabric.services().reloads().describeState();
        check("the coordinator owns no packs and is idle again (" + after + ")",
                after.contains("ownedPacks=0") && after.contains("serverDirty=false")
                        && after.contains("clientDirty=false"));
    }

    /** Polls a client-thread predicate until it answers {@code want}. */
    private static boolean awaitClient(ClientGameTestContext context,
                                       java.util.function.Predicate<net.minecraft.client.Minecraft> test,
                                       boolean want) {
        for (int i = 0; i < 600; i++) {
            if (context.computeOnClient(test::test) == want) {
                return true;
            }
            context.waitTick();
        }
        return false;
    }

    /** Polls a plain predicate on the test thread until it is true. */
    private boolean awaitTrue(ClientGameTestContext context, java.util.function.BooleanSupplier test) {
        for (int i = 0; i < 600; i++) {
            if (test.getAsBoolean()) {
                return true;
            }
            context.waitTick();
        }
        return false;
    }

    /** Polls the integrated server's recipe manager for an id until it is (not) there. */
    private boolean awaitServerRecipe(ClientGameTestContext context, String id, boolean want) {
        for (int i = 0; i < 600; i++) {
            boolean found = false;
            for (var holder : VibeModFabric.services().server().getRecipeManager().getRecipes()) {
                if (holder.id().identifier().toString().equals(id)) {
                    found = true;
                    break;
                }
            }
            if (found == want) {
                return true;
            }
            context.waitTick();
        }
        return false;
    }

    /** Waits for the open screen's class name to (not) contain {@code needle}. */
    private static boolean awaitScreen(ClientGameTestContext context, String needle, boolean present) {
        for (int i = 0; i < 120; i++) {
            String open = context.computeOnClient(client ->
                    client.gui.screen() == null ? "" : client.gui.screen().getClass().getName());
            if (open.contains(needle) == present) {
                return true;
            }
            context.waitTick();
        }
        return false;
    }

    /** A console-shaped {@link com.gijsm.vibemod.platform.Sender} for driving the router. */
    private static com.gijsm.vibemod.platform.Sender consoleSender() {
        return com.gijsm.vibemod.loader.LoaderSender.of(
                VibeModFabric.services().server().createCommandSourceStack(),
                VibeModFabric.services().messenger());
    }

    // ------------------------------------------------------------------ V3 Phase 3

    /**
     * A real item and a real entity type, registered at runtime by a plain
     * Fabric mod, in a real client (V3 Phase 3 §A/§B/§D).
     *
     * <p>This is the phase's thesis test and every assertion in it is about the
     * running game rather than about VibeMod's bookkeeping: the id is in
     * {@code BuiltInRegistries}, the components are bound (so a stack can
     * exist at all), the model and texture resolve through the game's own
     * resource manager, the game's own recipe lookup finds the mod's recipe for
     * a real 3x3 grid and assembles the registered item out of it, a real
     * right-click reaches the {@code use} override the mod wrote, the item is in
     * a creative tab, and the runtime-registered entity type spawns and has a
     * renderer.
     *
     * <p>Then it is disabled, and the honest half: the recipe goes, the creative
     * tab entry goes, the command goes — and the ITEM STAYS, because
     * {@code MappedRegistry} has no remove and pretending otherwise would
     * corrupt the save. The ledger is what says so out loud.
     */
    private void testRegistryCanary(ClientGameTestContext context) {
        String ns = "vibemod_registryclientcanary";
        Path dir = Path.of("vibemod", "registrycanary");
        for (String marker : new String[] {
                "use-fired", "use-fired-client", "command-ran", "renderer-registered"}) {
            deleteMarker(dir.resolve(marker));
        }

        RegistrySeam seam = VibeModFabric.registrySeam();
        check("the registry seam was installed at mod init", seam != null);
        if (seam == null) {
            return;
        }
        check("and it starts holding nothing (" + seam.describeState() + ")",
                seam.describeState().contains("registryItems=0"));

        int reloadsBefore = clientReloads();
        seedWithResources("RegistryClientCanary", REGISTRY_CANARY_SOURCE, REGISTRY_CANARY_META,
                java.util.Map.of(
                        "data/" + ns + "/recipe/ruby_sword.json",
                        REGISTRY_CANARY_RECIPE.replace("NS", ns),
                        "assets/" + ns + "/lang/en_us.json",
                        "{\"item." + ns + ".ruby_sword\": \"Ruby Sword\"}\n",
                        "assets/" + ns + "/items/ruby_sword.json",
                        "{\"model\":{\"type\":\"minecraft:model\",\"model\":\""
                                + ns + ":item/ruby_sword\"}}\n",
                        "assets/" + ns + "/models/item/ruby_sword.json",
                        "{\"parent\":\"minecraft:item/handheld\",\"textures\":{\"layer0\":\""
                                + ns + ":item/ruby_sword\"}}\n",
                        "assets/" + ns + "/textures/item/ruby_sword.png.grid", REGISTRY_CANARY_GRID));

        VibeModFabric.services().scheduler().runOnMain(() -> VibeModFabric.services().router()
                .run(consoleSender(), new String[] {"enable", "RegistryClientCanary"}));
        awaitLoaded(context, "RegistryClientCanary");

        Identifier itemId = Identifier.fromNamespaceAndPath(ns, "ruby_sword");
        Identifier entityId = Identifier.fromNamespaceAndPath(ns, "ruby_pig");

        // ---- the registry itself ----
        check("the item is in the game's own item registry",
                BuiltInRegistries.ITEM.containsKey(itemId));
        check("and the registry hands back the MOD's own subclass, not a vanilla stand-in",
                BuiltInRegistries.ITEM.getValue(itemId).getClass().getName()
                        .startsWith("vibemod.registryclientcanary."));
        check("the entity type is in the game's own entity registry",
                BuiltInRegistries.ENTITY_TYPE.containsKey(entityId));
        check("its default attributes were registered",
                net.minecraft.world.entity.ai.attributes.DefaultAttributes
                        .hasSupplier(BuiltInRegistries.ENTITY_TYPE.getValue(entityId)));

        // Components are the thing a registration alone does NOT give you: the
        // initializer Item.<init> appended is bound by the window's repair pass
        // (and again by the next datapack reload). Without it, making a stack
        // throws "Components not bound yet" and nothing else below is reachable.
        check("its data components were bound, so an ItemStack of it can exist",
                context.computeOnClient(client -> {
                    try {
                        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(itemId));
                        return !stack.isEmpty() && stack.getMaxStackSize() > 0;
                    } catch (Throwable t) {
                        return false;
                    }
                }));

        String state = seam.describeState();
        check("the seam counts what it registered (" + state + ")",
                state.contains("registryItems=1") && state.contains("registryEntityTypes=1")
                        && state.contains("registryAttributes=1"));
        check("and the ledger recorded both ids (" + state + ")",
                state.contains("ledgerIds=2") && state.contains("ledgerTombstones=0"));

        // ---- the creative menu ----
        check("the item is in the creative INGREDIENTS tab",
                awaitTrue(context, () -> tabContains(itemId)));

        // ---- the resources that make it look like anything ----
        check("a NEW client resource reload completed for this mod",
                awaitTrue(context, () -> clientReloads() > reloadsBefore));
        Identifier modelDefinition = Identifier.fromNamespaceAndPath(ns, "items/ruby_sword.json");
        Identifier texture = Identifier.fromNamespaceAndPath(ns, "textures/item/ruby_sword.png");
        check("its item-model DEFINITION resolves (26.x's assets/<ns>/items/<id>.json)",
                awaitClient(context, client -> client.getResourceManager()
                        .getResource(modelDefinition).isPresent(), true));
        check("and its texture is a real PNG the client can find",
                context.computeOnClient(client -> client.getResourceManager()
                        .getResource(texture).isPresent()));
        check("its name translates from the lang key the item id derives",
                "Ruby Sword".equals(context.computeOnClient(client ->
                        net.minecraft.client.resources.language.I18n.get("item." + ns + ".ruby_sword"))));

        // ---- the recipe, through the game's own lookup ----
        check("the game's own recipe lookup finds the mod's recipe and assembles the "
                        + "runtime-registered item out of it",
                awaitTrue(context, () -> craftsToRegisteredItem(itemId)));

        // ---- a real right-click on a real stack ----
        //
        // The reload the registration asked for puts a LoadingOverlay up, and
        // while one is showing the client neither applies inventory updates nor
        // routes mouse buttons to keybinds (MouseHandler.onButton's game-input
        // branch is guarded on `gui.overlay() == null`). The first version of
        // this test clicked straight through that and read
        // `hand=0 minecraft:air useDown=false` for its trouble.
        check("the client is quiet enough to receive a click before the interaction test",
                awaitQuietClient(context));
        context.runOnClient(client -> client.player.connection.sendCommand("regcanary"));
        check("the mod's command ran and put the item in the player's hand",
                awaitMarker(context, dir.resolve("command-ran"), true));
        check("and the CLIENT sees the runtime-registered item in that hand",
                awaitClient(context, client -> client.player != null
                        && itemId.equals(BuiltInRegistries.ITEM
                                .getKey(client.player.getMainHandItem().getItem())), true));
        // Look at the sky: with a block under the crosshair the use key becomes
        // Item.useOn, and it is Item.use that this mod overrides.
        // MouseHandler.onButton routes a click to the open Screen when there is
        // one and to the game only when there is not, so an earlier test's
        // leftover screen would swallow this silently.
        context.setScreen(() -> null);
        context.getInput().lookAt(0.0F, -90.0F);
        context.waitTicks(5);
        // The right mouse button itself, not the keybind that happens to be
        // bound to it: holdKey(keyUse) resolves the mapping's bound key, and one
        // more layer of indirection is one more thing that can be the reason a
        // click did not land.
        context.getInput().holdMouseFor(1, 10);
        check("a real right-click reached the item subclass's own use() override",
                awaitMarker(context, dir.resolve("use-fired"), true));

        // ---- the entity ----
        check("the mod's client half registered a renderer for its entity type",
                awaitMarker(context, dir.resolve("renderer-registered"), true));
        check("the runtime-registered entity type spawned into the world",
                awaitTrue(context, () -> liveCanaryEntity(entityId) != null));
        check("and the client has a REAL renderer for it after the resource reload "
                        + "(late EntityRendererRegistry.register works)",
                awaitClient(context, client -> {
                    net.minecraft.world.entity.Entity entity = clientCanaryEntity(client, ns);
                    return entity != null && PigRenderer.class.isInstance(
                            client.getEntityRenderDispatcher().getRenderer(entity));
                }, true));

        // ---- and now take it away ----
        deleteMarker(dir.resolve("use-fired"));
        VibeModFabric.services().scheduler().runOnMain(() ->
                VibeModFabric.services().lifecycle().disable("RegistryClientCanary"));
        context.waitTicks(20);

        check("disabling took the item out of the creative tab",
                awaitTrue(context, () -> !tabContains(itemId)));
        check("disabling removed the recipe with the rest of the datapack",
                awaitServerRecipe(context, ns + ":ruby_sword", false));
        check("disabling removed the mod's command",
                VibeModFabric.services().server().getCommands().getDispatcher()
                        .getRoot().getChild("regcanary") == null);
        check("the seam holds nothing for it any more (" + seam.describeState() + ")",
                seam.describeState().contains("registryItems=0")
                        && seam.describeState().contains("registryAttributes=0"));

        // The honest half. There is no MappedRegistry.remove, so the id stays —
        // and the ledger is where that is written down rather than hidden.
        check("the item is STILL in the registry, because a registry has no remove",
                BuiltInRegistries.ITEM.containsKey(itemId));
        check("and the ledger still lists it as this mod's, live",
                seam.ledger() != null && seam.ledger().entriesOf("RegistryClientCanary").size() == 2
                        && !seam.ledger().isTombstoned("RegistryClientCanary"));
        check("a disabled mod's item does not crash the client that is holding one",
                context.computeOnClient(client -> {
                    try {
                        return !new ItemStack(BuiltInRegistries.ITEM.getValue(itemId)).isEmpty();
                    } catch (Throwable t) {
                        return false;
                    }
                }));

        // And the limitation, pinned rather than glossed. §D hoped a disabled
        // mod's item would stop responding; it does not, and it cannot: the game
        // calls Item.use on the object in the registry directly, with no host
        // frame in between to drain. What IS gone is every way to GET one (the
        // recipe, the creative tab) and everything the item's code reaches
        // through the host (its command, its events). Asserted positively so
        // that if this ever changes, the gate says so instead of going quiet.
        check("the client is quiet again after the teardown's reloads",
                awaitQuietClient(context));
        check("the player is still holding the disabled mod's item",
                awaitClient(context, client -> client.player != null
                        && itemId.equals(BuiltInRegistries.ITEM
                                .getKey(client.player.getMainHandItem().getItem())), true));
        deleteMarker(dir.resolve("use-fired-client"));
        context.setScreen(() -> null);
        context.getInput().lookAt(0.0F, -90.0F);
        context.waitTicks(5);
        context.getInput().holdMouseFor(1, 10);
        check("a disabled mod's item subclass STILL runs its own use() — there is no seam "
                        + "between the game and an object it already holds",
                awaitMarker(context, dir.resolve("use-fired"), true));

        // ---- unload: the tombstone ----
        VibeModFabric.services().scheduler().runOnMain(() ->
                VibeModFabric.services().lifecycle().unload("RegistryClientCanary"));
        context.waitTicks(20);
        check("unloading tombstoned the mod's ids",
                seam.ledger() != null && seam.ledger().isTombstoned("RegistryClientCanary"));
        check("and the tombstone is on disk, so the next boot will not re-register them",
                ledgerFileSays("tombstone"));
        check("the ledger counts it (" + seam.describeState() + ")",
                seam.describeState().contains("ledgerTombstones=1"));
    }

    /**
     * Waits until a mouse click would actually reach the game.
     *
     * <p>Two reloads land around a registry change (the resource pack's, and the
     * one the seam asks for), each of which puts a {@code LoadingOverlay} up —
     * and {@code MouseHandler.onButton} routes to keybinds only when
     * {@code gui.overlay() == null}, so a click during one is silently dropped.
     * Waiting for "no overlay right now" is not enough either: the debounce can
     * start a second reload a tick later. So this waits for the coordinator to
     * be idle AND the overlay to be gone for a whole second together.
     */
    private static boolean awaitQuietClient(ClientGameTestContext context) {
        int quiet = 0;
        for (int i = 0; i < 900; i++) {
            boolean idle = VibeModFabric.services().reloads().describeState().contains("clientDirty=false")
                    && context.computeOnClient(client -> client.gui.overlay() == null);
            quiet = idle ? quiet + 1 : 0;
            if (quiet >= 20) {
                return true;
            }
            context.waitTick();
        }
        return false;
    }

    /**
     * The coordinator's client reload counter.
     *
     * <p>Read as a NUMBER rather than as "not zero", because by this point in
     * the gate two reloads have already happened for an earlier canary — and a
     * {@code !contains("clientReloads=0")} wait would return on the first poll,
     * before {@code LanguageManager} had re-read anything. Phase 2 wrote that
     * trap down; this is the shape that avoids it.
     */
    private static int clientReloads() {
        String state = VibeModFabric.services().reloads().describeState();
        int at = state.indexOf("clientReloads=");
        if (at < 0) {
            return -1;
        }
        int end = state.indexOf(' ', at);
        return Integer.parseInt(state.substring(at + "clientReloads=".length(),
                end < 0 ? state.length() : end));
    }

    /** Whether the creative ingredients tab currently offers {@code itemId}. */
    private static boolean tabContains(Identifier itemId) {
        net.minecraft.world.item.CreativeModeTab tab =
                BuiltInRegistries.CREATIVE_MODE_TAB.getValue(CreativeTabs.TAB.identifier());
        if (tab == null) {
            return false;
        }
        for (ItemStack stack : tab.getDisplayItems()) {
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Asks the integrated server's own {@code RecipeManager} to resolve a real
     * 3x3 crafting grid, and checks that what comes out is the registered item.
     *
     * <p>Stronger than "the recipe id is in the manager": the recipe can only
     * parse at all if the item id existed when the datapack was read, and
     * {@code assemble} can only produce the item if the result id resolved to
     * the object this mod registered.
     */
    private static boolean craftsToRegisteredItem(Identifier itemId) {
        try {
            net.minecraft.server.MinecraftServer server = VibeModFabric.services().server();
            net.minecraft.server.level.ServerLevel level = server.overworld();
            ItemStack shard = new ItemStack(net.minecraft.world.item.Items.AMETHYST_SHARD);
            ItemStack redstone = new ItemStack(net.minecraft.world.item.Items.REDSTONE);
            ItemStack stick = new ItemStack(net.minecraft.world.item.Items.STICK);
            ItemStack empty = ItemStack.EMPTY;
            net.minecraft.world.item.crafting.CraftingInput input =
                    net.minecraft.world.item.crafting.CraftingInput.of(3, 3, List.of(
                            empty, shard, empty,
                            shard, redstone, shard,
                            empty, stick, empty));
            return server.getRecipeManager()
                    .getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, level)
                    .map(holder -> holder.value().assemble(input))
                    .map(result -> BuiltInRegistries.ITEM.getKey(result.getItem()).equals(itemId))
                    .orElse(false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The spawned canary entity on the SERVER side, or null. */
    private static net.minecraft.world.entity.Entity liveCanaryEntity(Identifier entityId) {
        for (net.minecraft.world.entity.Entity entity
                : VibeModFabric.services().server().overworld().getAllEntities()) {
            if (BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).equals(entityId)) {
                return entity;
            }
        }
        return null;
    }

    /** The same entity as the CLIENT sees it, which is the one that has to be drawn. */
    private static net.minecraft.world.entity.Entity clientCanaryEntity(
            net.minecraft.client.Minecraft client, String ns) {
        if (client.level == null) {
            return null;
        }
        for (net.minecraft.world.entity.Entity entity : client.level.entitiesForRendering()) {
            if (entity.getClass().getName().startsWith("vibemod.registryclientcanary.")) {
                return entity;
            }
        }
        return null;
    }

    private static boolean ledgerFileSays(String needle) {
        try {
            Path file = FabricLoader.getInstance().getGameDir().resolve("vibemod")
                    .resolve(com.gijsm.vibemod.store.RegistryLedger.FILE_NAME);
            return Files.isRegularFile(file) && Files.readString(file).contains(needle);
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ V4 Phase 1

    /** The canonical namespace the block canary's ids land in; the seam rewrites to it anyway. */
    private static final String BLOCK_NS = "vibemod_blockclientcanary";

    /**
     * The section every far-chunk round trip writes into.
     *
     * <p>Air in every overworld and well clear of terrain, so the round trip is
     * measuring the codec rather than whatever the generator happened to put
     * there.
     */
    private static final int FAR_Y = 200;

    /** The id of the block the canary registers, and of its {@code BlockItem}. */
    private static Identifier blockId() {
        return Identifier.fromNamespaceAndPath(BLOCK_NS, "ruby_block");
    }

    /**
     * A real block, registered through the real seam path, then placed, read
     * back on both sides, lit, broken, dropped and round-tripped through disk
     * (V4 Phase 1 §2.3, §9).
     *
     * <p>This is the phase's happy path and <b>no other gate has it</b>.
     * {@code smoke-fabric.sh} runs on a dedicated server, where
     * {@code RegistrySeam.refuseOnDedicatedServer} turns every block
     * registration into a refusal, so its block coverage is the refusal and
     * nothing else; {@code palette-gate.sh} registers blocks by calling
     * {@code WritableRegistry.register} directly, on purpose, precisely so that
     * it is testing the palette machinery rather than the policy above it. Here
     * a plain Fabric mod with no VibeMod import in it calls
     * {@code Registry.register}, the surgeon's rewrite carries it into the seam,
     * and everything downstream of that is the real thing.
     *
     * <p>Three of the assertions are here because their failure mode is silent:
     *
     * <ul>
     *   <li><b>{@code initCache()}.</b> Without it the fields that call fills —
     *       {@code fluidState}, {@code occlusionShape},
     *       {@code occlusionShapesByFace} — stay null on every state, and the
     *       first thing to touch one is the light engine, on the first
     *       placement, with an NPE. Asserted twice: directly, by reading two of
     *       those fields off a live state, and then by putting the block beside
     *       a glowstone and letting a real lighting update run over it.</li>
     *   <li><b>{@code Item.BY_BLOCK}.</b> {@code Items.registerItem} puts a
     *       {@code BlockItem} in that map before it registers anything, and
     *       generated code never reaches that private helper. Without the link
     *       {@code Block.asItem()} falls through to air and pick-block hands
     *       back nothing — with nothing thrown, anywhere.</li>
     *   <li><b>The drop.</b> {@code BlockBehaviour.<init>} bakes the loot-table
     *       key out of the id, so a block whose namespace was rewritten after
     *       construction would drop nothing and say nothing about it.</li>
     * </ul>
     */
    private void testBlockCanary(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        RegistrySeam seam = VibeModFabric.registrySeam();
        PaletteGuard guard = VibeModFabric.paletteGuard();
        check("the palette guard was installed at mod init", guard != null);
        if (seam == null || guard == null) {
            check("the registry seam and palette guard are both installed for the block gate", false);
            return;
        }
        check("the seam holds no blocks before the block canary (" + seam.describeState() + ")",
                seam.describeState().contains("registryBlocks=0"));
        // The trailing space is not decoration: registryBlockStates is followed
        // by " registryPinnedStubs=" in describeState, and without it
        // contains("…=1") would also match "…=10" once a second block lands.
        check("and no blockstates have been appended yet (" + seam.describeState() + ")",
                seam.describeState().contains("registryBlockStates=0 "));

        int statesBefore = guard.states();
        int budgetBefore = guard.budget();
        check("the probe's arithmetic is self-consistent: states + budget == 2^bits ("
                        + guard.describeState() + ")",
                statesBefore + budgetBefore == (1 << guard.bits()));

        seedWithResources("BlockClientCanary", BLOCK_CANARY_SOURCE, BLOCK_CANARY_META,
                java.util.Map.of(
                        "assets/" + BLOCK_NS + "/blockstates/ruby_block.json",
                        "{\"variants\": {\"\": {\"model\": \"" + BLOCK_NS + ":block/ruby_block\"}}}\n",
                        "assets/" + BLOCK_NS + "/models/block/ruby_block.json",
                        "{\"parent\": \"minecraft:block/cube_all\", \"textures\": {\"all\": \""
                                + BLOCK_NS + ":block/ruby_block\"}}\n",
                        "assets/" + BLOCK_NS + "/items/ruby_block.json",
                        "{\"model\": {\"type\": \"minecraft:model\", \"model\": \""
                                + BLOCK_NS + ":item/ruby_block\"}}\n",
                        "assets/" + BLOCK_NS + "/models/item/ruby_block.json",
                        "{\"parent\": \"" + BLOCK_NS + ":block/ruby_block\"}\n",
                        "assets/" + BLOCK_NS + "/textures/block/ruby_block.png.grid", BLOCK_CANARY_GRID,
                        "assets/" + BLOCK_NS + "/lang/en_us.json",
                        "{\"block." + BLOCK_NS + ".ruby_block\": \"Ruby Block\"}\n",
                        "data/" + BLOCK_NS + "/loot_table/blocks/ruby_block.json",
                        BLOCK_CANARY_LOOT.replace("NS", BLOCK_NS)));

        VibeModFabric.services().scheduler().runOnMain(() -> VibeModFabric.services().router()
                .run(consoleSender(), new String[] {"enable", "BlockClientCanary"}));
        awaitLoaded(context, "BlockClientCanary");

        Identifier id = blockId();

        // ---- the registry itself ----
        check("the block is in the game's own block registry",
                BuiltInRegistries.BLOCK.containsKey(id));
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        check("and it is not air", block != null && block != Blocks.AIR);
        if (block == null || block == Blocks.AIR) {
            return;
        }
        // The blockId seam, observed through the one thing that proves it ran
        // BEFORE construction: BlockBehaviour.<init> reads the id twice, once
        // for the descriptionId and once for the loot-table key. A namespace
        // rewritten afterwards would leave both wrong and throw nothing.
        check("its description id came from the canonical namespace, so setId was seamed "
                        + "before construction (" + block.getDescriptionId() + ")",
                ("block." + BLOCK_NS + ".ruby_block").equals(block.getDescriptionId()));

        java.util.List<BlockState> states = block.getStateDefinition().getPossibleStates();
        check("a plain cube block costs exactly one blockstate (" + states.size() + ")",
                states.size() == 1);
        BlockState state = block.defaultBlockState();

        String seamState = seam.describeState();
        check("the seam counts the block (" + seamState + ")", seamState.contains("registryBlocks=1"));
        check("and counts the blockstates it appended, matching the state definition ("
                        + seamState + ")",
                seamState.contains("registryBlockStates=" + states.size() + " "));
        check("the guard's live state count grew by exactly that many",
                guard.states() == statesBefore + states.size());

        // vanilla's own loop out of Blocks.<clinit>, asserted from the far side:
        // the state has a global id and the id maps back to the same object.
        int globalId = Block.getId(state);
        check("the state was appended to BLOCK_STATE_REGISTRY (id=" + globalId + ")", globalId > 0);
        check("and that id maps back to the same state object",
                Block.BLOCK_STATE_REGISTRY.byId(globalId) == state);

        // ---- initCache(), read directly off the state ----
        //
        // Both of these are plain field reads of fields only initCache() ever
        // writes (javap: initCache stores fluidState, occlusionShape,
        // occlusionShapesByFace, solidRender, legacySolid). Skip the call and
        // the first is null and the second is an aaload on a null array.
        check("initCache() filled the state's fluidState (it is null until initCache runs)",
                safely(() -> state.getFluidState() != null));
        check("initCache() filled the state's per-face occlusion shapes — the array the "
                        + "light engine indexes, and an NPE without it",
                safely(() -> state.getFaceOcclusionShape(Direction.UP) != null));

        // ---- Item.BY_BLOCK ----
        Item blockItem = BuiltInRegistries.ITEM.getValue(id);
        check("the mod's BlockItem is in the item registry", blockItem != null && blockItem != Items.AIR);
        check("Item.byBlock resolves the block to an item rather than to air — the "
                        + "Items.registerItem branch generated code never reaches",
                Item.byBlock(block) != Items.AIR);
        check("and it resolves to the very item the mod registered",
                Item.byBlock(block) == blockItem);
        check("so Block.asItem() answers with it too (pick-block's whole path)",
                block.asItem() == blockItem);

        // ---- the creative menu ----
        check("the block item is in the creative INGREDIENTS tab",
                awaitTrue(context, () -> tabContains(id)));

        // ---- placed, read back on BOTH sides, and lit ----
        BlockPos work = workArea(context);
        check("the gate found a spot near the player to build in", work != null);
        if (work == null) {
            return;
        }
        BlockPos placed = work;
        BlockPos lamp = work.east();

        check("setBlockAndUpdate placed the runtime-registered block",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(server -> {
                    ServerLevel level = server.overworld();
                    // The light source first, so the placement below is the one
                    // that makes the light engine walk into the new state.
                    level.setBlockAndUpdate(lamp, Blocks.GLOWSTONE.defaultBlockState());
                    return level.setBlockAndUpdate(placed, state);
                })));
        check("the server world reads it back as the block that was placed",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(
                        server -> server.overworld().getBlockState(placed) == state)));

        // The client half: a chunk update packet had to be encoded, sent,
        // decoded and merged for this to be true, which is the only reason a
        // gate with a real client asks it at all.
        check("and the CLIENT sees it too, so the block update packet decoded",
                awaitClient(context, client -> client.level != null
                        && client.level.getBlockState(placed).getBlock() == block, true));

        // ---- the light engine, over a state that was registered minutes ago ----
        context.waitTicks(20);
        check("the light engine drained its queue with the new block in it",
                awaitTrue(context, () -> Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(
                        server -> !server.overworld().getLightEngine().hasLightWork()))));
        check("block light from the neighbouring glowstone reached the new block's position",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(server ->
                        server.overworld().getLightEngine().getLayerListener(LightLayer.BLOCK)
                                .getLightValue(placed.above()) > 0)));
        check("and the client is still running after the light engine walked over it",
                context.computeOnClient(client -> client.player != null));

        // ---- broken, and dropped ----
        //
        // The loot table has to be IN the server before the block is broken,
        // and it does not arrive synchronously: the mod's data/** becomes a
        // world datapack and the coordinator debounces one server reload for it,
        // about two seconds later. Breaking inside that window drops nothing and
        // says nothing — which is exactly §7.4's "the demo tore down inside the
        // debounce window", and is what the first run of this gate did.
        java.util.Optional<ResourceKey<net.minecraft.world.level.storage.loot.LootTable>> lootKey =
                block.getLootTable();
        check("the block carries a loot-table key baked from its id ("
                        + lootKey.map(key -> key.identifier().toString()).orElse("none") + ")",
                lootKey.isPresent()
                        && lootKey.get().identifier().equals(
                                Identifier.fromNamespaceAndPath(BLOCK_NS, "blocks/ruby_block")));
        check("and the debounced server reload carried that loot table into the game",
                lootKey.isPresent() && awaitTrue(context, () -> Boolean.TRUE.equals(
                        singleplayer.getServer().computeOnServer(server ->
                                server.reloadableRegistries().getLootTable(lootKey.get())
                                        != net.minecraft.world.level.storage.loot.LootTable.EMPTY))));
        check("destroyBlock broke the runtime-registered block",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(server ->
                        server.overworld().destroyBlock(placed, true, null, 512))));
        check("the world is air where it stood",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(
                        server -> server.overworld().getBlockState(placed).isAir())));
        check("breaking it dropped ITS OWN item, through the loot table its id derived",
                awaitTrue(context, () -> Boolean.TRUE.equals(singleplayer.getServer()
                        .computeOnServer(server -> {
                            for (net.minecraft.world.entity.item.ItemEntity dropped
                                    : server.overworld().getEntitiesOfClass(
                                            net.minecraft.world.entity.item.ItemEntity.class,
                                            new net.minecraft.world.phys.AABB(placed).inflate(4.0))) {
                                if (dropped.getItem().getItem() == blockItem) {
                                    return true;
                                }
                            }
                            return false;
                        }))));

        // ---- and a real disk round trip ----
        //
        // Far outside the player's view, so dropping the force ticket really
        // evicts the chunk and the reload comes off the region file rather than
        // straight back out of the chunk map.
        BlockPos far = farChunkOrigin(singleplayer, 0);
        BlockPos farNeighbour = far.offset(2, 0, 0);
        boolean evicted = roundTripFarChunk(context, singleplayer, far, level -> {
            level.setBlock(far, state, Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            level.setBlock(farNeighbour, Blocks.GOLD_BLOCK.defaultBlockState(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
        });
        // A hard failure, not a skip: a chunk that never left memory makes the
        // two assertions below say nothing at all.
        check("the test chunk really left memory, so the reload comes off disk", evicted);
        check("the runtime-registered block came back off disk as itself",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(
                        server -> server.overworld().getBlockState(far) == state)));
        check("and the known vanilla block two away came back unchanged (the shift detector)",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(server ->
                        server.overworld().getBlockState(farNeighbour).getBlock() == Blocks.GOLD_BLOCK)));
    }

    /**
     * The one assertion in the whole phase that needs real GL and a real model
     * bake: the block draws as <em>itself</em> (V4 Phase 1 §4.15).
     *
     * <p>{@code BlockStateModelSet.get(state)} is
     * {@code modelByState.getOrDefault(state, missingModel)} — verified with
     * {@code javap} — so a state the last bake never saw comes back as the
     * missing model with nothing thrown. That is deliberate on Mojang's part
     * and it is why blocks have no analogue of the entity-renderer NPE V3's
     * gate found; it is also why "it rendered" cannot be inferred from anything
     * short of asking the baked map.
     *
     * <p>A block registered after the last bake therefore IS the missing model
     * until the coordinator's debounced client reload rebakes it — about two
     * seconds. So this waits for that reload the way the resource canary does,
     * by counting completed reloads rather than by looking for "not zero", and
     * only then asks.
     *
     * <p>Render layers are deliberately not asserted, because in 26.2 there is
     * nothing to assert: there is no {@code ItemBlockRenderTypes}, no
     * {@code render_type} model key and no {@code fabric-blockrenderlayer} in
     * this dependency set — the layer is derived from the texture's own alpha
     * ({@code BakedQuad$MaterialInfo} → {@code ChunkSectionLayer.byTransparency}).
     * A correct texture is the whole story, and the texture is asserted below.
     */
    private void testBlockRendersWithItsOwnModel(ClientGameTestContext context) {
        Block block = BuiltInRegistries.BLOCK.getValue(blockId());
        if (block == null || block == Blocks.AIR) {
            check("the block canary registered a block for the render gate to look at", false);
            return;
        }
        BlockState state = block.defaultBlockState();

        Identifier blockstates = Identifier.fromNamespaceAndPath(BLOCK_NS, "blockstates/ruby_block.json");
        Identifier texture = Identifier.fromNamespaceAndPath(BLOCK_NS, "textures/block/ruby_block.png");

        check("the client is quiet again, so the model bake this asserts on is the last one",
                awaitQuietClient(context));
        check("its blockstate definition resolves through the game's own resource manager",
                awaitClient(context, client -> client.getResourceManager()
                        .getResource(blockstates).isPresent(), true));
        check("and its block texture is a real PNG the client can find",
                context.computeOnClient(client -> client.getResourceManager()
                        .getResource(texture).isPresent()));
        check("its name translates from the block lang key its id derives",
                "Ruby Block".equals(context.computeOnClient(client ->
                        net.minecraft.client.resources.language.I18n.get(
                                "block." + BLOCK_NS + ".ruby_block"))));

        // The assertion this whole gate exists for.
        check("the baked model for its state is NOT the missing model — the debounced "
                        + "client reload really rebaked a block registered after the last bake",
                awaitClient(context, client -> {
                    net.minecraft.client.renderer.block.BlockStateModelSet models =
                            client.getModelManager().getBlockStateModelSet();
                    return models.get(state) != models.missingModel();
                }, true));
        check("and its break/particle material came off its own model rather than the "
                        + "missing one — cube_all is what declares \"particle\": \"#all\"",
                context.computeOnClient(client -> {
                    net.minecraft.client.renderer.block.BlockStateModelSet models =
                            client.getModelManager().getBlockStateModelSet();
                    try {
                        return models.getParticleMaterial(state)
                                != models.missingModel().particleMaterial();
                    } catch (Throwable unbaked) {
                        // A model that resolved but whose particle reference did
                        // not is exactly the failure this asserts against, and a
                        // throw here would take the gate down instead of failing
                        // one line of it.
                        System.out.println("  (particle material threw: " + unbaked + ")");
                        return false;
                    }
                }));
        check("the client is still running after baking and drawing it",
                context.computeOnClient(client -> client.player != null));
    }

    /**
     * A palette crossing with a real client on the other end of it (finding 3b,
     * §4.13 step 2 and step 4).
     *
     * <p><b>This is the half {@code scripts/palette-gate.sh} says out loud that
     * it cannot see.</b> On a dedicated server {@code Shims.clientSeam()} is
     * null and {@code level.players()} is empty, so steps 2 and 4 of
     * {@code PaletteGuard.cross()} — widening the client level's own
     * {@code Strategy}, and the drop-then-mark chunk resend — are no-ops there.
     * Here both are real.
     *
     * <p>Why it matters: {@code ClientLevel} builds its own
     * {@code PalettedContainerFactory}, so its {@code globalPaletteBitsInMemory}
     * is a <em>separate int</em> from the server's even when the server is the
     * integrated one two objects away. And the integrated server genuinely
     * serialises chunk packets — {@code Connection.configureInMemoryPipeline}
     * delegates to {@code configureSerialization(…, memoryConnection=true, …)} —
     * while {@code PalettedContainer.read} sizes its long array from the
     * <em>receiving</em> container's own strategy. A disagreement is therefore a
     * decoder exception and a dropped world, in singleplayer included. That is
     * the one failure mode in this phase that kicks a real player.
     *
     * <p>The synthetic blocks are registered the way {@code palette-gate.sh}
     * does it and for the same stated reason: straight into
     * {@code WritableRegistry}, guard first, sized from the <em>measured</em>
     * budget. The policy above the palette is already gated elsewhere; what is
     * under test here is the machinery underneath it, and sizing from a constant
     * would make this gate wrong the day the version bumps.
     *
     * <p>Everything after this runs against a widened palette, permanently:
     * {@code IdMapper} has no remove.
     */
    private void testPaletteCrossingReachesTheClient(ClientGameTestContext context,
                                                     TestSingleplayerContext singleplayer) {
        PaletteGuard guard = VibeModFabric.paletteGuard();
        RegistrySeam seam = VibeModFabric.registrySeam();
        if (guard == null || seam == null) {
            check("the palette guard and seam are installed for the crossing gate", false);
            return;
        }

        int oldBits = guard.bits();
        int oldCeiling = 1 << oldBits;
        check("the two sides agree on the palette width BEFORE the crossing ("
                        + oldBits + " bits)",
                clientPaletteBits(context) == serverPaletteBits(singleplayer));
        check("and that width is the one the guard derives from the live registry",
                serverPaletteBits(singleplayer) == oldBits);

        // Sized from the measured budget, so this works on a version with 402
        // spare blockstates and on one with thirty thousand.
        int need = guard.budget() + 1;
        int size = 1;
        while (size < need) {
            size <<= 1;
        }
        final int crosserStates = size;
        Identifier crosserId = Identifier.fromNamespaceAndPath(BLOCK_NS, "wide_block");

        Block crosser = singleplayer.getServer().computeOnServer(server -> {
            Block[] made = {null};
            try {
                // Block.<init> takes an intrusive holder, which throws once the
                // registry is frozen — so even a harness needs the window a mod
                // gets. Reusing it also means the close does the tag refresh and
                // the component rebuild, exactly as a real registration would.
                seam.withWindow(() -> {
                    ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, crosserId);
                    Block wide = WideBlock.of(crosserStates,
                            BlockBehaviour.Properties.of().strength(1.0F).setId(key));
                    java.util.List<BlockState> possible =
                            wide.getStateDefinition().getPossibleStates();
                    // BEFORE a single state is appended. IdMapper.add stores at
                    // nextId++ and there is no remove, so a block that would not
                    // fit has to be stopped on this side of the append.
                    guard.admit("clientgate", crosserId.toString(), possible.size());
                    ((WritableRegistry<Block>) BuiltInRegistries.BLOCK)
                            .register(key, wide, RegistrationInfo.BUILT_IN);
                    for (BlockState appended : possible) {
                        Block.BLOCK_STATE_REGISTRY.add(appended);
                        appended.initCache();
                    }
                    made[0] = wide;
                });
            } catch (Throwable refused) {
                // Reported rather than rethrown: a refusal here is a finding
                // about the guard, and rethrowing would take the rest of the
                // gate's assertions down with it.
                System.out.println("  (the crossing threw: " + refused + ")");
            }
            return made[0];
        });

        int newBits = guard.bits();
        check("the synthetic block really crossed the global palette boundary ("
                        + oldBits + " -> " + newBits + " bits, " + crosserStates + " states)",
                newBits > oldBits);
        check("the gate is reading the client's live log file, so the two absence "
                        + "assertions below mean something",
                logIsLive());

        // ---- the two assertions palette-gate.sh cannot make ----
        int serverBits = serverPaletteBits(singleplayer);
        int clientBits = clientPaletteBits(context);
        check("the SERVER level's strategy is on the new width (" + serverBits + ")",
                serverBits == newBits);
        check("the CLIENT level's own strategy is on the new width too — a separate int, "
                        + "widened through ClientSeam (" + clientBits + ")",
                clientBits == newBits);
        check("so the two sides are in lockstep, which is what PalettedContainer.read "
                        + "sizes its long array from",
                clientBits == serverBits);
        check("the guard counted the sections it re-encoded (" + guard.describeState() + ")",
                guard.describeState().contains("paletteRepacks="));

        // ---- the client survived it ----
        check("the client is still connected after the crossing and the chunk resend",
                context.computeOnClient(client -> client.getConnection() != null
                        && client.level != null && client.player != null));
        check("no chunk section failed to decode on the client "
                        + "(PalettedContainer.read's fixed-size long array)",
                !logContains("readFixedSizeLongArray"));
        check("and nothing was silently shortened on the way in (finding 3c)",
                !logContains("Recoverable errors when loading section"));

        // ---- and a wide id actually travels ----
        //
        // SimpleBitStorage.set opens with Validate.inclusiveBetween(0, mask,
        // value), so writing the highest state of the crossing block is that
        // check exactly — and then the CLIENT has to decode it at the new width,
        // which is the part only this gate can ask.
        if (crosser == null) {
            check("the crossing block was constructed", false);
            return;
        }
        java.util.List<BlockState> possible = crosser.getStateDefinition().getPossibleStates();
        BlockState widest = possible.get(possible.size() - 1);
        int wideId = Block.getId(widest);
        check("the widest new state's id really is past the old boundary (" + wideId
                        + " >= " + oldCeiling + ")",
                wideId >= oldCeiling);

        BlockPos work = workArea(context);
        if (work == null) {
            check("the gate found a spot near the player to write a wide id into", false);
            return;
        }
        BlockPos widePos = work.north(2);
        check("writing a past-the-boundary state into a live section did not throw",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(server -> {
                    try {
                        return server.overworld().setBlockAndUpdate(widePos, widest);
                    } catch (Throwable t) {
                        System.out.println("  (wide write threw: " + t + ")");
                        return false;
                    }
                })));
        check("and it reads back on the server as the state that was written",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(
                        server -> server.overworld().getBlockState(widePos) == widest)));
        check("and the CLIENT decoded it at the new width — the resend and the widen, "
                        + "end to end",
                awaitClient(context, client -> client.level != null
                        && client.level.getBlockState(widePos) == widest, true));
        check("the client is STILL connected after receiving a wide id",
                context.computeOnClient(client -> client.getConnection() != null
                        && client.player != null));
    }

    /**
     * A pinned block id comes back as an inert stub, and a saved chunk that
     * names one decodes to the same state it was saved as (§4.14).
     *
     * <p>The pin exists because of finding 3c: {@code SerializableChunkData}'s
     * section palette is a {@code ListCodec}, which <em>drops</em> an element it
     * cannot decode and hands the shortened list to {@code promotePartial},
     * while the packed data indexes that palette by position. One missing id
     * renumbers every entry after it and rewrites that section's terrain, with
     * one recoverable-error line in the log. A block id can therefore never be
     * tombstoned the way an item id is.
     *
     * <p>Why this test uses a <b>ghost id</b> — one no mod in this session ever
     * registered — rather than the canary's own: {@code MappedRegistry} has no
     * remove, so inside one JVM a deleted mod's block is still sitting in the
     * registry and {@code replayPinnedBlocks} correctly declines to replace it.
     * The only way to see a stub actually mint an id in this process is to pin
     * an id that nothing owns. The schema is taken off a real vanilla block with
     * real properties ({@code StubBlock.schemaOf}), so the rebuild is exercising
     * a mixed enum/boolean state definition and not a one-state special case.
     *
     * <p>The state placed is deliberately <b>not</b> the default one: a save
     * records a state by property names and value strings, and the whole claim
     * of §4.14 is that the stub decodes back to the same state <em>index</em>.
     * A default state would pass that test by accident.
     */
    private void testPinnedStubRoundTripsThroughDisk(ClientGameTestContext context,
                                                     TestSingleplayerContext singleplayer) {
        RegistrySeam seam = VibeModFabric.registrySeam();
        RegistryLedger book = seam == null ? null : seam.ledger();
        check("the ledger is installed for the pin gate", book != null);
        if (seam == null || book == null) {
            return;
        }

        Identifier ghostId = Identifier.fromNamespaceAndPath("vibemod_pinnedghost", "ghost_block");
        // Two properties, one an enum with three values and one a boolean: six
        // states, cheap against 26.2's budget, and enough that a state index is
        // a real claim rather than a coincidence.
        BlockSchema schema = StubBlock.schemaOf(ghostId, Blocks.STONE_SLAB);
        check("a schema read off a live block is internally consistent (" + schema + ")",
                schema.usable() && schema.problems().isEmpty());
        check("and it records more than one property, so the state index means something",
                schema.properties().size() > 1 && schema.stateCount() > 1);

        book.record("PinnedGhost", 1, RegistryLedger.BLOCK_REGISTRY, ghostId.toString(), schema);
        book.tombstone("PinnedGhost");
        check("the ledger PINNED the mod rather than tombstoning it, because it "
                        + "registered a minecraft:block",
                book.isPinned("PinnedGhost"));
        check("and the pin is on disk, so a real next boot would find it",
                ledgerFileSays("pinned"));
        check("the ledger counts it apart from tombstones (" + book.describeState() + ")",
                book.describeState().contains("ledgerPinned=1"));

        int replayed = singleplayer.getServer().computeOnServer(
                server -> seam.replayPinnedBlocks(book));
        check("the boot-time replay registered a stub for the pinned id", replayed == 1);
        Block stub = BuiltInRegistries.BLOCK.getValue(ghostId);
        check("and the id is claimed by an inert StubBlock", stub instanceof StubBlock);
        if (!(stub instanceof StubBlock)) {
            return;
        }
        java.util.List<BlockState> stubStates = stub.getStateDefinition().getPossibleStates();
        check("the rebuilt state definition has exactly the recorded number of states ("
                        + stubStates.size() + " vs " + schema.stateCount() + ")",
                stubStates.size() == schema.stateCount());
        check("and every one of them reached BLOCK_STATE_REGISTRY",
                Block.getId(stubStates.get(stubStates.size() - 1)) > 0);
        check("the seam reports the stub it minted (" + seam.describeState() + ")",
                seam.describeState().contains("registryPinnedStubs=1"));

        // ---- the round trip that is the actual claim ----
        BlockState chosen = stubStates.get(stubStates.size() - 1);
        check("the state chosen for the round trip is NOT the default one",
                chosen != stub.defaultBlockState());

        BlockPos far = farChunkOrigin(singleplayer, 1);
        BlockPos neighbour = far.offset(2, 0, 0);
        BlockPos filler = far.offset(1, 0, 0);
        boolean evicted = roundTripFarChunk(context, singleplayer, far, level -> {
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
            level.setBlock(far, chosen, flags);
            // A third palette entry between the stub and the witness: if an
            // entry ever were dropped, the witness is what moves.
            level.setBlock(filler, Blocks.IRON_BLOCK.defaultBlockState(), flags);
            level.setBlock(neighbour, Blocks.GOLD_BLOCK.defaultBlockState(), flags);
        });
        check("the stub's test chunk really left memory, so the reload comes off disk", evicted);
        check("the stub came back off disk as the SAME state it was saved as — the "
                        + "state index the schema exists to reproduce",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(
                        server -> server.overworld().getBlockState(far) == chosen)));
        check("the vanilla block two away is unchanged (the palette-shift detector)",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(server ->
                        server.overworld().getBlockState(neighbour).getBlock() == Blocks.GOLD_BLOCK)));
        check("and the one between them is unchanged too",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(server ->
                        server.overworld().getBlockState(filler).getBlock() == Blocks.IRON_BLOCK)));
        check("the gate is reading the live log file", logIsLive());
        check("no section reported a recoverable decode error while loading it (finding 3c)",
                !logContains("Recoverable errors when loading section"));
    }

    /**
     * Deleting a block mod pins its ids instead of tombstoning them, and leaves
     * a witness in the world for the restart to check (Decision 15, §4.14).
     *
     * <p>The distinction is the whole point and it is structural rather than
     * conventional: {@code RegistryLedger.tombstone} refuses to write
     * {@code tombstone} for an entry whose registry is {@code minecraft:block},
     * so no caller can get it wrong by passing the wrong flag. An item id can be
     * tombstoned because vanilla drops an unknown item id out of a save and the
     * world heals; a block id cannot, because a dropped blockstate renumbers the
     * section palette around it.
     *
     * <p>What this leaves behind is deliberate: the mod's block at a known
     * position, a known vanilla block two away, and a third block between them,
     * all near spawn so the reopened world loads them without being asked.
     * {@link #testPinSurvivesAWorldRestart} is what reads them back.
     */
    private void testDeletingABlockModPinsIt(ClientGameTestContext context,
                                             TestSingleplayerContext singleplayer) {
        RegistrySeam seam = VibeModFabric.registrySeam();
        RegistryLedger book = seam == null ? null : seam.ledger();
        Block block = BuiltInRegistries.BLOCK.getValue(blockId());
        if (seam == null || book == null || block == null || block == Blocks.AIR) {
            check("the block canary and ledger are both live for the delete gate", false);
            return;
        }

        BlockPos witness = restartWitness(singleplayer);
        check("the gate found a spot near world spawn for the restart witness", witness != null);
        if (witness == null) {
            return;
        }
        BlockState state = block.defaultBlockState();
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            // A force ticket rather than a hope: the witness has to be readable
            // again in the reopened world, where the player may be anywhere, and
            // a forced chunk is recorded in the save so the reopen restores it.
            level.setChunkForced(witness.getX() >> 4, witness.getZ() >> 4, true);
            level.getChunk(witness.getX() >> 4, witness.getZ() >> 4);
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
            level.setBlock(witness, state, flags);
            level.setBlock(witness.offset(1, 0, 0), Blocks.IRON_BLOCK.defaultBlockState(), flags);
            level.setBlock(witness.offset(2, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), flags);
        });
        check("the mod's block is standing at the witness position",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(
                        server -> server.overworld().getBlockState(witness) == state)));
        check("with a known vanilla block two away",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(server ->
                        server.overworld().getBlockState(witness.offset(2, 0, 0)).getBlock()
                                == Blocks.GOLD_BLOCK)));

        check("the ledger recorded the block id as this mod's",
                book.blockIdsOf("BlockClientCanary").contains(blockId().toString()));

        // /vibe delete, by the path the command takes.
        VibeModFabric.services().scheduler().runOnMain(() ->
                VibeModFabric.services().lifecycle().unload("BlockClientCanary"));
        context.waitTicks(20);

        check("deleting a block mod PINNED it rather than tombstoning it",
                awaitTrue(context, () -> book.isPinned("BlockClientCanary")));
        check("and the pin reached disk, which is the only copy a next boot has",
                ledgerFileSays("pinned"));
        check("the ledger counts two pinned mods now (" + book.describeState() + ")",
                book.describeState().contains("ledgerPinned=2"));
        // The honest half, same as the item story: there is no remove, so the id
        // stays. The difference is that for a block the id staying is the POINT.
        check("the block id is still in the registry, because releasing it would "
                        + "corrupt the chunks it sits in",
                BuiltInRegistries.BLOCK.containsKey(blockId()));
        check("and the block placed in the world is still standing",
                Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(
                        server -> server.overworld().getBlockState(witness) == state)));
        check("the seam holds nothing live for the deleted mod (" + seam.describeState() + ")",
                seam.describeState().contains("registryBlocks=0"));
    }

    /**
     * The world-protecting test §9 asks for: close the world, open it again off
     * disk, and check that a known vanilla block two away from a deleted mod's
     * block is exactly what it was.
     *
     * <p>That neighbour assertion is the palette-shift detector. If a section's
     * palette ever loses an entry, everything after it renumbers — stone becomes
     * dirt, dirt becomes gravel — for that whole 16³ section, and the only thing
     * vanilla says about it is one recoverable-error line. A checksum of the mod
     * block alone would not catch it; a known block <em>beside</em> it is what
     * does.
     *
     * <p><b>The limit, stated rather than implied.</b> This is a world restart,
     * not a process restart. {@code MappedRegistry} has no remove and
     * {@code IdMapper} has no remove, so the reopened world decodes against a
     * registry that still holds the deleted mod's {@code Block} object. What is
     * proved here is that the pin, the save and the load all survive a real
     * shutdown-and-reopen with nothing shifted. What is <em>not</em> proved is
     * the fresh-process case in which the id would be absent unless
     * {@code replayPinnedBlocks} minted a stub for it — that half is
     * {@link #testPinnedStubRoundTripsThroughDisk}, and a genuine cross-process
     * version needs a second launch, which no gate in this repo has. It is owed,
     * the way {@code palette-gate.sh}'s header owes the client half.
     */
    private void testPinSurvivesAWorldRestart(ClientGameTestContext context, TestWorldSave save) {
        check("the gate kept a handle on the world save to reopen", save != null);
        if (save == null) {
            return;
        }
        Block block = BuiltInRegistries.BLOCK.getValue(blockId());
        check("the deleted mod's block id is still claimed between worlds",
                block != null && block != Blocks.AIR);
        if (block == null || block == Blocks.AIR) {
            return;
        }
        BlockState state = block.defaultBlockState();

        try (TestSingleplayerContext reopened = save.open()) {
            context.waitTicks(40);
            check("the host initialised again in the reopened world",
                    VibeModFabric.services() != null && VibeModFabric.services().server() != null);
            if (VibeModFabric.services() == null) {
                return;
            }
            check("and the client is in it, with a player",
                    awaitClient(context, client -> client.level != null && client.player != null, true));

            RegistrySeam seam = VibeModFabric.registrySeam();
            RegistryLedger book = seam == null ? null : seam.ledger();
            check("the ledger came back off disk still pinning the deleted mod",
                    book != null && book.isPinned("BlockClientCanary"));
            check("the pinned block id is claimed in the reopened world, so a saved "
                            + "section that names it still decodes",
                    BuiltInRegistries.BLOCK.containsKey(blockId()));

            BlockPos witness = restartWitness(reopened);
            check("the gate found the witness position again", witness != null);
            if (witness == null) {
                return;
            }
            // Asked for explicitly rather than assumed: the force ticket the
            // other half left is restored from the save, but re-asserting it
            // means this test does not depend on where the player happens to
            // respawn.
            reopened.getServer().runOnServer(server -> {
                ServerLevel level = server.overworld();
                level.setChunkForced(witness.getX() >> 4, witness.getZ() >> 4, true);
                level.getChunk(witness.getX() >> 4, witness.getZ() >> 4);
            });
            check("the witness chunk came back off disk",
                    awaitTrue(context, () -> Boolean.TRUE.equals(reopened.getServer()
                            .computeOnServer(server -> server.overworld().getChunkSource()
                                    .hasChunk(witness.getX() >> 4, witness.getZ() >> 4)))));
            check("the deleted mod's block came back off disk as itself",
                    Boolean.TRUE.equals(reopened.getServer().computeOnServer(
                            server -> server.overworld().getBlockState(witness) == state)));
            check("THE KNOWN VANILLA BLOCK TWO AWAY IS UNCHANGED — the palette-shift "
                            + "detector, and the one assertion that protects a player's world",
                    Boolean.TRUE.equals(reopened.getServer().computeOnServer(server ->
                            server.overworld().getBlockState(witness.offset(2, 0, 0)).getBlock()
                                    == Blocks.GOLD_BLOCK)));
            check("and the block between them is unchanged too",
                    Boolean.TRUE.equals(reopened.getServer().computeOnServer(server ->
                            server.overworld().getBlockState(witness.offset(1, 0, 0)).getBlock()
                                    == Blocks.IRON_BLOCK)));
            check("the gate is reading the live log file after the reopen", logIsLive());
            check("no section was silently shortened on load (finding 3c)",
                    !logContains("Recoverable errors when loading section"));
            check("and no chunk section failed to decode on the client",
                    !logContains("readFixedSizeLongArray"));
        } catch (Throwable reopenFailed) {
            check("reopening the world for the pin gate did not throw: " + reopenFailed, false);
        }
    }

    // -------------------------------------------------------- V4 Phase 1 helpers

    /**
     * A loaded, empty spot a few blocks from the player.
     *
     * <p>Near the player on purpose: the render and client-read assertions both
     * need the block to be inside the client's own view, which is the only place
     * a chunk packet was ever sent for.
     */
    private static BlockPos workArea(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.player == null
                ? null
                : client.player.blockPosition().offset(3, 4, 3));
    }

    /**
     * The fixed spot near world spawn that the delete/restart pair uses.
     *
     * <p>Anchored to the level's own respawn position rather than to the player,
     * because the player is somewhere else entirely after the world is reopened
     * and the two halves of that test have to agree on one block.
     */
    private static BlockPos restartWitness(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerLevel level = server.overworld();
            BlockPos spawn = level.getRespawnData().pos();
            return new BlockPos(spawn.getX() + 4, FAR_Y, spawn.getZ() + 4);
        });
    }

    /**
     * The origin of a chunk far enough out that dropping its force ticket really
     * evicts it.
     *
     * <p>The offset is derived from the server's own view distance rather than
     * hard-coded, because a chunk inside the player's view is kept loaded by a
     * ticket this gate does not own, and the round trip below would then read
     * straight back out of the chunk map and prove nothing.
     */
    private static BlockPos farChunkOrigin(TestSingleplayerContext singleplayer, int nth) {
        return singleplayer.getServer().computeOnServer(server -> {
            int away = server.getPlayerList().getViewDistance() + 12 + (nth * 4);
            ServerLevel level = server.overworld();
            BlockPos spawn = level.getRespawnData().pos();
            int cx = (spawn.getX() >> 4) + away;
            int cz = (spawn.getZ() >> 4) + away;
            return new BlockPos(cx * 16 + 8, FAR_Y, cz * 16 + 8);
        });
    }

    /**
     * Force-loads a far chunk, writes into it, flushes it to disk, evicts it and
     * loads it again.
     *
     * <p>The eviction is the point. Vanilla's {@code unpack} rebuilds a global
     * container at the strategy's <em>current</em> width on load, and the
     * section palette is the {@code ListCodec} finding 3c is about, so both of
     * those are only exercised by a chunk that genuinely left memory. A reload
     * that came out of the chunk map would assert nothing at all — hence the
     * return value, which the callers turn into a failure rather than a skip.
     *
     * @return whether the chunk really left memory before it was asked for again
     */
    private boolean roundTripFarChunk(ClientGameTestContext context,
                                      TestSingleplayerContext singleplayer,
                                      BlockPos where,
                                      java.util.function.Consumer<ServerLevel> write) {
        int cx = where.getX() >> 4;
        int cz = where.getZ() >> 4;

        singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            level.setChunkForced(cx, cz, true);
            level.getChunk(cx, cz);
            write.accept(level);
        });
        // saveEverything(suppressLog, flush, forced). flush, because the whole
        // question is what is on disk — and suppressLog FALSE on purpose, so
        // vanilla's own "Saving chunks for level" reaches logs/latest.log and
        // gives {@link #logIsLive} something Minecraft wrote to key on.
        singleplayer.getServer().runOnServer(server -> server.saveEverything(false, true, true));
        singleplayer.getServer().runOnServer(server ->
                server.overworld().setChunkForced(cx, cz, false));

        boolean evicted = false;
        for (int i = 0; i < 1200 && !evicted; i++) {
            context.waitTick();
            evicted = Boolean.TRUE.equals(singleplayer.getServer().computeOnServer(
                    server -> !server.overworld().getChunkSource().hasChunk(cx, cz)));
        }
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            level.setChunkForced(cx, cz, true);
            level.getChunk(cx, cz);
        });
        return evicted;
    }

    /** The width the SERVER's overworld strategy is on right now. */
    private static int serverPaletteBits(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            Strategy<BlockState> strategy =
                    server.overworld().palettedContainerFactory().blockStatesStrategy();
            return ((StrategyAccessor) strategy).getGlobalPaletteBitsInMemory();
        });
    }

    /**
     * The width the CLIENT's own strategy is on, which is a different {@code int}
     * from the server's even in singleplayer — the whole reason
     * {@code ClientSeam.widenBlockStatePalette} exists.
     *
     * @return the width, or -1 when there is no client level
     */
    private static int clientPaletteBits(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            if (client.level == null) {
                return -1;
            }
            Strategy<BlockState> strategy =
                    client.level.palettedContainerFactory().blockStatesStrategy();
            return ((StrategyAccessor) strategy).getGlobalPaletteBitsInMemory();
        });
    }

    /**
     * Whether the client's own log file contains {@code needle}.
     *
     * <p>Used only for things whose evidence is a stack trace on some other
     * thread — a decoder length mismatch, a shortened section palette. Every
     * absence assertion built on this is paired with a presence one (that the
     * crossing's own line IS there), so a log file that could not be read fails
     * visibly instead of making every absence trivially true.
     *
     * <p>Read as ISO-8859-1 because a crash report can carry bytes that are not
     * valid UTF-8, and a {@code MalformedInputException} here would be an
     * assertion failing for a reason that has nothing to do with the assertion.
     */
    private static boolean logContains(String needle) {
        Path log = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
        try {
            return Files.isRegularFile(log)
                    && Files.readString(log, java.nio.charset.StandardCharsets.ISO_8859_1)
                            .contains(needle);
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Whether {@code logs/latest.log} is the file this run is actually writing.
     *
     * <p>Keyed on a line <b>Minecraft itself</b> emits through log4j —
     * {@code MinecraftServer.saveAllChunks} logs "Saving chunks for level '…'"
     * whenever it is called with {@code suppressLog=false}, which
     * {@link #roundTripFarChunk} arranges. Deliberately not keyed on one of
     * VibeMod's own lines: the host logs through {@code java.util.logging},
     * which reaches the console but does <em>not</em> land in log4j's file
     * (checked against {@code fabric/palette-gate/logs/latest.log}, which has
     * the game's lines and none of VibeMod's).
     *
     * <p>Every {@code !logContains(...)} assertion is paired with this one, so a
     * log that could not be read fails visibly instead of making an absence
     * trivially true.
     */
    private static boolean logIsLive() {
        return logContains("Saving chunks for level");
    }

    /** Runs a predicate that is allowed to throw, and counts a throw as false. */
    private static boolean safely(java.util.function.BooleanSupplier test) {
        try {
            return test.getAsBoolean();
        } catch (Throwable t) {
            System.out.println("  (threw: " + t + ")");
            return false;
        }
    }

    /**
     * A block with an arbitrary power-of-two number of states, for forcing a
     * palette crossing.
     *
     * <p>Built from boolean properties rather than one wide
     * {@code IntegerProperty} because the neighbour tables
     * {@code StateDefinition} builds are {@code states * Σ(values - 1)} entries:
     * twelve booleans give 4096 states for 49,152 entries, while one
     * 4096-value integer property would ask for 16.7 million.
     *
     * <p>{@code createBlockStateDefinition} is called from {@code Block.<init>},
     * before any subclass field exists, which is why the property count arrives
     * through a {@code ThreadLocal} instead of a constructor argument — the same
     * problem vanilla solves by making every property a {@code static final},
     * and the same one {@code StubBlock} has.
     */
    public static final class WideBlock extends Block {

        private static final BooleanProperty[] BITS = {
            BooleanProperty.create("a"), BooleanProperty.create("b"),
            BooleanProperty.create("c"), BooleanProperty.create("d"),
            BooleanProperty.create("e"), BooleanProperty.create("f"),
            BooleanProperty.create("g"), BooleanProperty.create("h"),
            BooleanProperty.create("i"), BooleanProperty.create("j"),
            BooleanProperty.create("k"), BooleanProperty.create("l"),
        };

        private static final ThreadLocal<Integer> PENDING = new ThreadLocal<>();

        /** Builds one with exactly {@code states} states; {@code states} must be a power of two. */
        static Block of(int states, BlockBehaviour.Properties properties) {
            PENDING.set(Integer.numberOfTrailingZeros(states));
            try {
                return new WideBlock(properties);
            } finally {
                PENDING.remove();
            }
        }

        private WideBlock(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            Integer count = PENDING.get();
            if (count == null) {
                return;
            }
            for (int i = 0; i < count; i++) {
                builder.add(BITS[i]);
            }
        }
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
        write(mods, "NativeCanary", NATIVE_CANARY_SOURCE, NATIVE_CANARY_META);
    }

    private static void seedOne(String name, String source, String meta) {
        write(FabricLoader.getInstance().getGameDir().resolve("vibemod").resolve("mods"),
                name, source, meta);
    }

    /**
     * Seeds a mod that ships resource files as well as Java (V3 Phase 2 §A).
     *
     * <p>The paths arrive in the CANONICAL namespace already: a generated mod's
     * would be rewritten there by {@code ModStore.saveNewVersion}, and this
     * writes straight to disk. The rewrite itself is gated in
     * {@code :core:selfTestStore}.
     */
    private static void seedWithResources(String name, String source, String meta,
                                          java.util.Map<String, String> resources) {
        Path mods = FabricLoader.getInstance().getGameDir().resolve("vibemod").resolve("mods");
        write(mods, name, source, meta);
        try {
            Path version = mods.resolve(name).resolve("v1");
            for (var entry : resources.entrySet()) {
                Path file = version.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    /**
     * The V3 canary: an ordinary Fabric mod, with no VibeMod import in it.
     *
     * <p>The marker is written relative to the process's working directory
     * rather than through {@code FabricLoader.getGameDir()}, and that is not
     * laziness: {@code net.fabricmc.loader.*} is denied by the surgeon's policy
     * (loader internals outlive a disable), so the mod genuinely cannot ask. The
     * gate reads the same relative path from the same JVM, so the two always
     * agree.
     */
    private static final String NATIVE_CANARY_SOURCE = """
            package vibemod.nativecanary;

            import java.nio.file.Files;
            import java.nio.file.Path;

            import net.fabricmc.api.ModInitializer;
            import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
            import net.fabricmc.fabric.api.event.player.AttackBlockCallback;

            import net.minecraft.world.InteractionResult;

            public final class NativeCanary implements ModInitializer {

                private static final Path MARKER = Path.of("vibemod", "native-canary-ticks");

                private int ticks;

                @Override
                public void onInitialize() {
                    ServerTickEvents.END_SERVER_TICK.register(server -> {
                        if (++ticks % 5 != 0) {
                            return;
                        }
                        try {
                            Files.createDirectories(MARKER.getParent());
                            Files.writeString(MARKER, Integer.toString(ticks));
                        } catch (Exception ignored) {
                            // the marker is for the gate, not for the mod
                        }
                    });
                    AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                            InteractionResult.PASS);
                }
            }
            """;

    /**
     * The V3 Phase 1 canary: one ordinary Fabric mod using every surface Phase 1
     * opened, and still importing nothing from VibeMod.
     *
     * <p>Deliberately the same mod as the native profile's {@code CoordToggle}
     * few-shot, with marker files added — so what the prompt teaches a model to
     * write is literally what this gate compiles, loads and runs.
     *
     * <p>The one thing here a real mod would do differently is hopping to the
     * render thread from the command to open the screen. On a dedicated server
     * that would be a networking job; in singleplayer the two sides share a JVM
     * and {@code Minecraft#execute} is the correct hop, which is exactly what
     * makes it the right shape for a gate that runs in singleplayer.
     */
    private static final String NATIVE_CLIENT_SOURCE = """
            package vibemod.nativeclientcanary;

            import java.nio.file.Files;
            import java.nio.file.Path;

            import com.mojang.blaze3d.platform.InputConstants;

            import net.fabricmc.api.ClientModInitializer;
            import net.fabricmc.api.ModInitializer;
            import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
            import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
            import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
            import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
            import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

            import net.minecraft.client.KeyMapping;
            import net.minecraft.client.Minecraft;
            import net.minecraft.client.gui.screens.Screen;
            import net.minecraft.commands.Commands;
            import net.minecraft.network.chat.Component;
            import net.minecraft.resources.Identifier;

            public final class NativeClientCanary implements ModInitializer, ClientModInitializer {

                private static final Path DIR = Path.of("vibemod", "nativeclient");

                private KeyMapping toggle;
                private int frames;

                @Override
                public void onInitialize() {
                    // This mod is enabled long after the world started, so the
                    // host has to replay this for it.
                    ServerLifecycleEvents.SERVER_STARTING.register(server -> mark("server-starting"));
                    CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                            dispatcher.register(Commands.literal("nativecmd").executes(ctx -> {
                                ctx.getSource().sendSystemMessage(Component.literal("native-cmd-ok"));
                                mark("command-ran");
                                Minecraft client = Minecraft.getInstance();
                                client.execute(() -> client.setScreenAndShow(new CanaryScreen()));
                                return 1;
                            })));
                }

                @Override
                public void onInitializeClient() {
                    mark("client-init");
                    toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                            "key.nativeclientcanary.toggle", InputConstants.Type.KEYSYM,
                            InputConstants.KEY_H, KeyMapping.Category.MISC));
                    ClientTickEvents.END_CLIENT_TICK.register(client -> {
                        while (toggle.consumeClick()) {
                            mark("key-pressed");
                        }
                    });
                    HudElementRegistry.addLast(
                            Identifier.fromNamespaceAndPath("nativeclientcanary", "readout"),
                            (graphics, delta) -> {
                                if (++frames % 20 == 0) {
                                    mark("hud-frames");
                                }
                                graphics.text(Minecraft.getInstance().font,
                                        "native " + frames, 4, 24, 0xFF55FF55);
                            });
                }

                private static void mark(String name) {
                    try {
                        Files.createDirectories(DIR);
                        Files.writeString(DIR.resolve(name), "yes");
                    } catch (Exception ignored) {
                        // the markers are for the gate, not for the mod
                    }
                }

                /** A Screen the mod defined itself: the host closes it when the mod goes. */
                public static final class CanaryScreen extends Screen {

                    CanaryScreen() {
                        super(Component.literal("Native Canary"));
                    }

                    @Override
                    public boolean isPauseScreen() {
                        return false;
                    }
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
    private static final String NATIVE_CANARY_META =
            meta("NativeCanary", "A plain Fabric ModInitializer, hot-loaded through the bytecode seam.");
    private static final String NATIVE_CLIENT_META = meta("NativeClientCanary",
            "A plain Fabric mod using both entrypoints: command, keybind, HUD and its own screen.");

    // ---------------------------------------------------------- V3 Phase 2

    /**
     * The Phase 2 canary's Java, and there is deliberately almost none of it:
     * everything interesting about this mod is in its resource files.
     */
    private static final String RESOURCE_CANARY_SOURCE = """
            package vibemod.resourceclientcanary;

            import java.util.logging.Logger;

            import net.fabricmc.api.ModInitializer;

            /** A mod whose content is data. The Java only says it loaded. */
            public final class ResourceClientCanary implements ModInitializer {

                @Override
                public void onInitialize() {
                    Logger.getLogger("VibeMod.ResourceClientCanary").info("resource-client-canary-init");
                }
            }
            """;

    private static final String RESOURCE_CANARY_META = meta("ResourceClientCanary",
            "A plain Fabric mod that ships a datapack and a resource pack.");

    /** Vanilla's own 26.2 shape, read out of data/minecraft/recipe/golden_apple.json. */
    private static final String RESOURCE_CANARY_RECIPE = """
            {
              "type": "minecraft:crafting_shaped",
              "key": {"#": "minecraft:redstone", "X": "minecraft:amethyst_shard"},
              "pattern": [" # ", "#X#", " # "],
              "result": {
                "id": "minecraft:amethyst_shard",
                "components": {"minecraft:item_model": "NS:ruby"}
              }
            }
            """;

    /** A four-colour 8x8 grid: small enough to read, big enough to be a real texture. */
    private static final String RESOURCE_CANARY_GRID = """
            {"palette": {".": "transparent", "d": "#5a1010", "r": "#b31c1c"},
             "rows": ["..dddd..", ".drrrrd.", "drrrrrrd", "drrrrrrd",
                      "drrrrrrd", "drrrrrrd", ".drrrrd.", "..dddd.."]}
            """;

    /**
     * The V3 Phase 3 canary (§D): a plain Fabric mod, no VibeMod import
     * anywhere, that registers a real item with its own {@code use} override and
     * a real entity type with default attributes and a vanilla renderer.
     *
     * <p>Compiled against the real Loom classpath with {@code javac} before
     * being embedded, exactly like the prompt's own few-shots.
     */
    private static final String REGISTRY_CANARY_SOURCE = """
            package vibemod.registryclientcanary;

            import java.nio.file.Files;
            import java.nio.file.Path;

            import net.fabricmc.api.ClientModInitializer;
            import net.fabricmc.api.ModInitializer;
            import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
            import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
            import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;

            import net.minecraft.client.renderer.entity.PigRenderer;
            import net.minecraft.commands.Commands;
            import net.minecraft.core.Registry;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.network.chat.Component;
            import net.minecraft.resources.Identifier;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.entity.EntitySpawnReason;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.MobCategory;
            import net.minecraft.world.entity.animal.pig.Pig;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ToolMaterial;
            import net.minecraft.world.level.Level;

            /**
             * A plain Fabric mod with no VibeMod import that registers a REAL item and a
             * REAL entity type, and gives the gate a command to drive them with.
             */
            public final class RegistryClientCanary implements ModInitializer, ClientModInitializer {

                private static final String NS = "vibemod_registryclientcanary";
                private static final Path DIR = Path.of("vibemod", "registrycanary");

                public static final Identifier ITEM_ID = Identifier.fromNamespaceAndPath(NS, "ruby_sword");
                public static final Identifier ENTITY_ID = Identifier.fromNamespaceAndPath(NS, "ruby_pig");

                public static Item rubySword;
                public static EntityType<RubyPig> rubyPig;

                @Override
                public void onInitialize() {
                    rubySword = Registry.register(BuiltInRegistries.ITEM, ITEM_ID, new RubySwordItem(
                            new Item.Properties().sword(ToolMaterial.IRON, 4.0F, -2.4F)
                                    .setId(ResourceKey.create(Registries.ITEM, ITEM_ID))));

                    rubyPig = EntityType.Builder.of(RubyPig::new, MobCategory.CREATURE)
                            .build(ResourceKey.create(Registries.ENTITY_TYPE, ENTITY_ID));
                    Registry.register(BuiltInRegistries.ENTITY_TYPE, ENTITY_ID, rubyPig);
                    FabricDefaultAttributeRegistry.register(rubyPig, Pig.createAttributes());

                    CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                            dispatcher.register(Commands.literal("regcanary").executes(ctx -> {
                                ServerLevel level = ctx.getSource().getLevel();
                                ServerPlayer player = ctx.getSource().getPlayer();
                                if (player != null) {
                                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(rubySword));
                                }
                                boolean spawned = false;
                                RubyPig pig = rubyPig.create(level, EntitySpawnReason.COMMAND);
                                if (pig != null && player != null) {
                                    pig.setPos(player.getX() + 2.0, player.getY(), player.getZ());
                                    spawned = level.addFreshEntity(pig);
                                }
                                ctx.getSource().sendSystemMessage(
                                        Component.literal("registry-canary spawned=" + spawned));
                                mark("command-ran");
                                return 1;
                            })));
                }

                @Override
                public void onInitializeClient() {
                    EntityRendererRegistry.register(rubyPig, PigRenderer::new);
                    mark("renderer-registered");
                }

                static void mark(String name) {
                    try {
                        Files.createDirectories(DIR);
                        Files.writeString(DIR.resolve(name), "ok");
                    } catch (Exception ignored) {
                        // a marker that cannot be written is a failed assertion, not a crash
                    }
                }

                /** The item subclass: its own {@code use} override, which the gate fires with a real click. */
                public static final class RubySwordItem extends Item {

                    public RubySwordItem(Properties properties) {
                        super(properties);
                    }

                    @Override
                    public InteractionResult use(Level level, Player player, InteractionHand hand) {
                        mark(level.isClientSide() ? "use-fired-client" : "use-fired");
                        return InteractionResult.SUCCESS;
                    }
                }

                /** A vanilla mob subclass, so a vanilla renderer already knows how to draw it. */
                public static final class RubyPig extends Pig {

                    public RubyPig(EntityType<? extends RubyPig> type, Level level) {
                        super(type, level);
                    }
                }
            }
            """;

    private static final String REGISTRY_CANARY_META = """
            {
              "schema": 3,
              "platform": "fabric",
              "mcVersion": "26.2",
              "side": "client",
              "name": "RegistryClientCanary",
              "description": "A plain Fabric mod that registers a real item and a real entity type.",
              "usage": "",
              "manual": "",
              "icon": "IRON_SWORD",
              "mainClass": "vibemod.registryclientcanary.RegistryClientCanary",
              "currentVersion": 1,
              "enabled": false,
              "creator": "gate",
              "versions": [
                {
                  "version": 1,
                  "prompt": "the V3 registry canary",
                  "model": "none",
                  "createdAt": 0,
                  "changelog": "First registry canary.",
                  "kind": "create",
                  "costUsd": 0.0,
                  "requester": "gate"
                }
              ],
              "config": [],
              "configValues": {}
            }
            """;

    /** The result names the MOD's own item, so the recipe only parses if the id exists. */
    private static final String REGISTRY_CANARY_RECIPE = """
            {
              "type": "minecraft:crafting_shaped",
              "key": {
                "#": "minecraft:redstone",
                "X": "minecraft:amethyst_shard",
                "S": "minecraft:stick"
              },
              "pattern": [
                " X ",
                "X#X",
                " S "
              ],
              "result": {
                "id": "NS:ruby_sword"
              }
            }
            """;

    // ---------------------------------------------------------- V4 Phase 1

    /**
     * The V4 Phase 1 canary: a plain Fabric mod, with no VibeMod import in it,
     * that registers a REAL block and the {@code BlockItem} you place it with.
     *
     * <p>Deliberately the same mod as the native profile's {@code RubyBlock}
     * few-shot, down to the comments — so what the prompt teaches a model to
     * write is literally what this gate compiles, hot-loads and then places,
     * breaks and round-trips through disk.
     *
     * <p>Two lines carry the whole phase and neither of them looks like it:
     * {@code setId(...)} runs BEFORE construction, because
     * {@code BlockBehaviour.<init>} reads the id twice — once for the
     * description id that becomes the lang key, once for the {@code drops} key
     * that becomes {@code data/<ns>/loot_table/blocks/<path>.json}; and
     * registering the {@code BlockItem} under the same id is what the seam
     * turns into an {@code Item.BY_BLOCK} entry, without which
     * {@code Block.asItem()} silently answers air.
     */
    private static final String BLOCK_CANARY_SOURCE = """
            package vibemod.blockclientcanary;

            import net.fabricmc.api.ModInitializer;

            import net.minecraft.core.Registry;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.resources.Identifier;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.world.item.BlockItem;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.SoundType;
            import net.minecraft.world.level.block.state.BlockBehaviour;
            import net.minecraft.world.level.material.MapColor;

            /** A real registered block: a plain red cube you can place, mine and get back. */
            public final class BlockClientCanary implements ModInitializer {

                // One id for the block AND its item, in the namespace the resource files use.
                public static final Identifier ID =
                        Identifier.fromNamespaceAndPath("vibemod_blockclientcanary", "ruby_block");

                public static Block rubyBlock;

                @Override
                public void onInitialize() {
                    // No properties at all, so this block costs exactly ONE blockstate.
                    // setId(...) BEFORE construction: the Block constructor bakes the
                    // description id and the loot-table path out of it.
                    rubyBlock = Registry.register(BuiltInRegistries.BLOCK, ID, new Block(
                            BlockBehaviour.Properties.of()
                                    .mapColor(MapColor.COLOR_RED)
                                    .strength(3.0F)
                                    .sound(SoundType.STONE)
                                    .setId(ResourceKey.create(Registries.BLOCK, ID))));
                    // The item you hold and place, under the same id.
                    Registry.register(BuiltInRegistries.ITEM, ID, new BlockItem(rubyBlock,
                            new Item.Properties()
                                    .useBlockDescriptionPrefix()
                                    .setId(ResourceKey.create(Registries.ITEM, ID))));
                }
            }
            """;

    /**
     * Stored DISABLED, like the registry canary: the block gate enables it
     * mid-session on purpose, so registration, the resource reload and the model
     * rebake all happen against a world that has been running for thousands of
     * ticks rather than at a convenient moment during boot.
     */
    private static final String BLOCK_CANARY_META = """
            {
              "schema": 3,
              "platform": "fabric",
              "mcVersion": "26.2",
              "side": "both",
              "name": "BlockClientCanary",
              "description": "A plain Fabric mod that registers a real block and its BlockItem.",
              "usage": "",
              "manual": "",
              "icon": "REDSTONE_BLOCK",
              "mainClass": "vibemod.blockclientcanary.BlockClientCanary",
              "currentVersion": 1,
              "enabled": false,
              "creator": "gate",
              "versions": [
                {
                  "version": 1,
                  "prompt": "the V4 block canary",
                  "model": "none",
                  "createdAt": 0,
                  "changelog": "First block canary.",
                  "kind": "create",
                  "costUsd": 0.0,
                  "requester": "gate"
                }
              ],
              "config": [],
              "configValues": {}
            }
            """;

    /**
     * The loot table without which the block drops NOTHING when broken — and
     * says nothing about it, because {@code BlockBehaviour.<init>} baked the
     * key {@code <ns>:blocks/ruby_block} out of the id and vanilla simply finds
     * no table there.
     */
    private static final String BLOCK_CANARY_LOOT = """
            {
              "type": "minecraft:block",
              "pools": [
                {
                  "rolls": 1,
                  "entries": [{"type": "minecraft:item", "name": "NS:ruby_block"}],
                  "conditions": [{"condition": "minecraft:survives_explosion"}]
                }
              ]
            }
            """;

    /** A full 16x16 grid: a block texture with no alpha, which is what makes it a solid layer. */
    private static final String BLOCK_CANARY_GRID = """
            {
              "palette": {"d": "#5a1010", "r": "#b31c1c", "l": "#ff6b6b"},
              "rows": [
                "dddddddddddddddd",
                "drrrrrrrrrrrrrrd",
                "drlrrrrrrrrrrlrd",
                "drrrrlrrrrlrrrrd",
                "drrrrrrllrrrrrrd",
                "drrrrrlrrlrrrrrd",
                "drrrrlrrrrlrrrrd",
                "drrrlrrrrrrlrrrd",
                "drrrlrrrrrrlrrrd",
                "drrrrlrrrrlrrrrd",
                "drrrrrlrrlrrrrrd",
                "drrrrrrllrrrrrrd",
                "drrrrlrrrrlrrrrd",
                "drlrrrrrrrrrrlrd",
                "drrrrrrrrrrrrrrd",
                "dddddddddddddddd"
              ]
            }
            """;

    private static final String REGISTRY_CANARY_GRID = """
            {
              "palette": {
                ".": "transparent",
                "d": "#5a1010",
                "r": "#b31c1c",
                "l": "#ff6b6b",
                "h": "#8b5a2b"
              },
              "rows": [
                "..............l.",
                ".............lr.",
                "............lrr.",
                "...........lrrd.",
                "..........lrrd..",
                ".........lrrd...",
                "........lrrd....",
                "...h...lrrd.....",
                "..hhh.lrrd......",
                "...h.lrrd.......",
                "..hh.lrd........",
                ".hh..ldd........",
                "hh...d..........",
                "h...............",
                "................",
                "................"
              ]
            }
            """;
}
