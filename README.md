# VibeMod

Vibe-code Minecraft gameplay from inside the game. One Paper plugin — **VibeMod** — takes a prompt
like `/vibe make "sheep can fly"`, asks an LLM for Java, **compiles it in-process** with the JDK's
built-in compiler, and hot-loads the result into the running server. No restarts, no external
services, prompt-to-gameplay in seconds.

```
/vibe make when a creeper dies a chicken spawns at its location with a poof
  Thinking → Writing → Compiling → Loading
  ✔ ChickenCreepers v1 installed
```

## Security model — read this first

VibeMod compiles LLM-generated Java and loads it into your server's JVM with **full privileges**.
There is **no sandboxing** — no SecurityManager, no bytecode filtering, no import allow-list at
load time. This is by design: sandboxing generated gameplay code convincingly is not a solved
problem, and pretending otherwise would be worse than being honest about it. A generated mod can,
in principle, do anything your server process can do — read files, open sockets, all of it.

What that means in practice:

- **Only trust your ops.** Every `/vibe` subcommand is gated behind `vibe.use` (read-only) or
  `vibe.admin` (everything else), and both default to **op**. Anyone who can run `/vibe make` can
  effectively execute arbitrary code on the host. Grant these permissions accordingly — i.e. to
  people you would give a shell.
- **Mitigations that do exist** are about stability and control, not containment: each mod runs in
  its own child classloader with exact per-instance teardown; a tick-time **watchdog** auto-disables
  mods that stall the main thread; an **error-storm** detector auto-disables mods that throw
  repeatedly; and `/vibe panic` is a kill switch that unloads every mod at once.
- **Run it on servers you can afford to lose.** A disposable or private server is the right home
  for this plugin — not a production server with irreplaceable worlds or other players' data.
- The dev scripts are locked down by default: `scripts/setup.sh` generates a **unique RCON
  password** (stored in `scripts/.rcon-password`, gitignored) and binds the server to
  **127.0.0.1**, so nothing is reachable from outside the machine.
- The dev default is `online-mode=false` for quick local testing with no authentication.
  **Reconsider that for anything non-local** — on an offline-mode server anyone can join under
  any name, including an op's.

## Why mods aren't plugins

