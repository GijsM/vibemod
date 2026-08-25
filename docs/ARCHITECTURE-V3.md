# VibeMod v3 Architecture — The Anything Engine

*Status: authoritative for V3 on Fabric. Written by the Phase 4 agent, 2026-08-26, from the
record left by Phases 0–3 (`docs/phases/PHASE-{0,1,2,3}-RESULT.md`). Companion documents:
`docs/ARCHITECTURE-V2.md` (still authoritative for everything V3 did not touch — the module
graph, the screen model, the Paper host, the `VibeContext` flavor) and the four phase briefs.
Where this document and a phase brief disagree, this document wins: the briefs were written
before the code, and several of their assumptions turned out to be false against the real 26.2
jars. Every such case is recorded rather than quietly corrected.*

---

## The thesis, in one paragraph

In v2, a generated mod on a loader implemented `com.gijsm.vibemod.api.Mod` and reached the game
through a curated wrapper: ten frozen server hooks (`ctx.onPlayerJoin`, `ctx.onServerTick`, …),
`ctx.command`, and — on a client — `ClientContext.key` and `ClientContext.hud`. A useful surface,
and every inch of it a VibeMod invention. What the mod could **not** do was talk to the loader:
no `net.fabricmc.*` at all, so no event outside those ten, no `Screen`, no `data/**` or
`assets/**`, and nothing registered. Those bans existed for one honest reason: **a Fabric `Event`
cannot be unsubscribed**, and a mod the host cannot un-register is a mod the host cannot unload.

V3 keeps that constraint and inverts the response to it. Instead of giving the model a smaller
API that is revocable, the host lets the model write **an ordinary Fabric mod** — the code it
already knows, out of distribution for nobody — and intercepts the handful of call sites that
would otherwise be unrevocable, in bytecode, on the way out of the compiler. `Event.register`
becomes a call into a host-owned fanout. `CommandRegistrationCallback` becomes an immediate
invocation the host can diff and undo. `Registry.register` becomes a namespaced, ledgered,
refusable write. The mod's source says none of this. It has **zero VibeMod imports** and it is
byte-for-byte a mod you could put in a jar.

The prompt got *smaller* and the surface got larger, because the prompt no longer teaches an
API. It teaches five 26.x facts a model would otherwise get wrong, and gets out of the way.

---

## 0. Decision log (locked; do not relitigate)

| # | Decision | Why |
|---|---|---|
| 1 | **Seam architecture over a bespoke API.** Generated mods are plain Fabric mods; the host intercepts at choke points | LLMs write in-distribution code far better than they write against an invented wrapper. The v2 native profile is 4k characters *smaller* than the legacy one it replaced and teaches five more surfaces, because it teaches almost nothing |
| 2 | **The surgeon is installed on the compiler, not on any one code path** | Every route from source to live classes runs through `InMemoryCompiler.compile`: generation, repair rounds, `/vibe edit`, rollback, restore-on-boot. One hook covers all of them, and a policy violation becomes a javac-shaped diagnostic in the existing self-heal loop rather than a second error channel |
| 3 | **The scan walks instructions, not the constant pool** | Lambdas, records and pattern switches all put `MethodHandle` into the pool, so a pool-level rule rejects the most ordinary Java there is. Walking instructions lets the rule be the right one: a dynamic call site is fine when its *bootstrap* is one javac emits, and the handles javac threads through those bootstraps are checked as the ordinary member references they are — which is how `Thread::start` is caught despite appearing nowhere as an `invokevirtual` |
| 4 | **The rewrite is shape-preserving**: `invokevirtual` → `invokestatic` with the receiver prepended to the descriptor | The operand stack before and after is identical, so no frame is recomputed and no verifier is argued with. `Seam.prependingReceiver` derives the shim descriptor from the original, so the two cannot drift |
| 5 | **A class with no seam hit is returned byte-identical** — asserted, not assumed | It is what makes the legacy `VibeContext` corpus *provably* unaffected by a pass that runs over it on every compile |
| 6 | **The registry unfreeze is a window around the whole `onInitialize()`**, not a step inside the `Registry.register` shim | `Item.<init>` writes to the registry itself (`createIntrusiveHolder`). In `Registry.register(ITEM, id, new Item(props))` the constructor is an *argument*: it runs, and throws, before the shim that was supposed to unfreeze anything is entered. §3.4 |
| 7 | **Tombstones, not lies.** `/vibe disable` cannot remove a registry id, and the ledger says so | There is no `MappedRegistry.remove` and there was never going to be one. The install card says *stays registered until the world is restarted*, the ledger records it per installation, and unloading writes a tombstone so the next boot does not re-register |
| 8 | **Blocks are refused**, by name, with the mechanism in the message | `PalettedContainerFactory` takes its global palette bit width from the size of `BLOCK_STATE_REGISTRY` once per world load, and every chunk section in the loaded world is serialized against that strategy. Adding block states mid-session changes the id space under live containers — which does not necessarily *throw*, and that is exactly what makes it the wrong thing to ship in a release whose promise is that generated content is real |
| 9 | **Registry content is refused on a dedicated server**, deterministically, at load | A vanilla client joining later negotiates a registry sync without the id and would be kicked. Working until somebody logs in is worse than not working |
| 10 | **No silent drops.** Anything the host cannot honour is refused loudly — at compile time as a diagnostic, at registration time as a throw, or at dispatch time as a journalled error | The house rule. It is why `Event.register` via a method reference is a policy error rather than an untracked subscription, why dropped HUD ordering is documented rather than swallowed, and why a command-name collision lands in `/vibe errors` instead of a log line |
| 11 | **Every dispatch into mod code goes through `ModDispatch` under `ModAttribution`** | One watchdog, one journal, one attribution model, whether the call came from a curated hook or from a loader event the mod subscribed to itself. The single exception is `onInitialize`, which must be able to *throw* so the load rolls back — §4.7 |
| 12 | **The seam table is Fabric-only** | NeoForge keeps the v2 `VibeContext` path and the (loader-neutral) datapack channel. Its policy denies `net/fabricmc/`, so a Fabric-API mod there is a compile diagnostic, not a crash |

---

## 1. What changed, by module

Nothing in the v2 module graph moved. V3 added files inside it.

