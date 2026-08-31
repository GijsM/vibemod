# VibeMod architecture — the map

There are **two** architecture documents, and both are contract:
**[docs/ARCHITECTURE-V4.md](docs/ARCHITECTURE-V4.md)** for the Fabric seam architecture, and
**[docs/ARCHITECTURE-V2.md](docs/ARCHITECTURE-V2.md)** for everything V4 did not touch. This file
is the one-page map to them, and to the three documents that are history rather than contract.

## Where things are

| | |
|---|---|
| **[docs/ARCHITECTURE-V4.md](docs/ARCHITECTURE-V4.md)** | **The seam architecture** (Fabric, 26.2). Why generated mods are plain Fabric mods; the bytecode surgeon's policy and all eighteen seams; the shim semantics for events, commands, keybinds, HUD, screens, resources and registries; **runtime blocks** — the palette guard, the boundary crossing, and why a block id is pinned forever rather than tombstoned; the **teardown matrix**; a table of 26.2 facts each read off the jars with `javap`, including the ones that overturned V3's; what every gate found; and the out-of-scope list with the mechanism behind each refusal. |
| **[docs/ARCHITECTURE-V2.md](docs/ARCHITECTURE-V2.md)** | **The multi-platform architecture.** The module graph, the host SPI, the screen model, the two sdk flavors, `meta.json` v3, the prompt profiles, the compilation pipeline, the client design — and §10, a phase-by-phase record of what actually landed, what deviated, and what is still open. Still authoritative for Paper, for NeoForge, and for the `VibeContext` flavor. |
| [docs/ARCHITECTURE-V3.md](docs/ARCHITECTURE-V3.md) | **History.** The seam architecture as of 3.0.0, before runtime blocks. Carried forward whole by V4 rather than replaced piecemeal, so read it only to see what was believed and why it changed — its Decision 8 and its §10 refuse blocks on a fact that turned out to be wrong, and V4 §2.1 says exactly how. |
| [docs/ARCHITECTURE-V1.md](docs/ARCHITECTURE-V1.md) | **History.** The frozen contracts of v1/v2/v3, when VibeMod was one Paper 1.21.8 plugin. Useful for understanding why `core/` looks the way it does; several of its rules no longer hold, and its header says which. |
| [docs/PLATFORM-EXPANSION.md](docs/PLATFORM-EXPANSION.md) | **The original research.** The market survey and the plan that argued for going multi-platform, written before any of it existed. V2 is what got built. |
| [README.md](README.md) | Install, usage and supported versions, per platform. |
| [CHANGELOG.md](CHANGELOG.md) | What changed, release by release. |

## The shape, in one screen

VibeMod turns a player's prompt into Java, compiles it **in-process**, and loads it into its own
child classloader with every registration tracked so it can be torn down exactly. That core idea
is unchanged since v1. What 2.0 changed is that none of it knows what platform it is on.

```
                       ┌───────────────────────────────────────────┐
   /vibe make …  ──►   │ core/    the engine. Names no platform.    │
                       │   llm · compile · store · gen · screens · │
                       │   both renderers · /vibe routing          │
                       └────────────────┬──────────────────────────┘
                                        │ platform-api/  (the SPI: scheduler,
                                        │   events, commands, messaging,
                                        │   compiler, classpath, capability probe)
              ┌─────────────────────────┼─────────────────────────┐
              ▼                         ▼                         ▼
        ┌───────────┐            ┌────────────┐            ┌─────────────┐
        │  paper/   │            │  fabric/   │            │  neoforge/  │
        │  Bukkit   │            │   Loom     │            │     MDG     │
        └───────────┘            └─────┬──────┘            └──────┬──────┘
                                       │   loader-common/         │
                                       └────── shared source ─────┘
                                          (Mojang-typed: dialogs, mod host,
                                           command bridge, client surface)

   sdk/         the contract generated code writes against. Two flavors, same
                class names: Bukkit-typed for Paper, Mojang-typed for both loaders.
   sdk-client/  the HUD / keybind / client-tick contract. Pure JDK, no game types.
```

Three rules hold this together, and each one is load-bearing rather than tidy:

1. **`core` names no platform type.** Enforced by a Gradle check (`:core:checkPlatformFree`)
   that fails the build on `org.bukkit`, `io.papermc` or `net.minecraft` in core's sources.
2. **Capabilities are probed, never version-compared.** Whether the server has the dialog API,
   a system compiler, a client, a native command map, an item-glint override — every one is a
   question asked at runtime through `PlatformInfo`, which is why the Paper floor could drop from
   1.21.8 to 1.20 without a single `if (version >= …)`. The drop shipped as "1.20.6" and the
   later version sweep found the code had been working four releases lower the whole time —
   which is the rule paying off, not an exception to it. What actually stops the plugin below
   1.20 is the `api-version: '1.20'` declaration in `plugin.yml`, not a capability.
3. **A host owns every subscription.** Generated mods never register with the platform directly;
   the host dispatches and revokes on their behalf. On Fabric that is not a preference — Fabric
   events cannot be unregistered at all, so anything else would leak a listener per load.

## Ground rules that still apply

- Every public class gets a short javadoc, in the style of its neighbours.
- No `-Werror`; the dialog API is `@Experimental` and its warnings are accepted.
- **The stored corpus must keep compiling.** The `com.gijsm.vibemod.api` surface is frozen per
  flavor: mods already on someone's disk — including pre-rename ones that
  `implements VibeMod` — have to recompile on restore-on-boot. `StoreSelfTest` checks this
  against a checked-in fixture corpus on every build, and against a real 569-source corpus
  wherever one exists.
- Thread rules are per platform and are written into the prompt profiles and the SDK javadoc:
  Bukkit API on the main thread; on loaders, server work on the server thread and client work on
  the render/client thread, with the host doing the hops.
- Never hardcode an API key. Resolution order everywhere: the config file →
  `$OPENROUTER_API_KEY` → `~/.config/vibemod/openrouter.key`.
