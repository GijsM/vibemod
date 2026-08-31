# Phase 4 result — docs, a live demo that cost money, and the matrix

All five deliverables (A–E) landed. The live-LLM step **was executed**: a real OpenRouter key
resolved through the host's own order, a real dedicated Fabric server, two real `/vibe make`
runs, $0.31 of somebody's money. It found three things, all of them fixed here, and the
re-run passed 37/37.

No new capabilities were added. Every code change below is either something a live run
revealed or something an audit found unprotected.

---

## The headline: what the live demo found

This is the part worth reading. Everything else is bookkeeping.

### 1. The prompt never said which side of the game it was running on

`scripts/demo-live.sh 1` on a **dedicated server**, first run. The model produced this:

```java
public final class RubySword implements ModInitializer {
    @Override
    public void onInitialize() {
        Identifier rubyId = Identifier.fromNamespaceAndPath(NAMESPACE, "ruby");
        ResourceKey<Item> rubyKey = ResourceKey.create(Registries.ITEM, rubyId);
        ruby = Registry.register(BuiltInRegistries.ITEM, rubyId,
                new Item(new Item.Properties().setId(rubyKey)));
        …
    }
}
```

That is **textbook-correct 26.2 Fabric code**: `setId` before construction, `ResourceKey`
built the right way, the canonical namespace, exactly what the `RubySword` few-shot teaches.
The model did nothing wrong. The host refused it:

```
WARNING: Refusing registry content from RubySword on a dedicated server:
  registry content is singleplayer/LAN-host only in v1
```

The Fabric profile serves a client *and* a dedicated server, and several of its rules branch
on which — `assets/**` render or are inert, `ClientModInitializer` runs or is skipped, a
registered item works or is refused. The prompt stated **both branches** and left the model to
pick. So it picked, was refused, and spent a full repair round (real money, ~40 seconds of a
progress bar) rediscovering something the host had known since boot.

**Fix.** `PromptLibrary.systemPrompt(profile, hostFacts)` — a `THIS HOST` block ahead of the
frozen API, fed by a new `ModGenerator.setHostFacts(...)` (mirroring `setSymbolOracle`), whose
text lives in `PlatformProfiles.fabricHostFacts(boolean)` because it is prompt content, and
whose boolean comes from `platform.isDedicatedServer()`. It is in the **system** prompt rather
than the request because it is constant for the life of a host, which is what keeps it
cacheable. `null` or blank reproduces the old prompt byte for byte, asserted for all five
profiles, so no host that supplies nothing pays for this.

**Result.** On the re-run the same prompt produced a working mod in **one round, no repair**.

### 2. A recipe that failed silently

The same first run wrote a smelting recipe with a 1.20-era ingredient:

```json
{"type": "minecraft:smelting", "ingredient": {"item": "minecraft:redstone"}, …}
```

and got the *shaped* recipe right, because the few-shot shows one. The datapack loader's
verdict:

```
Couldn't parse data file 'vibemod_rubysword:ruby' from '…/recipe/ruby.json':
  No key fabric:type in MapLike[{"item":"minecraft:redstone"}];
  Second: Not a string: …; Second: Not a json array: …
```

The mod loaded. The install card said installed. `/vibe errors` was empty. The recipe simply
was not there, and the only way a player finds out is by trying to craft it. **This is the
worst failure mode in the system** — worse than a compile error, which self-heals — and no
fixture would ever have produced it, because every fixture recipe in this repo was copied from
vanilla's own data.

Verified off the jar rather than recalled — `Ingredient.CODEC` is:

```
61: getstatic  NON_AIR_HOLDER_SET_CODEC        // HolderSetCodec.create(Registries.ITEM, Item.CODEC, false)
64: invokestatic ExtraCodecs.nonEmptyHolderSet
77: invokeinterface Codec.xmap
```

A `HolderSetCodec` accepts a plain id string, a `#tag` string, or a JSON array. Never an
object. The runtime error and the disassembly say the same thing.

**Fix.** The profile now states the general rule where it previously only demonstrated the
shaped case:

```
* AN INGREDIENT IS A STRING, NOT AN OBJECT. You may remember
  `{"item": "minecraft:redstone"}`; 26.x REJECTS it. Everywhere a recipe takes
  one (`ingredient` in smelting/blasting/smoking/campfire/stonecutting, each
  value of a shaped `key`, each entry of a shapeless `ingredients`) write
  `"minecraft:redstone"`, a tag `"#minecraft:planks"`, or an array of either.
  This does NOT fail your build: the recipe is silently dropped as the pack
  loads and the mod looks fine until a player tries to craft it.
```

**Result.** Zero `Couldn't parse data file` lines in the re-run, against one before.

*Recorded honestly, not smoothed over:* the host still does not **surface** vanilla's datapack
parse errors. They happen on a worker thread inside the game's own reload with no return
channel, and catching them means attaching a log handler to vanilla's reload — a bigger surface
than a hardening phase should add. The prompt now prevents the common case; the gap is real and
is written down in ARCHITECTURE-V3 §9 as an accepted limitation rather than being quietly
dropped.

### 3. The oracle was blind to the shape a *second* repair round produces

Prompt (2), round three, verbatim:

```
[ERROR] …/HomeCooldown.java:62 - no suitable method found for
  teleportTo(ServerLevel,double,double,double,Set<Object>,float,float)
    method net.minecraft.server.level.ServerPlayer.teleportTo(double,double,double) is not applicable
    method net.minecraft.server.level.ServerPlayer.teleportTo(ServerLevel,double,double,double,
      Set<Relative>,float,float,boolean) is not applicable
```

`SymbolOracle` parsed **only** "cannot find symbol" shapes. This is not one. Yet it is the
*most* tractable diagnostic in the whole system — the owner and the method name are both right
there on the continuation line — and it is exactly what a second round looks like once the
model has stopped guessing the name and started guessing the arguments.

**Fix.** Two new patterns (javac's `method Owner.x(…) is not applicable`, ECJ's
`The method x(…) in the type Y is not applicable`) and a `Why` verdict on each parsed miss.
The verdict is not cosmetic: for an absent member the honest sentence is *"this type has no
such member, here are near names"*; for a signature mismatch that sentence is **a lie**, and
telling a model it has no `teleportTo` when it just wrote a working `teleportTo` name is how
one repair round becomes two. A signature miss gets its own header and lists **every real
overload, exact-name only, shortest argument list first** — the fuzzy neighbour list is
actively unhelpful there.

Both wordings are self-tested against the verbatim text above (with `VibeContext.repeat`
standing in for `ServerPlayer.teleportTo`, since that is the only overloaded method on the
test runtime), including an assertion that the hint does **not** claim the method is missing.

*Claimed carefully:* the repair prompt is not logged, so I cannot show that the hint is what
made round three converge. What is true: the shape appeared, the new path matches it verbatim,
and round three took one edit block and went live.

### 4. The harness was faster than a player, and the coordinator was right

The first version of `demo-live.sh` asserted "a data reload ran for it" immediately after
generation and deleted the mod one second later — well inside the coordinator's 40-tick
debounce. The load and the unload coalesced into a **single** reload that correctly reported
the mod as *unloaded*:

```
00:20:09  Datapack vibemod-rubysword materialized with 3 file(s)
00:20:10  Removed /rubyinfo with mod RubySword
00:20:12  Reloading server data (RubySword unloaded), 2 pack(s) selected
```

Nothing was broken except the harness. Recorded because it is the coalescing path working,
tested by accident, and because the honest fix was to make the harness wait (`await_pack_live`)
rather than to widen an assertion.

---

## What landed, by deliverable

### A. `docs/ARCHITECTURE-V3.md`

New, 11 sections, in ARCHITECTURE-V2's voice and rigour. Contains all seven required parts:

1. **The decision log** — twelve numbered, locked decisions: seam architecture over a bespoke
   API, the hook on the compiler rather than a code path, instruction-walk over pool-scan, the
   shape-preserving rewrite, byte-identical pass-through, the unfreeze window around
   `onInitialize` (with the intrusive-holder finding), tombstones over lies, the block refusal
   (`PalettedContainerFactory`'s bit width), the dedicated-server registry refusal, "no silent
   drops" as the house rule, `ModDispatch`/`ModAttribution` threading, and the Fabric-only seam
   table.
2. **The verified-facts table** — 22 rows, each with how it was established (`javap`, a
   disassembly excerpt, or the gate that produced it). Includes every correction the phases
   made to their own briefs: `KeyMappingHelper` (not `KeyBindingHelper`), `CreativeModeTabEvents`
   (not `ItemGroupEvents`), `identifier()` (not `location()`), `min_format`/`max_format` (not
   `pack_format`), no `SwordItem` at all — plus the two the live demo added.
3. **The surgeon spec** — the policy allowlist (9 roots), the deny table (12 entries with the
   text each gives the model), the four-plus-one bootstrap allowlist and why
   `ConstantBootstraps` is on exactly one of the two lists, and **all seventeen seams with their
   descriptors**, taken from `FabricSeams.table()` rather than from memory.
4. **Shim semantics** — the permanent-subscription model, the fanout merge table per return
   type, thread guards, commands (invoke/diff/undo/replay), the eight-slot keybind mapping, HUD
   adaptation and what is dropped, entrypoints, screen hygiene, the registry window with its
   snapshot/rollback, the ledger and its tombstones, resources, and the reload coordinator.
5. **The teardown matrix** — 13 capabilities × (disable / unload / documented residue). The
   through-line is stated: *VibeMod can revoke every path it stands in, and no path it does not.*
6. **What the gates found** — the ten real bugs from Phases 0–3, plus the two the live demo
   added, plus the `LoadingOverlay` finding about the harness itself.
7. **Out of scope** — 12 entries, each with the mechanism rather than a preference, so nobody
   "helpfully" adds blocks, dedicated-server registry sync, NeoForge seams or mixins.

Plus §9, which resolves or explicitly accepts every "notes worth carrying" item from the four
RESULT docs (see D below), and §11, a gate inventory.

### B. README / CHANGELOG / DEMO.md

- **README** — new lead paragraph stating the thesis; the "What generated mods can do" section
  split into *On Fabric: a generated mod is a normal Fabric mod* (with the real limits stated
  rather than implied) and *On NeoForge and Paper: the v2 contract* (accurate: NeoForge keeps
  `VibeContext` **and** gets the loader-neutral datapack channel, but not the client pack, and
  the native seams are Fabric-only); "Why mods aren't plugins" gained a subsection describing
  the surgeon, its three safety properties and `surgeonSelfTest`; the manuals bullet now says
  what verified facts a *native* mod reports; the gates section gained the compile-only CI step
  and `demo-live.sh` with a plain statement that it is not a gate.
- **`ARCHITECTURE.md`** — updated too, because its rule 3 ("Generated mods never register with
  the platform directly") is precisely what V3 inverted, and its map named one architecture
  document where there are now two.
- **CHANGELOG** — a 3.0.0 entry: Added (the surgeon, events, commands, the client half,
  resources, registered content, the ledger, the oracle, the native profile, the host-facts
  block, `surgeonSelfTest`, `demo-live.sh`), Changed (the `/vibe info` correction, the growing
  gates, `pause-when-empty-seconds`, newest-jar selection, the CI compile step), Fixed (the six
  real bugs), and **"Not in this release, on purpose"** with the reason for each.
- **DEMO.md** — rewritten around the six required prompts. Each carries: the verbatim
  `/vibe make` line, what 2.0 could not do, the seams and channels it uses (named from
  `FabricSeams.java`), the expected file shape at the canonical namespace, and — the part that
  matters — **what `/vibe disable` takes away and what it does not**. The limits that apply to
  more than one prompt are stated once up front. The repair-round section carries the live
  demo's real diagnostics. The Paper record is preserved as an appendix rather than deleted.

Version bumped to **3.0.0** in `build.gradle.kts`, `plugin.yml`, `fabric.mod.json` and
`neoforge.mods.toml`.

### C. The live runs

Executed. Key resolved in the host's own order — the harness deliberately leaves
`openrouter.api-key` **blank** in the run directory's config and exports `$OPENROUTER_API_KEY`,
so the run proves the environment branch and leaves no key on disk (asserted both ways).

`scripts/demo-live.sh` is new: boots the same dedicated Fabric server the Phase D gate boots,
from the same installed jar and the same download cache, drives `/vibe make` over RCON, and
asserts the whole arc. It is documented as **not a gate** — it spends money and depends on a
model's judgement.

Three runs happened. The first two are the evidence above; the third is the record.

| Run | Prompt (1) ruby sword | Prompt (2) /home + HUD timer |
| --- | --- | --- |
| 1 (before fixes) | registry refused → **1 repair round** → components fallback; recipe **silently dropped** | 2 compile rounds, then killed for a harness fix |
| 2 (host facts in) | **1 round, no repair** | 2 compile rounds → live, 13/13 |
| 3 (final) | **1 round, no repair**, 20/20 | **3 repair rounds** → live, 15/15 |

**Run 3, prompt (1) — `RubySword`.** One round. A native mod (asserted: no `com.gijsm.vibemod`
anywhere in its sources), shipping 3 datapack files and 4 `assets/` files, registering
`/rubysword`. Datapack materialized, went live 3 s later, `datapack list enabled` showed
`file/vibemod-rubysword` with no operator ever touching it. Deleted → store gone, directory
gone, pack deselected, teardown reload completed, no missing-data-pack warning.

**Run 3, prompt (2) — `HomeCooldown`.** Three repair rounds, each a different kind of wrong:
round 1 four `cannot find symbol` (a renamed class, a moved method), answered with **4 edit
blocks** rather than a rewritten project; round 2 one left; round 3 the `teleportTo` overload
mismatch above, answered with **1 edit block**, then live. Registered **two** commands
(`/sethome`, `/home`) through the command seam; both disappeared on disable and both came back
on enable; both gone for good after delete. Its `ClientModInitializer` half was skipped, and
the host said so.

Verbatim host log from run 3:

```
INFO: Mod RubySword registered /rubysword
INFO: Datapack vibemod-rubysword materialized with 3 file(s)
INFO: RubySword ships 4 assets/ file(s); this host has no client resource pack, so they are
      stored but inert here (models, textures and lang need a physical client)
INFO: Reloading server data (RubySword loaded), 3 pack(s) selected
INFO: Removed /rubysword with mod RubySword
INFO: Reloading server data (RubySword unloaded), 2 pack(s) selected
INFO: Mod HomeCooldown registered /sethome
INFO: Mod HomeCooldown registered /home
INFO: Generated HomeCooldown v1 after 3 repair round(s)
INFO: Removed /sethome with mod HomeCooldown
INFO: Removed /home with mod HomeCooldown
```

Total session cost across the two generations: **$0.31**.

**Not executed, stated plainly:** the client-side variant of prompt (1) on `runClient` (texture
rendering verified by eye). `:fabric:runClientGameTest` is a scripted gametest harness with no
`/vibe make` step and no key, and adding a live LLM call to a gate is the opposite of what a
gate is for. The client *mechanism* is fully gated without an LLM — the client gate asserts a
mod's `.png.grid` becomes a real PNG the game's own `ResourceManager` resolves, that its
two-file item model resolves, and that its lang key translates — so what went untested is the
combination "a model wrote it **and** it rendered", not either half.

### D. Audit + polish

**Prompt budget.** `LlmSelfTest` printed every profile already; it now also prints and asserts
the number **as sent** (profile + host-facts block), which is the only number a generation
actually pays for, and prints the repair prompt bare and with a deliberately oversized hint
block. Adding §1 and §2's prompt text blew the 30k budget (30492), which is what a budget is
for. Phase 2's rule — *the next thing added to this prompt has to take something out* — was
honoured rather than the number raised: five passages were tightened (the ingredient block
itself, the now-redundant half of the registry side-limit bullet, the `assets/**` bullet, a
rename-table clause listing names that did **not** change, the keybind manual sentence). No
assertion was weakened; every phrase the existing checks look for is still present.

```
  paper-modern     22977 chars  ~  5744 tokens  (2 few-shots)
  paper-legacy     23980 chars  ~  5995 tokens  (2 few-shots)
  fabric           29450 chars  ~  7362 tokens  (3 few-shots)
  fabric-legacy    33773 chars  ~  8443 tokens  (3 few-shots)
  neoforge         33778 chars  ~  8444 tokens  (3 few-shots)
  fabric+host      29970 chars  ~  7492 tokens  (as SENT by a dedicated server)
  fabric+host      29707 chars  ~  7426 tokens  (as SENT by a client)
  repair prompt     6888 chars bare,   10319 chars with hints
```

**`/vibe info` on a native mod — it did not, and now it does.** The audit found it reporting

```
commands: none
actions: none
listeners: 0  tasks: 0
```

for a mod with three event subscriptions and a registered command. All three lines were wrong
or vacuous: `listenerCount()`/`taskCount()` count a kind of registration a native mod does not
have by construction, `nativeCount()` and `contentCount()` existed and were never read, and
`CommandSeam` tracked its commands for teardown but never called `handle.trackCommandName`.
Nothing recorded the entrypoints at all — `FabricEntrypointAdapter` computed `common`/`client`
and discarded them.

Four minimal changes:

- `ModHandle.entrypoints()` / `noteEntrypoints(String)` — a plain `String`, filled in by the
  host, because the names belong to a loader `core` must not import.
- `FabricEntrypointAdapter` records it before anything runs, so a mod whose client half was
  skipped or failed still reports both entrypoints. It is a fact about the class, not about how
  far activation got.
- `CommandSeam.install` calls `trackCommandName(name)` for each literal it discovers. Dropped
  by `ModHandle.drain()` with everything else; a reload re-adds and the method de-duplicates.
- `InstallCard.verifiedFactLines` branches: native mods get `entrypoints:` and
  `event subscriptions:`, `VibeContext` mods keep `listeners: … tasks: …` **byte-identically**,
  and `resource trees:` appears for either kind when non-zero (omitted rather than printed as
  zero, which would be a claim about a feature the mod did not use).

Asserted in the live demo (both prompts) and confirmed unchanged for Paper — the 1.21.8 gate's
`/vibe info SmokeCanary` still prints `commands: smokeping / actions: ping / listeners: 1
tasks: 1` with no new line.

*Accepted, recorded in ARCHITECTURE-V3 §9:* the **player-facing** mod hub renders store data
only and shows no live introspection for any mod, native or not; the verified facts are one
click away under "Manual". Fixing the fact lines fixed both places facts are *claimed*. Putting
them on the hub body is a UI change, not a correctness one, and out of scope for a phase with
"no new capabilities" in its brief.

**The notes sweep.** All four RESULT docs' "notes worth carrying" are now resolved or explicitly
accepted with reasons in ARCHITECTURE-V3 §9. Addressed: the `/vibe info` gap, the oracle gap,
the CI gap, the jar-selection hazard, and `describeState()`'s substring caveat (which now has a
written home instead of living in one phase's notes). Accepted with reasons: the boolean-merge
`TRUE` default, `CommandBuildContext`'s server lifetime, `ClientSeam`'s client-type hygiene, the
registration window's shared-JVM race, unbound tags created during the window, the client pack's
single tree, `assets/**` on dedicated servers, vanilla's unsurfaced datapack parse errors, the
hub body, the ungated `ctx.onChat`, and `InstallCard`'s static hook.

**CI.** The audit found one real gap, and it is not a task that fails to run — it is a **source
set nothing compiles**. `fabric/src/gametest` (1200 lines, including the `NativeCanary` that is
the game-side counterpart to the surgeon self-test) and `neoforge/src/clientgate` are pulled in
only by their run tasks, which live in the `client-gates` job, which is `continue-on-error`
because it needs a display. A plain compile error in either produced a **green required build**
and a yellow advisory job. One step added to the required job:

```yaml
      - name: Compile the client-gate sources
        run: ./gradlew :fabric:compileGametestJava :neoforge:compileClientgateJava --stacktrace
```

Verified to run green in 751 ms. Everything else checked out: `:fabric:surgeonSelfTest` is under
`check` (confirmed against `--dry-run`, not grep), all five `:core:selfTest*` run under `build`,
`selfTestEcj` has its own explicit step, and all four gate scripts are in the matrix.

**One hazard fixed on the way past.** Both loader smoke gates picked their jar with
`ls … | head -1` — *lexicographically* first. Bumping the version to 3.0.0 left
`vibemod-fabric-2.0.0.jar` in `build/libs`, and "2.0.0" sorts before "3.0.0", so the gates would
have tested **the previous release** and said nothing about it. Now `ls -t`, newest first, with
the reason in a comment.

### E. Gates

Full matrix below. `smoke-paper.sh 1.21.8` is untouched-green: V3 adds no Paper code, the Paper
host passes no surgeon (null is a pass-through), and the one shared file changed this phase
(`InstallCard`) is byte-identical in its output for a `VibeContext` mod, which the gate's own
`/vibe info` transcript shows.

---

## Files changed

| File | Why |
| --- | --- |
| `core/.../llm/PromptLibrary.java` | `systemPrompt(profile, hostFacts)` overload |
| `core/.../llm/PlatformProfiles.java` | `fabricHostFacts(boolean)`; the ingredient rule; the `RubyCharm`→`RubySword` correction; five budget trims |
| `core/.../gen/ModGenerator.java` | `setHostFacts(...)`, used at both `systemPrompt` call sites |
| `core/.../compile/SymbolOracle.java` | the overload-mismatch shape, on both backends' wordings |
| `core/.../runtime/ModHandle.java` | `entrypoints()` / `noteEntrypoints(...)` |
| `core/.../ui/InstallCard.java` | native-aware verified facts |
| `fabric/.../VibeModFabric.java` | supplies the host facts |
| `fabric/.../FabricEntrypointAdapter.java` | records the entrypoints |
| `fabric/.../shim/CommandSeam.java` | names its commands on the handle |
| `core/src/test/java/LlmSelfTest.java` | +30 assertions: as-sent budget, host-facts equivalence, the repair-prompt budget, the ingredient rule, the oracle's new shape |
| `scripts/demo-live.sh` | **new** — the live demo driver |
| `scripts/smoke-fabric.sh`, `smoke-neoforge.sh` | newest-jar selection |
| `.github/workflows/build.yml` | compile the client-gate source sets |
| `build.gradle.kts`, `plugin.yml`, `fabric.mod.json`, `neoforge.mods.toml` | 3.0.0 |
| `docs/ARCHITECTURE-V3.md` | **new** |
| `README.md`, `CHANGELOG.md`, `DEMO.md`, `ARCHITECTURE.md` | rewritten / updated |
| `.gitignore` | `fabric/demo/` |

Nothing was committed. Nothing was pushed.

---

## Deviations from the brief, and why

**1. `/vibe unload` does not exist; the command is `/vibe delete <mod> confirm`.** §C asks for
"`vibe unload` → zero residue". The router's subcommand list has no `unload`; the path that
runs `lifecycle.unload(...)` **and** removes the stored mod — the one that tombstones registry
ids, which is what §C is actually asking about — is `cmdDelete`. The demo asserts against that.

**2. The demo drives the installed jar, not `./gradlew :fabric:runServer`.** §C named
`runServer`. The smoke gates deliberately test the **installed** jar rather than Loom's dev
classpath, because that is the only way the Jar-in-Jar half is exercised (on the dev classpath
Adventure and ECJ are plain classpath entries; in the shipped jar they are nested and must be
found through the loader and materialized by the cpcache before javac can read them). Reusing
that recipe means the demo tests what a user installs, shares the download cache, and needed no
second boot path. It is still a headless dedicated server driven over RCON, which is what the
requirement is for.

**3. The client half of prompt (1) was not run.** Stated in §C above rather than glossed: the
client gate has no `/vibe make` step, and putting a live LLM call inside a gate is the opposite
of what a gate is for. Both halves of the claim are gated separately.

**4. Host facts are supplied on Fabric only.** Paper and NeoForge pass nothing, so their prompts
are byte-identical to before (asserted for all five profiles). Their profiles do not branch on
the side they run on — Paper is always a dedicated server, and the NeoForge profile's
`ctx.client()` story already says what it needs to. Adding a block for them would be prompt
churn with no question behind it.

**5. `fabricHostFacts` lives in `PlatformProfiles`, not in the Fabric host.** The first cut put
it in `VibeModFabric`. It moved because it is prompt *text*: keeping it with the prompts is what
lets `LlmSelfTest` budget the real worst case rather than the profile alone, which is precisely
the deliverable-D question. The host supplies only the boolean.

**6. The budget assertion got stricter, not looser.** The brief's baseline is "prompt budget
fabric ≤30k". The profile alone is 29450 and would have passed unchanged. The new assertion is
on the number **as sent**, which is larger and is the one a generation pays for. It is at 29970
— tight, and deliberately not relieved by raising the ceiling.

---

## Gate results

All five run on this machine, on the final tree. Verbatim tails.

### 1. `./gradlew build`

```
> Task :core:selfTest
> Task :core:test SKIPPED
> Task :core:check
> Task :core:build

BUILD SUCCESSFUL in 6s
38 actionable tasks: 38 executed
```

### 2. `scripts/smoke-fabric.sh` — 89/89, exit 0

```
  ok: and it is journalled as an onInitialize failure, not a crash
  ok: and the refused mod is not live
== stopping server (pid 39319)

== PHASE D DEDICATED-SERVER GATE PASSED
   log: /Users/gijsmulder/projects/vibemine/.claude/worktrees/v3-anything-engine/fabric/smoke/boot.log
   rcon transcript: /Users/gijsmulder/projects/vibemine/.claude/worktrees/v3-anything-engine/fabric/smoke/rcon.log
```

### 3. `scripts/smoke-neoforge.sh` — 44/44, exit 0

```
There are 2 data pack(s) enabled: [vanilla (built-in)], [mod_data]

> function vibemod_resourcecanary:hello
Unknown function vibemod_resourcecanary:hello

  ok: the pack is no longer selected
  ok: and its function no longer resolves
  ok: no missing-data-pack warning was produced
== stopping server (pid 39612)

== PHASE E DEDICATED-SERVER GATE PASSED
   log: /Users/gijsmulder/projects/vibemine/.claude/worktrees/v3-anything-engine/neoforge/smoke/boot.log
   rcon transcript: /Users/gijsmulder/projects/vibemine/.claude/worktrees/v3-anything-engine/neoforge/smoke/rcon.log
```

### 4. `scripts/smoke-paper.sh 1.21.8` — exit 0, untouched

```
⬡ vibe SmokeCanary disabled.

> smokeping
Unknown command: /smokeping

> vibe enable SmokeCanary
⬡ vibe SmokeCanary enabled.

== skipping the player phase (no mineflayer, or it does not speak 1.21.8)
== stopping server (pid 39735)
== server stopped; log at /Users/gijsmulder/projects/vibemine/.claude/worktrees/v3-anything-engine/paper/run/smoke-1.21.8/boot.log, rcon transcript at /Users/gijsmulder/projects/vibemine/.claude/worktrees/v3-anything-engine/paper/run/smoke-1.21.8/rcon.log
```

### 5. `./gradlew :fabric:runClientGameTest` — 114/114, exit 0

```
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

### And the one that is not a gate: `scripts/demo-live.sh 1 2` — 37/37, exit 0

```
  ok: /vibe info names the loader entrypoints it implements
  ok: /vibe info lists the command the seam installed for it
  ok: disabling removed the command it registered
  ok: re-enabling put the command back
== deleting demo 2 and asserting zero residue
  ok: the store no longer holds HomeCooldown
  ok: its world datapack directory is gone
  ok: the world no longer has its datapack selected
  ok: /vibe list no longer knows it
  ok: its command is gone from the dispatcher for good
== generation rounds, from the host's own log
WARNING: Mod compile round failed:
WARNING: Mod compile round failed:
WARNING: Mod compile round failed:
== cost, from /vibe costs
> vibe costs
VibeMod — costs:Session spend: $0.31


== V3 PHASE 4 LIVE DEMO PASSED
== stopping server (pid 37793)
```

No pre-existing check was removed, weakened or skipped in any of them.
