# Phase 2 result — resources: datapacks, a runtime resource pack, and one reload per batch

All six deliverables (A–F) landed. All four gates are green, and the one that
matters is the fourth: inside a real Minecraft client, a mod written as a
**plain Fabric mod** ships a `data/**` recipe and an `assets/**` model, texture
and lang file; the recipe appears in the integrated server's live
`RecipeManager`, the model and a hand-encoded PNG resolve through the game's own
`ResourceManager`, a translation key that did not exist when the client started
translates — and `/vibe disable` takes all of it away again, asserted after a
second completed reload rather than after a deleted file.

The same channel, byte for byte, works on NeoForge. That was the design bet and
it paid: nothing in it names a loader.

---

## What landed, by deliverable

### A. Store: non-Java files

| File | What |
| --- | --- |
| `core/.../util/Ids.java` | New. The sanitize rule, extracted from `LoaderDialogRenderer.Bindings` |
| `core/.../store/ModResources.java` | New. Resource-path validation, the canonical namespace, and the rewrite |
| `core/.../store/PixelGrid.java` | New. `.png.grid` parsing + a hand-rolled RGBA PNG encoder |
| `core/.../store/ModStore.java` | `saveNewVersion` splits java/resources and canonicalizes; `resources(name, version)` added |
| `core/.../llm/PromptLibrary.java` | `parse()` accepts `data/**`/`assets/**` paths and validates grids |
| `core/.../gen/ModGenerator.java` | Resources skip the compiler, survive a repair round, and can be edited |

The namespace rule is the load-bearing part. Whatever namespace the model chose,
what reaches disk is `vibemod_<modname sanitized>` — **in the path and in every
`"<ns>:…"` id inside the bodies** — so two mods cannot collide on a recipe id no
matter what they were told to call themselves, and the few-shot is free to use a
natural-looking namespace. `"minecraft:"` ids are never touched, because
`minecraft` is not in the "foreign namespace" set the rewrite builds from the
mod's own paths.

**The rewrite deliberately stops at ids and does not touch dotted forms**
(`item.myns.ruby` in a lang file). Those are translation keys, a mod's own Java
may name one, and Java sources are *not* rewritten — so rewriting the lang file
alone would break exactly the pairing it was meant to protect. The prompt states
the canonical namespace instead, and the rewrite is the safety net for a model
that ignores it.

Validation happens in `PromptLibrary.parse`, not at the store. That is where a
rejection becomes a self-heal round with the model's own text in it; at the
store it would be a stack trace after the money has already been spent. A
non-square grid, a character that is not in the palette, a `..` in a path and an
uppercase path segment are all refused there, with messages written for the
model.

`sources()` needed no change and that is asserted rather than assumed: its
`"*.java"` glob is on a **non-recursive** directory stream, and resources always
live at least two directories down.

### B. Datapack channel (loader-common + both hosts)

| File | What |
| --- | --- |
| `core/.../runtime/ModContent.java` | New. The seam: `Registration install(ModHandle)` |
| `core/.../runtime/ModLifecycle.java` | `setContent(...)`; `activate` installs after the entrypoint, tracks `CONTENT` |
| `core/.../runtime/ModHandle.java` | `Kind.CONTENT`, `contentCount()`, and drain order |
| `loader-common/.../content/LoaderModContent.java` | New. Materializes `<world>/datapacks/vibemod-<mod>/` |
| `fabric/.../VibeModFabric.java`, `neoforge/.../VibeModNeoForge.java` | Two identical wiring lines each |

**The hook is on the lifecycle, not on the call sites that load a mod**, and
that is a correctness decision. `/vibe enable` on an already-compiled mod goes
straight to `lifecycle.enable(...)` and never touches the store, so a content
installer wired into the compile path would materialize a datapack on first load
and silently skip it on every re-enable afterwards.

It runs **after** the mod's own entrypoint: a mod's code should be running
before its recipes appear, and the `CONTENT` registration then lands last in the
handle's list — which is the order `ModHandle.drain()` now guarantees explicitly
rather than by luck.

