import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.event.Event;

import com.gijsm.vibemod.compile.CompileResult;
import com.gijsm.vibemod.compile.InMemoryCompiler;
import com.gijsm.vibemod.fabric.FabricSeams;
import com.gijsm.vibemod.fabric.shim.Shims;
import com.gijsm.vibemod.loader.surgeon.BytecodeSurgeon;
import com.gijsm.vibemod.loader.surgeon.Seam;
import com.gijsm.vibemod.loader.surgeon.SurgeonPolicy;
import com.gijsm.vibemod.platform.ClassSurgeon;
import com.gijsm.vibemod.platform.CompilerProvider;
import com.gijsm.vibemod.runtime.ModLifecycle;

/**
 * The V3 Phase 0 §F.1 gate: proves the bytecode pass does what the whole
 * architecture is resting on.
 *
 * <p>Plain {@code main()}, no test framework, like every other VibeMod
 * self-test. It lives in the {@code fabric} module and in its own source set
 * because it needs three things at once that exist nowhere else: Java 25 (for
 * {@code java.lang.classfile}), {@code loader-common} (the surgeon), and the
 * real Fabric API on the compile classpath (so a fixture can contain a genuine
 * {@code Event.register} call site rather than an imitation of one).
 *
 * <p>The fixtures are compiled by the SAME {@link InMemoryCompiler} the host
 * uses, against the same kind of classpath, so what is under test is the
 * production path and not a reconstruction of it. And the rewrite test does not
 * stop at the constant pool: it defines the rewritten class, runs it, and
 * checks that the registration arrived at the shim. A rewrite that produced
 * unverifiable bytecode, or pointed at a method that does not exist, would pass
 * a constant-pool assertion and fail here.
 */
public final class SurgeonSelfTest {

    private static int failures;

