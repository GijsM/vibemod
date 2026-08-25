# The fixture corpus

A tiny, committed stand-in for the real stored-mod corpus, in exactly the
on-disk layout `ModStore` reads (`<Name>/meta.json` + `<Name>/v<N>/*.java`).

`StoreSelfTest` compiles **every version of every mod in here** against the live
sdk, and — unlike the real corpus — this one is **required**, not skipped: it is
checked in, so it is present on a bare clone and in CI. The real corpus
(`server/plugins/VibeMod/mods`, ~570 sources, on the maintainer's machine only)
still runs on top when it exists.

That split is the point. The real corpus is the honest api-compatibility gate
but it cannot be committed — it is a user's generated mods, and it is huge. The
fixture is what makes `./gradlew build` on a GitHub runner actually exercise
`InMemoryCompiler` + `ModStore` + the frozen `com.gijsm.vibemod.api` surface
instead of printing `SKIPPED` and moving on.

Three mods, chosen so between them they touch every path the corpus gate exists
to protect:

| mod | what it is here for |
| --- | --- |
| `FixtureCanary` (v1, v2) | the ordinary case: two versions, two source files each, and between them `command` / `action` / `listen` / `repeat` / `later` / all four `config*` readers. Multi-version proves the gate walks history, not just `currentVersion`. |
| `FixtureLegacy` (v1) | a **pre-v3 mod**: `meta.json` without `schema`/`platform`/`mcVersion`/`side`, a class that `implements VibeMod` (the deprecated bridge), and imports of `com.gijsm.vibemine.api` — the pre-rename package. `ModStore.sources()` rewrites those imports on read; if that rewrite or the bridge interface ever goes away, this mod stops compiling and the gate says so. |
| `FixtureClient` (v1) | the `sdk-client` surface: `hasClient()`, `client(...)`, a HUD renderer, a key lease, a client tick handler and a `/vibec` command. Nothing else in the headless matrix compiles client-flavored generated code. |

Keep it tiny. This is a compile fixture, not a test suite: if you want to assert
on *behaviour*, the smoke scripts boot real servers and are the place for it.
