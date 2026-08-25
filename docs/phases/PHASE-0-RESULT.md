# Phase 0 result — the seam architecture, built and gated

All six deliverables (A–F) landed. All four gates are green, including the one
that matters: on a real Fabric dedicated server and inside a real Minecraft
client, a mod written as a **plain Fabric mod** — `implements
net.fabricmc.api.ModInitializer`, registering to `ServerTickEvents
.END_SERVER_TICK` and `AttackBlockCallback.EVENT`, with zero VibeMod imports —
hot-loads, runs, drains to zero on `/vibe disable`, and comes back on
`/vibe enable`.

---

## What landed, by deliverable

### A. Surgeon + the core seam

| File | What |
| --- | --- |
| `platform-api/.../platform/ClassSurgeon.java` | Java-21-clean SPI: `Result operate(Map<String, byte[]>)`, `Result` = accepted classes or javac-shaped diagnostics |
| `core/.../compile/InMemoryCompiler.java` | `setSurgeon(ClassSurgeon)`; applied after a successful compile, rejection → failed `CompileResult` |
| `loader-common/.../surgeon/BytecodeSurgeon.java` | The pass, on `java.lang.classfile` (JDK 25, zero deps) |
| `loader-common/.../surgeon/SurgeonPolicy.java` | Allowlisted package roots + deny table, both host-supplied |
| `loader-common/.../surgeon/Seam.java` | One redirected call site; `prependingReceiver` derives the shim descriptor so the two cannot drift |
| `fabric/.../FabricSeams.java` | The Fabric table (2 entries) and the host's surgeon |

The hook is on the **compiler**, not on any one code path, because every route
from source to live classes runs through `InMemoryCompiler.compile`:
generation, repair rounds, `/vibe edit`, rollback, restore-on-boot. One hook
gates all of them, and a policy violation reaches the model through the
existing self-heal loop rather than through a second error channel.

**The scan walks instructions, not the constant pool** — and that is a
correctness decision the self-test forced, not a stylistic one. Lambdas,
records and pattern switches all put `java.lang.invoke.MethodHandle` into the
constant pool, so a pool-level "no method handles" rule rejects the most
ordinary Java there is. Walking instructions lets the rule be the right one: a
dynamic call site is fine when its *bootstrap* is one javac emits, and the
method handles javac threads through those bootstraps are checked as the
ordinary member references they are. That is why `Thread::start` is caught even
though it appears nowhere as an `invokevirtual` — there is a self-test for
exactly that.

The rewrite is shape-preserving: `invokevirtual` → `invokestatic` with the
receiver prepended to the descriptor, so the operand stack before and after is
identical and no frame recomputation happens. A class with no seam hit is
returned **byte-identical** — asserted, not assumed, which is what makes the
legacy `VibeContext` corpus provably unaffected.

### B. Shims + fanout (fabric)

`fabric/.../shim/{Shims, EventSeam, EventFanout}.java`, plus
`loader-common/.../ModAttribution.java`.

- One permanent `Proxy` per distinct `Event` instance, registered once, forever
  — process-lived, built in `onInitialize()`, for the same reason every other
  host subscription is (§10.3: a client that loads a second world must not
  leave a dead proxy behind).
- Callback interface discovered from the listener's own interfaces; ambiguity
  is an error, not a guess.
- Dispatch runs through `ModDispatch` with `ModAttribution.runAs` around each
  listener, so a mod registering from inside its own tick handler attributes
  correctly.
- Merge policy copies `LoaderEventBridge.every()` exactly: `void` → all run;
  `boolean` → AND with **no short-circuit**, a thrower casts no vote;
  `InteractionResult` → first non-`PASS`; `TriState` → first non-`DEFAULT`;
  other reference → first non-null.
- Thread guard: off the server thread, one log line per event and no mod
  dispatch.
- `ModHandle.Kind.NATIVE` added (plus `nativeCount()`); `drain()` closes these
  like every other registration.
