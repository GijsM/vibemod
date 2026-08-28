# Should VibeMod support Spigot / CraftBukkit?

*A recommendation for a human to accept or overrule. Written 2026-08-28 during the
reach-and-context work (`docs/MASTER-PROMPT-reach-and-context.md`, Objective A4), which
asked for the argument in writing rather than an implementation.*

**Recommendation: no. Do not build it.** Not "not yet" for scheduling reasons — the
beneficiary of this work can already get a strictly better outcome for free, and the
implementation carries a regression hazard aimed squarely at the platform that has users.

Below is the evidence, the cost, and — because a recommendation nobody can overturn is
just an opinion — the specific signal that should change this answer.

---

## 1. What actually blocks it

Four things, measured in this tree rather than recalled.

### 1.1 Adventure is not in the jar, and it is not confined to the Paper module

The shipped artifact (`:paper:shadowJar`) contains exactly two top-level entries,
`com/gijsm` and `META-INF`. Zero `net.kyori` classes:

```
$ jar tf paper/build/libs/VibeMod.jar | grep -c kyori
0
```

VibeMod does not bundle Adventure. It relies on Paper supplying it natively. Spigot does
not. Every class that touches Adventure fails to link there.

That is **36 source files**, and the distribution is the part that matters:

| module | files importing `net.kyori.adventure` |
|---|---|
| `core` | 18 |
| `paper` | 7 |
| `platform-api` | 6 |
| `loader-common` | 5 |

(The original brief said 24 files. The real count is 36. More importantly it is not a
Paper-module problem that a Spigot host module could route around — Adventure is in
`core` and `platform-api`, the modules every platform shares.)

This is not an accident to be cleaned up. `docs/ARCHITECTURE-V2.md` §1 makes it a
decision: Adventure is allowed in `core` and `platform-api` because it is a standalone
library rather than a server internal, and it is the project's text and UI currency, with
the LuckPerms precedent cited. `PlatformProfiles.PAPER_IMPORT_RULES` goes further and
makes `net.kyori.adventure.*` an officially *allowed import root for generated mods*,
because 88+ of the stored corpus already used it and banning it was "the single largest
source of avoidable self-heal rounds."

So Adventure is load-bearing in the shared modules, in the generated-code contract, and
in the stored corpus.

### 1.2 Shading Adventure creates a two-Adventure hazard on Paper

The obvious fix — shade `adventure-platform-bukkit` and relocate it — is where this stops
being routine. Relocation rewrites `net.kyori.adventure.text.Component` to
`com.gijsm.vibemod.libs.adventure.text.Component`. That is a *different type*. It cannot
be passed to Paper's native `Player#sendMessage(Component)`, `Audience#showBossBar(...)`
or any other native Adventure entry point, because those want the server's own class.

The consequence is that on Paper — the platform that actually has users — every piece of
text would have to stop going through the native path and start going through
`BukkitAudiences`, which on a relocated classpath cannot detect-and-delegate to native
support and instead falls back to serialization. Rich text degrades: hover and click
events, and the fidelity the dialog UI depends on, are exactly what serialization
fallbacks lose.

The alternative — bundle Adventure *without* relocating — trades that for classpath
roulette with every other plugin shipping its own Adventure, which is the failure mode
relocation exists to prevent.

Neither branch is fatal, and a careful implementation could pick per-platform paths. But
note what has happened: a feature for a platform with no measured users now requires
touching the text path of the platform with all of them. That is the wrong risk gradient.

### 1.3 Top-level commands vanish silently

`Bukkit.getCommandMap()` is Paper-only. It is already capability-gated — `PaperCommandBridge`
checks `platform.hasNativeCommandMap()` and returns `Registration.inactive()` at four
separate sites when it is false.

That gate is honest but its *user-visible behaviour* is bad on a platform where it would
always be false: a generated mod that registers `/mycommand` would load, enable, report
success, and simply have no command. Not an error — an absence. Spigot support therefore
needs a reflection fallback onto `SimplePluginManager.commandMap`, not merely the existing
degradation.

### 1.4 The chat UI is Paper-only

`PaperChatBridge` is built on `io.papermc.paper.event.player.AsyncChatEvent`. Spigot has
only the deprecated `AsyncPlayerChatEvent`, which is a `String` API — so the chat fallback
renderer, which is *the entire UI* below 1.21.7 and the reason the dialog boundary is
survivable, needs a second implementation on the legacy event.

The dialog UI itself needs nothing: it is already reflective and capability-gated.

### 1.5 Summary of the work

| # | Work | Size | Risk |
|---|---|---|---|
| 1 | Shade + relocate `adventure-platform-bukkit`, route all text through a platform-chosen audience path | large | **regresses Paper's text fidelity** |
| 2 | Reflection fallback onto `SimplePluginManager.commandMap` | small | low |
| 3 | `AsyncPlayerChatEvent` chat bridge | medium | low |
| 4 | Dialogs | none | none |
| 5 | A real Spigot line in the smoke matrix, plus a fork label | small | low |

Items 2–5 are ordinary. Item 1 is the decision.

---

## 2. What it would buy

A Spigot operator gets VibeMod, with degraded text, having installed a plugin whose
premise is that it compiles LLM-generated code against their live server.

Weigh that against three facts:

- **They can run Paper instead, on the same hardware, for free, today.** Paper is a
  drop-in CraftBukkit/Spigot replacement. There is no migration cost story here comparable
  to the engineering cost above.
- **The Paper-fork audience is already covered at zero cost, and that is now measured.**
  Purpur 26.2 (build 2627) and Leaf 26.2 (build 89) both pass the full gate *unmodified* —
  same profile, same UI, all assertions green — and Folia 26.2 passes it too, with heavy
  limits on what generated mods can do there (ARCHITECTURE-V2 §10.7). When this document
  was first written the fork claim had no run behind it; it does now. Whatever "we support
  forks" is worth, VibeMod already has it.
- **The floor work already widened reach where it was free.** Paper support is 1.20–26.2:
  **21 `paper-api` artifacts, 20 gateable server versions** — 1.20.3 has an API artifact and
  no server build to boot, so it is not run and not claimed (§10.7). That came from deleting
  a false claim, not from writing code.

## 3. What would change this answer

This recommendation is contingent, and the contingency is demand, not taste. Build it if:

- Someone reports actually being unable to move a specific server off Spigot, with the
  reason. "Prefer Spigot" is not that reason; a pinned plugin that breaks on Paper is.
- Or more than one such report arrives, in which case do items 2–4 and solve item 1 by
  making text fidelity a capability rather than an assumption — which is the same shape as
  the rest of the codebase and would be worth doing on its own merits.

The cheap half of that (items 2–4) is roughly a week. Item 1 done *properly* — text
fidelity as a probe, native path on Paper, serialized path on Spigot — is the one that
needs a reason to exist.

## 4. What is not being claimed

- Spigot support is not impossible, and nothing here says it is.
- Nobody has tried it. There is no measured Spigot failure list beyond the four structural
  blockers above, because the plugin has never been loaded on a Spigot server. The
  Adventure blocker is certain from the jar contents; the other three are read from the
  source. If a human overrules this, the first step is a real Spigot boot to find out what
  the fifth blocker is.