| Module | Added |
| --- | --- |
| `platform-api` | `ClassSurgeon` — the SPI: `Result operate(Map<String, byte[]>)`, where `Result` is accepted classes or javac-shaped diagnostics |
| `core` | `compile/SymbolOracle`, `store/ModResources`, `store/PixelGrid`, `store/RegistryLedger`, `runtime/ModContent`, `util/Ids`, `llm/NativeFabricExamples` |
| `loader-common` | `surgeon/{BytecodeSurgeon, SurgeonPolicy, Seam}`, `EntrypointAdapter`, `ModAttribution`, `content/{LoaderModContent, ReloadCoordinator}` |
| `fabric` | `FabricSeams`, `FabricEntrypointAdapter`, `shim/{Shims, ClientShims, EventSeam, EventFanout, CommandSeam, ClientSeam, RegistrySeam, RegistryTarget, CreativeTabs}`, `client/FabricClientPacks`, six accessor mixins |
| `paper` | *nothing* — the Paper host passes no surgeon, null is a pass-through, and `smoke-paper.sh` is untouched |

`loader-common` still names no loader type. That is enforced by structure, not discipline:
`ClientSeam` and the reload coordinator's two interfaces exist precisely so classes that load on
a dedicated server never mention `Minecraft`.

---

## 2. Verified facts (26.2 / fabric-api 0.158.0)

Everything below was read off the jars with `javap` or produced by a gate. Nothing was recalled.
The corrections matter more than the confirmations: each one is a place where a phase brief, or
a model's training data, said something that is no longer true.

| Fact | How it was established | Consequence |
| --- | --- | --- |
| `Item.<init>` calls `BuiltInRegistries.ITEM.createIntrusiveHolder(...)` | `javap -c` on `Item` | The unfreeze cannot live at the `Registry.register` call site — Decision 6 |
| `MappedRegistry.freeze()` sets `unregisteredIntrusiveHolders = null` | `javap -c` on `MappedRegistry` | After boot, `createIntrusiveHolder` throws before anything of ours runs |
| `freeze()` throws `Tags already present before freezing` on a second call | Gate | The window's close sets `frozen = true` directly, then does the two things `freeze()` does that a new holder needs: `refreshTagsInHolders()` and a fresh `DataComponentLookup` |
| `Item.Properties.sword(...)` reaches the **BLOCK** registry (`ToolMaterial.applySwordProperties` → `acquireBootstrapRegistrationLookup(BLOCK)` → `validateWrite()`) | Smoke gate, first run | The window must unfreeze `BLOCK` too — which is not the same as allowing blocks; the shim still refuses `BuiltInRegistries.BLOCK` by name |
| `Item.<init>` appends to `BuiltInRegistries.DATA_COMPONENT_INITIALIZERS`, keyed by id, *before* our refusal fires | Smoke gate: nine **Phase 2** assertions failed at once | A refused registration poisoned every later datapack reload with `Missing element`. Fixed with a snapshot/rollback on the window |
| There is **no `SwordItem` class** | `javap` | `Item.Properties.sword(ToolMaterial.IRON, 4.0F, -2.4F)` is what makes a sword. The prompt says so |
| `setId(...)` must be on `Item.Properties` **before** the item is constructed | `Item.<init>` calls `Properties.itemIdOrThrow()` twice | `Item$Properties.setId` is its own seam: rewriting the namespace at register time would leave the item pointing at an `assets/` path in a namespace nothing writes to |
| There is **no `KeyBindingHelper`** and no `…api.client.keybinding.v1` package | `javap` over `fabric-key-mapping-api-v1` 2.0.5 | The class is `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper`, the method is `registerKeyMapping(KeyMapping)KeyMapping`. `LlmSelfTest` asserts the dead name never returns to the prompt |
| There is **no `ItemGroupEvents`** and no `fabric-item-group-api-v1` | `javap` over `fabric-creative-tab-api-v1` 5.0.14 | The class is `net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents`; the method is `modifyOutputEvent(ResourceKey<CreativeModeTab>)` |
| `ResourceLocation` is `net.minecraft.resources.Identifier`; `RecipeHolder.id()` returns `ResourceKey<Recipe<?>>` and its accessor is **`identifier()`**, not `location()` | Found by the Fabric gate's first run, reported as an ordinary compile diagnostic | The 1.21-era shape in the brief was wrong; the prompt carries the rename table |
| `pack.mcmeta` takes **`min_format`/`max_format`** (each an int *or* a `[major, minor]` list) | `data/minecraft/datapacks/trade_rebalance/pack.mcmeta` + `PackFormat$IntermediaryFormat`'s codec | The numbers are read at runtime off `DetectedVersion.BUILT_IN.packVersion(PackType)`, never from `SharedConstants.DATA_PACK_FORMAT_MAJOR`, which javac would inline and freeze into the jar |
| An **ingredient is a string**, a `#tag`, or an array — never `{"item": …}` | `Ingredient.CODEC` is `ExtraCodecs.nonEmptyHolderSet(HolderSetCodec.create(Registries.ITEM, Item.CODEC, false))`, `javap -c`; and the runtime error from the live demo | A 1.20-era ingredient object is **silently dropped** while the pack loads — the mod loads, reports success, and cannot be crafted. §7.3 |
| An `item_model` component names `assets/<ns>/items/<name>.json`, which points at `assets/<ns>/models/item/<name>.json` | `assets/minecraft/items/apple.json` → `assets/minecraft/models/item/apple.json` | A model trained on 1.20 writes **one** file and gets a missing texture. The few-shot ships both |
| `MinecraftServer.reloadResources(Collection<String>)` managed-blocks when called on the server thread, and `managedBlock` **pumps the task queue** | `javap -c`; and a gate assertion that passed vacuously until it was fixed | "The reload started" is not "the reload finished": an RCON command can execute *inside* the reload, against the old data. Both smoke gates wait for `Server data reloaded in` |
| `reloadResources` writes the pack selection back through `WorldData#setDataConfiguration` | `javap -c` on `lambda$reloadResources$4` | The teardown reload is not optional — it is what makes `level.dat` forget a pack whose folder is gone. The gate reads `level.dat` in both directions |
| `PackRepository.sources` is a `private final Set` assigned from `ImmutableSet.copyOf(varargs)` | `javap` | A pack that did not exist when the client was constructed cannot reach the repository through any public API. Hence one accessor mixin |
| `ReloadableResourceManager` swaps its packs at the **start** of a reload | Client gate: the model and texture resolved, the translation did not | `getResource()` answers from the new list immediately while `LanguageManager` has not run. The gate waits for the reload to complete before asserting anything a listener produces |
| Brigadier's `CommandNode.addChild` **merges** onto an existing child rather than replacing it | Disassembling brigadier 1.3.10 | Node identity is unchanged after a takeover, so "compare identity" detects nothing. The collision guard compares the executor and the child-name set as well |
| `CreativeModeTabs.tryRebuildTabContents` short-circuits on `CACHED_PARAMETERS.needsUpdate(...)`, which compares by **reference identity** (`if_acmpeq`) | `javap -c` | Registering an item changes none of the three things it compares. Clearing the cached parameters is the whole mechanism; vanilla's own rebuild then runs vanilla's own way |
| `EntityRenderers.PROVIDERS` is a mutable `Object2ObjectOpenHashMap` that `EntityRenderDispatcher.onResourceManagerReload` rebuilds from, and that method is public and entirely self-contained | `javap -c` | Late renderer registration works. `ClientShims` calls the rebuild directly rather than waiting for a resource reload — §4.9 |
| `EntityRendererRegistry` is `@Deprecated` in fabric-rendering-v1 25.3.2 because a transitive access widener makes vanilla's own `EntityRenderers.register` public | `javap` + the widener file | Both entry points are seamed: the fabric wrapper because a model trained on older tutorials writes it, the vanilla method because current code does |
| A world datapack's pack id is `"file/" + folder` | `FolderRepositorySource`'s string-concat bootstrap | The coordinator resolves it against the repository anyway, so a version that changes the prefix changes nothing |
| Since 1.21.2 an empty server **stops ticking** after a minute | `Server empty for 60 seconds, pausing`, in a gate that had been green for a phase | `pause-when-empty-seconds=0` in both loader gates. Every tick-counting assertion since Phase 0 had been living on borrowed time |

