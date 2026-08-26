# VibeMod v4 Architecture — Blocks

*Status: authoritative for VibeMod on Fabric, superseding `docs/ARCHITECTURE-V3.md`, which is now
history. Written after V4 Phase 1 shipped, from the approved plan and from the code that came out
of it. Companion document: `docs/ARCHITECTURE-V2.md`, still authoritative for everything neither
V3 nor V4 touched — the module graph, the screen model, the Paper host, the `VibeContext` flavor.
This document carries V3 forward rather than sitting beside it: everything V3 said that is still
true is restated here, and everything it said that turned out to be false is recorded as such
rather than quietly dropped. §2.1 is the list.*

---

## The thesis, in one paragraph

V3's thesis was that a generated mod should be **an ordinary Fabric mod** — the code the model
already knows, with zero VibeMod imports — and that the host should intercept, in bytecode, the
handful of call sites that would otherwise be unrevocable. That thesis is unchanged and V4 adds
nothing to it. What V4 changes is the size of the world the mod can reach into. V3 could make an
item and an entity type real at runtime and refused blocks by name, because the global block
palette bakes a bit width once per world load and V3 could not prove that growing the blockstate
registry underneath it was safe. Disassembling 26.2 answered that: the id space is append-only, a
section below 9 bits per entry holds object references and cannot care, and the one real hazard —
crossing a power of two — throws loudly out of `Validate.inclusiveBetween` instead of corrupting.
A refusal became a **guard**. The thing that guard cannot do is give an id back, and that is the
other half of Phase 1: a block id can never be tombstoned, because a section palette that loses an
entry is a section whose terrain silently shifts.

---

## 0. Decision log (locked; do not relitigate)

| # | Decision | Why |
|---|---|---|
| 1 | **Seam architecture over a bespoke API.** Generated mods are plain Fabric mods; the host intercepts at choke points | LLMs write in-distribution code far better than they write against an invented wrapper. The v2 native profile is 4k characters *smaller* than the legacy one it replaced and teaches five more surfaces, because it teaches almost nothing |
| 2 | **The surgeon is installed on the compiler, not on any one code path** | Every route from source to live classes runs through `InMemoryCompiler.compile`: generation, repair rounds, `/vibe edit`, rollback, restore-on-boot. One hook covers all of them, and a policy violation becomes a javac-shaped diagnostic in the existing self-heal loop rather than a second error channel |
| 3 | **The scan walks instructions, not the constant pool** | Lambdas, records and pattern switches all put `MethodHandle` into the pool, so a pool-level rule rejects the most ordinary Java there is. Walking instructions lets the rule be the right one: a dynamic call site is fine when its *bootstrap* is one javac emits, and the handles javac threads through those bootstraps are checked as the ordinary member references they are — which is how `Thread::start` is caught despite appearing nowhere as an `invokevirtual` |
| 4 | **The rewrite is shape-preserving**: `invokevirtual` → `invokestatic` with the receiver prepended to the descriptor | The operand stack before and after is identical, so no frame is recomputed and no verifier is argued with. `Seam.prependingReceiver` derives the shim descriptor from the original, so the two cannot drift |
| 5 | **A class with no seam hit is returned byte-identical** — asserted, not assumed | It is what makes the legacy `VibeContext` corpus *provably* unaffected by a pass that runs over it on every compile |
| 6 | **The registry unfreeze is a window around the whole `onInitialize()`**, not a step inside the `Registry.register` shim | `Item.<init>` writes to the registry itself (`createIntrusiveHolder`). In `Registry.register(ITEM, id, new Item(props))` the constructor is an *argument*: it runs, and throws, before the shim that was supposed to unfreeze anything is entered. `Block.<init>` has byte-for-byte the same shape, which is why blocks needed no change to the window at all. §3.4, §4.9 |
| 7 | **Tombstones, not lies.** `/vibe disable` cannot remove a registry id, and the ledger says so | There is no `MappedRegistry.remove` and there was never going to be one. The install card says *stays registered until the world is restarted*, the ledger records it per installation, and unloading writes a tombstone so the next boot does not re-register |
| 8 | ~~**Blocks are refused**, by name, with the mechanism in the message~~ — **reversed in V4 Phase 1** | The original reason: "`PalettedContainerFactory` takes its global palette bit width from the size of `BLOCK_STATE_REGISTRY` once per world load, and every chunk section in the loaded world is serialized against that strategy. Adding block states mid-session changes the id space under live containers — which does not necessarily *throw*." **The answer, verified by disassembly: it is not the id space that changes, and it does throw.** `IdMapper.add` stores at `nextId++` and nothing renumbers, so the id space is only *extended*; a section below 9 bits per entry holds object references and cannot be reached by registry growth at all; and the single case that does bite — a container one bit too narrow for an id that now exists — opens `SimpleBitStorage.set` with `Validate.inclusiveBetween(0L, mask, value)`. Loud, catchable, gateable. §2.1, §4.13 |
| 9 | **Registry content is refused on a dedicated server**, deterministically, at load | A vanilla client joining later negotiates a registry sync without the id and would be kicked. Working until somebody logs in is worse than not working. Unchanged by Phase 1, and blocks make it stronger rather than weaker: a joining client's `BLOCK_STATE_REGISTRY` is smaller than ours, and chunk packets carry global blockstate ids. §10 |
| 10 | **No silent drops.** Anything the host cannot honour is refused loudly — at compile time as a diagnostic, at registration time as a throw, or at dispatch time as a journalled error | The house rule. It is why `Event.register` via a method reference is a policy error rather than an untracked subscription, why a stub whose rebuilt state count disagrees with the recorded one refuses to register, and why a command-name collision lands in `/vibe errors` instead of a log line |
| 11 | **Every dispatch into mod code goes through `ModDispatch` under `ModAttribution`** | One watchdog, one journal, one attribution model, whether the call came from a curated hook or from a loader event the mod subscribed to itself. The single exception is `onInitialize`, which must be able to *throw* so the load rolls back — §4.7 |
| 12 | **The seam table is Fabric-only** | NeoForge keeps the v2 `VibeContext` path and the (loader-neutral) datapack channel. Its policy denies `net/fabricmc/`, so a Fabric-API mod there is a compile diagnostic, not a crash |
| 13 | **The blockstate budget is a guard, not a refusal.** A block whose states fit under the current global palette width registers with no sweep, no repack and no packets; a block that does not fit forces a **crossing** | The free path has to be genuinely free, because it is every block anybody will ever generate. 26.2 ships 32,366 blockstates against a 15-bit ceiling of 32,768 — **402 spare**, about five stairs blocks — so the crossing is a real path rather than a theoretical one, and it is worth the machinery. Vanilla itself crosses into 16 bits in 26.3, where the squeeze disappears |
| 14 | **A crossing widens the existing `Strategy` in place; it never swaps the Level's factory** | Every `PalettedContainer` holds its own `private final Strategy` and consults *that*. A factory swap fixes only containers built afterwards, and turns every local-palette container into a latent failure — the moment one promotes past 8 bits it asks its *old* strategy and builds a 15-bit global storage. One `@Mutable @Accessor` on `globalPaletteBitsInMemory` fixes every container that holds that strategy with a single `int` store, **including the `ProtoChunk`s a worldgen worker is holding right now and that no sweep can reach**. §4.13 |
| 15 | **A block id is pinned, never tombstoned — and the ledger decides that, not the caller** | `SerializableChunkData.parse` calls `.promotePartial(…).getOrThrow(…)`; a section palette is a `ListCodec`, which *drops* an entry it cannot decode and hands back the **shortened** list, and packed chunk data indexes that palette **by position**. One missing block id renumbers every entry after it and silently rewrites the terrain of that whole 16³ section. So `RegistryLedger.tombstone` structurally writes `pinned` for a mod that registered a `minecraft:block`, with no flag and no override — the state is chosen by what was registered, so no caller can get it wrong. §4.14 |
| 16 | **The client half of a crossing is an inline cross-thread `int` write, not a render-thread hop** | The ordering *is* the correctness argument: both sides must be wide before any wide id exists. An `execute(...)` hop puts the client's widen an unbounded number of frames later and reopens exactly the window the ordering closes — a 16-bit section reaching a 15-bit reader, which is a length mismatch and a disconnect, in singleplayer included. Accepted as a limitation rather than hidden as a detail: §9 |
| 17 | **Render layers are not registered, because there is nothing to register** | 26.2 has no `render_type` model key, no `ItemBlockRenderTypes`, no `BlockRenderLayerMap` and no `fabric-blockrenderlayer` in this dependency set. The layer is derived from the texture's own alpha (`BakedQuad$MaterialInfo` → `ChunkSectionLayer.byTransparency`). `LlmSelfTest` asserts all four dead names are absent from every prompt, because a model that sees one anywhere will reach for it |

