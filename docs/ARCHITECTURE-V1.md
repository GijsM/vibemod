# VibeMod architecture contract — v1/v2/v3 (HISTORICAL)

> **This document is history, not the contract.** It was written when VibeMod was one Paper
> 1.21.8 plugin, and VibeMod 2.0.0 is three hosts on two class-name eras. Read
> [ARCHITECTURE-V2.md](ARCHITECTURE-V2.md) for the architecture that is actually built; read
> this one to understand why something in `core/` looks the way it does.
>
> Specifically stale, and left standing on purpose rather than quietly corrected:
>
> - **"one Paper 1.21.8 plugin"** — the floor is Paper **1.20** (ARCHITECTURE-V2 §10.6; it was
>   claimed as 1.20.6 until a sweep measured lower), and there are two more hosts.
> - **The "frozen surface" rules.** They froze `com.gijsm.vibemod.api` for the *stored corpus*,
>   and that promise is kept — every source in it still compiles, and CI proves it. But the
>   surfaces below are Bukkit-typed, and on the loaders the same class names carry a
>   Mojang-typed flavor (ARCHITECTURE-V2 §4.1). "Frozen" now means "frozen per flavor".
> - **"No new dependencies."** Untrue three times over: the loader jars nest ECJ and Adventure,
>   and the Paper jar bundles bStats. Adventure was already in use before the rule was written.
> - **"Only `org.bukkit.*` / `io.papermc.paper.*`, never `net.minecraft.*`"** — a rule for the
>   *Paper* host, and still correct there. `loader-common/`, `fabric/` and `neoforge/` are
>   Mojang-typed from top to bottom.
> - **"Java 21 language level"** — still true for every module except `fabric` and `neoforge`,
>   which compile at 25 because Minecraft 26.x requires it.
> - **Package paths** like `plugin/src/main/java/...` predate the Gradle multi-module split.
>
> Everything below is unedited from the v3 era.

---

This file records the frozen implementation contracts each iteration (v1, v2, v3) was built
against — the exact public surfaces, addendum by addendum. It is the reference for how the
plugin fits together.

