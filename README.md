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
| **Paper** | 1.20 – 1.21.6 | chat fallback | — | yes |
| **Purpur**, **Leaf** | 26.2 (verified: Purpur build 2627, Leaf build 89); other lines untested | as Paper | — | yes |
| **Folia** | 26.2 (verified — VibeMod itself; generated mods are heavily restricted, see below) | native dialogs | — | no |
| **Fabric** | 26.1+ (built against 26.2) | native dialogs | HUD, keys, `/vibec` | no |
| **NeoForge** | 26.1+ (built against 26.2) | native dialogs | HUD, keys, `/vibec` | no |

**Paper 1.20 through 26.2 is twenty measured versions, and every one of them has been
measured**, not inferred. "Supported" here has one meaning and it is a high bar: a real dedicated
server of that exact version booted the shipped jar, VibeMod compiled and hot-loaded a mod
in-process, the mod's command answered, a config knob applied live, and disable/enable removed and
restored the command cleanly. Anything that has not cleared that bar is called untested below,
not supported.

**Two counts are in play in this range and both are correct: 21 and 20.** Paper publishes **21
`paper-api` artifacts** between 1.20 and 26.2, but only **20 of them are gateable server
versions**. The odd one out is **Paper 1.20.3**, which has a `paper-api` artifact and **no server
build** — the Fill v3 API 404s it and the legacy v2 API is gone (HTTP 410) — so 1.20.3 was
**NOT RUN** and is not claimed as passing. That is why `paper/api-jars/` holds 21 jars and
[docs/API-VOCABULARY.md](docs/API-VOCABULARY.md) has 21 columns while the sweep covers 20. (1.21.2
is a third kind of gap: Paper never published it at all, so the twenty are twenty *releases*, not
twenty consecutive patch numbers.)

Purpur 26.2 (build 2627) and Leaf 26.2 (build 89) clear the same bar **unmodified** — same jar,
same profile, same UI, every assertion green. They are Paper forks and VibeMod never asks which
fork it is on, only what the server can do, so other Paper forks are likely to work too; "likely"
is not "verified", and only 26.2 has actually been run.

