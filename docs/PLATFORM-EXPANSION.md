# Platform Expansion Plan

> **This is the original research and plan, kept as written. It is not the implemented
> reality — [ARCHITECTURE-V2.md](ARCHITECTURE-V2.md) is.**
>
> The expansion shipped in VibeMod 2.0.0: Paper 1.20.6+, Fabric 26.1+ and NeoForge 26.1+.
> **The Paper figure has since been corrected to 1.20+** — the code was working four releases
> below the claimed floor all along, and Purpur and Leaf turned out to pass unmodified. See
> ARCHITECTURE-V2 §10.6. Every "1.20.6" and every ranking of Paper forks below is the plan as
> written in 2026, not the measured result.
> Read this document for *why* those directions were chosen and what the 2026 landscape looked
> like; read V2 (and especially its §10, the phase-by-phase record) for what was actually built,
> what deviated from the plan, and why. Some of what is proposed here was overtaken by the work:
> `adventure-platform-mod` was investigated and rejected outright, the two loaders ended up
> sharing a `loader-common` source tree this document does not describe, Gson turned out not to
> need nesting, and NeoForge turned out to need no mixin at all.

*Status: proposed — research completed 2026-08-25. Superseded by ARCHITECTURE-V2.md and
implemented in 2.0.0.*

VibeMod today is a single-module Maven project targeting exactly Paper 1.21.8. This
document is the plan for expanding the supported surface in two directions:
**backwards** (older Paper/Spigot/Bukkit) and **sideways** (Fabric, NeoForge, others).
It is grounded in three research passes: a full inventory of our own API surface, the
2026 server-version landscape, and per-loader feasibility.

---

## 1. What the research established

### 1.1 The market (bStats, Aug 2026)