- `describeState()` → `"AttackBlockCallback=1 ServerTickEvents.EndTick=1 total=2"`.
- Immediate replay for `SERVER_STARTING`/`SERVER_STARTED` when the server is
  already running.
- Refused loudly, never silently: client callbacks
  (`net.fabricmc.fabric.api.client.*`), registration-style events
  (`CommandRegistrationCallback` and friends), and unsupported return types all
  throw `UnsupportedOperationException` naming the phase that will support
  them.

### C. Entrypoint path

`loader-common/.../EntrypointAdapter.java` (injected, so `loader-common` never
names `net.fabricmc.*`), `fabric/.../FabricEntrypointAdapter.java`,
`LoaderModHost.activate/deactivate`.

Detection is by interface, so **no meta.json change** was needed — a fact about
the bytes rather than a claim in a file that could disagree with them. A native
mod's `Activation` carries no instance and no context; `deactivate` therefore
does nothing and draining the handle *is* the teardown.

### D. SymbolOracle + prompt hygiene

`core/.../compile/SymbolOracle.java`, wired on both hosts via
`SymbolOracle.forLoader(...)` and `ModGenerator.setSymbolOracle(...)`, used in
both repair paths. `PromptLibrary.repairPrompt(diagnostics, hints)` overload
added; passing `null`/blank hints reproduces the old prompt byte-for-byte
(asserted).

Hygiene: the Bukkit-only registration block and craft advice moved out of the
shared skeleton into `PlatformProfiles.PAPER_REGISTRATION` / `PAPER_CRAFT`; the
hardcoded "two examples" now counts the profile's actual few-shots.
`LlmSelfTest` asserts both directions — `Bukkit.`, `ctx.listen` and
`spawnEntity(` are gone from all three loader prompts and still present in both
Paper ones.

### E. Native Fabric profile

`PlatformProfiles.FABRIC` (id `fabric`) is now the native profile;
the v2 one survives as `FABRIC_LEGACY` (id `fabric-legacy`).
`NativeFabricExamples` carries its single few-shot — `BlockTally`, deliberately
the same mod as the v2 gameplay few-shot, rewritten as a plain Fabric mod, so
the two profiles stay honestly comparable. Every signature in it was read off
the jars with `javap`.

Prompt budgets, printed by `LlmSelfTest`:

```
  paper-modern     22977 chars  ~  5744 tokens  (2 few-shots)
  paper-legacy     23980 chars  ~  5995 tokens  (2 few-shots)
  fabric           13941 chars  ~  3485 tokens  (1 few-shot)
  fabric-legacy    33773 chars  ~  8443 tokens  (3 few-shots)
  neoforge         33778 chars  ~  8444 tokens  (3 few-shots)
```

The native profile is **59% smaller** than the one it replaces, on every round
of every generation, and it teaches a larger surface — because it teaches
nothing at all. The model already knows Fabric.

### F. Tests and gates

- `:fabric:surgeonSelfTest` — new source set (`fabric/src/surgeonTest`), wired
  into `check`, 28 assertions. It compiles fixtures with the *same*
  `InMemoryCompiler` against the *same* classpath the host uses, then defines
  and **runs** the rewritten class against a recording shim. A rewrite that
  produced unverifiable bytecode, or pointed at a method that does not exist,
  passes a constant-pool assertion and fails here.
- `LlmSelfTest` — hygiene, native-profile content and absences, budgets, and a
  `SymbolOracle` suite covering both javac's and ECJ's wordings.
- `scripts/smoke-fabric.sh` — `NativeCanary` added (+12 asserts, 37 total).
- `scripts/smoke-neoforge.sh` — `FabricOnNeo` added (+3 asserts, 31 total).
- `VibeModClientGateTest` — `NativeCanary` added (+9 asserts, 36 total).

---

## Deviations from the brief, and why

