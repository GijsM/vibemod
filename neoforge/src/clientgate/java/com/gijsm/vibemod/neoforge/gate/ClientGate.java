package com.gijsm.vibemod.neoforge.gate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.mojang.blaze3d.platform.InputConstants;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import com.gijsm.vibemod.loader.LoaderSender;
import com.gijsm.vibemod.loader.client.LoaderClientEventBridge;
import com.gijsm.vibemod.neoforge.VibeModNeoForge;
import com.gijsm.vibemod.neoforge.client.NeoForgeClientEventBridge;
import com.gijsm.vibemod.neoforge.client.VibeModNeoForgeClient;
import com.gijsm.vibemod.platform.Sender;
import com.gijsm.vibemod.runtime.ModHandle;

/**
 * The CLIENT half of the Phase E acceptance gate (ARCHITECTURE-V2 §9), driving a
 * real Minecraft client with a real GL context and a real singleplayer world.
 *
 * <p><b>Why this exists in this shape.</b> Fabric has
 * {@code fabric-client-gametest}, which boots a client, builds a world and runs
 * assertions in a plain imperative method with {@code waitTicks} calls.
 * <b>NeoForge has no equivalent</b> — its GameTest framework runs on a dedicated
 * server ({@code gameTestServer}) and never starts a client, and MDG's
 * {@code unitTest} support runs JUnit against the game classpath with no window
 * at all. Neither can answer "does a throwing HUD renderer crash the render
 * loop", which is the §8.1 question this gate exists for.
 *
 * <p>So the gate is a self-driving mod instead, and the shape is forced by that:
 * <b>nothing here may block</b>, because it runs on the render thread inside
 * {@code ClientTickEvent.Post}. A blocking {@code waitTicks} would freeze the
 * very client it is testing. The test is therefore a list of {@link Stage}s —
 * each a readiness condition, a timeout and a body — advanced one per tick.
 * Reading it top to bottom in {@link #buildStages()} gives the same narrative
 * the Fabric test's method bodies do.
 *
 * <p>It creates its own world programmatically through
 * {@code WorldOpenFlows#createFreshLevel}, the same call the Fabric gametest
 * world builder makes, rather than relying on {@code --quickPlaySingleplayer}
 * and a pre-baked save: a save copied in from elsewhere is a second thing that
 * can be wrong, and this way the gate needs nothing on disk.
 *
 * <p>Results go to a file and the process is halted with an exit code, because
 * a client that finishes its test and then sits at the title screen forever is
 * not a gate. The runner script asserts on the file.
 *
 * <p>This class lives in its own source set (`neoforge/src/clientgate`) and is a
 * separate mod id, so none of it ships in the VibeMod jar.
 */
@Mod(value = "vibemodgate", dist = Dist.CLIENT)
public final class ClientGate {

    /** Where the runner script looks for the verdict. */
    private static final String RESULTS_FILE = "vibemod-clientgate-results.txt";

    /** A whole-run ceiling in TICKS, for a client that is running but stuck. */
    private static final int GLOBAL_TIMEOUT_TICKS = 20 * 60 * 12;

    /** A whole-run ceiling in WALL CLOCK, for a client that is not ticking at all. */
    private static final long DEADLINE_MILLIS = 15 * 60 * 1000L;

    /** How long to give an async compile + hot-load before calling it a failure. */
    private static final int LOAD_TIMEOUT_TICKS = 20 * 60;

    /** Let the client settle (resource reload, first frames) before making a world. */
    private static final int WORLD_START_TICKS = 60;

    private final List<String> results = new ArrayList<>();
    private int failures;

    private List<Stage> stages;
    private int index;
    private int waited;
    private int totalTicks;
    private boolean worldRequested;
    private boolean finished;

    /**
     * One step of the test: wait until {@code ready} (or until
     * {@code timeoutTicks} elapse), then run {@code body} once.
     *
     * <p>A {@code ready} of {@code null} means "this is a plain delay": wait the
     * full timeout, then run. That is how the Fabric test's
     * {@code context.waitTicks(40)} calls translate.
     */
    private record Stage(String name, BooleanSupplier ready, int timeoutTicks, Runnable body) {

        static Stage delay(int ticks, String name, Runnable body) {
            return new Stage(name, null, ticks, body);
        }

