# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.0.0] - 2026-08-26

**A generated mod is now a normal Fabric mod.** It `implements
net.fabricmc.api.ModInitializer`, it calls `ServerTickEvents.END_SERVER_TICK.register`,
`CommandRegistrationCallback`, `KeyMappingHelper`, `HudElementRegistry` and
`Registry.register` the way every tutorial on the internet does, it ships `data/**` and
`assets/**` like a real jar — and it has **zero VibeMod imports anywhere in it**. It still
hot-loads and still hot-unloads.

Nothing about that is a bigger API. It is a *smaller* one. VibeMod stopped asking the model to
learn a bespoke wrapper and started letting it write the code it already knows, then intercepting
the handful of call sites that would otherwise be unrevocable. The Fabric prompt lost 4k
characters and gained five surfaces.

Major because the loader generated-code contract changed shape. Paper is untouched — same
plugin, same gate, same behaviour — and the v2 `VibeContext` mod flavor still compiles, still
loads, and is still what NeoForge uses.

### Added

- **The bytecode surgeon.** Every route from source to live classes runs through
  `InMemoryCompiler.compile`, so the pass is installed on the compiler rather than on any one
  code path: generation, repair rounds, `/vibe edit`, rollback and restore-on-boot are all
  covered by one hook. It walks *instructions* (not the constant pool), enforces an allowlist
  and a deny table, and rewrites seamed call sites `invokevirtual` → `invokestatic` with the
  receiver prepended, so the operand stack is unchanged and no frame is recomputed. A class with
  no seam hit comes back **byte-identical** — asserted, which is what makes the legacy corpus
  provably unaffected. A policy violation is a javac-shaped diagnostic that reaches the model
  through the existing self-heal loop.
- **Loader events.** `Event.register` is seamed into a host-owned fanout standing behind one
  permanent, process-lived subscription per `Event`. Merge rules copy the v2 bridge exactly
  (`void` all run, `boolean` AND with no short-circuit, `InteractionResult` first non-`PASS`,
  `TriState` first non-`DEFAULT`, other references first non-null). `SERVER_STARTING`/
  `SERVER_STARTED` are replayed for a mod loaded after the fact.
- **Hot Brigadier commands.** A mod registers `CommandRegistrationCallback` normally. The host
  invokes it immediately against the live dispatcher, discovers what it added by diffing the
  root, removes it again on disable, and replays it into the fresh dispatcher on every `/reload`
  and every datapack reload. Command-name collisions are journalled to the mod, not logged and
  forgotten.