Runtime *plugin* loading is unsupported on modern Paper — reloading a plugin id throws
`Provider attempted to add duplicate plugin identifier`, and the Paper team has said unload/reload
will not be supported ([discussion #10561](https://github.com/PaperMC/Paper/discussions/10561)).
So generated mods are **not** plugins: each is compiled in memory (`javax.tools`) and loaded into
its own child classloader under VibeMod's plugin identity, with every listener/task/command tracked
per mod. That makes load/unload/reload genuinely instant and exact. Want a real plugin anyway?
`/vibe export <mod>` emits a standalone drop-in jar (verified to boot on a plain Paper server).

## Installation (existing server)

Just want the plugin on your own Paper 1.21.8 server?

1. Download `VibeMod.jar` from [GitHub Releases](../../releases) into your server's `plugins/`.
2. Make sure the server runs on a full **JDK 21+** (not a bare JRE — VibeMod needs the in-process
   Java compiler).
3. Set your [OpenRouter](https://openrouter.ai) API key: either in
   `plugins/VibeMod/config.yml` (created on first boot), or via `$OPENROUTER_API_KEY`, or in
   `~/.config/vibemod/openrouter.key`.
4. Op yourself, run `/vibe make something fun`. Done — but read the security model above first.

## Quick start (from source)

Requirements: a full **JDK 21+** (the server needs `javac`; tested on Temurin 25), Maven, Node.js
(for the RCON dev scripts), and an [OpenRouter](https://openrouter.ai) API key.

```bash
./scripts/setup.sh     # downloads Paper 1.21.8 (sha256-verified), writes eula.txt and
                       # server/server.properties, generates scripts/.rcon-password, installs rcon-client
./scripts/build.sh     # mvn package → server/plugins/VibeMod.jar
./scripts/start.sh     # boots the server on 127.0.0.1:25565
./scripts/rcon.sh 'vibe make zombies explode into fireworks'   # console driving
./scripts/stop.sh
```

API key resolution order: `plugins/VibeMod/config.yml` → `$OPENROUTER_API_KEY` →
`~/.config/vibemod/openrouter.key`. The server-side config.yml is gitignored — put your key there.

Join at `localhost` with a 1.21.8 client (the dev server runs offline mode, bound to 127.0.0.1,
for local testing only). You need op: `./scripts/rcon.sh 'op <yourname>'`.

## Legible, tunable, fixable mods

Every generated mod documents and exposes itself, and the whole UI is native Paper dialogs —
bare `/vibe` opens the mod browser, and everything per-mod (config, manual, source, errors,
history, settings) is a dialog:

- **Config knobs** — mods declare tunable settings (type, default, min/max/step, description).
  Change them via the config dialog (`/vibe config <mod>`: sliders, checkboxes, dropdowns) or
  `/vibe set <mod> <key> <value>`; both apply **instantly** — mods read config live, no reload.
- **Manuals** — the model writes a player guide (Markdown, rendered in a scrollable dialog);
  VibeMod appends *verified facts* it introspects itself (real commands/actions/listeners +
  current knob values). `/vibe manual <mod>` opens it, `/vibe info <mod>` prints a chat card with
  clickable buttons — the same card printed on every install.
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
- **`/vibe reload`** — re-reads config.yml live (model, timeouts, watchdog budgets, retries,
  concurrency, error-storm thresholds).

## Commands

| Command | What it does |
|---|---|
| `/vibe` | The front door: opens the mod browser dialog (help text on console) |
| `/vibe make <prompt>` | Generate + hot-load a new mod (argless: opens a prompt dialog) |
| `/vibe edit <mod> <prompt>` | Evolve a mod — the model sees the current source; new version hot-swaps |
| `/vibe again <mod>` | Fresh take on the mod's last prompt |
| `/vibe list` | All mods with state dots, click-through to details |
| `/vibe info <mod>` | Install card: description, usage hint, verified facts, action buttons |
| `/vibe manual <mod>` | The model-written player guide + introspected facts |
| `/vibe source <mod>` | The generated Java in a scrollable dialog (console: chat dump) |
| `/vibe config <mod>` / `set <mod> <key> <value>` | Tune a mod's knobs — applies live |
| `/vibe errors <mod>` | A mod's deduped runtime error log |
| `/vibe fix <mod>` | Send recent errors to the model for a repair round |
| `/vibe debug <mod> [on\|off]` | Echo a mod's ctx.log()/exceptions live to ops |
| `/vibe history <mod>` | Browse and re-activate previous versions |
| `/vibe rollback <mod>` | Instantly back to the previous version |
| `/vibe enable/disable <mod>` | Exact hot teardown / re-load |
| `/vibe export <mod>` | Standalone Paper plugin jar + readable source tree |
| `/vibe do <mod> <action>` | Invoke a mod-declared action |
| `/vibe book [mod]` | Prompt/edit dialog aliases for drafting an idea or change request |
| `/vibe chat` | Chat mode: plain chat lines become prompts ("off" to stop) |
| `/vibe model [id]` | Show/set the OpenRouter model (default `anthropic/claude-sonnet-5`); tab-completes the live catalog |
| `/vibe costs` | Session + per-mod generation costs |
| `/vibe settings` | Plugin settings dialog (model picker, budgets, reload) |
| `/vibe reload` | Re-read config.yml live |
| `/vibe panic` | Kill switch: unload every mod now |

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

## Architecture

```
plugin/src/main/java/com/gijsm/vibemod/
├── api/        Mod + VibeContext — the tiny contract generated code writes against
│               (deprecated VibeMod bridge kept so older generated sources keep compiling)
├── llm/        OpenRouterClient (JDK HttpClient, SSE streaming) + PromptLibrary
│               (system prompt, lenient JSON parse) + live model catalog & cost tracking
├── gen/        ModGenerator — generate → compile → on error feed javac output back → retry
├── compile/    InMemoryCompiler — javax.tools, bytecode captured in memory,
│               classpath = running Paper jar + bundler libraries/ + VibeMod itself
├── runtime/    ModRegistry (child classloader per mod, exact per-instance teardown),
│               DynamicCommands (runtime /commands with neuterable handlers — Paper has no
│               CommandMap#unregister), Watchdog (auto-disables slow mods),
│               ModErrors (deduped error log, degraded state, storm auto-disable)
├── store/      ModStore (mods/<Name>/meta.json + v1..vN source history) + JarExporter
├── ui/         native Paper dialogs (mod browser, config, settings, viewers),
│               boss-bar progress, install cards, chat mode
└── command/    /vibe dispatcher
```

See `ARCHITECTURE.md` for the full contracts and `DEMO.md` for the verified test transcript.

## License

[MIT](LICENSE) — © 2026 Gijs Mulder.