- Paper-API servers (Paper/Purpur/Leaf/Folia) are **~90%** of the tracked market; Spigot ~8.5% and shrinking.
- Version spread: MC 26.x (Mojang's new year-based scheme, successor to 1.22) + 1.21.x dominate.
  **1.8.8 + 1.12.2 + 1.16.5 together are under 3%** of visible servers. The "old versions are huge" folklore is dead for plugins.
- Java: 26.1+ requires Java 25; 1.20.5–1.21.11 requires Java 21; 1.18–1.20.4 runs Java 17.
  `javax.tools` needs a full JDK — reliably present on Java 11+ era servers, a real risk on Java 8 (bare JREs).
- Folia is ~0.8% of servers and would force region-thread-safety on *every generated mod*. Not worth it now.

### 1.2 Our own coupling (full inventory in the research pass; hot spots below)

The good news: **zero NMS / CraftBukkit / mappings usage anywhere.** Nine core files
(`llm/*`, `store/ModStore|ModConfigs`, `compile/CompileResult`, `gen/GeneratedProject`,
`api/Mod|VibeMod`) are pure JDK+Gson already. The compiler derives its classpath from
the *running server* (`InMemoryCompiler` + the paperclip `libraries/`+`versions/` walk in
`VibeMod.java:438-452`), which is exactly the right multi-version mechanism.

The coupling is concentrated:

| Coupling | Where | Floor it imposes |
|---|---|---|
| Dialog API (17 screens, 12 `io.papermc.paper.*dialog*` imports) | entire `ui/` layer | **Paper 1.21.7** (dialogs are a vanilla 1.21.6 client feature; no server-side fallback exists below it) |
| `Bukkit.getCommandMap()` | `runtime/DynamicCommands.java:61,98` **and every exported jar** (`store/JarExporter.java:364,438,475`) | Paper (any version) |
| `AsyncChatEvent` | `ui/ChatMode.java` | Paper (any version) |
| Adventure `Component`/`Audience`/`BossBar` (~60 call sites) | everywhere user-facing | Paper 1.16.5+ (absent on Spigot) |
| `ItemMeta#setEnchantmentGlintOverride` | `ui/DialogKit.java:178` | 1.20.5+ |
| Prompt text: "Paper 1.21.8", "1.21 enum constants" | `llm/PromptLibrary.java:183,250,294-297` + few-shot examples | prompt-level, trivially parameterizable |
| `api-version: '1.21'` | `plugin.yml:4`, `JarExporter.buildPluginYml` (`:134`) | hard floor 1.21 as declared |
| Generated mods in the wild | 569 stored `.java` files | Adventure in 88+ files (prompt rule violated, unenforced), `Attribute.MAX_HEALTH`-family names (1.21.3+), registry-ified `Sound`/`Particle`/`PotionEffectType` |
| No version detection at all | — | no `getMinecraftVersion()` call, no capability probe; must be built from scratch |

### 1.3 The loader landscape (the headline finding)

**Minecraft 26.1 shipped unobfuscated.** Yarn is discontinued, intermediary is gone —
from 26.1, every loader (Fabric included) runs official Mojang names at runtime.
NeoForge has run Mojang names since 1.20.2. This removes the historical blocker for
LLM-generated code on modded loaders: on Fabric 26.1+ we can compile generated code
against the local game jar exactly like we do on Paper, zero remapping.

Per-loader feasibility (easiest → hardest):

1. **Paper** — done. Bonus: Geyser already translates 1.21.6+ dialogs to Bedrock forms, so Bedrock clients come nearly free (QA fidelity only).
2. **Fabric 26.1+** — low effort. Knot classloader permits child-classloader `defineClass` (our exact pattern); generated code = plain classes driven by the host, not Fabric mods (runtime mod/mixin registration is impossible, same as our mods aren't Paper plugins). Dialogs are a *vanilla* feature: the server can send `ClientboundShowDialogPacket` to vanilla clients directly; responses need **one static mixin** on `ServerCommonNetworkHandler` (no vanilla callback API). Commands: live Brigadier dispatcher injection + `sendCommandTree` resync. Prior art: KubeJS, Minescript, Cardboard — nobody has done in-memory *Java* compilation; the mappings wall (now gone) was why.
3. **NeoForge** — moderate. Mojmap since 1.20.2; `NeoForge.EVENT_BUS.register/unregister` is cleaner than `HandlerList`; blocker is fussier JPMS/ModLauncher classloader plumbing.
4. **Fabric ≤1.21.11** — high (would need a compile-vs-mojmap → tiny-remapper bytecode pipeline). **Skip.**
5. **Sponge** — technically fine, audience too small. **Skip** (SpongeNeo users can run the NeoForge build).
6. **Old Forge** — SRG runtime, declining. **Skip.**

Highest-leverage open check before the Fabric port: whether **adventure-platform-mod**
(Fabric+NeoForge, v6.x) implements `Audience#showDialog(DialogLike)`. If yes, DialogKit
can target Adventure types and become nearly platform-neutral.

---

## 2. Strategy

Three decisions fall straight out of the data:

1. **Don't chase the past; widen the present.** Backwards support stops at Paper
   1.20.6 (Java 21 floor, Brigadier-era API, ~85%+ of the live market). Spigot,
   ≤1.20.4, and Folia are explicitly unsupported — each would cost more than the
   Fabric port and buy single-digit percent share.
2. **Go sideways where the engine already fits.** Fabric 26.1+ and then NeoForge.
   The compile-and-hot-load engine ports 1:1; only thin platform bridges are new.
3. **The abstraction is the LuckPerms/Geyser shape, not Architectury.** Our core
   (LLM client, compiler harness, mod registry/lifecycle, store) barely touches
   Minecraft. A small SPI + per-platform bootstrap is the whole trick. Architectury
   is built for game-content mods and doesn't cover Paper at all.

And one principle that keeps it beautiful: **the running server is the validation
oracle.** We never ship per-version API jars or maintain compatibility tables for
generated code — the compiler classpath *is* the live server, compile diagnostics
feed the existing self-heal retry loop, and the prompt merely tells the model which
era it's writing for to cut retries. This is the structural advantage no offline
generator (Voxen, Kodari) has; every phase below preserves it.

### Support tiers (end state)

| Tier | Target | UI | Share |
|---|---|---|---|
| 1 | Paper/Purpur/Leaf 1.21.7 → 26.x | native dialogs (current UX) | ~70–80% |
| 2 | Paper 1.20.6 → 1.21.6 | chest GUI + anvil/chat-input fallback | ~+10% |
| 3 | Fabric 26.1+ | native dialogs (vanilla packets + response mixin) | modded servers |
| 4 | NeoForge 26.1+ | native dialogs (same, via event or mixin) | modded servers |
| — | Spigot, ≤1.20.4, Folia, Sponge, old Forge, Fabric ≤1.21.11 | unsupported | <10% combined, disproportionate cost |

---

## 3. Target architecture

```
vibemod/
  core/           # zero Minecraft deps (mostly exists already):
                  #   llm/ (OpenRouterClient, StreamScanner, ModelCatalog, PromptLibrary*)
                  #   compile/ (InMemoryCompiler, CompileResult)
                  #   gen/ (ModGenerator*, GeneratedProject)
                  #   store/ (ModStore, ModConfigs, JarExporter*)
                  #   registry lifecycle state machine (platform-free part of ModRegistry)
  platform-api/   # the SPI core calls into:
                  #   PlatformInfo      (name, mcVersion, capabilities)
                  #   ClasspathProvider (jars for javax.tools)
                  #   CommandBridge     (register/unregister/resync dynamic commands)
                  #   EventBridge       (register/unregisterAll generated listeners)
                  #   TickScheduler     (repeat/later/async, main-thread hop)
                  #   ChatBridge        (chat-capture mode)
                  #   VibeUi            (screen model -> renderer; see §5)
                  #   Messenger         (Adventure Component out; Audience per player)
  sdk/            # what GENERATED code compiles against: Mod, VibeContext,
                  #   ModCommandHandler + per-platform typed escape hatch
                  #   (ctx.bukkit() / ctx.fabric() ...). Stays tiny on purpose.
  paper/          # current plugin refactored onto platform-api (Tier 1 + Tier 2)
  fabric/         # host mod: Knot-parented BytesClassLoader, dialog-response mixin,
                  #   Brigadier bridge, END_SERVER_TICK scheduler
  neoforge/       # host mod: EVENT_BUS bridges, sendCommands resync
```

Notes:

- `*` = exists but needs platform-specific bits extracted (PromptLibrary gains a
  `PlatformProfile` parameter; ModGenerator's `Bukkit.getScheduler` hops move behind
  `TickScheduler`; JarExporter grows per-platform wrapper emitters).
- **Adventure is the shared text/UI currency** on all three platforms (native on
  Paper, via adventure-platform-mod on Fabric/NeoForge). We do not abstract over it.
- **Build system**: the loader ecosystems are Gradle-only (Fabric Loom / ModDevGradle).
  The multi-module split is the moment to migrate Maven → Gradle. Do it as its own
  commit with zero code changes; CI keeps producing the identical `VibeMod.jar`.
- The three `api/` sources embedded verbatim in `PromptLibrary` (lines 39–157) are
  duplicated from the real files — the refactor must keep them in lockstep or,
  better, generate the prompt constants from the `sdk/` sources at build time.

### The generated-mod contract across platforms

Generated mods stay what they are: plain classes implementing `Mod`, driven entirely
through `VibeContext` (`ctx.listen/repeat/later/command/action`). That contract is
already platform-neutral *in shape*; what varies per platform is:

- **the event vocabulary** (`org.bukkit.event.*` vs Fabric API callbacks vs NeoForge
  events) — we do **not** abstract this; generated code uses the native events of the
  host platform, because mappings are readable everywhere now and any event-facade
  would cap what mods can do,
- **the prompt profile** (imports whitelist, event/command cheat-sheet, enum-name
  era, few-shot examples),
- **the compile classpath** (ClasspathProvider).

So a mod generated on Fabric is a *Fabric-flavored* mod. `meta.json` gains
`platform` + `mcVersion` fields; a mod is portable across servers of the same
platform (recompile-on-boot already handles minor drift via the self-heal loop) and
explicitly non-portable across platforms — `/vibe make` on the other platform is the
migration path, and honestly the more fun one.

---

## 4. Phases

### Phase 0 — Seams, in place (no behavior change, still Paper 1.21.8 only)

Pure refactor inside the current module; ship as one release with byte-identical UX.

1. Introduce `PlatformInfo` + a `Capabilities` probe (`hasDialogs`, `hasCommandMap`,
   `jdkCompiler`, …) — capability checks, not version string comparisons. Today there
   is zero version detection; this is the foundation everything else gates on.
2. Extract `CommandBridge`, `EventBridge`, `TickScheduler`, `ClasspathProvider`
   interfaces; the current Bukkit code becomes their first implementation. The
   paperclip `libraries/`+`versions/` walk moves into `PaperClasspathProvider`.
3. Split `ui/` into **screen models** (what the 17 screens *say*: title, body,
   inputs, buttons, actions) and a `DialogRenderer` (how they're shown). DialogKit
   becomes the renderer; screens stop importing `io.papermc.paper.*` directly.
4. Parameterize `PromptLibrary` with a `PlatformProfile` record (platform name,
   version, import rules, enum cheat-sheet, few-shots). The Paper 1.21.8 profile is
   the only instance for now. Generate the embedded `api/` source constants from the
   real files at build time.
5. `JarExporter`: thread `api-version` and the command-registration snippet through
   the profile instead of hardcoding (`:134`, `:438`, `:475`).

Exit criterion: `mvn package` output behaves identically on the dev server; all
self-tests pass; `StoreSelfTest` still compiles all 569 stored sources.

### Phase 1 — Multi-module + Gradle migration

Split into `core` / `platform-api` / `sdk` / `paper` along the Phase-0 seams;
migrate to Gradle (Loom needed later anyway). CI: same artifact, plus a
**run-task server matrix** (`xyz.jpenilla.run-paper`) booting 1.21.8 and 26.x and
smoke-testing the pipeline: feed a canned generated-mod source through the compiler,
assert load + command execution. This matrix is our regression net for everything
after.

### Phase 2 — Paper Tier 2 (1.20.6 → 1.21.6)

1. Build `ChestUiRenderer` implementing the same screen models: chest inventories
   for menus/browsers, anvil GUI (AnvilGUI lib) or chat-prompt capture for text
   input, chat click-components for confirmations. This is the one genuinely new
   subsystem — dialogs' text fields/sliders map imperfectly; accept a degraded but
   complete UX. Renderer selection via the `hasDialogs` capability.
2. Lower `plugin.yml` `api-version` to `'1.20'`; keep plugin build at `--release 21`
   (already true). Gate `setEnchantmentGlintOverride` and other 1.20.5+ calls behind
   capabilities.
3. Add per-era `PlatformProfile`s: inject detected MC version into the prompt with
   era constraints ("target 1.20.6: pre-1.21.3 attribute names `GENERIC_MAX_HEALTH`…",
   correct Sound/Particle names). The compiler-diagnostics self-heal loop is the
   backstop; the profile just cuts retries.
4. Legalize reality in the prompt rules: generated mods already use Adventure (88+
   files) — since Spigot is out of scope, officially allow `net.kyori.adventure.*`
   imports on Paper profiles and drop the fiction.
5. Extend the CI matrix down to 1.20.6; `StoreSelfTest` variant compiling the stored
   corpus against each tier's API to map real-world breakage.

### Phase 3 — Fabric 26.1+ host mod

1. `fabric/` bootstrap: implements `platform-api`. `BytesClassLoader` parented to
   the host mod's Knot classloader (pattern ports 1:1).
2. `FabricClasspathProvider`: unobfuscated game jar + fabric-loader + fabric-api
   jars from `mods/` — all already on the server's disk; nothing redistributed.
3. Dialogs natively: build vanilla `Dialog`s, `ServerPlayerEntity.openDialog(...)`;
   **one static mixin** on `ServerCommonNetworkHandler` routes
   `CustomClickActionC2SPacket` responses into our callback registry. (First: check
   whether adventure-platform-mod already ships `showDialog` — if so, DialogKit
   targets Adventure `DialogLike` and Paper/Fabric share the renderer.)
4. `CommandBridge`: live `CommandDispatcher` injection + `sendCommandTree` resync.
   `TickScheduler` on `ServerTickEvents.END_SERVER_TICK`. Permissions via Fabric
   Permissions API with op-level fallback.
5. Fabric `PlatformProfile`: native Fabric API events + Brigadier in the cheat-sheet
   (mind the 26.1 renames, e.g. `ItemGroupEvents` → `CreativeModeTabEvents`); rule:
   **no mixins in generated code** (unenforceable at runtime — mixins bind at class
   load; state it and let compile/load errors catch attempts).
6. Constraint to accept: generated mods are plain classes, not Fabric mods — no
   runtime registry content (items/blocks). Same limitation as Paper, so feature
   parity is natural.

### Phase 4 — NeoForge host mod

Mostly `fabric/` transposed: EVENT_BUS register/unregister (cleaner than
`HandlerList`), `Commands#sendCommands` resync, ClasspathProvider over `libraries/`.
The work is in ModLauncher/JPMS classloader plumbing — child loader in the GAME
layer's unnamed-module space. Start after Fabric proves the shape.

### Continuous

- **bStats** in all bootstraps: let real install data (platform/version histogram)
  decide whether Tier 3 backwards (1.17–1.20.4) or Folia ever get built.
- Docs: per-platform install pages; `ARCHITECTURE.md` gains the module map.

---

## 5. Design invariants (the "beautifully" constraints)

1. **Core never imports a platform.** Enforced by module boundaries after Phase 1
   (core has no Minecraft dependency at all, so it can't cheat).
2. **Capability checks, not version checks**, everywhere a feature gates.
3. **The live server is the compile classpath.** No shipped API jars, no offline
   compatibility tables. Prompt profiles are an optimization, diagnostics are the
   contract.
4. **Adventure is the lingua franca** for text/UI on every platform; screens are
   data, renderers are per-capability (`DialogRenderer` / `ChestUiRenderer`).
5. **Generated mods are platform-native**, version-stamped in `meta.json`, and never
   pretend to be portable across platforms. The SDK facade stays tiny.
6. **Every tier in CI or it doesn't exist**: a platform/version is "supported" only
   while the run-task matrix boots it and the smoke test passes.

## 6. Risks & open questions

- **Dialog API is still `@Experimental`** on Paper — 26.x releases can break the 17
  screens; the screen-model split (Phase 0.3) is also our shock absorber for that.
- **adventure-platform-mod `showDialog` support** — unverified; determines whether
  Fabric reuses DialogKit or gets its own renderer. Check first in Phase 3.
- **ChestUiRenderer scope creep** — dialogs express forms; chests don't. Timebox it:
  degraded-but-complete beats pixel-parity. If Tier 2 telemetry stays tiny, consider
  dropping it before building more on it.
- **Stored-corpus migrations** — precedent exists (`scripts/migrate-mods-package.sh`,
  the `@Deprecated api.VibeMod` bridge); the sdk split must keep both tricks working.
- **Prompt-era confusion** — LLM training data mixes API eras; each backward tier
  roughly doubles prompt-tuning surface. Another reason the floor is 1.20.6.
- **26.x versioning cadence** — Mojang's year-based scheme may mean faster API churn;
  the capability-probe design is the hedge.

## 7. Effort ballpark

| Phase | Size | Risk |
|---|---|---|
| 0 seams | ~1 week equiv. | low (pure refactor, self-tests guard) |
| 1 modules+Gradle | days | low-medium (build churn) |
| 2 Paper 1.20.6+ | 1–2 weeks | medium (ChestUiRenderer is the bulk) |
| 3 Fabric | 1–2 weeks | medium (one mixin, new prompt profile) |
| 4 NeoForge | ~1 week after Fabric | medium (classloader plumbing) |

Sequencing note: Phases 2 and 3 are independent after Phase 1 — if modded reach
excites more than backwards reach, do Fabric first; the market data mildly favors
that order too.