- **A real client half.** `ClientModInitializer` is a tracked deferred step on the render
  thread. Keybinds come from the existing eight-slot pool (`KeyMappingHelper.registerKeyMapping`
  hands back the *slot's* mapping, so ordinary `consumeClick()` polling just works), HUD elements
  ride behind the host's single element with the same watchdog and instant-detach-on-throw, and a
  mod may subclass `Screen` — which the host closes off the player's display when the mod that
  defined it is unloaded.
- **Resources.** A mod ships the same `data/**` and `assets/**` tree a real jar would.
  `data/**` is materialized as a world datapack (staged and renamed, so a half-written pack is
  never discovered mid-write); `assets/**` joins a runtime client resource pack. One debounced
  reload per batch, per side. Textures are `.png.grid` files — a JSON palette plus rows — which
  the host encodes into real RGBA PNG with `Deflater` and `CRC32`, no `java.desktop`.
  Namespaces are rewritten to `vibemod_<modname>` in paths *and* in ids inside the bodies, so
  two mods cannot collide however they were told to name themselves.
- **Real registered content.** `Registry.register` works, for `ITEM` and `ENTITY_TYPE`, in
  singleplayer and on a LAN host. The unfreeze is a window around the mod's whole
  `onInitialize()` rather than a step inside the shim, because `Item.<init>` writes to the
  registry itself (`createIntrusiveHolder`) and therefore runs *before* the register call it is
  an argument to. Ids are namespaced, data components are bound, the creative Ingredients tab is
  rebuilt, and a registry ledger records what was registered.
- **A registry ledger with tombstones.** There is no `MappedRegistry.remove` and there was never
  going to be one, so `/vibe disable` cannot take an id back. The ledger writes that down —
  per installation, atomically, surviving a restart — instead of hiding it, and `/vibe info`
  says so on the card: *stays registered until the world is restarted*.
- **A symbol oracle.** "cannot find symbol" now goes back to the model with the real member list
  for the type it guessed at, parsed out of the formatted diagnostics so it works on both the
  javac and ECJ backends.
- **A native Fabric prompt profile** (`fabric`; the v2 one survives as `fabric-legacy`). It is
  smaller than the profile it replaced and teaches five more surfaces, because it teaches almost
  nothing: the model already knows Fabric. Every signature in its three few-shots was read off
  the jars with `javap`, and one of them is the exact file the client gate compiles and runs.
- **A "THIS HOST" prompt block.** The Fabric profile serves a client and a dedicated server, and
  several of its rules branch on which. The host now says which one it is, so the model does not
  spend a repair round rediscovering that a registered item is refused here.
- **`:fabric:surgeonSelfTest`** — a source set of its own, wired into `check`, 57 assertions. It
  compiles fixtures with the *same* compiler against the *same* classpath the host uses, then
  defines and **runs** the rewritten class against a recording shim: a rewrite that produced
  unverifiable bytecode, or pointed at a method that does not exist, fails here rather than
  passing a constant-pool check.
- **`scripts/demo-live.sh`** — the end-to-end demo driver. Not a gate: it spends real money and
  depends on a model's judgement. It boots the dedicated Fabric server, drives `/vibe make` with
  the DEMO.md prompts over RCON, and asserts generated → self-healed → live → exercised →
  deleted → no residue.

### Changed

- **`/vibe info` tells the truth about a native mod.** It used to report `listeners: 0
  tasks: 0` — counters for a kind of registration a native mod does not have — while saying
  nothing about the ones it does. It now names the entrypoints, counts loader-event
  subscriptions, lists the commands the command seam installed, and reports resource trees.
- **The gates grew rather than being replaced**: `smoke-fabric.sh` 37 → 89, `smoke-neoforge.sh`
  31 → 44, the client gate 36 → 114. That is deliberate, and it paid: Phase 3's worst bug was
  found by *Phase 2's* assertions failing.
- **`pause-when-empty-seconds=0`** in both loader smoke gates. Since 1.21.2 an empty server stops
  ticking after a minute; every tick-counting assertion in those gates had been living on
  borrowed time and one of them finally noticed.
- **The smoke gates pick the newest jar, not the lexicographically first one.** After a version
  bump the old jar is still in `build/libs` and sorts first, so `ls | head -1` would have gated
  the previous release and said nothing about it.
- **CI compiles the client-gate source sets** in the required build job. They live in their own
  source sets that nothing in `build` compiled, and the only task that pulled them in was in the
  display job, which is `continue-on-error` — so a compile error in 1200 lines of gate code
  produced a green required build.

### Fixed

- **`/vibe` and every generated command vanished on the first `/reload` on Fabric**, and
  `ctx.onChat` never fired at all. `VibeModFabric.Boot` declared instance fields that shadowed
  the statics of the same name; `wire()` assigned the shadows and the process-lived
  subscriptions read the statics, which stayed null forever. NeoForge's structurally identical
  `Boot` never declared them and never had the bug. Found by a new `/reload` assertion.
- **A refused registration poisoned every later datapack reload in the session.**
  `Item.<init>` appends to `BuiltInRegistries.DATA_COMPONENT_INITIALIZERS` before our refusal
  fires and nothing removed it, so every subsequent reload failed with `Missing element`. The
  window now snapshots and rolls back.
- **A runtime-registered entity crashed the render thread**, twice: once when spawned between
  the type's registration and the deferred client half, once on teardown while entities were
  still in the world. Registering a type now installs vanilla's `NoopRenderer` immediately and
  draining *replaces* the mod's provider rather than removing it. An invisible mob is a bug
  report; a crashed client is a lost world.
- **A creative tab that was invalidated but never rebuilt** kept offering a disabled mod's item.
- **Deleting a pack file out from under a running reload threw.** Mutations arriving during a
  reload are now held and applied when it completes.
- **The keybind pool ate the mod's own key presses**: the host's per-tick `consumeClick()` drain
  ran on leases with no `onPress`, so a mod polling its mapping never saw one.

### Not in this release, on purpose

- **Blocks.** Most of the machinery is public, but `PalettedContainerFactory` takes its global
  palette bit width from the size of `BLOCK_STATE_REGISTRY` once per world load, and every chunk
  section in the loaded world is serialized against that. Adding block states mid-session changes
  the id space under live containers — which does not necessarily throw, and that is exactly what
  makes it the wrong thing to ship. Refused by name, with the reason.
- **Registry content on a dedicated server.** A vanilla client joining later negotiates a
  registry sync without the id and would be kicked, so the host refuses deterministically rather
  than working until somebody logs in.
- **Native seams on NeoForge.** The seam table is Fabric-only; the NeoForge policy denies
  `net/fabricmc/` and a Fabric-API mod there is a compile diagnostic. NeoForge keeps the v2
  `VibeContext` path and gets the datapack channel, which is loader-neutral and gated end to end.

## [2.0.0] - 2026-08-25

VibeMod runs on **Fabric** and **NeoForge** as well as Paper, and the Paper floor drops from
1.21.8 to **1.20.6**. One codebase, three hosts, one jar per host.

Major rather than minor because the things a 1.0.0 user would notice changed: the UI is not
always dialogs any more, the plugin declares a lower API version, stored `meta.json` gains
fields, and the loader builds keep their settings in a different file format. All of those are
listed under **Changed** below.

### Added

- **Fabric and NeoForge hosts** — Minecraft 26.1+, Java 25, one jar each serving a dedicated
  server *and* a client. Generated mods are Mojang-typed and get a curated, frozen set of ten
  server hooks (`onPlayerJoin`, `onPlayerQuit`, `onServerTick`, `onChat`, `onBlockBreak`,
  `onUseBlock`, `onUseItem`, `onEntityDeath`, `onPlayerDeath`, `onRespawn`) plus the same
  commands, actions, tasks and live config knobs Paper mods have. The curation is not
  minimalism: a Fabric event cannot be unregistered, so every hook has to be host-dispatched for
  a mod to be unloadable at all.
- **Singleplayer.** On both loaders the host runs inside the integrated server, so `/vibe` in
  your own world opens a real dialog screen and everything works locally.
- **A client surface for generated mods** (loaders only) — `sdk-client`: HUD drawing (text,
  boxes, outlines, item icons) with reads for position, health, dimension, targeted block, FPS
  and world time; eight pooled keybind slots shown in **Options → Controls → VibeMod**; a
  client-tick hook; and `/vibec <mod> <command>` for client-side commands. A key lease
  auto-binds its suggested default **only** if you have never bound that slot yourself.
- **A render-thread watchdog.** A HUD renderer that throws is detached immediately rather than
  after the error-storm threshold — ten failures at sixty frames a second is ten frames, not ten
  seconds — and one that is merely slow trips the watchdog and auto-disables its mod. The client
  keeps running through both.
- **Paper 1.20.6–1.21.6 support** via a full chat UI: the same seventeen screens rendered as
  clickable chat blocks, with forms driven by typing into chat. `ui.force-chat: true` forces it
  on a newer server, which is how it gets tested.
- **ECJ as a fallback compiler** on the loader builds, bundled Jar-in-Jar, so a JRE-only install
  can still compile generated mods. Which backend was resolved is printed in the boot line.
- **`platform` / `mcVersion` / `side` on stored mods.** A mod remembers where it was generated;
  enabling a Paper-generated mod on a Fabric server is refused with a friendly message instead of
  a crash.
- **A CI matrix that runs the real gates**: build + all self-tests + an ECJ-forced pass, then a
  real dedicated server per platform and version (Paper 1.20.6 / 1.21.8 / 26.2, Fabric 26.2,
  NeoForge 26.2) driven over RCON, plus two real game clients under xvfb.
- **Third-party licences ship in the loader jars** under `META-INF/licenses/` — ECJ's EPL-2.0
  and Kyori's MIT, with a NOTICE naming every nested artifact.
- **bStats on Paper**, wired but inert: no bStats service id has been registered yet, and the
  code refuses to start without a real one rather than inventing a placeholder.

### Changed

- **The UI is not always dialogs.** On Paper **1.20.6–1.21.6** there is no dialog API, so VibeMod
  renders the chat UI instead. Every subcommand works either way; the boot log says which
  renderer it picked. Nothing changes on 1.21.7+.
- **`plugin.yml` declares `api-version: '1.20'`** (was `'1.21'`), which is what lets the plugin
  load on the new floor. Everything newer that VibeMod touches is now behind a runtime capability
  probe rather than a version comparison.
- **`meta.json` is schema v3.** Existing files are normalized on read — no `platform` becomes
  `paper`, no `mcVersion` becomes `1.21.8`, no `side` becomes `server` — and written back on the
  next save. Nothing needs migrating by hand.
- **Settings live in `config/vibemod.json` on the loaders**, not a YAML file. Same key set as
  Paper's `config.yml`, spelled in JSON; unknown keys are preserved on save.
- **Stored mods live in `<game dir>/vibemod/mods/` on the loaders** (Paper is unchanged:
  `plugins/VibeMod/mods/`).
- **Chat form input no longer reaches generated mods' `onChat` hooks on Fabric.** On NeoForge it
  never did. A line typed into a chat-rendered form is form input, not chat, and letting a
  generated mod observe somebody filling in a text field is a privacy leak as much as a cosmetic
  bug — so the two loaders now agree, on NeoForge's ordering.
- **`/vibe export` is Paper-only.** On the loaders it reports that it is not supported;
  generating standalone loader boilerplate is not on the critical path.
- **The Paper jar is built by `shadowJar`**, not a hand-rolled merge, so bStats can be relocated
  as bStats requires. `:paper:jar` is now a thin artifact that is never shipped; use
  `:paper:shadowJar` (or `scripts/build.sh`, which was updated).
- **The build needs a JDK 25** for the two loader modules. Everything else still compiles at
  `--release 21`.
- **`ARCHITECTURE.md` is now a one-page map** into `docs/ARCHITECTURE-V2.md`. The old v1/v2/v3
  frozen-contract document moved to `docs/ARCHITECTURE-V1.md` with a header saying which of its
  rules no longer hold.

### Fixed

- **Two mods compiling at the same time could lose a library off the classpath.** The
  classpath cache pruned every in-progress temp file it did not recognise, including ones another
  thread was writing — so a concurrent compile could drop ECJ or Adventure and then report that a
  package it can see does not exist. Found by the new CI matrix, on its second run.
- `fabric.mod.json` declared an icon file that has never existed in the repository.
- The eight keybind slots showed up in the Controls screen as raw translation keys; both loader
  jars now ship an `en_us.json`.
- `scripts/setup.sh` named its Paper download after a hard-coded build number it never actually
  requested, so the filename and the bytes in it could disagree; `scripts/start.sh` hard-coded
  the same stale name. Both now follow whatever build Fill reports, and honour `PAPER_VERSION`.

### Known limitations

- **bStats does nothing yet** on any platform: Paper's is wired but has no registered service id,
  and there is no bStats client for Fabric or NeoForge, so those two ship no metrics at all.
- **`/vibe export` is unsupported on the loaders**, as above.
- The **17 dialog screens have not had a screen-by-screen visual review** on a Fabric or NeoForge
  client. They are verified to construct and show, and the mapping is mechanical from the same
  screen data Paper's renderer consumes, but wrapping and layout need human eyes.
- **No VibeMod client + VibeMod dedicated server has been run at once.** Both halves are gated
  separately, and between them they cover the same code paths, but not simultaneously.
- The `java.compiler` module probe has never been observed on a **Mojang-launcher jlink
  runtime**. It reports present on every full JDK tested. If one ever reports absent, the fix is
  to bundle the `javax.tools` API classes.

## [1.0.0] - 2026-08-25

First public release.

### Added

- **Prompt-to-gameplay**: `/vibe make <prompt>` asks an LLM (via OpenRouter) for Java, compiles
  it in-process with `javax.tools`, and hot-loads it into the live Paper 1.21.8 server — each mod
  in its own child classloader with exact per-instance teardown (no restarts, no reloads).
- **Self-healing generation**: compile errors are fed back to the model for repair rounds;
  repair/edit rounds may answer with SEARCH/REPLACE edit blocks, with automatic full-project
  retry when a block fails to apply.
- **Streaming generation**: SSE-streamed completions drive a plan-aware boss bar with a live
  scrolling ticker, plus configurable generation concurrency with queue visibility.
- **Dialog-based UI**: the entire interface is native Paper dialogs with one design language —
  bare `/vibe` opens the mod browser; per-mod config (sliders/checkboxes/dropdowns), settings
  form, prompt/edit dialogs, and scrollable manual/source/error viewers with Markdown manuals.
- **Live model catalog & cost tracking**: a model picker dialog and tab completion over the full
  OpenRouter catalog, configurable reasoning effort, real usage-based cost visibility via
  `/vibe costs`, and per-version cost/requester/changelog in `/vibe history`.
- **Legible, tunable mods**: generated mods declare config knobs read live (no reload),
  model-written player manuals extended with introspected verified facts, and install cards
  with clickable actions.
- **Debuggability**: runtime errors are deduped into per-mod logs (`/vibe errors`); a throwing
  mod is marked degraded but keeps running; `/vibe fix` sends recent errors to the model for a
  surgical repair round; `/vibe debug` echoes a mod's logging live to ops.
- **Safety nets**: a tick-time watchdog auto-disables mods that stall the main thread, an
  error-storm detector auto-disables mods that throw repeatedly, and `/vibe panic` unloads
  every mod at once.
- **Version history & rollback**: every generation is a stored version; browse and re-activate
  any of them, or `/vibe rollback` one step.
- **Jar export**: `/vibe export` emits a genuine standalone Paper plugin jar (with seeded
  config.yml and readable source tree) that boots on a plain server.
- **Runtime /commands**: mods can register real top-level commands, unregistered cleanly on
  disable via swappable handlers.
- **Live reload**: `/vibe reload` re-reads config.yml (model, timeouts, watchdog budgets,
  retries, concurrency, error-storm thresholds) without a restart.

[3.0.0]: ../../releases/tag/v3.0.0
[2.0.0]: ../../releases/tag/v2.0.0
[1.0.0]: ../../releases/tag/v1.0.0