---

## 1. What changed, by module

Nothing in the v2 module graph moved. V3 added files inside it, and so did V4.

| Module | Added by V3 | Added by V4 Phase 1 |
| --- | --- | --- |
| `platform-api` | `ClassSurgeon` — the SPI: `Result operate(Map<String, byte[]>)` | *nothing* |
| `core` | `compile/SymbolOracle`, `store/ModResources`, `store/PixelGrid`, `store/RegistryLedger`, `runtime/ModContent`, `util/Ids`, `llm/NativeFabricExamples` | `store/BlockSchema`; a third state and a schema payload in `RegistryLedger`; `InstallCard.registeredBlocks`; a `PromptLibrary.parse` rule; the block half of `PlatformProfiles` and the `RubyBlock` few-shot |
| `loader-common` | `surgeon/{BytecodeSurgeon, SurgeonPolicy, Seam}`, `EntrypointAdapter`, `ModAttribution`, `content/{LoaderModContent, ReloadCoordinator}` | *nothing* |
| `fabric` | `FabricSeams`, `FabricEntrypointAdapter`, `shim/{Shims, ClientShims, EventSeam, EventFanout, CommandSeam, ClientSeam, RegistrySeam, RegistryTarget, CreativeTabs}`, `client/FabricClientPacks`, six accessor mixins | `shim/{PaletteGuard, BlockRegistration, StubBlock, StubProperty}`, three accessor mixins (`StrategyAccessor`, `LevelChunkSectionAccessor`, `ChunkMapAccessor`), one `ClientSeam` method and its impl |
| `paper` | *nothing* | *nothing* — the Paper host passes no surgeon, null is a pass-through, and `smoke-paper.sh` is untouched |
| `scripts` | — | `palette-gate.sh`, its own server and its own world |

`loader-common` still names no loader type, and `ClientSeam` still names no client type — which is why the client half of a palette crossing is an `int` in and an `int` out (§4.13, §9).

---

## 2. Verified facts (26.2 / fabric-api 0.158.0)

Everything below was read off the jars with `javap` or produced by a gate. Nothing was recalled.
The corrections matter more than the confirmations: each one is a place where a phase brief, a
model's training data, or **this document's own predecessor** said something that is not true.

### 2.1 Where V3's document was wrong

A facts table earns its keep by being checked. So when a check overturns something the last
edition of this document asserted, that belongs in the table too, first.

| V3 said | The check | What is actually true |
| --- | --- | --- |
| Adding blockstates mid-session "changes the id space under live containers" (Decision 8, §10) | `javap` on `IdMapper` | `add(T)` stores at `nextId++` and nothing renumbers. The id space is **extended**, never remapped, so every id already in a saved chunk still means what it meant. What changes is not the id space; it is whether one particular `Strategy`'s cached bit width is still wide enough |
| "…and does not necessarily throw" (Decision 8, §10) | `javap -c` on `SimpleBitStorage` | It throws. `set` and `getAndSet` both open with `Validate.inclusiveBetween(0L, this.mask, value)`, so an out-of-range id is an immediate `IllegalArgumentException`. This is the sentence the whole phase turned on: a failure that is loud is a failure that can be gated, and the risk of the feature was mostly the belief that it was silent |
| "A block registered at *world-load* time would be a different feature with a different design" (§10) | Phase 1 | The timing was never the problem. Registration mid-session works through V3's existing window unchanged; the design that was needed was for the **boundary**, not for the moment |
| A tombstone is safe because "vanilla drops an unknown item id from a save on load, so the world heals itself" (§4.10) | `javap -c` on `SerializableChunkData` | True for items. **False for blockstates**, and silently so — see the block facts below. This is why `pinned` exists |

### 2.2 Facts carried forward from V3

