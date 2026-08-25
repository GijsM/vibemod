# VibeMod

Vibe-code Minecraft gameplay from inside the game. Describe a mod like
`/vibe make "sheep can fly"`, and VibeMod asks an LLM for Java, **compiles it in-process**, and
hot-loads the result into the running game. No restarts, no external services, prompt-to-gameplay
in seconds.

```
/vibe make when a creeper dies a chicken spawns at its location with a poof
  Thinking → Writing → Compiling → Loading
  ✔ ChickenCreepers v1 installed
```

**Three platforms, one project.** VibeMod ships as a Paper plugin, a Fabric mod and a NeoForge
mod, built from one codebase. On the two loaders it runs on a dedicated server *and* in
singleplayer, and generated mods can put things on your own HUD and bind your own keys.

**On Fabric, a generated mod is just a Fabric mod.** It implements `ModInitializer`, registers to
real fabric-api events, adds real Brigadier commands, ships `data/` and `assets/` like a real jar,
and can register real items — with **no VibeMod imports anywhere in it**. It still hot-loads and
hot-unloads, because the host intercepts the handful of call sites that would otherwise be
unrevocable, in bytecode, on the way out of the compiler. See
[Why mods aren't plugins](#why-mods-arent-plugins) and [`DEMO.md`](DEMO.md).

## Security model — read this first

VibeMod compiles LLM-generated Java and loads it into your game's JVM with **full privileges**.
There is **no sandboxing** — no SecurityManager, no bytecode filtering, no import allow-list at
load time. This is by design: sandboxing generated gameplay code convincingly is not a solved
problem, and pretending otherwise would be worse than being honest about it. A generated mod can,
in principle, do anything your game process can do — read files, open sockets, all of it.

What that means in practice:

- **Only trust your ops.** Every `/vibe` subcommand is gated behind `vibe.use` (read-only) or
  `vibe.admin` (everything else), and both default to **op**. Anyone who can run `/vibe make` can
  effectively execute arbitrary code on the host. Grant these permissions accordingly — i.e. to
  people you would give a shell.
- **On singleplayer, that person is you.** Running VibeMod in a singleplayer world means an LLM
  writes code that runs on *your* machine with *your* user's privileges.
- **Mitigations that do exist** are about stability and control, not containment: each mod runs in
  its own child classloader with exact per-instance teardown; a tick-time **watchdog**
  auto-disables mods that stall the main thread (and, on loaders, a second watchdog for the render
  thread); an **error-storm** detector auto-disables mods that throw repeatedly; and `/vibe panic`
  is a kill switch that unloads every mod at once.
- **Run it on servers you can afford to lose.** A disposable or private server is the right home
  for this — not a production server with irreplaceable worlds or other players' data.
- The dev scripts are locked down by default: `scripts/setup.sh` generates a **unique RCON
  password** (stored in `scripts/.rcon-password`, gitignored) and binds the server to
  **127.0.0.1**, so nothing is reachable from outside the machine.
- The dev default is `online-mode=false` for quick local testing with no authentication.
  **Reconsider that for anything non-local** — on an offline-mode server anyone can join under
  any name, including an op's.

## Supported versions

| Platform | Versions | UI | Client features | `/vibe export` |
|---|---|---|---|---|
| **Paper** | 1.21.7 – 26.x | native dialogs | — | yes |
| **Paper** | 1.20.6 – 1.21.6 | chat fallback | — | yes |
| **Fabric** | 26.1+ (built against 26.2) | native dialogs | HUD, keys, `/vibec` | no |
| **NeoForge** | 26.1+ (built against 26.2) | native dialogs | HUD, keys, `/vibec` | no |

Java: **25** on Fabric and NeoForge (Minecraft 26.x requires it). **21+** on Paper.

A full JDK is preferred everywhere. If you only have a JRE, the loader builds still work — they
bundle the Eclipse compiler (ECJ) as a fallback backend — but the Paper plugin does **not** bundle
ECJ (Paper servers run a JDK), so Paper needs a real `javac`. VibeMod prints which compiler
backend it resolved in its boot line either way.

**Not supported, and not planned:** Spigot and CraftBukkit (Paper-only APIs throughout), Folia,
Paper below 1.20.6, Fabric or NeoForge below 26.1 (VibeMod relies on the game shipping official
Mojang names, which starts at 26.1), and legacy Forge. Chest/anvil GUIs, registry content
(items, blocks) in generated mods, mixins in generated code, and VibeMod-to-VibeMod networking
are out of scope on every platform.

## Install

Download the jar for your platform from [GitHub Releases](../../releases):
`VibeMod-<version>-paper.jar`, `-fabric.jar` or `-neoforge.jar`.

Then, on any platform, give it an [OpenRouter](https://openrouter.ai) API key. Resolution order is
the same everywhere: the config file → `$OPENROUTER_API_KEY` → `~/.config/vibemod/openrouter.key`.

### Paper

1. Drop `VibeMod-<version>-paper.jar` into `plugins/`.
2. Run the server on a full **JDK 21+** — not a bare JRE. VibeMod needs the in-process compiler
   and the Paper build does not bundle a fallback.
3. Boot once; edit `plugins/VibeMod/config.yml` for the API key.
4. Op yourself and run `/vibe make something fun`.

On 1.21.7+ the whole UI is native dialogs: bare `/vibe` opens the mod browser, and config,
manual, source, errors, history and settings are all dialog screens. On **1.20.6 – 1.21.6** there
is no dialog API, so VibeMod falls back to a **chat UI** — the same screens rendered as clickable
chat blocks, with forms driven by typing into chat. Every subcommand works either way; the boot
log says which renderer it picked. You can force the chat UI on a newer server with
`ui.force-chat: true` in `config.yml`, which is how the fallback gets tested.

### Fabric

1. Install **Fabric Loader 0.19.3+** for Minecraft **26.1+** and put
   [Fabric API](https://modrinth.com/mod/fabric-api) in `mods/`.
2. Drop `VibeMod-<version>-fabric.jar` into `mods/`. ECJ and Adventure ride along inside it
   (Jar-in-Jar); you do not install anything else.
3. Boot once; the config file is `config/vibemod.json` — same keys as Paper's `config.yml`, in
   JSON.
4. Permissions: `vibe.admin` becomes the node **`vibemod:admin`** with an op-level-2 fallback, and
   `vibe.use` the node **`vibemod:use`** with a fallback of "everyone". On a server with LuckPerms
   they are granted by node; on a vanilla-ish server, by op level. VibeMod never learns which.

Stored mods live in `<game dir>/vibemod/mods/`.

**Singleplayer works.** Open a world, run `/vibe`, and you get a real dialog screen — the same 17
screens the dedicated server pushes, rendered by your own client. The host runs inside the
integrated server, so everything is local.

### NeoForge

1. Install **NeoForge for Minecraft 26.1+**.
2. Drop `VibeMod-<version>-neoforge.jar` into `mods/`. As on Fabric, ECJ and Adventure are nested
   inside the jar.
3. Config is `config/vibemod.json`, stored mods are `<game dir>/vibemod/mods/`, permissions work
   the same way — the two loaders share about two thirds of their host code, so the differences
   are essentially the install step and nothing else.

NeoForge needs no Fabric-API-equivalent and VibeMod ships **no mixins** on it: NeoForge patches the
dialog-click packet handler itself and posts an event with the player attached, so the one mixin
the Fabric build needs has no counterpart here.

## Client features (Fabric and NeoForge only)

On a loader, a generated mod can ask for a client surface. This is the one genuinely new
capability the loader builds add over the Paper plugin.

- **HUD** — a mod can draw text, boxes, outlines and item icons on your screen, and read player
  position, health, dimension, targeted block, FPS and world time. A HUD renderer that **throws**
  is detached immediately (not after a storm threshold — ten failures at sixty frames a second is
  ten frames) and its mod is marked degraded, with the client still running. A HUD renderer that
  is merely **slow** trips a render-thread watchdog and the mod is auto-disabled. Both of those
  are covered by the client gates, not just asserted here.
- **Keybinds** — eight pooled key slots, shown in **Options → Controls** under the category
  **VibeMod** as "Mod key 1" … "Mod key 8". A mod leases the lowest free slot and may suggest a
  default key; the suggestion is auto-bound **only** if you have never bound that slot yourself,
  so a rebind you made always wins. Disabling the mod hands the slot back and unbinds only what
  VibeMod bound.
- **`/vibec <mod> <command> [args]`** — client-side commands a mod registers. One static command
  root whose suggestions read the live registry, so it works as mods come and go.

On Paper, `ctx.hasClient()` is `false` and `ctx.client(...)` is a no-op — the same generated source
compiles everywhere, it just does less.

## What generated mods can do, per platform

### On Fabric: a generated mod is a normal Fabric mod

Since 3.0.0 the model is not asked to learn anything. It writes what every Fabric tutorial on the
internet writes — `implements net.fabricmc.api.ModInitializer`, `ServerTickEvents
.END_SERVER_TICK.register(...)`, `CommandRegistrationCallback`, `KeyMappingHelper`,
`HudElementRegistry`, `Registry.register` — with **zero VibeMod imports anywhere in the file**.
The result still hot-loads and still hot-unloads.

- **Any Fabric event.** `Event.register` on anything fabric-api exposes. Merge semantics follow
  the event's return type (`boolean` events AND without short-circuiting, `InteractionResult`
  takes the first non-`PASS`, and so on).
- **Real Brigadier commands**, live the instant the mod loads — no `/reload` — and gone again when
  it is disabled. They survive `/reload` and every datapack reload, because the host replays them
  into the fresh dispatcher.
- **A real client half.** `ClientModInitializer`: keybinds out of the eight-slot pool, HUD
  elements, client events, and `Screen` subclasses the host closes off your display when the mod
  is unloaded.
- **`data/**` and `assets/**`, like a real jar.** Recipes, advancements, loot tables, functions
  and tags become a world datapack; models, textures and language files join a runtime client
  resource pack. Both are live within a couple of seconds and both are gone on disable. Textures
  are written as a small JSON pixel grid (a palette plus rows) and the host encodes the PNG.
- **Really registered content** — items and entity types, in `BuiltInRegistries`, with data
  components bound, in the creative Ingredients tab, craftable from the mod's own recipe.

**The limits are real and the host states them rather than failing quietly:**

- Registering items or entity types works in **singleplayer and on a LAN host**. On a dedicated
  server it is refused and the mod fails to load, because a vanilla client joining later would
  negotiate a registry sync without the id and be kicked. The prompt teaches the alternative
  (components on a vanilla item in the recipe result), and the host now *tells* the model which
  side it is on, so it does not have to find out the expensive way.
- **Blocks are refused**, with the reason: chunk sections are serialized against a palette whose
  bit width is fixed from the block-state registry when the world loads.
- `/vibe disable` cannot take a registry **id** back — there is no `MappedRegistry.remove`. The
  install card says so, and a ledger records it.
- No mixins, no reflection, no threads, no networking. Mod code runs on the server thread so the
  watchdog means something.
- Native mods have **no config knobs** — there is no `ctx` to read one from — so the prompt tells
  the model to use named constants and not to promise settings in the manual.

### On NeoForge and Paper: the v2 contract

Both still use `com.gijsm.vibemod.api.Mod` + `VibeContext`, unchanged:

- **On Paper**, mods are Bukkit-typed and get the *entire* Bukkit event system through
  `ctx.listen(listener)`, plus commands, actions, tasks and live config knobs.
- **On NeoForge**, mods are Mojang-typed and get a **curated, frozen set of ten server hooks** —
  `onPlayerJoin`, `onPlayerQuit`, `onServerTick`, `onChat`, `onBlockBreak`, `onUseBlock`,
  `onUseItem`, `onEntityDeath`, `onPlayerDeath`, `onRespawn` — plus the same commands, actions,
  tasks and config knobs, plus the client surface above. The list is curated rather than open for
  the same reason the Fabric seams exist: a loader event cannot be unregistered, so every hook has
  to be host-dispatched for a mod to be unloadable at all. NeoForge mods also get the **datapack
  channel** (`data/**` becomes a world datapack, exactly as on Fabric — that half of the design
  names no loader), but not the client resource pack.

  The native seams are **Fabric-only for now**. A NeoForge mod that reaches for
  `net.fabricmc.*` gets a compile diagnostic, not a crash.

A mod remembers which platform it was generated on (`meta.json` records `platform`, `mcVersion`
and `side`). Enabling a Paper-generated mod on a Fabric server is refused with a friendly message
rather than a crash — `/vibe make` again is the migration path.

`/vibe export`, which emits a standalone drop-in plugin jar, is **Paper-only**. On the loaders it
reports that it is not supported: exporting a standalone loader mod means generating loader
boilerplate, and it is not on the critical path.

## Legible, tunable, fixable mods

Every generated mod documents and exposes itself. The whole UI is screens — native dialogs
wherever the platform has them, chat blocks on Paper 1.20.6–1.21.6:

- **Config knobs** — mods declare tunable settings (type, default, min/max/step, description).
  Change them via the config screen (`/vibe config <mod>`: sliders, checkboxes, dropdowns) or
  `/vibe set <mod> <key> <value>`; both apply **instantly** — mods read config live, no reload.
- **Manuals** — the model writes a player guide (Markdown, rendered in a scrollable screen);
  VibeMod appends *verified facts* it introspects itself. For a `VibeContext` mod those are its
  real commands, actions, listener and task counts and current knob values; for a native Fabric
  mod they are the loader entrypoints it implements, its loader-event subscription count, the
  commands the seam installed, the resource trees it owns, and any registry ids it holds (with
  the honest note that those outlive `/vibe disable`). `/vibe manual <mod>` opens it,
  `/vibe info <mod>` opens the mod hub.
- **Self-healing generation** — compile errors are fed back to the model automatically; repair
  and edit rounds may return SEARCH/REPLACE edit blocks instead of whole files (blocks that fail
  to apply trigger an automatic full-project retry).
- **Degraded → fix loop** — a mod that throws at runtime is marked *degraded* (it keeps running),
  its errors are deduped into a per-mod log (`/vibe errors <mod>`), and `/vibe fix <mod>` sends
  the recent errors to the model for a surgical repair round. Error storms auto-disable the mod.
- **Streaming progress** — generations stream over SSE and drive a plan-aware boss bar with a
  live ticker; `/vibe costs` shows what generation has cost, per mod, from real OpenRouter usage
  data, and `/vibe model` tab-completes the full live OpenRouter model catalog.
- **Version history** — every generation is a new version with its own changelog, cost, and
  requester; `/vibe history <mod>` browses and re-activates any of them, `/vibe rollback` is the
  one-step shortcut.
- **`/vibe reload`** — re-reads the config live (model, timeouts, watchdog budgets, retries,
  concurrency, error-storm thresholds).

## Commands

Identical on all three platforms unless the last column says otherwise.

| Command | What it does | |
|---|---|---|
| `/vibe` | The front door: opens the mod browser (help text on console) | |
| `/vibe make <prompt>` | Generate + hot-load a new mod (argless: opens a prompt form) | |
| `/vibe edit <mod> <prompt>` | Evolve a mod — the model sees the current source; new version hot-swaps | |
| `/vibe again <mod>` | Fresh take on the mod's last prompt | |
| `/vibe list` | All mods with state dots, click-through to details | |
| `/vibe info <mod>` | Mod hub: description, usage hint, verified facts, action buttons | |
| `/vibe manual <mod>` | The model-written player guide + introspected facts | |
| `/vibe source <mod>` | The generated Java in a scrollable screen (console: chat dump) | |
| `/vibe config <mod>` / `set <mod> <key> <value>` | Tune a mod's knobs — applies live | |
| `/vibe errors <mod>` | A mod's deduped runtime error log | |
| `/vibe fix <mod>` | Send recent errors to the model for a repair round | |
| `/vibe debug <mod> [on\|off]` | Echo a mod's ctx.log()/exceptions live to ops | |
| `/vibe history <mod>` | Browse and re-activate previous versions | |
| `/vibe rollback <mod>` | Instantly back to the previous version | |
| `/vibe enable/disable <mod>` | Exact hot teardown / re-load | |
| `/vibe export <mod>` | Standalone Paper plugin jar + readable source tree | **Paper only** |
| `/vibe do <mod> <action>` | Invoke a mod-declared action | |
| `/vibe book [mod]` | Prompt/edit form for drafting an idea or change request | |
| `/vibe chat` | Chat mode: plain chat lines become prompts ("off" to stop) | |
| `/vibe model [id]` | Show/set the OpenRouter model (default `anthropic/claude-sonnet-5`); tab-completes the live catalog | |
| `/vibe costs` | Session + per-mod generation costs | |
| `/vibe settings` | Settings screen (model picker, budgets, reload) | |
| `/vibe reload` | Re-read the config live | |
| `/vibe panic` | Kill switch: unload every mod now | |
| `/vibec <mod> <command>` | A mod's client-side command | **loaders, client only** |

### Standalone exports — what changes

`/vibe export <mod>` produces a genuinely self-contained Paper plugin (the VibeContext API classes
and a standalone context implementation are bundled), verified to boot on a plain Paper server
with VibeMod absent. A few things necessarily differ from running under VibeMod:

- **Saved data starts fresh** — a standalone mod's data folder is `plugins/<ModName>/`, not
  VibeMod's `plugins/VibeMod/moddata/<ModName>/`; copy files over manually if you want the state.
- **No watchdog or error quarantine** — VibeMod's tick-time watchdog and error-storm auto-disable
  don't travel with the export; a misbehaving mod behaves like any other misbehaving plugin.
- **Actions get an umbrella command** — `/vibe do <mod> <action>` becomes `/<modname> <action>`
  (the enable log prints the exact label if the name had to be namespaced).
- **Config is file-based** — knobs are tuned by editing the exported `config.yml` and reloading,
  not live via `/vibe config`.

## Why mods aren't plugins

Runtime *plugin* loading is unsupported on modern Paper — reloading a plugin id throws
`Provider attempted to add duplicate plugin identifier`, and the Paper team has said unload/reload
will not be supported ([discussion #10561](https://github.com/PaperMC/Paper/discussions/10561)).
The loaders are no better: Fabric and NeoForge both resolve their mod list once at launch. So
generated mods are **not** plugins or mods in the platform's sense: each is compiled in memory and
loaded into its own child classloader under VibeMod's identity, with every listener, task, command
and client registration tracked per mod. That makes load/unload/reload genuinely instant and exact
on all three platforms.

### The surgeon: how a Fabric mod can be a Fabric mod and still be unloadable

Tracking registrations works when the mod asks *you* to register something. It does not work when
the mod calls the loader directly — and a Fabric `Event` has no unsubscribe at all. Until 3.0.0
the answer was to forbid it: generated mods got a curated hook list and were banned from touching
`net.fabricmc.*`.

3.0.0 inverts that. A **bytecode pass** runs on the compiler — `InMemoryCompiler.compile`, which
every route from source to live classes goes through, so generation, repair rounds, `/vibe edit`,
rollback and restore-on-boot are covered by one hook — and rewrites seventeen specific call sites
to point at host shims instead:

- `Event.register` → a host-owned fanout standing behind one permanent, process-lived
  subscription per event. The mod is an entry behind it, and entries can be removed.
- `CommandRegistrationCallback` → invoked immediately against the live dispatcher, with what it
  added *discovered* by diffing the dispatcher root rather than declared.
- `KeyMappingHelper`, `HudElementRegistry` → the existing pooled key slots and the host's single
  HUD element.
- `Registry.register`, `Item$Properties.setId`, `EntityType$Builder.build` and friends →
  namespaced, ledgered, refusable writes inside a registration window.

Three properties make this safe rather than clever. The rewrite is **shape-preserving**
(`invokevirtual` → `invokestatic` with the receiver prepended, so the operand stack is unchanged
and no frame is recomputed). A class that hits no seam is returned **byte-identical**, which is
asserted — that is what makes the older `VibeContext` mods provably unaffected by a pass that runs
over them. And a **policy** (an allowlist of package roots plus a deny table covering reflection,
threads, networking, mixins and loader internals) turns anything out of bounds into a javac-shaped
diagnostic that goes back to the model through the ordinary self-heal loop.

`:fabric:surgeonSelfTest` compiles fixtures with the same compiler against the same classpath the
host uses, then **defines and runs** the rewritten classes against a recording shim — so a rewrite
that produced unverifiable bytecode, or pointed at a method that does not exist, fails there
rather than passing a constant-pool check and exploding in someone's world.

The full design, including every seam's descriptor, the teardown matrix and the list of things
deliberately not built, is in [`docs/ARCHITECTURE-V3.md`](docs/ARCHITECTURE-V3.md).

## Building

Requirements: a **JDK 25** (the loader modules require it; the rest of the build compiles at
`--release 21` and the Gradle toolchain resolver fetches a 21 by itself if the machine has none).
Node.js is optional and only used by the dev scripts. The build uses the committed Gradle wrapper.

```bash
./gradlew build          # every module, every self-test, the fixture corpus
./gradlew selfTest       # just the self-tests
./gradlew selfTestEcj    # the compile-heavy self-tests with the ECJ backend forced
```

Artifacts:

| | |
|---|---|
| `paper/build/libs/VibeMod.jar` | the Paper plugin (`:paper:shadowJar`) |
| `fabric/build/libs/vibemod-fabric-<version>.jar` | the Fabric mod (`:fabric:build`) |
| `neoforge/build/libs/vibemod-neoforge-<version>.jar` | the NeoForge mod (`:neoforge:build`) |

There is no test framework: the tests are plain `main()` classes wired as `selfTest*` JavaExec
tasks, and `check` depends on them. `StoreSelfTest` additionally recompiles a checked-in
**fixture corpus** (`core/src/test/resources/corpus`) against the live API, and — if you point it
at one with `-Pvibemod.modsDir=<path>/plugins/VibeMod/mods` — a real stored-mod corpus too.

## Development

### The gates

Three scripts boot a **real dedicated server**, install the freshly built jar next to a pre-seeded
canned mod, and drive the whole compile → load → command → config → unload flow over RCON with
assertions on every reply. They need no LLM and no API key, and they run headless. These are the
acceptance gates, and CI runs all of them:

```bash
./gradlew build
scripts/smoke-paper.sh 1.21.8      # or 1.20.6 (chat UI) or 26.2
scripts/smoke-fabric.sh
scripts/smoke-neoforge.sh
```

They deliberately test the **installed jar**, not the dev classpath — on Loom's and ModDevGradle's
dev classpaths Adventure and ECJ are plain classpath entries, while in the shipped jar they are
nested and have to be found through the loader before the compiler can read them.

Two more gates drive a **real game client** and therefore need a display (or xvfb):

```bash
./gradlew :fabric:runClientGameTest    # fabric-client-gametest: real world, real GL
scripts/clientgate-neoforge.sh         # a self-driving mod, since NeoForge has no harness
```

They are `continue-on-error` in CI until they have passed a few times in a row, but their
*compilation* is required — they live in their own source sets that a plain `build` does not
touch, so CI compiles them explicitly.

And one thing that is **not** a gate:

```bash
scripts/demo-live.sh          # needs $OPENROUTER_API_KEY or ~/.config/vibemod/openrouter.key
```

It boots the same dedicated Fabric server and drives `/vibe make` with the [`DEMO.md`](DEMO.md)
prompts against a real model, asserting generated → self-healed → live → exercised → deleted → no
residue. It spends real money and depends on a model's judgement, so a bad run is not necessarily
a regression — but it is the only thing in the repo that tests **the prompt**, and it has already
paid for itself twice (a recipe shape that failed silently, and a prompt that never said which
side of the game it was running on).

### The Paper dev server

`scripts/setup.sh`, `start.sh`, `stop.sh`, `build.sh`, `deploy.sh`, `rcon.sh` and `logtail.sh` are
**Paper-only dev helpers** for hand-testing against one local server under `server/`. They are not
the gates.

```bash
./scripts/setup.sh     # downloads Paper (sha256-verified), writes eula.txt and server.properties,
                       # generates scripts/.rcon-password, installs rcon-client
./scripts/build.sh     # ./gradlew :paper:shadowJar → server/plugins/VibeMod.jar
./scripts/start.sh     # boots the server on 127.0.0.1:25565
./scripts/rcon.sh 'vibe make zombies explode into fireworks'
./scripts/stop.sh
```

`PAPER_VERSION` selects the line: `PAPER_VERSION=1.20.6 ./scripts/setup.sh` for the supported
floor, `26.2` for the newest. For a dev server on any platform with no shell scripts involved,
`./gradlew :paper:runServer`, `:fabric:runServer` and `:neoforge:runServer` also work.

## Architecture

A Gradle multi-module build. `sdk` / `sdk-client` / `platform-api` / `core` are platform-free;
`paper`, `fabric` and `neoforge` are hosts, and `loader-common` is the Mojang-typed host code the
two loaders share.

```
sdk/            api/ — Mod + VibeContext, the contract generated code writes against.
                Two flavors, same FQCNs: src/main (Bukkit-typed, Paper) and
                src/mod (Mojang-typed, both loaders)
sdk-client/     api/client/ — HUD/keybind/tick/command contract. Pure JDK, no game types
platform-api/   platform/ — the host SPI (scheduler, events, commands, messaging, compiler,
                classpath, platform probe) + the platform-neutral screen model
core/           the engine, and it names no platform at all: LLM client, prompt library,
                in-memory compiler, mod store, screen builders, both renderers, /vibe routing
loader-common/  the ~2700 lines of host code Fabric and NeoForge share verbatim — the dialog
                renderer, the mod host, the command bridge, the text/audience adapters and the
                whole client surface below each loader's own hooks. A shared SOURCE directory,
                not a module, so each loader compiles it against its own patched game jar
paper/          the Paper host + native dialog renderer. Produces VibeMod.jar
fabric/         the Fabric host (Loom). One jar, server + client entrypoints
neoforge/       the NeoForge host (ModDevGradle). One jar, no mixins
```

Two architecture documents, both authoritative for their own half:

- **`docs/ARCHITECTURE-V3.md`** — the seam architecture: the decision log, the table of 26.2 facts
  each one was read off the jars with `javap`, the surgeon's policy and all seventeen seams, the
  shim semantics, the **teardown matrix** (what `disable` and `delete` each take away and what
  documented residue remains), what every gate found, and the out-of-scope list with the mechanism
  behind each refusal.
- **`docs/ARCHITECTURE-V2.md`** — everything V3 did not touch: the SPI, the screen model, the two
  sdk flavors, the compilation pipeline, the client design, and a phase-by-phase record of what
  landed and what was deferred.

`ARCHITECTURE.md` is a short overview that points into both. `DEMO.md` has six prompts that were
impossible before 3.0.0, with the seams each one uses. `docs/phases/` holds the four V3 phase
briefs and the result document each one produced.

## Third-party code

VibeMod is MIT. The **loader** jars bundle two libraries, unmodified and unrelocated, with their
licences under `META-INF/licenses/` in the jar:

- **Eclipse JDT Core Batch Compiler (ECJ)** — [EPL-2.0](https://www.eclipse.org/legal/epl-2.0/).
  The compiler backend of last resort, so a JRE-only install can still compile generated mods.
- **adventure**, **examination** and **option** (KyoriPowered) — MIT. VibeMod's text layer on
  every platform; Paper provides it, the loaders do not, so it travels with them.

The **Paper** jar bundles **bStats** (MIT), relocated to `com.gijsm.vibemod.bstats`. Metrics are
currently inert — no bStats service id has been registered — and when they are switched on, the
opt-out is bStats' own `plugins/bStats/config.yml`.

## License

[MIT](LICENSE) — © 2026 Gijs Mulder.
