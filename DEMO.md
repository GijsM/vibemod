# DEMO.md — verified end-to-end runs (2026-08-21)

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

# v2 verification (2026-08-21, evening)

Built by 5 parallel subagents against frozen contracts; **zero integration compile errors on
first full assembly** (again). Self-tests: LlmSelfTest (incl. embedded-API-copy drift guard +
few-shots extracted from the live prompt, parsed, compiled clean), StoreSelfTest (v1-meta
normalization, validation matrix, schema evolution), BookParserSelfTest (pure JVM) — all PASS.

## Live config loop ✅ (the headline)
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

## Documentation surfaces ✅
`/vibe info ChickenCreepers` (console-rendered): card + usage + [manual][config][info][off] +
verified facts (listeners: 1, knobs with live values). v1 mod `/vibe info ZombieFireworks`:
degrades to description + introspected facts, no knob section, no errors.

## Diff-based repair/edit ✅ (first live use)
```
> vibe edit ChickenCreepers also play a chicken sound, change nothing else
  Applied 1 edit block(s) from an edit-shaped response
  Generated ChickenCreepers v4       knobs preserved; player's chicken-count=5 survived
```

## Reload ✅
config.yml watchdog 250→400ms + `/vibe reload` → `Config reloaded (model=..., watchdog=400ms/...)`.

## Export with config ✅
`ChickenCreepers-4.jar` embeds a seeded config.yml (`chicken-count: 5` — the live override, with
description comments); the standalone wrapper reads it via standard Bukkit getConfig.

## Regression ✅
Rollback v4→v3 recompiles + hot-swaps; knob values still apply across versions (5 chickens on v3).

## Deferred / human-verified 🎮
- Standalone boot of a v2 exported jar (mechanism proven in v1; deferred to avoid restarting the
  server twice while player was online — drop the jar in a plugins/ dir to confirm).
- Book flows in the client (sign-to-submit prompt/edit books, config-book Done-loop), GUI detail
  panel + steppers + settings page, install-card buttons: give/parse/apply logic is unit- and
  compile-verified; the clicking needs hands. Steps: `/vibe book`, write a wish, Sign it.
  `/vibe config GrapplingHook`, change pull-strength, press Done mid-swing.

Field note: while v2 was being verified, player (age-appropriate chaos engineer) had already
generated GrapplingHook v1 with three perfectly-formed knobs. The contract works under real use.


---

# v3 verification (2026-08-21, evening) — VibeMod

Rename + debuggability + native dialogs. Three parallel agents (rename sweep / ModErrors+DebugEcho
runtime / dialog UX), architect-integrated; full compile clean on first assembly (fourth in a row).

## Rename + migration ✅
Server stopped, the plugin's data dir migrated from its pre-rename name to plugins/VibeMod
(API key + mods + moddata + exports intact), the stale old jar removed. Boot:
`[VibeMod] Enabling VibeMod`, all 7 enabled mods
recompiled from stored sources that still say `implements VibeMod` — the deprecated
`VibeMod extends Mod` bridge is load-bearing and works. New generations teach/emit `implements Mod`.

## Degraded → fix loop ✅ (the headline)
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

## Error storm ✅
Rolled back to throwing v1, 11 rapid triggers → `DiagCheck was auto-disabled after an error storm`.
Storm fires once per episode; threshold live-reloadable (verified via /vibe reload).

## Also machine-verified ✅
Rollback regression (used as the storm setup), `/vibe debug <mod> on` toggle, reload of errors.*
keys, boot-restore of the full mod set post-migration, self-test suites (ErrorsSelfTest 36 checks:
dedup/episodes/storm-once/cap-eviction/persistence; Llm/Store/Compiler suites incl. a
bridge-compile case and "prompt never teaches the deprecated name").

## Human-verified 🎮 (client-only)
Native dialogs (`/vibe make` argless → popup with multiline idea field; `/vibe config <mod>` →
sliders/checkboxes/dropdowns with Save; fix-confirm dialog), virtual books (manual/source/errors
open with NO item), the restyled GUI (state-colored borders, ● dots, expressive buttons incl.
per-mod [⟳ reload], sounds), debug-echo lines to ops, unified ⬡ vibe chat style. Dialog API
signatures were javap-verified against the real paper-api jar; the clicking needs hands.
