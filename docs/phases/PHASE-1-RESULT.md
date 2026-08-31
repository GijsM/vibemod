# Phase 1 result — commands, the client entrypoint, keybinds, HUD and screens

All seven deliverables (A–G) landed. All four gates are green, and the one that
matters is the last one: inside a real Minecraft client, a mod written as a
**plain Fabric mod** — `implements ModInitializer, ClientModInitializer`, with
zero VibeMod imports — is seeded and enabled **mid-session**, gets
`SERVER_STARTING` replayed, registers a Brigadier command through
`CommandRegistrationCallback` that runs on the tick it loads, leases a keybind
out of the shared pool and polls it, draws a `HudElement` every frame, subscribes
to `ClientTickEvents`, and opens a `Screen` it defined itself — and `/vibe
disable` takes **all six** away, including closing the screen off the player's
display, and `/vibe enable` brings them back.

---

## What landed, by deliverable

### A. Command replay (`CommandRegistrationCallback`)

| File | What |
| --- | --- |
| `fabric/.../shim/CommandSeam.java` | New. Immediate invoke + dispatcher diff + reflective removal + reload replay + collision policy |
| `fabric/.../shim/EventFanout.java` | `CommandRegistrationCallback` left the deny set; `register()` routes it to the seam instead of building a `Fan` |
| `loader-common/.../LoaderCommandBridge.java` | `removeChild`, `restoreCommand`, `resync` extracted as public statics; the instance methods now call them |
| `fabric/.../VibeModFabric.java` | The host's own callback hands every firing to the seam, **after** reinstalling `/vibe` |

Three mechanisms, one per problem the brief named:

- **Immediate invocation.** A live mod's callback is invoked at once against
  `server.getCommands().getDispatcher()`. The `CommandBuildContext` and
  `Commands.CommandSelection` are the **captured** ones — the real objects vanilla
  handed the host at the last firing — rather than reconstructed, so a mod
  building an `ItemArgument` gets the registries every other mod on this server
  got. `Commands.createValidationContext(server.registryAccess())` is kept as a
  fallback for a case that cannot happen (the callback fires inside
  `Commands.<init>`, long before any mod can be hot-loaded) but would be silent
  if it did.

- **A before/after diff of the dispatcher root.** The callback is opaque, so
  what it added is discovered rather than declared: snapshot every root child's
  identity, executor and child names, invoke, compare. New literals become the
  mod's and are removed on disable through the same reflection `/vibe`'s dynamic
  commands already used.

- **Replay on reload.** The host's process-lived callback fires again with a
  fresh dispatcher; every live mod's stored callback is replayed into it under
  attribution, then clients are resynced.

**The collision guard is not the one the brief specified, because Brigadier
does not behave the way the brief assumed.** `CommandNode.addChild` *merges*
onto an existing child rather than replacing it (verified by disassembling
brigadier 1.3.10): it overwrites the existing node's `command` field and adds the
incoming node's children to it. Node identity is therefore **unchanged** after a
takeover, and "compare node identity before/after" detects nothing. The
snapshot records the executor and the child-name set as well, the diff detects a
merge, `LoaderCommandBridge.restoreCommand` puts the old executor back, the added
grandchildren are removed, and the mod gets a journalled error naming the command
it lost. Identity is still compared, for the wholesale-swap case that
`addChild` cannot produce but some other path might.

Collisions are journalled through `ModDispatch` rather than logged: that is the
mod's own channel, so the error lands in `/vibe errors`, counts towards the
error storm, and reads like any other failure — which is what "a journalled
error, not silence" has to mean to be worth anything.

**The Phase-0 leftover is gated, and gating it exposed a latent double-fire.**
`replayIfLate` used to fire on "a server exists". A mod loaded during boot
restore has missed `SERVER_STARTING` but may still be *ahead* of
`SERVER_STARTED`, so that rule would call it once by replay and again when
Fabric got to it. The fanout now tracks what has actually fired
(`noteServerStarting`/`noteServerStarted`/`noteServerStopped`, called by the
host, because at the moment those events fire nothing has subscribed through the
fanout yet). The client gate asserts the replay directly, with a mid-session
`seedOne` + `/vibe enable`.

### B. Client entrypoint + client events