---

## 3. The surgeon

### 3.1 Where it sits

`InMemoryCompiler.setSurgeon(ClassSurgeon)`. Applied after a successful compile and before
`defineClass`; a rejection produces a failed `CompileResult` carrying javac-shaped diagnostics,
which the generator feeds back to the model as an ordinary repair round. A host that installs no
surgeon (Paper) is a null check.

### 3.2 Policy: the allowlist

Every member reference in a mod's bytecode must have an owner under one of these roots.
Anything else is refused by name.

```
java/                       net/fabricmc/api/
net/minecraft/              net/fabricmc/fabric/api/
com/mojang/                 com/gijsm/vibemod/
org/joml/                   vibemod/
it/unimi/
```

### 3.3 Policy: the deny table

Denials are checked *inside* the allowlist — `java/` is allowed, and then most of what makes
`java/` dangerous is taken back.

| Denied | Detail given to the model |
| --- | --- |
| `java/lang/reflect/` | reflection (a mod the host can unload must not reach around its own class loader) |
| `java/lang/invoke/MethodHandles` | method handles (same reason as reflection) |
| `java/lang/Thread.<init>`, `.start` | creating / starting threads (mod code runs on the server thread) |
| `java/util/concurrent/Executors`, `ForkJoinPool` | thread pools |
| `java/util/concurrent/CompletableFuture#*Async` | off-thread work |
| `java/lang/Runtime`, `java/lang/ProcessBuilder` | the process runtime; starting processes |
| `java/lang/System.exit` | shutting the JVM down |
| `java/net/` *(unless `java/net/URI`)* | networking |
| `org/spongepowered/` | mixins |
| `net/fabricmc/loader/`, `…/fabric/impl/`, `…/fabric/mixin/` | loader and Fabric API internals |
| `net/fabricmc/fabric/api/event/Event#addPhaseOrdering` | phase order is global and cannot be undone on disable |

The NeoForge host installs `defaultsPlus(deny "net/fabricmc/")`, so on a dev classpath where the
Fabric API happens to be present, a Fabric mod there is refused with a policy sentence rather
than a stack trace from inside a builder. On a real NeoForge server javac gets there first
(`package net.fabricmc.api does not exist`), which is also a clear refusal; both halves are
gated, in the two places each can be reached.

### 3.4 The bootstrap allowlist — four, plus one

An `invokedynamic` is accepted when its bootstrap is one javac emits:

```
LambdaMetafactory   StringConcatFactory   ObjectMethods   SwitchBootstraps
```

`ConstantBootstraps` is allowed for dynamic **constants** and *not* for call sites. That
asymmetry is not fastidiousness: javac compiles a pattern switch's `null` label into a dynamic
constant bootstrapped by `ConstantBootstraps.nullConstant`, so a four-bootstrap list rejects an
ordinary `switch` — found by the self-test the brief asked for. It stays off the call-site list
because `ConstantBootstraps.invoke` can call an arbitrary method handle. Either way the
bootstrap *arguments* are walked as ordinary member references, so an argument reaching for
something forbidden is still caught.

### 3.5 The seam table

Seventeen call sites. `prependingReceiver` derives the shim descriptor from the original by
prepending the receiver; `staticCall` keeps the descriptor and moves only the owner. Both shapes
are defined, inspected **and run** by `:fabric:surgeonSelfTest`.

| # | Intercepted call site | Descriptor | Shim | Phase |
| --- | --- | --- | --- | --- |
| 1 | `Event.register` | `(Ljava/lang/Object;)V` | `Shims.eventRegister` | 0 |
| 2 | `Event.register` | `(Identifier, Object)V` | `Shims.eventRegister` | 0 |
| 3 | `KeyMappingHelper.registerKeyMapping` | `(KeyMapping)KeyMapping` | `ClientShims.registerKeyMapping` | 1 |
| 4 | `HudElementRegistry.addFirst` | `(Identifier, HudElement)V` | `ClientShims.hudAdd` | 1 |
| 5 | `HudElementRegistry.addLast` | `(Identifier, HudElement)V` | `ClientShims.hudAdd` | 1 |
| 6 | `HudElementRegistry.attachElementBefore` | `(Identifier, Identifier, HudElement)V` | `ClientShims.hudAttach` | 1 |
| 7 | `HudElementRegistry.attachElementAfter` | `(Identifier, Identifier, HudElement)V` | `ClientShims.hudAttach` | 1 |
| 8 | `Registry.register` | `(Registry, String, Object)Object` | `Shims.registryRegister` | 3 |
| 9 | `Registry.register` | `(Registry, Identifier, Object)Object` | `Shims.registryRegister` | 3 |
| 10 | `Registry.register` | `(Registry, ResourceKey, Object)Object` | `Shims.registryRegister` | 3 |
| 11 | `Registry.registerForHolder` | `(Registry, ResourceKey, Object)Holder$Reference` | `Shims.registryRegisterForHolder` | 3 |
| 12 | `Registry.registerForHolder` | `(Registry, Identifier, Object)Holder$Reference` | `Shims.registryRegisterForHolder` | 3 |
| 13 | `Item$Properties.setId` | `(ResourceKey)Item$Properties` | `Shims.itemId` | 3 |
| 14 | `EntityType$Builder.build` | `(ResourceKey)EntityType` | `Shims.entityTypeBuild` | 3 |
| 15 | `FabricDefaultAttributeRegistry.register` | `(EntityType, AttributeSupplier$Builder)V` | `Shims.defaultAttributes` | 3 |
| 16 | `FabricDefaultAttributeRegistry.register` | `(EntityType, AttributeSupplier)V` | `Shims.defaultAttributes` | 3 |
| 17 | `EntityRendererRegistry.register` / `EntityRenderers.register` | `(EntityType, EntityRendererProvider)V` | `ClientShims.entityRenderer` | 3 |

