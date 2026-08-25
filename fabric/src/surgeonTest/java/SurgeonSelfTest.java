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

    // ------------------------------------------------------------------ plumbing

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