> **Naming note:** in v1/v2 the api contract interface that generated mods implement was itself
> named `VibeMod`; in v3 it was renamed to `Mod` (with a deprecated `VibeMod extends Mod` bridge
> kept so older generated sources keep compiling). Api-interface mentions below ("implements
> VibeMod", "api/VibeMod.java") are the v1/v2-era name for what is now `Mod`; every other
> "VibeMod" refers to the plugin.

VibeMod = one Paper 1.21.8 plugin (`com.gijsm.vibemod`) that turns a player prompt
(`/vibe make "sheep can fly"`) into LLM-generated Java, compiles it **in-process** with `javax.tools`,
and hot-loads it as a "mod" in a child `URLClassLoader` under VibeMod's plugin identity.
Generated mods are NOT Bukkit plugins (runtime plugin loading is unsupported on modern Paper).

Ground rules for ALL code in this repo:
- Java 21 language level (`--release 21`), Paper API 1.21.8 (`io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT`).
- **No new dependencies.** Allowed: JDK, Paper API, and Gson (`com.google.gson`) which Paper bundles at runtime
  (declare only paper-api in the pom; gson is provided transitively by it).
- Only `org.bukkit.*` / `io.papermc.paper.*` API — never `net.minecraft.*` / CraftBukkit internals,
  EXCEPT the one sanctioned reflection site in `DynamicCommands` described below.
- Every public class gets a short javadoc. Match the style of the frozen files in
  `plugin/src/main/java/com/gijsm/vibemod/api/`.
- Thread rule: all Bukkit API calls on the main thread. LLM/HTTP work off-thread,
  hopping back via `Bukkit.getScheduler().runTask(plugin, ...)`.
- Never hardcode any API key anywhere. Key resolution order: config.yml `openrouter.api-key`
  → env `OPENROUTER_API_KEY` → file `~/.config/vibemod/openrouter.key`.

## Frozen files (the shared contract)
- `api/VibeMod.java`, `api/VibeContext.java`, `api/ModCommandHandler.java`
- `gen/GeneratedProject.java` (record: `name, description, mainClass, files[path,content]`)
- `compile/CompileResult.java` (record: `success, classes(Map<String,byte[]>), diagnostics`)

## Frozen public surfaces (exact signatures)

### compile/InMemoryCompiler.java
```java
public final class InMemoryCompiler {
    /** extraClasspath entries are appended after the auto-detected paper jar + VibeMod jar. */
    public InMemoryCompiler(Path... extraClasspath)
    /** sources: fully-qualified class name -> source text. Never throws on bad source; returns failure result. */
    public CompileResult compile(Map<String, String> sources)
    /** true if a system java compiler is available (server started from a full JDK). */
    public static boolean available()
}
```
Classpath auto-detection: `Bukkit.class.getProtectionDomain().getCodeSource().getLocation()` (running
Paper jar) + `InMemoryCompiler.class.getProtectionDomain()...` (VibeMod jar). Must ALSO work in a
plain-JVM self-test where Bukkit isn't loaded: detect reflectively/gracefully — if a class isn't
present, skip that entry. Compile options: `--release <Runtime.version().feature()>`, `-proc:none`.
Capture bytecode in memory via ForwardingJavaFileManager; include inner classes in the result map.

### llm/OpenRouterClient.java
```java
public final class OpenRouterClient {
    public record ChatMessage(String role, String content) {}
    public OpenRouterClient(String apiKey, String model, Duration timeout)
    /** POST https://openrouter.ai/api/v1/chat/completions, returns assistant message text. */
    public CompletableFuture<String> complete(String systemPrompt, List<ChatMessage> messages)
    public String model(); public void setModel(String model);
}
```
JDK HttpClient only. Include headers `HTTP-Referer: https://github.com/gijsm/vibemod` and
`X-Title: VibeMod`. Surface API errors as failed futures with the response body in the message.

### llm/PromptLibrary.java
```java
public final class PromptLibrary {
    public static String systemPrompt()                       // the full system prompt (see below)
    public static String makePrompt(String request, String creator)
    public static String editPrompt(String request, Map<String,String> currentSources)
    public static String repairPrompt(String javacDiagnostics)
    public static GeneratedProject parse(String llmResponse)  // lenient: extract first balanced JSON object, Gson
}
```
System prompt requirements (write it WELL, this is the product): the model is an expert Paper 1.21.8
mod author writing against the VibeMod/VibeContext API (embed the full api interface sources verbatim
in the prompt); output STRICT JSON `{"name": PascalCase, "description": one line, "mainClass": simple
name, "files":[{"path":"X.java","content":"..."}]}` and nothing else; all files in package
`vibemod.<name lowercased>`; exactly one public class implements VibeMod; imports limited to `java.*`,
`org.bukkit.*` (NO net.minecraft, NO io.papermc internals, no reflection); never call Bukkit
register/scheduler APIs directly — only via ctx; all Bukkit interactions are main-thread (ctx tasks
already are); keep mods self-contained and defensive (null checks, world checks); Materials/EntityTypes/
Sounds must be real 1.21 enum constants; when spawning entities use `world.spawnEntity(loc, EntityType.X)`;
include 2 worked few-shot examples (a listener mod + a repeat-task mod). `parse` must tolerate markdown
fences and prose around the JSON.

### runtime/ModRegistry.java  (+ ModHandle, DynamicCommands, Watchdog in same package)
```java
public final class ModRegistry {
    public ModRegistry(Plugin plugin, DynamicCommands commands, Watchdog watchdog)
    /** Compile output -> live mod. Replaces (tears down) an existing mod of the same name. Main thread. */
    public ModHandle load(String name, int version, String description, String mainClassFqcn,
                          Map<String, byte[]> classes) throws ModLoadException
    public boolean disable(String name)     // teardown registrations + close loader, keep bytes; false if absent/disabled
    public boolean enable(String name) throws ModLoadException  // fresh loader from kept bytes
    public void unload(String name)         // disable + forget entirely
    public void panic()                     // disable ALL mods
    public ModHandle get(String name)       // null if unknown
    public Collection<ModHandle> mods()     // stable order by name
    public boolean runAction(String mod, String action, CommandSender sender, String[] args)
    public static final class ModLoadException extends Exception { public ModLoadException(String msg, Throwable cause) }
}
public final class ModHandle {   // read surface used by UI; internals package-private for the registry
    public String name(); public int version(); public String description(); public boolean enabled();
    public int listenerCount(); public int taskCount();
    public List<String> commandNames(); public List<String> actionNames();
}
```
Rules: each enabled mod = ONE fresh `URLClassLoader` (parent = VibeMod's own class loader) fed by an
in-memory map (subclass, override findClass -> defineClass from bytes). Teardown MUST use
`HandlerList.unregisterAll(listenerInstance)` per instance and `task.cancel()` per task — NEVER the
`Plugin`-wide overloads. VibeContext impl lives here and forwards registrations into the handle's
tracking lists, wrapping every listener/task/handler through the Watchdog. Listener registration goes
through `PluginManager.registerEvents(listener, plugin)` is NOT enough for timing — instead scan
`@EventHandler` methods and register each with `PluginManager#registerEvent(evtClass, listener,
priority, timedExecutor, plugin, ignoreCancelled)` where timedExecutor wraps the call in
`watchdog.time(modName, () -> ...)`. An exception thrown by a mod's handler must be caught, logged
with the mod name, and never propagate into Bukkit.

### runtime/DynamicCommands.java
```java
public final class DynamicCommands {
    public DynamicCommands(Plugin plugin, boolean allowTopLevel)
    /** Register /name at runtime. Returns true if a real top-level command was registered,
        false if unavailable/failed (caller then falls back to an action). Never throws. */
    public boolean register(String name, String description, CommandSender feedbackTarget, CommandExecutorLike handler)
    public void unregister(String name)     // remove + resync clients. Never throws.
    @FunctionalInterface public interface CommandExecutorLike { void run(CommandSender sender, String label, String[] args); }
}
```
Implementation: `Bukkit.getCommandMap()` (public on Paper) + a `Command` subclass instance, prefix
"vibemod". Unregister = reflectively remove from `SimpleCommandMap#knownCommands` ("name" and
"vibemod:name" and aliases) + `command.unregister(map)`. After ANY change call
`Bukkit.getOnlinePlayers().forEach(Player::updateCommands)`. Wrap everything in try/catch: on any
Throwable, log a warning and return false / degrade silently. Refuse to override a command that
already exists and wasn't registered by us (return false).

### runtime/Watchdog.java
```java
public final class Watchdog {
    public Watchdog(Plugin plugin, long singleInvocationMs, long perSecondBudgetMs)
    /** Wrap a mod entry point: times it, records, and returns whether the mod is still healthy. */
    public void time(String mod, Runnable body)
    public boolean isTripped(String mod)
    public void reset(String mod)
    /** Registry hooks in so a tripped mod is auto-disabled + broadcast. */
    public void onTrip(java.util.function.Consumer<String> handler)
}
```
Trip when a single invocation exceeds singleInvocationMs OR accumulated time in a rolling 1s window
exceeds perSecondBudgetMs. On trip: stop timing, call the handler ONCE (main thread, next tick), and
short-circuit further invocations of that mod's wrapped bodies until reset.

### store/ModStore.java (+ JarExporter.java)
```java
public final class ModStore {
    public record StoredVersion(int version, String prompt, String model, long createdAt) {}
    public record StoredMod(String name, String description, String mainClass, int currentVersion,
                            boolean enabled, String creator, List<StoredVersion> versions) {}
    public ModStore(Path modsDir)
    public List<StoredMod> all()                     // sorted by name
    public StoredMod get(String name)                // null if unknown
    public StoredMod saveNewVersion(String name, String description, String mainClass, String creator,
                                    String prompt, String model, GeneratedProject project)
    public Map<String,String> sources(String name, int version)   // FQCN -> source text
    public void setCurrentVersion(String name, int version)
    public void setEnabled(String name, boolean enabled)
    public boolean rollback(String name)             // current-1; false if already at v1/unknown
    public void delete(String name)
}
```
Disk layout: `<modsDir>/<Name>/meta.json` (Gson, pretty) + `<modsDir>/<Name>/v<N>/<File>.java`.
`sources` derives FQCN from each file's `package` declaration + filename. All IO exceptions wrapped
in `UncheckedIOException`.

```java
public final class JarExporter {
    public JarExporter(InMemoryCompiler compiler)
    /** Standalone Paper plugin jar. Embeds compiled mod classes, copies of the three api classes
        (bytes read from VibeMod's own jar), a generated <Name>Plugin JavaPlugin wrapper with a
        standalone VibeContext impl (no watchdog, plain registerEvents/scheduler/commandMap), and a
        generated plugin.yml (name=<Name>, main=wrapper, api-version 1.21). Also writes the source
        tree next to it as <Name>-src/. Returns the jar path. */
    public Path export(ModStore.StoredMod mod, Map<String,String> sources, Path outDir) throws Exception
}
```

### gen/ModGenerator.java — the surface callers use:
```java
public final class ModGenerator {
    public interface ProgressListener { void phase(String label); void detail(String line); }
    public record Result(boolean success, String modName, int version, int retries, String message) {}
    public CompletableFuture<Result> make(String prompt, String creator, ProgressListener l)
    public CompletableFuture<Result> edit(String modName, String prompt, String creator, ProgressListener l)
    public CompletableFuture<Result> remake(String modName, String creator, ProgressListener l)   // "again": rerun last prompt
}
```

### ui/ + command/
```java
public final class Progress {          // ui/Progress.java
    public Progress(Plugin plugin, CommandSender viewer, String title)
    public void phase(String label)    // player: boss bar advance (Thinking→Writing→Compiling→Loading) + chat; console: chat line
    public void detail(String line)
    public void succeed(String message)  // full bar, green, sound (UI_TOAST_CHALLENGE_COMPLETE) + firework particle at player, remove bar after 60 ticks
    public void fail(String message)     // red bar + VILLAGER_NO sound, remove after 100 ticks
}
public final class ModBrowserGui implements Listener {   // ui/ModBrowserGui.java
    public ModBrowserGui(Plugin plugin, ModRegistry registry, ModStore store, BiConsumer<Player,String> exportAction)
    public void open(Player p)
    // chest inventory: one item per mod (enabled=lime dye / disabled=gray dye, name+desc+version+counts in lore,
    // left-click toggle enable/disable, right-click rollback, shift-right-click delete w/ confirm, drop-key export)
}
public final class SourceBook {        // ui/SourceBook.java
    public static void give(Player p, String modName, Map<String,String> sources)  // written book, source split across pages (<=256 chars/page-ish, mono not possible: keep plain)
}
public final class ChatMode implements Listener {  // ui/ChatMode.java
    public ChatMode(Plugin plugin, BiConsumer<Player,String> onPrompt)
    public boolean toggle(Player p)    // when on: AsyncChatEvent lines from that player are cancelled and routed to onPrompt (hop to main thread)
}
public final class VibeCommand implements TabExecutor {  // command/VibeCommand.java
    // constructor takes (VibeMod plugin, ModGenerator gen, ModRegistry registry, ModStore store,
    //                    JarExporter exporter, ModBrowserGui gui, ChatMode chatMode, Supplier<String> model, Consumer<String> setModel)
    // subcommands: make|edit|again|list|source|rollback|enable|disable|delete|export|do|model|chat|gui|panic|help
    // make/edit take a quoted-or-rest-of-line prompt; permission vibe.use for read-only (list/source/help), vibe.admin for the rest; full tab completion incl. mod names
}
```
`/vibe list` prints hoverable/clickable lines using Paper's Adventure API (net.kyori.adventure, part of paper-api).

## Wiring (VibeMod.java)
onEnable: read config → construct compiler/client/store/watchdog/dynCommands/registry/generator/ui →
register /vibe (declared in plugin.yml) → async boot-restore: for each StoredMod enabled=true, compile
sources(current version) and registry.load on main thread.

## config.yml (defaults)
```yaml
openrouter:
  api-key: ""            # or env OPENROUTER_API_KEY, or ~/.config/vibemod/openrouter.key
  model: anthropic/claude-sonnet-5
  timeout-seconds: 120
generation:
  max-retries: 3
watchdog:
  enabled: true
  single-invocation-ms: 250
  per-second-budget-ms: 500
commands:
  allow-top-level: true
```

============================================================================
# V2 ADDENDUM (frozen contracts for the v2 iteration — legible, tunable mods)
============================================================================

v2 theme: per-mod config knobs with LIVE reads, model-written manuals + introspected verified
facts, writable-book workflows, a teaching GUI, diff-based repair rounds, /vibe reload.
Everything in the v1 sections above still holds unless amended here.

## Amended frozen files
- `api/VibeContext.java`: gains `configBool/configInt/configDouble/configString(String key)`.
  Resolution: stored value → knob default → type zero + one-time warning. Prompt rule: mods read
  config at the moment of use, never cache in fields.
- `gen/GeneratedProject.java`: now
  `(name, description, usage, manual, mainClass, files, config, edits)` with nested
  `ConfigKnob(key, type, def, description, min, max, step, choices)` (type ∈
  boolean|integer|decimal|text|choice) and `EditBlock(path, find, replace)`, plus
  `isEditResponse()`. usage/manual/config/edits all optional.

## store/ModStore.java v2
```java
public record StoredVersion(int version, String prompt, String model, long createdAt) {}  // unchanged
public record StoredMod(String name, String description, String usage, String manual,
                        String mainClass, int currentVersion, boolean enabled, String creator,
                        List<StoredVersion> versions,
                        List<GeneratedProject.ConfigKnob> config,
                        Map<String,String> configValues) {}
// saveNewVersion keeps its signature; usage/manual/config now come from the project argument.
// On a new version: keep existing configValues entries whose key is still in the new schema.
// Old meta.json deserializes with null usage/manual/config/configValues — every reader in this
// class must normalize nulls to ""/List.of()/Map.of() before returning (add a normalize(StoredMod) helper).
public void setConfigValue(String name, String key, String rawValue)  // validates vs schema: type parse,
        // min/max for numerics, membership for choice; throws IllegalArgumentException with a human reason
public Map<String,String> resolvedConfigValues(String name)  // schema defaults overlaid with stored values
```

## store/ModConfigs.java — NEW in v2
In-memory live-read cache the runtime contexts hit on every event; persistence via ModStore.
```java
public final class ModConfigs {
    public ModConfigs(ModStore store)
    public void register(String mod, List<GeneratedProject.ConfigKnob> schema, Map<String,String> values) // replace
    public void forget(String mod)
    public boolean bool(String mod, String key)     // typed live reads; schema-default fallback;
    public long integer(String mod, String key)     // unknown key/mod -> type zero + ONE-TIME warning
    public double decimal(String mod, String key)   // via java.util.logging (rate-limit per mod+key)
    public String text(String mod, String key)
    public void set(String mod, String key, String rawValue)  // validate -> cache -> store.setConfigValue
    public List<GeneratedProject.ConfigKnob> schema(String mod)   // empty list for v1 mods
    public Map<String,String> values(String mod)                  // resolved (defaults overlaid)
}
```
Thread-safety: reads are hot-path (event handlers) — ConcurrentHashMap, no locks on read.

## store/JarExporter.java v2
- Generated `StandaloneContext` implements the four config accessors by reading the exported
  plugin's own Bukkit config (`plugin.getConfig().getBoolean/Long/Double/String(key, <default>)`),
  defaults baked from the schema at export time.
- Export embeds a `config.yml` seeded with the mod's current resolved values, each key preceded
  by a `# <description>` comment line. plugin wrapper calls `saveDefaultConfig()` in onEnable.

## runtime/ModRegistry.java v2
```java
public ModRegistry(Plugin plugin, DynamicCommands commands, Watchdog watchdog, ModConfigs configs)
public ModHandle load(String name, int version, String description, String mainClassFqcn,
                      Map<String,byte[]> classes,
                      List<GeneratedProject.ConfigKnob> schema, Map<String,String> values)
        throws ModLoadException
// old 5-arg load stays as a delegate with List.of()/Map.of()
```
- load(): configs.register(name, schema, values) BEFORE onEnable; unload(): configs.forget(name);
  disable(): keep registered (values still viewable/settable while off).
- VibeContextImpl implements the four accessors → configs.bool(displayName, key) etc.

## runtime reload setters
- `Watchdog`: `public void setBudgets(long singleInvocationMs, long perSecondBudgetMs)` — volatile
  fields; 0/negative single budget = disabled (same semantics as constructor).
- `DynamicCommands`: `public void setAllowTopLevel(boolean allow)` — volatile; affects future
  registrations only.
- `OpenRouterClient`: `public void setTimeout(Duration timeout)` — volatile,
  used for subsequent requests.

## llm/PromptLibrary.java v2
- systemPrompt(): update the embedded VibeContext source to the REAL current file content
  (copy it verbatim from api/VibeContext.java); output contract adds:
  `"usage"`: one-line "try this" hint (e.g. "Kill a creeper and watch"),
  `"manual"`: 4-10 sentence player-facing guide that mentions the knobs,
  `"config"`: array of {key, type(boolean|integer|decimal|text|choice), default, description,
  min?, max?, step?, choices?}. Hard rules add: expose the values a player would obviously want
  to tweak as config knobs; read them with ctx.configX INSIDE handlers (never cache in fields).
- BOTH few-shot examples updated: ChickenCreepers gains a `chicken-count` integer knob (default 1,
  min 1, max 10, step 1) read live in the listener via ctx.configInt; SpeedPulse gains
  `period-seconds` (integer) and `strength` (choice: [weak,normal,strong]) — examples MUST compile
  (verify against paper-api like v1).
- EDIT RESPONSE SHAPE for editPrompt/repairPrompt rounds only: the prompt tells the model it MAY
  respond `{"edits":[{"path":"X.java","find":"<exact snippet>","replace":"<new snippet>"}],
  "usage"?, "manual"?, "config"?}` instead of full files when changes are small; `find` must match
  EXACTLY ONCE in that file (whitespace included). Initial generation must use the full shape.
- `parse()` accepts both shapes: full (files non-empty) or edit (edits non-empty, files absent/empty);
  reject responses with BOTH or NEITHER. Optional-field mapping: missing usage/manual/config → null.
- New builder: `public static String demandFullProject(String reason)` — a user message used by
  ModGenerator when an edit block failed to apply ("your edits did not apply cleanly: <reason>;
  return the FULL corrected project as strict JSON with complete files").
- editPrompt(request, currentSources) additionally takes the current schema + values:
  new signature `editPrompt(String request, Map<String,String> currentSources,
  List<GeneratedProject.ConfigKnob> schema, Map<String,String> values)` — includes them so edits
  preserve/extend knobs.
- NEW self-test requirement (in LlmSelfTest): assert the embedded VibeContext/VibeMod/
  ModCommandHandler constants match the real api/*.java files ignoring leading/trailing whitespace
  per line — read the real files from disk relative to a base dir passed as args[0].

## ui/BookFlows.java + ui/ConfigBookParser.java — NEW in v2
PDC keys (NamespacedKey(plugin,...)): `book-kind` (prompt|edit|config), `book-mod`,
`book-mod-version` (int), `book-owner` (player UUID string), `book-id` (random UUID string).
Capture must work from PDC alone (restart-safe); soft session map only for hints.
```java
public final class BookFlows implements Listener {
    public interface EditSubmit { void submit(Player p, String modName, String changeRequest); }
    public interface ConfigSubmit { List<String> apply(Player p, String modName, Map<String,String> values); } // returns per-key error strings
    public record ConfigEntry(String key, String description, String currentValue) {}
    public BookFlows(Plugin plugin,
                     BiConsumer<Player,String> onPromptSubmit,          // full text (title hint prepended as "Name hint: <title>\n" when signed with a custom title)
                     EditSubmit onEditSubmit,
                     ConfigSubmit onConfigSubmit,
                     Function<String, List<ConfigEntry>> schemaLookup)  // re-fetch at capture time
    public void givePromptBook(Player p);
    public void giveEditBook(Player p, String modName, int modVersion, String manualText, List<ConfigEntry> entries);
    public void giveConfigBook(Player p, String modName, int modVersion, List<ConfigEntry> entries);
}
```
Behavior (validated against Paper source):
- PlayerEditBookEvent is main-thread; isSigning() distinguishes Done vs Sign; PDC survives the
  round-trip (read it from event.getNewBookMeta().getPersistentDataContainer()).
- prompt/edit books: Done = ignore (vanilla draft save). Sign = read pages + title, setCancelled(true),
  consume the book NEXT TICK by scanning the player inventory for matching `book-id` PDC (never
  trust event.getSlot()), dispatch callback next tick. Owner mismatch -> refuse politely, no cancel.
- config books: Done = parse + apply + one chat feedback block (applied keys green, per-line errors
  red, clickable [fresh config book] -> /vibe config <mod>), do NOT cancel (vanilla keeps their text).
  Sign = apply + cancel + consume.
- Pre-fill: WritableBookMeta#setPages(String...) PLAIN STRINGS ONLY, <=13 lines and <=230 chars per
  page, <=4 knobs per config page as "# description\nkey: value", edit books = manual summary pages +
  "== Changes: ==" page + 3 blank pages, prompt books = instruction page + blank pages.
- Deleted mod at capture -> message + consume; version mismatch (book-mod-version != current) ->
  warn but proceed for edit, warn + still apply for config.
- Full inventory on give -> drop at feet (SourceBook pattern) + ITEM_BOOK_PAGE_TURN sound.
```java
public final class ConfigBookParser {   // pure static, zero Bukkit imports, unit-tested
    public record ParseResult(Map<String,String> values, List<String> errors) {}
    public static ParseResult parse(List<String> pages, Set<String> knownKeys)
}
```
Grammar: per page per line; strip §codes; trim; skip blank and #-or-//-prefixed lines; split on the
FIRST ':' or '='; key lowercased, matched case-insensitively; unknown key -> error with nearest-key
suggestion (edit distance <=2 or prefix); duplicate key -> last wins + warning entry; no separator ->
"page N, line M: expected 'key: value'". Values NOT validated here (schema's job).
Self-test: plugin/src/test/java/BookParserSelfTest.java (plain main, pure JVM) covering grammar,
comments, suggestions, duplicates, partial updates, §-stripping, empty input.

## ui v2 + command
```java
public record GuiCallbacks(BiConsumer<Player,String> export,
                           BiConsumer<Player,String> applyVersion,
                           BiConsumer<Player,String> giveConfigBook,
                           BiConsumer<Player,String> giveManualBook,
                           BiConsumer<Player,String> giveSourceBook,
                           Runnable reloadConfig,
                           Supplier<String> getModel, Consumer<String> setModel) {}   // ui/GuiCallbacks.java

public final class ModBrowserGui implements Listener {   // REWORK
    public ModBrowserGui(Plugin plugin, ModRegistry registry, ModStore store, ModConfigs configs, GuiCallbacks cb)
    public void open(Player p)          // main list: one item per mod, LEFT-CLICK opens detail panel
    public void openDetail(Player p, String modName)
    public void openSettings(Player p)  // ops page: model picker (curated list), watchdog/retry steppers, [reload] button
}
```
- Main list: lime/gray dye per live-enabled state, lore = wrapped description + "v<N> · click for details";
  last row: [settings] item visible only with vibe.admin permission.
- Detail panel: header item (wrapped description, usage line, state, version, creator); per-knob items —
  boolean toggle on click, choice cycles, integer/decimal shown with current value and edited via click
  (−step on left, +step on right, ×10 with shift, clamped to min/max, defaults step=1 min=0 max=1e9);
  text knob click -> cb.giveConfigBook; buttons: [manual] [source] [config book] [enable/disable]
  [rollback] [export] [delete w/ 5s confirm] [back]. Knob edits go through ModConfigs.set with the
  IllegalArgumentException message surfaced red.
- Settings page writes: setModel via callback; watchdog/retry steppers mutate config.yml via a
  BiConsumer<String,Object> configWrite callback? NO — keep it simple: settings page calls
  cb.reloadConfig only for the [reload] button, and model via cb.setModel; watchdog/retry steppers
  are DISPLAY + click hint "edit config.yml + click reload" in this iteration (avoid comment-stripping
  saveConfig writes). Document this in the lore.
- Shared word-wrap helper: ui/Text.java `public static List<Component> wrap(String s, int width, NamedTextColor c)`
  (~38 chars/line, no italics) — used by GUI lore and install card.
- SourceBook: page budget fixed to <=256 chars and <=13 lines per page.
```java
public final class InstallCard {   // ui/InstallCard.java — static builders
    public static Component build(ModStore.StoredMod mod, ModHandle liveOrNull)
        // name+version+state line, wrapped description, "Try: <usage>" when present,
        // clickable [manual] [config] [info] [off] buttons (runCommand /vibe ...)
    public static Component verifiedFooter(ModStore.StoredMod mod, ModHandle liveOrNull, Map<String,String> values)
        // introspected facts: commands, actions, listener/task counts, knobs with current values, creator
}
```
```java
public final class VibeCommand implements TabExecutor {   // REWORK — new constructor
    public VibeCommand(Plugin plugin, ModGenerator generator, ModRegistry registry, ModStore store,
                       ModConfigs configs, JarExporter exporter, ModBrowserGui gui, ChatMode chatMode,
                       BookFlows books, Supplier<String> getModel, Consumer<String> setModel,
                       BiConsumer<CommandSender,String> applyVersion, Runnable reloadConfig)
}
```
- New subcommands: `info <mod>` (install card + verified footer, works for console), `manual <mod>`
  (player: written book manual+footer+config table via a new ui/ManualBook.java static give(...);
  console: chat dump), `config <mod>` (player-only, books.giveConfigBook), `set <mod> <key> <value>`
  (configs.set, errors red, success shows old -> new), `book [mod]` (player-only; no arg = prompt
  book, arg = edit book), `reload` (runs reloadConfig, reports what changed where feasible).
- READ_ONLY (vibe.use) now also includes info + manual. `set` tab-completes: mod -> schema keys ->
  current value + choices. `list` rows: click -> /vibe info <name>, hover adds usage line.
- On successful generation, onGenerationDone prints the InstallCard (fetch store.get(result.modName())).
- help updated; keep every v1 subcommand working.

## gen/ModGenerator v2 + VibeMod v2 (wiring)
ModGenerator applies EditBlocks to the previous round's sources (exact-unique match
per file; failure -> next round uses PromptLibrary.demandFullProject), carries forward
usage/manual/config when an edit response omits them, and calls the 7-arg registry.load with the
schema+values (values from store after save). VibeMod constructs ModConfigs, BookFlows, GuiCallbacks,
wires /vibe reload (re-reads config.yml -> watchdog.setBudgets, client.setModel/setTimeout,
dynamicCommands.setAllowTopLevel, generator retry supplier), and passes the InstallCard path into
its finish() dedupe.

## v2 ground rules
- v1 mods (null usage/manual/config in meta.json) MUST degrade gracefully everywhere: normalize
  nulls at the ModStore boundary; empty knob panels say "No configurable settings"; info/manual fall
  back to description + verified footer only.
- No new dependencies. Books: plain-string pages only. All Bukkit calls main-thread.
- Existing self-tests must keep passing; update them where records grew (they construct positionally).

## v2.1: model-chosen GUI icons (frozen)
- `GeneratedProject` gained `String icon` (after `manual`) — a Bukkit `Material` ITEM name in
  UPPER_SNAKE (e.g. "CHICKEN", "SUGAR"); optional (null/"" = absent).
- `StoredMod` gains `String icon` (after `manual`); normalize null -> ""; threaded through all
  rebuild sites; `saveNewVersion` takes it from the project with carry-forward semantics handled
  upstream by ModGenerator.
- Prompt contract: `"icon"`: pick ONE thematic, obtainable ITEM Material name from vanilla 1.21
  (never a block-only/technical material, never AIR); both few-shot examples set one
  (ChickenCreepers -> "CHICKEN", SpeedPulse -> "SUGAR"). parse() maps it (optional).
- GUI: mod items render as `Material.matchMaterial(icon)` when it resolves AND `Material#isItem()`,
  else fallback `PAPER`. Enabled state = `ItemMeta#setEnchantmentGlintOverride(true)` (Paper 1.21
  API) instead of dye swapping; disabled = no glint + name in gray + "(off)" in the state lore.
  Applies to the main list item AND the detail-panel header item.

============================================================================
# V3 ADDENDUM — the VibeMod rename, debuggability, native dialogs
============================================================================
v3 theme: the plugin's public rename to VibeMod, runtime debuggability (per-mod error logs, a
degraded state, `/vibe fix`, `/vibe debug`), and native Paper dialogs replacing book-based UI.
Everything in the v1/v2 sections above still holds unless amended here.

## api rename
- The api contract interface generated mods implement was renamed `VibeMod` → `Mod`
  (api/Mod.java). A deprecated bridge `VibeMod extends Mod` (api/VibeMod.java) is kept so
  pre-v3 generated sources keep compiling; new generations teach and emit `implements Mod`.

## Style helper — ui/Style.java
```java
public final class Style {
    public static Component prefix()                       // "⬡ vibe " gradient-ish (two-tone), non-italic
    public static Component ok(String msg)                 // prefix + green
    public static Component warn(String msg)               // prefix + gold
    public static Component err(String msg)                // prefix + red
    public static Component info(String msg)               // prefix + gray
    public static Component button(String label, String command, String hover, NamedTextColor color) // "[label]" runCommand
    public static Component dot(boolean enabled, boolean degraded)  // ● colored green/gold/gray
}
```
ALL user-facing chat lines in ui/ + command/ route through Style. Semantic colors: GREEN success,
GOLD degraded/warn, RED error, AQUA clickable/actions, GRAY info.

## Dialogs — ui/Dialogs.java implementation notes
- Imports: io.papermc.paper.dialog.Dialog, io.papermc.paper.registry.data.dialog.{DialogBase,ActionButton},
  .input.DialogInput (+TextDialogInput.MultilineOptions), .body.DialogBody, .type.DialogType,
  .action.DialogAction; net.kyori.adventure.text.event.ClickCallback.
- Suppress the @Experimental warnings file-wide with @SuppressWarnings("UnstableApiUsage") + a javadoc
  note; do NOT let the build fail on them (no -Werror anywhere).
- Submit buttons: DialogAction.customClick(callback, ClickCallback.Options.builder().uses(1).build());
  callbacks MUST runTask to main before Bukkit calls; audience instanceof Player guard.
- Config dialog value mapping: numberRange returns Float — convert per knob type (integer: Math.round,
  respecting step); text inputs .maxLength(256) for knob text, 2000 for prompt/edit; every submitted
  value re-validated via the injected ConfigSubmit (which calls ModConfigs.set) — on any error,
  re-open the dialog via openConfig with a red plainMessage error body prepended and the player's
  submitted values as initial values (add a private overload carrying (errorMessage, priorValues)).
- Functional shapes reused from the deleted BookFlows: EditSubmit{submit(Player,String mod,String)},
  ConfigSubmit{List<String> apply(Player,String mod,Map<String,String>)} — declare them INSIDE Dialogs.
- openPrompt name-hint: single-line text input key "name" (maxLength 32, labelVisible); when non-blank,
  prepend "Name hint: <v>\n" to the prompt text (same convention BookFlows used).
- WAIT_FOR_RESPONSE afterAction on prompt/edit submit buttons; config uses CLOSE.

## Virtual books — ui/VirtualBooks.java
Replaces ManualBook + SourceBook giving; ManualBook.java and SourceBook.java were deleted (their
pagination logic folded in; the 256-char/13-line page budget kept as static helpers). Book building:
net.kyori.adventure.inventory.Book.book(Component title, Component author, List<Component> pages);
player.openBook(book). Surface: openManual/openSource/openErrors.
openErrors pages: one error per section — "3× NullPointerException", where-line, relative time,
then the truncated stack lines. VibeCommand console paths keep chat dumps (move the dump helpers
into VibeCommand or VirtualBooks statics taking CommandSender).

## GUI callbacks — ui/GuiCallbacks.java v3 (replaces the v2 record)
```java
public record GuiCallbacks(BiConsumer<Player,String> export,
                           BiConsumer<Player,String> applyVersion,     // = per-mod [reload]
                           BiConsumer<Player,String> configure,        // opens config dialog
                           BiConsumer<Player,String> editMod,          // opens edit dialog
                           BiConsumer<Player,String> fix,              // opens fix-confirm dialog
                           BiConsumer<Player,String> openManual,
                           BiConsumer<Player,String> openSource,
                           BiConsumer<Player,String> openErrors,
                           Runnable reloadConfig,
                           Supplier<String> getModel, Consumer<String> setModel) {}
```
ModBrowserGui ctor: (Plugin, ModRegistry, ModStore, ModConfigs, ModErrors, DebugEcho, GuiCallbacks).

## ModErrors — runtime/ModErrors.java detail
- errors.json shape: {"records":[ErrorRecord...]} Gson pretty; file lives at <modsDir>/<Name>/errors.json.
- report(mod,n): "== <mod> errors ==\n" + per record "N× <cls>: <msg>\n  at <topFrame> (<where>, last <rel-time>)\n<indented stack>".
- Relative-time helper package-private static (also used by UI via recent() records' lastSeen).
- Storm + episode fields volatile; setLimits mirrors Watchdog.setBudgets semantics.
- Plain-JVM self-test: plugin/src/test/java/ErrorsSelfTest.java — dedup, episode transitions, storm
  trip once, cap eviction, persistence round-trip (construct with a fake Plugin? NO — split the
  Bukkit-free core: put record/dedup/window/persistence in a package-private static core class or
  make the Bukkit scheduler hop injectable (Consumer<Runnable> mainThreadRunner param defaulting to
  Runnable::run in a test ctor) — implementer's choice, but the self-test must run without Bukkit).

## VibeCommand v3 — behavior notes
- `make`/`edit` argless + player -> dialogs.openPrompt/openEdit; console argless -> usage error.
- `fix <mod>`: sender==Player -> dialogs.openFixConfirm; console -> run fix immediately
  (generator.fix with errors.report(mod,8)) — console needs no confirm.
- `errors <mod>`: player -> VirtualBooks.openErrors + a compact chat summary; console -> report dump.
- `debug <mod> [on|off]`: no arg toggles; prints new state; tab-completes on|off.
- Keep `set` (RCON path) and `config` (dialog) both working. `book [mod]` aliases prompt/edit dialogs.
- READ_ONLY += errors. MOD_ARG_SUBS += errors, fix, debug.
- Generation completions route through Style + InstallCard as today.

## Ground rules
- No -Werror; @Experimental dialog API warnings are accepted and suppressed locally.
- v1/v2 mods and stored sources MUST keep working (bridge interface, null-safe reads).
- Existing self-tests keep passing; BookParserSelfTest is DELETED with its subject.