The two `ResourceKey` overloads of `Registry.register`/`registerForHolder` share a parameter list
and differ only in **return type**, which is why the seam matches on the whole descriptor rather
than on name and arity.

### 3.6 The one place the rewrite is not transparent

`Event.register` reached through a **method reference** is a policy error, with a message telling
the model to call it directly. A seam can only intercept a real call site; the alternative is an
untracked subscription that no `/vibe disable` can ever undo. It says so rather than leaking.

---

## 4. Shim semantics

### 4.1 One permanent subscription per `Event`

The fanout registers exactly one `Proxy` per distinct `Event` instance, in `onInitialize()`,
for the life of the process. Mods are entries behind it. The subscription is never removed
because it *cannot* be; what changes is whether anything is standing behind it. This is why
`describeState()` reports `ServerTickEvents.EndTick=0` after a disable rather than dropping the
key — zero is the shape of a working teardown, and the client gate asserts exactly that.

The callback interface is discovered from the listener's own interfaces. Ambiguity is an error,
not a guess.

### 4.2 Fanout merge rules, per return type

Copied from `LoaderEventBridge.every()` exactly, so a mod behaves the same whether it reached an
event through a curated hook or through the seam.

| Return type | Merge |
| --- | --- |
| `void` | every listener runs |
| `boolean` | AND, with **no short-circuit** — a thrower casts no vote |
| `InteractionResult` | first non-`PASS` |
| `TriState` | first non-`DEFAULT` |
| any other reference | first non-null |
| anything else (`int`, `float`, `long`) | **refused at registration time**, named, with the reason |

The boolean default is `TRUE`: an event with no listeners returns the permissive answer. Every
Fabric boolean event checked reads `true` as "allow". An event that read it as "handled" would be
merged wrongly — see §9.

### 4.3 Thread guards

Off the server thread, a server-side fanout logs one line and dispatches to nothing. Client
callbacks are allowed **only** on the render thread, which is how "registered from the client
entrypoint" is answered: `onInitializeClient` runs there and `onInitialize` does not, so
`seam.onRenderThread()` answers the question exactly — and keeps answering correctly for a mod
that registers a second client callback from inside the first, which a one-shot "are we in client
init" flag would refuse.

Every client event gets **instant detach on throw**, not just per-frame ones. One rule the whole
surface obeys beats a list of event names somebody has to maintain as fabric-api grows. The cost
is that a rare client event which throws once is detached; the render loop is the thing being
protected.

### 4.4 Commands: invoke, diff, undo, replay

Three mechanisms, one per problem:

- **Immediate invocation.** A live mod's callback is invoked at once against
  `server.getCommands().getDispatcher()`, with the **captured** `CommandBuildContext` and
  `Commands.CommandSelection` — the real objects vanilla handed the host at the last firing —
  so a mod building an `ItemArgument` gets the registries every other mod on this server got.
- **A before/after diff of the dispatcher root.** The callback is opaque, so what it added is
  *discovered*, not declared: snapshot every root child's identity, executor and child names,
  invoke, compare. New literals become the mod's and are removed on disable through the same
  reflective node surgery `/vibe`'s dynamic commands already used.
- **Replay.** The host's process-lived callback fires again with a fresh dispatcher on `/reload`
  **and on every content reload** (a coordinator reload constructs a new `Commands`). Every live
  mod's stored callback is replayed under attribution, then clients are resynced.

Collisions are journalled through `ModDispatch`, not logged: that is the mod's own channel, so
the error lands in `/vibe errors`, counts towards the error storm, and reads like any other
failure.

### 4.5 The keybind pool

`KeyMappingHelper.registerKeyMapping` returns **the pool slot's** `KeyMapping`, not the one the
mod passed in, so ordinary `consumeClick()`/`isDown()` polling just works. The requested mapping
is read for its translation key, category and default binding and then discarded. The default is
honoured on the same terms as v2's `leaseKey` — only over an unbound slot or one we bound
ourselves, so a player's rebind always wins. Eight slots; exhaustion is a clear
`IllegalStateException`. Released on drain, and the client gate proves the slot is re-leasable.

Because the physical key is not the one the mod asked for, the prompt forbids promising a
specific key in the manual without saying it is rebindable.

### 4.6 HUD

`addFirst`/`addLast`/`attachElementBefore`/`attachElementAfter` all land in a second dispatch
list behind the host's single permanent `vibemod:mods` element, with the same watchdog,
journalling and instant-detach-on-throw. `HudElement`'s SAM takes a `DeltaTracker`, so the host
passes the tracker through rather than reducing it to a partial tick — a native element is
entitled to the object the game hands out.

**Ordering and anchors are dropped, and it is not a silent drop.** The host owns one element and
a mod's is an entry behind it, so "first" and "last" would be claims about a list the mod is not
in. `removeElement` and `replaceElement` are deliberately **not** on the seam table: they act on
other mods' elements, permanently and globally — the same objection that keeps
`Event.addPhaseOrdering` on the deny list.

### 4.7 Entrypoints

`onInitialize` runs **synchronously on the server thread**, inside the load that rolls back if it
throws. It is the one dispatch that does not go through `ModDispatch`, because `ModDispatch`
swallows by contract and this call must be able to throw into `ModLoadException(where=
"onInitialize")` so the caller rolls back and the generator gets its repair round. It mirrors the
`onEnable` path directly above it — plain try/catch inside `ModAttribution.runAs` — and is
journalled the same way. Every *later* dispatch into the mod does go through `ModDispatch`.

