# Phase 4 brief — docs, demo, prompt budget, full-matrix hardening

Prerequisites: `PHASE-0/1/2/3-RESULT.md`. This phase writes down what was built, proves it end-to-end with the real LLM, and hardens the matrix. Code changes are limited to what the demo/audit reveals — no new capabilities.

Current gate baseline (never weaken): build (surgeonSelfTest 57 + RegistryLedger suite + store/prompt asserts), smoke-fabric 89/89, smoke-neoforge 44/44, client gate 114/114, prompt budget fabric ≤30k.

## Deliverables

### A. `docs/ARCHITECTURE-V3.md`
Written in the same voice/rigor as ARCHITECTURE-V2.md. Must contain:
1. The decision log (numbered, like V2 §0): seam architecture over bespoke API (LLMs write in-distribution code; host intercepts at choke points), instruction-walk over pool-scan, unfreeze-window-around-onInitialize (the intrusive-holder finding), tombstone semantics, block-registration refusal (PalettedContainer bit-width mechanism), dedicated-server registry refusal, "no silent drops" as the house rule, ModDispatch/attribution threading.
2. The verified-facts table (26.2 + fabric-api signatures the phases javap'd, incl. the corrections: KeyMappingHelper, identifier(), min_format/max_format, no SwordItem, CreativeModeTabEvents).
3. The surgeon spec: policy allowlist/denies, the four+1 bootstraps, seam table (all entries with descriptors), shape-preservation guarantee, byte-identical pass-through.
4. Shim semantics: fanout merge rules per return type, replay semantics (commands, lifecycle), thread guards, the 8-slot keybind pool mapping, HUD adaptation, screen hygiene, registry ledger/tombstones, snapshot/rollback on refusal.
5. Teardown matrix: per capability, what disable vs unload does and what (documented) residue remains (registry entries, world items with live code paths, level.dat pack ids).
6. What each phase's gates found (the ~10 real bugs — pull from the four RESULT docs; they're the proof the gates work).
7. Out-of-scope list (blocks, dedicated-server registry sync/pack-server, NeoForge seams, mixins in generated code) with reasons, so nobody "helpfully" adds them.

### B. README / CHANGELOG / DEMO
- README: rewrite the Fabric capability story ("a generated mod is a normal Fabric mod: events, commands, keybinds, HUD, screens, data/ + assets/ resources, real registered items — hot-loaded and hot-unloaded"). Keep Paper/NeoForge sections accurate (NeoForge: legacy VibeContext path + datapack channel; native seams are Fabric-only for now). Update the "Why mods aren't plugins" section to describe the surgeon.
- CHANGELOG: 3.0.0 entry.
- DEMO.md: six prompts impossible in 2.0, e.g. (1) "a ruby sword with a custom texture, crafted from rubies you get by smelting redstone" (registry + assets + data), (2) "a /home command with a 30s cooldown and a HUD timer", (3) "a boss bar bee invasion every dawn that drops honey armor recipes", (4) "a keybind that toggles X-ray-style glowing on nearby ores" (client), (5) "an emerald shop screen", (6) "a pet wolf that fights for me and teleports to my side". For each: what the mod uses (which seams), and the expected shape.

### C. End-to-end with the real LLM
- Requires `OPENROUTER_API_KEY` (resolve via config/env/`~/.config/vibemod/openrouter.key` — the host's existing order). If no key is available in this environment, run everything except the live-LLM step and mark it clearly as the one unexecuted item in the result doc — do not fake it.
- Drive `./gradlew :fabric:runServer` (dedicated, headless-friendly) with RCON `/vibe make` for at least demo prompts (1) and (2) — assert generation → self-heal (if any) → live → exercise → `vibe unload` → zero residue (datapack gone, ledger updated, commands gone). If a client is feasible for one run, do (1) on `runClient` and verify the texture renders; otherwise state it.
- Record transcripts (prompt, rounds, repair causes) in `docs/phases/PHASE-4-RESULT.md` — these are the honest demo evidence and will surface prompt weaknesses.
- Fix what the runs reveal (prompt wording, oracle gaps, diagnostics clarity) — this is the hardening loop. Re-run gates after any fix.

### D. Audit + polish
- Prompt token audit: print all profile budgets in `LlmSelfTest`; fabric ≤30k chars asserted; check the repair prompt + oracle hints stay bounded.
- `/vibe info <mod>` shows native-mod facts (entrypoints, event subscriptions count, commands, registered content from the ledger) — verify it does; if the UI still assumes VibeContext-only, patch the info screen minimally.
- Sweep the four RESULT docs' "notes worth carrying" for anything unaddressed (describeState substring caveat; CommandBuildContext lifetime; ClientSeam client-type hygiene; boolean-merge default assumption) — either address or record as accepted with reasons in ARCHITECTURE-V3 §out-of-scope/notes.
- CI: extend `.github/workflows/build.yml` if any new gate task isn't already covered (surgeonSelfTest runs in `check` so `build` covers it; smoke scripts already in the matrix — verify nothing new needs wiring).

### E. Gates
Full matrix at the end, all four + smoke-paper.sh 1.21.8 (Paper must still be untouched-green): paste verbatim tails in the result doc.

## Working rules
Same as before. Don't commit. Write `docs/phases/PHASE-4-RESULT.md`.