| Fact | How it was established | Consequence |
| --- | --- | --- |
| `Item.<init>` calls `BuiltInRegistries.ITEM.createIntrusiveHolder(...)` | `javap -c` on `Item` | The unfreeze cannot live at the `Registry.register` call site — Decision 6 |
| `MappedRegistry.freeze()` sets `unregisteredIntrusiveHolders = null` | `javap -c` on `MappedRegistry` | After boot, `createIntrusiveHolder` throws before anything of ours runs |
| `freeze()` throws `Tags already present before freezing` on a second call | Gate | The window's close sets `frozen = true` directly, then does the two things `freeze()` does that a new holder needs: `refreshTagsInHolders()` and a fresh `DataComponentLookup` |
| `Item.Properties.sword(...)` reaches the **BLOCK** registry (`ToolMaterial.applySwordProperties` → `acquireBootstrapRegistrationLookup(BLOCK)` → `validateWrite()`) | Smoke gate, first run | The window has unfrozen `BLOCK` since V3 — which in V3 was not the same as allowing blocks, and in V4 is exactly what made allowing them free |
| `Item.<init>` appends to `BuiltInRegistries.DATA_COMPONENT_INITIALIZERS`, keyed by id, *before* our refusal fires | Smoke gate: nine **V3 Phase 2** assertions failed at once | A refused registration poisoned every later datapack reload with `Missing element`. Fixed with a snapshot/rollback on the window, which now covers refused **blocks** too |
| There is **no `SwordItem` class** | `javap` | `Item.Properties.sword(ToolMaterial.IRON, 4.0F, -2.4F)` is what makes a sword. The prompt says so |
| `setId(...)` must be on `Item.Properties` **before** the item is constructed | `Item.<init>` calls `Properties.itemIdOrThrow()` twice | `Item$Properties.setId` is its own seam; so is `BlockBehaviour$Properties.setId`, for a stronger version of the same reason (§3.5) |
| There is **no `KeyBindingHelper`** and no `…api.client.keybinding.v1` package | `javap` over `fabric-key-mapping-api-v1` 2.0.5 | The class is `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper`, the method is `registerKeyMapping(KeyMapping)KeyMapping`. `LlmSelfTest` asserts the dead name never returns to the prompt |
| There is **no `ItemGroupEvents`** and no `fabric-item-group-api-v1` | `javap` over `fabric-creative-tab-api-v1` 5.0.14 | The class is `net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents`; the method is `modifyOutputEvent(ResourceKey<CreativeModeTab>)` |
| `ResourceLocation` is `net.minecraft.resources.Identifier`; `RecipeHolder.id()` returns `ResourceKey<Recipe<?>>` and its accessor is **`identifier()`**, not `location()` | Found by the Fabric gate's first run, as an ordinary compile diagnostic | The 1.21-era shape in the brief was wrong; the prompt carries the rename table |
| `pack.mcmeta` takes **`min_format`/`max_format`** (each an int *or* a `[major, minor]` list) | `data/minecraft/datapacks/trade_rebalance/pack.mcmeta` + `PackFormat$IntermediaryFormat`'s codec | The numbers are read at runtime off `DetectedVersion.BUILT_IN.packVersion(PackType)`, never from `SharedConstants.DATA_PACK_FORMAT_MAJOR`, which javac would inline and freeze into the jar |
| An **ingredient is a string**, a `#tag`, or an array — never `{"item": …}` | `Ingredient.CODEC`, `javap -c`; and the runtime error from the live demo | A 1.20-era ingredient object is **silently dropped** while the pack loads — the mod loads, reports success, and cannot be crafted. §7.3 |
| An `item_model` component names `assets/<ns>/items/<name>.json`, which points at `assets/<ns>/models/item/<name>.json` | `assets/minecraft/items/apple.json` | A model trained on 1.20 writes **one** file and gets a missing texture. Both few-shots ship both |
| `MinecraftServer.reloadResources(Collection<String>)` managed-blocks when called on the server thread, and `managedBlock` **pumps the task queue** | `javap -c`; and a gate assertion that passed vacuously until it was fixed | "The reload started" is not "the reload finished". Both smoke gates wait for `Server data reloaded in` |
| `reloadResources` writes the pack selection back through `WorldData#setDataConfiguration` | `javap -c` on `lambda$reloadResources$4` | The teardown reload is what makes `level.dat` forget a pack whose folder is gone. The gate reads `level.dat` in both directions |
| `PackRepository.sources` is a `private final Set` assigned from `ImmutableSet.copyOf(varargs)` | `javap` | A pack that did not exist when the client was constructed cannot reach the repository through any public API. Hence one accessor mixin |
| `ReloadableResourceManager` swaps its packs at the **start** of a reload | Client gate: the model and texture resolved, the translation did not | `getResource()` answers from the new list immediately while `LanguageManager` has not run |
| Brigadier's `CommandNode.addChild` **merges** onto an existing child rather than replacing it | Disassembling brigadier 1.3.10 | Node identity is unchanged after a takeover, so "compare identity" detects nothing. The collision guard compares the executor and the child-name set as well |
| `CreativeModeTabs.tryRebuildTabContents` short-circuits on `CACHED_PARAMETERS.needsUpdate(...)`, which compares by **reference identity** (`if_acmpeq`) | `javap -c` | Registering an item changes none of the three things it compares. Clearing the cached parameters is the whole mechanism |
| `EntityRenderers.PROVIDERS` is a mutable `Object2ObjectOpenHashMap` that `EntityRenderDispatcher.onResourceManagerReload` rebuilds from, and that method is public and self-contained | `javap -c` | Late renderer registration works. §4.9 |
| `EntityRendererRegistry` is `@Deprecated` in fabric-rendering-v1 25.3.2 because a transitive access widener makes vanilla's `EntityRenderers.register` public | `javap` + the widener file | Both entry points are seamed |
| A world datapack's pack id is `"file/" + folder` | `FolderRepositorySource`'s string-concat bootstrap | The coordinator resolves it against the repository anyway |
| Since 1.21.2 an empty server **stops ticking** after a minute | `Server empty for 60 seconds, pausing`, in a gate that had been green for a phase | `pause-when-empty-seconds=0` in both loader gates |

### 2.3 Block facts (V4 Phase 1)

| Fact | How it was established | Consequence |
| --- | --- | --- |
| **Blockstate ids are append-only.** `Block.BLOCK_STATE_REGISTRY` is an `IdMapper<BlockState>`; `add(T)` stores at `nextId++` and nothing renumbers | `javap -c` on `IdMapper` | Registering a block cannot invalidate an id already sitting in a chunk. It is also why nothing is reclaimable: `IdMapper` has no `remove`, so `registryBlockStates` is monotonic for the life of the process and says so |
| **A section under 9 bits per entry holds object references.** `LinearPalette` holds a `T[]`; `HashMapPalette` holds a `CrudeIncrementalIntIdentityHashBiMap<T>`. Global ids appear only in `Palette.read/write/getSerializedSize` | `javap` | Registry growth cannot touch a local-palette container at all. That is most of the world, and it needs nothing — no sweep, no repack, no packet |
| **`Strategy.globalPaletteBitsInMemory` is `Mth.ceillog2(idMap.size())`, computed once in `<init>`**, and `getConfigurationForBitCount(n)` hands it to `Configuration$Global` for every `n > 8`. The `globalMap` reference beside it is live | `javap` on `Strategy` | The registry is seen immediately; the width is not. This one `int` is the entire hazard, and the entire fix |
| **Crossing a power of two throws loudly.** `SimpleBitStorage.set` and `getAndSet` both open with `Validate.inclusiveBetween(0L, this.mask, value)` | `javap -c` | Gateable rather than corrupting. V3's "does not necessarily throw" was the part that was wrong, and it is the reason this feature could ship |
| **The bit width is a field on one `Strategy` object per level**, and every `PalettedContainer` holds its own reference to that object rather than consulting `Level.palettedContainerFactory` | `javap` | Mutating it in place fixes every container that holds it — including `ProtoChunk`s a worldgen worker is building right now, which no sweep can reach. Decision 14 |
| **26.2 has ~32,366 blockstates against a 32,768 ceiling: about 402 spare.** 1.21.8 had 4,822 and 26.3 has 29,813, because vanilla itself crosses into 16 bits there | Computed as `Σ over blocks (Π property value-set sizes)` from `misode/mcmeta`, method validated against the published 1.21.8 figure (27,946); then confirmed at runtime by `PaletteGuard.probe()`, which the smoke gate asserts is self-consistent | The crossing is a real path, five stairs blocks away, not a theoretical one. The probe replaces the computed number with ground truth at every boot, and the smoke gate says so out loud when a version bump moves it |
| **Widening must be two-sided, even in singleplayer.** `Connection.configureInMemoryPipeline` delegates to `configureSerialization(…, memoryConnection=true, …)`, and `PalettedContainer.read` sizes its long array from the *receiving* container's own strategy. `ClientLevel` builds its own factory | `javap -c` | A disagreement is a decoder exception and a dropped world, not a singleplayer freebie. Hence `ClientSeam.widenBlockStatePalette` and Decision 16 |
| **The re-encode primitives are public**: `PalettedContainer.pack(Strategy)` → `PackedData`, static `unpack(Strategy, PackedData)` → `DataResult`. `Configuration$Global.alwaysRepack()` always returns true | `javap` | `unpack` genuinely rebuilds the bit storage at the strategy's *current* width instead of reusing the old longs — which is also why everything on disk needs nothing. `recreate()` reuses the *same* strategy and is **not** the rebuild primitive |
| **`LevelChunkSection.states` has no setter**, and `ChunkMap`'s three chunk maps are private | `javap` | Two `@Mutable`/`@Accessor` mixins. `forEachBlockTickingChunk` is the tempting public alternative and it is a hole: a loaded-but-not-ticking border chunk skipped by the sweep keeps a container on the old width |
| **A missing block id does not become air — it shifts the palette.** `SerializableChunkData.parse` calls `.promotePartial(…).getOrThrow(…)`; the section palette is a `ListCodec`, which drops elements that fail to decode and returns the *shortened* list as its partial value; packed data indexes that palette **by position** | `javap -c` | One missing id renumbers every entry after it: stone becomes dirt, dirt becomes gravel, for that whole 16³ section, with one recoverable-error line in the log. **A block id can never be tombstoned.** Decision 15, §4.14 |
| **Runtime block registration otherwise mirrors items exactly.** `BlockBehaviour$Properties.setId` exists like `Item.Properties.setId`; `Block.<init>` uses `createIntrusiveHolder` just as `Item.<init>` does; `StateDefinition.getPossibleStates()` and `BlockStateBase.initCache()` are public | `javap` | V3's registration window needed **no change at all** to carry blocks. Everything Phase 1 adds is on the far side of the registration |
| **`Blocks.<clinit>`'s tail is two calls per state**: `Block.BLOCK_STATE_REGISTRY.add(state)` then `state.initCache()` | `javap -c` on `Blocks`, the last 70 bytes | Generated code never runs it. Without the first the block has no blockstate ids and cannot be placed; without `initCache()` it can be placed once and then the light engine dereferences a null cache |
| **`Items.registerItem` puts a `BlockItem` into `Item.BY_BLOCK` via `blockItem.registerBlocks(map, item)`** before it registers anything, and `BY_BLOCK` is a plain `Maps.newHashMap()` | `javap -c` on `Items` and `Item.<clinit>` | Generated code calls `Registry.register` directly and never reaches that private helper. Without the link `Block.asItem()` falls through to air and pick-block, `getCloneItemStack` and every block-derived recipe hand back nothing — with nothing thrown. Being a plain `HashMap` is also what makes it the one part of a block registration that is genuinely revocable |
| **`StateDefinition.propertiesByName` is an `ImmutableSortedMap`** and `Builder.properties` is a plain `HashMap`; `NAME_PATTERN` is `^[a-z0-9_]+$` | `javap` | A rebuilt state definition is a pure function of the properties **sorted by name**, so a stub needs no record of the order they were added in. Value order within one property *is* recorded, because `getPossibleValues()` order is the property's own and nothing sorts it |
| **`Property` has a `protected Property(String, Class<T>)` constructor** and exactly four abstract methods — `getPossibleValues`, `getName(T)`, `getValue(String)`, `getInternalIndex(T)`. `Property.equals` compares `clazz` and `name` only | `javap` | A property can be rebuilt from nothing but recorded strings (§4.14). The `equals` detail is why `StubProperty` overrides it: every stub shares one `clazz`, so two unrelated properties both called `facing` would otherwise collide in any map keyed by property. `EnumProperty` overrides it for the same reason |
| **`BlockBehaviour.<init>` reads the id twice** — once for the `descriptionId` that becomes the lang key, once for the `drops` key that becomes `data/<ns>/loot_table/blocks/<path>.json` | `javap -c` | Rewriting the namespace at `Registry.register` time would leave a block whose name renders as `block.whatever.thing` and which drops nothing, with no error anywhere to say why. Hence a second `setId` seam |
| **Render layers need no registration.** There is no `ItemBlockRenderTypes`, no `render_type` model key and no `fabric-blockrenderlayer` in this dependency set; the layer comes from the texture's own alpha (`BakedQuad$MaterialInfo` → `ChunkSectionLayer.byTransparency`) | `javap` over the 26.2 jar and the dependency set | The whole client story for a block is the existing Phase-2 respack channel plus one int-only `ClientSeam` method. Sounds and break particles likewise need nothing: `SoundType` is server-side, and particles come from the model's `particle` texture reference |
| **`BlockModelShaper` has a missing-model fallback where `EntityRenderDispatcher` had a null** | `javap` | A block registered after the last model bake renders as the missing model for the ~2 s until the coordinator's debounced client reload rebakes it. Visible and harmless, and there is no analogue of the entity-renderer NPE that V3's client gate found |

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

