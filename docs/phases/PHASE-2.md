# Phase 2 brief — resources: `data/**` datapacks, `assets/**` runtime resource pack, ReloadCoordinator

Prerequisites: `docs/phases/PHASE-0-RESULT.md`, `PHASE-1-RESULT.md` (surgeon, EventFanout, CommandSeam, client entrypoint, keybind/HUD seams all exist and are gated). Same hard constraints (module Java levels; loader-common never names `net.fabricmc.*`; no silent drops; javap-verify unlisted signatures; all gates grow, never weaken — build/surgeonSelfTest 38, smoke-fabric 49/49, smoke-neoforge 31/31, client gate 56/56).

## Goal

A generated mod ships resource files exactly like a real mod jar would — `data/<ns>/recipe/ruby_sword.json`, `assets/<ns>/models/item/ruby.json`, `assets/<ns>/textures/item/ruby.png` (as a pixel grid), `assets/<ns>/lang/en_us.json` — and they take effect in the live game: recipes craftable, advancements granted, models/textures/lang visible on the client, all gone on unload. No new mod-facing Java API.

## Verified 26.2 facts (javap'd; re-verify anything else you need)

- `MinecraftServer.reloadResources(Collection<String>) → CompletableFuture<Void>`; `getPackRepository()`; `getWorldPath(LevelResource)`; `LevelResource.DATAPACK_DIR`. Vanilla's folder `RepositorySource` scans `<world>/datapacks/` — **no server-side repository injection needed**.
- `/reload` re-reads recipes/advancements/functions/tags/loot/predicates/item-modifiers. Worldgen-layer registries (ENCHANTMENT, DIALOG, DAMAGE_TYPE, JUKEBOX_SONG, PAINTING_VARIANT…) load at world load only → if a mod ships one, keep the file but report "applies on next world load" in the install card/logs, not a failure.
- Client: `Minecraft.reloadResourcePacks() → CompletableFuture<Void>` (public no-arg), `Minecraft.getResourcePackRepository()`. `PackRepository.sources` is `private final Set<RepositorySource>` — **client-side injection requires a mixin** (constructor tail or an accessor adding to the set before first `reload()`; fabric-resource-loader does the ctor approach — copy its shape). Client-only mixins go in the `"client"` array of `vibemod.mixins.json`.
- `Pack.readMetaAndCreate(PackLocationInfo, Pack$ResourcesSupplier, PackType, PackSelectionConfig)`; `PackLocationInfo(String id, Component title, PackSource, Optional<KnownPack>)`; `PackSelectionConfig(boolean required, Pack$Position, boolean fixedPosition)`; `PathPackResources(PackLocationInfo, Path)`. `PackLocationInfo`/`PackSelectionConfig` live in `net.minecraft.server.packs` (not `.repository`). javap-verify the current `pack.mcmeta` `pack_format` (read `SharedConstants`/`WorldVersion` — or read it off vanilla's own pack metadata at runtime, which is version-proof).

## Deliverables

### A. Store: non-Java files
- LLM output contract: `files[]` may include `data/**` and `assets/**` paths (any text file; textures as `.png.grid` pixel-grid JSON — see D). Java files keep the existing rules. `ModStore` persists them under the version dir alongside sources; `sources()` returns only `.java` to the compiler (verify current filter); add `resources()` returning the rest. Namespace: every `data/<ns>/`/`assets/<ns>/` path is **rewritten to the mod's canonical namespace** `vibemod_<modname-lowercased-sanitized>` regardless of what the model wrote (reuse/extract the sanitize helper from `LoaderDialogRenderer.Bindings.sanitize`), and occurrences of the model's chosen namespace inside JSON bodies (`"<ns>:`) are rewritten the same way — so collisions are structurally impossible and few-shots can use a natural namespace. Recipe/model ids the mod references in code (rare in Phase 2) are the mod's problem; the prompt states the canonical namespace rule.

### B. Datapack channel (server, loader-common + hosts)
- On load/enable of a mod with `data/**`: materialize `<world>/datapacks/vibemod-<mod>/` (pack.mcmeta + files; staging dir + atomic rename), then schedule a reload via the ReloadCoordinator (C). On disable/unload: delete the dir, schedule reload (never skip — level.dat remembers selected pack ids and would warn "Missing data pack" forever).
- Selection: after `repo.reload()`, `reloadResources(currentSelected ∪ ours)` — verify how vanilla auto-enables world datapacks (feature "enabled by default"? check `WorldDataConfiguration`); explicitly include our ids to be safe.
- Tracked as `ModHandle.Kind.CONTENT` (add the constant; drain order: content last). The Registration's close only marks dirty — the coordinator flushes on a later tick, **never synchronously inside drain** (a reload takes 200ms–2s vs the 250ms watchdog).

### C. ReloadCoordinator (loader-common)
- Debounce: first dirty mark arms a ~40-tick timer (ticked from the host's existing END_SERVER_TICK subscription); more marks re-arm; flush runs at most one `reloadResources` at a time (chain on the returned future; if dirtied during a reload, run once more after). Client-pack flush analogous on the render thread via `Minecraft.reloadResourcePacks()` (hop with `Minecraft.getInstance().execute`), also single-flight. Boot restore of N mods must coalesce to ≤2 server reloads (assert `≤` in the gate, not `==`).
- Expose `describeState()` (pending/dirty/reload count) for gates.

### D. Runtime client resource pack (fabric client)
- One pack `vibemod/respack` rooted at `<gamedir>/vibemod/respack/` merging every live mod's `assets/**` (per-mod subtrees are fine if merged logically — simplest: write all mods into one tree with per-mod manifests for exact cleanup). Client-only mixin injects a `RepositorySource` into the client `PackRepository` (required=true, fixed position, `PackSource.BUILT_IN`); joins the repo before first reload. Stale guard: on client init, clear the respack tree of mods not currently in the store (crash residue), and the pack contributes nothing when empty.
- `.png.grid` → PNG: hand-rolled encoder (`java.util.zip.Deflater` + `CRC32`, RGBA, no filters or filter 0 per scanline; ~60 lines; **no `java.desktop`**). Grid format: `{"palette": {"a": "#RRGGBB" | "#RRGGBBAA" | "transparent"}, "rows": ["aab…", …]}` — square, ≤64×64. Malformed grid → generation diagnostic (self-heal), not a runtime crash.
- Singleplayer/LAN-host only (client repo exists). On a dedicated server, `assets/**` are stored but inert; log one line. (Pack-server push is a later phase.)
- Lifecycle: mod enable/disable/unload marks the client pack dirty → coordinator reloads (the ~1–3s resource reload overlay is acceptable, once per batch).

### E. Prompt
- Native FABRIC profile: lift the resources ban; document `data/**`/`assets/**` conventions, the canonical-namespace rule, the `.png.grid` texture format, "recipes/loot/advancements/tags/functions apply live; enchantments/dialogs/damage types on next world load". ONE new few-shot: a custom-ish item done the vanilla-data way (e.g. a `RubyCharm`: recipe for a renamed/lored vanilla item via `minecraft:crafting_shaped` with components in the result + an advancement + lang entry) — verify 26.2 recipe JSON shape against vanilla data (unzip a recipe from the server jar; do not write from memory). Registry.register stays banned (Phase 3). Budget: fabric profile ≤ 26k chars; update `LlmSelfTest`.

### F. Gates
- `smoke-fabric.sh`: `ResourceCanary` heredoc mod with `data/**` (a recipe granting something checkable + an mcfunction or advancement observable over RCON) — assert: datapack dir exists after load; recipe works (`recipe give @a` succeeds or craft via commands); `vibe unload` → dir gone, one final reload logged, reload count across boot ≤ threshold; no "Missing data pack" warning after unload+reboot cycle if the script does one.
- Client gate: canary ships `assets/**` (model+grid texture+lang) and `data/**`; assert respack registered in the client repo, the resource resolves post-reload (e.g. lang key translates / `Minecraft.getResourceManager().getResource(id)` present), teardown removes it after unload (resource absent after the next reload), coordinator `describeState()` sane.
- `smoke-neoforge.sh`: stays 31/31; a `data/**`-shipping mod on NeoForge should also work if the channel is loader-common + vanilla API only — implement it loader-neutrally and add a NeoForge datapack assert if it works there for free; if anything Fabric-specific blocks it, keep NeoForge inert-with-one-log-line and assert that instead. `assets/**` on NeoForge: inert, logged.
- Surgeon/compiler: resources bypass the compiler entirely — assert a mod with only resource changes still round-trips versions.

## Working rules
Same as before: javap/unzip-verify (including vanilla JSON shapes), Edit tool, run all four gates yourself, don't commit, write `docs/phases/PHASE-2-RESULT.md` with decisions/deviations/verbatim gate tails.
