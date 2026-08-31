# Phase 3 result — the registry seam: real items and entity types at runtime

All four deliverables (A–D) landed. All four gates are green, and the one that
matters is the fourth: inside a real Minecraft client, a mod written as a
**plain Fabric mod** — `implements ModInitializer, ClientModInitializer`, with
zero VibeMod imports — registers a **real item** with `Registry.register(
BuiltInRegistries.ITEM, id, new RubySwordItem(new Item.Properties()
.sword(ToolMaterial.IRON, 4.0F, -2.4F).setId(key)))` and a **real entity type**,
and every one of those words is true in the running game: the id is in
`BuiltInRegistries`, its data components are bound, its two-file item model and
its hand-encoded PNG resolve through the game's own resource manager, its lang
key translates, it sits in the creative Ingredients tab, **the game's own recipe
lookup resolves a real 3×3 grid and assembles the registered item out of it**, a
**real right mouse click** reaches the `use` override the mod wrote, and the
runtime-registered entity spawns and is drawn by a real renderer.

`/vibe disable` takes away the recipe, the creative-tab entry and the mod's
command. It does not take away the id — there is no `MappedRegistry.remove` and
there was never going to be one — and the registry ledger is where that is
written down rather than hidden.

On a dedicated server the whole thing is refused, deterministically, with the
policy sentence in the log, in `/vibe errors`, and in the exception that fails
the mod's load.

---

## The three findings that shaped the implementation

Everything below was read off the 26.2 jars with `javap` or produced by a gate,
never recalled. Three of them contradict the brief, and each changed the design.

### 1. The unfreeze cannot live at the `Registry.register` call site

The brief's shape — "unfreeze (accessor) → register → refreeze" inside the shim
— cannot work, and one line of disassembly says why:

```
public net.minecraft.world.item.Item(Item$Properties);
   5: getstatic       BuiltInRegistries.ITEM
   9: invokeinterface DefaultedRegistry.createIntrusiveHolder:(Ljava/lang/Object;)…
```

`Item.<init>` **writes to the registry**. In
`Registry.register(BuiltInRegistries.ITEM, id, new Item(props))` the constructor
is an *argument*: it runs to completion before the shim that was supposed to
unfreeze the registry is entered. And `MappedRegistry.freeze()` sets
`unregisteredIntrusiveHolders = null` (line 150–152 of its bytecode), so after
boot `createIntrusiveHolder` throws `This registry can't create intrusive
holders` — before anything of ours is reached.

So the unfreeze is a **window around the mod's whole `onInitialize()`**, opened
by `FabricEntrypointAdapter` on the server thread. `ITEM`, `ENTITY_TYPE` and
`BLOCK` all come from `registerDefaultedWithIntrusiveHolders`, so all three need
both `frozen = false` and a restored intrusive-holder map. The seam on
`Registry.register` stays — it namespaces the id, refuses the unsupported cases,
journals the entry and tracks it for teardown — but it is no longer the thing
that makes writing legal.

### 2. Refreezing is not `freeze()`, and the smoke gate found what that costs

`freeze()` is not safe to call twice: it throws `Tags already present before
freezing` when `allTags` is bound, which it is after the first freeze. So the
close sets `frozen = true` directly and then does, explicitly, the two things
`freeze()` does that a newly registered holder needs and `register()` does not:

- `refreshTagsInHolders()` (an `@Invoker`), without which
  `Holder.Reference.is(TagKey)` throws `Tags not bound`;
- a fresh `DataComponentLookup` over `byId`, because `freeze()` builds one and
  its per-component-type cache is otherwise stale.

Components themselves are bound by running vanilla's own
`DataComponentInitializers.build(provider).forEach(PendingComponents::apply)` —
the same call `ReloadableServerResources` makes on every datapack reload, which
is also why the coordinator's reload repairs anything the eager pass misses.
Without it, `new ItemStack(theItem)` throws `Components not bound yet` and
nothing else works.

**And then the dedicated-server smoke gate produced a bug that no amount of
reading would have found.** `Item.<init>` also appends an entry to
`BuiltInRegistries.DATA_COMPONENT_INITIALIZERS`, keyed by the id — *after*
`createIntrusiveHolder` and *before* our refusal fires. Nothing ever removes
that entry, and `DataComponentInitializers.build` resolves every key it holds.
So one item that was constructed and refused turned **every subsequent datapack
reload in the session** into:

```
java.util.concurrent.CompletionException: java.lang.IllegalStateException:
  Missing element ResourceKey[minecraft:item / vibemod_registrycanary:ruby_sword]
```

Nine Phase 2 assertions failed at once, in a gate that had been green for a
phase. `DataComponentInitializersAccessor` + a snapshot/rollback on the window
closes it; the smoke gate now asserts the rollback line, the absence of
`Missing element`, and the discarded orphan.

### 3. Late entity-renderer registration works, and crashes the client anyway

The brief asked for an experiment. Here is the verdict, in three parts.

**Late registration works.** fabric-rendering's `EntityRendererRegistryImpl`
swaps its buffering handler for a direct write into vanilla's
`EntityRenderers.PROVIDERS` the first time `createEntityRenderers` runs, and
that map is a mutable `Object2ObjectOpenHashMap` which
`EntityRenderDispatcher.onResourceManagerReload` — a real
`ResourceManagerReloadListener` — rebuilds from. So "register the provider, then
rebuild" is a complete path to a rendering custom entity. The gate asserts
`client.getEntityRenderDispatcher().getRenderer(entity)` is a real
`PigRenderer`.

**But waiting for a resource reload is not good enough.** The first run of the
new gate crashed the client outright:

```
java.lang.NullPointerException: Cannot invoke
  "net.minecraft.client.renderer.entity.EntityRenderer.shouldRender(…)" because "renderer" is null
	at EntityRenderDispatcher.shouldRender(EntityRenderDispatcher.java:123)
	at LevelExtractor.extractVisibleEntities(LevelExtractor.java:244)
```

The dispatcher bakes its `EntityType -> EntityRenderer` map once per reload; a
type registered afterwards is simply absent, and the first frame in which one is
visible dies. Two windows produce this, and both are real:

- the entity type is registered synchronously in `onInitialize` (server thread)
  while the mod's own renderer is registered in `onInitializeClient`, which
  Phase 1 **defers to the render thread** — anything spawned in between crashes;
- disabling the mod removed the provider while entities of that type were still
  in the world — the second crash, on teardown.