Eighteen call sites. `prependingReceiver` derives the shim descriptor from the original by
prepending the receiver; `staticCall` keeps the descriptor and moves only the owner. Both shapes
are defined, inspected **and run** by `:fabric:surgeonSelfTest`.

| # | Intercepted call site | Descriptor | Shim | Phase |
| --- | --- | --- | --- | --- |
| 1 | `Event.register` | `(Ljava/lang/Object;)V` | `Shims.eventRegister` | V3.0 |
| 2 | `Event.register` | `(Identifier, Object)V` | `Shims.eventRegister` | V3.0 |
| 3 | `KeyMappingHelper.registerKeyMapping` | `(KeyMapping)KeyMapping` | `ClientShims.registerKeyMapping` | V3.1 |
| 4 | `HudElementRegistry.addFirst` | `(Identifier, HudElement)V` | `ClientShims.hudAdd` | V3.1 |
| 5 | `HudElementRegistry.addLast` | `(Identifier, HudElement)V` | `ClientShims.hudAdd` | V3.1 |
| 6 | `HudElementRegistry.attachElementBefore` | `(Identifier, Identifier, HudElement)V` | `ClientShims.hudAttach` | V3.1 |
| 7 | `HudElementRegistry.attachElementAfter` | `(Identifier, Identifier, HudElement)V` | `ClientShims.hudAttach` | V3.1 |
| 8 | `Registry.register` | `(Registry, String, Object)Object` | `Shims.registryRegister` | V3.3 |
| 9 | `Registry.register` | `(Registry, Identifier, Object)Object` | `Shims.registryRegister` | V3.3 |
| 10 | `Registry.register` | `(Registry, ResourceKey, Object)Object` | `Shims.registryRegister` | V3.3 |
| 11 | `Registry.registerForHolder` | `(Registry, ResourceKey, Object)Holder$Reference` | `Shims.registryRegisterForHolder` | V3.3 |
| 12 | `Registry.registerForHolder` | `(Registry, Identifier, Object)Holder$Reference` | `Shims.registryRegisterForHolder` | V3.3 |
| 13 | `Item$Properties.setId` | `(ResourceKey)Item$Properties` | `Shims.itemId` | V3.3 |
| 14 | **`BlockBehaviour$Properties.setId`** | `(ResourceKey)BlockBehaviour$Properties` | `Shims.blockId` | **V4.1** |
| 15 | `EntityType$Builder.build` | `(ResourceKey)EntityType` | `Shims.entityTypeBuild` | V3.3 |
| 16 | `FabricDefaultAttributeRegistry.register` | `(EntityType, AttributeSupplier$Builder)V` | `Shims.defaultAttributes` | V3.3 |
| 17 | `FabricDefaultAttributeRegistry.register` | `(EntityType, AttributeSupplier)V` | `Shims.defaultAttributes` | V3.3 |
| 18 | `EntityRendererRegistry.register` / `EntityRenderers.register` | `(EntityType, EntityRendererProvider)V` | `ClientShims.entityRenderer` | V3.3 |

Two pairs of rows share a parameter list and are told apart only by the rest of the descriptor.
The `ResourceKey` overloads of `Registry.register`/`registerForHolder` (10 and 11) differ only in
**return type**; rows 13 and 14 differ in **owner and return type** and in nothing else, because
`Item$Properties.setId` and `BlockBehaviour$Properties.setId` both take one `ResourceKey`. That is
why the seam matches on owner, name **and the full descriptor** rather than on name and arity, and
`SurgeonSelfTest` has a fixture that asserts exactly that pair is not confused.

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
`onInitialize()`. It unfreezes `ITEM`, `ENTITY_TYPE` and `BLOCK`.

V3 unfroze `BLOCK` for a reason that had nothing to do with blocks: `Item.Properties.sword(...)`
reads the frozen block registry three frames before it touches the item registry, and what the
window bought was that the refusal was ours and legible instead of a vanilla stack trace from
inside a builder. **V4 Phase 1 changed nothing here.** `Block.<init>` takes an intrusive holder
exactly as `Item.<init>` does, so the window that already had to be open for a sword was already
the right window for a block. All Phase 1 did was move `Registries.BLOCK` from the refusal list
into `SUPPORTED`; everything else it added is on the far side of the registration (§4.13, §4.14).

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
and restores it, loudly, discarding the orphaned object. A refused *block* leaves the same kind of
orphan and takes the same path — the smoke gate asserts the discard line names
`minecraft:block`, not just "registry content".

