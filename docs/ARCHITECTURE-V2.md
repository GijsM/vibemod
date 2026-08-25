# VibeMod v2 Architecture — Multi-Platform

*Status: authoritative for the platform-expansion migration (Phases B–F). Written by the
Phase A architecture agent, 2026-08-25. Companion documents: `docs/PLATFORM-EXPANSION.md`
(research + strategy) and the approved migration plan. Where this document and the plan
disagree on a detail, this document wins — it is the one implementation agents execute.*

Skeleton sources for every interface named here exist under `platform-api/`, `sdk/` and
`sdk-client/` at the repo root. They are the contract; this document is the semantics.

---

## 0. Decision log (locked; do not relitigate in Phases B–F)

| # | Decision | Why |
|---|---|---|
| 1 | UI fallback is **plain chat**, never chest/anvil GUIs | User decision; keeps one fallback renderer, chat maps cleanly to screen models |
| 2 | Generated mods get **full client capabilities** on Fabric/NeoForge v1: HUD, keybinds, client tick, sounds, toasts. Excluded v1: world-render hooks, custom screens, raw input, networking, mixins | User decision; exclusions bound the blast radius (a bad render hook hard-crashes the client) and the prompt surface |
| 3 | **NeoForge only**, no legacy MinecraftForge | SRG names at runtime would force a remapping pipeline; ecosystem moved on |
| 4 | Loaders target **MC 26.1+** only | 26.1 is unobfuscated — generated code compiles against Mojang names with zero remapping; ≤1.21.11 Fabric would need an intermediary pipeline |
| 5 | **Paper floor 1.20.6**; Spigot, ≤1.20.4, Folia unsupported | Market data: <5% share for everything below, each with disproportionate cost |
| 6 | **No VibeMod↔VibeMod networking v1** — servers never push generated client code to players | That is remote code execution on player machines; needs consent/signing UX. Client features = singleplayer/LAN-host. Client-local mode on third-party servers is v1.1, config-flagged, chat-only |
| 7 | **The live game is the compile classpath** — never ship or pin API jars for generated code | The running server validates generated code against exactly itself; compile diagnostics feed the existing self-heal loop |
| 8 | **Capability probes, not version checks** | Version strings differ per platform/fork; capabilities compose |
| 9 | **`sdk-client` is pure JDK** (`HudCanvas` facade, state getters, `Object` escape hatch) — deviation from the client-design pass, see §4.3 | Compiles everywhere without MC jars; shields Fabric's thrice-rewritten HUD API; much easier prompt surface |
| 10 | One revocation model everywhere: `Registration.close()` | Generalizes today's HandlerList/task/command teardown; identical semantics on all platforms |

---

## 1. Modules and dependency graph

```
sdk-client      pure JDK                       (generated-code client contract)
platform-api    JDK + adventure-api + sdk-client   (SPI + screen models)
core            JDK + Gson + adventure-api + platform-api   (engine)
sdk             paper-api (provided) + sdk-client           (generated-code contract, Paper flavor)
paper           core + platform-api + sdk + paper-api
fabric          core + platform-api + sdk-mod flavor + sdk-client + fabric-loader/api (Loom)
neoforge        core + platform-api + sdk-mod flavor + sdk-client + neoforge (ModDevGradle)
```

Rules:
- `core` and `platform-api` never import `org.bukkit`, `io.papermc`, `net.minecraft`,
  `net.fabricmc`, `net.neoforged`. Adventure (`net.kyori.adventure`) **is allowed** in both —
  it is a standalone library and the project's text/UI currency (LuckPerms precedent).
- `sdk-client` never imports anything outside `java.*`.
- Gson stays an assumed-present dependency on Paper (provided via paper-api transitives, per
  ARCHITECTURE.md); on Fabric/NeoForge it is JiJ'd with the host jar.
- ECJ (`org.eclipse.jdt:ecj`) is `compileOnly` in core, JiJ'd in fabric/neoforge (§7.3).

### 1.1 Class-by-class destination map

Every class in `plugin/src/main/java/com/gijsm/vibemod/`:

