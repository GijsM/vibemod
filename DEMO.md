# DEMO.md — six prompts, and what the host does with them (3.0.0)

In 3.0 a generated mod is a **plain Fabric mod**. It `implements
net.fabricmc.api.ModInitializer` (and `ClientModInitializer` if it has a client half), it
calls `ServerTickEvents.END_SERVER_TICK.register`, `CommandRegistrationCallback`,
`KeyMappingHelper`, `HudElementRegistry` and `Registry.register` the way every Fabric
tutorial on the internet does, it ships `data/**` and `assets/**` like a real jar, and it
has **zero VibeMod imports anywhere in it**. It still hot-loads and still hot-unloads,
because the host does not ask the model for cooperation: it installs a bytecode surgeon on
`InMemoryCompiler` and rewrites seventeen call sites before `defineClass`
(`fabric/.../FabricSeams.java` is the whole table). A Fabric `Event` cannot be
unsubscribed; a mod that *thinks* it subscribed to one, and is really standing behind a
host-owned fanout, can be.

Before 3.0 a generated mod on a loader was a **VibeContext mod**: it implemented
`com.gijsm.vibemod.api.Mod`, and its entire event surface was ten frozen hooks —
`onPlayerJoin`, `onPlayerQuit`, `onServerTick`, `onChat`, `onBlockBreak`, `onUseBlock`,
`onUseItem`, `onEntityDeath`, `onPlayerDeath`, `onRespawn`. Everything else it could do, it
did through VibeMod-shaped stand-ins: `ctx.command(name, description, handler)` — one flat
literal with a string-args handler, no Brigadier tree — and, inside `ctx.client(...)`,
`c.key(label, "G", onPress)` and `c.hud(id, canvas -> ...)` against a six-call `HudCanvas`.
Registries were banned outright, `Screen` did not exist as a concept, and every `files[]`
entry had to end in `.java`, so there was no channel for a recipe, a model, a lang file or
a texture at all.