**1. The oracle parses the diagnostics string; it does not hook `Diagnostic`
objects.** §D offered both and preferred the structured route. The string won
for two reasons. javac's `getMessage(null)` already *contains* the
`symbol:`/`location:` lines, so the structured route buys nothing there; and
ECJ's `Diagnostic` objects carry a differently-shaped message that would have
needed string parsing anyway. Parsing the formatted text handles both backends
in one place, leaves `CompileResult` untouched, and works on a diagnostics
string that has been stored, logged or handed across a thread.

*Consequence, recorded honestly:* ECJ names types by their **simple** name
("undefined for the type `LivingEntity`"), which is unresolvable on its own. The
oracle harvests every fully-qualified type name mentioned anywhere in the same
diagnostics and uses that as a symbol table. It works in practice (there is a
self-test) and fails closed — an unresolvable owner produces no hint.

**2. `onInitialize` is not routed through `ModDispatch`.** §C asked for
"`ModAttribution.runAs` + `ModDispatch`". Those two requirements conflict:
`ModDispatch` swallows by contract, and the same sentence requires a throw to
become `ModLoadException(where="onInitialize")` so the caller rolls the
activation back and the generator gets its repair round. Routing through
`ModDispatch` *and* rethrowing would journal the failure twice
(`ModDispatch.report` → `markFailure`, then `ModLifecycle.activate` →
`errors.note`). It mirrors the `onEnable` path directly above it instead — plain
try/catch inside `ModAttribution.runAs` — which is journalled the same way for
the same reason. Every *later* dispatch into the mod (the whole fanout) does go
through `ModDispatch`, which is where the house rule actually bites.

**3. `PlatformProfile` gained two fields.** §E's "config is not available in v2"
and the `mainClass implements X` line are both profile-dependent text that lived
in the shared skeleton. Rather than special-casing in `PromptLibrary`, the
profile now carries `entrypointName` and `configContract`. Five construction
sites updated; nothing else reads the record positionally.

**4. `byId("fabric")` → native, and the v2 profile moved to a new id.** §E asked
for both "`byId("fabric")` → v2" and "keep the legacy loader profile intact".
Two profiles cannot share an id, so `FABRIC_LEGACY` took `fabric-legacy`. Stored
mods are unaffected: recompiles do not consult a profile at all (they go
straight to `InMemoryCompiler`), and `meta.json` stamps `platformName()`
(`"fabric"`), not a profile id. `LlmSelfTest`'s loader-neutrality assertion now
pairs NeoForge with `FABRIC_LEGACY`, which is the pairing that is still true.

