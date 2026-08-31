# Master prompt — maximise platform reach, rebuild the context engineering

A briefing for an autonomous coding agent working on VibeMod. Paste everything below
the line. It is written to be handed over cold: the ground truth section exists so the
agent does not re-derive facts that cost a day of real server boots to establish, and
the traps section exists so it does not lose the same day again.

Provenance: every measurement quoted is from the sweep on branch
`worktree-version-sweep-paper` (commit `bcb0b2e`), run on 2026-08-28 against real
dedicated servers. Re-run any of it with `scripts/sweep-paper.sh <version…>`.

---

## Mission

You are working on **VibeMod**, a Minecraft plugin/mod that asks an LLM for Java,
compiles it in-process, and hot-loads it into a running game. Your job has two halves,
and the second matters more than the first:

1. **Widen the set of servers VibeMod runs on**, as far as it can honestly go.
2. **Rebuild the prompt assembly so that platform knowledge is derived from the running
   server rather than from a version string.** This is the higher-value half. VibeMod's
   output quality is bounded almost entirely by how accurately the system prompt
   describes the API the generated code must compile against, and that description is
   currently wrong on at least twelve supported versions.

Read `docs/ARCHITECTURE-V2.md` first. Honour its numbered constraints; where this brief
and that document disagree, say so rather than silently picking one.

## Ground truth — already measured, do not re-litigate

**Paper support is 1.20 → 26.2**, twenty consecutive versions, all fully functional
(mod compiles in-process, hot-loads, all commands answer, disable/enable clean). The
README claims a 1.20.6 floor; that is four releases too conservative.

**The floor is a declaration, not a capability.** Paper 1.19.4 and below refuse with
`org.bukkit.plugin.InvalidPluginException: Unsupported API version 1.20`, read straight
out of `api-version: '1.20'` in `plugin.yml`. Confirmed identically on 1.19.4, 1.19.2,
1.18.2, 1.17.1, 1.16.5. Nothing in the plugin was given a chance to fail.

**The UI boundary is real and correct.** `io.papermc.paper.dialog.Dialog` first appears
on 1.21.7. Verified by class-load logging: it loads on 1.21.7 and never appears on
1.21.6 or 1.21.5. Native dialogs above, chat fallback below, exactly as documented. Note
that `PaperPlatformInfo`'s javadoc claims 1.21.6 "has the class but not the behaviour" —
that is wrong; the class is absent. The defensive `&&` is harmless, the comment is not.

**Platforms:**

| Server | State | Note |
|---|---|---|
| Paper 1.20–26.2 | works | reference |
| Purpur 26.2 | works unmodified | same profile, same UI, all assertions green |
| Leaf 26.2 | works unmodified | same |
| Folia 26.2 | refuses to load | no `folia-supported` in `plugin.yml` |
| Spigot / CraftBukkit | cannot work as built | see below |

**Folia, measured — this is the important one.** With `folia-supported: true` added and
nothing else changed, VibeMod *loads and enables* on Folia 26.2 (dialogs and command map
both probe true), then dies during restore-on-boot:

```
java.lang.UnsupportedOperationException
  at org.bukkit.craftbukkit.scheduler.CraftScheduler.handle(CraftScheduler.java:517)
  at CraftScheduler.runTaskAsynchronously(CraftScheduler.java:158)
  at com.gijsm.vibemod.paper.PaperTickScheduler.async(PaperTickScheduler.java:44)
  at com.gijsm.vibemod.VibeMod.applyStoredVersion(VibeMod.java:361)
```

It fails on VibeMod's *own* async compile path, before any generated mod runs. So the
flag on its own converts today's honest refusal into a boot-time crash that disables the
plugin. **Add `folia-supported` as the last commit of the Folia work, never the first.**

**Spigot, structural.** The shipped jar contains only `com/gijsm` — Adventure is not
bundled, and 24 source files import `net.kyori.adventure`. Spigot does not provide it.
Additionally `Bukkit.getCommandMap()` is Paper-only (already gated by
`hasNativeCommandMap()`, degrading to `Registration.inactive()`, which means top-level
commands silently vanish rather than erroring), and
`io.papermc.paper.event.player.AsyncChatEvent` is Paper-only and carries the chat UI.

**The prompt is measurably wrong.** Two confirmed defects, same root cause:

- *Attribute era boundary.* Profiles split at 1.21.7 (dialog API). The attribute
  vocabulary changes at **1.21.3**. Read from each server's own `paper-api` jar: 1.21.1
  has 23 `GENERIC_*` constants and no short names; 1.21.3 has zero `GENERIC_*` and only
  short names. `paper-legacy` serves 1.21.3–1.21.6 and instructs *"Attributes keep their
  long prefixes: `Attribute.GENERIC_MAX_HEALTH` … NEVER the short 1.21.3+ forms."* On
  those four versions that code cannot compile. Guaranteed self-heal round, real money,
  every attribute-touching mod.
- *Probed then contradicted.* `hasItemGlintOverride()` is probed at boot and varies
  inside one profile — `false` on 1.20–1.20.4, `true` on 1.20.5–1.21.6. All twelve get
  the same text: *"Do NOT call `ItemMeta#setEnchantmentGlintOverride(...)`"*. On eight of
  twelve the prompt forbids a method the server has.

Root cause: `PromptLibrary.systemPrompt(profile)` receives only a `PlatformProfile`,
selected by `paperProfileIdFor(mcVersion)` — a single version comparison.
`PlatformInfo` already probes eight capabilities at boot and **none of them reach the
prompt.**

**Measured prompt sizes** (against the shipped jar): `paper-modern` 23,028 chars
(~5.8k tokens), `paper-legacy` 24,031 (~6.0k), `fabric` 34,805 (~8.7k), `neoforge`
34,810 (~8.7k). Fabric and NeoForge are byte-identical but for one word in the role
line, which confirms the loader-neutral SDK claim — leave that alone.

**No prompt caching exists.** There is no `cache_control` anywhere in
`OpenRouterClient`, and `buildBody` sends the system message as
`sys.addProperty("content", systemPrompt)` — a plain string. A cache breakpoint needs
the structured content-block array form. The system prompt is re-sent in full on every
generation *and* every self-heal round.

## Objective A — reach

Work in this order. Each step ends with a green real-server gate, not a unit test.

**A1. Tell the truth about 1.20 (hours).** README supported table → 1.20, or state
explicitly that 1.20.6 is a support boundary the code does not enforce. Add `paper 1.20`
and `paper 26.1.2` to the CI smoke matrix: it currently gates only 1.20.6, 1.21.8 and
26.2, so the entire 1.21.9 → 26.1.2 band — where a new Minecraft release lands — has
never been gated. Document that 1.20 emits 125 harmless `Commodore` errors (Paper 1.20
bundles ASM 9.4, which cannot read the plugin's Java 21 bytecode; CraftBukkit falls back
to the original bytes and everything works).

**A2. Folia (1–2 weeks).** In this order, flag last:

- Add a `FoliaTickScheduler` beside `PaperTickScheduler` — the only class that touches
  `BukkitScheduler`. Select it by **class-presence probe**, not by calling
  `getScheduler()`, which is the thing that throws. Verify the current Folia scheduler
  API against Folia's own docs before writing it (expect global-region, region, entity
  and async schedulers returning a `ScheduledTask` rather than a `BukkitTask`).
- Migrate the mod-facing return type. `sdk/…/VibeContext.java` declares
  `BukkitTask repeat(…)` and `BukkitTask later(…)`; Folia has no `BukkitTask`. The loader
  flavour already returns a neutral `TaskHandle`, and `PaperTaskHandle` already
  implements it — its `task()` accessor exists *only* to feed these frozen signatures.
  So the change is short but it is a public contract present in every generated mod.
  **Before committing to it, grep the stored corpus for assignments of the return value**
  and report the count; that number decides whether this is a rename or a migration.
- Revisit what "the main thread" means. The tick watchdog measures main-thread stalls;
  the error-storm detector and `ModDispatch` assume one ordering domain. On Folia these
  are per-region.
- Write a `paper-folia` prompt era. Every generated mod is currently told *"Event
  handler methods and Runnables … already run on the main server thread — do not spawn
  your own threads and do not attempt to hop threads yourself."* On Folia that sentence
  is false and a mod that believes it races silently. Recommended contract: restrict
  generated mods to the global region scheduler. Be explicit in the release notes that
  this forgoes Folia's per-region parallelism — which is most of why someone runs Folia.
  Do not paper over that trade-off.

**A3. Push the floor to 1.17 (days), only with demand.** Three chained constraints:

