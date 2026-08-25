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
import net.minecraft.client.renderer.entity.PigRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import com.gijsm.vibemod.fabric.VibeModFabric;
import com.gijsm.vibemod.fabric.client.FabricClientEventBridge;
import com.gijsm.vibemod.fabric.client.FabricClientPacks;
import com.gijsm.vibemod.fabric.client.VibeModFabricClient;
import com.gijsm.vibemod.fabric.shim.CreativeTabs;
import com.gijsm.vibemod.fabric.shim.EventFanout;
import com.gijsm.vibemod.fabric.shim.RegistrySeam;
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
