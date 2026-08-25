# Phase 0 brief — BytecodeSurgeon, Shims event bus, ModInitializer entrypoint, SymbolOracle

Read `docs/ARCHITECTURE-V2.md` §4/§8 and skim `README.md` first. The full V3 design is the approved plan (context: generated mods become *ordinary Fabric mods*; the host intercepts at bytecode seams). This brief is the Phase 0 spec.

## Goal (the thesis test)

A generated mod written as a **plain Fabric mod** — a class implementing `net.fabricmc.api.ModInitializer`, registering to real Fabric API events (`ServerTickEvents.END_SERVER_TICK`, `AttackBlockCallback.EVENT`), with **zero VibeMod imports** — hot-loads into the live game, runs, `/vibe disable` drains it to zero, `/vibe enable` brings it back. Achieved by rewriting `Event.register` call sites in the compiled bytecode into a host shim before `defineClass`.

## Hard constraints (violating any of these breaks the build)

1. `core` and `platform-api` compile `--release 21` and **ban `net.minecraft` imports** (gradle check tasks). `sdk-client` must stay pure JDK.
2. `loader-common/src/main/java` is a **shared source dir** compiled into BOTH `:fabric` and `:neoforge` (Java 25). It may use `java.lang.classfile` (verified present on the Temurin 25 toolchain) and `net.minecraft.*`, but **must not reference `net.fabricmc.*` or `net.neoforged.*`** — anything loader-specific goes in `fabric/` or `neoforge/` and is injected.
3. `net.fabricmc.api.ModInitializer` therefore **cannot be named in loader-common**. The entrypoint check is injected per loader (see §D).
4. All existing gates stay green: `./gradlew build` (all selfTests + fixture corpus — legacy `Mod`/`VibeContext` mods must keep compiling and loading), `scripts/smoke-fabric.sh`, `scripts/smoke-neoforge.sh`, `./gradlew :fabric:runClientGameTest`.
5. Never edit generated/stored mod fixtures to make tests pass; fix the host.
6. Verify any game/loader signature you need beyond the table below with `javap -p -cp ~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar <fqcn>` (fabric-api jars under `~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/`). Never write a call/rewrite from memory.

## Verified 26.2 / fabric-api 0.158.0+26.2 facts (already javap'd — trust these)

- `net.fabricmc.fabric.api.event.Event<T>` (abstract class, fabric-api-base 2.0.4): `public abstract void register(T)` → erased descriptor `(Ljava/lang/Object;)V`, invoked via `invokevirtual`; also `public void register(Identifier, T)` and `public void addPhaseOrdering(Identifier, Identifier)`.
- `EventFactory.createArrayBacked(Class<? super T>, Function<T[],T>)` and `(Class<T>, T, Function<T[],T>)`.
- Interaction callbacks return `net.minecraft.world.InteractionResult` (`AttackBlockCallback: InteractionResult interact(Player, Level, InteractionHand, BlockPos, Direction)`); `ServerLivingEntityEvents.ALLOW_DAMAGE: boolean allowDamage(LivingEntity, DamageSource, float)`; Fabric `TriState` exists for some events.
- `ResourceLocation` is now `net.minecraft.resources.Identifier`.
- `Commands.performPrefixedCommand(CommandSourceStack, String)`; `CommandSourceStack.withSuppressedOutput()`.
- Class files are major 69 (Java 25); generated code is compiled by `InMemoryCompiler` at `--release min(runtime, backend, host)`.

## Existing machinery to reuse (read these files before designing)