- `api-version: '1.20'` refuses anything older. One line to lower.
- But once `api-version` is below the server's version, Bukkit's `Commodore` *must*
  rewrite legacy calls, and Paper 1.20's ASM 9.4 throws `Unsupported class file major
  version 65` on Java 21 bytecode. On 1.20 those errors are harmless only because no
  rewrite was needed. Below 1.20 they would not be. **So this step requires retargeting
  the plugin to Java 17.**
- `options.release = 21` → `17` in `build.gradle.kts`. Verified safe: the only advanced
  language features in use are `sealed` interfaces and records, both Java 17.

That puts the real floor at **1.17** — Paper 1.17–1.19.x run on a JDK that loads Java 17
bytecode; Paper 1.16.5 supports only Java 8–16 and can never load it. Do not go below
1.17: a Java 11 or 8 target costs records, text blocks and sealed types across the
codebase for users who have newer options. Also verify `AsyncChatEvent` exists that far
back, and add a third prompt era — which is nearly free if you have done Objective B.

**A4. Spigot — argue it before building it.** Reachable: shade and relocate
`adventure-platform-bukkit` behind `BukkitAudiences` (carefully — on Paper the server
supplies Adventure natively), add a reflection fallback onto
`SimplePluginManager.commandMap`, add an `AsyncPlayerChatEvent` fallback. The dialog API
needs nothing, being already reflective and gated. The case against: the beneficiary
could run Paper instead, on the same hardware, for free — and Purpur and Leaf already
pass unmodified, which covers the Paper-fork audience at zero cost. **Default to not
doing this.** If you disagree after reading the code, make the argument in writing and
let a human decide.

## Objective B — context engineering

This is the half that compounds. The current architecture is sound in shape —
`PlatformProfile` makes era-specific sentences data rather than branches, and its own
javadoc names the stake: *"the difference between a mod that compiles first try and one
that needs three self-heal rounds is one sentence about which era's enum names are
real."* The failure is that the table is coarser than the knowledge the host already
holds. Fix that structurally, not case by case.

**B1. Assemble the prompt from capability probes, not a version string.** Pass
`PlatformInfo` into the prompt builder. Express each era- or capability-specific rule as
a `(predicate, text)` pair evaluated against the probes, so a rule can never contradict
a probe. This collapses both confirmed defects into one change and makes unknown future
versions degrade gracefully instead of mis-teaching silently. Delete
`paperProfileIdFor`'s role as the sole authority; keep it at most as a fallback for text
that genuinely has no probe.

**B2. Derive the API vocabulary from the running server by reflection.** This is the
strongest available move and it retires an entire error class permanently. VibeMod
already compiles against the live server classpath, so at boot it can enumerate what
actually exists: the real constant names on `Attribute`, `Sound`, `Particle`,
`Material`, `EntityType`, `PotionEffectType`, `Enchantment`. Two uses, and do both:

- **Inject** a compact, truthful vocabulary (or just the constants that differ from
  what a model is likely to assume) instead of hand-maintained prose about which era's
  names are real. Watch the token budget; prefer the diff over the dump.
- **Validate before spending a token.** Add a local, deterministic pre-compile pass that
  checks every referenced constant against the real reflected set and repairs the
  mechanical cases (`Attribute.GENERIC_MAX_HEALTH` → `Attribute.MAX_HEALTH` and its
  siblings) without an LLM round-trip. Free, instant, and it makes the remaining
  self-heal rounds be about logic rather than spelling.

**B3. Make the prompt's factual claims testable.** Add an offline gate that, for every
supported version, asserts that every symbol the cheat sheet *names* actually exists in
that version's `paper-api` jar — and that every symbol it *forbids* actually is absent.
This test would have caught the attribute defect the day it was introduced. Treat it as
the durable fix; B1 and B2 are the repair, this is what stops regression. The API jars
are already on disk after a smoke run at
`paper/run/smoke-<version>/libraries/io/papermc/paper/paper-api/…`.

**B4. Enable prompt caching, and order the prompt for it.** Convert the system message
to the structured content-block form and mark a cache breakpoint at the end of the
invariant section. Verify the exact mechanism against current OpenRouter and
provider documentation — the project is provider-agnostic (`ModelCatalog` spans
anthropic, openai, google, x-ai, deepseek, qwen, nvidia), and caching semantics differ
per provider: some require an explicit breakpoint, others cache automatically. Then
order the prompt **invariant first**: the API source block and the fixed rules, then the
capability-derived lines, then the few-shots. Today the role line — the one sentence
that differs between the two Paper eras — is the very first thing in the prompt, so the
shared prefix between them is 27 characters. That costs nothing for a single server
(one profile per boot) but it is the wrong shape, and B1 will multiply the number of
variable fragments.

**B5. Prove prompt changes with an eval, not an assertion.** The repo already ships a
fixture corpus, and `StoreSelfTest` compiles it. Build on that: a scored eval that
measures **first-try compile rate per platform and version**. Every change under
Objective B must move that number, and you must report the before and after. Without
this you are guessing, and prompt work that cannot be measured tends to drift toward
longer prompts rather than better ones.

**B6. Do not teach what the model is forbidden to write.** The NeoForge profile already
gets this right and says so: it deliberately omits loader event names because a generated
mod never sees one. Apply the same discipline everywhere — every sentence in the prompt
should either constrain output or supply a fact the model needs. Prune anything that is
neither. Report the token delta.

## Invariants — do not break these

- `core` must not depend on `paper-api`.
- Everything a generated mod does routes through the `VibeContext` it is handed; that is
  what makes exact teardown possible. Do not add an escape hatch.
- The watchdog, the error-storm detector and `/vibe panic` are the only real safety
  mechanisms. Keep all three working on every platform you add.
- No sandboxing claims. VibeMod runs LLM-generated code with full privileges and the
  README is honest about it. Do not add language implying containment you have not built.
- The stored corpus must keep compiling. It is the regression suite for generated code.
- `api-version` governs legacy data conversion, not which API exists — and declaring a
  floor **above** the running server makes Paper refuse the plugin outright. Do not raise
  it to signal a minimum version.

## Traps that already cost time

1. **`./gradlew :paper:jar` builds the wrong artifact.** Since the shadow plugin arrived
   it produces a thin, bStats-less jar. The shipped artifact is `:paper:shadowJar`.
2. **Paper 1.21 cannot be tested on JDK 25.** Its bundled spark ships an async-profiler
   native library that SIGSEGVs the whole JVM seconds after boot, which presents as *"the
   canned mod never went live"*. Use JDK 21 for that line. `scripts/smoke-paper.sh` now
   honours `JAVA_HOME`.
3. **`minecraft-data(version)` resolves loosely.** Asked for `1.21.7` it returns the data
   for `1.21`; asked for `26.1.2` it returns `26.1`. Truthy both times. Test exact
   membership in `supportedVersions.pc`, and still catch the throw — `minecraft-data`
   ships a `26.1` entry mineflayer cannot use, so no pre-connect check is complete.
4. **`smoke-rcon.py` asserts nothing.** It prints replies and never checks them, so a
   gate can be green while every answer is wrong. `scripts/sweep-paper.sh` adds the
   assertions; use it rather than reading transcripts by eye.
5. **Do not grep for `smoke-pong` unanchored** when checking that a disabled mod stopped
   answering — `vibe source` echoes the mod's own Java, which contains the string.
6. **A fork at the same Minecraft version needs `SMOKE_LABEL`**, or its run directory
   overwrites the Paper run you are comparing against.

## Acceptance — what "done" means

A claim about a platform or version is only true once a **real dedicated server** has
booted with the shipped jar, compiled and hot-loaded a mod, and answered the assertions.
Specifically:

- `scripts/sweep-paper.sh` green across the Paper range you claim, on a JDK each line
  supports.
- Forks driven through the same gate via `SMOKE_LABEL` and `SMOKE_SERVER_JAR`.
- For Objective B, the eval from B5 reported before and after, plus the B3 symbol test
  passing for every supported version.
- Anything you could not verify stated plainly as unverified. Do not launder a prediction
  into a result — this brief exists because six gates went red for reasons that had
  nothing to do with the plugin, and the only reason that was recoverable is that the
  logs were read rather than the verdicts trusted.

## Out of scope

Chest and anvil GUIs, registry content in generated mods, mixins in generated code,
VibeMod-to-VibeMod networking, and any sandboxing effort. Do not port the Paper plugin to
Fabric or NeoForge — those are separate builds with their own gates.

## One open question worth an experiment, not a patch

There is no 26.x prompt profile: `paperProfileIdFor` maps everything above `1.x` onto
`paper-modern`, which opens *"You are an expert Paper 1.21.8 gameplay-mod author"* and
asks for *"real Paper 1.21 enum constants"*. The host is fine on 26.1 and 26.2 — that is
measured. The **generation** cost of describing a 26.2 server as 1.21.8 is not measured,
and needs a run with a real API key comparing first-try compile rates on 1.21.8 versus
26.2. Run that before adding a profile nobody has shown is needed. If Objective B2 lands,
the question may dissolve on its own — which is a good reason to do B2 first.