`onInitializeClient` cannot be synchronous: it registers HUD elements, keybinds and client
events, all of which belong to the render thread. It is a **tracked deferred step** — queued with
`Minecraft#execute`, run under attribution, journalled `where="onInitializeClient"` on failure. A
client half that fails degrades the mod rather than failing the load, the same bargain
`ctx.client(...)` already makes.

A `ClientModInitializer`-only class on a dedicated server is a `ModLoadException`. A class
implementing both skips the client half **and says so in the log** — a silent skip is how you get
"the keybind does nothing on my server" with nothing to explain it.

Detection is by interface, so no `meta.json` change was needed: a fact about the bytes rather
than a claim in a file that could disagree with them.

### 4.8 Screen hygiene

Every native mod on a physical client gets one tracked `Kind.CLIENT` registration whose close
hops to the render thread and, if the currently open screen's class was defined by **this mod's**
`BytesClassLoader` (`instanceof` **and** loader identity — two versions of a mod have identically
named classes), calls `setScreenAndShow(null)`. Zero mod-facing API; `Screen` subclassing stays
allowed by the `net/minecraft/` root.

### 4.9 The registry window

Opened by `FabricEntrypointAdapter` on the server thread, around the mod's whole
`onInitialize()`. It unfreezes `ITEM`, `ENTITY_TYPE` **and `BLOCK`** — the last not because
blocks are allowed but because `Item.Properties.sword(...)` reads the frozen block registry three
frames before it touches the item registry, and what the window buys is that the refusal is ours
and legible instead of a vanilla stack trace from inside a builder.

Closing is not `freeze()`, which throws `Tags already present before freezing` the second time.
The close sets `frozen = true` directly and then does explicitly the two things `freeze()` does
that a newly registered holder needs and `register()` does not: `refreshTagsInHolders()` (without
which `Holder.Reference.is(TagKey)` throws `Tags not bound`) and a fresh `DataComponentLookup`
over `byId`. Components are bound by running vanilla's own
`DataComponentInitializers.build(provider).forEach(PendingComponents::apply)` — the same call
`ReloadableServerResources` makes on every datapack reload, which is also why the coordinator's
reload repairs anything the eager pass misses.

**Snapshot and rollback.** `Item.<init>` appends to `DATA_COMPONENT_INITIALIZERS` before any
refusal of ours can fire, and nothing removes it. Without a rollback, one refused item turned
every later datapack reload in the session into `Missing element`. The window snapshots that map
and restores it, loudly, discarding the orphaned object.

Only `ITEM` and `ENTITY_TYPE` may be written. Every other registry gets a diagnostic naming what
*is* supported. Ids are rewritten to `vibemod_<modname>` — the same canonical namespace the
resource tree uses — so the recipe, the model path and the lang key agree by construction rather
than by the model's care.

### 4.10 The registry ledger

`<datadir>/registry-ledger.json`, atomic write, per **installation** rather than per world. Live
entries while a mod is loaded; a **tombstone** when it is unloaded, so the next boot does not
re-register an id that a world may already have baked into saved items. A second world in the
same game directory inherits the tombstones, which is conservative (ids stay absent) rather than
wrong. `/vibe info` reads it, and says the honest sentence: *stays registered until the world is
restarted*.

### 4.11 Resources

`data/**` is materialized as `<world>/datapacks/vibemod-<mod>/`, **staged and renamed** — a
half-written directory that vanilla's folder source discovers mid-write is a pack that fails to
load, during the reload the player is watching. `assets/**` joins one runtime client resource
pack (`required=true`, `Pack.Position.TOP`, `fixedPosition`, so vanilla's `rebuildSelected`
re-inserts it after every reload with nothing remembered in `options.txt`).

The installer hooks the **lifecycle**, not the compile path: `/vibe enable` on an already-compiled
mod goes straight to `lifecycle.enable(...)` and never touches the store, so a content installer
on the compile path would materialize on first load and silently skip it on every re-enable
afterwards. It runs **after** the mod's entrypoint, and `Kind.CONTENT` drains **last** — its
close only marks a reload pending, and everything else must be gone before that reload runs.

Namespaces are canonicalized to `vibemod_<modname sanitized>` in the path *and* in every
`"<ns>:…"` id inside the bodies, so two mods cannot collide on a recipe id however they were told
to name themselves. `minecraft:` ids are never touched. **Dotted forms are deliberately not
rewritten**: `item.myns.ruby` in a lang file is a translation key, a mod's own Java may name one,
and Java sources are not rewritten — so rewriting the lang file alone would break exactly the
pairing it was meant to protect. The prompt states the canonical namespace instead, and the
rewrite is the safety net for a model that ignores it.

Validation lives in `PromptLibrary.parse`, not at the store: that is where a rejection becomes a
self-heal round with the model's own text in it, rather than a stack trace after the money has
been spent.

Textures are `.png.grid` — a JSON palette plus rows — encoded to RGBA8 PNG by ~60 lines of
`Deflater` + `CRC32`, no `java.desktop`. The store self-test walks the output's chunk list and
verifies every CRC the way a decoder would.

### 4.12 The reload coordinator

A dirty mark arms a 40-tick timer; every further mark re-arms it; when it expires exactly one
reload runs. A side already reloading never starts a second — it re-arms, so a change landing
mid-reload is not lost. Ticked from the host's existing `END_SERVER_TICK` subscription, so it
inherits that subscription's "null between worlds" lifetime and needs no loader event of its own.

Boot restore of three live mods coalesces to **one** server reload. So does a mod that is created
and deleted inside one debounce window — the live demo did exactly that, faster than any player
could, and the single resulting reload correctly reported the mod as *unloaded* (§7.4).

---

## 5. Teardown matrix

What `/vibe disable` (drain) and `/vibe delete` (unload) each take away, and what documented
residue remains. This table is the honest answer to "is it really gone".

