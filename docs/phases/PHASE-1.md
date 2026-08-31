# Phase 1 brief — command replay, client seams (ClientModInitializer, keybinds, HUD, screens)

Prerequisite reading: `docs/phases/PHASE-0.md` + `PHASE-0-RESULT.md` (built and gated — the surgeon, `Shims`/`EventFanout`, `EntrypointAdapter`, `ModAttribution`, `SymbolOracle`, native FABRIC profile all exist). Same hard constraints as Phase 0 (module Java levels; loader-common never names `net.fabricmc.*`; no silent drops; javap-verify unlisted signatures; all existing gates stay green — build, smoke-fabric 37/37, smoke-neoforge 31/31, client gate 36/36; grow them, never weaken them).

## Goal

A plain Fabric mod can now: register commands via `CommandRegistrationCallback` (hot: available immediately, removed on disable), implement `ClientModInitializer` for its client half, lease a keybind via `KeyBindingHelper.registerKeyBinding`, draw HUD via `HudElementRegistry`, open its own `Screen` subclass — all revocable, all through the existing seams/shims, no new mod-facing API.

## Deliverables

### A. Command replay (`CommandRegistrationCallback`)
- Remove `CommandRegistrationCallback` from `EventFanout`'s registration-style deny set; give it dedicated handling in the fanout (or a sibling `CommandSeam` in `fabric/.../shim/`):
  - On registration by a live mod: invoke the callback **immediately** against `server.getCommands().getDispatcher()` (plus the real `CommandBuildContext` — build one via `Commands.createValidationContext`/`CommandBuildContext.simple` — javap-verify what 26.2 offers; check how vanilla constructs it in `Commands.<init>` or where Fabric fires the callback) and the right `Commands.CommandSelection`.
  - **Diff the dispatcher root's children before/after** the callback to learn which literals the mod added; track each as a Registration (Kind.NATIVE or COMMAND) whose close removes the nodes reflectively — reuse/extract the removal + `Commands.sendCommands` client-resync logic from `loader-common/.../LoaderCommandBridge.java` (:239-274) rather than duplicating it. Guard against a mod overriding an existing literal (diff shows no new child but a replaced one): detect by comparing node identity before/after; on collision, restore the old node and journal a clear error.
  - On datapack reload (`CommandRegistrationCallback` re-fires into the host — see `VibeModFabric.java` :191-196 pattern): replay every live mod's stored callbacks into the fresh dispatcher under attribution + `ModDispatch`, then resync.
  - Name collisions with `/vibe` and other mods' commands: first registration wins; the loser gets a journalled error, not silence.
- Gate the Phase-0 leftover: immediate-replay of `SERVER_STARTING/STARTED` — the client gate can seed+enable a native mod **mid-session** (the `seedOne` + `router().run(console, {"enable", name})` pattern already exists), which is exactly the late-load case.