**5. `ConstantBootstraps` had to be allowed for dynamic *constants*.** Not
foreseen by the brief, found by the self-test the brief asked for ("lambdas/
records/switch must pass — test this"): javac compiles a pattern switch's `null`
label into a dynamic constant bootstrapped by `ConstantBootstraps.nullConstant`,
so the four-bootstrap allowlist rejected an ordinary `switch`. It is allowed for
constants and *not* for call sites — `ConstantBootstraps.invoke` can call an
arbitrary method handle — and even there its arguments are walked as ordinary
member references, so an argument reaching for something forbidden is still
caught.

**6. The NeoForge canary asserts on javac's diagnostic, not the surgeon's.**
§F.5 asked for "the clear diagnostic (compile-time policy message)". On a real
NeoForge server the Fabric API is not on the compile classpath at all, so javac
gets there first and the surgeon never sees the class:

```
Stored version failed to compile: [ERROR] /vibemod/fabriconneo/FabricOnNeo.java:3
  - package net.fabricmc.api does not exist
```

That is a clear compile-time refusal and not a crash, which is what the
requirement is for. The **policy** message the brief specifies is still
implemented (NeoForge installs a `SurgeonPolicy` denial on `net/fabricmc/`, for
the dev-run case where those classes *are* present) and is asserted directly in
`:fabric:surgeonSelfTest`:

```
WrongLoader.java: error: forbidden API: net.fabricmc.api.ModInitializer
  — Fabric API seams are not available on NeoForge yet
```

Both halves are gated; they are just gated in the two places each can actually
be reached.

**7. Immediate-replay is implemented but not gated.** Firing
`SERVER_STARTING`/`SERVER_STARTED` for a mod hot-loaded after the fact is in
`EventFanout.replayIfLate`. Neither smoke gate exercises it, because
restore-on-boot runs *during* `SERVER_STARTING` — the honest test needs a mod
generated mid-session, which needs an LLM. Flagged for Phase 1, where a
`/vibe make` round is already in scope.

---

## Notes worth carrying into Phase 1

- **`describeState()` counts persist at zero after a disable, by design.** Fans
  are never removed (their subscriptions cannot be undone), so
  `ServerTickEvents.EndTick=0` is the shape of a working teardown, and the
  client gate asserts exactly that.
- **The boolean default is `TRUE`.** A boolean event with no listeners returns
  the permissive answer, matching `LoaderEventBridge.every()`. Every Fabric
  boolean event checked reads `true` as "allow"; an event that reads it as
  "handled" would be merged wrongly. No such event is in Phase 0's reach, but it
  is the assumption to revisit when the surface widens.
- **Unsupported return types are refused at registration time**, not silently
  mis-merged at dispatch time. `int`/`float`/`long`-returning events will need a
  per-event merge rule when one shows up.
- **`Event.register` via a method reference is a policy error**, with a message
  telling the model to call it directly. The seam can only intercept a real call
  site, so this is the one place the rewrite is not transparent — and it says so
  rather than leaking an untracked subscription.
- The Paper host still passes no surgeon; null is a pass-through and
  `smoke-paper.sh` is untouched.

---

## Gate results

All four run on this machine, on the final tree. Verbatim tails.

### 1. `./gradlew build`

```
> Task :core:selfTest
> Task :core:test SKIPPED
> Task :core:check
> Task :core:build

BUILD SUCCESSFUL in 4s
46 actionable tasks: 35 executed, 11 from cache
```

`:fabric:surgeonSelfTest` runs inside `check`, so it is part of this:

```
  ok: lambdas, records, pattern switches and string concat pass the policy:
  ok: ordinary Java with no seam call is returned byte-identical
  ok: reflection is rejected
  ok: the reflection diagnostic is javac-shaped and names the file (Reflect.java: error: forbidden API: java.lang.reflect.Method — reflection (a mod the host can unload must not reach around its own class loader))
  ok: the reflection diagnostic explains itself
  ok: starting a thread is rejected
  ok: the thread diagnostic names Thread (Threads.java: error: forbidden API: java.lang.Thread.<init> — creating threads (mod code runs on the server thread))
  ok: a method reference to Thread.start is rejected too
  ok: the method-reference diagnostic names start (ThreadRef.java: error: forbidden API: java.lang.Thread.start — starting threads (mod code runs on the server thread))
  ok: a class calling Event.register passes the policy:
  ok: the rewritten class no longer calls Event.register ([java/lang/Object.<init>, com/gijsm/vibemod/fabric/shim/Shims.eventRegister])
  ok: the rewritten class calls the host shim instead ([java/lang/Object.<init>, com/gijsm/vibemod/fabric/shim/Shims.eventRegister])
  ok: the rewrite actually changed the bytes
  ok: the rewritten call reached the host shim
  ok: the shim received the mod's own event instance
  ok: the shim received the mod's own listener
  ok: the real Event.register was never called
  ok: an ordinary Fabric mod passes the policy:
  ok: both idiomatic Event.register call sites were redirected to the shim ([java/lang/Object.<init>, com/gijsm/vibemod/fabric/shim/Shims.eventRegister, com/gijsm/vibemod/fabric/shim/Shims.eventRegister])
  ok: no Event.register call survived (...)
  ok: the rewritten mod keeps its ModInitializer entrypoint
  ok: a legacy VibeContext mod passes the policy:
  ok: a legacy VibeContext mod comes back byte-identical
  ok: the NeoForge policy refuses a Fabric-API mod
  ok: with the message the Phase E gate looks for (WrongLoader.java: error: forbidden API: net.fabricmc.api.ModInitializer — Fabric API seams are not available on NeoForge yet)
  ok: and the same mod is accepted by the Fabric policy
  ok: Event.addPhaseOrdering is rejected
  ok: the phase-ordering diagnostic explains why (Phases.java: error: forbidden API: net.fabricmc.fabric.api.event.Event.addPhaseOrdering — Event.addPhaseOrdering (phase order is global and cannot be undone on disable))
ALL CHECKS PASSED
```

### 2. `scripts/smoke-fabric.sh` — 37/37, exit 0

```
== asserting on the V3 native canary (the thesis test)
  ok: the bytecode seam was installed
  ok: a plain Fabric mod compiled and hot-loaded
  ok: its ModInitializer entrypoint ran
  ok: the host fanned out the event it subscribed to
  ok: its END_SERVER_TICK subscription dispatches
  ok: nothing it registered was refused
  ok: the mod source really has no VibeMod import
...
== round-tripping the native canary through disable/enable
> vibe disable NativeCanary
⬡ vibe NativeCanary disabled.

  ok: disabling a native mod drained its Fabric event subscription (ticks 1 -> 1)
> vibe enable NativeCanary
⬡ vibe NativeCanary enabled.

  ok: re-enabling reports success
  ok: re-enabling brought the subscription back (ticks 1 -> 4)
  ok: the entrypoint ran a second time
  ok: no registration was silently refused across the round trip
== stopping server (pid 16701)

== PHASE D DEDICATED-SERVER GATE PASSED
```

The tick counts are the whole point: a Fabric `Event` cannot be unsubscribed, so
"the log stopped growing after `/vibe disable` and started again after
`/vibe enable`" is a claim that is simply false unless the seam works.

### 3. `scripts/smoke-neoforge.sh` — 31/31, exit 0

```
== asserting on the boot log
  ok: the host initialised
  ok: the platform probe says neoforge with dialogs
  ok: the neoforge prompt profile was selected
  ok: a compiler backend resolved
  ok: the hot-load class-file ceiling is the JVM's own version
  ok: generated mods target the running JVM's release
  ok: the canned mod compiled and hot-loaded
  ok: the mod's own onEnable ran
  ok: ctx.client() was inert on a dedicated server
  ok: native dialogs were chosen as the renderer
  ok: the foreign-platform mod was skipped, not compiled
  ok: nothing threw during boot
  ok: no mod-loading issue was reported
  ok: a Fabric-API mod on NeoForge is refused with a compile diagnostic
  ok: and the diagnostic names the API that is not here
  ok: the refusal did not stop the other mods loading
...
== PHASE E DEDICATED-SERVER GATE PASSED
```

### 4. `./gradlew :fabric:runClientGameTest` — 36/36, exit 0

Run for real, on this Mac's display.

```
  ok: the process-lived event fanout was installed at mod init
  ok: a plain Fabric mod's END_SERVER_TICK subscription dispatches
  ok: the fanout holds its tick subscription (AttackBlockCallback=1 ServerTickEvents.EndTick=1 total=2)
  ok: the fanout holds its attack-block subscription (AttackBlockCallback=1 ServerTickEvents.EndTick=1 total=2)
  ok: disabling drained the tick subscription to zero (AttackBlockCallback=0 ServerTickEvents.EndTick=0 total=0)
  ok: disabling drained the attack-block subscription to zero (AttackBlockCallback=0 ServerTickEvents.EndTick=0 total=0)
  ok: a disabled native mod really stops running
  ok: re-enabling re-subscribed through the same permanent fanout (AttackBlockCallback=1 ServerTickEvents.EndTick=1 total=2)
  ok: and the mod is dispatching again
...
PHASE D CLIENT GATE PASSED
```

The original file has 29 `check(...)` call sites; two of them are failure-branch
guards that fire only when something is wrong, so 27 execute on the happy path.
27 + 9 new = the 36 above. No pre-existing check was removed, weakened, or
skipped.