| Capability | `disable` | `delete`/unload | Residue, and why |
| --- | --- | --- | --- |
| Loader event subscriptions | Mod's entry leaves the fanout; dispatch stops | same | The host's permanent `Proxy` stays subscribed forever. It cannot be removed — that is the constraint the whole design exists for. `describeState()` reports `=0` |
| Brigadier commands | Node removed from the live dispatcher; clients resynced | same | None. `Unknown or incomplete command` is asserted in both gates |
| Keybind lease | Slot returned to the pool, unbinding only what VibeMod bound | same | A key the *player* bound to that slot stays bound — deliberate |
| HUD element | Detached from the host's dispatch list | same | The host's own `vibemod:mods` element remains, contributing nothing |
| Open `Screen` | Closed off the player's display if this mod's loader defined it | same | None |
| `data/**` datapack | Directory removed, teardown reload runs, `level.dat` forgets the pack id | same | None — asserted by reading the gzipped `level.dat` in both directions |
| `assets/**` client pack | Files removed from the tree, second client reload runs | same | The pack itself stays registered and simply contributes nothing |
| Registered **item / entity type id** | **Stays in the registry** | Stays; ledger writes a **tombstone** | There is no `MappedRegistry.remove`. Decision 7 |
| An item's own `use()` behaviour | **Still runs** | Still runs | The game calls `Item.use` on the object in the registry directly; there is no host frame in between. The gate asserts this *positively*, so a future change says so instead of going quiet |
| Creative-tab entry | Removed, and a rebuild is queued onto the render thread | same | Queued, not synchronous: a rebuild walks every item in the game and a whole teardown gets 250ms |
| Entity default attributes | Supplier removed from `DefaultAttributes.SUPPLIERS` | same | A **live entity already has its attribute map**; removal affects the next spawn |
| Entity renderer | Provider **replaced** with vanilla's `NoopRenderer`, not removed | same | A disabled mod's entities are still in the world. An invisible mob is a bug report; a crashed client is a lost world |
| Items already in a player's inventory | Remain, holdable, usable | Remain | Same shape as everything else here: the object outlives the mod |

The through-line: **VibeMod can revoke every path it stands in, and no path it does not.** Where
it does not stand in the way, it says so — on the install card, in the ledger, and in a gate
assertion written the positive way round.

---

## 6. What the gates found

Ten real bugs, none of them found by reading. This section exists because it is the argument for
gates that **grow** rather than get replaced.

1. **`/vibe` and every generated command vanished on the first `/reload` on Fabric**, and
   `ctx.onChat` never fired at all — since Phase D of v2. `VibeModFabric.Boot` declared instance
   fields `commandBridge`/`chatBridge` that shadowed the statics of the same name; `wire()`
   assigned the shadows and the process-lived subscriptions read the statics, which stayed null
   forever. NeoForge's structurally identical `Boot` never declared them and never had the bug.
   Found by a new `/reload` assertion written for the *command seam*, which worked fine.
2. **An empty server stops ticking after a minute**, and every tick-counting assertion since
   Phase 0 had been living on borrowed time. Found when Phase 2's extra waits pushed a gate past
   the one-minute mark and a *Phase 0* assertion failed.
3. **"The reload started" is not "the reload finished".** `managedBlock` pumps the task queue, so
   an RCON command issued after `Reloading server data` executed *inside* the reload against the
   old function library — and the assertion was passing vacuously, looking for the absence of a
   string that never appeared. Both gates now wait for the completion line and assert positively.
4. **`ReloadableResourceManager` swaps packs at the start of a reload.** The client gate asserted
   a model, a texture and a translation in one breath; the first two resolved and the third did
   not, because `LanguageManager` had not run yet.
5. **Deleting a pack file out from under a running reload threw.** The end state self-corrected,
   but a stack trace nobody can act on is not an acceptable way to get there. Mutations arriving
   during a reload are now held and applied when it completes.
6. **`Item.Properties.sword(...)` needs the BLOCK registry open.** Found by the dedicated-server
   smoke gate on its first run — building *any tool item* touches the frozen block registry three
   frames before it touches the item registry.
7. **A refused registration poisoned every later datapack reload in the session.** Found by the
   same gate's **Phase 2** assertions failing — nine at once, in a gate that had been green for a
   phase. This is the best argument in the repo for growing gates rather than replacing them.
8. **A runtime-registered entity crashed the render thread**, twice: once spawned in the window
   between the type's registration (server thread) and the mod's deferred client half, once on
   teardown while entities of that type were still in the world.
9. **A creative tab that was invalidated but never rebuilt** kept offering a disabled mod's item,
   because `getDisplayItems()` hands back the list baked at the last rebuild.
10. **The keybind pool ate the mod's own key presses**: the host's per-tick `consumeClick()` drain
    ran on leases with no `onPress`, so a mod polling its own mapping never saw one.

And two the **live demo** found, which no fixture would have (§7):

11. **A 1.20-era ingredient object is silently dropped** while the datapack loads. The mod loads,
    reports success, and cannot be crafted.
12. **The oracle said nothing about an overload mismatch** — the shape a *second* repair round
    produces, once the model has the name right and is guessing the arguments.

One about the gate harness itself, worth carrying: **a `LoadingOverlay` swallows mouse input**
(`MouseHandler.onButton` routes to keybinds only when `gui.overlay() == null`), and a registry
change triggers up to two client reloads. `awaitQuietClient` waits for the coordinator to be idle
*and* the overlay to be gone for a full second together.

---

## 7. What the live demo changed

`scripts/demo-live.sh` boots the dedicated Fabric server, drives `/vibe make` with the DEMO.md
prompts over a real OpenRouter key, and asserts generated → self-healed → live → exercised →
deleted → no residue. It is **not a gate**: it spends money and depends on a model's judgement.
It is the only thing in the repo that tests the prompt, and it earned its place on the first run.

### 7.1 The prompt did not say which side it was on

The first run produced textbook-correct code — `Registry.register(BuiltInRegistries.ITEM, …)`
with `setId` before construction, exactly as the few-shot teaches — on a **dedicated server**,
where the host refuses it. The model had followed the prompt perfectly. The prompt stated both
branches ("SINGLEPLAYER AND LAN-HOST ONLY … on a DEDICATED server the host REFUSES") and left the
model to guess which one applied, so it guessed, was refused, and spent a full repair round
rediscovering something the host had known since boot.

The fix is a **`THIS HOST` block** in the system prompt: `PromptLibrary.systemPrompt(profile,
hostFacts)`, fed by `ModGenerator.setHostFacts(...)`, supplied by the Fabric host from
`platform.isDedicatedServer()`. It goes in the system prompt rather than the request because it
is constant for the life of a host, which is what keeps it cacheable. `null` or blank reproduces
the old prompt **byte for byte** — asserted for every profile, so a host that supplies nothing is
not paying for this.