**So both ends are closed, and neither waits for a reload.**
`onResourceManagerReload` is public and, disassembled, entirely self-contained
(every input is one of the dispatcher's own fields), so `ClientShims` calls it
directly to rebuild on the spot. Registering an entity type installs vanilla's
`NoopRenderer` immediately through a new `ClientSeam.ensureEntityRenderer`, and
the mod's real renderer replaces it a frame later. Draining **replaces** the
mod's provider with `NoopRenderer` rather than removing it, because a disabled
mod's entities are still in the world. An invisible mob is a bug report; a
crashed client is a lost world.

**Verdict recorded:** custom entity types with custom renderers are supported,
and the prompt teaches the safe shape anyway (subclass a vanilla mob so a
vanilla renderer already exists), because a mod that ships its own model layer
is a much larger surface than this phase gated.

---

## What landed, by deliverable

### A. The registry seam (fabric)

| File | What |
| --- | --- |
| `fabric/.../shim/RegistrySeam.java` | New. The window, the five register entry points, the id rewrite, the ledger, the tab hook |
| `fabric/.../shim/RegistryTarget.java` | New. What `Shims` delegates to — an interface for the same reason `EventSeam` is one |
| `fabric/.../shim/CreativeTabs.java` | New. One permanent `CreativeModeTabEvents` listener, and the cache invalidation |
| `fabric/.../mixin/MappedRegistryAccessor.java` | New. `frozen`, `unregisteredIntrusiveHolders`, `componentLookup`, `byId`, `refreshTagsInHolders` |
| `fabric/.../mixin/CreativeModeTabsAccessor.java` | New. `CACHED_PARAMETERS`, the one field between a new item and the creative menu |
| `fabric/.../mixin/DefaultAttributesAccessor.java` | New. `SUPPLIERS`, so a drain can remove |
| `fabric/.../mixin/DataComponentInitializersAccessor.java` | New. The rollback for a refused registration (finding 2) |
| `fabric/.../mixin/client/EntityRenderersAccessor.java` | New. `PROVIDERS`, for the no-op fallback and the replacement |
| `core/.../store/RegistryLedger.java` | New. live/tombstone, JSON, atomic write |
| `core/.../runtime/ModLifecycle.java` | `onUnload(Consumer<String>)` — the one hook that separates "disabled" from "deleted" |
| `core/.../ui/InstallCard.java` | `registered content: …  (stays registered until the world is restarted)` |
| `fabric/.../FabricSeams.java` | Ten new seams (7 → 17) |
| `fabric/.../FabricEntrypointAdapter.java` | Opens the window around `onInitialize()` |
| `fabric/.../VibeModFabric.java` | Builds the seam and the tab listener once per process; installs the ledger per server |

**All five `Registry` statics are seamed**, and the two `ResourceKey` overloads
are told apart by their *return* type — which is why the seam matches on the
whole descriptor rather than on name and arity:

```
register(Registry, String, Object)Object
register(Registry, Identifier, Object)Object
register(Registry, ResourceKey, Object)Object
registerForHolder(Registry, ResourceKey, Object)Holder$Reference
registerForHolder(Registry, Identifier, Object)Holder$Reference
```

Five more seams exist because 26.2 needs them: `Item$Properties.setId` (the id
is required *before* the item exists — `Item.<init>` calls
`Properties.itemIdOrThrow()` twice, for the description id and the model id, so
rewriting the namespace at register time would leave the item pointing at an
`assets/` path in a namespace nothing writes to), `EntityType$Builder.build`,
both `FabricDefaultAttributeRegistry.register` overloads, and both entity
renderer registrations.

**Namespacing** mirrors Phase 2 exactly: whatever the mod writes, the id that
reaches the registry is `vibemod_<modname sanitized>:<path>`, from the same
`ModResources.canonicalNamespace` the resource tree uses. That is what makes the
recipe's `"vibemod_x:ruby_sword"`, the model at
`assets/vibemod_x/items/ruby_sword.json` and the lang key
`item.vibemod_x.ruby_sword` agree by construction rather than by the model's
care.

**Only `ITEM` and `ENTITY_TYPE` may be written to.** `BLOCK` is unfrozen by the
window but refused by the shim — see the spike below. Every other registry gets
a diagnostic naming what *is* supported.

### B. Entity types

`EntityType.Builder.build(ResourceKey)` is namespaced through the same rewrite;
the type goes through the same registry shim;
`FabricDefaultAttributeRegistry.register` is seamed (not because it would fail —
it is a plain `Map.put` into a map fabric-object-builder-api has already made
mutable — but because nothing else could take it away again); the client half is
covered by finding 3 above. The gate spawns one with
`TYPE.create(level, EntitySpawnReason.COMMAND)` + `level.addFreshEntity(...)`
and asserts it renders.

### C. Prompt

The registry ban is lifted for items and entity types, with the 26.2 facts that
a model will otherwise get wrong: `setId(...)` **before** construction, no
`SwordItem` class at all (`Item.Properties.sword(ToolMaterial.IRON, 4.0F,
-2.4F)` is what makes a sword now), subclassing `Item` for behaviour, the lang
key that is derived from the id, the creative tab, the singleplayer/LAN-host
limit and the dedicated-server refusal — and a by-name refusal of blocks *with
the reason*.

One new few-shot, `RubySword`: a registered item with a `use` override, a
recipe, an advancement, a lang file, the 26.x two-file item model and a 16×16
pixel-grid texture. Its Java was compiled against the real Loom classpath with
`javac` before being embedded, and every signature in it was javap'd.

```
  paper-modern     22977 chars  ~  5744 tokens  (2 few-shots)
  paper-legacy     23980 chars  ~  5995 tokens  (2 few-shots)
  fabric           29196 chars  ~  7299 tokens  (3 few-shots)
  fabric-legacy    33773 chars  ~  8443 tokens  (3 few-shots)
  neoforge         33778 chars  ~  8444 tokens  (3 few-shots)
```

29196 against the brief's ≤30k budget, still at three few-shots. How, is
deviation 4.

### D. Gates

| Gate | Before | After |
| --- | --- | --- |
| `./gradlew build` (incl. `:fabric:surgeonSelfTest`) | green, 38 surgeon assertions | green, **57** surgeon assertions + a new `RegistryLedger` suite |
| `scripts/smoke-fabric.sh` | 75 | **89** |
| `scripts/smoke-neoforge.sh` | 44 | **44**, unchanged |
| `:fabric:runClientGameTest` | 79 | **114** |

---

## The block feasibility spike, and its verdict

**Blocks are refused.** Not because they are impossible — the spike found that
most of the machinery is public — but because of one thing that is not.

What works: `BlockBehaviour.Properties` has `setId` like items do; `Block.<init>`
uses `createIntrusiveHolder` exactly as `Item.<init>` does, so the same window
covers it; `Block.BLOCK_STATE_REGISTRY.add(state)` and `BlockState.initCache()`
are both public and are literally what `Blocks`' own static initializer calls;
and blockstate models would arrive through the Phase 2 respack like any other
asset.

What does not:

```
public static PalettedContainerFactory create(RegistryAccess) {
   0: getstatic  Block.BLOCK_STATE_REGISTRY
   3: invokestatic Strategy.createForBlockStates:(IdMap)Strategy
```

`PalettedContainerFactory` is built **once per world load** and takes its global
palette bit width from the size of `BLOCK_STATE_REGISTRY` at that moment. Every
chunk section in the loaded world is serialized against that strategy. Adding
block states mid-session changes the id space under live containers — which does
not necessarily throw, and that is exactly what makes it the wrong thing to ship
in a phase whose promise is that generated content is real. A block registered
at world-load time would be a different feature with a different design.

The refusal names the mechanism and steers to items and `data/**`, and the
prompt says the same thing in one line.

---

## Deviations from the brief, and why

**1. The unfreeze is a window around `onInitialize()`, not a step inside
`Registry.register`.** See finding 1. The seam still exists and still does
everything else §A asks of it.

**2. The window unfreezes `BLOCK` as well, and it took a gate to find out.**
`Item.Properties.sword(...)` calls
`BuiltInRegistries.acquireBootstrapRegistrationLookup(BuiltInRegistries.BLOCK)
.getOrThrow(BlockTags.SWORD_EFFICIENT)`, and
`acquireBootstrapRegistrationLookup` is `createRegistrationLookup()`, which calls
`validateWrite()`. So building *any tool item* touches the frozen block registry
three frames before it touches the item registry:

```
java.lang.IllegalStateException: Registry is already frozen
  at MappedRegistry.validateWrite(MappedRegistry.java:84)
  at MappedRegistry.createRegistrationLookup(MappedRegistry.java:379)
  at BuiltInRegistries.acquireBootstrapRegistrationLookup(BuiltInRegistries.java:352)
  at ToolMaterial.applySwordProperties(ToolMaterial.java:71)
  at Item$Properties.sword(Item.java:281)
```

Unfreezing it is not the same as allowing blocks: `register` still refuses
`BuiltInRegistries.BLOCK` by name. What the window buys is that the refusal is
ours and legible instead of a vanilla stack trace from inside a builder.

**3. There is no `ItemGroupEvents` and no `fabric-item-group-api-v1` in this
era.** §A named both. `javap` over fabric-creative-tab-api-v1 5.0.14 says the
class is `net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents` and the
method is `modifyOutputEvent(ResourceKey<CreativeModeTab>)`, whose callback takes
a `FabricCreativeModeTabOutput`. The tab is `minecraft:ingredients`, resolved by
`ResourceKey` because `CreativeModeTabs`' own tab keys are all
`private static final`. `accept(ItemStack)` defaults to
`PARENT_AND_SEARCH_TABS`, so creative search finds it too.

The rebuild needed a mixin nobody expected: `tryRebuildTabContents` is public but
short-circuits on `CACHED_PARAMETERS.needsUpdate(...)`, which compares the holder
provider by **reference identity** (`if_acmpeq`) — and registering an item
changes none of the three things it compares. Clearing the cached parameters is
the whole mechanism; after that, vanilla's own rebuild runs vanilla's own way,
and the creative screen would pick the change up even if VibeMod never asked.

**4. `RubyCharm` was replaced by `RubySword` rather than joined by it.** §C asked
for a `RubySword` few-shot and a ≤30k budget. Four few-shots came to 34109
chars. The two Ruby examples taught the same six file shapes around the same
kind of item, differing only in whether the item was real — 4.5k characters of
prompt, on every round of every generation, to say one thing twice. So
`RubySword` absorbed `RubyCharm`'s advancement and its whole resource layout,
and the one shape that did **not** survive the merge — a recipe result wearing
`minecraft:custom_name`/`lore`/`item_model` components, still the only answer on
a dedicated server — moved into the cheat sheet as a literal.

Nothing `LlmSelfTest` asserted was weakened: every shape assertion (the recipe,
the advancement's `recipe_crafted`/`recipe_id`, the two-file item model, the
grid, the canonical namespace) now points at `RubySword`'s files, and eight new
assertions cover the registry surface. Two were strengthened: the lang key is
now asserted to be the one the item id derives, and the item model's parent is
`minecraft:item/handheld` rather than `generated`, which is the tool shape.

**5. `describeState()` is on the seam, not folded into the fanout's line.**
§A said "counts for gates". `EventFanout.describeState()` is already three
concerns wide and Phase 1 wrote down what happens when a new counter's name is a
substring of an old one. `RegistrySeam.describeState()` is reachable from
`VibeModFabric.registrySeam()` and reads
`registryMods= registryItems= registryEntityTypes= registryAttributes=
tabRebuilds= ledgerMods= ledgerIds= ledgerTombstones=`.

**6. §D's "disable → behaviour drained (use no longer writes)" is not
achievable, and the gate asserts the opposite on purpose.** The game calls
`Item.use` on the object in the registry directly; there is no host frame in
between to drain, and mixing into `Item.use` (and `useOn`, and `hurtEnemy`, and
`inventoryTick`…) would be a list somebody has to maintain against a vanilla
class. §A's own text already says this ("subclasses keep their code but their
event/command registrations are drained"), so the gate pins the true behaviour:

```
  ok: a disabled mod's item subclass STILL runs its own use() — there is no seam
      between the game and an object it already holds
```

What *is* gone on disable is asserted right above it: the recipe, the
creative-tab entry, the mod's command, and every event it subscribed to. So the
item cannot be obtained any more, and everything it reaches through the host is
dead. Recorded here rather than smoothed over, and the assertion is positive so
that if this ever changes the gate says so instead of going quiet.

**7. The "reboot inside the gate" is a ledger assertion, as §D allowed.** The
gate framework boots once, so the tombstone is asserted three ways instead: the
in-memory ledger says so, the JSON on disk says so, and `:core:selfTestStore`
opens a *fresh* `RegistryLedger` over the same file and asserts it still says so
— which is the only property the tombstone actually has to have.

**8. Both entity-renderer entry points are seamed, and one of them is
vanilla's.** `EntityRendererRegistry` carries `@Deprecated` in
fabric-rendering-v1 25.3.2, because
fabric-transitive-access-wideners-v1 makes vanilla's own
`EntityRenderers.register` public for every mod:

```
transitive-accessible method net/minecraft/client/renderer/entity/EntityRenderers
    register (Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/client/renderer/entity/EntityRendererProvider;)V
```

The seam table covers both — the fabric wrapper because a model trained on older
tutorials will write it, the vanilla method because current code does.

**9. `ClientSeam` gained a method, and it still names no client type.**
`ensureEntityRenderer(EntityType<?>)` is how the registry seam (which runs on
dedicated servers) asks the client for the no-op fallback.
`net.minecraft.world.entity.EntityType` is common, so Phase 1's rule holds.

---

## The bugs the new gates found

Four, all of them in this phase's own code, and each was found by a gate rather
than by reading.

1. **`Item.Properties.sword` needs the BLOCK registry open** — deviation 2.
   Found by the dedicated-server smoke gate on its first run.
2. **A refused registration poisoned every later datapack reload** — finding 2.
   Found by the same gate's *Phase 2* assertions failing, which is the best
   possible argument for gates that grow rather than get replaced.
3. **A runtime-registered entity crashed the render thread** — finding 3. Found
   by the client gate, twice: once on spawn, once on teardown.
4. **A creative tab that was invalidated but never rebuilt** kept offering a
   disabled mod's item, because `getDisplayItems()` hands back the list baked at
   the last rebuild. The drain now queues a rebuild onto the render thread —
   queued, not synchronous, because a rebuild walks every item in the game and
   the watchdog gives a whole teardown 250ms.

And one about the gate itself, worth carrying forward: **a `LoadingOverlay`
swallows mouse input.** `MouseHandler.onButton` routes to keybinds only when
`gui.overlay() == null`, and a registry change triggers up to two client reloads.
The first version of the click test read `hand=0 minecraft:air useDown=false` for
its trouble. `awaitQuietClient` waits for the coordinator to be idle *and* the
overlay to be gone for a full second together.

---

## Notes worth carrying into Phase 4

- **The window is a shared-JVM race, and it is not gated.** It unfreezes three
  registries on the server thread while the render thread may be iterating them.
  In singleplayer they are the same objects. Nothing has been observed, mods
  load one at a time and the window is microseconds wide, but this is the
  assumption to revisit if registry work ever moves off the load path.
- **`DataComponentLookup`'s cache is rebuilt, but its `elements` are live.** It
  wraps `byId` directly, so new items are visible to a fresh query; the rebuild
  exists only to drop stale per-component-type caches.
- **A tag a mod names that does not exist gets created empty by
  `getOrCreateTagForRegistration` during the window, and lands in `frozenTags`
  without being in `allTags`.** `refreshTagsInHolders` iterates `allTags`, so such
  a tag's holders would be unbound. No generated mod has done this yet.
- **The ledger is per installation, not per world.** `<datadir>/registry-ledger.json`
  sits next to the store, so a tombstone follows the installation. A second world
  in the same game directory inherits the tombstones, which is conservative
  (ids stay absent) rather than wrong.
- **`InstallCard.setRegisteredContent` is a static hook.** Paper and every
  self-test see the card they always saw; the Fabric host installs the lookup.
  If a second host ever wants it, this is the seam.
- **`registryAttributes` is drained but `DefaultAttributes.SUPPLIERS` is only
  correct for types nothing spawned.** A live entity already has its attribute
  map; removing the supplier affects the next spawn, not the existing one. That
  is the same shape as everything else in this phase: the object outlives the
  mod.
- **NeoForge has none of this.** The seam is fabric-module and the NeoForge
  policy already denies `net/fabricmc/`, so a registry mod there is a compile
  diagnostic. Its gate is unchanged at 44.

---

## Gate results

All four run on this machine, on the final tree. Verbatim tails.

### 1. `./gradlew build`

```
> Task :core:check
> Task :core:build

BUILD SUCCESSFUL in 4s
38 actionable tasks: 10 executed, 28 up-to-date
```

`:fabric:surgeonSelfTest` runs inside `check` and grew from 38 to 57. The twenty
new assertions, verbatim (call-site lists elided for width):

```
  ok: a mod calling Registry.register passes the policy: 
  ok: Registry.register was redirected to the host shim 
  ok: no call to the real Registry.register survived 
  ok: the rewritten registry call reached the host shim
  ok: the shim received the mod's own id
  ok: the shim received the mod's own value
  ok: and the mod got its object back (static final ITEM = register(...) still works)
  ok: the RubySword few-shot shape passes the policy: 
  ok: its Registry.register went through the host shim 
  ok: its Item.Properties.setId went through the host shim 
  ok: no raw Registry.register or Properties.setId survived 
  ok: the builder call that is NOT a seam is untouched 
  ok: a mod building an EntityType passes the policy: 
  ok: EntityType.Builder.build was redirected to the host shim 
  ok: FabricDefaultAttributeRegistry.register was redirected too 
  ok: neither original survived 
  ok: a mod registering an entity renderer passes the policy: 
  ok: EntityRendererRegistry.register was redirected to the client shim 
  ok: no call to the real EntityRendererRegistry survived 
ALL CHECKS PASSED
```

and in `:core:selfTestStore`:

```
PASS: the registry ledger records ids, tombstones an unloaded mod, and survives a restart
```

### 2. `scripts/smoke-fabric.sh` — 89/89, exit 0

```
== asserting the registry seam refuses a dedicated server (V3 Phase 3 §A/§D)
  ok: the registry seam refused a dedicated server
  ok: and the refusal states the deterministic policy verbatim
  ok: the refusal failed the mod's LOAD rather than being swallowed
  ok: the mod's onInitialize never got past the refusal
  ok: the refusal did not stop the other mods loading
  ok: and did not stop the resource channel either
  ok: the item id really is absent from the running game
  ok: nothing was tombstoned or written to a ledger on a host that registers nothing
  ok: the refused item's half-built state was rolled back
  ok: so no later datapack reload was poisoned by it
  ok: and the orphaned item object was discarded, loudly
  ok: the refusal is journalled where /vibe errors can show it
  ok: and it is journalled as an onInitialize failure, not a crash
  ok: and the refused mod is not live

== PHASE D DEDICATED-SERVER GATE PASSED
```

and the message an operator actually sees, out of `/vibe errors RegistryCanary`:

```
1× java.lang.UnsupportedOperationException: Mod RegistryCanary tried to register
ruby_sword into minecraft:item on a dedicated server: registry content is
singleplayer/LAN-host only in v1; applies after restart on dedicated. A vanilla
client that joins later negotiates a registry sync without this id and would be
kicked, so VibeMod refuses rather than working until somebody logs in. Ship the
item as a data/** recipe whose result carries minecraft:custom_name and
minecraft:item_model instead, or run this mod on a singleplayer or LAN-hosted
world.
```

### 3. `scripts/smoke-neoforge.sh` — 44/44, exit 0

Unchanged, and it has to be: the registry seam is fabric-module, and the only
`core` changes (`ModLifecycle.onUnload`, `InstallCard`'s static hook) are
loader-neutral and default to the previous behaviour.

```
  ok: no missing-data-pack warning was produced
== stopping server (pid 32656)

== PHASE E DEDICATED-SERVER GATE PASSED
```

### 4. `./gradlew :fabric:runClientGameTest` — 114/114, exit 0

Run for real, on this Mac's display. The Phase 3 block, verbatim:

```
  ok: the registry seam was installed at mod init
  ok: and it starts holding nothing (registryMods=0 registryItems=0 registryEntityTypes=0 registryAttributes=0 tabRebuilds=0 ledgerMods=0 ledgerIds=0 ledgerTombstones=0)
  ok: the item is in the game's own item registry
  ok: and the registry hands back the MOD's own subclass, not a vanilla stand-in
  ok: the entity type is in the game's own entity registry
  ok: its default attributes were registered
  ok: its data components were bound, so an ItemStack of it can exist
  ok: the seam counts what it registered (registryMods=1 registryItems=1 registryEntityTypes=1 registryAttributes=1 tabRebuilds=1 ledgerMods=1 ledgerIds=2 ledgerTombstones=0)
  ok: and the ledger recorded both ids (registryMods=1 registryItems=1 registryEntityTypes=1 registryAttributes=1 tabRebuilds=1 ledgerMods=1 ledgerIds=2 ledgerTombstones=0)
  ok: the item is in the creative INGREDIENTS tab
  ok: a NEW client resource reload completed for this mod
  ok: its item-model DEFINITION resolves (26.x's assets/<ns>/items/<id>.json)
  ok: and its texture is a real PNG the client can find
  ok: its name translates from the lang key the item id derives
  ok: the game's own recipe lookup finds the mod's recipe and assembles the runtime-registered item out of it
  ok: the client is quiet enough to receive a click before the interaction test
  ok: the mod's command ran and put the item in the player's hand
  ok: and the CLIENT sees the runtime-registered item in that hand
  ok: a real right-click reached the item subclass's own use() override
  ok: the mod's client half registered a renderer for its entity type
  ok: the runtime-registered entity type spawned into the world
  ok: and the client has a REAL renderer for it after the resource reload (late EntityRendererRegistry.register works)
  ok: disabling took the item out of the creative tab
  ok: disabling removed the recipe with the rest of the datapack
  ok: disabling removed the mod's command
  ok: the seam holds nothing for it any more (registryMods=0 registryItems=0 registryEntityTypes=0 registryAttributes=0 tabRebuilds=3 ledgerMods=1 ledgerIds=2 ledgerTombstones=0)
  ok: the item is STILL in the registry, because a registry has no remove
  ok: and the ledger still lists it as this mod's, live
  ok: a disabled mod's item does not crash the client that is holding one
  ok: the client is quiet again after the teardown's reloads
  ok: the player is still holding the disabled mod's item
  ok: a disabled mod's item subclass STILL runs its own use() — there is no seam between the game and an object it already holds
  ok: unloading tombstoned the mod's ids
  ok: and the tombstone is on disk, so the next boot will not re-register them
  ok: the ledger counts it (registryMods=0 registryItems=0 registryEntityTypes=0 registryAttributes=0 tabRebuilds=3 ledgerMods=1 ledgerIds=2 ledgerTombstones=1)
PHASE D CLIENT GATE PASSED
```

No pre-existing check was removed, weakened or skipped in any of the four.