| File | What |
| --- | --- |
| `loader-common/.../EntrypointAdapter.java` | `adapt(ModHandle, ClassLoader, Object)`, may throw `ModLoadException` |
| `fabric/.../FabricEntrypointAdapter.java` | Detects both entrypoints; defers the client half to the render thread; tracks the screen guard |
| `fabric/.../shim/ClientSeam.java` | New. The render thread as the *server* side is allowed to see it |
| `fabric/.../shim/EventFanout.java` | Client callbacks allowed on the render thread; render-watchdog dispatch with instant detach |

`onInitialize` stays synchronous on the server thread inside the load that rolls
back if it throws. `onInitializeClient` cannot: it registers HUD elements,
keybinds and client events, all of which belong to the render thread. It is a
**tracked deferred step** — queued with `Minecraft#execute`, run under
`ModAttribution`, journalled `where="onInitializeClient"` on failure. A client
half that fails degrades the mod rather than failing the load, which is the same
bargain `ctx.client(...)` already makes.

A `ClientModInitializer`-only class on a dedicated server is a `ModLoadException`.
A class implementing both skips the client half **and says so in the log** — the
smoke gate asserts both the log line and the absence of the client half's own
marker, because a silent skip is how you get "the keybind does nothing on my
server" with nothing in the log to explain it.

**"Registered from the client entrypoint" is implemented as a thread question,
not a phase flag.** `onInitializeClient` runs on the render thread and
`onInitialize` runs on the server thread, so `seam.onRenderThread()` answers it
exactly — and keeps answering correctly for a mod that registers a second client
callback from inside the first one, which is legitimate and which a one-shot "are
we in client init" flag would refuse.

`ClientSeam` names **no client type at all** (only JDK types, `Watchdog` and
`ModFailure`), which is what lets `EventFanout` and `FabricEntrypointAdapter`
hold one on a dedicated server where `Minecraft` does not exist. The client-only
registrations live in a second interface, `ClientRegistrations`, loaded only by
`ClientShims`.

### C. Keybind seam