### B. Client entrypoint + client events
- `FabricEntrypointAdapter` (or a second adapter wired into `LoaderModHost`) also detects `net.fabricmc.api.ClientModInitializer`; on a physical client, after `onInitialize`, run `onInitializeClient()` on the **render thread** (hop via `Minecraft.getInstance().execute` — javap-verify; keep the server-side load path synchronous and let the client init be a tracked deferred step) under `ModAttribution` with failures journalled `where="onInitializeClient"`. On a dedicated server a `ClientModInitializer`-only class is an error; a class implementing both just skips the client half (mirror `ctx.client(...)`'s inert-on-server semantics).
- `EventFanout` learns client events: callback types under `net.fabricmc.fabric.api.client.` are now **allowed when registered from the client entrypoint on a physical client** (still refused server-side, with the same clear UOE). Their dispatch expects the render thread (`Minecraft.getInstance().isSameThread()` — verify) and uses the render-thread watchdog (`LoaderClientEventBridge`'s watchdog/`guard` pattern at :327-350) with instant-detach-on-throw for per-frame events (`ClientTickEvents`, `LevelRenderEvents`, `HudElementRegistry`-adjacent). Verify `LevelRenderEvents` field names/context accessors with javap before wiring (rendering-v1 25.3.2: `LevelRenderEvents.BEFORE_GIZMOS/END_MAIN`, contexts expose `poseStack()/submitNodeCollector()`; extraction contexts `level()/camera()/deltaTracker()`).

### C. Keybind seam
- Surgeon seam table += `net/fabricmc/fabric/api/client/keybinding/v1/KeyBindingHelper.registerKeyBinding(KeyMapping)KeyMapping` (javap-verify exact owner/descriptor in fabric-api 0.158.0) → `Shims.registerKeyBinding`.
- Implementation: lease one of the 8 pre-registered slot `KeyMapping`s (existing pool in `LoaderClientEventBridge` — reuse its lease bookkeeping, don't fork it) and **return the slot's KeyMapping**, so the mod's `consumeClick()/isDown()` polling just works. Pool exhausted → clear ISE (journalled). Released on drain. The requested mapping's translation key/category are recorded for `describeState()`/UI.

### D. HUD seam
- Surgeon seam += `HudElementRegistry.addLast(Identifier, HudElement)` and any sibling add methods generated code plausibly calls (javap the class; rewrite all `add*` overloads) → `Shims.hudAdd`: attach to the host's existing tracked HUD pipeline (the single permanent `vibemod:mods` element), adapting `HudElement.render(...)` (javap its SAM shape) to the internal renderer list; watchdog + instant-detach like existing HUDs; tracked, drained.

### E. Screen hygiene
- On disable/unload of a mod, if `Minecraft.getInstance().screen`'s class was defined by that mod's classloader (`screen.getClass().getClassLoader() instanceof ModLifecycle.BytesClassLoader` + identity match with the mod's loader), close it (`setScreenAndShow(null)` on the render thread — verify null is legal; else `player.closeContainer()`/`popGuiLayer` equivalent). Zero mod-facing API. Policy: `net/minecraft/client/gui/screens/Screen` subclassing stays allowed (it already is — allowlisted root).

### F. Prompt + oracle
- Native FABRIC profile: lift the "do not use CommandRegistrationCallback/KeyBindingHelper/HudElementRegistry" bans; add short guidance (commands are hot and removed on disable; keybinds come from a shared 8-slot pool — the physical key may differ from the one requested; client code goes in `ClientModInitializer`, render thread, never touch server state from it; the singleplayer shared-JVM race warning from the legacy profile's threading block, restated briefly). Add ONE new few-shot exercising `CommandRegistrationCallback` + `ClientModInitializer` + keybind + HUD together (e.g. CoordToggle rewritten natively). Registry/resources still banned (Phases 2–3). `LlmSelfTest` assertions + budget updated (fabric profile should stay ≲ 20k chars).

### G. Gates
- `VibeModClientGateTest` additions: a native canary registering `/nativecmd` (executes → marker file), a keybind (press via `context.getInput().pressKey`, slot released after disable and re-leasable — mirror the existing slot test), a HUD via `HudElementRegistry` (frame counter > 0; detached on disable), its own `Screen` (opened via its command, auto-closed on disable), mid-session seed+enable exercising SERVER_STARTING replay, and command removed after disable (`sendCommand` → no marker growth) + back after enable.
- `scripts/smoke-fabric.sh`: native canary registers a command; RCON executes it; assert output; disable → command gone (execute fails or unknown), enable → back. Dedicated server: `ClientModInitializer` half inert (no crash, log line).
- `scripts/smoke-neoforge.sh`: unchanged green (client seams are all fabric-module; loader-common additions must be loader-neutral).

## Working rules
Same as Phase 0: javap before wiring, Edit tool for source, run all four gates yourself, don't commit, write `docs/phases/PHASE-1-RESULT.md` (decisions, deviations, verbatim gate tails).