    public static void main(String[] args) {
        InMemoryCompiler compiler = compiler();

        testOrdinaryJavaPasses(compiler);
        testReflectionRejected(compiler);
        testThreadRejected(compiler);
        testMethodReferenceToThreadRejected(compiler);
        testEventRegisterIsRewrittenAndRuns(compiler);
        testIdiomaticEventCallSiteIsRewritten(compiler);
        testLegacyVibeContextModPassesUntouched(compiler);
        testNeoForgePolicyRefusesFabric(compiler);
        testPhaseOrderingRejected(compiler);
        testCommandCallbackIsSeamed(compiler);
        testKeybindSeamIsRewritten(compiler);
        testHudSeamIsRewritten(compiler);
        testRegistryRegisterIsRewrittenAndRuns(compiler);
        testRubySwordShapeIsFullySeamed(compiler);
        testEntityTypeSeamsAreRewritten(compiler);
        testEntityRendererSeamIsRewritten(compiler);
        testTheTwoSetIdSeamsAreToldApart();
        testBlockSetIdIsRewrittenAndRuns(compiler);
        testRubyBlockShapeIsFullySeamed(compiler);
        testBlockColorRegistryRejected(compiler);

        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ (a)

    /**
     * Lambdas, records and switches must survive the policy.
     *
     * <p>This is the check that would have caught the tempting-but-wrong
     * implementation. Scanning the constant pool for {@code java/lang/invoke}
     * would reject every one of these — javac routes all three through
     * {@code invokedynamic} — so the pass has to allow the four bootstraps
     * javac actually uses and check what they point at instead.
     */
    private static void testOrdinaryJavaPasses(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.ordinary.Ordinary", """
                package vibemod.ordinary;

                import java.util.List;
                import java.util.function.Supplier;

                public final class Ordinary {

                    public record Point(int x, int y) { }

                    public enum Colour { RED, GREEN }

                    public String run(Object value, Colour colour) {
                        Supplier<String> lambda = () -> "from a lambda";
                        List<Point> points = List.of(new Point(1, 2), new Point(3, 4));
                        String pattern = switch (value) {
                            case Integer i -> "int " + i;
                            case String s when s.isEmpty() -> "empty";
                            case String s -> "text " + s;
                            default -> "other";
                        };
                        String named = switch (colour) {
                            case RED -> "red";
                            case GREEN -> "green";
                        };
                        return lambda.get() + points.get(0) + pattern + named + points.get(1).equals(points);
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("lambdas, records, pattern switches and string concat pass the policy: "
                + result.diagnostics(), result.ok());
        check("ordinary Java with no seam call is returned byte-identical",
                result.ok() && identical(classes, result.classes()));
    }

    // ------------------------------------------------------------------ (b)

    private static void testReflectionRejected(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.reflect.Reflect", """
                package vibemod.reflect;

                import java.lang.reflect.Method;

                public final class Reflect {
                    public Object peek(Object target) throws Exception {
                        Method method = target.getClass().getDeclaredMethod("toString");
                        return method.invoke(target);
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("reflection is rejected", !result.ok());
        check("the reflection diagnostic is javac-shaped and names the file ("
                        + firstLine(result.diagnostics()) + ")",
                result.diagnostics().startsWith("Reflect.java: error: forbidden API:"));
        check("the reflection diagnostic explains itself",
                result.diagnostics().contains("java.lang.reflect.Method"));
    }

    // ------------------------------------------------------------------ (c)

    private static void testThreadRejected(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.threads.Threads", """
                package vibemod.threads;

                public final class Threads {
                    public void go() {
                        Thread thread = new Thread(() -> System.out.println("nope"));
                        thread.start();
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("starting a thread is rejected", !result.ok());
        check("the thread diagnostic names Thread (" + firstLine(result.diagnostics()) + ")",
                result.diagnostics().contains("java.lang.Thread"));
    }

    /**
     * The case the instruction walk exists for: {@code Thread::start} appears
     * nowhere as an {@code invokevirtual}. It is a method handle in an
     * {@code invokedynamic}'s bootstrap arguments, and the only way to see it is
     * to look inside them.
     */
    private static void testMethodReferenceToThreadRejected(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.threadref.ThreadRef", """
                package vibemod.threadref;

                import java.util.List;

                public final class ThreadRef {
                    public void go(List<Thread> threads) {
                        threads.forEach(Thread::start);
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("a method reference to Thread.start is rejected too", !result.ok());
        check("the method-reference diagnostic names start ("
                        + firstLine(result.diagnostics()) + ")",
                result.diagnostics().contains("Thread.start"));
    }

    // ------------------------------------------------------------------ (d)

    /**
     * The load-bearing one: a real {@code Event.register} call site is
     * redirected into {@link Shims}, and the rewritten class links, verifies and
     * runs.
     */
    private static void testEventRegisterIsRewrittenAndRuns(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.seam.Seamy", """
                package vibemod.seam;

                import net.fabricmc.fabric.api.event.Event;

                public final class Seamy {
                    public void subscribe(Event<Runnable> event, Runnable listener) {
                        event.register(listener);
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("a class calling Event.register passes the policy: " + result.diagnostics(), result.ok());
        if (!result.ok()) {
            return;
        }
        byte[] rewritten = result.classes().get("vibemod.seam.Seamy");
        List<String> calls = callSites(rewritten);
        check("the rewritten class no longer calls Event.register (" + calls + ")",
                !calls.contains("net/fabricmc/fabric/api/event/Event.register"));
        check("the rewritten class calls the host shim instead (" + calls + ")",
                calls.contains("com/gijsm/vibemod/fabric/shim/Shims.eventRegister"));
        check("the rewrite actually changed the bytes",
                !Arrays.equals(classes.get("vibemod.seam.Seamy"), rewritten));

        // And now the part a constant-pool assertion cannot reach: does it run?
        Recorder recorder = new Recorder();
        Shims.install(recorder);
        try {
            ClassLoader loader = new ModLifecycle.BytesClassLoader(
                    SurgeonSelfTest.class.getClassLoader(), result.classes());
            Class<?> seamy = loader.loadClass("vibemod.seam.Seamy");
            Object instance = seamy.getDeclaredConstructor().newInstance();
            Event<Runnable> event = new StubEvent();
            Runnable listener = () -> { };
            seamy.getMethod("subscribe", Event.class, Runnable.class).invoke(instance, event, listener);

            check("the rewritten call reached the host shim", recorder.events.size() == 1);
            check("the shim received the mod's own event instance",
                    recorder.events.size() == 1 && recorder.events.get(0) == event);
            check("the shim received the mod's own listener",
                    recorder.listeners.size() == 1 && recorder.listeners.get(0) == listener);
            check("the real Event.register was never called", ((StubEvent) event).registered == 0);
        } catch (Throwable t) {
            check("defining and running the rewritten class threw: " + t, false);
        } finally {
            Shims.install(null);
        }
    }

    /**
     * The idiomatic spelling, {@code SomeEvents.SOME_EVENT.register(lambda)}.
     *
     * <p>Worth its own fixture because the seam matches on the call site's
     * declared owner, and the whole table rests on that owner always being
     * {@code Event} — which is true only because every Fabric event is declared
     * as an {@code Event<Callback>} field. If that ever stopped being true, this
     * is the check that would say so.
     */
    private static void testIdiomaticEventCallSiteIsRewritten(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.idiomatic.Idiomatic", """
                package vibemod.idiomatic;

                import net.fabricmc.api.ModInitializer;
                import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
                import net.fabricmc.fabric.api.event.player.AttackBlockCallback;

                import net.minecraft.world.InteractionResult;

                public final class Idiomatic implements ModInitializer {

                    private int ticks;

                    @Override
                    public void onInitialize() {
                        ServerTickEvents.END_SERVER_TICK.register(server -> ticks++);
                        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                                InteractionResult.PASS);
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("an ordinary Fabric mod passes the policy: " + result.diagnostics(), result.ok());
        if (!result.ok()) {
            return;
        }
        byte[] rewritten = result.classes().get("vibemod.idiomatic.Idiomatic");
        List<String> calls = callSites(rewritten);
        long shimCalls = calls.stream()
                .filter("com/gijsm/vibemod/fabric/shim/Shims.eventRegister"::equals).count();
        check("both idiomatic Event.register call sites were redirected to the shim (" + calls + ")",
                shimCalls == 2);
        check("no Event.register call survived (" + calls + ")",
                !calls.contains("net/fabricmc/fabric/api/event/Event.register"));
        check("the rewritten mod keeps its ModInitializer entrypoint",
                mentions(rewritten, "net/fabricmc/api/ModInitializer"));
    }

    // ------------------------------------------------------------------ (e)

    /**
     * The legacy corpus must be provably unaffected. Not "still compiles" —
     * byte-identical, which is the only claim strong enough to be worth
     * anything for 500+ stored sources nobody is going to re-read.
     */
    private static void testLegacyVibeContextModPassesUntouched(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.legacy.Legacy", """
                package vibemod.legacy;

                import java.util.Map;
                import java.util.UUID;
                import java.util.concurrent.ConcurrentHashMap;

                import com.gijsm.vibemod.api.Mod;
                import com.gijsm.vibemod.api.VibeContext;

                import net.minecraft.network.chat.Component;

                public final class Legacy implements Mod {

                    private final Map<UUID, Long> counts = new ConcurrentHashMap<>();

                    @Override
                    public void onEnable(VibeContext ctx) throws Exception {
                        ctx.onBlockBreak((player, pos, state) -> {
                            long total = counts.merge(player.getUUID(), 1L, Long::sum);
                            if (total % Math.max(1L, ctx.configInt("milestone")) == 0L) {
                                player.sendSystemMessage(Component.literal("Broke " + total));
                            }
                            return true;
                        });
                        ctx.onServerTick(server -> { });
                        ctx.repeat(20L, 20L, () -> { });
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("a legacy VibeContext mod passes the policy: " + result.diagnostics(), result.ok());
        check("a legacy VibeContext mod comes back byte-identical",
                result.ok() && identical(classes, result.classes()));
    }

    // ------------------------------------------------------------------ NeoForge

    /**
     * The NeoForge variant (§A): the same surgeon class, an empty seam table and
     * one extra denial, producing the message the Phase E gate asserts on.
     */
    private static void testNeoForgePolicyRefusesFabric(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.wrongloader.WrongLoader", """
                package vibemod.wrongloader;

                import net.fabricmc.api.ModInitializer;
                import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

                public final class WrongLoader implements ModInitializer {
                    private int ticks;

                    @Override
                    public void onInitialize() {
                        ServerTickEvents.END_SERVER_TICK.register(server -> ticks++);
                    }
                }
                """);
        BytecodeSurgeon neoforge = new BytecodeSurgeon(SurgeonPolicy.defaultsPlus(
                new SurgeonPolicy.Denial("net/fabricmc/", null,
                        "Fabric API seams are not available on NeoForge yet")));
        ClassSurgeon.Result result = neoforge.operate(classes);
        check("the NeoForge policy refuses a Fabric-API mod", !result.ok());
        check("with the message the Phase E gate looks for ("
                        + firstLine(result.diagnostics()) + ")",
                result.diagnostics().contains("Fabric API seams are not available on NeoForge yet"));
        check("and the same mod is accepted by the Fabric policy",
                fabricSurgeon().operate(classes).ok());
    }

    private static void testPhaseOrderingRejected(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.phases.Phases", """
                package vibemod.phases;

                import net.fabricmc.fabric.api.event.Event;

                import net.minecraft.resources.Identifier;

                public final class Phases {
                    public void reorder(Event<Runnable> event) {
                        event.addPhaseOrdering(Identifier.withDefaultNamespace("first"),
                                Identifier.withDefaultNamespace("second"));
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("Event.addPhaseOrdering is rejected", !result.ok());
        check("the phase-ordering diagnostic explains why ("
                        + firstLine(result.diagnostics()) + ")",
                result.diagnostics().contains("addPhaseOrdering"));
    }

    // ------------------------------------------------------------- V3 Phase 1

    /**
     * §A: {@code CommandRegistrationCallback} is a seamed registration now, not
     * a refusal.
     *
     * <p>The rewrite is the ordinary {@code Event.register} one — the difference
     * Phase 1 makes is at the other end, inside the fanout — so what this proves
     * is that a command mod passes the policy and that its registration really
     * does go through the host rather than into a Brigadier tree nothing can
     * prune.
     */
    private static void testCommandCallbackIsSeamed(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.cmd.Cmd", """
                package vibemod.cmd;

                import com.mojang.brigadier.builder.LiteralArgumentBuilder;

                import net.fabricmc.api.ModInitializer;
                import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

                import net.minecraft.commands.CommandSourceStack;
                import net.minecraft.commands.Commands;
                import net.minecraft.network.chat.Component;

                public final class Cmd implements ModInitializer {

                    @Override
                    public void onInitialize() {
                        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
                            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("cmd")
                                    .executes(ctx -> {
                                        ctx.getSource().sendSystemMessage(Component.literal("hi"));
                                        return 1;
                                    });
                            dispatcher.register(node);
                        });
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("a mod registering CommandRegistrationCallback passes the policy: "
                + result.diagnostics(), result.ok());
        if (!result.ok()) {
            return;
        }
        List<String> calls = callSites(result.classes().get("vibemod.cmd.Cmd"));
        check("its CommandRegistrationCallback registration goes through the host shim (" + calls + ")",
                calls.contains("com/gijsm/vibemod/fabric/shim/Shims.eventRegister"));
        check("and no raw Event.register survived (" + calls + ")",
                !calls.contains("net/fabricmc/fabric/api/event/Event.register"));
    }

    /**
     * §C: {@code KeyMappingHelper.registerKeyMapping} lands on
     * {@code ClientShims}.
     *
     * <p>This is the seam whose shape differs from every Phase 0 entry: it is an
     * {@code invokestatic} with no receiver AND it returns a value the mod goes
     * on to use. A {@code prependingReceiver} entry here would produce bytecode
     * that fails verification, and the descriptor is the thing to get exactly
     * right — {@code (Lnet/minecraft/client/KeyMapping;)Lnet/minecraft/client/KeyMapping;},
     * read off fabric-key-mapping-api-v1 with {@code javap -s}. The class is
     * {@code KeyMappingHelper} and not {@code KeyBindingHelper}: the latter does
     * not exist in this era at all.
     */
    private static void testKeybindSeamIsRewritten(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.keys.Keys", """
                package vibemod.keys;

                import com.mojang.blaze3d.platform.InputConstants;

                import net.fabricmc.api.ClientModInitializer;
                import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

                import net.minecraft.client.KeyMapping;

                public final class Keys implements ClientModInitializer {

                    private KeyMapping key;

                    @Override
                    public void onInitializeClient() {
                        key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                                "key.keys.toggle", InputConstants.Type.KEYSYM,
                                InputConstants.KEY_G, KeyMapping.Category.MISC));
                    }

                    public boolean pressed() {
                        return key.consumeClick();
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("a mod leasing a keybind passes the policy: " + result.diagnostics(), result.ok());
        if (!result.ok()) {
            return;
        }
        List<String> calls = callSites(result.classes().get("vibemod.keys.Keys"));
        check("KeyMappingHelper.registerKeyMapping was redirected to the client shim (" + calls + ")",
                calls.contains("com/gijsm/vibemod/fabric/shim/ClientShims.registerKeyMapping"));
        check("no call to the real KeyMappingHelper survived (" + calls + ")",
                calls.stream().noneMatch(c -> c.startsWith(
                        "net/fabricmc/fabric/api/client/keymapping/v1/KeyMappingHelper.")));
        check("the mod still polls the mapping it was handed back (" + calls + ")",
                calls.contains("net/minecraft/client/KeyMapping.consumeClick"));
    }

    /** §D: every {@code HudElementRegistry} add/attach overload lands on the client shim. */
    private static void testHudSeamIsRewritten(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.hud.Hud", """
                package vibemod.hud;

                import net.fabricmc.api.ClientModInitializer;
                import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
                import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

                import net.minecraft.resources.Identifier;

                public final class Hud implements ClientModInitializer {

                    @Override
                    public void onInitializeClient() {
                        Identifier id = Identifier.fromNamespaceAndPath("hud", "one");
                        HudElementRegistry.addLast(id, (graphics, delta) -> { });
                        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("hud", "two"),
                                (graphics, delta) -> { });
                        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
                                Identifier.fromNamespaceAndPath("hud", "three"),
                                (graphics, delta) -> { });
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("a mod drawing a HUD passes the policy: " + result.diagnostics(), result.ok());
        if (!result.ok()) {
            return;
        }
        List<String> calls = callSites(result.classes().get("vibemod.hud.Hud"));
        long shimCalls = calls.stream()
                .filter(c -> c.startsWith("com/gijsm/vibemod/fabric/shim/ClientShims.hud")).count();
        check("all three HudElementRegistry overloads were redirected (" + calls + ")",
                shimCalls == 3);
        check("no call to the real HudElementRegistry survived (" + calls + ")",
                calls.stream().noneMatch(c -> c.startsWith(
                        "net/fabricmc/fabric/api/client/rendering/v1/hud/HudElementRegistry.")));
    }

    // ------------------------------------------------------------------ V3 Phase 3

    /**
     * §A: {@code Registry.register} reaches the shim, with the mod's own
     * arguments, and the real static is never called.
     *
     * <p>The registry arrives as a <em>parameter</em> rather than as
     * {@code BuiltInRegistries.ITEM}, on purpose: touching that field would
     * initialise every built-in registry and run vanilla's whole bootstrap
     * inside a self-test that is meant to prove a property of bytes. The
     * descriptor at the call site is identical either way — that is exactly what
     * the seam matches on — and the running-game proof is the client gate's
     * registry canary.
     */
    private static void testRegistryRegisterIsRewrittenAndRuns(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.regcall.RegCall", """
                package vibemod.regcall;

                import net.minecraft.core.Registry;
                import net.minecraft.resources.Identifier;

                public final class RegCall {
                    public Object put(Registry<Object> registry, Identifier id, Object value) {
                        return Registry.register(registry, id, value);
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("a mod calling Registry.register passes the policy: " + result.diagnostics(), result.ok());
        if (!result.ok()) {
            return;
        }
        List<String> calls = callSites(result.classes().get("vibemod.regcall.RegCall"));
        check("Registry.register was redirected to the host shim (" + calls + ")",
                calls.contains("com/gijsm/vibemod/fabric/shim/Shims.registryRegister"));
        check("no call to the real Registry.register survived (" + calls + ")",
                !calls.contains("net/minecraft/core/Registry.register"));

        RegistryRecorder recorder = new RegistryRecorder();
        Shims.installRegistries(recorder);
        try {
            ClassLoader loader = new ModLifecycle.BytesClassLoader(
                    SurgeonSelfTest.class.getClassLoader(), result.classes());
            Class<?> regCall = loader.loadClass("vibemod.regcall.RegCall");
            Object instance = regCall.getDeclaredConstructor().newInstance();
            net.minecraft.resources.Identifier id =
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("regcall", "thing");
            Object value = new Object();
            Object returned = regCall.getMethod("put", net.minecraft.core.Registry.class,
                            net.minecraft.resources.Identifier.class, Object.class)
                    .invoke(instance, null, id, value);

            check("the rewritten registry call reached the host shim", recorder.ids.size() == 1);
            check("the shim received the mod's own id",
                    recorder.ids.size() == 1 && recorder.ids.get(0) == id);
            check("the shim received the mod's own value",
                    recorder.values.size() == 1 && recorder.values.get(0) == value);
            check("and the mod got its object back (static final ITEM = register(...) still works)",
                    returned == value);
        } catch (Throwable t) {
            check("defining and running the rewritten registry class threw: " + t, false);
        } finally {
            Shims.installRegistries(null);
        }
    }

    /**
     * §C's few-shot, seam by seam: the exact shape {@code RubySword} teaches,
     * with a real {@code BuiltInRegistries.ITEM} and a real
     * {@code Item.Properties.setId}.
     *
     * <p>Compiled and rewritten but NOT run — constructing an {@code Item}
     * outside a registration window is precisely what the window exists to make
     * legal, and doing it here would prove nothing about the rewrite.
     */
    private static void testRubySwordShapeIsFullySeamed(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.rubysword.RubySword", """
                package vibemod.rubysword;

                import net.fabricmc.api.ModInitializer;

                import net.minecraft.core.Registry;
                import net.minecraft.core.registries.BuiltInRegistries;
                import net.minecraft.core.registries.Registries;
                import net.minecraft.resources.Identifier;
                import net.minecraft.resources.ResourceKey;
                import net.minecraft.world.item.Item;
                import net.minecraft.world.item.ToolMaterial;

                public final class RubySword implements ModInitializer {

                    public static Item RUBY_SWORD;

                    @Override
                    public void onInitialize() {
                        Identifier id = Identifier.fromNamespaceAndPath("rubysword", "ruby_sword");
                        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
                        RUBY_SWORD = Registry.register(BuiltInRegistries.ITEM, id, new Item(
                                new Item.Properties().sword(ToolMaterial.IRON, 4.0F, -2.4F).setId(key)));
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("the RubySword few-shot shape passes the policy: " + result.diagnostics(), result.ok());
        if (!result.ok()) {
            return;
        }
        List<String> calls = callSites(result.classes().get("vibemod.rubysword.RubySword"));
        check("its Registry.register went through the host shim (" + calls + ")",
                calls.contains("com/gijsm/vibemod/fabric/shim/Shims.registryRegister"));
        check("its Item.Properties.setId went through the host shim (" + calls + ")",
                calls.contains("com/gijsm/vibemod/fabric/shim/Shims.itemId"));
        check("no raw Registry.register or Properties.setId survived (" + calls + ")",
                !calls.contains("net/minecraft/core/Registry.register")
                        && !calls.contains("net/minecraft/world/item/Item$Properties.setId"));
        check("the builder call that is NOT a seam is untouched (" + calls + ")",
                calls.contains("net/minecraft/world/item/Item$Properties.sword"));
    }

    /** §B: {@code EntityType.Builder.build} and the default-attribute registry. */
    private static void testEntityTypeSeamsAreRewritten(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.critter.Critter", """
                package vibemod.critter;

                import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;

                import net.minecraft.resources.ResourceKey;
                import net.minecraft.world.entity.EntityType;
                import net.minecraft.world.entity.LivingEntity;
                import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

                public final class Critter {

                    public EntityType<?> build(EntityType.Builder<?> builder,
                                               ResourceKey<EntityType<?>> key) {
                        return builder.build(key);
                    }

                    public void attributes(EntityType<? extends LivingEntity> type,
                                           AttributeSupplier.Builder attributes) {
                        FabricDefaultAttributeRegistry.register(type, attributes);
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("a mod building an EntityType passes the policy: " + result.diagnostics(), result.ok());
        if (!result.ok()) {
            return;
        }
        List<String> calls = callSites(result.classes().get("vibemod.critter.Critter"));
        check("EntityType.Builder.build was redirected to the host shim (" + calls + ")",
                calls.contains("com/gijsm/vibemod/fabric/shim/Shims.entityTypeBuild"));
        check("FabricDefaultAttributeRegistry.register was redirected too (" + calls + ")",
                calls.contains("com/gijsm/vibemod/fabric/shim/Shims.defaultAttributes"));
        check("neither original survived (" + calls + ")",
                !calls.contains("net/minecraft/world/entity/EntityType$Builder.build")
                        && calls.stream().noneMatch(c -> c.startsWith("net/fabricmc/fabric/api/object/"
                                + "builder/v1/entity/FabricDefaultAttributeRegistry.")));
    }

    /** §B: the client half — an entity renderer the host can take away again. */
    private static void testEntityRendererSeamIsRewritten(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.critterclient.CritterClient", """
                package vibemod.critterclient;

                import net.fabricmc.api.ClientModInitializer;
                import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

                import net.minecraft.client.renderer.entity.EntityRendererProvider;
                import net.minecraft.world.entity.Entity;
                import net.minecraft.world.entity.EntityType;

                public final class CritterClient implements ClientModInitializer {

                    @Override
                    public void onInitializeClient() {
                    }

                    @SuppressWarnings("deprecation")
                    public void render(EntityType<Entity> type, EntityRendererProvider<Entity> provider) {
                        EntityRendererRegistry.register(type, provider);
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("a mod registering an entity renderer passes the policy: " + result.diagnostics(),
                result.ok());
        if (!result.ok()) {
            return;
        }
        List<String> calls = callSites(result.classes().get("vibemod.critterclient.CritterClient"));
        check("EntityRendererRegistry.register was redirected to the client shim (" + calls + ")",
                calls.contains("com/gijsm/vibemod/fabric/shim/ClientShims.entityRenderer"));
        check("no call to the real EntityRendererRegistry survived (" + calls + ")",
                calls.stream().noneMatch(c -> c.startsWith(
                        "net/fabricmc/fabric/api/client/rendering/v1/EntityRendererRegistry.")));
    }

    // ------------------------------------------------------------------ V4 Phase 1

    /**
     * The one assertion the block seam has to earn: {@code setId} appears twice
     * in the table with an identical parameter list, and the surgeon still
     * tells them apart.
     *
     * <p>{@code Seam.matches} compares owner AND name AND the FULL descriptor.
     * Both rows take a bare {@code (Lnet/minecraft/resources/ResourceKey;)} —
     * generics are erased, so {@code ResourceKey<Item>} and
     * {@code ResourceKey<Block>} are the same bytes — and what separates them
     * is the owner and the return type. This is the check that fires if
     * somebody ever "simplifies" the table to match on name and arity.
     */
    private static void testTheTwoSetIdSeamsAreToldApart() {
        List<Seam> setIds = new ArrayList<>();
        for (Seam seam : FabricSeams.table()) {
            if (seam.name().equals("setId")) {
                setIds.add(seam);
            }
        }
        check("the seam table carries exactly two setId rows (" + setIds.size() + ")",
                setIds.size() == 2);
        if (setIds.size() != 2) {
            return;
        }
        Seam first = setIds.get(0);
        Seam second = setIds.get(1);
        check("the two setId rows have different owners ("
                        + first.owner() + " / " + second.owner() + ")",
                !first.owner().equals(second.owner()));
        check("their erased PARAMETER lists are identical, which is the trap ("
                        + first.descriptor() + ")",
                first.descriptor().substring(0, first.descriptor().indexOf(')'))
                        .equals(second.descriptor().substring(0, second.descriptor().indexOf(')'))));
        check("so it is the return type that separates them ("
                        + first.descriptor() + " / " + second.descriptor() + ")",
                !first.descriptor().equals(second.descriptor()));
        check("and they point at different shims ("
                        + first.shimName() + " / " + second.shimName() + ")",
                !first.shimName().equals(second.shimName()));
        boolean itemRow = setIds.stream().anyMatch(seam ->
                seam.owner().equals("net/minecraft/world/item/Item$Properties")
                        && seam.shimName().equals("itemId"));
        boolean blockRow = setIds.stream().anyMatch(seam ->
                seam.owner().equals("net/minecraft/world/level/block/state/BlockBehaviour$Properties")
                        && seam.shimName().equals("blockId"));
        check("Item$Properties.setId still routes to Shims.itemId", itemRow);
        check("BlockBehaviour$Properties.setId routes to Shims.blockId", blockRow);
    }

    /**
     * Both {@code setId} call sites in one class, rewritten and then actually
     * run.
     *
     * <p>Running is the point. A constant-pool assertion passes just as
     * happily on a shim descriptor that does not exist; defining the class and
     * invoking it turns that into a {@code NoSuchMethodError} at the first
     * call, which is the only proof that the descriptor the table computed and
     * the descriptor {@code Shims.blockId} actually has are the same string.
     *
     * <p>The receivers are null on purpose: the shim is static, the recorder
     * never dereferences them, and constructing a real
     * {@code BlockBehaviour.Properties} would say nothing about the rewrite.
     */
    private static void testBlockSetIdIsRewrittenAndRuns(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.blockid.BlockId", """
                package vibemod.blockid;

                import net.minecraft.resources.ResourceKey;
                import net.minecraft.world.item.Item;
                import net.minecraft.world.level.block.Block;
                import net.minecraft.world.level.block.state.BlockBehaviour;

                public final class BlockId {
                    public BlockBehaviour.Properties block(BlockBehaviour.Properties properties,
                                                           ResourceKey<Block> key) {
                        return properties.setId(key);
                    }

                    public Item.Properties item(Item.Properties properties, ResourceKey<Item> key) {
                        return properties.setId(key);
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("a class calling both setId overloads passes the policy: " + result.diagnostics(),
                result.ok());
        if (!result.ok()) {
            return;
        }
        List<String> calls = callSites(result.classes().get("vibemod.blockid.BlockId"));
        check("BlockBehaviour$Properties.setId went to Shims.blockId (" + calls + ")",
                calls.contains("com/gijsm/vibemod/fabric/shim/Shims.blockId"));
        check("Item$Properties.setId still went to Shims.itemId (" + calls + ")",
                calls.contains("com/gijsm/vibemod/fabric/shim/Shims.itemId"));
        check("neither raw setId survived (" + calls + ")",
                !calls.contains(
                        "net/minecraft/world/level/block/state/BlockBehaviour$Properties.setId")
                        && !calls.contains("net/minecraft/world/item/Item$Properties.setId"));

        RegistryRecorder recorder = new RegistryRecorder();
        Shims.installRegistries(recorder);
        try {
            ClassLoader loader = new ModLifecycle.BytesClassLoader(
                    SurgeonSelfTest.class.getClassLoader(), result.classes());
            Class<?> blockId = loader.loadClass("vibemod.blockid.BlockId");
            Object instance = blockId.getDeclaredConstructor().newInstance();
            net.minecraft.resources.Identifier id =
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("blockid", "thing");
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.block.Block> blockKey =
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.BLOCK, id);
            net.minecraft.resources.ResourceKey<net.minecraft.world.item.Item> itemKey =
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.ITEM, id);
            blockId.getMethod("block",
                            net.minecraft.world.level.block.state.BlockBehaviour.Properties.class,
                            net.minecraft.resources.ResourceKey.class)
                    .invoke(instance, null, blockKey);
            blockId.getMethod("item", net.minecraft.world.item.Item.Properties.class,
                            net.minecraft.resources.ResourceKey.class)
                    .invoke(instance, null, itemKey);

            check("the block setId reached Shims.blockId with the mod's own key",
                    recorder.blockIds.size() == 1 && recorder.blockIds.get(0) == blockKey);
            check("the item setId reached Shims.itemId, and did NOT land on blockId",
                    recorder.itemIds.size() == 1 && recorder.itemIds.get(0) == itemKey);
        } catch (Throwable t) {
            check("defining and running the rewritten setId class threw: " + t, false);
        } finally {
            Shims.installRegistries(null);
        }
    }

    /**
     * The block few-shot, seam by seam: the shape a generated block mod has,
     * with a real {@code BuiltInRegistries.BLOCK}, a real
     * {@code BlockBehaviour.Properties.setId} and the paired {@code BlockItem}.
     *
     * <p>Compiled and rewritten but NOT run, for the same reason
     * {@code RubySword} is not: constructing a {@code Block} outside a
     * registration window is exactly what the window exists to make legal.
     */
    private static void testRubyBlockShapeIsFullySeamed(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.rubyblock.RubyBlock", """
                package vibemod.rubyblock;

                import net.fabricmc.api.ModInitializer;

                import net.minecraft.core.Registry;
                import net.minecraft.core.registries.BuiltInRegistries;
                import net.minecraft.core.registries.Registries;
                import net.minecraft.resources.Identifier;
                import net.minecraft.resources.ResourceKey;
                import net.minecraft.world.item.BlockItem;
                import net.minecraft.world.item.Item;
                import net.minecraft.world.level.block.Block;
                import net.minecraft.world.level.block.state.BlockBehaviour;

                public final class RubyBlock implements ModInitializer {

                    public static Block RUBY_BLOCK;
                    public static Item RUBY_BLOCK_ITEM;

                    @Override
                    public void onInitialize() {
                        Identifier id = Identifier.fromNamespaceAndPath("rubyblock", "ruby_block");
                        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
                        RUBY_BLOCK = Registry.register(BuiltInRegistries.BLOCK, id,
                                new Block(BlockBehaviour.Properties.of()
                                        .strength(3.0F)
                                        .setId(blockKey)));
                        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
                        RUBY_BLOCK_ITEM = Registry.register(BuiltInRegistries.ITEM, id,
                                new BlockItem(RUBY_BLOCK, new Item.Properties()
                                        .useBlockDescriptionPrefix()
                                        .setId(itemKey)));
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("the RubyBlock few-shot shape passes the policy: " + result.diagnostics(), result.ok());
        if (!result.ok()) {
            return;
        }
        List<String> calls = callSites(result.classes().get("vibemod.rubyblock.RubyBlock"));
        long registrations = calls.stream()
                .filter("com/gijsm/vibemod/fabric/shim/Shims.registryRegister"::equals).count();
        check("both Registry.register call sites went through the host shim (" + calls + ")",
                registrations == 2);
        check("its BlockBehaviour.Properties.setId went through the host shim (" + calls + ")",
                calls.contains("com/gijsm/vibemod/fabric/shim/Shims.blockId"));
        check("its Item.Properties.setId went through the host shim (" + calls + ")",
                calls.contains("com/gijsm/vibemod/fabric/shim/Shims.itemId"));
        check("no raw Registry.register or setId survived (" + calls + ")",
                !calls.contains("net/minecraft/core/Registry.register")
                        && !calls.contains("net/minecraft/world/item/Item$Properties.setId")
                        && !calls.contains("net/minecraft/world/level/block/state/"
                                + "BlockBehaviour$Properties.setId"));
        check("the builder calls that are NOT seams are untouched (" + calls + ")",
                calls.contains("net/minecraft/world/level/block/state/"
                        + "BlockBehaviour$Properties.of")
                        && calls.contains("net/minecraft/world/level/block/state/"
                                + "BlockBehaviour$Properties.strength"));
    }

    /**
     * The V4 Phase 1 denial: per-block tint colours.
     *
     * <p>{@code BlockColors} is built once per client out of every registration
     * made before the build, and has no unregister — so a tint outlives the mod
     * that added it, for the rest of the session. That is the same objection
     * that keeps {@code HudElementRegistry.removeElement} off the seam table.
     * A coloured texture is the same picture and goes away with the pack.
     *
     * <p>{@code BlockRenderLayerMap} is deliberately NOT denied: it does not
     * exist in 26.2, so a model that writes it already gets "cannot find
     * symbol" from javac, which names the line and is the better message.
     *
     * <p>The fixture reaches the class without calling any of its methods, and
     * that is the honest shape of this denial rather than a shortcut around
     * one: the {@code Denial} is written with a null member, so it forbids the
     * <em>type</em>. {@code BytecodeSurgeon.Scan.constant} routes a class
     * literal through {@code type()}, which is the same check every
     * {@code invokestatic} to it would hit. Pinning a method signature here
     * would only make the gate break on a fabric-api bump that renamed it,
     * while the thing under test — that no generated mod may touch this class
     * at all — would be no better proven.
     */
    private static void testBlockColorRegistryRejected(InMemoryCompiler compiler) {
        Map<String, byte[]> classes = compile(compiler, "vibemod.tint.Tint", """
                package vibemod.tint;

                import net.fabricmc.api.ClientModInitializer;
                import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;

                public final class Tint implements ClientModInitializer {

                    @Override
                    public void onInitializeClient() {
                    }

                    public Class<?> tint() {
                        return BlockColorRegistry.class;
                    }
                }
                """);
        ClassSurgeon.Result result = fabricSurgeon().operate(classes);
        check("BlockColorRegistry is rejected", !result.ok());
        check("the tint diagnostic names the mechanism and the fix ("
                        + firstLine(result.diagnostics()) + ")",
                result.diagnostics().contains("cannot be unregistered")
                        && result.diagnostics().contains("coloured texture"));
    }

    // ------------------------------------------------------------------ plumbing

    /** Records what a rewritten registry call site handed the shim (V3 Phase 3 §A). */
    private static final class RegistryRecorder
            implements com.gijsm.vibemod.fabric.shim.RegistryTarget {

        private final List<Object> ids = new ArrayList<>();
        private final List<Object> values = new ArrayList<>();
        /** Kept apart from {@link #ids} so a block key landing on itemId is a FAIL, not a pass. */
        private final List<Object> blockIds = new ArrayList<>();
        private final List<Object> itemIds = new ArrayList<>();

        @Override
        public Object register(net.minecraft.core.Registry<?> registry, Object id, Object value) {
            ids.add(id);
            values.add(value);
            return value;
        }

        @Override
        public net.minecraft.core.Holder.Reference<?> registerForHolder(
                net.minecraft.core.Registry<?> registry, Object id, Object value) {
            register(registry, id, value);
            return null;
        }

        @Override
        public net.minecraft.world.item.Item.Properties itemId(
                net.minecraft.world.item.Item.Properties properties,
                net.minecraft.resources.ResourceKey<net.minecraft.world.item.Item> key) {
            ids.add(key);
            itemIds.add(key);
            return properties;
        }

        @Override
        public net.minecraft.world.level.block.state.BlockBehaviour.Properties blockId(
                net.minecraft.world.level.block.state.BlockBehaviour.Properties properties,
                net.minecraft.resources.ResourceKey<net.minecraft.world.level.block.Block> key) {
            ids.add(key);
            blockIds.add(key);
            return properties;
        }

        @Override
        public net.minecraft.world.entity.EntityType<?> entityTypeBuild(
                net.minecraft.world.entity.EntityType.Builder<?> builder,
                net.minecraft.resources.ResourceKey<net.minecraft.world.entity.EntityType<?>> key) {
            ids.add(key);
            return null;
        }

        @Override
        public void defaultAttributes(
                net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.LivingEntity> type,
                net.minecraft.world.entity.ai.attributes.AttributeSupplier supplier) {
            values.add(supplier);
        }

        @Override
        public void defaultAttributes(
                net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.LivingEntity> type,
                net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder builder) {
            values.add(builder);
        }
    }

    /** Records what the rewritten bytecode handed the shim. */
    private static final class Recorder implements com.gijsm.vibemod.fabric.shim.EventSeam {
        private final List<Event<?>> events = new ArrayList<>();
        private final List<Object> listeners = new ArrayList<>();

        @Override
        public void register(Event<?> event, Object listener) {
            events.add(event);
            listeners.add(listener);
        }
    }

    /** A minimal {@code Event} that records whether the REAL register was reached. */
    private static final class StubEvent extends Event<Runnable> {
        private int registered;

        @Override
        public void register(Runnable listener) {
            registered++;
        }
    }

    private static BytecodeSurgeon fabricSurgeon() {
        return FabricSeams.surgeon();
    }

    private static InMemoryCompiler compiler() {
        String raw = System.getProperty("vibemod.surgeon.cp", "");
        List<Path> entries = new ArrayList<>();
        for (String part : raw.split(File.pathSeparator)) {
            if (!part.isBlank()) {
                entries.add(Path.of(part));
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("-Dvibemod.surgeon.cp was not set: the fixtures need the "
                    + "same compile classpath the host gives generated mods");
        }
        return new InMemoryCompiler(
                CompilerProvider.resolve().orElseThrow(() ->
                        new IllegalStateException("no Java compiler backend on this runtime")),
                () -> entries,
                Runtime.version().feature());
    }

    private static Map<String, byte[]> compile(InMemoryCompiler compiler, String fqcn, String source) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(fqcn, source);
        CompileResult result = compiler.compile(sources);
        if (!result.success()) {
            throw new IllegalStateException("fixture " + fqcn + " did not compile:\n" + result.diagnostics());
        }
        return result.classes();
    }

    /**
     * Every {@code owner.name} a class actually invokes.
     *
     * <p>Read off the instructions rather than the constant pool, because the
     * classfile API shares the pool when it transforms: an entry the rewrite
     * orphaned is still in the bytes, so a byte-level search for
     * {@code "register"} would report a call that no longer exists.
     */
    private static List<String> callSites(byte[] classFile) {
        List<String> calls = new ArrayList<>();
        java.lang.classfile.ClassModel model = java.lang.classfile.ClassFile.of().parse(classFile);
        for (java.lang.classfile.ClassElement element : model) {
            if (!(element instanceof java.lang.classfile.MethodModel method)) {
                continue;
            }
            method.code().ifPresent(code -> {
                for (java.lang.classfile.CodeElement instruction : code) {
                    if (instruction instanceof java.lang.classfile.instruction.InvokeInstruction invoke) {
                        calls.add(invoke.owner().asInternalName() + "." + invoke.name().stringValue());
                    }
                }
            });
        }
        return calls;
    }

    /** Whether the raw class bytes contain a UTF-8 constant with this text. */
    private static boolean mentions(byte[] classFile, String text) {
        byte[] needle = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + needle.length <= classFile.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (classFile[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean identical(Map<String, byte[]> before, Map<String, byte[]> after) {
        if (!before.keySet().equals(after.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> entry : before.entrySet()) {
            if (!Arrays.equals(entry.getValue(), after.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static String firstLine(String text) {
        if (text == null || text.isEmpty()) {
            return "(no diagnostics)";
        }
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            System.out.println("  ok: " + what);
        } else {
            System.out.println("  FAIL: " + what);
            failures++;
        }
    }

    private SurgeonSelfTest() {
    }
}