Only `ITEM`, `BLOCK` and `ENTITY_TYPE` may be written. Every other registry gets a diagnostic
naming what *is* supported. Ids are rewritten to `vibemod_<modname>` — the same canonical
namespace the resource tree uses — so the recipe, the model path, the lang key and the loot-table
path agree by construction rather than by the model's care.

### 4.10 The registry ledger

`<datadir>/registry-ledger.json`, atomic write, per **installation** rather than per world. Three
states, not two:

- **live** — the mod is in the store and its ids are registered again, identically, on the next
  boot. Deterministic because the id derives from the mod's own name and the path it asked for.
- **tombstone** — the mod was unloaded and registered no block. Its ids are never registered
  again. Vanilla drops an unknown *item* id from a save on load, so the world heals itself.
- **pinned** — the mod was unloaded and had registered at least one `minecraft:block`. Its ids are
  never reused *and never released*: on every subsequent boot the host registers an inert stub
  under each one. §4.14.

`isTombstoned` is true for both of the last two, because the re-registration guard's question —
"may this mod's ids be handed back to a mod of the same name?" — has the same answer either way.
`isPinned` is the separate question of whether the ids also have to be registered *back in* on the
way up. `describeState()` counts them apart (`ledgerTombstones`, `ledgerPinned`) because they are
not the same fact and the gates match on full prefixes of that string.

A block entry also carries a `BlockSchema` payload, recorded at **registration** time — the only
moment the live `StateDefinition` exists to read it off. By the time the mod is deleted, the block
object is the only copy of its own schema and it is about to stop being constructed.

### 4.11 Resources

`data/**` is materialized as `<world>/datapacks/vibemod-<mod>/`, **staged and renamed** — a
half-written directory that vanilla's folder source discovers mid-write is a pack that fails to
load, during the reload the player is watching. `assets/**` joins one runtime client resource
pack (`required=true`, `Pack.Position.TOP`, `fixedPosition`, so vanilla's `rebuildSelected`
re-inserts it after every reload with nothing remembered in `options.txt`).

Blocks add one directory to that channel — `assets/<ns>/blockstates/` — and nothing else. A block
needs nine files, three of which a model trained on 1.20 leaves out: the blockstate file, the loot
table, and the `mineable/pickaxe` tag. The first of those is invisible until somebody places one,
so `PromptLibrary.parse` refuses a project that registers into `BuiltInRegistries.BLOCK` and ships
no `assets/<ns>/blockstates/*.json`. That is a self-heal round with the model's own text in it
rather than a mod that installs cleanly and looks like a missing-model bug.

The installer hooks the **lifecycle**, not the compile path: `/vibe enable` on an already-compiled
mod goes straight to `lifecycle.enable(...)` and never touches the store, so a content installer
on the compile path would materialize on first load and silently skip it on every re-enable
afterwards. It runs **after** the mod's entrypoint, and `Kind.CONTENT` drains **last** — its
close only marks a reload pending, and everything else must be gone before that reload runs.

Namespaces are canonicalized to `vibemod_<modname sanitized>` in the path *and* in every
`"<ns>:…"` id inside the bodies. `minecraft:` ids are never touched. **Dotted forms are
deliberately not rewritten**: `item.myns.ruby` in a lang file is a translation key, a mod's own
Java may name one, and Java sources are not rewritten — so rewriting the lang file alone would
break exactly the pairing it was meant to protect.

Textures are `.png.grid` — a JSON palette plus rows — encoded to RGBA8 PNG by ~60 lines of
`Deflater` + `CRC32`, no `java.desktop`. The store self-test walks the output's chunk list and
verifies every CRC the way a decoder would.

### 4.12 The reload coordinator

A dirty mark arms a 40-tick timer; every further mark re-arms it; when it expires exactly one
reload runs. A side already reloading never starts a second — it re-arms, so a change landing
mid-reload is not lost. Ticked from the host's existing `END_SERVER_TICK` subscription, so it
inherits that subscription's "null between worlds" lifetime and needs no loader event of its own.

The palette guard's straggler watch (§4.13) rides the same subscription for the same reason, and
borrows `DEBOUNCE_TICKS` rather than choosing a number, because it is the same two seconds and the
same question: how long does a burst of work take to settle before it is safe to stop looking.

### 4.13 The palette guard

`PaletteGuard` is per-JVM, not per-server, because the thing it guards is: the blockstate id space
is appended to from the registration window and never reclaimed, world or no world.

**Probe.** One line at server start: the live `BLOCK_STATE_REGISTRY.size()`, the derived width,
and the headroom to the next power of two. It exists because the 402 figure this design was
calibrated against was computed from data dumps, and a number computed from a data dump is a
claim. The smoke gate asserts the three numbers are self-consistent (`states + budget == 2^bits`)
and *notes without failing* when the state count is not 26.2's 32,366 — a version bump that moves
it must be visible without being a build break, because the budget is read live.

**Budget.** `block.getStateDefinition().getPossibleStates().size()` is the cost. Under the budget,
`admit` does nothing whatsoever — no sweep, no repack, no packets — and warns only when fewer than
64 states are left, because the next block that does not fit is the expensive one. Above 4096
states for one block it refuses by count: vanilla's widest block is the wall at 324, so a number
that large is a bug in the block's property set, not a budget question.

`admit` is called on the **near side** of `MappedRegistry.register`. A refusal there leaves a
constructed-but-unregistered intrusive holder, which the window's close already discards loudly.
The other order would leave a live block id whose states never reached `BLOCK_STATE_REGISTRY` — an
id the registry knows about and no chunk can ever hold.

**The crossing**, whose order is the correctness argument and not a preference:

1. Widen every `ServerLevel`'s block-state `Strategy`, in place (Decision 14).
2. Widen the **client** level's strategy, through `ClientSeam.widenBlockStatePalette(int)` —
   inline, on the server thread (Decision 16, §9).
3. Repack the sections that are *already* global: `bitsPerEntry() > 8 && != newBits`, via
   `pack(strategy)` → `unpack(strategy, …)`, swapping the container into the existing section
   so section identity, the biome container and the four block-count shorts all survive. Building
   a replacement `LevelChunkSection` would compile and would be wrong in three ways.
4. Resend: drop then mark, per tracked chunk per player — the pair `ChunkMap` itself uses when a
   player's tracking view changes, because the forget packet is what makes the client discard the
   section it decoded at the old width instead of merging into it.

Only then does the caller append the new states. Both sides are wide **before the first wide id
exists**, so there is no window in which a 16-bit section can reach a 15-bit reader.

The sweep reads `ChunkMap`'s `updatingChunkMap` and `pendingUnloads` through an accessor rather
than using `forEachBlockTickingChunk`, which is the tempting public alternative and is a hole: a
loaded-but-not-ticking border chunk it skips keeps a container on the old width, and the next
block written into it throws. Results are deduplicated by identity, because a `ChunkHolder` in
`pendingUnloads` and an `ImposterProtoChunk` beside its `LevelChunk` can both be reachable, and
the count is what the gates assert on.

**What deliberately needs nothing:** local-palette sections, unloaded chunks, and everything on
disk. A saved section records `bitsInStorage`, which is derived from palette size and is
width-independent, and `Configuration$Global.alwaysRepack()` is always true, so `unpack` rebuilds
at the current width on load. Even an unswept container still *saves* correctly; staleness bites
only on a live write or a live send, and the crossing closes both.

**Refusals.** A crossing needs a running server to do it to, so it is refused with the arithmetic
in the message when there is none. It is also refused when more than one player is connected: a
remote client builds its own `Strategy` from its own `BLOCK_STATE_REGISTRY`, which does not have
these states, so widening ours would make every chunk packet we send undecodable to it.

