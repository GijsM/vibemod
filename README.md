# VibeMine

> **v3 note:** the plugin was renamed from VibeCore to **VibeMod** (the api contract mods
> implement was renamed `VibeMod` → `Mod`, with a deprecated `VibeMod` bridge kept so old
> generated sources keep compiling). See `ARCHITECTURE.md`'s V3 ADDENDUM for the full rename.

Vibe-code Minecraft gameplay from inside the game. One Paper plugin — **VibeMod** — takes a prompt
like `/vibe make "sheep can fly"`, asks an LLM for Java, **compiles it in-process** with the JDK's
built-in compiler, and hot-loads the result into the running server. No restarts, no external
services, prompt-to-gameplay in seconds.

```
/vibe make when a creeper dies a chicken spawns at its location with a poof
  Thinking → Writing → Compiling → Loading
  ✔ ChickenCreepers v1 installed
```

## Why mods aren't plugins

Runtime *plugin* loading is unsupported on modern Paper — reloading a plugin id throws
`Provider attempted to add duplicate plugin identifier`, and the Paper team has said unload/reload
will not be supported ([discussion #10561](https://github.com/PaperMC/Paper/discussions/10561)).
So generated mods are **not** plugins: each is compiled in memory (`javax.tools`) and loaded into
its own child classloader under VibeMod's plugin identity, with every listener/task/command tracked
per mod. That makes load/unload/reload genuinely instant and exact. Want a real plugin anyway?
`/vibe export <mod>` emits a standalone drop-in jar (verified to boot on a plain Paper server).

## Quick start

Requirements: a full **JDK 21+** (the server needs `javac`; tested on Temurin 25), Maven, Node
(for the RCON dev script), and an [OpenRouter](https://openrouter.ai) API key.

```bash
./scripts/setup.sh     # downloads Paper 1.21.8, writes eula/server.properties, installs rcon-client
./scripts/build.sh     # mvn package → server/plugins/VibeMod.jar
./scripts/start.sh     # boots the server on localhost:25565 (offline mode)
./scripts/rcon.sh 'vibe make zombies explode into fireworks'   # console driving
./scripts/stop.sh
```

API key resolution order: `plugins/VibeMod/config.yml` → `$OPENROUTER_API_KEY` →
`~/.config/vibemine/openrouter.key`. The server-side config.yml is gitignored — put your key there.

Join at `localhost` with a 1.21.8 client (server runs offline mode for local testing). You need op:
`./scripts/rcon.sh 'op <yourname>'`.

## v2: legible, tunable mods

Every generated mod now documents and exposes itself:

- **Config knobs** — mods declare tunable settings (type, default, min/max/step, description).
  Change them three ways, all applying **instantly** (mods read config live, no reload):
  `/vibe set <mod> <key> <value>`, the GUI's −/+ steppers, or a **config book**
  (`/vibe config <mod>`: edit the `key: value` lines, press *Done* to apply — the book stays in
  your hand as an editing loop; *Sign* to apply-and-finish).
- **Manuals** — the model writes a player guide; VibeMod appends *verified facts* it introspects
  itself (real commands/actions/listeners + current knob values). `/vibe manual <mod>` = a written
  book, `/vibe info <mod>` = a chat card with clickable [manual] [config] [info] [off] buttons —
  the same card printed on every install.
- **Book authoring** — `/vibe book` hands you a book-and-quill: draft a multi-page mod idea at
  your own pace (*Done* saves the draft), then **Sign to submit** — the title becomes a name hint.
  `/vibe book <mod>` pre-fills the mod's manual + config with a *Changes:* page for edit requests.
- **GUI v2** — `/vibe gui`: click a mod for its detail panel (knob steppers, manual/source/config
  books, enable/rollback/export/delete), plus an ops-only settings page with a model picker and
  `[reload]`.
- **Cheap self-heals** — repair and edit rounds may return SEARCH/REPLACE edit blocks instead of
  whole files (the format Claude's own tooling uses; classic line-number diffs are brittle).
  Blocks that fail to apply trigger an automatic full-project retry.
- **`/vibe reload`** — re-reads config.yml live (model, timeouts, watchdog budgets, retries).

v1 mods degrade gracefully: no knobs, introspected-facts-only manuals.

## Commands

| Command | What it does |
|---|---|
| `/vibe make <prompt>` | Generate + hot-load a new mod (boss-bar progress, self-healing compile retries) |
| `/vibe edit <mod> <prompt>` | Evolve a mod — the model sees the current source; new version hot-swaps |
| `/vibe again <mod>` | Fresh take on the mod's last prompt |
| `/vibe list` | All mods, hover for details, click for source |
| `/vibe source <mod>` | The generated Java as an in-game written book (console: chat dump) |
| `/vibe rollback <mod>` | Instantly back to the previous version |
| `/vibe enable/disable <mod>` | Exact hot teardown / re-load |
| `/vibe export <mod>` | Standalone Paper plugin jar + readable source tree |
| `/vibe do <mod> <action>` | Invoke a mod-declared action |
| `/vibe gui` | Chest-GUI mod browser (toggle / rollback / delete / export) |
| `/vibe chat` | Chat mode: plain chat lines become prompts ("off" to stop) |
| `/vibe model [id]` | Show/set the OpenRouter model (default `anthropic/claude-sonnet-5`) |
| `/vibe panic` | Kill switch: unload every mod now |

## Architecture

```
plugin/src/main/java/com/gijsm/vibemine/
├── api/        Mod + VibeContext — the tiny contract generated code writes against
│               (deprecated VibeMod bridge kept so pre-v3 generated sources keep compiling)
├── llm/        OpenRouterClient (JDK HttpClient) + PromptLibrary (system prompt, lenient JSON parse)
├── gen/        ModGenerator — generate → compile → on error feed javac output back → retry
├── compile/    InMemoryCompiler — javax.tools, bytecode captured in memory,
│               classpath = running Paper jar + bundler libraries/ + VibeMod itself
├── runtime/    ModRegistry (child classloader per mod, exact per-instance teardown),
│               DynamicCommands (runtime /commands with neuterable handlers — Paper has no
│               CommandMap#unregister), Watchdog (auto-disables slow mods)
├── store/      ModStore (mods/<Name>/meta.json + v1..vN source history) + JarExporter
├── ui/         boss-bar progress, chest GUI, source books, chat mode
└── command/    /vibe dispatcher
```

Safety posture (deliberate, per project choice): op-only permissions, tick-time watchdog with
auto-disable, `/vibe panic`, and exact per-mod teardown — but **no import/bytecode sandboxing**.
Generated code runs with full JVM privileges. Run this on servers you own, with ops you trust.

See `ARCHITECTURE.md` for the full frozen contracts and `DEMO.md` for the verified test transcript.