        static Stage when(String name, BooleanSupplier ready, int timeoutTicks, Runnable body) {
            return new Stage(name, ready, timeoutTicks, body);
        }
    }

    public ClientGate(IEventBus modBus) {
        // Before any world exists, so restore-on-boot compiles and hot-loads them
        // exactly as generated ones would — no LLM and no API key, the same trick
        // the dedicated-server gate uses.
        seedMods();
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            try {
                tick();
            } catch (Throwable t) {
                // Never let the gate's own bug look like the host's: a listener
                // that throws is unsubscribed by some buses and logged by none
                // loudly enough.
                System.out.println("[vibemod-gate] the gate itself threw: " + t);
                t.printStackTrace(System.out);
                check("the gate ran without throwing: " + t, false);
                finish();
            }
        });
        startDeadlineThread();
        System.out.println("[vibemod-gate] armed");
    }

    /**
     * A wall-clock deadline on its own daemon thread, and it is not belt and
     * braces — it is the only guard that works.
     *
     * <p>{@link #GLOBAL_TIMEOUT_TICKS} is counted from inside
     * {@code ClientTickEvent}, so it only fires while the client is still
     * ticking. A client that wedges, hangs on a GL call, or sits on a screen that
     * does not tick would never reach it, and the game would then sit open on
     * somebody's desktop until they closed it by hand. That happened during
     * development, which is why this exists.
     */
    private void startDeadlineThread() {
        Thread deadline = new Thread(() -> {
            try {
                Thread.sleep(DEADLINE_MILLIS);
            } catch (InterruptedException interrupted) {
                return;
            }
            if (!finished) {
                System.out.println("[vibemod-gate] wall-clock deadline reached; halting the client");
                check("the gate finished within its wall-clock deadline", false);
                finish();
            }
        }, "vibemod-gate-deadline");
        deadline.setDaemon(true);
        deadline.start();
    }

    // ------------------------------------------------------------------ driver

    private void tick() {
        if (finished) {
            return;
        }
        if (++totalTicks > GLOBAL_TIMEOUT_TICKS) {
            check("the gate finished within its time budget", false);
            finish();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (totalTicks % 100 == 0) {
            // A heartbeat, because a client gate that quietly does nothing looks
            // exactly like a client gate that is still starting up.
            System.out.println("[vibemod-gate] tick " + totalTicks
                    + " screen=" + (mc.gui == null ? "<no gui>"
                            : mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName())
                    + " level=" + (mc.level != null) + " player=" + (mc.player != null)
                    + " host=" + (VibeModNeoForge.services() != null));
        }

        // Phase 1: get into a world. Done here rather than as a Stage because it
        // is the only part that must survive the client not being ready at all.
        //
        // The trigger is "the client has been ticking for a while and has no
        // level", deliberately NOT "the title screen is up". A first launch does
        // not show the title screen: it shows AccessibilityOnboardingScreen, and
        // the runner script's options.txt only suppresses that when it is
        // written before the game reads it. Waiting for a specific screen made
        // the first version of this gate sit at the onboarding screen forever,
        // which is exactly the kind of silent nothing the heartbeat above exists
        // to expose.
        if (stages == null) {
            if (!worldRequested) {
                if (totalTicks >= WORLD_START_TICKS && mc.level == null) {
                    createWorld(mc);
                    worldRequested = true;
                }
                return;
            }
            if (mc.level == null || mc.player == null || VibeModNeoForge.services() == null) {
                return;
            }
            stages = buildStages();
            System.out.println("[vibemod-gate] in world; running " + stages.size() + " stages");
            return;
        }

        // Phase 2: the test itself.
        if (index >= stages.size()) {
            finish();
            return;
        }
        Stage stage = stages.get(index);
        boolean ready = stage.ready() != null && stage.ready().getAsBoolean();
        if (!ready && waited < stage.timeoutTicks()) {
            waited++;
            return;
        }
        index++;
        waited = 0;
        try {
            stage.body().run();
        } catch (Throwable t) {
            check(stage.name() + " threw: " + t, false);
        }
    }

    /**
     * A flat, peaceful, creative world with cheats — the same choices the Fabric
     * gametest world builder makes, and for the same reasons: flat generates
     * instantly, peaceful keeps a mob from killing the test subject mid-run, and
     * cheats let the gate drive {@code /vibe} as a player.
     */
    private void createWorld(Minecraft mc) {
        System.out.println("[vibemod-gate] creating the world");
        LevelSettings settings = new LevelSettings(
                "vibegate",
                GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                true,
                WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(
                "vibegate", settings, WorldOptions.defaultWithRandomSeed(),
                WorldPresets::createNormalWorldDimensions, null);
    }

    // ------------------------------------------------------------------ the test

    /**
     * The gate, in order. Each entry is one of the Fabric client test's methods,
     * turned inside out so it never blocks the render thread.
     *
     * <p>Order matters, and not for convenience. Both boot-time canned mods
     * register a HUD renderer, and the angry one un-registers ITSELF the first
     * frame it throws — so any assertion on the dispatcher's size has to happen
     * on a known side of that event. The storm stage runs first; only then is
     * {@code huds} a number about HudCanary alone.
     */
    private List<Stage> buildStages() {
        List<Stage> s = new ArrayList<>();

        s.add(Stage.delay(20, "host init", () -> {
            VibeModNeoForge.Services services = VibeModNeoForge.services();
            check("the host initialised inside the client's integrated server", services != null);
            if (services == null) {
                return;
            }
            check("the platform reports a physical client", services.platform().hasClient());
            check("the platform does NOT report a dedicated server",
                    !services.platform().isDedicatedServer());
            check("the client bridge was installed at client init",
                    VibeModNeoForgeClient.bridge() != null);
        }));

        s.add(loaded("HudCanary"));
        s.add(loaded("AngryHud"));

        // ---- the §8.1 requirement: a throwing HUD renderer never crashes the
        // render loop. That it threw at all proves it was attached AND actually
        // drawn — nothing but the HUD dispatcher ever calls a HudRenderer.
        s.add(Stage.delay(60, "throwing HUD", () -> {
            check("the throwing renderer really ran, and its failure was journalled",
                    errors("AngryHud") > 0);
            ModHandle angry = handle("AngryHud");
            check("the throwing mod was marked degraded", angry != null && angry.degraded());
            String state = state();
            check("the throwing renderer was detached, leaving only the healthy one (" + state + ")",
                    state.contains("huds=1"));
        }));
        s.add(Stage.delay(40, "still alive after a throw", () ->
                check("the client is still running after a HUD renderer threw",
                        Minecraft.getInstance().player != null)));

        // ---- the key pool (§8.2). Three things at once, none of them provable
        // from the pool's own bookkeeping: that "G" was parsed and auto-bound,
        // that the tick dispatcher's consumeClick() polling sees the press, and
        // that it lands in the mod. Observed through a marker file, because the
        // visible effect is a character on a HUD.
        s.add(Stage.delay(1, "press the leased key", () -> {
            delete(marker("HudCanary", "key-pressed"));
            KeyMapping.click(InputConstants.getKey("key.keyboard.g"));
        }));
        s.add(Stage.when("key reached the mod",
                () -> Files.isRegularFile(marker("HudCanary", "key-pressed")), 60,
                () -> check("pressing the auto-bound key reached the mod's handler",
                        Files.isRegularFile(marker("HudCanary", "key-pressed")))));

        // ---- /vibec routing (§8.3), observed the same way and for the same
        // reason: the alternative is a toast, and asserting on pixels would be
        // testing that Minecraft draws toasts.
        s.add(Stage.delay(1, "run /vibec", () -> {
            delete(marker("HudCanary", "vibec-ran"));
            Minecraft.getInstance().player.connection.sendCommand("vibec HudCanary status");
        }));
        s.add(Stage.when("/vibec reached the mod",
                () -> Files.isRegularFile(marker("HudCanary", "vibec-ran")), 80,
                () -> check("/vibec <mod> <command> routed into the mod's handler",
                        Files.isRegularFile(marker("HudCanary", "vibec-ran")))));

        // ---- the happy path: four registration kinds land in the live
        // dispatchers. Asserted against the bridge's own counters rather than the
        // screen: what is under test is that VibeMod's dispatcher holds the mod's
        // renderer, not that Minecraft draws text.
        s.add(Stage.delay(40, "registrations", () -> {
            String state = state();
            check("the HUD dispatcher holds the mod's renderer (" + state + ")",
                    state.contains("huds=1"));
            check("the client-tick dispatcher holds the mod's handler (" + state + ")",
                    state.contains("tickers=1"));
            check("the mod's /vibec command registered (" + state + ")",
                    state.contains("clientCommands=1"));
            check("the mod leased exactly one key slot (" + state + ")",
                    state.contains("keysLeased=1/" + LoaderClientEventBridge.KEY_SLOTS));
            ModHandle handle = handle("HudCanary");
            check("the mod's client registrations are tracked on its handle",
                    handle != null && handle.registrationCount() >= 4);
            check("the client survived rendering the mod's HUD",
                    Minecraft.getInstance().getFps() >= 0);
        }));

        // ---- teardown: the §0#10 revocation model, on the surface where it is
        // hardest.
        s.add(Stage.delay(1, "disable HudCanary", () -> onServerThread(() ->
                VibeModNeoForge.services().lifecycle().disable("HudCanary"))));
        s.add(Stage.delay(30, "teardown drained everything", () -> {
            String drained = state();
            // Zero, not one: the angry mod's renderer detached itself before this
            // phase began, so HudCanary's was the only one left to drain.
            check("disabling drained the HUD dispatcher (" + drained + ")",
                    drained.contains("huds=0"));
            check("disabling drained the client-tick dispatcher (" + drained + ")",
                    drained.contains("tickers=0"));
            check("disabling removed the /vibec command (" + drained + ")",
                    drained.contains("clientCommands=0"));
            check("disabling returned the key slot to the pool (" + drained + ")",
                    drained.contains("keysLeased=0/" + LoaderClientEventBridge.KEY_SLOTS));
        }));

        // ---- and the slot really is re-leasable. A pool that hands a slot back
        // but never hands it out again would pass the teardown assertions above
        // and still exhaust itself after eight mod reloads.
        s.add(Stage.delay(1, "re-enable HudCanary", () -> onServerThread(() -> {
            try {
                VibeModNeoForge.services().lifecycle().enable("HudCanary");
            } catch (Exception e) {
                check("re-enabling the mod after a disable did not throw: " + e, false);
            }
        })));
        s.add(Stage.delay(30, "re-enable re-leased", () -> {
            String state = state();
            check("re-enabling re-leased a key slot from the pool (" + state + ")",
                    state.contains("keysLeased=1/" + LoaderClientEventBridge.KEY_SLOTS));
            check("re-enabling re-attached the HUD renderer (" + state + ")",
                    state.contains("huds=1"));
        }));

        // ---- the singleplayer dialog path, end to end, with a real player:
        // player command -> Screen model -> LoaderDialogRenderer -> an inline
        // Holder.direct dialog -> ClientboundShowDialogPacket -> a screen the
        // client actually put up. Bare /vibe needs only vibe.use, which maps to
        // PermissionLevel.ALL, so this works without cheats.
        s.add(Stage.delay(1, "run bare /vibe", () ->
                Minecraft.getInstance().player.connection.sendCommand("vibe")));
        s.add(Stage.when("dialog opened",
                () -> Minecraft.getInstance().gui.screen() instanceof DialogScreen, 100,
                () -> {
                    Minecraft mc = Minecraft.getInstance();
                    check("bare /vibe opened a native dialog on the client",
                            mc.gui.screen() instanceof DialogScreen);
                    // Leave the screen closed so nothing downstream inherits it.
                    if (mc.gui.screen() != null) {
                        mc.gui.screen().onClose();
                    }
                }));

        // ---- the render watchdog (§8.4): the failure a try/catch cannot catch.
        // SlowHud never errors, it is simply far too slow. Seeded here rather
        // than at boot because a mod eating 400ms of every frame from the start
        // would make the whole gate crawl.
        s.add(Stage.delay(10, "seed and enable SlowHud", () -> {
            seedOne("SlowHud", SLOW_HUD_SOURCE, meta("SlowHud",
                    "A HUD renderer that overruns the render-thread budget."));
            onServerThread(() -> VibeModNeoForge.services().router()
                    .run(consoleSender(), new String[] {"enable", "SlowHud"}));
        }));
        s.add(loaded("SlowHud"));
        s.add(Stage.delay(120, "watchdog tripped", () -> {
            ModHandle slow = handle("SlowHud");
            check("the render watchdog auto-disabled the slow mod", slow != null && !slow.enabled());
            check("the watchdog trip was journalled", errors("SlowHud") > 0);
            String state = state();
            check("the slow renderer was drained with its mod (" + state + ")",
                    state.contains("huds=1"));
            check("the client is still running after a HUD renderer overran its budget",
                    Minecraft.getInstance().player != null);
        }));

        return s;
    }

    private Stage loaded(String modName) {
        return Stage.when("load " + modName, () -> handle(modName) != null, LOAD_TIMEOUT_TICKS,
                () -> check("the canned mod " + modName + " compiled and hot-loaded",
                        handle(modName) != null));
    }

    // ------------------------------------------------------------------ helpers

    private static String state() {
        NeoForgeClientEventBridge bridge = VibeModNeoForgeClient.bridge();
        return bridge == null ? "<no bridge>" : bridge.describeState();
    }

    private static ModHandle handle(String modName) {
        VibeModNeoForge.Services services = VibeModNeoForge.services();
        return services == null ? null : services.lifecycle().get(modName);
    }

    private static int errors(String modName) {
        VibeModNeoForge.Services services = VibeModNeoForge.services();
        return services == null ? 0 : services.errors().distinctCount(modName);
    }

    /**
     * Hops to the server thread.
     *
     * <p>Not optional: {@code ModLifecycle.disable} asserts it is on that thread,
     * and this whole class runs on the render thread. It is the same hop the
     * render watchdog's trip handler needs, for the same reason.
     */
    private static void onServerThread(Runnable body) {
        VibeModNeoForge.Services services = VibeModNeoForge.services();
        if (services != null) {
            services.scheduler().runOnMain(body);
        }
    }

    /** A console-shaped {@link Sender} for driving the router. */
    private static Sender consoleSender() {
        return LoaderSender.of(VibeModNeoForge.services().server().createCommandSourceStack(),
                VibeModNeoForge.services().messenger());
    }

    private static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    private static Path marker(String modName, String file) {
        return gameDir().resolve("vibemod").resolve("moddata").resolve(modName).resolve(file);
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // never written yet
        }
    }

    // ------------------------------------------------------------------ reporting

    private void check(String what, boolean ok) {
        String line = (ok ? "  ok: " : "  FAIL: ") + what;
        System.out.println(line);
        results.add(line);
        if (!ok) {
            failures++;
        }
    }

    /**
     * Write the verdict and end the process.
     *
     * <p>{@code Runtime#halt} rather than {@code Minecraft#stop}: stopping the
     * game from inside its own tick is a deadlock waiting to happen, and a
     * client that finishes its test and then sits at the title screen forever is
     * not a gate. The results file is written and flushed first, so the runner
     * script has the verdict whatever the exit does.
     */
    private void finish() {
        finished = true;
        results.add(failures == 0
                ? "PHASE E CLIENT GATE PASSED (" + results.size() + " checks)"
                : failures + " CLIENT CHECK(S) FAILED");
        try {
            Files.writeString(gameDir().resolve(RESULTS_FILE),
                    String.join("\n", results) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println(results.get(results.size() - 1));
        System.out.flush();
        Runtime.getRuntime().halt(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ seeding

    private static void seedMods() {
        seedOne("HudCanary", HUD_CANARY_SOURCE,
                meta("HudCanary", "Every ClientContext registration kind, once."));
        seedOne("AngryHud", ANGRY_HUD_SOURCE,
                meta("AngryHud", "A HUD renderer that throws on every frame."));
    }

    private static void seedOne(String name, String source, String meta) {
        try {
            Path mods = gameDir().resolve("vibemod").resolve("mods").resolve(name);
            Path version = mods.resolve("v1");
            Files.createDirectories(version);
            Files.writeString(version.resolve(name + ".java"), source, StandardCharsets.UTF_8);
            Files.writeString(mods.resolve("meta.json"), meta, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
             * Exactly the shape the neoforge prompt profile's few-shots teach —
             * and byte-identical to the Fabric gate's canary, because the sdk mod
             * flavor is loader-neutral.
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

    private static String meta(String name, String description) {
        return """
                {
                  "schema": 3,
                  "platform": "neoforge",
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
}