On the next run the same prompt produced a working mod in **one round, no repair**.

### 7.2 The prompt pointed at an example that no longer existed

Phase 3 replaced the `RubyCharm` few-shot with `RubySword`, and one sentence still read *"The
RubyCharm example below shows the whole shape"*. Asserted now: the prompt names an example it
actually ships.

### 7.3 A recipe that failed silently

The model wrote a smelting recipe with `"ingredient": {"item": "minecraft:redstone"}` — correct
for 1.20, rejected by 26.2 — and got the *shaped* recipe right, because the few-shot shows one.
The result was not an error the mod could self-heal from:

```
Couldn't parse data file 'vibemod_rubysword:ruby' from '…/recipe/ruby.json':
  No key fabric:type in MapLike[{"item":"minecraft:redstone"}]
```

The mod loaded, the card said installed, and the recipe simply was not there. The prompt now
states the general rule (an ingredient is a string, a `#tag`, or an array — never an object),
names the recipe types the few-shot does not show, and says out loud that a bad one **does not
fail your build**.

This is the one place V3 knowingly breaks the "no silent drops" rule, and it is recorded as such
in §9 rather than smoothed over: the host does not surface vanilla's datapack parse errors.

### 7.4 The demo tore down inside the debounce window

The harness deleted a mod one second after materializing its datapack — well inside the 40-tick
window — and the coordinator coalesced the load and the unload into a single reload that
correctly reported the mod as *unloaded*. Nothing was broken except the harness, which was faster
than any player could be. Recorded because it is the coalescing path working, tested by accident.

### 7.5 The prompt budget

Deliverable D asked for a budget audit; adding §7.1 and §7.3 promptly blew it, which is what a
budget is for. Phase 2's rule — *the next thing added to this prompt has to take something out* —
was honoured rather than the number raised. The self-test now asserts the number **as sent**
(profile + host facts), which is the only number a generation actually pays for:

```
  paper-modern     22977 chars  ~  5744 tokens  (2 few-shots)
  paper-legacy     23980 chars  ~  5995 tokens  (2 few-shots)
  fabric           29450 chars  ~  7362 tokens  (3 few-shots)
  fabric-legacy    33773 chars  ~  8443 tokens  (3 few-shots)
  neoforge         33778 chars  ~  8444 tokens  (3 few-shots)
  fabric+host      29970 chars  ~  7492 tokens  (as SENT by a dedicated server)
  fabric+host      29707 chars  ~  7426 tokens  (as SENT by a client)
  repair prompt     6888 chars bare,   10319 chars with a deliberately oversized hint block
```

---

## 8. The symbol oracle

"cannot find symbol" is the single most expensive failure mode in VibeMod: a full round, real
money, real seconds of a player watching a progress bar — and the model's second guess is often
no better than its first, because nothing in the diagnostic says what the type actually offers.
The running game **is** the documentation (generated code compiles against the server's own
jars), so the host looks.

It parses the **formatted diagnostics string**, not `Diagnostic` objects. javac's
`getMessage(null)` already contains the `symbol:`/`location:` lines, so the structured route buys
nothing there, and ECJ's objects carry a differently-shaped message that would need string
parsing anyway. One parser handles both backends, leaves `CompileResult` untouched, and works on
a diagnostics string that has been stored, logged or handed across a thread.

*Recorded honestly:* ECJ names types by their **simple** name, which is unresolvable on its own.
The oracle harvests every fully-qualified type name mentioned anywhere in the same diagnostics
and uses that as a symbol table. It works, there is a self-test, and it fails closed — an
unresolvable owner produces no hint.

Four shapes are read:

| Backend | Shape | Verdict |
| --- | --- | --- |
| javac | `symbol: method x` + `location: …` | member **absent** |
| ECJ | `The method x() is undefined for the type Y` | member **absent** |
| ECJ | `x cannot be resolved or is not a field` | member **absent** |
| javac | `method Owner.x(…) is not applicable` | **signature** — V3 Phase 4 |
| ECJ | `The method x(…) in the type Y is not applicable` | **signature** — V3 Phase 4 |

The signature verdict is not cosmetic. For an absent member the honest sentence is "this type has
no such member, here are near names". For a signature mismatch that sentence is a **lie** — the
model got the name right — and saying "has no `teleportTo`" to a model that just wrote a working
`teleportTo` name is how one repair round becomes two. A signature miss lists **every real
overload, exact-name only, shortest argument list first**, under a header that says so.

Resolution is injected as a `Function` because the classes live on the *host's* class loader, not
`core`'s: `Class.forName(name, false, loader)`, and the `false` matters — a hint must never run a
static initialiser. Hints are bounded (12 members per symbol, 3000 chars total) and the oracle
never throws: a hint is a nicety and must never cost a round.

---

## 9. Accepted limitations and carried notes

Everything the four phase records flagged as "worth carrying", resolved or accepted here.

**Addressed in Phase 4:**

- ~~`describeState()`'s substring caveat~~ — still the rule (all keys are `name=value` with names
  nothing else contains, and gates use full-prefix `contains` checks), and it now has a written
  home rather than living in one phase's notes.
- ~~`/vibe info` assumes a `VibeContext` mod~~ — fixed. It reported `listeners: 0 tasks: 0` for a
  native mod (counters for a kind of registration it does not have) while saying nothing about
  the ones it does. It now names the entrypoints, counts loader-event subscriptions, lists the
  commands the command seam installed (which never called `trackCommandName`), and reports
  resource trees. `entrypoints` is a plain `String` filled in by the host, because the names
  belong to a loader `core` must not import.
- ~~The oracle is blind to overload mismatches~~ — fixed, §8.
- ~~CI never compiles the client-gate source sets~~ — fixed. They live in their own source sets
  that nothing in `build` compiled, and the only task that pulled them in was in the
  `continue-on-error` display job, so a compile error in 1200 lines of gate code produced a green
  required build.
- ~~The smoke gates pick the lexicographically first jar~~ — fixed to newest-first. After a
  version bump the old jar is still in `build/libs` and sorts first, so the gates would have
  tested the previous release and said nothing about it.

**Accepted, with reasons:**

- **The boolean-merge default is `TRUE`.** Every Fabric boolean event checked reads `true` as
  "allow", and that is what `LoaderEventBridge.every()` has always done. An event that read it as
  "handled" would be merged wrongly. Nothing in V3's reach does; this is the assumption to
  revisit when the surface widens.