- Load path: `core/src/main/java/com/gijsm/vibemod/runtime/ModLifecycle.java` (BytesClassLoader parent = Knot at :221-247; `load/disable/enable/unload/panic`; `disableInternal` → `handle.drain()`), `core/.../runtime/ModHandle.java` (Kind enum, `track`, `drain`), `ModDispatch` (watchdog + journal wrapper — every mod callback must go through it).
- Host: `loader-common/.../LoaderModHost.java` (`activate` at :82-114 does `instanceof Mod` + `onEnable`), `LoaderEventBridge.java` (the AND-of-votes/no-short-circuit `every()` policy at :201-222 — copy those semantics), `LoaderCommandBridge.java`.
- Compiler: `core/.../compile/InMemoryCompiler.java` (`compile()` never throws; diagnostics formatted in `formatDiagnostics` around :246-282), `CompileResult.java`, `CompilerProvider` (javac or bundled ECJ — the oracle must handle both diagnostic wordings).
- Generation: `core/.../gen/ModGenerator.java` (compile at :324-326, repair round at :327-339 via `PromptLibrary.repairPrompt(diagnostics)` :397; enable-failure repair at :356-368). Boot restore: `fabric/.../VibeModFabric.java` `restoreModsFromDisk`/`applyStoredVersion` (~:619-671) — it also calls `InMemoryCompiler`, so a hook inside the compiler gates every path.
- Prompts: `core/.../llm/PromptLibrary.java` (skeleton; Bukkit-flavored blocks at :155-161 and :176-187 leak into loader prompts; ":237" hardcodes "two examples"), `PlatformProfiles.java` (FABRIC at :329-339), `LoaderExamples.java`. API sources embedded at build time: `core/build.gradle.kts:70-130` (hand-listed map). `core/src/test/java/LlmSelfTest.java` asserts prompt contents.
- Gates: `fabric/src/gametest/.../VibeModClientGateTest.java` (canary Java text blocks + `meta()` + `check()`/`describeState()` pattern), `scripts/smoke-fabric.sh` (heredoc canaries + RCON asserts), `scripts/smoke-neoforge.sh`.

## Deliverables

