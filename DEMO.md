# DEMO.md — verified end-to-end runs (2026-08-21)

Everything below was executed for real against the live Paper 1.21.8 server in `server/`,
driven over RCON with `scripts/rcon.sh`. Model: `anthropic/claude-sonnet-5` via OpenRouter.
✅ = machine-verified via console assertions; 🎮 = needs a human player (verified code paths only).

## 1. Boot ✅
Paper 1.21.8 build 60 on Temurin JDK 25. `[VibeCore] VibeCore ready` — zero plugin errors.
(The log's `No key layers in MapLike[{}]` lines are vanilla's empty-flat-preset grumble; the
`PaperVersionFetcher` stack trace is Paper phoning its own sunset v2 update API. Neither is ours.)

## 2–3. Prompt → gameplay ✅
```
> vibe make when a creeper dies a chicken spawns at its location with a poof
  [VibeCore] Generated ChickenCreepers v1        (LLM round-trip ≈ 8s)
> execute if entity @e[type=chicken]             → Test failed        (baseline: none)
> summon creeper 0 -58 0 {NoAI:1b}
> damage @e[type=creeper,limit=1] 100
> execute if entity @e[type=chicken]             → Test passed, count: 1   🐔
```

## 4. Self-healing compile loop ✅ (organic, in production)
```
> vibe make zombies explode into a colorful firework when they die
  [VibeCore] Generated ZombieFireworks v1 after 1 repair round(s)
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
Dropped into plugins/, VibeCore's mod disabled, server restarted:
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