**These are real prompts.** They are typed exactly as written, nothing here names a class,
an API or a file, and none of them is a scripted fixture. A model gets one shot at a
compile and, when it misses, the diagnostics go back to it and it tries again — see
[What a repair round actually looks like](#what-a-repair-round-actually-looks-like). What
each prompt *would* produce is the model's business; what the host does with what it
produces is this document's, and every mechanism named below is asserted by a gate in
`docs/phases/PHASE-{0,1,2,3}-RESULT.md`.

## The limits that apply to more than one prompt

Stated once here and repeated per prompt only where they bite.

- **A dedicated server refuses registry content.** `Registry.register` is refused
  deterministically and the mod's *load* fails; the refusal is in the log, in
  `/vibe errors`, and in the exception. The reason is in the message: a vanilla client that
  joins later negotiates a registry sync without the id and would be kicked, so VibeMod
  refuses rather than working until somebody logs in. The answer on a dedicated server is
  components on a vanilla item in the recipe result (`minecraft:custom_name`,
  `minecraft:lore`, `minecraft:item_model`, `minecraft:enchantment_glint_override`), which
  is what the prompt teaches as the fallback and what a live dedicated-server run of prompt
  (1) actually produced.
- **`assets/**` only render on a physical client.** On a dedicated server they are stored
  and reported inert, once per mod, in the log — no texture, model or translation is ever
  seen there.
- **A `ClientModInitializer` half is skipped on a dedicated server**, and the log says so,
  because a silent skip is how you get "the keybind does nothing on my server" with nothing
  to explain it.
- **Only `ITEM` and `ENTITY_TYPE` may be registered.** Every other registry is refused with
  a diagnostic naming what *is* supported. **Blocks are refused by name, with the reason**:
  `PalettedContainerFactory` takes its global palette bit width from the size of
  `BLOCK_STATE_REGISTRY` once per world load, and every chunk section in the loaded world
  is serialized against that strategy, so adding block states mid-session changes the id
  space under live containers — which does not necessarily throw, and that is exactly what
  makes it the wrong thing to ship.
- **Textures are `.png.grid` files** — a JSON palette plus rows, square, at most 64×64 —
  not binary PNG. The host encodes real RGBA PNG out of them with `Deflater` and `CRC32`.
- **There are no config knobs in native-mod mode.** A native mod has no VibeMod context to
  read one from, so the prompt tells the model to use named `private static final`
  constants and to promise no settings in the manual.

---

## 1. A ruby sword

```
/vibe make a ruby sword with a custom texture, crafted from rubies you get by smelting redstone
```

**What 2.0 could not do.** Two bans, either one fatal: registries were refused outright, so
no item could exist; and every `files[]` entry had to end in `.java`, so there was no
channel for a recipe, a model, a lang file or a texture even if one had.

**What it uses.**

| The mod writes | The surgeon rewrites it to | What that buys |
| --- | --- | --- |
| `Registry.register(BuiltInRegistries.ITEM, id, item)` | `Shims.registryRegister` | id namespaced, unsupported registries refused, entry journalled and tracked for teardown |
| `new Item.Properties().setId(key)` | `Shims.itemId` | the namespace rewrite lands *before* `Item.<init>`, which calls `itemIdOrThrow()` for both the description id and the model id |

All five `Registry` statics are on the table (`register` ×3, `registerForHolder` ×2); the
two `ResourceKey` overloads share a parameter list and are told apart by their **return**
type, which is why the seam matches on the whole descriptor rather than on name and arity.
The unfreeze itself is not at the call site — `Item.<init>` writes to the registry through
`createIntrusiveHolder` and therefore runs to completion *as an argument*, before the shim
is entered — so `FabricEntrypointAdapter` opens a window around the mod's whole
`onInitialize()` and closes it afterwards. `data/**` goes out through **`LoaderModContent`**
as `<world>/datapacks/vibemod-rubysword/`, `assets/**` joins the runtime client resource
pack **`FabricClientPacks`**, and **`ReloadCoordinator`** debounces both sides into one
reload per batch. `CreativeModeTabEvents.modifyOutputEvent` puts the item in the
Ingredients tab.

**The expected shape.** One Java file and six resource files, namespace
`vibemod_<modname lowercased>`:

```
RubySword.java                                          Registry.register + an Item subclass
data/vibemod_rubysword/recipe/ruby.json                 minecraft:smelting, redstone -> ruby
data/vibemod_rubysword/recipe/ruby_sword.json           crafting_shaped
assets/vibemod_rubysword/lang/en_us.json                item.vibemod_rubysword.ruby_sword
assets/vibemod_rubysword/items/ruby_sword.json          26.x item-model DEFINITION
assets/vibemod_rubysword/models/item/ruby_sword.json    the model it points at
assets/vibemod_rubysword/textures/item/ruby_sword.png.grid
```

The two-file item model is the shape a model trained on 1.20 gets wrong: in 26.x an
`item_model` component names `assets/<ns>/items/<name>.json`, which points at
`assets/<ns>/models/item/<name>.json`. There is no `SwordItem` class in this era at all —
`Item.Properties` carries `.sword(ToolMaterial.IRON, 4.0F, -2.4F)`. And an ingredient is a
**string**, not an object: `{"item": "minecraft:redstone"}` does not fail the build, it is
silently dropped as the pack loads and the mod looks fine until a player tries to craft it.

**What `/vibe disable` takes away.** The recipes and the advancement, with the whole
datapack directory — removed immediately, then a teardown reload, after which `level.dat`
has *forgotten* the pack id so a later boot cannot warn about it. The creative-tab entry
(the tab is invalidated *and* rebuilt; a tab that is only invalidated keeps handing out the
list it baked). The model, the texture and the lang key, after a second client reload —
the translation falls back to its key. Any command the mod registered, and every event it
subscribed to.

**What it does not.** **The item id stays in `BuiltInRegistries.ITEM`.** There is no
`MappedRegistry.remove` and there was never going to be one. Any stack already in a chest
or a hand is still there, and **its `use()` override still runs** — the game calls the
method on the object in the registry directly, and there is no host frame in between to
drain. The client gate asserts that in the positive, on purpose, so that if it ever changes
the gate says so instead of going quiet. What is gone is everything the item reached
*through* the host, and the recipe, so no new one can be obtained. `/vibe delete` (unload,
as opposed to disable) tombstones the ids in `registry-ledger.json`, atomically and per
installation, so the next boot does not re-register them; the id itself only leaves on a
world restart, and `/vibe info` says so on the card: *stays registered until the world is
restarted*.

**On a dedicated server.** Refused — see the shared limits. The live dedicated-server run
of this exact prompt produced a mod whose own manual says it, unprompted: *"Both items are
recolored vanilla items with a custom look and name rather than brand new registered items,
so they work fine on a dedicated server."* That is the host's `THIS HOST` prompt block
doing its job; before it existed the model spent a repair round rediscovering what the host
knew at boot.

---

## 2. A /home command with a HUD timer

```
/vibe make a /home command with a 30 second cooldown and a HUD timer showing the cooldown
```

**What 2.0 could not do.** It could approximate this, and the approximation is the point:
`ctx.command("home", description, handler)` was one flat literal with a string-args
handler — no Brigadier tree, no argument types, no `/home set <name>` — and the timer had
to be drawn on a six-call `HudCanvas` reached only through `ctx.client(...)`. Neither is
the code a Fabric author would write, and neither could grow past what VibeMod had wrapped.

**What it uses.** `CommandRegistrationCallback.EVENT.register(...)` goes through
`Shims.eventRegister` like any other event, and then **does not** get a fan: the fanout
routes it to **`CommandSeam`**, which does three things a plain subscription cannot. It
**invokes the callback immediately** against `server.getCommands().getDispatcher()`, with
the *captured* `CommandBuildContext` and `Commands.CommandSelection` — the real objects
vanilla handed the host at the last firing — so the command is live on the tick the mod
loads and no `/reload` is needed. It **diffs the dispatcher root** before and after
(identity, executor and child names) to discover what the callback added, because a
Brigadier callback is opaque and `CommandNode.addChild` *merges* rather than replaces. And
it **replays** every live mod's registration into the fresh dispatcher on every `/reload`
and on every datapack reload, since `reloadResources` constructs a new `Commands`.

The HUD half is `HudElementRegistry.addLast(...)` → `ClientShims.hudAdd` →
`LoaderClientEventBridge.rawHud`, a dispatch list behind the host's single permanent
`vibemod:mods` element, with the same watchdog and the same instant-detach-on-throw. There
is no per-tick scheduler; the cooldown is ticks counted in an `END_SERVER_TICK` handler,
with per-player state in a `ConcurrentHashMap` keyed by `player.getUUID()`.

**The expected shape.** One `.java` implementing both entrypoints, no resource files. The
mistake a model actually makes here is not getting one of the APIs wrong, it is putting
client code in the server entrypoint: `/home` belongs in `onInitialize()` (server thread),
the `HudElement` in `onInitializeClient()` (render thread).

**The honest gap.** The two halves have no channel between them. There is no mod
networking in 3.0, so the client half cannot *learn* the server's cooldown; what a model
typically writes is a `volatile long` deadline on the mod's own class, which carries across
in singleplayer and on a LAN host because both sides share one JVM. What it must never do
is read game state across that line — `server.getPlayerList()` from a HUD renderer is a
real data race that corrupts a world, not a theoretical one. On a dedicated server the
client half is skipped entirely, so the countdown has to be an action-bar or chat message
from the server half.

**What `/vibe disable` takes away.** Everything, and this is the clean one. `/home`
disappears from the live dispatcher — the server answers `Unknown or incomplete command`
for a command Brigadier has no remove for, which is a claim that is simply false unless the
reflective node surgery ran. The `HudElement` detaches (`nativeHuds=1` → `0`) and really
stops drawing. The tick subscription drains to zero. `/vibe enable` puts all three back,
and the command survives a `/reload` afterwards.

**What it does not.** Nothing lingers. The one thing worth knowing is upstream of disable:
**the first registration of a name wins.** If something else already owns `/home`, the seam
detects the merge, restores the previous executor, removes the added grandchildren, and
journals the collision through `ModDispatch` — so it lands in `/vibe errors` and counts
toward the error storm, rather than being logged and forgotten.

---

## 3. A bee invasion at dawn

```
/vibe make a boss bar bee invasion every dawn that drops honey armor recipes
```

**What 2.0 could not do.** Ship a recipe. Every `files[]` entry had to end in `.java`, so
there was no `data/**` channel: a mod could hand a player an item, but it could not add a
way to craft one, and an advancement that unlocks a recipe was not expressible at all.

**What it uses.** `ServerTickEvents.END_SERVER_TICK` and, if the mod sets up on boot,
`ServerLifecycleEvents.SERVER_STARTING` — both through `Shims.eventRegister` into
**`EventFanout`**, which holds exactly one permanent `Proxy` per distinct `Event` for the
life of the process and dispatches the mods standing behind it. `SERVER_STARTING` and
`SERVER_STARTED` are **replayed** for a mod hot-loaded after the server is already up, and
the fanout tracks what has actually fired so a mod loaded during boot restore is not fired
at twice. Recipes and the advancement go out through **`LoaderModContent`** and one
debounced **`ReloadCoordinator`** reload.

**The expected shape.** One `.java` plus a `data/**` tree:

```
BeeInvasion.java
data/vibemod_beeinvasion/recipe/honey_helmet.json     (and the rest of the set)
data/vibemod_beeinvasion/advancement/survived.json    rewards.recipes unlocks them
```

Dawn is `level.getOverworldClockTime()` in 26.2 — `getDayTime()` is gone, and that is
precisely the kind of thing a repair round exists for. The bar is
`new ServerBossEvent(UUID, Component, BossEvent.BossBarColor, BossEvent.BossBarOverlay)`
(one constructor, and it takes a `UUID` in this era), with `addPlayer`, `setProgress` and
`removeAllPlayers`. The bees are ordinary vanilla bees, spawned by the mod. "Honey armour"
is leather armour wearing `minecraft:custom_name`/`lore`/`item_model` in the recipe result:
that shape works on every host, and it sidesteps the fact that datapack **registry-layer**
files — enchantments, damage types, jukebox songs, painting variants, `worldgen/` — only
apply on the *next world load*, which is what makes an enchantment the wrong shape for a
mod a player just asked for.

**What `/vibe disable` takes away.** The recipes and the advancement, with the datapack
directory and the teardown reload; `level.dat` forgets the pack id. The tick and lifecycle
subscriptions drain to zero — the fanout's counters go to `0` and stay there, which is the
shape of a working teardown, since the fans themselves can never be removed.

**What it does not.** Two kinds of residue, both of them world state rather than
registration. **Bees already in the world stay** — they are vanilla entities, spawned by
the mod but not owned by it, and nothing about disabling a mod despawns what it spawned.
And **a boss bar that is on screen at the moment of disable is not revoked**: a
`ServerBossEvent` is a plain object the mod made, not a registration, so nothing seams it.
Its tick handler stops running, so the bar stops updating; it clears when the player
reconnects. The shape that avoids this is an invasion that ends on its own and calls
`removeAllPlayers()` when it does — a native mod does not write teardown code and must not
try to.

---

## 4. A keybind that lights up nearby ores

```
/vibe make a keybind that toggles X-ray-style glowing on nearby ores
```

**What 2.0 could not do.** 2.0 had a key (`c.key(label, "G", onPress)`, one of eight
slots), but its `ClientContext` exposed no world: `playerX/Y/Z`, `targetedBlock`, `fps`,
`worldTime` and a `HudCanvas` were the whole of it, and importing `net.minecraft.client.*`
was forbidden. There was no way to look at the blocks around the player and no way to draw
anything in world space.

**What it uses.** A real `ClientModInitializer` half — a **tracked deferred step**, queued
onto the render thread with `Minecraft#execute`, run under attribution and journalled
`where="onInitializeClient"` if it throws, so a broken client half degrades the mod instead
of failing the load. Inside it:

| The mod writes | The surgeon rewrites it to |
| --- | --- |
| `KeyMappingHelper.registerKeyMapping(new KeyMapping(...))` | `ClientShims.registerKeyMapping` |
| `HudElementRegistry.addLast(id, element)` | `ClientShims.hudAdd` |

The keybind seam leases one of the eight shared pool slots and hands back **the slot's**
`KeyMapping`, not the one the mod passed in, so ordinary `consumeClick()`/`isDown()`
polling just works — and the mod's manual must never promise a physical key, only point at
*VibeMod Slot N* under Options → Controls. (There is no `KeyBindingHelper` in this era;
the class is `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper`.) For drawing
in the world rather than on the HUD, `LevelRenderEvents` is an ordinary Fabric `Event` and
goes through the same generic `Event.register` seam; client callbacks are allowed on the
render thread and carry instant-detach-on-throw, because the render loop is the thing being
protected.

**The expected shape.** One `.java` implementing both entrypoints — or client-only, in
which case it is a `ModLoadException` on a dedicated server rather than a silent no-op —
with the scan and the drawing both on the render thread, reading only
`Minecraft.getInstance()`.

**The mechanism, honestly.** Ores are blocks, and blocks cannot glow:
`Entity.setGlowingTag(boolean)` is entity-only. So there are two real shapes, and they
differ in what disable can undo. Drawing the outlines yourself from the client half —
scanning `Minecraft.getInstance().level` around the player and drawing through
`LevelRenderEvents` or the HUD — is fully revocable. Spawning glowing marker entities from
the server half is not: those are world state, and `/vibe disable` cannot take back what a
mod put in the world.

**What `/vibe disable` takes away.** The key slot returns to the pool
(`keysLeased=2/8` → `1/8`, and the gate proves the slot is genuinely re-leasable), the HUD
or level-render callback detaches and stops being called, and the client-tick subscription
drains. On the drawing shape, that is all of it.

**What it does not.** Marker entities, if the mod took that route. And ordering: `addFirst`
versus `addLast` and the `attachElementBefore/After` anchors are dropped, deliberately and
not silently — the host owns one HUD element and a mod's is an entry behind it, so "first"
and "last" would be claims about a list the mod is not in.

**On a dedicated server** the whole mod is inert: the client half is skipped and says so.

---

## 5. An emerald shop screen

```
/vibe make an emerald shop screen
```

**What 2.0 could not do.** There was no `Screen` in 2.0 in any form. A "GUI" meant
VibeMod's own chest browser, which is VibeMod's UI, not the mod's; the client surface a mod
could reach was a HUD canvas, a key and a toast.

**What it uses.** Nothing, which is the point: the mod subclasses
`net.minecraft.client.gui.screens.Screen` and opens it, and there is **zero mod-facing
API** for any of it — `net/minecraft/` is an allowlisted package root in the surgeon's
policy and a `Screen` subclass is ordinary Java. What the host adds is **screen hygiene**:
every native mod on a physical client gets one tracked `Kind.CLIENT` registration whose
close hops to the render thread and, if the currently open screen's class was defined by
*this mod's* `BytesClassLoader` — an `instanceof` check **and** loader identity, because
two versions of a mod have identically named classes — calls `setScreenAndShow(null)`.

**The expected shape.** One `.java` implementing both entrypoints: the `Screen` subclass
and the keybind that opens it in `onInitializeClient()`, and the actual exchange as a
Brigadier command in `onInitialize()` through `CommandSeam`.

**The trigger is the interesting part.** `ClientCommandRegistrationCallback` is still
refused, and there is no mod networking, so the client-side trigger is a **keybind**. In
singleplayer a server command can hop to the render thread with `Minecraft#execute` — the
client gate does exactly that — but the few-shot deliberately does not teach it, because a
mod on a dedicated server would need networking for the same move. The join in the other
direction does exist and is ordinary: a button can run
`Minecraft.getInstance().player.connection.sendCommand("shop buy emerald")`, which is the
player running the mod's own command, so the transaction happens server-side where it
belongs.

**What `/vibe disable` takes away.** The command. The keybind slot. And the screen itself,
off the player's display, if it is open at that moment — `Closing
vibemod.emeraldshop.EmeraldShop$ShopScreen: the mod that defined it was unloaded`.

**What it does not.** Whatever the shop already paid out. Items in a player's inventory are
world state.

**On a dedicated server** the screen never exists: the client half is skipped, so a shop is
a singleplayer or LAN-host feature and the manual should say so.

---

## 6. A pet wolf

```
/vibe make a pet wolf that fights for me and teleports to my side
```

**What 2.0 could not do.** A *custom* pet — its own entity type, its own attributes, its
own renderer — was impossible: registries were banned outright. The vanilla-wolf version
was reachable, but only by polling `onServerTick`, one of the ten frozen hooks, and
commanding it through a flat `ctx.command`.

**What it uses.** There are two shapes and they land in different places.

The **vanilla-wolf shape** needs no registry at all: tame or summon
`net.minecraft.world.entity.animal.wolf.Wolf` (26.x moved it into a `wolf` subpackage),
hold its UUID in a `ConcurrentHashMap` keyed by `player.getUUID()`, and in an
`END_SERVER_TICK` handler set its target and call `snapTo(x, y, z)` or
`teleportTo(x, y, z)` when it falls behind. Every subscription is through
`Shims.eventRegister`; the command is `CommandSeam`. **This shape works everywhere**,
dedicated servers included.

The **custom-entity shape** uses four more seams:

| The mod writes | The surgeon rewrites it to |
| --- | --- |
| `EntityType.Builder.of(...).build(key)` | `Shims.entityTypeBuild` |
| `Registry.register(BuiltInRegistries.ENTITY_TYPE, id, TYPE)` | `Shims.registryRegister` |
| `FabricDefaultAttributeRegistry.register(TYPE, ...)` (both overloads) | `Shims.defaultAttributes` |
| `EntityRendererRegistry.register` / `EntityRenderers.register` | `ClientShims.entityRenderer` |

The attribute registration is seamed not because it would fail — it is a plain map put into
a map fabric-object-builder-api has already made mutable — but because nothing else could
take it away again. Both renderer entry points are on the table: the fabric wrapper because
a model trained on older tutorials will write it, and vanilla's own
`EntityRenderers.register` because a transitive access widener made it public and current
code writes that. Late renderer registration works, but a type registered after the
dispatcher baked its map is simply absent and the first frame in which one is visible dies,
so registering a type installs vanilla's `NoopRenderer` **immediately** and the mod's real
renderer replaces it a frame later. The prompt teaches subclassing a vanilla mob anyway, so
a renderer already exists.

**The expected shape.** `PetWolf.java` plus, for the custom-entity shape, a lang file for
the entity's name and whatever `data/**` the mod wants for taming. Spawning is the mod's
own job — `TYPE.create(level, EntitySpawnReason.COMMAND)` then `level.addFreshEntity(...)`.
No spawn eggs and no natural spawning.

**What `/vibe disable` takes away.** The command, the tick subscription, the default
attribute supplier, and the mod's entity renderer — which is **replaced** by `NoopRenderer`
rather than removed, because a disabled mod's entities are still in the world and an
invisible mob is a bug report while a crashed client is a lost world.

**What it does not.** The wolf. On the vanilla shape it is a vanilla wolf and simply stops
being managed. On the custom shape the `EntityType` id stays in the registry for the same
reason the item's does, the entities already spawned stay in the world and keep their
class, and removing the attribute supplier affects the *next* spawn rather than a mob that
already has its attribute map. That is the shape of everything in this phase: the object
outlives the mod, and the ledger writes down what could not be taken back rather than
hiding it.

**On a dedicated server** the custom-entity shape is refused at load; the vanilla-wolf
shape is the one to ask for.

---

## What a repair round actually looks like

Not every generation compiles first time, and the interesting failures are the ones where
the model wrote perfectly good 1.20 Java. Prompt (1) came back clean on the first round.
Prompt (2) took three repair rounds on a real dedicated server, and each one is a different
kind of wrong.

**Round one — invented and moved symbols.** `RelativeMovement` was renamed; `getServer()` is
not on `ServerPlayer` any more:

```
[ERROR] /vibemod/homecooldown/HomeCooldown.java:17 - cannot find symbol
[ERROR] /vibemod/homecooldown/HomeCooldown.java:33 - cannot find symbol
[ERROR] /vibemod/homecooldown/HomeCooldown.java:64 - cannot find symbol
[ERROR] /vibemod/homecooldown/HomeCooldown.java:88 - cannot find symbol
```

The reply was four **edit blocks** rather than a rewritten project
(`Applied 4 edit block(s) from an edit-shaped response`) — cheaper in tokens and less likely
to lose an unrelated file.

**Round two** fixed all but one, and **round three** is the interesting one: the name is now
right and the *arguments* are not.

```
[ERROR] …/HomeCooldown.java:62 - no suitable method found for
  teleportTo(ServerLevel,double,double,double,Set<Object>,float,float)
    method ServerPlayer.teleportTo(double,double,double) is not applicable
    method ServerPlayer.teleportTo(ServerLevel,double,double,double,
                                   Set<Relative>,float,float,boolean) is not applicable
```

This shape is what `SymbolOracle` reads. For a genuinely missing member it answers "this type
has no `getServerr`; here are the real members closest to that name". For an overload
mismatch it must **not** say that — the model got the name right — so it says the name exists
but the arguments do not, and lists every real overload, shortest first. Both javac's and
ECJ's wordings for both cases are parsed out of the formatted diagnostics, so the hint works
whichever compiler backend resolved. Round three took **one** edit block and the mod went
live.

A policy violation from the surgeon arrives through the same channel, as a javac-shaped
diagnostic naming the forbidden API and the reason:

```
Reflect.java: error: forbidden API: java.lang.reflect.Method — reflection
  (a mod the host can unload must not reach around its own class loader)
```

## Running these yourself

`scripts/demo-live.sh` drives prompts (1) and (2) against a real dedicated Fabric server
over RCON. It is deliberately **not** a gate: it spends real money and depends on a model's
judgement, and a model having a bad day is not a regression in this repo. It asserts the
whole arc — generated → self-healed → live → exercised → deleted → **no residue** (store
gone, datapack directory gone, pack no longer selected, command gone from the dispatcher,
`/vibe list` no longer knows it). It needs `$OPENROUTER_API_KEY` or
`~/.config/vibemod/openrouter.key`, and it never writes a key into the run directory.

The other four prompts want a human: a keybind, a screen and a boss bar are not things RCON
can press, click or look at. Join a singleplayer world with `./gradlew :fabric:runClient`
and type them.

## Where every claim above comes from

| Claim | Gate |
| --- | --- |
| the seam table, byte-identical pass-through, policy diagnostics | `:fabric:surgeonSelfTest`, 57 assertions inside `check` |
| events, commands, resources, registry refusal on a dedicated server | `scripts/smoke-fabric.sh`, 89 assertions |
| the datapack channel on a host that shares no Fabric code | `scripts/smoke-neoforge.sh`, 44 assertions |
| keybinds, HUD, screens, textures, the creative tab, a real right-click | `:fabric:runClientGameTest`, 114 assertions, on a real display |

Each phase's verbatim gate tails are in `docs/phases/PHASE-{0,1,2,3}-RESULT.md`, along with
the bugs the gates found — including the one where Phase 3's worst regression was caught by
*Phase 2's* assertions failing, which is the best argument there is for gates that grow
rather than get replaced.

---

# Appendix — the Paper record

Everything below is the verified-run record for the Paper line, recorded before V3 and
unchanged by it. Paper is untouched by 3.0: same plugin, same gate, same behaviour, same
`VibeContext` mod flavour — which is also what NeoForge still uses. The transcripts are
kept verbatim because they are the evidence for claims (hot-swap, rollback, the watchdog,
export to a standalone plugin, the config loop, the degraded→fix loop) that 3.0 did not
change.

> The v1/v2 transcripts below were recorded before the plugin's log prefix settled on
> `[VibeMod]`; log lines have been normalized to the current name.

Everything below was executed for real against the live Paper 1.21.8 server in `server/`,
driven over RCON with `scripts/rcon.sh`. Model: `anthropic/claude-sonnet-5` via OpenRouter.
✅ = machine-verified via console assertions; 🎮 = needs a human player (verified code paths only).

## 1. Boot ✅
Paper 1.21.8 build 60 on Temurin JDK 25. `[VibeMod] VibeMod ready` — zero plugin errors.
(The log's `No key layers in MapLike[{}]` lines are vanilla's empty-flat-preset grumble; the
`PaperVersionFetcher` stack trace is Paper phoning its own sunset v2 update API. Neither is ours.)

## 2–3. Prompt → gameplay ✅
```
> vibe make when a creeper dies a chicken spawns at its location with a poof
  [VibeMod] Generated ChickenCreepers v1        (LLM round-trip ≈ 8s)
> execute if entity @e[type=chicken]             → Test failed        (baseline: none)
> summon creeper 0 -58 0 {NoAI:1b}
> damage @e[type=creeper,limit=1] 100
> execute if entity @e[type=chicken]             → Test passed, count: 1   🐔
```

## 4. Self-healing compile loop ✅ (organic, in production)
```
> vibe make zombies explode into a colorful firework when they die
  [VibeMod] Generated ZombieFireworks v1 after 1 repair round(s)
```
The model's first attempt failed javac; the diagnostics were fed back and round two compiled.
Also verified standalone: deliberate syntax errors produce line-numbered diagnostics
(CompilerSelfTest), and unusable JSON triggers a repair round (LlmSelfTest).

## 5. Edit → hot-swap → rollback ✅
```
> vibe edit ChickenCreepers spawn exactly two chickens instead of one
  Generated ChickenCreepers v2
> (kill creeper)  → Test passed, count: 2
> vibe rollback ChickenCreepers                  → Rolled back ChickenCreepers to v1.
> (kill creeper)  → Test passed, count: 1
```

## 6. Exact teardown ✅
```
> vibe disable ChickenCreepers  → (kill creeper) → Test failed        (no chicken)
> vibe enable ChickenCreepers   → (kill creeper) → Test passed, count: 1
```

## 7. Real runtime /commands ✅ (including a found-and-fixed bug)
```
> vibe make add a command /oink that spawns a pig at 0 -58 0
  Generated OinkSpawner v1
> oink                          → Oink! A pig has appeared at 0 -58 0.   + pig entity verified
> vibe disable OinkSpawner
> oink                          → Unknown command: /oink
> vibe enable OinkSpawner
> oink                          → works again (same Command instance revived)
```
Found during this test: Paper's `getKnownCommands()` returns an immutable view, so map removal
threw `UnsupportedOperationException` and `/oink` survived disable. Fixed by making runtime
commands hold a swappable handler — unregister neuters the handler regardless of map surgery,
re-register revives in place. Exactly the fragility the research predicted for this subsystem.

## 8. Watchdog ✅
```
> vibe make ... action "burn" that busy-loops ~2s on the main thread
  Generated PrimeBurn v1
> vibe do PrimeBurn burn
  (tick stalls — even the RCON response times out)
  PrimeBurn was auto-disabled by the watchdog (too slow)
> vibe do PrimeBurn burn        → No such mod/action    (short-circuited)
> vibe list                     → PrimeBurn [off]       (persisted; won't restore at boot)
```

## 9. Export → genuine standalone plugin ✅
```
> vibe export ChickenCreepers   → exports/ChickenCreepers-1.jar + ChickenCreepers-src/
jar contains: plugin.yml, mod classes, generated JavaPlugin wrapper, embedded api classes
Dropped into plugins/, VibeMod's own copy of the mod disabled, server restarted:
  [ChickenCreepers] Enabling ChickenCreepers v1.0
> (kill creeper)  → Test passed, count: 1        (behaviour from the exported plugin alone)
```

## 10. Persistence across restarts ✅
Server restarted repeatedly during testing; enabled mods recompile from `mods/<Name>/v<N>/` and
re-enable at boot (`Restoring mod ChickenCreepers v1` → `ChickenCreepers v1 is live`), disabled
ones stay off.

## 11. Panic ✅
```
> vibe panic     → all 5 mods off, /oink → Unknown command, creeper death spawns nothing,
                   no errors in log, server healthy
```

## 12. Prompt diversity ✅
Five distinct mod shapes generated and verified: event listener (ChickenCreepers), runtime
command (OinkSpawner), named action (PrimeBurn), repeating task (SkyDiamonds — diamond item
entity confirmed dropping), event + entity manipulation (ZombieFireworks — firework_rocket
entity confirmed on zombie death; it detonates same-tick, so the check must be immediate).

## Not machine-verifiable (needs a human in-game) 🎮
Boss-bar progress + sounds/particles, the `/vibe gui` chest browser, `/vibe source` written
books, `/vibe chat` mode, and player-only mechanics. Code paths compile-verified; join
`localhost` with a 1.21.8 client, `op` yourself, and try them.

## Cost
The whole verified session (≈10 generations incl. retries + smoke tests): ≈ $0.65 of
OpenRouter credit.


---

## Paper 2.0 verification (2026-08-21, evening)

Built by 5 parallel subagents against frozen contracts; **zero integration compile errors on
first full assembly** (again). Self-tests: LlmSelfTest (incl. embedded-API-copy drift guard +
few-shots extracted from the live prompt, parsed, compiled clean), StoreSelfTest (v1-meta
normalization, validation matrix, schema evolution), BookParserSelfTest (pure JVM) — all PASS.

### Live config loop ✅ (the headline)
```
> vibe make when a creeper dies chickens spawn ... with a poof
  Generated ChickenCreepers v3        knobs: chicken-count (1-16), particle-count
> (kill creeper)                      → Test passed, count: 3     (schema default)
> vibe set ChickenCreepers chicken-count 5   → 3 -> 5
> (kill creeper)                      → Test passed, count: 5     (live read — no reload!)
> vibe set ... chicken-count 999      → 999 is above the maximum of 16
> vibe set ... chicken-count banana   → not a valid integer
> vibe set ... chiken-count 4         → no config key 'chiken-count'
```

Native mods have no equivalent: there is no VibeMod context to read a knob from, so the
native profile tells the model to omit `config` entirely and use named constants instead.

### Documentation surfaces ✅
`/vibe info ChickenCreepers` (console-rendered): card + usage + [manual][config][info][off] +
verified facts (listeners: 1, knobs with live values). v1 mod `/vibe info ZombieFireworks`:
degrades to description + introspected facts, no knob section, no errors.

### Diff-based repair/edit ✅ (first live use)
```
> vibe edit ChickenCreepers also play a chicken sound, change nothing else
  Applied 1 edit block(s) from an edit-shaped response
  Generated ChickenCreepers v4       knobs preserved; player's chicken-count=5 survived
```

### Reload ✅
config.yml watchdog 250→400ms + `/vibe reload` → `Config reloaded (model=..., watchdog=400ms/...)`.

### Export with config ✅
`ChickenCreepers-4.jar` embeds a seeded config.yml (`chicken-count: 5` — the live override, with
description comments); the standalone wrapper reads it via standard Bukkit getConfig.

### Regression ✅
Rollback v4→v3 recompiles + hot-swaps; knob values still apply across versions (5 chickens on v3).

### Deferred / human-verified 🎮
- Standalone boot of a v2 exported jar (mechanism proven in v1; deferred to avoid restarting the
  server twice while player was online — drop the jar in a plugins/ dir to confirm).
- Book flows in the client (sign-to-submit prompt/edit books, config-book Done-loop), GUI detail
  panel + steppers + settings page, install-card buttons: give/parse/apply logic is unit- and
  compile-verified; the clicking needs hands. Steps: `/vibe book`, write a wish, Sign it.
  `/vibe config GrapplingHook`, change pull-strength, press Done mid-swing.

Field note: while v2 was being verified, player (age-appropriate chaos engineer) had already
generated GrapplingHook v1 with three perfectly-formed knobs. The contract works under real use.


---

## The rename release (2026-08-21, evening) — VibeMod

Rename + debuggability + native dialogs. Three parallel agents (rename sweep / ModErrors+DebugEcho
runtime / dialog UX), architect-integrated; full compile clean on first assembly (fourth in a row).

### Rename + migration ✅
Server stopped, the plugin's data dir migrated from its pre-rename name to plugins/VibeMod
(API key + mods + moddata + exports intact), the stale old jar removed. Boot:
`[VibeMod] Enabling VibeMod`, all 7 enabled mods
recompiled from stored sources that still say `implements VibeMod` — the deprecated
`VibeMod extends Mod` bridge is load-bearing and works. New generations teach/emit `implements Mod`.

### Degraded → fix loop ✅ (the headline)
```
> vibe make ... action "boomcheck" which intentionally throws ...   → Generated DiagCheck v1
> vibe do DiagCheck boomcheck
  Error in mod command: kaboom test
  DiagCheck hit an error (RuntimeException) — [fix] [errors]        (one announce, buttoned)
> vibe list → ● DiagCheck [degraded]                                (mod keeps running)
> vibe do DiagCheck boomcheck (again) → deduped to 2×, NO second announce
> vibe errors DiagCheck → "2× java.lang.RuntimeException: kaboom test
                            at vibemod.diagcheck.DiagCheck...(DiagCheck.java:30) (action:boomcheck, last just now)"
> vibe fix DiagCheck
  Applied 1 edit block(s) from an edit-shaped response              (surgical repair!)
  Generated DiagCheck v2
> vibe do DiagCheck boomcheck → "[DiagCheck] boomcheck ran successfully"
> vibe list → ● DiagCheck [on]                                      (degraded cleared)
```

### Error storm ✅
Rolled back to throwing v1, 11 rapid triggers → `DiagCheck was auto-disabled after an error storm`.
Storm fires once per episode; threshold live-reloadable (verified via /vibe reload).

### Also machine-verified ✅
Rollback regression (used as the storm setup), `/vibe debug <mod> on` toggle, reload of errors.*
keys, boot-restore of the full mod set post-migration, self-test suites (ErrorsSelfTest 36 checks:
dedup/episodes/storm-once/cap-eviction/persistence; Llm/Store/Compiler suites incl. a
bridge-compile case and "prompt never teaches the deprecated name").

### Human-verified 🎮 (client-only)
Native dialogs (`/vibe make` argless → popup with multiline idea field; `/vibe config <mod>` →
sliders/checkboxes/dropdowns with Save; fix-confirm dialog), virtual books (manual/source/errors
open with NO item), the restyled GUI (state-colored borders, ● dots, expressive buttons incl.
per-mod [⟳ reload], sounds), debug-echo lines to ops, unified ⬡ vibe chat style. Dialog API
signatures were javap-verified against the real paper-api jar; the clicking needs hands.