### A. Surgeon (loader-common) + core seam interface
- `platform-api/.../platform/ClassSurgeon.java` (Java-21-clean): `Result operate(Map<String, byte[]> classes)` where `Result` is either transformed classes or javac-style diagnostics (`String`). Wire into `InMemoryCompiler`: `setSurgeon(ClassSurgeon)`; applied after successful compile; diagnostics → failed `CompileResult` (feeds the existing self-heal loop). Null surgeon (Paper) = pass-through.
- `loader-common/.../surgeon/BytecodeSurgeon.java` implements it using `java.lang.classfile` (JDK 25, zero deps). One pass per class:
  1. **Policy verify** — allowlist of referenced package roots: `java/`, `net/minecraft/`, `com/mojang/`, `org/joml/`, `it/unimi/`, `net/fabricmc/api/`, `net/fabricmc/fabric/api/`, `vibemod/` (generated code's own package). Denies inside allowed roots: `java/lang/reflect/`, `java/lang/invoke/MethodHandles`, `Thread` construction/start, `Executors`/`ForkJoinPool`/`CompletableFuture.*Async`, `Runtime`/`ProcessBuilder`/`System.exit`, `java/net/` (except `java/net/URI`), `org/spongepowered/`, `net/fabricmc/loader/`, `net/fabricmc/fabric/impl/`, `net/fabricmc/fabric/mixin/`, `Event.addPhaseOrdering`. Allow bootstrap methods `LambdaMetafactory`, `StringConcatFactory`, `ObjectMethods`, `SwitchBootstraps` (lambdas/records/switch must pass — test this). Report violations as `<Class>.java: error: forbidden API: <detail>` lines.
  2. **Seam rewrite** — driven by an injected seam table (`List<Seam>`: owner FQCN + method name + descriptor → static shim FQCN/method with receiver prepended). Phase 0 table (supplied by the Fabric host): `Event.register(Object)` → `Shims.eventRegister(Event, Object)`, `Event.register(Identifier, Object)` → same shim (ignore phase). NeoForge supplies an empty table plus a *deny* entry that turns any `net/fabricmc/` reference into a policy diagnostic ("Fabric API seams are not available on NeoForge yet").
- Config of table + policy variant comes from the host at wiring time (`VibeModFabric.Boot`), not hardcoded per loader inside loader-common.

### B. Shims + fanout bus (fabric module)
- `fabric/.../shim/Shims.java` (static entry points called by rewritten bytecode; must be resolvable from Knot — it's in the host jar, parent-first, so it is) delegating to `fabric/.../shim/EventFanout.java`:
  - One permanent fanout per distinct `Event` instance: first registration builds a `java.lang.reflect.Proxy` over the callback interface and calls `event.register(proxy)` once, forever (Fabric events can't unregister — same rationale as the existing bridges).
  - Callback interface discovery: from the listener object's interfaces, pick the single `@FunctionalInterface`/SAM that the listener implements (a lambda implements exactly one). Validate it.
  - Dispatch: iterate live `Bound(modName, handle, listener)` entries **through `ModDispatch.run`** with the mod attribution set (§C). Merge results: `void` → all run; `boolean` → AND (no short-circuit — match `LoaderEventBridge.every` semantics: every handler runs, thrower = no vote); `InteractionResult` → first non-`PASS`; `TriState` → first non-`DEFAULT`; other → first non-null, else null/default.
  - Thread guard: if invoked off the expected thread (`server.isSameThread()` false for server events), log once per event and skip mod dispatch. Registration-time denylist for callback types under `net.fabricmc.fabric.api.client.` (Phase 1 lifts this for the client entrypoint).
  - Registration returns a `Registration` tracked on the current mod's handle as a new `ModHandle.Kind.NATIVE` (add the enum constant; `drain()` closes it like the rest — check `countOf` users).
  - `describeState()` → `"<eventClass>=<n> ..."` counts for gates.
  - Immediate-replay: if a mod registers `ServerLifecycleEvents.SERVER_STARTING/STARTED` while the server is already running, invoke the listener immediately (via dispatch). `CommandRegistrationCallback` gets a diagnostic pointing to Phase 1 for now — DO NOT silently drop: throw `UnsupportedOperationException("commands land in Phase 1; use …")`? No — better: implement nothing special, the registration lands in the fanout and simply never fires; that is a silent drop, which violates the house rule. So: maintain a small set of "registration-style" event classes; registering one of those in Phase 0 throws UOE with a clear message. Document in the prompt that commands come later (or implement Phase 1 early if trivial — do NOT; keep scope).
- ThreadLocal `ModAttribution` (loader-common): `current()` / `runAs(handle, Runnable)`. Set around entrypoint init and around every fanout dispatch so nested registrations attribute correctly.

### C. Entrypoint path (loader-common + fabric)
- `LoaderModHost` gains an injected `EntrypointAdapter` (loader-common interface): `Runnable adapt(Object instance)` returning null if the object isn't a native entrypoint. Fabric implementation (in `fabric/`): `obj instanceof ModInitializer mi ? mi::onInitialize : null`. NeoForge passes an adapter that always returns null.
- `activate(...)`: if the adapter matches, run `onInitialize()` under `ModAttribution.runAs(handle, …)` + `ModDispatch` (a throw = `ModLoadException(where="onInitialize")`, repair-round like `onEnable`); else fall through to the existing `instanceof Mod` path unchanged. `deactivate`: native mods have no onDisable — just drain.
- Store/meta: mods whose mainClass implements neither → existing error. No meta schema change needed if detection is by interface; add `"flavor": "fabric"` to meta only if something forces it (prefer not).

### D. SymbolOracle (core) + prompt hygiene
- `core/.../compile/SymbolOracle.java`: constructed with `Function<String, Class<?>>` resolver (hosts pass `n -> Class.forName(n, false, VibeModFabric.class.getClassLoader())`). Input: the diagnostics string (or better: hook the `Diagnostic` objects in `formatDiagnostics` and record structured (symbol, owner) pairs on the `CompileResult`). Handle javac (`cannot find symbol` + `symbol:`/`location:` lines) and ECJ (`The method X(..) is undefined for the type Y`, `X cannot be resolved`). For owners under `net.minecraft.`, `com.mojang.`, `net.fabricmc.`, `com.gijsm.vibemod.api.`: list public members fuzzy-matching the missing name (containment, then Levenshtein ≤ 3), cap 12 per symbol / 3 kB total, formatted as an `API HINTS` block.
- `PromptLibrary.repairPrompt(String diagnostics, String hints)` overload; `ModGenerator.setSymbolOracle(...)` used in both repair paths; wire in `VibeModFabric` (and `VibeModNeoForge` — the resolver works there too).
- Prompt hygiene: move the Bukkit-only blocks (`PromptLibrary.java:155-161`, `:176-187`) into the Paper profile strings in `PlatformProfiles.java`; fix the hardcoded "two examples" to use the actual few-shot count; `LlmSelfTest`: loader prompts must NOT contain `Bukkit.`, `ctx.listen`, `spawnEntity(`; Paper prompt still must.

### E. FABRIC profile v2 (core, text only)
- New system-prompt profile for Fabric generations: "You write a normal Fabric mod for Minecraft 26.2, official Mojang mappings, Java 25. Your main class implements `net.fabricmc.api.ModInitializer`. There is no `fabric.mod.json` and no mixins — the host loads you and can unload you at any time; register everything through the normal Fabric API and it is tracked automatically. No reflection, no threads, no sockets, no `Event.addPhaseOrdering`. Commands/keybinds/HUD/registries land in later phases — do not use `CommandRegistrationCallback`, `KeyBindingHelper`, `HudElementRegistry`, or `Registry.register` yet." Plus a short Yarn→Mojang rename table (`World`→`Level`, `PlayerEntity`→`Player`, `ServerPlayerEntity`→`ServerPlayer`, `Text`→`Component`, `Identifier` stays, `BlockPos` stays, `MinecraftServer` stays…), the 26.2 renames (`ResourceLocation`→`Identifier`), and ONE few-shot rewritten as a plain Fabric mod (e.g. BlockTally: `AttackBlockCallback`/`PlayerBlockBreakEvents.BEFORE` + tick-based announce via `ServerTickEvents`). Keep the legacy loader profile intact for stored-mod recompiles; select v2 for new Fabric generations (`PlatformProfiles.byId("fabric")` → v2). Legacy stored mods still compile because the API flavor sources remain on the classpath and the surgeon's policy allows `com/gijsm/vibemod/api/`? — NOTE: add `com/gijsm/vibemod/` to the policy allowlist so legacy VibeContext mods pass the surgeon too.
- Output contract: unchanged JSON (files[], mainClass, config[] etc.). `config[]` knobs: for native mods there is no ctx.config — either keep knobs out of v2 few-shots and mark config optional, or (preferred, trivial) note in the prompt that config is not yet available in v2 and omit `config[]`.

### F. Tests & gates (all must run in `./gradlew build` or the scripts)
1. Surgeon self-test: a new JavaExec (e.g. `:fabric:surgeonSelfTest`, own source set at Java 25 with loader-common on the compile path, wired into `check`) that compiles-in-memory (or pre-bakes with javac at build time) fixtures: (a) lambda+record+switch class passes policy; (b) reflection use rejected; (c) Thread.start rejected; (d) `Event.register` call is rewritten (assert the constant pool now references Shims, and/or actually `defineClass` + invoke against a stub Event); (e) legacy VibeContext-style class passes untouched.
2. `LlmSelfTest` additions per §D/§E (hygiene, v2 profile selection, budget print).
3. `scripts/smoke-fabric.sh`: add a `NativeCanary` heredoc mod — plain `ModInitializer`, `ServerTickEvents.END_SERVER_TICK` counter that logs once, and an `AttackBlockCallback` registration; assert it goes live, assert `vibe disable NativeCanary` then `enable` round-trips (RCON), assert no `UnsupportedOperationException`. Existing SmokeCanary asserts unchanged.
4. `VibeModClientGateTest`: add a `NativeCanary` (same idea, singleplayer): loads at boot, tick fires (marker file), disable → `EventFanout.describeState()` shows zero for it → enable → fires again. Keep all 29 existing checks passing.
5. `scripts/smoke-neoforge.sh`: still 28/28; add one canary that tries a `net.fabricmc.*` import on NeoForge and assert the clear diagnostic (compile-time policy message), not a crash.

## Working rules
- javap-verify every signature you touch that isn't in the table. Use the Edit tool for source changes (no sed-over-source), keep files focused, follow the repo's comment style (explain *why*, reference ARCHITECTURE sections).
- Run `./gradlew build` and `scripts/smoke-fabric.sh` yourself before reporting; report actual outputs honestly. `:fabric:runClientGameTest` needs a display — run it; if the environment truly can't, say so explicitly.
- Do not commit; the architect reviews and commits.
- Write a summary of decisions + deviations into `docs/phases/PHASE-0-RESULT.md`.
