# VibeMine architecture contract (FROZEN — implement exactly these surfaces)

VibeMine = one Paper 1.21.8 plugin, **VibeCore** (`com.gijsm.vibemine`), that turns a player prompt
(`/vibe make "sheep can fly"`) into LLM-generated Java, compiles it **in-process** with `javax.tools`,
and hot-loads it as a "mod" in a child `URLClassLoader` under VibeCore's plugin identity.
Generated mods are NOT Bukkit plugins (runtime plugin loading is unsupported on modern Paper).

Ground rules for ALL code in this repo:
- Java 21 language level (`--release 21`), Paper API 1.21.8 (`io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT`).
- **No new dependencies.** Allowed: JDK, Paper API, and Gson (`com.google.gson`) which Paper bundles at runtime
  (declare only paper-api in the pom; gson is provided transitively by it).
- Only `org.bukkit.*` / `io.papermc.paper.*` API — never `net.minecraft.*` / CraftBukkit internals,
  EXCEPT the one sanctioned reflection site in `DynamicCommands` described below.
- Every public class gets a short javadoc. Match the style of the frozen files in
  `plugin/src/main/java/com/gijsm/vibemine/api/`.
- Thread rule: all Bukkit API calls on the main thread. LLM/HTTP work off-thread,
  hopping back via `Bukkit.getScheduler().runTask(plugin, ...)`.
- Never hardcode any API key anywhere. Key resolution order: config.yml `openrouter.api-key`
  → env `OPENROUTER_API_KEY` → file `~/.config/vibemine/openrouter.key`.

## Frozen files (already written — read them, do not modify)
- `api/VibeMod.java`, `api/VibeContext.java`, `api/ModCommandHandler.java`
- `gen/GeneratedProject.java` (record: `name, description, mainClass, files[path,content]`)
- `compile/CompileResult.java` (record: `success, classes(Map<String,byte[]>), diagnostics`)

## Frozen public surfaces (each owner implements EXACTLY these signatures)

### compile/InMemoryCompiler.java
```java
public final class InMemoryCompiler {
    /** extraClasspath entries are appended after the auto-detected paper jar + VibeCore jar. */
    public InMemoryCompiler(Path... extraClasspath)
    /** sources: fully-qualified class name -> source text. Never throws on bad source; returns failure result. */
    public CompileResult compile(Map<String, String> sources)
    /** true if a system java compiler is available (server started from a full JDK). */
    public static boolean available()
}
```
Classpath auto-detection: `Bukkit.class.getProtectionDomain().getCodeSource().getLocation()` (running
Paper jar) + `InMemoryCompiler.class.getProtectionDomain()...` (VibeCore jar). Must ALSO work in a
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
JDK HttpClient only. Include headers `HTTP-Referer: https://github.com/gijsm/vibemine` and
`X-Title: VibeMine`. Surface API errors as failed futures with the response body in the message.

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
Rules: each enabled mod = ONE fresh `URLClassLoader` (parent = VibeCore's own class loader) fed by an
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
"vibemine". Unregister = reflectively remove from `SimpleCommandMap#knownCommands` ("name" and
"vibemine:name" and aliases) + `command.unregister(map)`. After ANY change call
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
        (bytes read from VibeCore's own jar), a generated <Name>Plugin JavaPlugin wrapper with a
        standalone VibeContext impl (no watchdog, plain registerEvents/scheduler/commandMap), and a
        generated plugin.yml (name=<Name>, main=wrapper, api-version 1.21). Also writes the source
        tree next to it as <Name>-src/. Returns the jar path. */
    public Path export(ModStore.StoredMod mod, Map<String,String> sources, Path outDir) throws Exception
}
```

### gen/ModGenerator.java — owned by the architect (do NOT implement), callers use:
```java
public final class ModGenerator {
    public interface ProgressListener { void phase(String label); void detail(String line); }
    public record Result(boolean success, String modName, int version, int retries, String message) {}
    public CompletableFuture<Result> make(String prompt, String creator, ProgressListener l)
    public CompletableFuture<Result> edit(String modName, String prompt, String creator, ProgressListener l)
    public CompletableFuture<Result> remake(String modName, String creator, ProgressListener l)   // "again": rerun last prompt
}
```

### ui/ + command/ (owner: UI agent)
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
    // constructor takes (VibeCore plugin, ModGenerator gen, ModRegistry registry, ModStore store,
    //                    JarExporter exporter, ModBrowserGui gui, ChatMode chatMode, Supplier<String> model, Consumer<String> setModel)
    // subcommands: make|edit|again|list|source|rollback|enable|disable|delete|export|do|model|chat|gui|panic|help
    // make/edit take a quoted-or-rest-of-line prompt; permission vibe.use for read-only (list/source/help), vibe.admin for the rest; full tab completion incl. mod names
}
```
`/vibe list` prints hoverable/clickable lines using Paper's Adventure API (net.kyori.adventure, part of paper-api).

## Wiring (owned by architect in VibeCore.java — for reference only)
onEnable: read config → construct compiler/client/store/watchdog/dynCommands/registry/generator/ui →
register /vibe (declared in plugin.yml) → async boot-restore: for each StoredMod enabled=true, compile
sources(current version) and registry.load on main thread.

## config.yml (defaults)
```yaml
openrouter:
  api-key: ""            # or env OPENROUTER_API_KEY, or ~/.config/vibemine/openrouter.key
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