**Folia 26.2 passes the same gate too**, and that claim always travels with its limits, because
they are severe — see [Folia](#folia) below. VibeMod itself is correct on Folia; the mods it
generates are substantially restricted there.

Java: **25** on Fabric and NeoForge (Minecraft 26.x requires it). **21+** on Paper — and JDK 25
works on every measured Paper version **except the Paper 1.21 base release**, whose bundled spark
SIGSEGVs the JVM on 25. Run that one line on JDK 21. (1.20 on 25 is fine; so is 1.21.1 upward.)

A full JDK is preferred everywhere. If you only have a JRE, the loader builds still work — they
bundle the Eclipse compiler (ECJ) as a fallback backend — but the Paper plugin does **not** bundle
ECJ (Paper servers run a JDK), so Paper needs a real `javac`. VibeMod prints which compiler
backend it resolved in its boot line either way.

**Not supported:**

- **Spigot and CraftBukkit** — structural, not a policy. The shipped jar bundles no Adventure and
  24 source files import `net.kyori.adventure`; `Bukkit.getCommandMap()` and `AsyncChatEvent`
  are Paper-only and the chat UI rides on both. It would not load usefully, so it is not offered.
- **Paper below 1.20** — refused by the plugin's own `api-version: '1.20'` declaration, with
  `InvalidPluginException: Unsupported API version 1.20`, before a single line of VibeMod runs.
  This is a **declaration, not a measured incapability**: 1.19.4, 1.19.2, 1.18.2, 1.17.1 and
  1.16.5 were each tried and each gave the identical refusal, so nothing in the plugin was ever
  given the chance to fail. Lowering the declaration is a real project (Bukkit's `Commodore`
  would then have to rewrite legacy calls for real, which needs a Java 17 retarget) rather than
  a one-line edit, and it is not done.
- **Fabric or NeoForge below 26.1** — VibeMod relies on the game shipping official Mojang names,
  which starts at 26.1. And legacy Forge.

Chest/anvil GUIs, registry content (items, blocks) in generated mods, mixins in generated code,
and VibeMod-to-VibeMod networking are out of scope on every platform.

### Folia

`plugin.yml` ships `folia-supported: true`, and **Folia 26.2 passes the full gate** — boot,
compile in-process, hot-load, every command answering, a config knob applied live, disable/enable
clean, native dialogs. VibeMod itself is correct there. **The mods it generates are not equally
well served, and the limits below are severe enough to be part of the claim rather than a
footnote:**

- **Every mod callback is pinned to the global region.** `ctx.repeat` and `ctx.later` run on the
  global-region scheduler, which forgoes Folia's per-region parallelism — most of the reason to
  run Folia in the first place.
- **A global-region task cannot reliably touch the world.** Measured on a real Folia server:
  reading a block from a global-region task throws `IllegalStateException`, and
  `world.getEntities()` **silently returns empty** — the worse of the two failures, because
  nothing complains. Much of the stored mod corpus does world work from `ctx.repeat` and would
  misbehave rather than error.
- **Event handlers still arrive on region threads** and are deliberately not hopped to the global
  region. That is the design, not an oversight.
- **`/vibe export` jars will not run on Folia.**

So: run VibeMod on Folia if you want VibeMod on Folia. Do not expect a generated mod that walks
entities or edits blocks on a timer to behave there the way it does on Paper.

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
manual, source, errors, history and settings are all dialog screens. On **1.20 – 1.21.6** there
is no dialog API, so VibeMod falls back to a **chat UI** — the same screens rendered as clickable
chat blocks, with forms driven by typing into chat. Every subcommand works either way; the boot
log says which renderer it picked. You can force the chat UI on a newer server with
`ui.force-chat: true` in `config.yml`, which is how the fallback gets tested.

#### Paper 1.20: the wall of `Commodore` errors at boot

On **Paper 1.20 specifically**, the server logs exactly **133 errors** while loading VibeMod,
each one a failure to convert a class, with `Unsupported class file major version 65` at the
bottom of the stack. The plugin then enables normally and everything works. Both halves of that
sentence are true and the errors can be ignored. The count is 133 on JDK 21 and 133 on JDK 25 —
it is a property of the server's bundled ASM, not of the JDK running it. One of the 133:

```
[14:49:46 ERROR]: Fatal error trying to convert VibeMod v2.0.0:com/gijsm/vibemod/VibeMod.class
java.lang.IllegalArgumentException: Unsupported class file major version 65
	at org.objectweb.asm.ClassReader.<init>(ClassReader.java:199) ~[asm-9.4.jar:9.4]
```

What is happening: `plugin.yml` declares `api-version: '1.20'`, which is *below* the running
server, so CraftBukkit runs each of VibeMod's classes through **`Commodore`**, its legacy-API
rewriter. Paper 1.20 bundles **ASM 9.4**, which predates Java 21 and cannot parse class file
major version 65 — which is exactly what VibeMod is compiled to. So `Commodore` throws on every
class it is handed, CraftBukkit catches each failure, **falls back to the original unmodified
bytes**, and the class loads as written.

Why that is harmless *here*: VibeMod calls nothing that needs rewriting. The fallback bytes are
the bytes the plugin wanted in the first place, so nothing is lost. **This is specific to 1.20,
and it is not a general guarantee** — it holds because no rewrite was actually needed, not
because failed rewrites are safe. On a server old enough that a rewrite *would* be required, the
same failure would silently produce a broken class instead of a working one. That is the reason
the `api-version` floor is not simply lowered.

These 133 errors do **not** trip the sweep's `vibemod-exception` assertion, and that is now
checked rather than hoped: **0 lines match**, on both JDKs. The assertion looks for
`[VibeMod…]` followed by `Exception` or `Error`; CraftBukkit prints the plugin name here as bare
text (`VibeMod v2.0.0:`) while the only bracketed field on the line holds the timestamp
(`[14:49:45 ERROR]:`), so the required prefix never appears. This had been written down as an
unverified worry — that a wall of errors might be silently failing the gate's own assertion. It is
now measured, and unfounded.

Why you do not see it on the versions above 1.20: Paper bumped its bundled ASM past the Java 21
barrier — 1.20.6 ships ASM 9.7 — so `Commodore` reads the bytecode fine, finds nothing to change,
and says nothing. The exact build where the bump landed has not been pinned down; what has been
observed is that 1.20 emits the errors and no gated version above it does.

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

The generated-code contract (`com.gijsm.vibemod.api.Mod` + `VibeContext`) has the same class names
on all three platforms but two flavors, and the host puts the right one on the classpath:

- **On Paper**, mods are Bukkit-typed and get the *entire* Bukkit event system through
  `ctx.listen(listener)`, plus commands, actions, tasks and live config knobs.
- **On Fabric and NeoForge**, mods are Mojang-typed (`net.minecraft.*`) and get a **curated,
  frozen set of ten server hooks** — `onPlayerJoin`, `onPlayerQuit`, `onServerTick`, `onChat`,
  `onBlockBreak`, `onUseBlock`, `onUseItem`, `onEntityDeath`, `onPlayerDeath`, `onRespawn` — plus
  the same commands, actions, tasks and config knobs, plus the client surface above. The list is
  curated rather than open because a Fabric event cannot be unregistered: every hook has to be
  host-dispatched for a mod to be unloadable at all, so a curated list is the honest surface.
  Mods may not touch `net.fabricmc.*` / `net.neoforged.*`, mixins, screens or registry content;
  the prompt says so and the compiler catches attempts.

A mod remembers which platform it was generated on (`meta.json` records `platform`, `mcVersion`
and `side`). Enabling a Paper-generated mod on a Fabric server is refused with a friendly message
rather than a crash — `/vibe make` again is the migration path.

`/vibe export`, which emits a standalone drop-in plugin jar, is **Paper-only**. On the loaders it
reports that it is not supported: exporting a standalone loader mod means generating loader
boilerplate, and it is not on the critical path.

## Legible, tunable, fixable mods

Every generated mod documents and exposes itself. The whole UI is screens — native dialogs
wherever the platform has them, chat blocks on Paper 1.20–1.21.6:

- **Config knobs** — mods declare tunable settings (type, default, min/max/step, description).
  Change them via the config screen (`/vibe config <mod>`: sliders, checkboxes, dropdowns) or
  `/vibe set <mod> <key> <value>`; both apply **instantly** — mods read config live, no reload.
- **Manuals** — the model writes a player guide (Markdown, rendered in a scrollable screen);
  VibeMod appends *verified facts* it introspects itself (real commands/actions/listeners +
  current knob values). `/vibe manual <mod>` opens it, `/vibe info <mod>` opens the mod hub.
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
canned mod, and drive the whole compile → load → command → config → unload flow over RCON. They
need no LLM and no API key, and they run headless. These are the acceptance gates, and CI runs all
of them:

```bash
./gradlew build
scripts/smoke-paper.sh 1.21.8      # or 1.20 (the floor, chat UI) or 26.2
scripts/sweep-paper.sh 1.20 1.21.8 26.2   # the same gate, but asserted, one verdict per version
scripts/smoke-fabric.sh
scripts/smoke-neoforge.sh
```

`smoke-paper.sh` proves the server booted and the canned mod went live, and it **prints** the RCON
replies — but it does not check them, so on its own it can go green while every answer is wrong.
`sweep-paper.sh` is the wrapper that adds the missing half: it reads the transcript back, requires
eight specific replies, requires the boot log to have reached "VibeMod ready" with no VibeMod
stack trace in it, and records which UI the plugin picked. It emits one PASS/FAIL line per version
into `paper/run/sweep-results.tsv`, honours `JAVA_HOME`, and takes `<label>=<mc-version>=<jar>`
arguments so a Paper **fork** runs the same protocol:

```bash
JAVA_HOME=/path/to/jdk21 scripts/sweep-paper.sh 1.20 1.20.6 1.21.8
scripts/sweep-paper.sh purpur-26.2=26.2=/path/to/purpur-26.2.jar
```

CI uses the sweep for every Paper line, which is why the Paper gates assert and the fork claims
above are checkable.

They deliberately test the **installed jar**, not the dev classpath — on Loom's and ModDevGradle's
dev classpaths Adventure and ECJ are plain classpath entries, while in the shipped jar they are
nested and have to be found through the loader before the compiler can read them.

Two more gates drive a **real game client** and therefore need a display (or xvfb):

```bash
./gradlew :fabric:runClientGameTest    # fabric-client-gametest: real world, real GL
scripts/clientgate-neoforge.sh         # a self-driving mod, since NeoForge has no harness
```

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

`PAPER_VERSION` selects the line: `PAPER_VERSION=1.20 ./scripts/setup.sh` for the supported
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

`docs/ARCHITECTURE-V2.md` is the real architecture document: the SPI, the screen model, the two
sdk flavors, the compilation pipeline, the client design, and a phase-by-phase record of what
actually landed and what was deliberately deferred. `ARCHITECTURE.md` is a short overview that
points into it. `DEMO.md` has a verified test transcript.

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