Materialization is staged and renamed. A half-written datapack directory that
vanilla's folder source discovers mid-write is a pack that fails to load, during
the reload the player is watching.

Verified rather than assumed, by disassembling the 26.2 jar:

- `MinecraftServer.reloadResources(Collection<String>)` **managed-blocks when
  called on the server thread** (`isSameThread()` → `managedBlock`). That is why
  nothing reloads inside `drain()`, which the watchdog gives 250ms.
- It does **not** call `repo.reload()` — `/reload`'s own `discoverNewPacks` does,
  and so does the coordinator.
- Vanilla auto-enables a newly discovered world datapack: `ReloadCommand
  .discoverNewPacks` adds every available id the world has not explicitly
  disabled. The coordinator replays exactly that, and adds its own ids by name
  on top.
- `reloadResources` writes the resulting selection back through
  `WorldData#setDataConfiguration` (`lambda$reloadResources$4`). **That is why
  the teardown reload is not optional**: it is what makes `level.dat` forget a
  pack whose folder is gone. The gate asserts it in both directions, by reading
  `level.dat` itself.
- The pack id for a world datapack folder is `"file/" + folder` (read off
  `FolderRepositorySource`'s string-concat bootstrap). The coordinator resolves
  it against the repository anyway, so a version that changes the prefix changes
  nothing.

### C. ReloadCoordinator (loader-common)

`loader-common/.../content/ReloadCoordinator.java`, plus two interfaces
(`ClientReloader`, `ClientResourceSink`) that exist so `loader-common` can stay
loadable on a dedicated server — the same rule Phase 1 wrote down for
`ClientSeam`.

A dirty mark arms a 40-tick timer; every further mark re-arms it; when it
expires exactly one reload runs. A side already reloading never starts a second
— it re-arms instead, so a change landing mid-reload is not lost. Ticked from
the host's existing `END_SERVER_TICK` subscription, so it inherits that
subscription's "null between worlds" lifetime and needs no loader event of its
own.

`describeState()` reports
`serverReloads= clientReloads= serverDirty= clientDirty= serverPending=
clientPending= ownedPacks= lastReload=`. Boot restore of three live mods on the
Fabric gate coalesces to **one** server reload (asserted `<= 2`, as specified).

### D. Runtime client resource pack (fabric)

| File | What |
| --- | --- |
| `fabric/.../mixin/client/PackRepositoryAccessor.java` | New. The second mixin in the project |
| `fabric/.../client/FabricClientPacks.java` | New. One pack, per-mod manifests, the grid→PNG step, the reloader |
| `fabric/src/main/resources/vibemod.mixins.json` | Gains a `"client"` array |
| `fabric/.../client/VibeModFabricClient.java` | Builds it, resets it, joins it |

`PackRepository.sources` is `private final Set<RepositorySource>` assigned from
`ImmutableSet.copyOf(varargs)` — no add, no setter, no event. So a pack that did
not exist when the client was constructed cannot reach the client's repository
through any public API at all.

**An accessor mixin rather than the constructor-tail injection the brief
suggested**, and the reason is the server: the same constructor runs for the
*server's* pack repository, so a ctor-tail mixin would have to guess which
repository it is looking at by sniffing the sources it was handed. Reading the
field off the object `Minecraft#getResourcePackRepository()` hands back asks the
question directly — and it works whenever we get around to asking, which
matters, because `Minecraft`'s own repository field is not assigned until part
way through its constructor, well after Fabric runs client entrypoints. The pack
joins on a best-effort basis at client init and again before every reload, so
"not yet" is never "not at all".

`required=true` + `Pack.Position.TOP` + `fixedPosition` means vanilla's
`rebuildSelected` re-inserts the pack after every reload without anybody
remembering it in `options.txt` (verified by disassembly). The gate asserts both
`getAvailableIds()` and `getSelectedIds()`.

The **stale guard is the strongest form of the one §D asks for**: at client init
no world is loaded, so no mod is live, so the pack *must* be empty — anything on
disk is crash residue. It deletes the whole tree rather than diffing it against
the store.

The PNG encoder is ~60 lines of `Deflater` + `CRC32`, RGBA8, filter type 0 per
scanline, no `java.desktop`. The store self-test walks the output's chunk list
and verifies every CRC the way a decoder would; the client gate reads the
signature back off the resource the game resolved.

### E. Prompt

`PlatformProfile` gained a thirteenth component, `filesContract`, because "every
`files[]` path ends in `.java`" was hardcoded in the shared skeleton and saying
that *and* "here is how to write a recipe" in one prompt is a contradiction the
model has to resolve for itself. The four non-native profiles supply the old
text byte-for-byte, and `LlmSelfTest` asserts they still produce `.java`-only
prompts with no `RESOURCE FILE` text in them.

The native profile's cheat sheet lifts the resource ban, states the canonical
namespace, splits datapack types into "live immediately, gone on disable" and
"only on the next world load" (enchantments, dialogs, damage types, jukebox
songs, painting variants, worldgen), teaches the `.png.grid` format, says
`assets/**` are inert on a dedicated server, and adds the answer models are
actually asked for: **a custom item without a registry** — a vanilla item wearing
`minecraft:custom_name`, `minecraft:lore`, `minecraft:item_model` and
`minecraft:enchantment_glint_override` out of a recipe result.

One new few-shot, `RubyCharm`: eleven lines of Java and six resource files.
**Every JSON shape in it was read out of the running game's own data, never
recalled:**

| Shape | Read from |
| --- | --- |
| `crafting_shaped` + `result.components` | `data/minecraft/recipe/golden_apple.json`, `…/suspicious_stew_from_blue_orchid.json` |
| advancement + `display` | `data/minecraft/advancement/story/mine_stone.json` |
| `recipe_crafted`'s `recipe_id` field | `RecipeCraftedTrigger$TriggerInstance`'s codec (`javap -v`) |
| the **two-file** item model layout | `assets/minecraft/items/apple.json` → `assets/minecraft/models/item/apple.json` |
| `pack.mcmeta`'s `min_format`/`max_format` | `data/minecraft/datapacks/trade_rebalance/pack.mcmeta` + `PackFormat$IntermediaryFormat`'s codec |

The item model layout is the one that matters most: in 26.x an `item_model`
component names `assets/<ns>/items/<name>.json`, which points at
`assets/<ns>/models/item/<name>.json`. A model trained on 1.20 writes one file
and gets a missing texture.

Prompt budgets, printed by `LlmSelfTest`:

```
  paper-modern     22977 chars  ~  5744 tokens  (2 few-shots)
  paper-legacy     23980 chars  ~  5995 tokens  (2 few-shots)
  fabric           25997 chars  ~  6499 tokens  (3 few-shots)
  fabric-legacy    33773 chars  ~  8443 tokens  (3 few-shots)
  neoforge         33778 chars  ~  8444 tokens  (3 few-shots)
```

25997 against a 26000 budget, with a third few-shot and a whole new surface
taught. `LlmSelfTest` asserts the number, so the next thing added to this prompt
has to take something out.

### F. Gates

| Gate | Before | After |
| --- | --- | --- |
| `./gradlew build` (incl. `:fabric:surgeonSelfTest` 38) | green | green, with 46 new store and prompt assertions |
| `scripts/smoke-fabric.sh` | 49 | **75** |
| `scripts/smoke-neoforge.sh` | 31 | **44** |
| `:fabric:runClientGameTest` | 56 | **79** |

The dedicated-server canary answers the question RCON cannot ask. `/recipe give
@a <id>` resolves its **target selector before its recipe argument** (verified by
disassembling `RecipeCommand.lambda$register$3`), so with no players online it
fails identically for a real recipe and a made-up one. So `ResourceCanary` — a
plain Fabric mod with no VibeMod import — registers `/rescanary`, which asks the
live `RecipeManager` and the live advancement tree directly and prints
`recipe=true advancement=true`. `/function vibemod_resourcecanary:hello` is the
same claim with **no mod code in the path at all**.

Two gate-environment fixes were needed, and both make the gates more
deterministic rather than less:

- **`pause-when-empty-seconds=0`** in both smoke scripts. Since 1.21.2 an empty
  server stops ticking after a minute, which freezes the tick-driven reload
  debounce — and, it turns out, every tick-counting assertion the Phase 0 gate
  has been making. The first Phase 2 run failed a *Phase 0* assertion for
  exactly this reason (see below).
- **Waiting on the reload's completion line, not its start line.** See
  "The bugs the new gates found".

---

## The bugs the new gates found

**1. An empty server stops ticking, and the Phase 0 tick assertions were living
on borrowed time.** The first run of the extended Fabric gate failed
`re-enabling brought the subscription back (ticks 26 -> 26)` — a Phase 0
assertion, not a Phase 2 one. The cause was `Server empty for 60 seconds,
pausing`: Phase 2's extra wait loops pushed the gate past the one-minute mark
and the server simply stopped ticking. The assertion had never been wrong; it
had never been slow enough to find out. `pause-when-empty-seconds=0` fixes it
for every tick-counting assertion in both gates.

**2. `managedBlock` pumps the task queue, so "the reload started" is not "the
reload finished".** The gate's first version waited for `Reloading server data`
and then immediately asked over RCON whether the removed mod's function still
resolved. It did — because `reloadResources` managed-blocks on the server
thread, `managedBlock` runs queued tasks while it waits, and the RCON command
executed *inside* the reload, against the old function library. The assertion
was passing vacuously (it looked for the absence of a string that never
appeared). Both gates now wait for `Server data reloaded in`, and the assertion
is positive: the reply must say `Unknown function`.

**3. `ReloadableResourceManager` swaps its packs at the start of a reload, not
the end.** The client gate asserted the model, the texture and the translation
in one breath. The model and texture resolved; the translation did not — because
`getResource()` answers from the new pack list immediately while the reload
*listeners*, `LanguageManager` among them, have not run yet. The gate now waits
for `clientReloads` to leave zero before asserting anything that a listener
produces.

**4. Deleting a pack file out from under a running reload throws.** Disabling a
mod while the reload its own load had triggered was still in flight produced a
`NoSuchFileException` in `SimpleReloadInstance`. The end state self-corrected
(the removal marks the pack dirty, so another reload follows), but a stack trace
nobody can act on is not an acceptable way to get there. `FabricClientPacks` now
holds mutations that arrive during its own reload and applies them when it
completes, before the coordinator's next flush. This closes the window VibeMod
opens; it does not close the window a *player* opens by changing resource packs
at exactly the wrong moment, because that reload is the game's and we are not
told about it.

---

## Deviations from the brief, and why

**1. `ResourceLocation`/`location()` do not exist in 26.2.** The brief's
`RecipeHolder.id().location()` shape is 1.21-era. In 26.2 `RecipeHolder.id()`
returns `ResourceKey<Recipe<?>>` and the accessor is **`identifier()`**;
`ResourceLocation` itself is `net.minecraft.resources.Identifier`. Found by the
Fabric gate's first run, which reported it as an ordinary compile diagnostic
through the existing self-heal channel.

**2. The client pack joins through an `@Accessor`, not a constructor-tail
inject.** §D offered both. The accessor wins because the constructor also runs
for the server's repository and a ctor injection would have to guess which one
it is looking at. See §D above.

**3. `PackRepositoryAccessor` is a client mixin but lives in the shared jar's
mixin config.** As specified — `"client": ["client.PackRepositoryAccessor"]` in
`vibemod.mixins.json`.

**4. The stale guard wipes rather than diffs.** §D asked to "clear the respack
tree of mods not currently in the store". At client init *no* mod is live, so
"not in the store" and "everything" are the same set, and wiping is the stronger
and simpler statement of the same guarantee.

**5. `merge()` carries unmentioned resource files forward.** Not in the brief,
and it is a policy decision worth stating. A full-project response replaces the
Java sources outright, but keeps any `data/**`/`assets/**` file it did not
mention. The case it exists for: a mod ships a recipe, a later round fails to
compile, and the repair response contains only the one Java file it fixed —
under "the response is the project" that repair silently deletes the recipe. The
cost is that deleting a resource takes an edit round rather than an omission,
which is a trade the house "no silent drops" rule already implies. It logs when
it fires.

**6. Edit blocks key resources by full path, sources by simple name.** The
existing forgiveness for `"src/Foo.java"` is preserved for Java; a resource is
addressed by its whole path, because two `en_us.json` files in one project would
otherwise collapse onto one key and an edit would rewrite the wrong file.

**7. `PlatformProfile` gained a thirteenth component.** Same shape as Phase 0's
deviation 3, for the same reason: profile-dependent prompt text belongs in the
profile, not in a special case inside `PromptLibrary`.

**8. The smoke gate does not do an unload+reboot cycle.** §F offered it
conditionally ("if the script does one"). Instead the gate asserts the exact
thing a reboot would surface, directly and in both directions: it runs
`save-all flush` and greps the gzipped `level.dat` for the pack id — present
while the mod is live, absent after the teardown reload. That is the claim
"Missing data pack" would be evidence of, without a second three-minute boot.

**9. NeoForge gets the datapack channel and no client pack.** As §F allows. The
channel is loader-neutral and the NeoForge gate proves it end to end (44/44,
including `recipe=true` from the live manager and `Unknown function` after
teardown). `assets/**` on NeoForge are stored and reported inert with one log
line per mod, asserted.

**10. `LoaderDialogRenderer.Bindings.sanitize` now delegates to `Ids`.** §A said
"reuse/extract"; extracting it into `core` is what lets `ModResources` (core)
and the dialog renderer (loader-common) share one rule. Behaviour, fallback
included, is unchanged.

---

## Notes worth carrying into Phase 3

- **`pack_format` is not the field any more.** 26.2's `pack.mcmeta` takes
  `min_format`/`max_format` (each an int *or* a `[major, minor]` list — the
  codec is `ExtraCodecs.compactListCodec`), with the legacy `pack_format` and
  `supported_formats` still accepted by the same codec. The numbers are read at
  runtime off `DetectedVersion.BUILT_IN.packVersion(PackType)` rather than from
  `SharedConstants.DATA_PACK_FORMAT_MAJOR`, which javac would inline as a
  compile-time constant and freeze into the jar.
- **A coordinator reload rebuilds the Brigadier tree.** `reloadResources`
  constructs a new `Commands`, which fires `CommandRegistrationCallback` — so
  Phase 1's replay path now runs on every content change, not only on `/reload`.
  It works (the gate's `Replaying 2 mod command registration(s)` line is the
  proof), but it means a command-seam regression will now show up as "my recipe
  broke my command".
- **`describeState()` gained eight more keys.** All of them are `name=value`
  with names nothing else contains, and the gates use full-prefix `contains`
  checks (`clientReloads=0`, not `Reloads=0`) precisely because of Phase 1's
  substring warning.
- **The client pack is one tree with per-mod manifests.** Two mods writing the
  same `assets/<ns>/…` path cannot happen (namespaces are per-mod and canonical),
  but nothing enforces it structurally the way the datapack directory does. If
  Phase 3 ever lets a mod write into `minecraft:`, that stops being true.
- **A dedicated server still cannot serve `assets/**`.** They are stored and
  logged as inert. Pushing a server resource pack to clients needs a hosted URL
  and a hash, which is a whole feature, and it is where the "one line in the log"
  becomes a real limitation rather than a note.
- **Registry-layer datapack files are installed but deferred.** A mod shipping an
  enchantment gets its file written and a log line saying it applies on the next
  world load. Nothing re-checks that on the next world load, because the mod's
  own load path re-materializes it anyway — but the install card does not say it
  either, which it should if Phase 3 makes registry content a headline feature.

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
38 actionable tasks: 11 executed, 27 up-to-date
```

`:fabric:surgeonSelfTest` runs inside `check` and is unchanged at 38 — Phase 2
adds no seam, because resources never reach the compiler. The new assertions are
in `:core:selfTestStore` and `:core:selfTestLlm`:

```
  ok: sources() hands the compiler ONLY the java file
  ok: resources() returns both non-java files
  ok: the model's namespace was rewritten in the data path
  ok: the model's namespace was rewritten in the assets path
  ok: an id inside the body was rewritten too
  ok: minecraft: ids were left alone
  ok: a dotted translation key is NOT rewritten (java names it, and java is not)
  ok: the canonical namespace is derived from the mod name
  ok: a mod name with punctuation still yields a legal namespace
  ok: a resource-only change makes a new version
  ok: v2's resource carries the change
  ok: v1's resource is untouched by v2
  ok: resources() of a mod with none is empty
PASS: resource files round-trip, and the canonical namespace is enforced
  ok: a grid knows its size
  ok: the PNG starts with the 8-byte signature
  ok: the PNG carries IHDR, IDAT and IEND
  ok: the IHDR declares 8-bit RGBA (colour type 6)
  ok: the IHDR declares the right dimensions
  ok: every chunk's CRC checks out
PASS: the pixel-grid PNG encoder produces a CRC-valid RGBA PNG
```

```
PASS: parse() rejects a path that is neither .java nor a resource root: File path must end with .java, got: notes.txt
PASS: parse() rejects a resource path that escapes its root: Resource path segment '..' is not allowed, got: data/foo/../../evil.json
PASS: parse() rejects a resource path with no file in it: Resource path must be <root>/<namespace>/<file>, got: data/foo
PASS: parse() rejects a non-square pixel grid: assets/foo/textures/item/t.png.grid: a .png.grid must be square: row 0 is 3 characters wide but there are 2 rows
PASS: parse() rejects a pixel grid using a character it never declared: assets/foo/textures/item/t.png.grid: character 'b' (row 0, column 1) is not in the "palette"
```

### 2. `scripts/smoke-fabric.sh` — 75/75, exit 0

The Phase 2 block, verbatim:

```
== asserting on the V3 resource canary's datapack (V3 Phase 2 B/C)
  ok: a mod's data/** was materialized as a world datapack
  ok: the pack carries a manifest the running game wrote the format for
  ok: the recipe landed in the pack at its canonical namespace
  ok: the host logged the materialization
  ok: the coordinator ran a reload for it
  ok: assets/** were stored but reported inert on a dedicated server
> datapack list enabled
There are 3 data pack(s) enabled: [vanilla (built-in)], [fabric-convention-tags-v2 (Fabric mod)], [file/vibemod-resourcecanary (world)]

> rescanary
resource-canary recipe=true advancement=true

> function vibemod_resourcecanary:hello
Running function vibemod_resourcecanary:hello

  ok: the world enabled the pack (no operator ever touched it)
  ok: the recipe is in the LIVE recipe manager
  ok: the advancement is in the LIVE advancement tree
  ok: the mod's mcfunction runs
  ok: level.dat remembers the pack while the mod is live
  ok: boot restore coalesced its reloads (1 <= 2)
== asserting the datapack goes away with the mod
> vibe disable ResourceCanary
⬡ vibe ResourceCanary disabled.

  ok: disabling removed the datapack directory immediately
  ok: and a final reload was scheduled, not skipped
  ok: and that final reload finished
  ok: the watchdog never tripped on the teardown
> datapack list enabled
There are 2 data pack(s) enabled: [vanilla (built-in)], [fabric-convention-tags-v2 (Fabric mod)]

> function vibemod_resourcecanary:hello
Unknown function vibemod_resourcecanary:hello

  ok: the pack is no longer selected
  ok: and its function no longer resolves
  ok: no missing-data-pack warning was produced
  ok: and level.dat has FORGOTTEN the pack id, so a later boot cannot warn about it
== asserting the datapack comes back with the mod
> vibe enable ResourceCanary
⬡ vibe ResourceCanary enabled.

  ok: re-enabling put the datapack directory back
  ok: re-enabling put the recipe back in the live manager
  ok: nothing in the resource channel threw
== stopping server (pid 25559)

== PHASE D DEDICATED-SERVER GATE PASSED
```

`Unknown function` for a function whose file was on disk a moment ago is the
claim that is simply false unless the teardown reload really ran.

### 3. `scripts/smoke-neoforge.sh` — 44/44, exit 0

31 → 44, and every one of the thirteen new ones is the *same* assertion the
Fabric gate makes, on a host that shares not one line of Fabric code:

```
== asserting on the V3 resource canary's datapack (V3 Phase 2 F)
  ok: a mod's data/** was materialized as a world datapack on NeoForge too
  ok: the pack carries a manifest
  ok: the host logged the materialization
  ok: the coordinator ran a reload for it
  ok: assets/** were stored but reported inert (no client pack on this host)
> datapack list enabled
There are 3 data pack(s) enabled: [vanilla (built-in)], [mod_data], [file/vibemod-resourcecanary (world)]

> rescanary
resource-canary recipe=true

> function vibemod_resourcecanary:hello
Running function vibemod_resourcecanary:hello

  ok: the world enabled the pack
  ok: the recipe is in the LIVE recipe manager
  ok: the mod's mcfunction runs
  ok: disabling removed the datapack directory immediately
> datapack list enabled
There are 2 data pack(s) enabled: [vanilla (built-in)], [mod_data]

> function vibemod_resourcecanary:hello
Unknown function vibemod_resourcecanary:hello

  ok: the pack is no longer selected
  ok: and its function no longer resolves
  ok: no missing-data-pack warning was produced
== stopping server (pid 24987)

== PHASE E DEDICATED-SERVER GATE PASSED
```

### 4. `./gradlew :fabric:runClientGameTest` — 79/79, exit 0

Run for real, on this Mac's display. The Phase 2 block, verbatim:

```
  ok: the runtime resource pack was built at client init
  ok: and it started empty (the stale guard ran)
  ok: the pack registered itself in the client's PackRepository
  ok: and the client SELECTED it (required=true, so no operator has to)
  ok: the mod's files are tracked for exact cleanup (respackMods=1 respackFiles=3)
  ok: the client resource reload completed
  ok: the mod's model resolves through the game's own resource manager
  ok: its .png.grid was encoded into a real PNG the client can find
  ok: the PNG really is a PNG (signature read back off the resource)
  ok: its lang file translates a key that did not exist at client start
  ok: its data/** became a world datapack
  ok: and its recipe reached the integrated server's recipe manager
  ok: the coordinator ran a reload on each side (serverReloads=1 clientReloads=1 serverDirty=false clientDirty=false serverPending=0 clientPending=0 ownedPacks=1 lastReload=ResourceClientCanary loaded)
  ok: and it is idle afterwards (serverReloads=1 clientReloads=1 serverDirty=false clientDirty=false serverPending=0 clientPending=0 ownedPacks=1 lastReload=ResourceClientCanary loaded)
  ok: disabling forgot the mod's files
  ok: disabling removed the world datapack at once
  ok: the teardown ran a second client reload
  ok: the model is gone from the client after the teardown reload
  ok: the texture is gone too
  ok: and the translation fell back to its key
  ok: and the recipe is gone from the integrated server
  ok: the pack itself is still registered, and simply contributes nothing
  ok: the coordinator owns no packs and is idle again (serverReloads=2 clientReloads=2 serverDirty=false clientDirty=false serverPending=0 clientPending=0 ownedPacks=0 lastReload=ResourceClientCanary unloaded)
PHASE D CLIENT GATE PASSED
```

No pre-existing check was removed, weakened or skipped in any of the four. The
one pre-existing assertion whose text changed —
`Replaying 1 mod command registration` → `Replaying 2` — changed because the
number is genuinely two now: the resource canary registers a command as well.