| Current class | Destination | Notes |
|---|---|---|
| `llm/OpenRouterClient` | core | move-only |
| `llm/StreamScanner` | core | move-only |
| `llm/ModelCatalog` | core | move-only |
| `llm/PromptLibrary` | core | parameterized by `PlatformProfile` (§6); embedded api-source constants become build-time-generated (§6.4) |
| `compile/InMemoryCompiler` | core | gains `CompilerProvider` ctor param + `--release` clamp (§7.3); the paperclip `libraries/`+`versions/` walk moves OUT (to `paper`'s ClasspathProvider) |
| `compile/CompileResult` | core | move-only |
| `gen/ModGenerator` | core | `Bukkit.getScheduler()` hops → `TickScheduler`; `Bukkit.isPrimaryThread()` → `TickScheduler.onMain()` |
| `gen/GeneratedProject` | core | move-only |
| `store/ModStore` | core | meta.json v3 normalization added (§5) |
| `store/ModConfigs` | core | values map becomes thread-safe (volatile/concurrent) — client callbacks read knobs off-main (§8.4) |
| `store/JarExporter` | core | wrapper source + plugin descriptor emitted per `PlatformProfile` (§6.3); Paper wrapper keeps current template with `api-version` from profile |
| `api/Mod` | sdk | unchanged (frozen surface) |
| `api/VibeContext` | sdk | + `hasClient()`, `client(Consumer<ClientContext>)` (§4.2) |
| `api/ModCommandHandler` | sdk | unchanged |
| `api/VibeMod` (deprecated bridge) | sdk | unchanged — pre-v3 stored mods still compile |
| `runtime/ModRegistry` | **split** | platform-free lifecycle → core `runtime/ModLifecycle` (states enabled/degraded, version activation, restore-on-boot, error-storm policy, `Registration` draining); Bukkit binding → paper `PaperEventBridge` + `PaperModHost` (listener reflection + `EventExecutor` + watchdog wrap + `@EventHandler` scan) |
| `runtime/ModHandle` | core | loses direct `Listener`/`BukkitTask` fields; holds `List<Registration>` |
| `runtime/ModErrors` | core | replace any Bukkit logging with JDK logger; storm-counter API gains a `where` dimension used by client dispatch (§8.3) |
| `runtime/Watchdog` | core | measured-thread parameterized (main vs render); trip path unchanged |
| `runtime/DebugEcho` | core | broadcast via `Messenger` (permission-scoped) instead of `Server#broadcast` |
| `runtime/DynamicCommands` | paper | becomes `PaperCommandBridge implements CommandBridge` (getCommandMap + knownCommands reflection stay here, isolated) |
| `command/VibeCommand` | paper (v2.0) | routing is mostly platform-neutral; extract to core only when fabric needs it (Phase D may promote shared routing to core `command/VibeRouter` — allowed, not required) |
| `VibeMod` (main class) | paper | bootstrap: builds core services, wires SPI impls |
| `ui/DialogKit` | paper | becomes internals of `PaperDialogRenderer` |
| `ui/Dialogs`, `ui/InfoDialogs`, `ui/ModHubDialog`, `ui/SettingsDialog` | **split** | screen *content* → core `ui/screens/*` builders producing `Screen` models (§3); dialog mechanics → paper `PaperDialogRenderer` |
| `ui/Style`, `ui/Text`, `ui/MarkdownMini`, `ui/InstallCard` | core | Adventure-only, already platform-free in content |
| `ui/VirtualBooks` | core | console dumps via `Messenger.console()` |
| `ui/Progress` | **split** | BossBar marquee is Adventure (`Audience#showBossBar`) → core; `Particle`/`Sound`/`Location` celebration effects → thin `Celebration` SPI method on `Messenger` (paper implements; loaders may no-op v1) |
| `ui/ChatMode` | paper | becomes `PaperChatBridge implements ChatBridge` (AsyncChatEvent stays Paper-only; loaders implement ChatBridge over their chat events) |
| self-tests (`*SelfTest`) | core test roots | stay `main()` classes; `CompilerSelfTest` gains ECJ-forced mode (§7.3); `StoreSelfTest` unchanged semantics |

---

## 2. platform-api SPI

All interfaces live in `platform-api/src/main/java/com/gijsm/vibemod/platform/` (skeletons
committed; javadoc there is normative). Summary of semantics and threading:

| Interface | Purpose | Threading contract |
|---|---|---|
| `Registration` | idempotent revocable handle; `close()` never throws | thread-safe |
| `PlatformInfo` | `platformName()`, `mcVersion()`, capability probes: `hasDialogs()`, `hasSystemCompiler()`, `hasClient()`, `hasNativeCommandMap()`, `isDedicatedServer()` | immutable after boot |
| `TickScheduler` | `repeat/later/async` returning `TaskHandle extends Registration`; `runOnMain(Runnable)`; `onMain()` | callbacks on main server thread (except `async`) |
| `EventBridge` | `Registration listen(Object nativeListener, String modName)` — registers a platform-native listener object with watchdog + error-storm wrapping | main thread |
| `CommandBridge` | `Registration register(String name, String description, CommandHandler h)`; `void resyncAll()`; `CommandHandler.run(Sender, String[])` | handlers on main thread |
| `Sender` | who invoked: `Audience audience()`, `String name()`, `boolean hasPermission(String)`, `UUID idOrNull()` | — |
| `Messenger` | `Audience player(UUID)`, `Audience console()`, `broadcast(Component)`, `broadcast(Component, String permission)`, `celebrate(UUID)` (particles/sound; may no-op) | main thread |
| `ChatBridge` | `Registration capture(UUID player, ChatCaptureHandler)` — swallow that player's next chat lines until handler returns DONE/CANCELLED | handler hopped to main thread |
| `ClasspathProvider` | `List<Path> compileClasspath()` — real files only; implementations own the extract-once cpcache (§7.2) | called off-main (compile thread) |
| `CompilerProvider` | `JavaCompiler compiler()`, `String name()`, `int maxSupportedRelease()`; static `resolve()` chain (§7.3) | thread-safe |
| `ClientEventBridge` | client hooks (§8): `hud`, `clientTick`, `leaseKey`, `clientCommand`, `playUiSound`, `toast`, `inGame` | registration thread-safe; callbacks on **render thread** |
| `UiRenderer` | `void show(UUID player, Screen screen)` (§3) | main thread |

`paper/` implements: PlatformInfo, TickScheduler (BukkitScheduler), EventBridge
(HandlerList/EventExecutor), CommandBridge (commandMap), Messenger, ChatBridge
(AsyncChatEvent), ClasspathProvider (paperclip walk), UiRenderer ×2 (Dialog + Chat).
No ClientEventBridge on Paper (`hasClient()` false).

`fabric/` and `neoforge/` implement all of the above including ClientEventBridge
(client only), per §8.

---

## 3. Screens are data: the screen model

Package `com.gijsm.vibemod.platform.ui` (skeletons committed). Pure JDK + Adventure.

```
Screen        title: Component, body: List<BodyBlock>, inputs: List<Input>,
              buttons: List<Button>, exit: Button|null, columns: int, kind: FORM|MENU|NOTICE
BodyBlock     Text(Component, WidthHint)  |  Icon(String iconId, boolean glint, Component beside)
WidthHint     BODY, WIDE, INPUT, ROW  (renderer maps to px or ignores)
Input         Text(key, label, initial, maxLength, multiline)
              Bool(key, label, initial)
              Choice(key, label, List<Option(id, label, selected)>)
              Number(key, label, min, max, step, initial, labelFormat)
Button        label: Component, tooltip: Component|null, width: WidthHint, action: UiAction
UiAction      RunCommand(String command)                     — stateless navigation
              Submit(UiCallback)                              — reads inputs, one-shot
              Callback(UiCallback)                            — no inputs read, one-shot
UiCallback    void handle(UiResponse response, UUID player)   — invoked ON MAIN THREAD by renderer
UiResponse    String text(key) | Boolean bool(key) | Double number(key)   — null when absent
```

Renderer obligations (both renderers): hop callbacks to the main thread; never let a
callback exception propagate (catch → log → `Style.err` to player); re-validate every
submitted value server-side (clients lie); one-shot callbacks (a second click is a no-op).

### 3.1 DialogRenderer mapping (paper, `hasDialogs`)

Mechanical: Screen.kind FORM → `DialogType.confirmation(submitButton, cancel)`;
MENU → `DialogType.multiAction(buttons).exitAction(exit).columns(n)`; NOTICE →
`DialogType.notice(exit)`. Inputs map 1:1 to `DialogInput.text/bool/singleOption/numberRange`
(key sanitization `[^a-z0-9_]→_` with reverse map — lift from `Dialogs.inputKey`).
`UiAction.RunCommand` → `DialogAction.staticAction(ClickEvent.runCommand(...))`;
Submit/Callback → `DialogAction.customClick(...)` with `ClickCallback.Options.uses(1)` and
main-thread hop — i.e. today's `DialogKit.mainThreadClick`. Show = next-tick
`player.showDialog`, never `closeInventory()` (regression: commits 639dd32/3666bf0).

### 3.2 ChatRenderer mapping (universal fallback)

The load-bearing design: **forms render as edit-in-place lists, not sequential wizards.**

- Screen → a chat block: `── title ──` header, body blocks as lines (`MarkdownMini`
  output verbatim; `Icon` renders as its `beside` component, id dropped), then interactive
  lines, then a button row.
- `Input.Text/Number` → line `key = current [✎ change]`; clicking `[✎ change]` starts a
  `ChatBridge.capture` for that player ("type a value in chat, or `cancel`"), validates
  (clamp numbers to min/max/step), then re-renders the whole screen with the new pending
  value. Multiline text (make/edit prompts): capture accepts lines until a lone `done`.
- `Input.Bool` → `key = on [toggle]` — click flips pending value, re-render.
- `Input.Choice` → `key: [opt1] [opt2] …` — selected one highlighted; click picks, re-render.
- `Button(Submit)` → `[Label]` click component that submits the **pending** value set as a
  `UiResponse`. Pending state lives in a per-player `ChatFormSession` (one active per player;
  opening a new screen discards the old; 5-minute TTL).
- `Button(RunCommand)` → `ClickEvent.runCommand` 1:1. `Button(Callback)` → callback token.
- Click plumbing for Submit/Callback/toggle/change: a hidden `/vibe ui <token>` subcommand;
  tokens are random, single-use, per-player, TTL 5 min, held in the renderer. (Adventure
  `ClickCallback` is NOT used here — on Paper it exists, but chat-renderer must also run on
  loaders where we control the token path uniformly.)
- MENU screens (hub, browser, history, source index): rows as click lines, exit button last.
  NOTICE: block + nothing.

ChatRenderer lives in **core** (`ui/chat/`) — it only needs Messenger, ChatBridge,
CommandBridge (for the `/vibe ui` route the host wires), so every platform reuses it.

### 3.3 The 17 screens, inventoried

Builders live in core `ui/screens/`, one method per screen, producing `Screen` from the same
inputs the current classes take. Content below is the extraction spec (from
`ui/Dialogs.java`, `ui/InfoDialogs.java`, `ui/ModHubDialog.java`, `ui/SettingsDialog.java`):

| # | Screen (builder) | Kind | Body | Inputs | Buttons / exit |
|---|---|---|---|---|---|
| 1 | makePrompt | FORM | icon CRAFTING_TABLE + "Describe…" | multiline `prompt` (2000), text `name` (32) | Submit "✨ Create" → onPrompt(name-hint + text); cancel |
| 2 | edit(mod) | FORM | manual summary | multiline `change` (2000) | Submit "Update" → onEdit; cancel |
| 3 | config(mod, knobs) | FORM | optional error banner; per-knob `key — description` lines | per knob: bool/choice/number(min,max,step)/text(256) | Submit "Save" → onConfig; rejected keys → reopen with banner + pending values; cancel |
| 4 | modelPicker | FORM | current model+price; session spend | choice `model` (id — price, cheapest first), text `custom` (80, wins if non-blank) | Submit "Use" → onPick; cancel "Keep the current model" |
| 5 | fixConfirm(mod) | FORM(no inputs) | icon+"is degraded"; question; last error | — | RunCommand "🔧 Fix it" → `/vibe fix <m> confirm`; cancel |
| 6 | rollbackConfirm(mod,v) | FORM(no inputs) | icon; "Recompile and hot-load…?"; changelog | — | RunCommand "⚡ Activate vN" → `/vibe rollback <m> <v> confirm`; cancel |
| 7 | deleteConfirm(mod) | FORM(no inputs) | icon; "permanently deletes … N versions" | — | RunCommand "✖ Delete forever" → `/vibe delete <m> confirm`; cancel |
| 8 | modHub(mod) | MENU c3 | icon(glint=running)+name; desc; usage; state line; version+changelog; creator; knob count; lifetime cost | — | Manual/Source/History/Errors; admin: Configure/Edit/Enable|Disable/Debug(state dot)/Reload/Export/[Fix when degraded]/Delete — all RunCommand `/vibe <sub> <m>`; exit "← Back to list" → `/vibe list` |
| 9 | browser | MENU c1 | "N mods · R running · D degraded" or empty-state hint | — | per-mod row `● Name vN` (tooltip: desc/state/versions/knobs) → `/vibe info <m>`; admin "⚙ Settings"; exit Done (no-op) |
| 10 | manual(mod) | MENU c2 | icon+name; markdown blocks; "Verified facts"; config table (uniform font) | — | Source/Errors; exit "← Back" → `/vibe info <m>` |
| 11 | sourceIndex(mod,v) | MENU c1 | "N files — pick one." | — | per-file row (tooltip fqcn + line count) → Callback opening screen 12; Manual/Errors; exit back-to-hub |
| 12 | sourceFile(mod,v,fqcn) | MENU | `// fqcn` (WIDE); whole source, uniform font (WIDE) | — | "← Back to files" → `/vibe source <m>` (or Manual/Errors when single-file); exit back-to-hub |
| 13 | errors(mod) | MENU c3 | per-record: `n× Class: msg` + `at frame (where, last Xs ago)` + stack lines | — | Manual/Source/History; exit back-to-hub |
| 14 | history(mod) | MENU c1 | "N versions · vX active" | — | per-version row newest-first `● ✨ v1 · create · 3d ago` (tooltip changelog/meta/missing-sources) → Callback opening screen 15; exit back-to-hub |
| 15 | versionDetail(mod,v) | MENU | changelog-or-prompt; `Prompt: …`; joined meta (+"sources missing") | — | [⚡ Activate… → `/vibe rollback <m> <v>` when !active && onDisk]; "← Back to history"; exit back-to-hub |
| 16 | costs | NOTICE | icon GOLD_INGOT + session spend; per-mod cost lines (uniform); zero-mods + pre-tracking footnotes | — | Done |
| 17 | settings | MENU c3 + inputs | icon COMPARATOR; model+price; session spend; "API key… in config.yml" | choice `thinking`(off/low/medium/high), bool `streaming`, numbers `timeout`(30-600/15) `maxtokens`(0-131072/1024) `retries`(0-10) `concurrency`(1-8), bool `watchdog`, numbers `watchdogms`(50-1000/25) `watchdogbudget`(100-2000/50), bool `debugecho` | Submit "Save" (clamped server-side); Callback "Model…" → opens 4 (reopens 17 after pick); Callback "⟳ Reload from disk"; exit Cancel |

Helpers that move with the builders: `relativeTime`, `changelogOrPrompt`, `ModCost`,
`kindGlyph`, state dots/colors (`Style`), `inputKey` sanitize/reverse-map.

---

## 4. The SDK: generated-code contract

### 4.1 One contract, two flavors

`Mod` is identical everywhere (shared file). `VibeContext`/`ModCommandHandler` exist in two
flavors with the same FQCN (`com.gijsm.vibemod.api.*`), selected by which sdk jar the host
puts on the generated-code classpath:

- **Paper flavor** (`sdk/src/main/java`, committed as skeleton): today's Bukkit-typed
  surface *unchanged* (corpus compatibility for the 569 stored sources) plus
  `hasClient()` (default `false`) and `client(Consumer<ClientContext>)` (default no-op).
  `ClientContext` is pure JDK (§4.3) so these defaults compile against paper-api.
- **Mod flavor** (`sdk/src/mod/java`, Phase D creates it; spec below): Mojang-typed,
  shared verbatim by Fabric and NeoForge (both run official names on 26.1+).

Mod-flavor `VibeContext` (normative spec — Phase D implements exactly this):

```java
MinecraftServer server();
String modName(); Logger log(); Path dataFolder();
TaskHandle repeat(long delayTicks, long periodTicks, Runnable task);   // TaskHandle: cancel()
TaskHandle later(long delayTicks, Runnable task);
void command(String name, String description, ModCommandHandler h);    // h: (CommandSourceStack src, String[] args)
void action(String name, ModCommandHandler h);                         // /vibe do <mod> <name>
boolean/long/double/String configBool|Int|Double|String(String key);
boolean hasClient();
void client(Consumer<ClientContext> setup);
// curated server event hooks, host-dispatched + revoked with the mod:
void onPlayerJoin(Consumer<ServerPlayer> h);        // Fabric: ServerPlayConnectionEvents.JOIN / Neo: PlayerLoggedInEvent
void onPlayerQuit(Consumer<ServerPlayer> h);        // …DISCONNECT / PlayerLoggedOutEvent
void onServerTick(Consumer<MinecraftServer> h);     // ServerTickEvents.END_SERVER_TICK / ServerTickEvent.Post
void onChat(ChatHandler h);                         // boolean handle(ServerPlayer, String) → false cancels; ServerMessageEvents / ServerChatEvent
void onBlockBreak(BlockHandler h);                  // boolean → false cancels; PlayerBlockBreakEvents.BEFORE / BlockEvent.BreakEvent
void onUseBlock(UseHandler h);                      // UseBlockCallback / RightClickBlock
void onUseItem(UseHandler h);                       // UseItemCallback / RightClickItem
void onEntityDeath(BiConsumer<LivingEntity, DamageSource> h);  // ServerLivingEntityEvents.AFTER_DEATH / LivingDeathEvent
void onPlayerDeath(Consumer<ServerPlayer> h);
void onRespawn(Consumer<ServerPlayer> h);           // AFTER_RESPAWN / PlayerRespawnEvent
```

The curated-hook list is v1-frozen; anything else is out of scope for generated mods on
loaders (the prompt says so; compile errors catch attempts). Rationale: Fabric events
cannot unregister, so *every* hook must be host-dispatched anyway — a curated list is the
honest surface, and Fabric's server-side event vocabulary is far smaller than Bukkit's to
begin with. Paper mods keep the full Bukkit event system via `listen()` as today.

### 4.2 Paper-flavor `VibeContext` additions

`hasClient()` and `client(...)` exist on Paper for prompt uniformity but are documented
"loader-only feature — no-op on Paper servers" and default-implemented so the frozen v3
surface stays source-compatible for all 49 stored mods.

### 4.3 `sdk-client` (pure JDK — Decision 9)

Committed skeletons; the normative surface:

```java
ClientContext:
  void hud(String id, HudRenderer renderer);
  KeyLease key(String label, String defaultKey, Runnable onPress);  // IllegalStateException when pool exhausted
  void tick(ClientTickHandler handler);
  void clientCommand(String name, String description, ClientCommandHandler handler); // /vibec <mod> <name>
  void sound(String soundId, float volume, float pitch);
  void toast(String title, String body);
  boolean inGame();
  // pure-JDK state getters (render-thread values, safe inside hud/tick callbacks):
  double playerX(); double playerY(); double playerZ();
  float playerHealth(); float playerMaxHealth();
  String dimension();            // e.g. "minecraft:overworld"; "" when not in game
  String targetedBlock();        // block id under crosshair within reach, "" if none
  int fps();
  long worldTime();              // day time ticks; -1 when not in game
  Object minecraftHandle();      // escape hatch: the net.minecraft.client.Minecraft instance; cast at your own risk

HudRenderer:      void render(HudCanvas c, float tickDelta);
HudCanvas:        int width(); int height();                       // scaled GUI size
                  void text(String s, int x, int y, int argb);
                  void text(String s, int x, int y, int argb, boolean shadow);
                  int textWidth(String s);
                  void box(int x1, int y1, int x2, int y2, int argb);   // filled rect
                  void outline(int x1, int y1, int x2, int y2, int argb);
                  void item(String itemId, int x, int y);              // 16x16 item icon
ClientTickHandler: void tick(ClientContext ctx);
ClientCommandHandler: void run(ClientContext ctx, String[] args);
KeyLease:         void release(); boolean active(); String slotName(); boolean pressed();
```

Hosts implement `HudCanvas` over `GuiGraphics` (~60 lines each). This is deliberately a
*drawing* API, not a widget API — enough for coordinate HUDs, timers, counters, bars.
Deviation note: the client-design pass proposed Mojang-typed `HudRenderer(GuiGraphics,…)`;
Decision 9 overrides it for compile-everywhere, churn-shielding and prompt simplicity.
`minecraftHandle()` preserves the power path.

---

## 5. meta.json v3

Current stored shape (v2, `ModStore`) gains three fields:

```json
{ "schema": 3,
  "platform": "paper" | "fabric" | "neoforge",
  "mcVersion": "1.21.8",
  "side": "server" | "client" | "both",
  ...existing fields... }
```

Normalization (extend `ModStore.normalize`, same pattern as v1→v2): on read, a meta.json
without these fields gets `platform="paper"`, `mcVersion="1.21.8"` (every existing mod was
generated there), `side="server"`; written back on next save. `side` is derived at
generation time from whether the produced code calls `ctx.client(...)`; it drives UX badges
and prompt selection ONLY — never load gating (`ctx.client` no-ops where irrelevant).
A mod whose stored `platform` differs from the running platform is shown in the browser as
`(other platform)` and refuses enable with a friendly message — `/vibe make` again is the
migration path. Same-platform `mcVersion` drift is NOT gated: restore-on-boot recompiles
against the live server and the self-heal loop handles breakage, as today.

---

## 6. PlatformProfile and prompts

### 6.1 Schema

```java
record PlatformProfile(
    String id,                    // "paper-modern" | "paper-legacy" | "fabric" | "neoforge"
    String displayName,           // "Paper 1.21.7+" …
    String roleLine,              // replaces PromptLibrary:183 "You are an expert … author."
    String apiSourceBlock,        // generated: the flavor's VibeContext + Mod + handler sources (§6.4)
    String importRules,           // allowed import roots + explicit bans
    String cheatSheet,            // event/command/enum guidance for this era/platform
    String threadingContract,     // §8.4 text for loader profiles; main-thread text for paper
    List<FewShot> fewShots,       // (user, assistant) example pairs
    String pluginDescriptor,      // JarExporter: api-version / fabric.mod.json / neoforge.mods.toml template
    String iconInstruction)       // PromptLibrary:250 equivalent
```

Selected at boot from `PlatformInfo` (platform + `hasDialogs` is irrelevant here; version
threshold 1.21.7 splits the two Paper profiles). `ModGenerator` threads the profile through
`makePrompt`/`editPrompt`/`fixPrompt`/`repairPrompt`.

### 6.2 The four v1 profiles

| Profile | Import rules | Cheat-sheet highlights | Few-shots |
|---|---|---|---|
| paper-modern (1.21.7+) | `java.*`, `org.bukkit.*`, **`net.kyori.adventure.*` now officially allowed** (88+ stored mods already use it); bans: `net.minecraft.*`, `io.papermc.*`, reflection | current PromptLibrary:294-297 enum guidance; 1.21.3+ attribute names (`Attribute.MAX_HEALTH`) | existing EXAMPLE_1/2 unchanged |
| paper-legacy (1.20.6–1.21.6) | same as paper-modern | **era table**: pre-1.21.3 attribute names (`GENERIC_MAX_HEALTH` family), pre-1.21.3 `Sound`/`Particle`/`Enchantment` enum era, no `setEnchantmentGlintOverride` | existing examples with enum names checked against 1.20.6 |
| fabric (26.1+) | `java.*`, `com.gijsm.vibemod.api.*`, `net.minecraft.*` (read-only use of game types passed into hooks); bans: `net.fabricmc.*` (all registration goes through ctx), mixins, `Screen` subclasses, render events, `java.net.*` | curated ctx hooks table (§4.1) + ClientContext surface + "no registry content (items/blocks)" | HUD-timer mod; keybind-toggle mod; simple gameplay mod (onBlockBreak counter) |
| neoforge (26.1+) | same as fabric with `net.neoforged.*` banned | identical (same sdk flavor) | same three |
| *(all)* | | threading contract per platform; `side` guidance ("if you use ctx.client, say side=client/both in meta") | |

### 6.3 JarExporter per profile

`pluginDescriptor` + a wrapper-emitter strategy per profile. v1: Paper wrapper only
(current template, `api-version` from profile = `'1.20'`); fabric/neoforge `/vibe export`
returns "not yet supported on this platform" (tracked as v1.1) — exporting a standalone
loader mod means bundling loader boilerplate and is not on the critical path.

### 6.4 Build-time prompt sources

A Gradle task (`generatePromptSources`) in core reads the sdk flavor sources
(`Mod.java`, `VibeContext.java`, `ModCommandHandler.java`, `ClientContext.java`) and emits
`com.gijsm.vibemod.llm.GeneratedApiSources` (string constants) consumed by `PromptLibrary`.
This kills the hand-duplicated constants at PromptLibrary.java:39-157; `LlmSelfTest`'s
compile-check of prompt examples keeps guarding drift.

---

## 7. Compilation pipeline changes

### 7.1 Classpath principle

`InMemoryCompiler` drops its own classpath assembly; it receives `ClasspathProvider`.
Implementations:
- **Paper**: current logic moved verbatim — `java.class.path` + code-source of
  `org.bukkit.Bukkit` + own code-source + paperclip `libraries/`+`versions/` walk.
- **Fabric**: `FabricLoader.getModContainer("minecraft"|"fabric-loader"|each fabric-api
  module|"vibemod").getOrigin().getPaths()`; fallback: code-source reflection on
  `net.minecraft.server.MinecraftServer` + one fabric-api class.
- **NeoForge**: FML mod-file APIs for patched minecraft + neoforge jars under `libraries/`;
  translate `union:` URLs (strip scheme + `%23n!/` suffix) — small pure function, unit-tested.

### 7.2 cpcache (extract-once)

Any provider path that is not a plain readable `.jar` file (nested JiJ jar, directory,
union path) is copied to `<dataFolder>/cpcache/<first16-of-sha256>.jar`, content-addressed,
reused across boots, orphans pruned on boot. Needed because javac/ECJ read real files while
Knot/ModLauncher read nested jars in place.

### 7.3 CompilerProvider + ECJ

`resolve()` order: `ToolProvider.getSystemJavaCompiler()` → `ServiceLoader.load(JavaCompiler.class)`
→ `Class.forName("org.eclipse.jdt.internal.compiler.tool.EclipseCompiler")`. ECJ ≥3.43
(JDK 25 line), EPL-2.0: license text shipped under `META-INF/licenses/`, credited in mod
metadata. `compileOnly` in core; **Jar-in-Jar in fabric/neoforge — never relocated**
(relocation breaks the reflective name + ServiceLoader entry). `buildOptions()` clamps
`--release` to `min(Runtime.version().feature(), provider.maxSupportedRelease())`.
`CompilerSelfTest` gains `-Dvibemod.compiler=ecj` forcing path 3 and must pass the full
stored-corpus compile. Known risk: ECJ's file manager vs our `InMemoryFileManager` —
contained fallback is explicit `-classpath` + a fully in-memory manager.
Client contingency (probe in Phase D before building anything): if Mojang's jlink runtime
lacks the `java.compiler` API module, JiJ the `javax.tools` API classes; check
`ModuleLayer.boot().findModule("java.compiler")` on a real launcher install first.

---

## 8. Client architecture (fabric/neoforge, physical client only)

### 8.1 Host-owned dispatchers

One permanent registration per surface at client init; generated mods attach/detach via
`ClientEventBridge`; teardown drains. Fabric: `HudElementRegistry.addLast("vibemod:mods",…)`,
`ClientTickEvents.END_CLIENT_TICK`. NeoForge: `RegisterGuiLayersEvent` (one layer above
CHAT), `ClientTickEvent.Post`. Every dispatch entry is try/catch-wrapped → `ModErrors`
(where="client"); the storm counter's trip auto-disables the mod. **A throwing HUD renderer
must never crash the render loop — this lands with the first client commit, not later.**

### 8.2 Key pool

8 `KeyMapping`s pre-registered at client init (`vibemod.slot.1..8`, category
`key.categories.vibemod`, default unbound). Lease = lowest free slot; auto-bind the
requested default only if the user never manually rebound that slot (tracked in host
config as auto-bound flags); release clears handler + unbinds if auto-bound. Presses
detected in the client-tick dispatcher via `consumeClick()`. Pool exhaustion throws at
`ctx.client` setup time → surfaces as a normal mod-load diagnostic.

### 8.3 /vibec

Static root registered once (Fabric: `ClientCommandRegistrationCallback`; NeoForge:
`RegisterClientCommandsEvent`, re-fires per connection — re-register each time):
`/vibec <mod> <command> [args…]` dispatching into the per-mod handler registry with a live
`SuggestionProvider`; `/vibec list` host verb. Dynamic top-level client commands: excluded v1.

### 8.4 Threading contract (goes verbatim into loader profiles + SDK javadoc)

`onEnable`/`onDisable` and all server hooks run on the server thread. `ctx.client(...)`
setup runs on the server thread; registrations are thread-safe. `hud`/`tick`/`key`/
`clientCommand` callbacks run on the **render thread**. Client callbacks must only use
`ClientContext` (its getters are render-thread-safe) and mod-local state; never touch
server state from client callbacks or vice versa — in singleplayer both live in one JVM
and races are silent. `ctx.config*` reads are thread-safe everywhere. `Watchdog` measures
client callbacks on the render thread with the same budgets/trip path.

### 8.5 Dialogs on loaders

Server-side vanilla `Dialog` construction + `ServerPlayer#openDialog` (inline
`Holder.direct`, no registry); responses arrive as custom-click actions — **one mixin** on
`ServerCommonPacketListenerImpl`/`ServerCommonNetworkHandler` routes them into the
UiRenderer's callback registry (Fabric + NeoForge alike unless NeoForge exposes an event).
Singleplayer rides the identical integrated-server path. Before building the loader
DialogRenderer, check whether adventure-platform-mod ships `Audience#showDialog`; if yes,
reuse Paper's renderer logic against Adventure `DialogLike` instead.

---

## 9. Phase acceptance criteria

Common to every phase: work on this branch; one commit per coherent step; never
`git stash`; the phase's full checklist verified before reporting done; deviations
documented in the completion report.

### Phase B — Gradle multi-module
- [ ] `gradle build` (wrapper committed, Gradle 8.x, Java 21 toolchain for paper/core/sdk) produces `paper/build/libs/VibeMod.jar`
- [ ] Maven files removed; `settings.gradle.kts` lists core, platform-api, sdk, sdk-client, paper
- [ ] Class moves per §1.1 table (splits may be deferred to C where the table says so; pure moves happen here)
- [ ] Paper jar parity: same plugin.yml (`api-version: '1.21'` — unchanged in B), `jar tf` classes superset-equal to Maven jar (allowing new platform-api/sdk classes), boots on the local dev server, `/vibe` browser opens, one canned `/vibe make`-less smoke: restore existing mods from `server/plugins/VibeMod/mods` copy → all enabled mods load
- [ ] All 5 self-tests run green via Gradle JavaExec tasks (`CompilerSelfTest`, `LlmSelfTest`, `StoreSelfTest` incl. 569-source corpus, `ErrorsSelfTest`, `CatalogSelfTest`)
- [ ] `generatePromptSources` task replaces PromptLibrary's hand-embedded constants; `LlmSelfTest` still passes
- [ ] Both CI workflows build with Gradle and upload the same artifact names

### Phase C — Seams + Paper 1.20.6 + ChatRenderer
- [ ] `PlatformInfo` probe implemented (dialog capability = classpath probe for `io.papermc.paper.dialog.Dialog` + MC version ≥1.21.7); all SPI impls wired in paper bootstrap
- [ ] 17 screen builders in core produce models per §3.3; `PaperDialogRenderer` renders all 17 byte-comparably to today (manual QA on dev server)
- [ ] `ChatRenderer` in core implements §3.2 incl. `/vibe ui <token>` route, ChatFormSession, capture flows
- [ ] Renderer selection by `hasDialogs`; config override `ui.force-chat: true` for QA
- [ ] `CompilerProvider` seam + clamp; ECJ-forced `CompilerSelfTest` passes corpus
- [ ] PlatformProfile wiring; paper-modern + paper-legacy profiles; adventure officially allowed
- [ ] `plugin.yml` + JarExporter `api-version: '1.20'`; `setEnchantmentGlintOverride` capability-gated
- [ ] run-task (`xyz.jpenilla.run-task`) targets for Paper 1.20.6 / 1.21.8 / 26.x; on each: boot, `vibe` command registered, canned-source compile→load→command→unload smoke (scripted via RCON, reuse `scripts/rcon.sh` pattern)
- [ ] On 1.20.6: full `/vibe` flow works chat-only (manual QA: make, browser, hub, config, settings)

### Phase D — Fabric
- [ ] `fabric/` module on Loom, MC 26.1+, one jar server+client; `fabric.mod.json` entrypoints main + client
- [ ] All SPI impls per §2/§7/§8; ECJ + Gson + adventure-platform-mod JiJ'd
- [ ] sdk mod-flavor per §4.1; fabric PlatformProfile per §6.2
- [ ] Dialog renderer per §8.5 (adventure-platform-mod check first, documented outcome)
- [ ] `java.compiler` module probe on a real launcher install documented; contingency only if absent
- [ ] Gate: dedicated-server run-task smoke (as C); `runClient` singleplayer: canned gameplay mod AND canned HUD+keybind client mod compile→load→render→toggle→unload; deliberately-throwing HUD renderer auto-disables without client crash; `/vibec` routes; key pool leases/releases across mod reload
- [ ] Watchdog measures render-thread callbacks (trip test with a busy-loop HUD mod)

### Phase E — NeoForge
- [ ] `neoforge/` on ModDevGradle; same checklist as D transposed (EVENT_BUS bridges, GUI layer, `RegisterClientCommandsEvent` re-registration, `sendCommands` resync, union: URL translation unit-tested)
- [ ] Gate: same as D on NeoForge server + client

### Phase F — CI, docs, release
- [ ] CI: all module jars built; matrix run-task smokes (Paper 1.20.6/1.21.8/26.x, Fabric 26.x server, NeoForge 26.x server) headless with scripted RCON/console smoke; self-tests incl. ECJ-forced wired in
- [ ] README per-platform install/quickstart; ARCHITECTURE.md updated to point here; CHANGELOG 2.0.0
- [ ] bStats on all three hosts (platform + mcVersion charts)
- [ ] `scripts/` either multi-platform or explicitly Paper-scoped (documented)
- [ ] Draft PR with migration summary

---

## 10. Phase A compile gate (record)

All 30 skeleton sources (platform-api 21, sdk 3, sdk-client 6) compile clean with
`-Werror` on JDK/`--release` 21. Because `sdk-client` is pure JDK (Decision 9), nothing
is deferred to Phase D for compilation. Exact command (run from the repo root; jars from
the local Maven repo):

```
javac --release 21 -Werror -d <out> \
  -cp ~/.m2/repository/io/papermc/paper/paper-api/1.21.8-R0.1-SNAPSHOT/paper-api-1.21.8-R0.1-SNAPSHOT.jar\
:~/.m2/repository/net/kyori/adventure-api/4.24.0/adventure-api-4.24.0.jar\
:~/.m2/repository/net/kyori/adventure-key/4.24.0/adventure-key-4.24.0.jar\
:~/.m2/repository/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar \
  $(find sdk-client/src sdk/src platform-api/src -name '*.java')
```

(paper-api is needed only by `sdk`'s Bukkit-typed members; adventure jars only by
`platform-api` — paper-api does not bundle Adventure.)

## 10.1 Phase B record (Gradle multi-module) — what actually landed

Build: Gradle **9.7.1** wrapper (checksum-pinned), not 8.x — Gradle 8 cannot run on the
JDK 25 that is the only JDK on the dev machine. Java 21 toolchain via the foojay resolver
convention, so the build provisions a JDK 21 where none exists and everything (including the
self-tests, hence `InMemoryCompiler`'s `--release`) is Java 21 everywhere.
`settings.gradle.kts` lists sdk-client, platform-api, sdk, core, paper.

Class moves: only classes that were **already** platform-free moved to `core` —
`llm/*`, `compile/*`, `gen/GeneratedProject`, `store/*`, `ui/{Style,Text,MarkdownMini}`.
Every §1.1 row whose destination is `core` *with a change note* (`gen/ModGenerator`,
`runtime/{ModHandle,ModErrors,Watchdog,DebugEcho}`, `ui/{Progress,VirtualBooks}`, the
`ModRegistry` and dialog splits) still imports Bukkit and therefore stayed in `paper`:
those moves are the Phase C seam work, not "pure moves". `ui/InstallCard` is in `paper`
too — it takes a `ModHandle`, so it can only follow `ModHandle` to core.
**Phase C must land those moves along with the SPI it introduces.**

sdk: the Phase A skeletons are now the only copies; `api/VibeMod` (the deprecated bridge)
joined them. `JarExporter` embeds the six `api/client/*` classes as well as the four `api/*`
ones — `VibeContext.client(Consumer<ClientContext>)` names them, so an export without them
would not link.

Prompts: `:core:generatePromptSources` emits `com.gijsm.vibemod.llm.GeneratedApiSources`
from the sdk files (§6.4); the four constants include `CLIENT_CONTEXT`, unused until the mod
flavor's profile needs it in Phase D. The Paper prompt still embeds exactly Mod +
VibeContext + ModCommandHandler, as before.

Self-tests: `./gradlew selfTest`, or per module — `:core:selfTest{Compiler,Llm,Store,Catalog}`
and `:paper:selfTestErrors`. They hang off `check`, so `./gradlew build` runs them.
`StoreSelfTest` gained the corpus gate the docs already claimed (`-Dvibemod.mods.dir`,
Gradle-defaulted to `<repo>/server/plugins/VibeMod/mods`, override with
`-Pvibemod.modsDir=`): 49 mods / 148 versions / **569 sources**, all compiling. Gradle's
JUnit `test` task is disabled — there is no test framework in this project.

Known bug found, deliberately not fixed here (Phase C owns `InMemoryCompiler`, §7.3):
`formatDiagnostics` iterates `DiagnosticCollector.getDiagnostics()` live while
`Diagnostic#getMessage` is what triggers javac's mandatory-warning aggregation, so on
**JDK 25** a compile that emits a deprecation summary throws `ConcurrentModificationException`
straight out of `compile()` — which is documented "never throws". Reproduced on JDK 25 for
MapArt and SpectralScreen (13 of 148 corpus versions); not reproduced under the Java 21
toolchain nor on the 1.21.8 dev server. One defensive copy fixes it.

## 10.2 Phase C record (seams + Paper 1.20.6 + ChatRenderer) — what actually landed

**Everything on the §9 Phase C checklist landed.** The §1.1 moves Phase B deferred are
done: `core` now holds the whole engine and `paper` holds only the host. Details worth
knowing before Phase D:

### The SPI, as built
`platform-api` gained four things the skeletons did not have, all documented in place:
- `Registration.of(Runnable)` / `.inactive()` / `.closeAll(...)` — the canonical
  once-only, never-throwing implementation. Every bridge returns one of these.
- `ModFailure` — where a caught mod failure goes. `ModLifecycle` implements it; every
  bridge that dispatches into generated code reports through it. Phase D's client
  dispatch needs exactly this (§8.1) with `where="client"`.
- `Messenger.online(UUID)` and `broadcastToPlayers(Component, String)`. The first
  because the progress boss bar must stop animating for someone who logged off and
  `Audience` cannot answer that; the second because `DebugEcho` mirrors JUL records the
  console already prints, so a console-inclusive broadcast doubles every line.
- `PlatformInfo` gained `hasItemGlintOverride()`, `hasCommandResync()`, `profileId()`
  and `maxTargetRelease()` (see the bug below). The first two are default-false so a
  host need not implement them.

`core` gained one SPI of its own, `runtime/ModHost`: instantiate the mod, build its
context, call `onEnable`/`onDisable`, returning an opaque activation token. This is the
seam that lets `ModLifecycle` live in core at all — `Mod` and `VibeContext` are
platform-typed by design (§4.1), so core cannot name them, and does not need to.
`ModDispatch` is the other new core class: the single guarded, watchdog-timed entry
point into mod code that v1 had copy-pasted three times inside `ModRegistry`.

### Screens
All 17 builders live in `core/ui/screens/` split four ways — `ScreenKit` (the shared
button/format vocabulary lifted out of `DialogKit`), `FormScreens` (1–7), `HubScreens`
(8–9), `InfoScreens` (10–16), `SettingsScreens` (17). `HubScreens`/`InfoScreens`/
`SettingsScreens`/`FormScreens` are instances, not statics: the config screen reopens
itself with a banner, the source index and version timeline drill down in place, and
the settings screen reopens after a model pick — all of which need the live
`UiRenderer` and `Messenger`.

`PaperDialogRenderer` replaces four hand-written dialog classes with one generic
`Screen -> Dialog` mapping; `DialogKit` kept only its visual vocabulary. `ChatRenderer`
lives in `core/ui/chat/` with a package-private `ChatFormSession`, and owns the
`/vibe ui <token>` route (single-use, per-player, 5-minute TTL, `SecureRandom`).

**One correction future screen authors must know:** `WidthHint.ROW` is the renderer's
signal for "this is a list row, pin it to 300px so the column aligns". A grid button
sizes to its label, which v1 achieved by simply never setting a width on one. Only
`ScreenKit.row(...)` uses `ROW`; nav/exit/submit buttons use `BODY`, which the dialog
renderer reads as "natural size". `Button.command(...)` in platform-api was changed to
match.

### Compilation pipeline
`CompilerProvider.resolve()` works as specced, with two additions reality forced:
- **`-Dvibemod.compiler=ecj` has to filter the ServiceLoader step.** The JDK's own
  `jdk.compiler` module registers `JavacTool` as a `JavaCompiler` service, so an
  unfiltered scan hands back javac even when ECJ was demanded. The first ECJ-forced run
  passed while silently testing javac; `CompilerSelfTest` now asserts the requested
  backend was actually resolved.
- **ECJ cannot take in-memory compilation units at all.** It re-resolves every unit by
  `JavaFileObject#getName()` against the filesystem and reports
  `File /p/A.java is missing`, whatever file manager it is handed — including one that
  properly implements `StandardJavaFileManager` (which `InMemoryFileManager` now does
  anyway, and should). That is the §7.3 risk, confirmed. The contained fallback is
  `CompilerProvider.acceptsInMemorySources()`: when false, `InMemoryCompiler` stages the
  sources into a temp directory, compiles from real files, and deletes it. Output still
  goes to memory. ECJ also names output classes with slashes (`p/A`), which the class
  loader would have rejected — normalized.

**ECJ is 3.42.0, not ≥3.43.** 3.43 does not exist on Maven Central yet. 3.42.0 compiles
the entire 569-source corpus clean.

**The §10.1 CME could not be reproduced.** All 148 corpus versions compile without
throwing on this machine's JDK 25 (Temurin 25.0.1+8), including MapArt and
SpectralScreen. The fix landed anyway — `List.copyOf` before formatting, plus a
fail-safe render — and `CompilerSelfTest` now proves both halves of the claim with a
synthetic diagnostic that reports another diagnostic while being rendered: the naive
live loop throws `ConcurrentModificationException`, the shipped snapshot does not. The
mechanism is real and guarded; which javac builds trigger it in the wild is not
something this branch can pin down.

### PlatformProfile
`paper-modern` and `paper-legacy` differ exactly where the era does: the legacy cheat
sheet teaches the `GENERIC_` attribute names, forbids the short 1.21.3+ forms, forbids
`setEnchantmentGlintOverride` and the other post-1.20.6 data-component setters, and
warns off `Registry`-based lookups. `net.kyori.adventure.*` is now an officially allowed
import root in both. `PromptLibrary.systemPrompt()` (no args) still returns the
paper-modern prompt, so the corpus and every existing assertion are unchanged.

**Deviation from §6.1:** the profile is threaded into `ModGenerator` and reaches
`systemPrompt(profile)`, but `makePrompt`/`editPrompt`/`fixPrompt`/`repairPrompt` stay
profile-free. Nothing in "Create a mod: X", the current sources, the knob table or a
javac diagnostic differs per platform, so a profile parameter there would be one nobody
reads. Phase D should add it to `makePrompt` when the `side` guidance (§6.2, "all" row)
gives it something to say.

**Also §6.1:** `roleLine` carries the whole opening block (role sentence *plus* the
"a mod is not a Bukkit plugin" paragraph), not just the first sentence. That second
paragraph is Paper-specific prose the loader profiles will have to restate.

### The floor drop, and the bug it hid
`plugin.yml` declares `api-version: '1.20'`; the exporter takes its `api-version` from
the profile, and **both Paper profiles say `'1.20'`** — `api-version` gates legacy data
conversion, not which API exists, and declaring a floor above the running server makes
Paper refuse the plugin outright, so the lowest honest floor is the one that lets an
export travel. The emitted wrapper now probes the command map instead of dereferencing
`Bukkit.getCommandMap()` blind.

**The real hazard was not the API surface, it was bytecode.** Paper 1.20.6 pipes every
dynamically defined class through the ASM 9.7 in its plugin remapper, and ASM 9.7 cannot
read Java 25 class files. Compiling generated mods for the JVM's feature version made a
1.20.6 server on a JDK 25 log `Unsupported class file major version 69` for every mod
class. `PlatformInfo.maxTargetRelease()` now answers with the release the *host itself*
was compiled for — read from `org.bukkit.Bukkit`'s class-file header — and `--release`
is clamped to `min(runtime, backend, host)`. 1.20.6 and 1.21.8 resolve to java21, 26.2
to java25. **Phase D must answer the same question for Fabric/NeoForge**; the default is
the runtime's feature version, which is only correct when the loader's own tooling is as
new as the JVM.

### cpcache: deliberately deferred
§7.2's extract-once content-addressed cache is not implemented. Every path
`PaperClasspathProvider` yields is already a plain readable jar or a directory javac can
open; the cache exists for nested Jar-in-Jar entries and ModLauncher `union:` URLs, which
only appear on the loaders. `ClasspathProvider`'s javadoc already assigns it to
implementations, so Phases D/E own it.

### meta.json v3: deliberately deferred
§5's `platform`/`mcVersion`/`side` fields are not implemented. Nothing in Phase C reads
them: there is one platform, `ModStore.normalize` would write fields no code consults,
and the `(other platform)` browser badge has nothing to badge. Phase D adds them
together with the code that needs them — and should note that `ModStore` persists
`ConfigKnob` with the record component name `def`, not the LLM contract's `default`.

### The acceptance gate, and how it is run
`scripts/smoke-paper.sh <version> [--force-chat]` is the whole Phase C gate in one
command: it downloads a Paper build, seeds a canned mod straight into the store (so no
LLM and no API key are needed — restore-on-boot compiles and hot-loads it exactly as a
generated mod), waits for the plugin's own "is live" line, drives it over RCON
(`scripts/smoke-rcon.py`, dependency-free), and then joins as a real headless player
(`scripts/smoke-bot.js`, mineflayer) because a `Screen` only renders for a player. The
bot clicks the way a client does: by running the `/vibe ui <token>` command it finds in
the message JSON.

Results:

| Server | Probe | UI | Result |
|---|---|---|---|
| 1.20.6 | `dialogs=false profile=paper-legacy target=java21` | chat | **zero** `io.papermc.paper.dialog` classes loaded (of 26136); canary compiles → loads → `/smokeping` answers → knob change applies live → action runs → disable removes the command → re-enable restores it; 25 player-driven chat-UI checks pass |
| 1.21.8 | `dialogs=true profile=paper-modern target=java21` | dialogs | bare `/vibe` and `/vibe settings` push a `show_dialog` packet and print no chat block; full RCON lifecycle green |
| 1.21.8 `--force-chat` | `dialogs=true` (probe stays honest) | chat | the only dialog class loaded is the probe's own `Class.forName`; the renderer never links; same 25 player checks pass |
| 26.2 | `dialogs=true profile=paper-modern target=java25` | dialogs | full RCON lifecycle green (mineflayer does not speak 26.2, so the player phase skips itself) |

`run-paper` **3.1.0** (not 2.x — 3.x speaks the current `fill.papermc.io` API) provides
`runServer` (1.21.8), `runServer1_20_6` and `runServer26_2`. Verified booting for real.

**Deviation from §9:** "manual QA on dev server" for the 17 dialog screens was replaced
by the scripted gate above. The dialog renderer is verified to render (packet-level) and
to be a mechanical refactor; a screen-by-screen visual diff against v1 needs a human
with a client and is the one item this branch cannot self-certify.

`ErrorsSelfTest` moved to `core/src/test` with the class it tests; `:core:selfTest` now
runs all five, and `./gradlew selfTestEcj` runs the compile-heavy two on ECJ (not wired
into `check` — it doubles the corpus compile time and answers a portability question,
not a correctness one).

### One behaviour change worth knowing
Chat mode (`/vibe chat`) is now a `ChatBridge` capture that never finishes. The bridge
allows one capture per player, so opening a chat-rendered form ends chat mode. That is
the honest outcome when two flows both want the player's next line, and it can only
happen on a server with no dialog support.

## 10.3 Phase D record (Fabric) — what actually landed

**Everything on the §9 Phase D checklist landed**, with three deviations flagged
below and one residual human-QA item. The target is **MC 26.2** (newest stable
26.x), fabric-loader **0.19.3**, fabric-api **0.158.0+26.2**, Loom **1.17.19**,
Java **25**.

Read this section before Phase E: most of it transposes, and the parts that do
not are called out.

### The unobfuscated era changes how you find things

There is no mappings browser any more. The game jar Loom resolves *is* the
documentation, and every Mojang and Fabric signature in `fabric/` was verified
with `javap` against it rather than recalled. `:fabric:printCp` exists for
exactly this and is kept deliberately:

```
javap -cp "$(./gradlew -q :fabric:printCp | tail -1)" net.minecraft.<X>
```

Three assumptions the plan carried did not survive that check:

- **`GuiGraphics` does not exist on 26.x.** It is
  `net.minecraft.client.gui.GuiGraphicsExtractor`, in a state-extraction
  rendering model: `drawString` → `text(Font, String, x, y, colour[, shadow])`,
  `renderItem` → `item(ItemStack, x, y)`, `fill`/`outline` survive, and
  `pose()` returns a 2D `Matrix3x2fStack`. Fabric's HUD element is
  `HudElement#extractRenderState(GuiGraphicsExtractor, DeltaTracker)`, not
  `render`. **Decision 9 paid for itself here**: had generated code been
  Mojang-typed as the client-design pass proposed, every stored HUD mod would
  now be a compile error. Instead `FabricHudCanvas` is sixty lines and the six
  methods generated code sees have not moved.
- **`ResourceLocation` is `net.minecraft.resources.Identifier`.**
- **`CommandSourceStack#hasPermission(int)` is gone.** 26.x has a typed
  `net.minecraft.server.permissions.PermissionSet`. See "Permissions" below.

Also worth knowing: `Level#getDayTime()` is gone (the clock is per-dimension —
`getDefaultClockTime()` / `getOverworldClockTime()`), and `ResourceKey` exposes
`identifier()` where it used to expose `location()`.

### Loom under the no-remap plugin id

The plugin is **`net.fabricmc.fabric-loom`**, which since Loom 1.14 means "the
game is unobfuscated". Under it Loom registers *no* `mappings` configuration
(`loom.officialMojangMappings()` throws at configuration time), *no*
`modImplementation`/`modApi`/`modRuntimeOnly`, and *no* `remapJar` — the plain
`jar` IS the mod jar and `include` nests into it. Access wideners are
`.classtweaker` files now. Loom 1.17 needs Gradle ≥ 9.5; the repo is on 9.7.1.

`fabric/` is the one module that leaves the repo-wide Java 21 toolchain: MC 26.x
requires Java 25. `core`/`platform-api`/`sdk-client` stay at 21 and load fine.

**`include` is not transitive.** Nesting `adventure-api` alone ships a mod that
boots and then dies with `NoClassDefFoundError: net/kyori/examination/Examinable`
the first time it builds a message. The nesting is driven off the resolved
artifact graph instead, skipping `adventure-bom` (a platform with no jar, which
Loom refuses to nest with a variant-matching error).

### adventure-platform-mod: NOT used — the §8.5 check, answered

§1 assumed it would provide Adventure on the loaders and §8.5 asked whether it
ships `Audience#showDialog` before building a dialog renderer. It was checked.
**The answer is no, three times over:**

1. **No dialog support at all.** A full-tree grep of its 143 sources for
   "dialog" is empty. Neither `ServerPlayerAudience` nor `ClientAudience`
   overrides `showDialog`/`closeDialog`, so those inherit Adventure's default
   *no-op*: calling them compiles and silently does nothing. (Adventure's own
   `DialogLike`, `@since 4.22.0`, is still an empty marker interface —
   "initial native support until Adventure has full API".) It therefore buys
   nothing for the single reason §8.5 named it.
2. **It would force an Adventure major bump.** Its MC 26.2 build (7.1.1) pulls
   **Adventure 5.2.0**; the rest of this project compiles against 4.24.0,
   which is what Paper provides. `core` is compiled once and shipped to both
   hosts, so a different Adventure major under it is a binary-compatibility
   risk in dozens of places.
3. **It cannot honour the MC 26.1+ floor with one Adventure.** 7.1.1 is 26.2 +
   Adventure 5; 6.9.0 is 26.1.x + Adventure 4.26. Supporting the stated floor
   through it means two Adventure majors in one product.

Instead: **Adventure 4.24.0 is nested directly**, and the four `Audience`
methods `core` actually uses are implemented over vanilla in `FabricAudience`
(`sendMessage`, `playSound`, `showBossBar`, `hideBossBar` — that is the entire
Adventure surface `core` and `platform-api` touch, verified by grep). Text
crosses into the game through `FabricText`: Adventure → JSON → vanilla
`ComponentSerialization.CODEC`, which is lossless because both sides implement
the same wire format.

The boss bar is the only non-trivial one. Adventure's `BossBar` is a live
mutable observable object that `Progress` animates; vanilla's `ServerBossEvent`
is a server-side object you push players into. Each shown bar gets a paired
`ServerBossEvent` plus a `BossBar.Listener` forwarding mutations, dropped on
hide. **Phase E can reuse `FabricAudience` and `FabricText` essentially
verbatim** — neither names a Fabric type.

**Gson is not nested either**, contra §1: Minecraft depends on it (2.14.0) and
Fabric shares game libraries with mods.

### Dialogs, and the one mixin

Built natively per §8.5. `FabricDialogRenderer` maps the same `Screen` model
`PaperDialogRenderer` does onto vanilla records — `FORM → ConfirmationDialog`,
`MENU → MultiActionDialog`, `NOTICE → NoticeDialog`, inputs 1:1 onto
`TextInput`/`BooleanInput`/`SingleOptionInput`/`NumberRangeInput` — and shows it
with `ServerPlayer#openDialog(Holder.direct(dialog))`. **`Holder.direct` is what
makes this possible without a datapack**: `Dialog.STREAM_CODEC` encodes a direct
holder inline, so nothing is registered.

Two vanilla details that will bite Phase E identically:

- `CommonDialogData`'s codec **rejects `pause=true` with an after-action that
  never unpauses**. The renderer passes `pause=false` regardless, which is also
  the only sane answer in singleplayer: pausing would freeze the very server
  generating the mod behind the dialog.
- `ItemBody.item` is an **`ItemStackTemplate`**, not an `ItemStack`.

**Responses need a mixin, and there is no way around it.** Vanilla has no
callback channel: a button's `CustomAll` action makes the client send
`ServerboundCustomClickActionPacket(Identifier, Optional<Tag>)`, and vanilla's
handler forwards to `MinecraftServer#handleCustomClickAction(Identifier,
Optional<Tag>)` — which is a `LOGGER.debug` no-op **and does not receive the
player**. The only hook that knows *who* clicked is
`ServerCommonPacketListenerImpl#handleCustomClickAction`, where `this` is the
connection. One mixin, injected at HEAD and hopped with `server.execute` (at
HEAD `PacketUtils.ensureRunningOnSameThread` has not run yet, so we may be on a
Netty thread), casting to `ServerGamePacketListenerImpl` for `.player`.
`ServerGamePacketListenerImpl` does not override the method, so mixing in there
instead is not an option.

`DialogClicks` mints one-shot, per-player, 5-minute-TTL tokens as the
Identifier's path — the same discipline the chat renderer's `/vibe ui <token>`
route already uses, and for the same reason: a dialog can sit open on a client
forever.

### The classpath, and the bug the dev environment hides

`FabricClasspathProvider` asks four sources and unions them: the `minecraft`,
`fabricloader` and `vibemod` mod containers, every `fabric-*` container, **the
loader's own classpath**, and `java.class.path`.

That fourth one is the one that matters and the one the first version missed.
On a real Fabric server `java.class.path` holds exactly one entry — the launcher
jar — because Knot loads the game and its ~50 libraries itself, and those are
game libraries, not mods. Under Loom's dev run Gradle puts everything on
`java.class.path`, so the gap is completely invisible there. The acceptance gate
found it the first time it compiled a mod calling `src.sendSystemMessage(...)`:
`cannot access com.mojang.brigadier.Message`. Two independent answers are now
used, because "VibeMod cannot compile anything" is the worst failure this mod
has: reflective `FabricLauncher#getClassPath()` (loader-internal, no public
equivalent exists) and a walk of the `libraries/` tree beside the game dir.
**Phase E must answer the same question for ModLauncher.**

### cpcache (§7.2) — landed, in core

`core/compile/CpCache`: content-addressed, extract-once materialization of any
classpath origin a compiler cannot open, pruning orphans each assembly. It lives
in `core` rather than in `fabric` because NeoForge needs exactly the same thing.

The load-bearing test is `path.getFileSystem() != FileSystems.getDefault()` — a
Jar-in-Jar entry also ends in `.jar` and also reports as a regular file, and
only its filesystem gives it away. Directories are zipped rather than passed
through (a directory reached through a nested filesystem is exactly as unreadable
to javac as a nested jar) and hashed by entry path/size/mtime rather than by
content, because an exploded classes directory can be hundreds of megabytes and
this runs every boot.

**Phase E adds `union:` URL translation** on top: strip the scheme and the
`%23n!/` suffix. `CpCache` will then materialize the result like any other
non-plain path.

### `maxTargetRelease()` on Fabric — the §10.2 question, answered

**The runtime's own feature version, and nothing lower.** The chain was checked
end to end: generated classes are defined by `ModLifecycle.BytesClassLoader`, a
plain `ClassLoader` subclass calling `defineClass` directly, whose parent is
Knot. Knot's transformer and Mixin's transformer only see classes they themselves
*load* — Mixin applies inside `KnotClassDelegate`'s class-load path, and a child
loader that defines bytes itself never enters it. Nothing between the compiler
output and the JVM reads the class file.

Paper's answer differed only because its plugin remapper pipes every dynamically
defined class through ASM. **Phase E must check whether ModLauncher does the
same** — it transforms far more aggressively than Knot, so the answer may well
be NeoForge's own class-file version rather than the JVM's.

Verified empirically: the gate compiles and hot-loads mods at `target=java25` on
a real 26.2 server. `InMemoryCompiler` still clamps to
`min(runtime, backend, host)`, so a bundled ECJ that lags the JVM lowers it on
its own — which is the case that actually bites, not the host.

### Permissions on 26.x

`CommandSourceStack#hasPermission(int)` is gone; permissions are a
`PermissionSet` of typed `Permission`s. fabric-api ships
**`fabric-permission-api-v1`** (new, and `CommandSourceStack` implements its
`PermissionContextOwner`), whose
`checkPermission(Identifier, PermissionLevel)` asks any installed permission
manager about a real node first and falls back to the level when nothing
answers. So `vibe.admin` becomes the node `vibemod:admin` with a GAMEMASTERS
(op 2) fallback: a server with LuckPerms grants it by node, a vanilla-ish server
by op, and VibeMod never learns which.

### The client (§8)

All of it landed in the first client commit, as §8.1 requires.

- **Dispatchers**: one `HudElement` (`HudElementRegistry.addLast`), one
  `ClientTickEvents.END_CLIENT_TICK` listener, one `/vibec` root. Mods attach to
  and detach from mutable registries behind them.
- **Key pool**: 8 `KeyMapping`s registered at client init through
  `KeyMappingHelper`, under a `KeyMapping.Category` registered by Identifier
  (26.x: the 4th constructor argument is a `Category`, not a String). Lease =
  lowest free slot; the requested default is auto-bound **only** if the slot is
  unbound or was auto-bound before, so a rebind the player made in Controls
  always wins; release unbinds only what we bound.
- **`/vibec`**: one static root whose `mod`/`command` arguments are served by
  suggestion providers reading the live registry, so the tree never needs
  rebuilding as mods come and go.
- **Watchdog**: a second `Watchdog` instance measuring the render thread, sharing
  the server's budgets (pushed in at server start and on every `/vibe reload`)
  and the same trip path.
- **Storm path**: every dispatch is guarded; a failure is journalled with
  `where="client"` and counts towards the mod's error storm. **On top of that, a
  throwing entry is detached from its dispatch list immediately** rather than
  after the storm threshold — ten failures at sixty frames a second is ten
  frames, not ten seconds, and each one builds a stack trace on the render
  thread.

`FabricModHost` never names a client-only type: it takes a
`Function<ModHandle, ClientContext>` that is null on a dedicated server. That is
what lets one jar serve both, and Phase E should copy the seam.

### The §8.1 rule applies to the HOST, not just to generated mods

Worth its own heading because it was got wrong once here and the mistake is
invisible on a dedicated server.

"A Fabric event cannot be unregistered" is stated in §8.1 as the reason
*generated mods* must not subscribe directly. It applies just as hard to
VibeMod itself. A **client** can load world A, quit to the menu, and load world
B in one process — and each of those starts and stops a complete VibeMod host,
because the host lives with the integrated server. The first version registered
`ServerTickEvents.END_SERVER_TICK`, `ServerPlayConnectionEvents.DISCONNECT`,
`CommandRegistrationCallback` and all ten curated hooks from that per-server
bootstrap, which would leave one subscription per world ever loaded, all but the
last dispatching into a dead scheduler and a dead bridge, forever.

Every Fabric subscription now happens exactly once in `onInitialize()` and
resolves the live per-server object when it fires (null between worlds); the
bridges' `installDispatchers` are static and take a supplier. A dedicated server
never loads a second world, so no gate catches this — it needs reading for, and
**Phase E must read for it too**: NeoForge's bus does support removal, but the
same "the host is per-world, the subscription is per-process" asymmetry exists
and the mod-loading events differ.

### The acceptance gate

`scripts/smoke-fabric.sh` — the dedicated-server half, **25/25 green**. It
downloads a real Fabric server launcher and fabric-api, installs the built jar
beside a pre-seeded canned mod, boots, and drives the whole flow over RCON with
assertions on every reply.

**It runs against the INSTALLED jar, not Loom's dev classpath**, and that is the
point: on the dev classpath Adventure and ECJ are plain classpath entries, while
in the shipped jar they are nested and have to be found through the loader and
materialized by the cpcache before javac can read them. Both bugs it found (the
classpath gap above, and RCON) are invisible under `runServer`.

The second bug is worth repeating because it is a design trap. `FabricSender`
originally routed console replies to the logging audience, reasoning that command
feedback and the log are different channels. They are — but **RCON's reply IS the
command's feedback and nothing else**, so routing away from it made the entire
management surface silent to the only caller that has no other one. Console
replies now go through the `CommandSourceStack`.

`fabric/src/gametest` — the client half, via **fabric-client-gametest**
(`./gradlew :fabric:runClientGameTest`), driving a real client through a real
singleplayer world with a real GL context. **29/29 green**, covering:

- the host initialises inside the client's integrated server, with
  `hasClient()` true and `isDedicatedServer()` false (the two really are
  different questions);
- a canned client-flavor mod's HUD, client tick, key lease and `/vibec` command
  all land in the live dispatchers and the key pool;
- `/vibec <mod> <command>` actually routes into the mod's handler, and pressing
  the leased key really reaches its `onPress` — both observed through marker
  files the handlers write, because the alternatives (a toast, a character on
  the HUD) are things on a screen, and asserting on pixels would be testing that
  Minecraft draws. The key press proves three things the pool's own bookkeeping
  cannot: that `"G"` was parsed and auto-bound, that the tick dispatcher's
  `consumeClick()` polling sees the press, and that it lands in the mod;
- disabling drains all four, and re-enabling re-leases a key slot and
  re-attaches the renderer (a pool that hands a slot back but never hands it out
  again would pass a teardown assertion and still exhaust itself after eight
  reloads);
- a deliberately-**throwing** HUD renderer is detached and its mod degraded,
  with the client still running;
- a deliberately-**slow** HUD renderer — the failure a try/catch cannot catch —
  trips the render-thread watchdog and is auto-disabled, with the client still
  running;
- **bare `/vibe` opens a native `DialogScreen` on the client.** That closes the
  §9 "singleplayer dialog path" item with an end-to-end observation rather than
  packet-level inference: player command → `Screen` model →
  `FabricDialogRenderer` → inline `Holder.direct` dialog →
  `ClientboundShowDialogPacket` → a screen the client actually put up.

Two of those checks needed a fix in the host rather than in the test. The
render watchdog was built with a `null` scheduler on the reasoning that its
trip handler needed no hop — but `ModLifecycle.disable` asserts it is on the
server thread, so the trip *cannot* do its job from the render thread. It now
takes a `DeferredTickScheduler` that resolves the live scheduler at call time,
which is sound because a trip needs a loaded mod and mods load with the world.

`runClientGameTest` is deliberately NOT wired into `check`: it boots a real
client and needs a display, which is a CI decision for Phase F, not a
precondition for `./gradlew build`.

**The `java.compiler` module probe** §9 asks for is implemented in
`FabricPlatformInfo.hasJavaCompilerModule()` and printed in the boot line on
every start, so the answer comes from whatever runtime a user actually launched
rather than from a developer's JDK. On this machine (a full JDK 25, dev client
and real dedicated server alike) it reads `java.compiler=present` and no
contingency is needed. **Not yet observed on a Mojang-launcher jlink runtime** —
if one ever reports `ABSENT`, the fix is to Jar-in-Jar the `javax.tools` API
classes, because ECJ implements `javax.tools.JavaCompiler` and cannot even load
without them.

### Deviations, flagged

1. **adventure-platform-mod is not used** (§1, §8.5). Reasons above. The
   consequence for Phase E: NeoForge should reuse `FabricAudience`/`FabricText`
   rather than reach for `adventure-platform-neoforge`, which has the same three
   problems.
2. **Gson is not nested** (§1). The game provides it.
3. **`/vibe` routing was promoted to `core`** as `command/VibeRouter` — which
   §1.1 explicitly permits ("Phase D may promote shared routing to core") but
   did not require. Fabric needs all 27 subcommands and the completion table
   verbatim, and the only Bukkit in the 1124-line original was
   `CommandSender`/`Player`, which is exactly what `Sender` abstracts. Paper's
   `VibeCommand` is now a 40-line adapter and **Phase E writes only a Brigadier
   node**. `PaperChatMode` moved to `core` as `runtime/ChatMode` unchanged.
4. **§6.1's profile-free `makePrompt`** (a §10.2 deviation) is unchanged. The
   `side` guidance §6.2's "all" row asks for is carried in the fabric profile's
   cheat sheet instead, where it reads as a manual-writing rule rather than as a
   field nobody sets.

### Residual human-QA item

**A screen-by-screen visual check of the 17 dialogs on a Fabric client.** The
renderer is verified to construct and show (the client gate proves the host is
live and the dedicated gate proves the flow), and the mapping is mechanical from
the same `Screen` data Paper's renderer consumes — but "does screen 12's source
listing wrap correctly at 400px in the vanilla dialog widget" needs a human
looking at it. This is the same item Phase C left open for Paper, for the same
reason.

## 10.4 Phase E record (NeoForge) — what actually landed

**Everything on the §9 Phase E checklist landed.** The target is **MC 26.2**,
NeoForge **26.2.0.67**, FML **11.0.16**, ModDevGradle **2.0.144**, Java **25** —
the same game version Phase D targets, deliberately, so the two loader hosts can
be compared jar to jar.

Phase E was smaller than Phase D, and the reason is worth stating first: **most
of a loader host is not loader code.**

### `loader-common`: the third of the codebase that is neither Fabric nor NeoForge

Fourteen of the Fabric module's twenty-one classes named no Fabric type at all.
Both loaders run official Mojang names on 26.1+ and both consume the same sdk
mod flavor, so the dialog renderer, the mod host, the Brigadier command bridge,
the Adventure↔vanilla text and audience adapters, the tick scheduler, the config
reader, the dialog-click token table and the entire client surface below the
loader's own hooks are the same work twice. Transposing them would have been
~2700 duplicated lines, and the 17-screen dialog mapping would then have drifted
between the two hosts silently.

They now live in **`loader-common/src/main/java`** (package
`com.gijsm.vibemod.loader`), which is **a shared SOURCE directory, not a Gradle
module**, added as a `srcDir` by both `fabric` and `neoforge`.

A module was considered and rejected: a module needs a plugin, and the only two
candidates are Loom and ModDevGradle. Whichever one it applied, the *other*
loader would compile its host against a game jar produced by its rival's
toolchain — and NeoForge's jar is patched, so that is not a theoretical
difference. As a source directory each loader compiles the shared code against
**its own** game jar, which means a NeoForge patch that moves a vanilla
signature is a compile error in `neoforge` rather than a runtime surprise on
someone's server. This is exactly how `sdk/src/mod/java` was already wired in
Phase D, so it is a pattern this build already had.

Three classes were ~90% shared and ~10% subscriptions, and split into an
abstract base plus a thin loader subclass:

| shared (`loader-common`) | fabric | neoforge |
|---|---|---|
| `LoaderEventBridge` — the ten curated per-mod registries, revocation, dispatch policy | `FabricEventBridge` (event wiring) | `NeoForgeEventBridge` (bus wiring) |
| `LoaderChatBridge` — captures, the hop, the one-per-player rule | `FabricChatBridge` | `NeoForgeChatBridge` |
| `LoaderClientEventBridge` — HUD/tick dispatch, key pool, `/vibec` registry, storm policy | `FabricClientEventBridge` | `NeoForgeClientEventBridge` |

Per-loader after all that: `PlatformInfo`, `ClasspathProvider`, the three bridge
subclasses, the two entrypoints, and (Fabric only) one mixin. Nine files on
NeoForge against Fabric's twenty-one.

**The one thing in `loader-common` that was NOT loader-neutral was permissions.**
`CommandSourceStack#checkPermission(Identifier, PermissionLevel)` is not vanilla
— fabric-permission-api-v1 *injects* it. NeoForge answers the identical question
(node first, op level when no permission manager answers) through
`PermissionAPI` plus `PermissionNode`s registered at `PermissionGatherEvent`. So
`LoaderSender` takes a `PermissionOracle` the host installs at boot rather than
branching on a platform, and the two hosts stay one shape.

### `/vibec` is the only client code that genuinely differs

Fabric's client commands take a `FabricClientCommandSource` and are registered
through `ClientCommands.literal`; NeoForge's take an ordinary
`CommandSourceStack` and `Commands.literal`. That is the whole difference, and
it is why the Brigadier tree is the one part of the client bridge that is
written twice.

Two consequences worth recording. NeoForge's `RegisterClientCommandsEvent`
**re-fires on every connection** where Fabric's callback fires once per client —
which costs nothing, because the `/vibec` root is static by design (§8.3) and
its suggestion providers read the live registry. And NeoForge's
`refreshCompletions()` is **deliberately empty**: Fabric needs
`ClientCommands.refreshCommandCompletions()` because that call is what merges
the client tree into the connection's dispatcher, but a client command's
suggestions are computed locally on every keystroke and this root never changes,
so there is nothing to invalidate. Calling a getter there to look busy would
have been worse than saying so.

### Dialogs: NeoForge does not need the mixin

§8.5 said one mixin on `ServerCommonPacketListenerImpl` was unavoidable
"unless NeoForge exposes an event". **It does.**

Vanilla's own handler for a dialog button's `custom_click_action` packet is a
`LOGGER.debug` no-op that does not receive the player, which is the entire
reason Fabric needs a mixin. NeoForge **patches that method**: it forwards to
`MinecraftServer#handleCustomClickAction(Identifier, Optional<Tag>, ServerPlayer,
GameProfile)`, where `CommonHooks.onCustomClickAction` posts
`net.neoforged.neoforge.event.entity.player.CustomClickActionEvent` carrying the
player, the identifier and the payload — **after**
`PacketUtils.ensureRunningOnSameThread`, so already on the server thread.

So the whole mixin, its `vibemod.mixins.json`, its `@Shadow` of the server
field and its explicit `server.execute` hop reduce to one `addListener` at
`EventPriority.HIGHEST`, cancelling the event when the token was ours.
`neoforge/` ships **no mixins at all**, and `DialogClicks` — the one-shot,
per-player, TTL'd token table — is shared between the two hosts unchanged.

Everything else in §8.5 transposed exactly, including both vanilla traps Phase D
flagged: `CommonDialogData`'s codec still rejects `pause=true` with a
never-unpausing after-action, and `ItemBody.item` is still an
`ItemStackTemplate`.

### `union:` URLs are gone, and the translation is kept anyway

§7.1 asked Phase E for `union:` URL translation. **FML 11 no longer produces
them.** SecureJarHandler is not on the classpath at all; its replacement is
`net.neoforged.fml.jarcontents.JarContents`, whose `getContentRoots()` returns
ordinary `Path`s, and a full-tree string search of `fml-loader-11.0.16.jar` for
"union" comes back empty.

`LoaderUris` implements it regardless, generalized to `file:`, `jar:file:…!/`
and `union:…%23n!/`, and unit-tested by `UriSelfTest` (wired into
`:neoforge:check`). It costs nine lines, module locations are the one place a
loader can still hand out something exotic, and a classpath provider that
silently drops an entry it cannot parse produces "VibeMod cannot compile
anything" — the worst failure this mod has.

### The classpath, for ModLauncher

§10.3 told Phase E to answer for ModLauncher what Phase D had to answer for
Knot: **the loader's classpath is not `java.class.path`.** It is not, for the
same reason — on a real NeoForge server `java.class.path` holds the bootstrap
and FML builds a module layer for the game and its ~60 libraries itself.

`NeoForgeClasspathProvider` unions four sources, then `LoaderUris`, then
`CpCache`:

1. **the game module layer** — `FMLLoader.getCurrent().getGameLayer()`, whose
   resolved modules' locations are exactly what ModLauncher loaded. This is the
   ModLauncher equivalent of Knot's `FabricLauncher#getClassPath()`, and unlike
   that one it is **public API**, so no reflection is needed here;
2. **every mod file's content roots** — `JarContents#getContentRoots()` rather
   than `IModFile#getFilePath()`, because a mod loaded from directories in dev
   has several and the file path then names none of them;
3. **`ClasspathResourceUtils.getAllClasspathItems`**, FML's own enumeration of
   the loading class loader;
4. **a `libraries/` walk** beside the game dir, plus `java.class.path` and the
   code source of `MinecraftServer` as the last belt.

On the real installed server this resolves **151 entries**; the canary compiles
and hot-loads against them.

### `maxTargetRelease()` on NeoForge — the §10.3 question, answered

**The runtime's own feature version, and nothing lower — same as Fabric, unlike
Paper.** §10.3 warned this might differ, because ModLauncher transforms far more
aggressively than Knot and Paper's answer is genuinely lower than its JVM's.

It does not differ, and the reason is structural rather than incidental. FML's
class processors — access transformers, mixin, the coremod pipeline — run inside
the transforming class loader's own `findClass`. They see only classes it is
asked to **load**. `ModLifecycle.BytesClassLoader` is a child that calls
`defineClass` on bytes it already holds, so it never enters that path, and
nothing between the compiler output and the JVM's verifier reads the class file.

**This was measured, not reasoned.** `ClassFileCeiling` hand-assembles a minimal
class file at the running JVM's own class-file version and defines it through
`ModLifecycle.BytesClassLoader` itself — not a lookalike, so there is no gap
between the probe and the real path. It runs at every boot and the gate asserts
on the line:

```
Platform: neoforge 26.2 · profile=neoforge · dialogs=true · physicalClient=false
          · dedicated=true · target=java25 · java.compiler=present
Hot-load class-file ceiling: java25 class files (major 69) load through
          BytesClassLoader — the loader does not read hot-loaded bytecode
```

`InMemoryCompiler` still clamps to `min(runtime, backend, host)`, so a bundled
ECJ that lags the JVM lowers it on its own — which remains the case that
actually bites.

### The `java.compiler` module probe

`NeoForgePlatformInfo.hasJavaCompilerModule()`, printed in the boot line on
every start like Fabric's. On this machine it reads `java.compiler=present` on
both the dedicated server and the dev client, and no contingency is needed.
**Still not observed on a Mojang-launcher jlink runtime** — the same open item
Phase D left, for the same reason.

### The prompt profile is the Fabric one with a different role line

§6.2 allowed the neoforge profile its own cheat sheet with NeoForge event names.
It does not get one, on purpose: **a generated mod never sees a loader event.**
The ten curated `ctx.on*` hooks are its entire event surface, and which bus event
the host subscribed to on its behalf is the host's business — teaching the model
`PlayerLoggedInEvent` would be teaching it a name it is forbidden to write.

So the api source block, the import bans, the threading contract, the cheat
sheet and all three worked examples are the same text on both loaders, and
`LlmSelfTest` now asserts exactly that: the two prompts must differ **only** in
the loader's name and its manifest file. That is the guard on the real claim —
the sdk mod flavor is loader-neutral, so a mod generated for one loader compiles
unchanged on the other. The dedicated-server gates check the same claim from the
other end: **the Fabric and NeoForge canaries are byte-identical sources.**

### Jar-in-Jar

`jarJar` insists on a version *range* per nested module (a nested jar's metadata
is a dependency declaration FML resolves against every other mod's nested jars,
so two mods shipping the same library must be able to agree on one copy) — and,
exactly like Loom's `include`, **it nests only the coordinates it is handed, not
their transitives.** Adventure is a seven-jar graph; nesting the four named
modules produces a mod that boots and dies with
`NoClassDefFoundError: net/kyori/examination/Examinable`. The nesting is
therefore driven off the resolved artifact graph, the same shape
`fabric/build.gradle.kts` uses, and ships the same ten jars. Gson is not nested
(the game has it) and ECJ is nested unrelocated.

### The dedicated-server gate — 28/28

`scripts/smoke-neoforge.sh`, the exact twin of the Fabric one: run NeoForge's own
installer into a cached template, copy it into a throwaway server, install the
built jar beside a pre-seeded canned mod, boot, and drive the whole flow over
RCON with assertions on every reply.

It runs against the **installed jar**, not MDG's dev classpath, for the same
reason Phase D's does: on the dev classpath Adventure and ECJ are plain
classpath entries, while in the shipped jar they are nested under
`META-INF/jarjar` and must be found through FML and materialized by the cpcache
before javac can read them.

### The client gate — no NeoForge equivalent existed, so one was built

**NeoForge has nothing like fabric-client-gametest.** Its GameTest framework
runs on a dedicated server (`gameTestServer`) and never starts a client; MDG's
`unitTest` support runs JUnit against the game classpath with no window. Neither
can answer §8.1's question — *does a throwing HUD renderer crash the render
loop* — which is the entire point of a client gate.

So the gate is a **self-driving mod**: `neoforge/src/clientgate`, mod id
`vibemodgate`, its own source set and its own MDG run (`runClientGate`), and
never in the shipped jar. `scripts/clientgate-neoforge.sh` runs it from a clean
directory and turns its verdict file into an exit code.

Its shape is forced by where it runs. Everything happens inside
`ClientTickEvent.Post` on the render thread, so **nothing may block** — a
`waitTicks` would freeze the client under test. The test is therefore a list of
stages (a readiness condition, a timeout, a body) advanced one per tick, which
reads top-to-bottom as the same narrative as the Fabric test's method bodies.
It creates its own world through `WorldOpenFlows#createFreshLevel` — the call
fabric-client-gametest's world builder makes — rather than depending on
`--quickPlaySingleplayer` and a save copied in from somewhere.

Four things it found that are worth the next person's time:

- **`Minecraft#screen` is gone on 26.x**; the current screen lives on `Gui`
  (`mc.gui.screen()` / `mc.gui.setScreen(...)`). Add it to §10.3's rename list.
- **A first launch does not show the title screen**, it shows
  `AccessibilityOnboardingScreen`. A gate that waits for `TitleScreen` waits
  forever. The trigger is now "the client has been ticking a while and has no
  level", and the runner seeds an `options.txt` as well.
- **`ExecutorService#submit` buried a compile failure.** `LoaderTickScheduler.async`
  now logs a throwing task before rethrowing, and restore-on-boot logs its
  outcome to the log rather than only to the requesting audience. Both are real
  host bugs on every platform — a mod that fails to compile at boot was leaving
  no trace anywhere a human looks — and the gate is what surfaced them.
- **A mod's classes must not be on a second source set's runtime classpath.**
  Declaring `clientgateImplementation(sourceSets.main.output)` registers
  `build/classes/java/main` both as the `vibemod` mod file and as a plain
  classpath entry, under different loaders. Everything starts, and then every
  generated mod fails with *"HudCanary does not implement
  com.gijsm.vibemod.api.Mod"* — because by then there are two `Mod` interfaces.
  `compileOnly` is the correct declaration, not a workaround.

**The gate needs a display.** There is no headless mode, because the point is
that a HUD renderer really renders.

### Deviations, flagged

1. **`loader-common` is new** (§1's module list does not have it). It is a
   source directory, not a module, and it is shared by exactly the two loader
   hosts. Justified above; the alternative was 2700 duplicated lines including
   the entire dialog mapping.
2. **No mixin on NeoForge** (§8.5 assumed one). `CustomClickActionEvent` exists;
   §8.5's own "unless NeoForge exposes an event" clause covers this.
3. **`union:` translation is dead code on FML 11** (§7.1). Kept and unit-tested
   anyway, reasons above.
4. **The neoforge cheat sheet does not name NeoForge events** (§6.2 allowed it
   to). Reasons above.
5. **`/vibe export` is unsupported on NeoForge**, as on Fabric — §6.3's v1
   decision, unchanged.
6. Two host fixes landed outside the NeoForge module (the async-task log and the
   restore-on-boot log). The first is in `loader-common` and therefore affects
   Fabric too; the Fabric gates were re-run green afterwards.

### Residual human-QA items

1. **A screen-by-screen visual check of the 17 dialogs on a NeoForge client** —
   the same item Phase C left open for Paper and Phase D for Fabric, for the
   same reason. The renderer is verified to construct and show (the client gate
   watches a real `DialogScreen` appear) and the mapping is mechanical from the
   same `Screen` data, but "does screen 12's source listing wrap correctly at
   400px" needs eyes.
2. **The `java.compiler` probe on a Mojang-launcher jlink runtime**, inherited
   from Phase D.
3. **Multiplayer NeoForge**: every gate here is a dedicated server driven over
   RCON or a singleplayer client. A NeoForge client connected to a NeoForge
   dedicated server exercises the same code paths the two gates cover
   separately, but nothing has run both at once.

### Notes for Phase F (CI)

- **The matrix**, as tasks and scripts: `./gradlew build` (all modules,
  self-tests, corpus) · `./gradlew selfTestEcj` · `scripts/smoke-paper.sh` ·
  `scripts/smoke-fabric.sh` · `scripts/smoke-neoforge.sh`. Those five are fully
  headless and are the CI gate.
- **Needs a display**: `scripts/clientgate-neoforge.sh` and
  `./gradlew :fabric:runClientGameTest`. On Linux runners both need `xvfb-run`
  plus a software GL stack (Mesa `llvmpipe`); neither can run on a plain
  container. Budget ~5 minutes each, and expect them to be the flakiest jobs in
  the matrix — they are real game clients.
- **Cannot run headless at all**: nothing, as far as this phase found — but the
  two client gates have never been run under xvfb here, only on a real macOS
  display. That is the one Phase F assumption still unverified.
- **Caches worth keeping**: `~/.gradle`, `fabric/smoke-cache/` (the Fabric server
  launcher + fabric-api) and `neoforge/smoke-cache/` (the NeoForge installer and
  the ~200MB server tree it builds — the smoke script already treats it as a
  template and copies rather than reinstalling).
- **The client gates write verdict files**; the NeoForge one halts the JVM with
  an exit code and also enforces a wall-clock deadline on its own daemon thread,
  so a wedged client cannot hang a job.

## 11. Out of scope (v1) — recorded so nobody "helpfully" adds them

Chest/anvil GUIs; legacy Forge; Fabric ≤1.21.11; Spigot; Folia; Paper <1.20.6;
VibeMod↔VibeMod networking / server-pushed client mods; client-local mode on third-party
servers (v1.1); world-render hooks, custom screens, raw input, networking, mixins in
generated code; loader jar export; dynamic top-level client commands; registry content
(items/blocks) in generated mods on any platform.
