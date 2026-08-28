# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **The Paper floor is 1.20, not 1.20.6.** No code changed; the claim did. A version sweep across
  real dedicated servers found VibeMod fully functional on **twenty consecutive Paper versions,
  1.20 through 26.2** — compile in-process, hot-load, every command answering, disable/enable
  clean. 2.0.0 shipped the floor as 1.20.6 and was four releases too conservative about its own
  code. The 2.0.0 entry below is left as written: it is the record of what was claimed and tested
  then, not a claim about today.
- **What stops Paper below 1.20 is a declaration, not a capability.** 1.19.4, 1.19.2, 1.18.2,
  1.17.1 and 1.16.5 all refuse identically with
  `InvalidPluginException: Unsupported API version 1.20`, read straight out of
  `api-version: '1.20'` in `plugin.yml`, before any VibeMod code runs. Lowering it is a real
  project rather than a one-line edit — below 1.20, Bukkit's `Commodore` would have to rewrite
  legacy calls for real, which needs a Java 17 retarget — and it is not done.
- **Purpur 26.2 and Leaf 26.2 are verified working, unmodified.** Same jar, same profile, same
  UI, all assertions green, driven through the same gate via `SMOKE_LABEL`/`SMOKE_SERVER_JAR`.
  Folia still refuses to load (no `folia-supported` in `plugin.yml`) and Spigot/CraftBukkit still
  cannot work as built (no bundled Adventure; `getCommandMap()` and `AsyncChatEvent` are
  Paper-only). Neither is claimed.
- **The CI smoke matrix gates five Paper lines instead of three**, and gates them with
  `scripts/sweep-paper.sh` rather than `scripts/smoke-paper.sh`. `paper 1.20` (the real floor) and
  `paper 26.1.2` are new: the whole 1.21.9 → 26.1.2 band, which is where a new Minecraft release
  lands, had never been gated at all. The sweep wrapper is what makes the Paper gates *assert* —
  `smoke-rcon.py` prints replies and checks none of them, so the old gates could go green with
  every answer wrong. Each Paper line now names the JDK it runs on, because at least one Paper
  line cannot be tested on JDK 25 (its bundled spark SIGSEGVs the JVM).

### Documented

- **The 125 `Commodore` errors on Paper 1.20**, in the README's Paper section. Paper 1.20 bundles
  ASM 9.4, which cannot read the plugin's Java 21 bytecode (`Unsupported class file major version
  65`); CraftBukkit catches each failure, falls back to the original bytes, and the plugin works.
  Harmless **on 1.20 specifically, because no rewrite was actually needed** — not a general
  guarantee that a failed `Commodore` pass is safe.
- **The `api-version` invariant**, as a comment at every site that declares it: it governs legacy
  data conversion, not which API exists, and raising it to signal a minimum supported version
  makes Paper refuse the plugin outright. This has confused people once already.

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

[2.0.0]: ../../releases/tag/v2.0.0
[1.0.0]: ../../releases/tag/v1.0.0