- **The command seam holds one `CommandBuildContext` for the life of a server**, refreshed on
  every reload. A per-invocation context (dynamic registries a mod itself contributed to) would
  be the thing to build if that ever matters.
- **`ClientSeam` must stay free of client types.** It is held by two classes that load on
  dedicated servers. Adding a `KeyMapping` to it would not fail here — it would fail on somebody's
  server, at class-load time, with a `NoClassDefFoundError` nothing in this repo would catch.
- **The registration window is a shared-JVM race, and it is not gated.** It unfreezes three
  registries on the server thread while the render thread may be iterating them; in singleplayer
  those are the same objects. Nothing has been observed, mods load one at a time and the window is
  microseconds wide. Revisit if registry work ever moves off the load path.
- **A tag a mod names that does not exist** is created empty by
  `getOrCreateTagForRegistration` during the window and lands in `frozenTags` without being in
  `allTags`; `refreshTagsInHolders` iterates `allTags`, so such a tag's holders would be unbound.
  No generated mod has done this yet.
- **The client pack is one tree with per-mod manifests.** Two mods writing the same
  `assets/<ns>/…` path cannot happen (namespaces are per-mod and canonical), but nothing enforces
  it structurally the way the datapack directory does.
- **A dedicated server cannot serve `assets/**`.** They are stored and reported inert, one log
  line per mod. Pushing a server resource pack needs a hosted URL and a hash — a whole feature.
- **Vanilla's datapack parse errors are not surfaced to the mod.** §7.3. A malformed recipe is
  dropped by the game's own loader, on a worker thread, with no return channel; catching it would
  mean attaching a log handler to vanilla's reload, which is a bigger surface than a hardening
  phase should add. The prompt now prevents the common case; the gap is real and this is where it
  is written down.
- **The player-facing mod hub shows no live introspection.** `/vibe info` for a *player* opens
  `HubScreens.modHub`, which renders store data only; the verified facts are one click away under
  "Manual". Fixing the fact lines fixed both places facts are *claimed*; putting them on the hub
  body is a UI change, not a correctness one.
- **`ctx.onChat` is still ungated on Fabric.** Producing a player chat line over RCON is not
  something the harness can do. Flagged for whoever next touches the chat surface.
- **`InstallCard.setRegisteredContent` is a static hook**, so Paper and every self-test see the
  card they always saw. If a second host ever wants it, that is the seam.

---

## 10. Out of scope — do not "helpfully" add these

Each of these was considered, and each has a reason that is a fact about the game rather than a
matter of taste. Reversing one means answering the fact.

| Not shipped | Why not |
| --- | --- |
| **Block registration** | `PalettedContainerFactory` is built once per world load and takes its global palette bit width from the size of `BLOCK_STATE_REGISTRY` at that moment; every chunk section in the loaded world is serialized against that strategy. Adding block states mid-session changes the id space under live containers, and does not necessarily throw. A block registered at *world-load* time would be a different feature with a different design |
| **Any registry other than `ITEM` and `ENTITY_TYPE`** | Block entities, enchantments, biomes, particles, sounds: each has its own baked-at-load story and none has been established the way these two were. Refused with a diagnostic naming what is supported |
| **Registry content on a dedicated server** | Registry sync. A vanilla client joining later would be kicked. The answer there is components on a vanilla item in the recipe result, which the prompt teaches |
| **A pack server for `assets/**` on dedicated** | Needs a hosted URL and a hash — a hosting feature, not a mod-loading one |
| **NeoForge seams** | The whole seam table is Fabric-module. NeoForge keeps the v2 `VibeContext` path plus the loader-neutral datapack channel, which its gate proves end to end. Adding seams there means a second table, a second shim set and a second policy, for a host whose users are already served |
| **Mixins in generated code** | `org/spongepowered/` is denied. A mixin is applied at class-load time to *somebody else's* class and cannot be undone; it is the exact opposite of the property this design exists to preserve |
| **`Event.addPhaseOrdering`** | Phase order is global and cannot be undone on disable |
| **`HudElementRegistry.removeElement` / `replaceElement`** | They act on other mods' elements, permanently and globally. Same objection as phase ordering |
| **`ClientCommandRegistrationCallback`** | Would need §4.4's whole treatment against a per-connection dispatcher. `/vibec` covers the need |
| **Draining an `Item` subclass's own behaviour** | The game calls `Item.use` on the object in the registry directly; there is no host frame in between, and mixing into `use`/`useOn`/`hurtEnemy`/`inventoryTick` is a list somebody maintains against a vanilla class forever. The gate asserts the true behaviour positively instead |
| **Config knobs for native mods** | There is no `ctx` to read one from. The prompt says so plainly rather than letting the model emit a `config[]` the mod cannot honour and a manual promising settings that do not exist |
| **Off-thread work in mod code** | Threads, executors and `*Async` are denied. Mod code runs on the server thread so the watchdog means something |

---

## 11. Gate inventory

| Gate | Runs | Covers |
| --- | --- | --- |
| `./gradlew build` | everywhere, CI required | `:fabric:surgeonSelfTest` (57), all five `:core:selfTest*` including the `RegistryLedger` suite, store/prompt/oracle assertions, the platform-free and pure-JDK checks |
| `./gradlew selfTestEcj` | CI, explicit step | The corpora recompiled on the ECJ backend a JRE-only install falls back to |
| `./gradlew :fabric:compileGametestJava :neoforge:compileClientgateJava` | CI, explicit step (new in 3.0.0) | That the client gates still compile, without needing a display |
| `scripts/smoke-fabric.sh` | CI matrix | 89 assertions on a real dedicated Fabric server, installed jar, driven over RCON |
| `scripts/smoke-neoforge.sh` | CI matrix | 44 — the v2 path plus the loader-neutral datapack channel |
| `scripts/smoke-paper.sh {1.20.6,1.21.8,26.2}` | CI matrix | Untouched by V3, and that is the point |
| `./gradlew :fabric:runClientGameTest` | display / xvfb | 114 assertions in a real client: real GL, a real world, a real right-click |
| `scripts/clientgate-neoforge.sh` | display / xvfb | A self-driving mod, since NeoForge has no harness |
| `scripts/demo-live.sh` | by hand, needs a key | **Not a gate.** The prompt's only test |

The two client gates are `continue-on-error` in CI until they have passed a few times in a row;
their *compilation* is now required, which is the half that was silently unprotected.