**The straggler watch.** Step 3 can only see the chunks `ChunkMap` holds. A `ProtoChunk` a
worldgen worker had *already* promoted past 8 bits is not one of them — widening the strategy
fixes every *future* promotion, which is the whole point of Decision 14, but a container that
promoted before the crossing still holds a `SimpleBitStorage` sized at the old width. It is
invisible only for as long as the worker owns it. So `tick()` re-runs the sweep predicate for 40
ticks after a crossing and repacks whatever surfaces, naming each one, counted separately from the
crossing's own repacks because the two answer different questions and the watch's counter is the
only evidence the gap is real. Disarmed — every tick of a normal server's life — it is one
volatile read.

**Threading:** server thread only, inside the registration window, where chunk ticking is not
concurrent. `PalettedContainer.pack` goes through the container's own `ThreadingDetector`, so a
worker that touches one mid-sweep trips vanilla's detector rather than corrupting quietly.

### 4.14 Pins and stubs

Disabling a block mod is the easy half: the block object stays in the registry, placed blocks stay
standing and breakable, and only the mod's behaviour is drained. **Deleting is the dangerous one.**
If the id is simply gone at the next boot, every saved section containing it loses one palette
entry and renumbers the rest.

So a deleted block mod is `pinned`, and the host owes those ids a replacement. A stub must
reproduce the **exact state schema**, because a save records a state by property names and value
strings (`{"Name":"…","Properties":{"lit":"true"}}`) and the stub must decode to the same state
index. Three things are recorded and only three: the id; every property's name and its possible
values *as the strings the save codec writes* (`Property.getName(T)`, never `toString()` — for an
enum property those differ the moment a mod's `StringRepresentable` name is not its Java constant
name); and the total state count, as a checksum. Reconstruction order needs no recording, because
`StateDefinition.propertiesByName` is an `ImmutableSortedMap`.

`StubProperty` is what makes it possible. Vanilla has no property class constructible from a
recorded value set: `EnumProperty` needs a live `Class<T extends Enum<T>>`, and the enum a deleted
mod named its values through went away with the mod. What survives is a list of strings, so the
stub's property is a property **of strings** — four abstract methods, each answered directly by
that list, and the codecs `Property.<init>` builds are lambdas over those four, which is what makes
the save *round-trip* work rather than merely the state count matching.

`StubBlock` reads its schema through a `ThreadLocal` because `javap` leaves no alternative:
`createBlockStateDefinition` is called from `Block.<init>`, before any subclass field has been
assigned. Vanilla's blocks read static constants there; a stub's properties differ per instance and
are known only to the caller. The handoff is set and cleared around one constructor call on one
thread — the server thread, inside the registration window, where block construction is
single-threaded anyway.

It refuses **twice**, and both refusals are the house rule (Decision 10). Before construction, on
`BlockSchema.problems()` — a name the game's own `^[a-z0-9_]+$` rejects, or a single-valued
property, would otherwise throw from inside the constructor of an object that has already taken an
intrusive holder. And after construction, on the state count, which is the only check that catches
a schema that is internally consistent and still not the original's. **A wrong stub is worse than a
missing one:** a missing id is one loud decode error per section, and a wrong one decodes a saved
property to a different state index, quietly, forever.

The stub is solid, stone-sounding, breakable, drops nothing, and renders as the missing model,
which is the honest presentation — *something was here and its mod is gone*. Its description id is
its own id plus a plain-English tail, because there is no lang file for a deleted mod and a client
renders an unknown translation key verbatim, so the key may as well say something true.

`/vibe delete` says all of this **before the click**, not after: the confirmation screen lists the
block ids, and the console path — which never sees a screen — gets the same sentence as a warning.
The wording is deliberate: *its block ids stay claimed forever and come back as inert stubs on
every restart, because releasing them would corrupt the chunks they sit in.*

The replay itself is `RegistrySeam.replayPinnedBlocks`, and three things about where it is meant to
be called from are load-bearing: **inside the registration window** (a stub is constructed exactly
the way a mod's block is, so it needs exactly the same window, and reusing `withWindow` means the
close does the tag refresh and the component rebuild for stubs too); **before any live mod is
restored**, so pinned ids are minted in first-assigned order, which is ledger order and not disk
order; and **through `BlockRegistration.admit`**, the same guard a live block takes, because a
stub's states are real states and cost real budget. Failures are per-id and loud rather than fatal:
one unbuildable stub is one id whose chunks will shift, while a throw out of here is a server that
will not start, which repairs nothing and loses the other pins too.

**It is not yet wired.** Nothing calls it. §6.13 and §9.

### 4.15 The client, for blocks

Nothing new is needed, and that is a verified result rather than an assumption (§2.3). Blockstate
JSON, block models, textures, item model and lang all flow through the existing Phase-2 respack
channel; only the `blockstates/` directory is new to it. Render layers, sounds and break particles
each need no registration at all — the first because 26.2 has no such API, the second because
`SoundType` is server-side, the third because particles come from the model's `particle` texture
reference (so the prompt tells the model to parent to `cube_all`, which is what declares
`"particle": "#all"`).