`KeyMappingHelper.registerKeyMapping` → `ClientShims.registerKeyMapping` →
`LoaderClientEventBridge.leaseSlotFor` → the existing eight-slot pool, returning
**the slot's** `KeyMapping` so the mod's `consumeClick()`/`isDown()` polling just
works. The requested mapping is read for its translation key, category and
default binding and never registered; the default is honoured on exactly the same
terms as the v2 `leaseKey` (only over an unbound slot or one we bound ourselves —
a player's rebind always wins). Pool exhausted throws the same clear `ISE`.
Released on drain, and the client gate proves the slot is genuinely re-leasable.

One thing the pool needed: `pollKeys()` now skips leases with no `onPress`.
Without that, the host's own per-tick `consumeClick()` drain would eat every
press before the mod's polling saw it.

### D. HUD seam

`HudElementRegistry.addFirst/addLast/attachElementBefore/attachElementAfter` →
`ClientShims.hudAdd`/`hudAttach` → `LoaderClientEventBridge.rawHud`, a second
dispatch list behind the same single permanent `vibemod:mods` element, with the
same watchdog, the same journalling and the same instant-detach-on-throw.
`HudElement`'s SAM is `extractRenderState(GuiGraphicsExtractor, DeltaTracker)`,
so `renderHuds` now takes the `DeltaTracker` and passes it through rather than
reducing it to a partial tick — a native element is entitled to the object the
game hands out. Both loader hosts' call sites were updated; NeoForge is a method
reference now.

Ordering (`addFirst` vs `addLast`) and anchors are dropped, and it is not a
silent drop: the host owns one element and a mod's is an entry behind it, so
"first" and "last" would be claims about a list the mod is not in.
`removeElement` and `replaceElement` are deliberately **not** on the table — they
act on other mods' elements, permanently and globally, which is the same
objection that keeps `Event.addPhaseOrdering` on the deny list.

### E. Screen hygiene

Every native mod on a physical client gets one tracked `Kind.CLIENT` registration
whose close hops to the render thread and, if `Minecraft.getInstance().gui.screen()`'s
class was defined by *this mod's* `BytesClassLoader` (`instanceof
ModLifecycle.BytesClassLoader` **and** loader identity — two versions of a mod
have identically named classes), calls `setScreenAndShow(null)`. Zero mod-facing
API. `Screen` subclassing stays allowed by the allowlisted `net/minecraft/` root.

### F. Prompt + oracle

The native FABRIC profile lifts the command/keybind/HUD bans and replaces them
with what is actually true: commands are hot and removed on disable and the first
registration of a name wins; keybinds come from a shared pool and
`registerKeyMapping` returns a *different* mapping, so the manual must never
promise a specific physical key; HUD via `HudElementRegistry.addLast` with
`graphics.fill`/`graphics.text`; `Screen` subclassing allowed and closed for you;
client code goes in `ClientModInitializer`; registries, resources and client
commands are still refused. The threading block gains the singleplayer
shared-JVM race, restated briefly from the legacy profile.

One new few-shot: `CoordToggle`, the legacy profile's keybind example rewritten
as a plain Fabric mod implementing **both** entrypoints, exercising
`CommandRegistrationCallback` + `KeyMappingHelper` + `HudElementRegistry`
together. It is deliberately the same file the client gate compiles and runs
(`NativeClientCanary` is this mod plus marker files), so what the prompt teaches
is provably what works. It was also compiled against the real Loom classpath with
`javac` before being embedded.

`LlmSelfTest`'s native-profile suite was rewritten around what is now true:
the single "defers commands, keybinds, HUD and registries" check became nine,
three more cover the new few-shot, and one asserts the prompt never says
`KeyBindingHelper` again. Prompt budgets:

```
  paper-modern     22977 chars  ~  5744 tokens  (2 few-shots)
  paper-legacy     23980 chars  ~  5995 tokens  (2 few-shots)
  fabric           19708 chars  ~  4927 tokens  (2 few-shots)
  fabric-legacy    33773 chars  ~  8443 tokens  (3 few-shots)
  neoforge         33778 chars  ~  8444 tokens  (3 few-shots)
```

The native profile is at 19708 chars with two few-shots — inside the ≲20k budget,
and still 42% smaller than the `fabric-legacy` profile it replaces while teaching
four more surfaces.

The `SymbolOracle` needed no change: it resolves against the host's own class
loader, which already has the keybind and HUD API on it.

### G. Gates

- `:fabric:surgeonSelfTest` — 28 → **38** assertions. Three new fixtures: a
  command mod, a keybind mod (the seam whose shape differs from every Phase 0
  entry — `invokestatic`, no receiver, and a return value the mod uses), and a
  HUD mod exercising all three rewritten overloads.
- `scripts/smoke-fabric.sh` — 37 → **49**. The native canary now implements both
  entrypoints and registers `/nativecmd`; the gate asserts the command runs
  immediately, the client half is skipped with a log line and really does not
  run, the command disappears on disable and comes back on enable, and — the new
  one that found a bug — that it survives a datapack `/reload`.
- `scripts/smoke-neoforge.sh` — **31**, unchanged and untouched.
- `VibeModClientGateTest` — 36 → **56**. `NativeClientCanary`, seeded and enabled
  mid-session, covering §A–§E end to end in a real client.

---

## The bug the new gate found

`scripts/smoke-fabric.sh`'s `/reload` assertion failed on its first run — not on
the new command seam, which worked, but on `/smokeping`, a v2 command that has
existed since Phase D.

`VibeModFabric.Boot` declared instance fields `commandBridge` and `chatBridge`
that **shadowed the statics of the same name** on the enclosing class. `wire()`
assigned the shadows; the process-lived subscriptions made in `onInitialize()`
read the statics, which stayed null forever. Two things quietly did not work on
Fabric:

1. **`/vibe` and every generated command vanished on the first `/reload`** —
   `CommandRegistrationCallback` found `commandBridge == null` and reinstalled
   nothing into the fresh dispatcher.
2. **`ctx.onChat` never fired at all** — `FabricChatBridge.installDispatcher(() ->
   chatBridge)`'s supplier answered null on every chat line.

NeoForge's structurally identical `Boot` never declared those fields and never
had the bug. The fix is deleting the two declarations, so every assignment and
read hits the static; a comment in their place records why they must not come
back. The `/reload` half is now gated three ways (the mod's command, the v2
bridge's command, and `/vibe` itself); the chat half is not gated, because
producing a player chat line over RCON is not something this harness can do —
flagged for whoever next touches the chat surface.

---

## Deviations from the brief, and why

**1. There is no `KeyBindingHelper`.** §C specified
`net/fabricmc/fabric/api/client/keybinding/v1/KeyBindingHelper.registerKeyBinding(KeyMapping)KeyMapping`.
`javap` over fabric-api's `fabric-key-mapping-api-v1` 2.0.5 says that package
does not exist in this era; the class is
`net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper` and the method is
`registerKeyMapping(KeyMapping)KeyMapping`. The seam uses the real one — which
is also the one the host's own `FabricClientEventBridge.install()` has been
calling since Phase D. `LlmSelfTest` now asserts the prompt does **not** mention
`KeyBindingHelper`, so the dead name cannot come back through the prompt.

**2. `Seam` gained a second construction, `staticCall`.** §C and §D's call sites
are `invokestatic` with no receiver on the stack, so `prependingReceiver` would
have produced bytecode looking for an argument that is not there.
`staticCall(owner, name, descriptor, shimOwner, shimName)` keeps the descriptor
and moves only the owner. Kept as a separate factory rather than making
`prependingReceiver` guess, so the caller states which kind of call site it is
intercepting and a mistake is a link error at the shim rather than a silent stack
mismatch. The surgeon self-test defines and inspects both shapes.

**3. Collision detection is executor-and-children, not node identity.** See §A
above: Brigadier merges rather than replaces, so identity is unchanged after a
takeover and the specified check would detect nothing. Identity is still
compared as well.

**4. Instant-detach-on-throw applies to every client event, not only per-frame
ones.** §B named `ClientTickEvents`, `LevelRenderEvents` and HUD-adjacent events.
Applying it to all of them is the policy `LoaderClientEventBridge` has used for
everything it dispatches on the render thread since Phase D, and one rule the
whole surface obeys beats a list of event names somebody has to maintain as
fabric-api grows. The cost is that a rare client event which throws once is
detached; the mod is journalled either way, and the render loop is the thing
being protected.

**5. `EntrypointAdapter.adapt` changed shape.** It takes the `ModHandle` and the
mod's `ClassLoader` now, and may throw `ModLoadException`. §B offered "a second
adapter wired into `LoaderModHost`"; one adapter with more information turned out
to be strictly less machinery, and it is what keeps all three client concerns
(render-thread deferral, attribution, screen ownership) on the Fabric side of the
`loader-common` wall. `LoaderModHost` changed by one line; `EntrypointAdapter.NONE`
and the NeoForge call site are unaffected in behaviour.

**6. `ModAttribution` gained `call`.** The client fanout needs the listener's
return value *and* its exception, so neither `Runnable` nor a captured array is
an honest signature for what is happening.

**7. `LoaderClientEventBridge.renderHuds` changed signature.** It takes a
`DeltaTracker` rather than a pre-computed partial tick, because a native
`HudElement` is handed the tracker by the loader and reducing it here would mean
the host could not pass on what the game gave it. Both loader call sites updated;
NeoForge's smoke gate is unchanged and still 31/31.

**8. The gate's screen is opened by hopping from the server command to the render
thread.** A mod on a dedicated server would need networking for that; in
singleplayer the two sides share a JVM and `Minecraft#execute` is the correct hop
— which is exactly what makes it the right shape for a gate that runs in
singleplayer. The few-shot does not teach this pattern; it opens nothing.

**9. `VibeModFabric` fixed a pre-existing bug.** Out of Phase 1's literal scope,
in Phase 1's critical path: §A's collision policy presupposes that `/vibe` is
reinstalled on reload, and it was not. See "The bug the new gate found".

---

## Notes worth carrying into Phase 2

- **`describeState()` now carries two more dimensions.** The client bridge reports
  `nativeHuds=`, and the fanout appends the command seam's own line
  (`CommandRegistrationCallback=1 modCommands=/nativecmd`). Existing assertions
  on `huds=` still read correctly because they are `contains` checks on a
  space-separated string — but `huds=1` is now a *substring* of nothing else, and
  a future counter named so that it is would break them silently.
- **The command seam holds `CommandBuildContext` for the life of a server.** It
  is refreshed on every reload. If Phase 2 ever needs a per-invocation context
  (dynamic registries a mod itself contributed to), this is the field to revisit.
- **`ClientSeam` must stay free of client types.** It is held by two classes that
  load on dedicated servers. Adding a `KeyMapping` to it would not fail here —
  it would fail on somebody's server, at class-load time, with a
  `NoClassDefFoundError` nothing in this repo would have caught.
- **The client-event thread rule refuses registration from `onInitialize`.** That
  is deliberate and the message says so, but it means a mod that guards its own
  client code with a `FabricLoader.getEnvironmentType()` check instead of using
  `ClientModInitializer` gets a clear refusal rather than working — and
  `net.fabricmc.loader.*` is denied by the policy anyway, so the entrypoint is
  the only route. Worth restating in the prompt if the model ever tries it.
- **`ClientCommandRegistrationCallback` is still refused.** It would need the same
  treatment as §A against a per-connection dispatcher. `/vibec` covers the need
  for now.

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
38 actionable tasks: 27 executed, 11 from cache
```

`:fabric:surgeonSelfTest` runs inside `check`, so it is part of this — 37
assertions, of which these ten are new:

```
  ok: a mod registering CommandRegistrationCallback passes the policy: 
  ok: its CommandRegistrationCallback registration goes through the host shim ([java/lang/Object.<init>, com/gijsm/vibemod/fabric/shim/Shims.eventRegister, net/minecraft/commands/Commands.literal, com/mojang/brigadier/builder/LiteralArgumentBuilder.executes, com/mojang/brigadier/CommandDispatcher.register, com/mojang/brigadier/context/CommandContext.getSource, net/minecraft/network/chat/Component.literal, net/minecraft/commands/CommandSourceStack.sendSystemMessage])
  ok: and no raw Event.register survived ([java/lang/Object.<init>, com/gijsm/vibemod/fabric/shim/Shims.eventRegister, net/minecraft/commands/Commands.literal, com/mojang/brigadier/builder/LiteralArgumentBuilder.executes, com/mojang/brigadier/CommandDispatcher.register, com/mojang/brigadier/context/CommandContext.getSource, net/minecraft/network/chat/Component.literal, net/minecraft/commands/CommandSourceStack.sendSystemMessage])
  ok: a mod leasing a keybind passes the policy: 
  ok: KeyMappingHelper.registerKeyMapping was redirected to the client shim ([java/lang/Object.<init>, net/minecraft/client/KeyMapping.<init>, com/gijsm/vibemod/fabric/shim/ClientShims.registerKeyMapping, net/minecraft/client/KeyMapping.consumeClick])
  ok: no call to the real KeyMappingHelper survived ([java/lang/Object.<init>, net/minecraft/client/KeyMapping.<init>, com/gijsm/vibemod/fabric/shim/ClientShims.registerKeyMapping, net/minecraft/client/KeyMapping.consumeClick])
  ok: the mod still polls the mapping it was handed back ([java/lang/Object.<init>, net/minecraft/client/KeyMapping.<init>, com/gijsm/vibemod/fabric/shim/ClientShims.registerKeyMapping, net/minecraft/client/KeyMapping.consumeClick])
  ok: a mod drawing a HUD passes the policy: 
  ok: all three HudElementRegistry overloads were redirected ([java/lang/Object.<init>, net/minecraft/resources/Identifier.fromNamespaceAndPath, com/gijsm/vibemod/fabric/shim/ClientShims.hudAdd, net/minecraft/resources/Identifier.fromNamespaceAndPath, com/gijsm/vibemod/fabric/shim/ClientShims.hudAdd, net/minecraft/resources/Identifier.fromNamespaceAndPath, com/gijsm/vibemod/fabric/shim/ClientShims.hudAttach])
  ok: no call to the real HudElementRegistry survived ([java/lang/Object.<init>, net/minecraft/resources/Identifier.fromNamespaceAndPath, com/gijsm/vibemod/fabric/shim/ClientShims.hudAdd, net/minecraft/resources/Identifier.fromNamespaceAndPath, com/gijsm/vibemod/fabric/shim/ClientShims.hudAdd, net/minecraft/resources/Identifier.fromNamespaceAndPath, com/gijsm/vibemod/fabric/shim/ClientShims.hudAttach])
ALL CHECKS PASSED
```

### 2. `scripts/smoke-fabric.sh` — 49/49, exit 0

The Phase 1 additions, verbatim:

```
== asserting on the client entrypoint's inertness (V3 Phase 1 B)
  ok: a ClientModInitializer half was skipped on a dedicated server
  ok: and it really did not run
  ok: the mod loaded anyway
...
== asserting on the native mod's own Brigadier command (V3 Phase 1 A)
> nativecmd
native-cmd-ok 111

  ok: a command registered via CommandRegistrationCallback runs immediately
...
== asserting the mod's command went with it
> vibe disable NativeCanary
⬡ vibe NativeCanary disabled.

> nativecmd
Unknown or incomplete command. See below for errornativecmd<--[HERE]

  ok: disabling the mod removed the command it registered
  ok: and the server says the command is unknown
> vibe enable NativeCanary
⬡ vibe NativeCanary enabled.

  ok: re-enabling put the command back in the live dispatcher
  ok: no command name collision was reported
== asserting the mod's command survives a datapack reload (V3 Phase 1 A)
> reload
Reloading!

> nativecmd
native-cmd-ok 111

> smokeping
smoke-pong howdy

> vibe list
VibeMod mods:● NativeCanary [on]● SmokeCanary [on]● WrongPlatform [on]

  ok: the host replayed the mod's command registration into the new dispatcher
  ok: the mod's own command still runs after /reload
  ok: and the v2 command bridge survived the same reload
  ok: and /vibe itself survived the same reload
== stopping server (pid 20257)

== PHASE D DEDICATED-SERVER GATE PASSED
```

`Unknown or incomplete command` for a command Brigadier has no remove for is the
claim that is simply false unless the reflective node surgery ran.

### 3. `scripts/smoke-neoforge.sh` — 31/31, exit 0

Unchanged, and it has to be: every client seam is fabric-module, and the
`loader-common` additions (`RawHudRenderer`, the `LoaderCommandBridge` statics,
`ModAttribution.call`, the `EntrypointAdapter` signature) are loader-neutral.

```
  ok: onDisable ran on disable
== stopping server (pid 20435)

== PHASE E DEDICATED-SERVER GATE PASSED
```

### 4. `./gradlew :fabric:runClientGameTest` — 56/56, exit 0

Run for real, on this Mac's display. The Phase 1 block, verbatim:

```
  ok: SERVER_STARTING was replayed for a mod enabled mid-session
  ok: the mod's ClientModInitializer half ran
  ok: its HudElement landed in the host's HUD pipeline (huds=1 nativeHuds=1 tickers=1 clientCommands=1 keysLeased=2/8)
  ok: its keybind leased a second slot from the same pool (huds=1 nativeHuds=1 tickers=1 clientCommands=1 keysLeased=2/8)
  ok: its client tick event went through the fanout (AttackBlockCallback=1 ClientTickEvents.EndTick=1 ServerLifecycleEvents.ServerStarting=1 ServerTickEvents.EndTick=1 total=4 CommandRegistrationCallback=1 modCommands=/nativecmd)
  ok: its command is tracked by name (AttackBlockCallback=1 ClientTickEvents.EndTick=1 ServerLifecycleEvents.ServerStarting=1 ServerTickEvents.EndTick=1 total=4 CommandRegistrationCallback=1 modCommands=/nativecmd)
  ok: the mod's HUD element is being drawn every frame
  ok: pressing the leased key reached the mod's own KeyMapping
  ok: the mod's own Brigadier command ran
  ok: the command opened the mod's own Screen
  ok: disabling closed the screen the mod had defined
  ok: disabling detached its HudElement (huds=1 nativeHuds=0 tickers=1 clientCommands=1 keysLeased=1/8)
  ok: disabling returned its key slot to the pool (huds=1 nativeHuds=0 tickers=1 clientCommands=1 keysLeased=1/8)
  ok: disabling drained its client tick subscription (AttackBlockCallback=1 ClientTickEvents.EndTick=0 ServerLifecycleEvents.ServerStarting=0 ServerTickEvents.EndTick=1 total=2 CommandRegistrationCallback=0 modCommands=-)
  ok: disabling forgot its command (AttackBlockCallback=1 ClientTickEvents.EndTick=0 ServerLifecycleEvents.ServerStarting=0 ServerTickEvents.EndTick=1 total=2 CommandRegistrationCallback=0 modCommands=-)
  ok: a disabled mod's HUD element really stops drawing
  ok: a disabled mod's command is really gone from the dispatcher
  ok: re-enabling put the command back
  ok: re-enabling re-attached the HUD element
  ok: re-enabling re-leased a key slot (huds=1 nativeHuds=1 tickers=1 clientCommands=1 keysLeased=2/8)
...
PHASE D CLIENT GATE PASSED
```

And the host's own log lines from the same run, which say what happened without
being assertions:

```
INFO: Fanning out ServerLifecycleEvents.ServerStarting (one permanent subscription)
INFO: Mod NativeClientCanary registered /nativecmd
INFO: Fanning out ClientTickEvents.EndTick (one permanent subscription)
INFO: Mod NativeClientCanary initialised its client half
...
INFO: Removed /nativecmd with mod NativeClientCanary
INFO: Closing vibemod.nativeclientcanary.NativeClientCanary$CanaryScreen: the mod that defined it was unloaded
```

No pre-existing check was removed, weakened or skipped in any of the four.
