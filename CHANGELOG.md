# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-08-25

First public release.

### Added

- **Prompt-to-gameplay**: `/vibe make <prompt>` asks an LLM (via OpenRouter) for Java, compiles
  it in-process with `javax.tools`, and hot-loads it into the live Paper 1.21.8 server — each mod
  in its own child classloader with exact per-instance teardown (no restarts, no reloads).
- **Self-healing generation**: compile errors are fed back to the model for repair rounds;
  repair/edit rounds may answer with SEARCH/REPLACE edit blocks, with automatic full-project
  retry when a block fails to apply.
- **Streaming generation**: SSE-streamed completions drive a plan-aware boss bar with a live
  scrolling ticker, plus configurable generation concurrency with queue visibility.
- **Dialog-based UI**: the entire interface is native Paper dialogs with one design language —
  bare `/vibe` opens the mod browser; per-mod config (sliders/checkboxes/dropdowns), settings
  form, prompt/edit dialogs, and scrollable manual/source/error viewers with Markdown manuals.
- **Live model catalog & cost tracking**: a model picker dialog and tab completion over the full
  OpenRouter catalog, configurable reasoning effort, real usage-based cost visibility via
  `/vibe costs`, and per-version cost/requester/changelog in `/vibe history`.
- **Legible, tunable mods**: generated mods declare config knobs read live (no reload),
  model-written player manuals extended with introspected verified facts, and install cards
  with clickable actions.
- **Debuggability**: runtime errors are deduped into per-mod logs (`/vibe errors`); a throwing
  mod is marked degraded but keeps running; `/vibe fix` sends recent errors to the model for a
  surgical repair round; `/vibe debug` echoes a mod's logging live to ops.
- **Safety nets**: a tick-time watchdog auto-disables mods that stall the main thread, an
  error-storm detector auto-disables mods that throw repeatedly, and `/vibe panic` unloads
  every mod at once.
- **Version history & rollback**: every generation is a stored version; browse and re-activate
  any of them, or `/vibe rollback` one step.
- **Jar export**: `/vibe export` emits a genuine standalone Paper plugin jar (with seeded
  config.yml and readable source tree) that boots on a plain server.
- **Runtime /commands**: mods can register real top-level commands, unregistered cleanly on
  disable via swappable handlers.
- **Live reload**: `/vibe reload` re-reads config.yml (model, timeouts, watchdog budgets,
  retries, concurrency, error-storm thresholds) without a restart.

[1.0.0]: ../../releases/tag/v1.0.0