The only new client-facing entry point in the whole phase is `ClientSeam.widenBlockStatePalette` —
a *host* seam, not a mod-facing one, and ints only, because `ClientSeam` is held by classes a
dedicated server loads. It returns the width the client level is on afterwards, or `-1` when there
is no level, which is the normal answer at the main menu and the reason it returns a width rather
than nothing.

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
| Registered **block id** | **Stays in the registry** | Stays; ledger writes a **pin**, never a tombstone | A tombstoned block id is an absent palette entry, and an absent palette entry shifts every entry after it. The id is claimed for the life of the installation and is meant to come back as an inert `StubBlock` on every subsequent boot — Decision 15, §4.14 |
| **Blockstate ids** appended to `BLOCK_STATE_REGISTRY` | Stay | Stay | `IdMapper` has no `remove`. `registryBlocks` comes back down when a mod is disabled and `registryBlockStates` does not, and that asymmetry is the truth: a counter that fell would be lying about the one number the palette budget is computed from |
| The **global palette width** after a crossing | Unchanged | Unchanged | It only ever grows, for the life of the process. Narrowing it again would invalidate every container that had already been widened |
| **Blocks the mod placed in the world** | Remain, standing and breakable | Remain | The same shape as an item in a player's inventory: the object outlives the mod. Player builds are never swept. Once the mod's resource pack is gone they render as the missing model, which is the honest presentation |
| The **`Item.BY_BLOCK` entry** for a `BlockItem` | **Removed**, by value identity | same | The one part of a block registration that really is revocable — `BY_BLOCK` is a plain `HashMap`. Removed by value rather than by block, because a `BlockItem` subclass may stand for several blocks (vanilla's own `DoubleHighBlockItem` does) and only the item knows which; the only thing certain from outside is that every entry pointing at *this* item was one of them |
| An item's own `use()` behaviour | **Still runs** | Still runs | The game calls `Item.use` on the object in the registry directly; there is no host frame in between. The gate asserts this *positively*, so a future change says so instead of going quiet |
| Creative-tab entry | Removed, and a rebuild is queued onto the render thread | same | Queued, not synchronous: a rebuild walks every item in the game and a whole teardown gets 250ms |
| Entity default attributes | Supplier removed from `DefaultAttributes.SUPPLIERS` | same | A **live entity already has its attribute map**; removal affects the next spawn |
| Entity renderer | Provider **replaced** with vanilla's `NoopRenderer`, not removed | same | A disabled mod's entities are still in the world. An invisible mob is a bug report; a crashed client is a lost world |
| Items already in a player's inventory | Remain, holdable, usable | Remain | Same shape as everything else here: the object outlives the mod |

The through-line: **VibeMod can revoke every path it stands in, and no path it does not.** Blocks
are the sharpest case of that yet — the id is not merely unrevocable, it is unrevocable *forever
and on purpose*, and the delete confirmation says so before the click.

---

## 6. What the gates found

Twelve real bugs, none of them found by reading. This section exists because it is the argument
for gates that **grow** rather than get replaced.

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
   string that never appeared.
4. **`ReloadableResourceManager` swaps packs at the start of a reload.** The client gate asserted
   a model, a texture and a translation in one breath; the first two resolved and the third did
   not, because `LanguageManager` had not run yet.
5. **Deleting a pack file out from under a running reload threw.** The end state self-corrected,
   but a stack trace nobody can act on is not an acceptable way to get there.
6. **`Item.Properties.sword(...)` needs the BLOCK registry open.** Found by the dedicated-server
   smoke gate on its first run — building *any tool item* touches the frozen block registry three
   frames before it touches the item registry. In hindsight this is also the fact that made V4
   Phase 1 cheap.
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

And one V4 Phase 1 found by **reading**, which is exactly the point:

13. **`replayPinnedBlocks` was written before anything called it.** For a while the pin had only
    its first half: a deleted block mod was recorded as `pinned` rather than tombstoned, with a
    usable schema per id, and `/vibe delete` promised the ids would come back — while the replay
    that keeps that promise was invoked from nowhere, and `InstallCard.setRegisteredBlocks` was
    installed only by `StoreSelfTest`. Both are now wired, beside `setLedger`, before
    `restoreModsFromDisk()`.

    The part worth keeping is **why no gate caught it**, because that has not changed: no gate
    covers the pin. The smoke gate runs on a dedicated server, where registry content is refused
    before a block ever exists, and `palette-gate.sh` bypasses the registry seam on purpose. A
    hole a reader found and no gate would have is still a hole. §9.

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
branches and left the model to guess which one applied, so it guessed, was refused, and spent a
full repair round rediscovering something the host had known since boot.

The fix is a **`THIS HOST` block** in the system prompt: `PromptLibrary.systemPrompt(profile,
hostFacts)`, fed by `ModGenerator.setHostFacts(...)`, supplied by the Fabric host from
`platform.isDedicatedServer()`. It goes in the system prompt rather than the request because it
is constant for the life of a host, which is what keeps it cacheable. `null` or blank reproduces
the old prompt **byte for byte** — asserted for every profile.

On the next run the same prompt produced a working mod in **one round, no repair**.

### 7.2 The prompt pointed at an example that no longer existed

V3 Phase 3 replaced the `RubyCharm` few-shot with `RubySword`, and one sentence still read *"The
RubyCharm example below shows the whole shape"*. Asserted now: the prompt names an example it
actually ships.

### 7.3 A recipe that failed silently

The model wrote a smelting recipe with `"ingredient": {"item": "minecraft:redstone"}` — correct
for 1.20, rejected by 26.2 — and got the *shaped* recipe right, because the few-shot shows one.
The mod loaded, the card said installed, and the recipe simply was not there. The prompt now
states the general rule (an ingredient is a string, a `#tag`, or an array — never an object),
names the recipe types the few-shot does not show, and says out loud that a bad one **does not
fail your build**.

This is the one place VibeMod knowingly breaks the "no silent drops" rule, and it is recorded as
such in §9 rather than smoothed over: the host does not surface vanilla's datapack parse errors.

### 7.4 The demo tore down inside the debounce window

The harness deleted a mod one second after materializing its datapack — well inside the 40-tick
window — and the coordinator coalesced the load and the unload into a single reload that
correctly reported the mod as *unloaded*. Nothing was broken except the harness, which was faster
than any player could be. Recorded because it is the coalescing path working, tested by accident.

### 7.5 The prompt, after blocks

The native Fabric profile now carries **four** few-shots. `RubyBlock` is the new one, and it is
there because a block's file set is the one thing a model cannot infer from the item one: it is
the plainest possible block — a cube with no properties, so it costs exactly one of the ~402
remaining states — and it ships all nine files, including the blockstate JSON, the loot table and
the `mineable/pickaxe` tag that a 1.20-trained model leaves out.

The prose half teaches three things the model cannot look up:

- **Budget your blockstates.** A block costs the *product* of its property value counts. The
  prompt gives the units a model can act on — no properties costs 1, one boolean 2, a fence 32, a
  door 64, stairs 80, a wall 324 — and says that past the budget the host refuses and the message
  says how many were left.
- **Never register a render layer**, and never put a `render_type` key in a block model. 26.x has
  neither. `LlmSelfTest` asserts all four dead names are absent from every prompt.
- **`setId(...)` before construction here too**, because the `Block` constructor bakes the
  description id *and* the loot-table path out of it.

The V3 rule — *the next thing added to this prompt has to take something out* — is not what
happened here; a whole few-shot was added and the ceiling (`NATIVE_FABRIC_BUDGET`, 40,000 chars)
absorbed it. That is a deliberate call and it is recorded as one rather than presented as
discipline. `LlmSelfTest` still asserts the number **as sent** (profile + host facts), which is the
only number a generation actually pays for, and prints the live figures for every profile rather
than having them transcribed into a document that would go stale.

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
| javac | `method Owner.x(…) is not applicable` | **signature** |
| ECJ | `The method x(…) in the type Y is not applicable` | **signature** |

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

**Open, from V4 Phase 1 — these are work, not decisions:**

- **The pinned-block replay is wired but ungated.** `replayPinnedBlocks` and
  `InstallCard.setRegisteredBlocks` now sit beside `setLedger` in `VibeModFabric`, before
  `restoreModsFromDisk()`, so pinned ids are claimed before any live mod registers anything. What
  is still owed is the test: a gate that places a block, deletes the mod, restarts, and asserts a
  known vanilla block **two blocks away is unchanged**. That neighbour assertion is the shift
  detector, and it is the one test that would protect a player's world. Until it exists the pin is
  believed rather than proven. §6.13.
- **No gate proves a block registers, places, breaks and drops through the real seam path.** The
  smoke gate runs on a dedicated server, where registry content is refused, so its block coverage
  is entirely the refusal path. `palette-gate.sh` proves the crossing against real chunks but its
  canary bypasses the registry seam deliberately. The client gate has no block assertions at all.
- **The client half of a crossing is not gated.** `Shims.clientSeam()` is null on a dedicated
  server and `level.players()` is empty, so steps 2 and 4 of the crossing are no-ops in
  `palette-gate.sh`, which says so in its own header rather than claiming coverage it lacks.
  Proving it needs `:fabric:runClientGameTest` and a display.

**Accepted, with reasons:**

- **The cross-thread write to the client's palette strategy is a plain aligned `int` store.** It
  happens on the server thread and is read on the render thread, with the netty pipeline supplying
  the happens-before edge for the packet that depends on it. The alternative — hopping to the
  render thread — was rejected because it would put the client's widen an unbounded number of
  frames after the server's and reopen the very window the ordering exists to close. The write is
  safe to be direct because the field only ever grows and every reader recomputes from it on the
  next container operation, so a racing reader sees either the old width or the new one and no id
  needing the new one exists yet in either case. This is the assumption to revisit if anything is
  ever *narrowed*.
- **The worker-thread `ProtoChunk` residue.** A chunk a worldgen worker had already promoted past
  8 bits before a crossing is not reachable from `ChunkMap` and is not swept. It is covered by a
  40-tick watch that repacks it the moment the worker hands it back, and every catch is named. The
  residue is the interval itself: a worker that holds one for longer than the watch, and writes a
  wide id into it, gets a loud `IllegalArgumentException` from `SimpleBitStorage`. Loud, not
  silent, which is the whole reason this was shippable.
- **The LAN residue.** A crossing is refused outright with more than one player connected, because
  a remote client's `BLOCK_STATE_REGISTRY` is smaller than ours. Below the boundary nothing is
  refused — and a remote LAN client still does not have the block, so a chunk carrying its global
  blockstate id is not something that client can render correctly. Registered content has been
  singleplayer/LAN-*host* only since V3; blocks make the "host" in that phrase load-bearing rather
  than incidental. The real answer is the vanilla-client projection phase, not this one. §10.
- **The boolean-merge default is `TRUE`.** Every Fabric boolean event checked reads `true` as
  "allow", and that is what `LoaderEventBridge.every()` has always done. Nothing in reach reads it
  as "handled"; this is the assumption to revisit when the surface widens.
- **The command seam holds one `CommandBuildContext` for the life of a server**, refreshed on
  every reload. A per-invocation context would be the thing to build if that ever matters.
- **`ClientSeam` must stay free of client types.** It is held by two classes that load on
  dedicated servers. Adding a `KeyMapping` — or a `Strategy` — to it would not fail here; it would
  fail on somebody's server, at class-load time, with a `NoClassDefFoundError` nothing in this
  repo would catch. That is why the palette widen is an `int` in and an `int` out.
- **The registration window is a shared-JVM race, and it is not gated.** It unfreezes three
  registries on the server thread while the render thread may be iterating them; in singleplayer
  those are the same objects. Nothing has been observed, mods load one at a time and the window is
  microseconds wide.
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
  dropped by the game's own loader, on a worker thread, with no return channel. The prompt now
  prevents the common case; the gap is real and this is where it is written down.
- **The player-facing mod hub shows no live introspection.** `/vibe info` for a *player* opens
  `HubScreens.modHub`, which renders store data only; the verified facts are one click away under
  "Manual".
- **`ctx.onChat` is still ungated on Fabric.** Producing a player chat line over RCON is not
  something the harness can do.
- **`InstallCard`'s two lookups are static hooks**, so Paper and every self-test see the card they
  always saw. If a second host ever wants them, that is the seam.

---

## 10. Out of scope — do not "helpfully" add these

Each of these was considered, and each has a reason that is a fact about the game rather than a
matter of taste. Reversing one means answering the fact — which is what V4 Phase 1 did to the row
that used to be first in this table, and the shape of that answer is in Decision 8 and §2.1.

| Not shipped | Why not |
| --- | --- |
| **Blocks on a dedicated server, or to a remote LAN client** | Chunk packets carry **global** blockstate ids, and a joining client builds its `BLOCK_STATE_REGISTRY` — and the palette width derived from it — from its own registry, which is smaller than ours. `PalettedContainer.read` sizes its long array from the receiving container's own strategy, so the two do not merely disagree about what a block is, they disagree about how many bits one takes. Fabric's registry sync remaps *raw registry ids* and does nothing for this. The answer is the vanilla-client projection lane — a per-connection re-encode of global-palette sections at the client's own width, borrowing from a finite pool of vanilla states — which is a phase, not a patch. Until then a crossing is refused with more than one player connected, and blocks are singleplayer/LAN-host only along with the rest of registry content |
| **Any registry other than `ITEM`, `BLOCK` and `ENTITY_TYPE`** | Block entities, enchantments, biomes, particles, sounds: each has its own baked-at-load story and none has been established the way these three were. Refused with a diagnostic naming what is supported |
| **Registry content on a dedicated server** | Registry sync. A vanilla client joining later would be kicked. The answer there is components on a vanilla item in the recipe result, which the prompt teaches |
| **A pack server for `assets/**` on dedicated** | Needs a hosted URL and a hash — a hosting feature, not a mod-loading one |
| **NeoForge seams** | The whole seam table is Fabric-module. NeoForge keeps the v2 `VibeContext` path plus the loader-neutral datapack channel, which its gate proves end to end. Adding seams there means a second table, a second shim set and a second policy, for a host whose users are already served |
| **Mixins in generated code** | `org/spongepowered/` is denied. A mixin is applied at class-load time to *somebody else's* class and cannot be undone; it is the exact opposite of the property this design exists to preserve. The host's own nine accessor mixins are host code, and three of them exist only because 26.2 offers no public way to enumerate loaded chunks or to put a re-encoded container back into a section |
| **`Event.addPhaseOrdering`** | Phase order is global and cannot be undone on disable |
| **`HudElementRegistry.removeElement` / `replaceElement`** | They act on other mods' elements, permanently and globally. Same objection as phase ordering |
| **`ClientCommandRegistrationCallback`** | Would need §4.4's whole treatment against a per-connection dispatcher. `/vibec` covers the need |
| **Draining an `Item` subclass's or a `Block` subclass's own behaviour** | The game calls `Item.use` and the block's own methods on the object in the registry directly; there is no host frame in between, and mixing into them is a list somebody maintains against a vanilla class forever. The gate asserts the true behaviour positively instead |
| **Config knobs for native mods** | There is no `ctx` to read one from. The prompt says so plainly rather than letting the model emit a `config[]` the mod cannot honour |
| **Off-thread work in mod code** | Threads, executors and `*Async` are denied. Mod code runs on the server thread so the watchdog means something |

---

## 11. Gate inventory

| Gate | Runs | Covers |
| --- | --- | --- |
| `./gradlew build` | everywhere, CI required | `:fabric:surgeonSelfTest` (78 assertions run, up from 57 — the source holds 82 `check(` call sites; the number here is what a run actually prints, because a facts document should carry the figure the gate reproduces — four new fixtures cover the block `setId` seam, the fact that it is not confused with the item one, the fully-seamed `RubyBlock` shape, and a block-adjacent registry that is still refused), all five `:core:selfTest*` including the `RegistryLedger` suite and its pin/schema assertions, store/prompt/oracle assertions, the platform-free and pure-JDK checks |
| `./gradlew selfTestEcj` | CI, explicit step | The corpora recompiled on the ECJ backend a JRE-only install falls back to |
| `./gradlew :fabric:compileGametestJava :neoforge:compileClientgateJava` | CI, explicit step | That the client gates still compile, without needing a display |
| `scripts/smoke-fabric.sh` | CI matrix | 101 assertions on a real dedicated Fabric server, installed jar, driven over RCON. New in V4: the palette probe's arithmetic, that nothing in the gate crossed the boundary, and that the dedicated-server refusal covers blocks — names `minecraft:block`, fails the mod's load, mints no blockstate, and leaves a discarded orphan that is *named as a block* |
| `scripts/palette-gate.sh` | CI, its own server | **New.** 31 assertions. Forces a real global-palette crossing with synthetic blocks sized from the *measured* budget, then asserts what did and did not move: the local-palette section untouched, the global one repacked, its contents unchanged, a known vanilla neighbour block unchanged (the palette-shift detector), a past-the-old-boundary write that neither throws nor reads back wrong, the free path taken by a block that fits, the straggler watch arming and disarming, and a save/evict/reload round trip in which nothing was silently shortened. Its own script because a crossing is **irreversible for the life of the JVM** and would invalidate every palette number `smoke-fabric.sh` measures |
| `scripts/smoke-neoforge.sh` | CI matrix | 44 — the v2 path plus the loader-neutral datapack channel |
| `scripts/smoke-paper.sh {1.20.6,1.21.8,26.2}` | CI matrix | Untouched by V3 and V4, and that is the point |
| `./gradlew :fabric:runClientGameTest` | display / xvfb | 118 assertions in a real client: real GL, a real world, a real right-click. **No block coverage yet** — §9 |
| `scripts/clientgate-neoforge.sh` | display / xvfb | A self-driving mod, since NeoForge has no harness |
| `scripts/demo-live.sh` | by hand, needs a key | **Not a gate.** The prompt's only test |

The two client gates are `continue-on-error` in CI until they have passed a few times in a row;
their *compilation* is required, which is the half that was silently unprotected.
