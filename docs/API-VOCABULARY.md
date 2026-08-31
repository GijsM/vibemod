# The measured Paper API vocabulary

**Generated — do not hand-edit.** Every number below was read out of a real
`paper-api` jar by `core/src/test/java/symbols/ClassFileVocabulary.java`, which
parses class files as bytes (constant pool, `fields[]`, `methods[]`, access
flags) rather than loading them. Regenerate with:

```sh
scripts/fetch-api-jars.sh
./gradlew -q :core:apiVocabularyReport > docs/API-VOCABULARY.md
```

It exists because `PlatformProfiles.java` tells the model which enum constants
and which `ItemMeta` methods are real on which Paper era, in hand-written prose
that nobody had ever checked against a jar. The **Claims checked** section at
the end is the audit; the tables are the evidence.

## What was measured

| | |
|---|---|
| Versions indexed | 21 |
| Range | 1.20 - 26.2 |
| Classes parsed | 41,778 |
| Constant counted as such | a `public static final` field, which catches enum constants and interface fields alike |

A note on the version list, which is itself a finding. Paper publishes **no
`paper-api` for 1.21.2** — the metadata jumps 1.21.1 to 1.21.3 — so the
"twenty consecutive versions" of the brief are twenty *releases*, not twenty
consecutive patch numbers. A twenty-first, **26.1.1**, exists but only ever got
`-alpha` builds (newest: `26.1.1.build.29-alpha`); it is measured here for
completeness and should not be treated as a supported release.

## Attribute

The single most consequential table in this document: the attribute vocabulary
is what the `paper-legacy` cheat sheet gets wrong.

| Version | kind | constants | `GENERIC_` | `PLAYER_` | `ZOMBIE_` | `HORSE_` | short-form | profile |
|---|---|--:|--:|--:|--:|--:|--:|---|
| 1.20 | enum | 13 | 11 | 0 | 1 | 1 | 0 | legacy |
| 1.20.1 | enum | 13 | 11 | 0 | 1 | 1 | 0 | legacy |
| 1.20.2 | enum | 14 | 12 | 0 | 1 | 1 | 0 | legacy |
| 1.20.3 | enum | 14 | 12 | 0 | 1 | 1 | 0 | legacy |
| 1.20.4 | enum | 14 | 12 | 0 | 1 | 1 | 0 | legacy |
| 1.20.5 | enum | 22 | 18 | 3 | 1 | 0 | 0 | legacy |
| 1.20.6 | enum | 22 | 18 | 3 | 1 | 0 | 0 | legacy |
| 1.21 | enum | 31 | 23 | 7 | 1 | 0 | 0 | legacy |
| 1.21.1 | enum | 31 | 23 | 7 | 1 | 0 | 0 | legacy |
| 1.21.3 | interface | 32 | 0 | 0 | 0 | 0 | 32 | legacy |
| 1.21.4 | interface | 32 | 0 | 0 | 0 | 0 | 32 | legacy |
| 1.21.5 | interface | 32 | 0 | 0 | 0 | 0 | 32 | legacy |
| 1.21.6 | interface | 35 | 0 | 0 | 0 | 0 | 35 | legacy |
| 1.21.7 | interface | 35 | 0 | 0 | 0 | 0 | 35 | modern |
| 1.21.8 | interface | 35 | 0 | 0 | 0 | 0 | 35 | modern |
| 1.21.9 | interface | 35 | 0 | 0 | 0 | 0 | 35 | modern |
| 1.21.10 | interface | 35 | 0 | 0 | 0 | 0 | 35 | modern |
| 1.21.11 | interface | 35 | 0 | 0 | 0 | 0 | 35 | modern |
| 26.1.1 | interface | 35 | 0 | 0 | 0 | 0 | 35 | modern |
| 26.1.2 | interface | 35 | 0 | 0 | 0 | 0 | 35 | modern |
| 26.2 | interface | 40 | 0 | 0 | 0 | 0 | 40 | modern |

The cut is total and it is at **1.21.3**: every constant is prefixed up to
1.21.1, none is from 1.21.3 on. There is no version where both spellings work,
so there is no spelling that compiles across the range the `paper-legacy`
profile serves.

`Attribute` also changed *shape* at 1.21.3, from an `enum` to an `interface`.
That is a second, unremarked break: `switch` over an `Attribute`, `EnumMap`,
`EnumSet` and `Attribute.values()` used as an enum array all behave differently
or stop compiling, and no line of the prompt mentions it.

## ItemMeta data-component setters

The three methods the `paper-legacy` sheet forbids by name, plus the getter
`PlatformInfo` probes at boot.

| Version | `setEnchantmentGlintOverride` | `setItemModel` | `setTooltipStyle` | `hasEnchantmentGlintOverride` | profile |
|---|:-:|:-:|:-:|:-:|---|
| 1.20 | **no** | **no** | **no** | **no** | legacy |
| 1.20.1 | **no** | **no** | **no** | **no** | legacy |
| 1.20.2 | **no** | **no** | **no** | **no** | legacy |
| 1.20.3 | **no** | **no** | **no** | **no** | legacy |
| 1.20.4 | **no** | **no** | **no** | **no** | legacy |
| 1.20.5 | yes | **no** | **no** | yes | legacy |
| 1.20.6 | yes | **no** | **no** | yes | legacy |
| 1.21 | yes | **no** | **no** | yes | legacy |
| 1.21.1 | yes | **no** | **no** | yes | legacy |
| 1.21.3 | yes | yes | yes | yes | legacy |
| 1.21.4 | yes | yes | yes | yes | legacy |
| 1.21.5 | yes | yes | yes | yes | legacy |
| 1.21.6 | yes | yes | yes | yes | legacy |
| 1.21.7 | yes | yes | yes | yes | modern |
| 1.21.8 | yes | yes | yes | yes | modern |
| 1.21.9 | yes | yes | yes | yes | modern |
| 1.21.10 | yes | yes | yes | yes | modern |
| 1.21.11 | yes | yes | yes | yes | modern |
| 26.1.1 | yes | yes | yes | yes | modern |
| 26.1.2 | yes | yes | yes | yes | modern |
| 26.2 | yes | yes | yes | yes | modern |

## Shape and size of the vocabulary types

`enum` versus `interface` matters as much as the constant names: it decides
whether `switch`, `EnumMap` and `values()` mean anything.

| Version | Attribute | Sound | Particle | Enchantment | PotionEffectType | Material | EntityType |
|---|---|---|---|---|---|---|---|
| 1.20 | enum 13 | enum 1474 | enum 101 | abstract class 39 | abstract class 33 | enum 1866 | enum 125 |
| 1.20.1 | enum 13 | enum 1474 | enum 101 | abstract class 39 | abstract class 33 | enum 1866 | enum 125 |
| 1.20.2 | enum 14 | enum 1485 | enum 101 | abstract class 39 | abstract class 33 | enum 1866 | enum 125 |
| 1.20.3 | enum 14 | enum 1539 | enum 107 | abstract class 39 | abstract class 33 | enum 1923 | enum 127 |
| 1.20.4 | enum 14 | enum 1539 | enum 107 | abstract class 39 | abstract class 33 | enum 1923 | enum 127 |
| 1.20.5 | enum 22 | enum 1607 | enum 109 | abstract class 42 | abstract class 39 | enum 1941 | enum 131 |
| 1.20.6 | enum 22 | enum 1607 | enum 109 | abstract class 42 | abstract class 39 | enum 1941 | enum 131 |
| 1.21 | enum 31 | enum 1611 | enum 109 | abstract class 42 | abstract class 39 | enum 1944 | enum 131 |
| 1.21.1 | enum 31 | enum 1611 | enum 109 | abstract class 42 | abstract class 39 | enum 1944 | enum 131 |
| 1.21.3 | interface 32 | interface 1636 | enum 111 | abstract class 42 | abstract class 39 | enum 1989 | enum 151 |
| 1.21.4 | interface 32 | interface 1651 | enum 112 | abstract class 42 | abstract class 39 | enum 2001 | enum 150 |
| 1.21.5 | interface 32 | interface 1702 | enum 114 | abstract class 42 | abstract class 39 | enum 2012 | enum 151 |
| 1.21.6 | interface 35 | interface 1727 | enum 114 | abstract class 42 | abstract class 39 | enum 2031 | enum 152 |
| 1.21.7 | interface 35 | interface 1728 | enum 114 | abstract class 42 | abstract class 39 | enum 2032 | enum 152 |
| 1.21.8 | interface 35 | interface 1728 | enum 114 | abstract class 42 | abstract class 39 | enum 2032 | enum 152 |
| 1.21.9 | interface 35 | interface 1771 | enum 115 | abstract class 42 | abstract class 39 | enum 2105 | enum 154 |
| 1.21.10 | interface 35 | interface 1771 | enum 115 | abstract class 42 | abstract class 39 | enum 2105 | enum 154 |
| 1.21.11 | interface 35 | interface 1838 | enum 115 | abstract class 43 | abstract class 40 | enum 2122 | enum 158 |
| 26.1.1 | interface 35 | interface 1902 | enum 117 | abstract class 43 | abstract class 40 | enum 2124 | enum 158 |
| 26.1.2 | interface 35 | interface 1902 | enum 117 | abstract class 43 | abstract class 40 | enum 2124 | enum 158 |
| 26.2 | interface 40 | interface 1968 | enum 125 | abstract class 43 | abstract class 40 | enum 2155 | enum 159 |

(Each cell is *kind* then *number of `public static final` fields*.)

## Where the vocabulary actually breaks

Adjacent-version diffs of the constant sets. **Removed** is the column that
matters: a name that disappears is a compile error in any mod that used it.
The prompt's era table splits at 1.21.7 (the dialog API). This table shows the
vocabulary does not.

| Step | Attribute +/- | Enchantment +/- | PotionEffectType +/- | Particle +/- |
|---|:-:|:-:|:-:|:-:|
| 1.20 -> 1.20.1 | - | - | - | - |
| 1.20.1 -> 1.20.2 | +1 / **-0** | - | - | - |
| 1.20.2 -> 1.20.3 | - | - | - | +6 / **-0** |
| 1.20.3 -> 1.20.4 | - | - | - | - |
| 1.20.4 -> 1.20.5 | +9 / **-1** | +22 / **-19** | +15 / **-9** | +40 / **-38** |
| 1.20.5 -> 1.20.6 | - | - | - | - |
| 1.20.6 -> 1.21 | +9 / **-0** | - | - | - |
| 1.21 -> 1.21.1 | - | - | - | - |
| 1.21.1 -> 1.21.3 | +32 / **-31** | - | - | +2 / **-0** |
| 1.21.3 -> 1.21.4 | - | - | - | +1 / **-0** |
| 1.21.4 -> 1.21.5 | - | - | - | +2 / **-0** |
| 1.21.5 -> 1.21.6 | +3 / **-0** | - | - | - |
| 1.21.6 -> 1.21.7 | - | - | - | - |
| 1.21.7 -> 1.21.8 | - | - | - | - |
| 1.21.8 -> 1.21.9 | - | - | - | +1 / **-0** |
| 1.21.9 -> 1.21.10 | - | - | - | - |
| 1.21.10 -> 1.21.11 | - | +1 / **-0** | +1 / **-0** | - |
| 1.21.11 -> 26.1.1 | - | - | - | +2 / **-0** |
| 26.1.1 -> 26.1.2 | - | - | - | - |
| 26.1.2 -> 26.2 | +5 / **-0** | - | - | +8 / **-0** |

Steps that remove five or more constants — i.e. the real era boundaries:

- 1.20.4 -> 1.20.5: `Enchantment` loses 19 names (e.g. `ARROW_DAMAGE`, `ARROW_FIRE`, `ARROW_INFINITE`, `ARROW_KNOCKBACK`)
- 1.20.4 -> 1.20.5: `PotionEffectType` loses 9 names (e.g. `CONFUSION`, `DAMAGE_RESISTANCE`, `FAST_DIGGING`, `HARM`)
- 1.20.4 -> 1.20.5: `Particle` loses 38 names (e.g. `BLOCK_CRACK`, `BLOCK_DUST`, `CRIT_MAGIC`, `DRIP_LAVA`)
- 1.21.1 -> 1.21.3: `Attribute` loses 31 names (e.g. `GENERIC_ARMOR`, `GENERIC_ARMOR_TOUGHNESS`, `GENERIC_ATTACK_DAMAGE`, `GENERIC_ATTACK_KNOCKBACK`)

Both boundaries fall **inside** the single `paper-legacy` profile, and neither
is where the profile splits. The larger of the two is the one nobody has
written about: 1.20.4 -> 1.20.5 removes **67** constants across these four types,
against **31** at the 1.21.3 attribute rename that the brief and the master
prompt both single out. The prompt contains no sentence about the 1.20.5
batch at all — and the legacy sheet's only worked enchantment example,
`Enchantment.DURABILITY`, is one of the 19 names it deletes.

Above 1.21.6 nothing is ever removed: the modern range is purely additive, so
a single modern profile is defensible on vocabulary grounds even though it
spans 1.21.7 to 26.2. The legacy range is the opposite.

## The dialog API

| Version | `io.papermc.paper.dialog.Dialog` | `io.papermc.paper.registry.data.dialog.DialogBase` |
|---|:-:|:-:|
| 1.20 | **no** | **no** |
| 1.20.1 | **no** | **no** |
| 1.20.2 | **no** | **no** |
| 1.20.3 | **no** | **no** |
| 1.20.4 | **no** | **no** |
| 1.20.5 | **no** | **no** |
| 1.20.6 | **no** | **no** |
| 1.21 | **no** | **no** |
| 1.21.1 | **no** | **no** |
| 1.21.3 | **no** | **no** |
| 1.21.4 | **no** | **no** |
| 1.21.5 | **no** | **no** |
| 1.21.6 | **no** | **no** |
| 1.21.7 | yes | yes |
| 1.21.8 | yes | yes |
| 1.21.9 | yes | yes |
| 1.21.10 | yes | yes |
| 1.21.11 | yes | yes |
| 26.1.1 | yes | yes |
| 26.1.2 | yes | yes |
| 26.2 | yes | yes |

## Every constant the prompt names, checked against every version

Extracted by regex from `llm/*.java` at generation time, so a constant added to the prompt tomorrow
is audited by tomorrow's report. A `.` means the constant is absent on that
version — i.e. the prompt names a symbol that does not compile there.

| Constant | 1.20 | 1.20.1 | 1.20.2 | 1.20.3 | 1.20.4 | 1.20.5 | 1.20.6 | 1.21 | 1.21.1 | 1.21.3 | 1.21.4 | 1.21.5 | 1.21.6 | 1.21.7 | 1.21.8 | 1.21.9 | 1.21.10 | 1.21.11 | 26.1.1 | 26.1.2 | 26.2 |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| `Attribute.ATTACK_DAMAGE` | . | . | . | . | . | . | . | . | . | x | x | x | x | x | x | x | x | x | x | x | x |
| `Attribute.GENERIC_ARMOR` | x | x | x | x | x | x | x | x | x | . | . | . | . | . | . | . | . | . | . | . | . |
| `Attribute.GENERIC_ATTACK_DAMAGE` | x | x | x | x | x | x | x | x | x | . | . | . | . | . | . | . | . | . | . | . | . |
| `Attribute.GENERIC_MAX_HEALTH` | x | x | x | x | x | x | x | x | x | . | . | . | . | . | . | . | . | . | . | . | . |
| `Attribute.GENERIC_MOVEMENT_SPEED` | x | x | x | x | x | x | x | x | x | . | . | . | . | . | . | . | . | . | . | . | . |
| `Attribute.GENERIC_SCALE` | . | . | . | . | . | x | x | x | x | . | . | . | . | . | . | . | . | . | . | . | . |
| `Attribute.MAX_HEALTH` | . | . | . | . | . | . | . | . | . | x | x | x | x | x | x | x | x | x | x | x | x |
| `Attribute.MOVEMENT_SPEED` | . | . | . | . | . | . | . | . | . | x | x | x | x | x | x | x | x | x | x | x | x |
| `Attribute.SCALE` | . | . | . | . | . | . | . | . | . | x | x | x | x | x | x | x | x | x | x | x | x |
| `Material.DIAMOND_SWORD` | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x |
| `EntityType.CHICKEN` | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x |
| `EntityType.CREEPER` | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x |
| `EntityType.ZOMBIE` | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x |
| `Sound.ENTITY_CHICKEN_AMBIENT` | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x |
| `Sound.ENTITY_PLAYER_LEVELUP` | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x |
| `Particle.CLOUD` | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x |
| `Particle.POOF` | . | . | . | . | . | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x |
| `PotionEffectType.SPEED` | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x | x |
| `Enchantment.DURABILITY` | x | x | x | x | x | . | . | . | . | . | . | . | . | . | . | . | . | . | . | . | . |

Constants the prompt names that are absent on at least one supported version:

- `Attribute.ATTACK_DAMAGE (absent on 9/21)`
- `Attribute.GENERIC_ARMOR (absent on 12/21)`
- `Attribute.GENERIC_ATTACK_DAMAGE (absent on 12/21)`
- `Attribute.GENERIC_MAX_HEALTH (absent on 12/21)`
- `Attribute.GENERIC_MOVEMENT_SPEED (absent on 12/21)`
- `Attribute.GENERIC_SCALE (absent on 17/21)`
- `Attribute.MAX_HEALTH (absent on 9/21)`
- `Attribute.MOVEMENT_SPEED (absent on 9/21)`
- `Attribute.SCALE (absent on 9/21)`
- `Particle.POOF (absent on 5/21)`
- `Enchantment.DURABILITY (absent on 16/21)`

This table does not on its own prove a defect — the prompt names some of these
only inside the era-specific sheet that is never shown on the versions where
they are missing. The claims below say which are real.

## Claims checked

Each claim from the Phase 1a brief, plus every factual assertion in
`PAPER_CHEAT_SHEET_LEGACY` and `PAPER_CHEAT_SHEET_MODERN` that a jar can
settle. Verdicts are computed from the tables above, not typed.

### 1. Paper 1.21.1 has 23 `GENERIC_*` Attribute constants and no short names.

**CONFIRMED**

1.21.1 `Attribute` is an `enum` with 31 constants: 23 `GENERIC_`, 7 `PLAYER_`, 1 `ZOMBIE_`, 0 short-form. The 23 is exact. Worth noting the claim undersells itself: there are 8 further prefixed constants beyond the `GENERIC_` ones, so a repair pass keyed only on `GENERIC_` would miss 8 of the 31 renames.

### 2. Paper 1.21.3 has zero `GENERIC_*` Attribute constants and only short names.

**CONFIRMED**

1.21.3 `Attribute` is an `interface` with 32 constants, 0 of them `GENERIC_`-prefixed and 0 carrying any of the four old prefixes. The rename is complete in one step, and the type changed from `enum` to `interface` in the same release — a break the prompt never mentions.

### 3. The `paper-legacy` profile teaches attribute names that cannot compile on the 1.21.3-1.21.6 versions it serves.

**CONFIRMED**

`paperProfileIdFor` routes every version below 1.21.7 to `paper-legacy`, which is 13 of the 21 measured versions (1.20 - 1.21.6). On 4 of them — 1.21.3, 1.21.4, 1.21.5, 1.21.6 — **all five** of the long names the sheet instructs the model to use (`GENERIC_MAX_HEALTH`, `GENERIC_MOVEMENT_SPEED`, `GENERIC_ATTACK_DAMAGE`, `GENERIC_ARMOR`, `GENERIC_SCALE`) are absent, and the sheet additionally says *"NEVER the short 1.21.3+ forms"* — the only forms that do exist there. The instruction is not merely unhelpful on those versions, it is exactly inverted.

> Not anticipated by the brief: `Attribute.GENERIC_SCALE` — one of the five names the legacy sheet holds up as correct — does not exist on 5 of the prefixed-era versions either (1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4). So the legacy sheet names a non-existent constant on the *old* versions as well as the wrong spelling on the new ones. `SCALE` arrived with the 1.20.5 attribute batch.

### 4. `setEnchantmentGlintOverride` is absent on 1.20-1.20.4 and present on 1.20.5-1.21.6, so the legacy sheet forbids a method the server has on eight of the twelve versions that profile serves.

**CONFIRMED, with a corrected count**

Absent on 5 versions (1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4), present on 8 (1.20.5, 1.20.6, 1.21, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.6). The boundary is exactly where the claim puts it and the **eight** is exactly right. The **twelve** is not: `paper-legacy` serves 13 versions, not 12 — the brief's count appears to have lost one to the missing 1.21.2. So the sheet forbids a present method on 8 of 13.

### 5. `io.papermc.paper.dialog.Dialog` first appears in 1.21.7 and is absent in 1.21.6 and 1.21.5.

**CONFIRMED**

First jar containing the class: **1.21.7**. Absent from 1.21.6 and 1.21.5, and from every jar below them. This also settles the disputed javadoc on `PaperPlatformInfo`: 1.21.6 does not "have the class but not the behaviour" — the class is not in the jar at all, so the master prompt's correction is right and the javadoc is wrong.

### 6. Every other factual assertion in the two cheat sheets

| # | Assertion | Verdict | Evidence |
|---|---|---|---|
| both sheets | the "obviously-real" fallback constant `Material.DIAMOND_SWORD` exists | **CONFIRMED** | present on all 21 versions |
| both sheets | the "obviously-real" fallback constant `EntityType.ZOMBIE` exists | **CONFIRMED** | present on all 21 versions |
| both sheets | the "obviously-real" fallback constant `Sound.ENTITY_PLAYER_LEVELUP` exists | **CONFIRMED** | present on all 21 versions |
| both sheets | the "obviously-real" fallback constant `Particle.CLOUD` exists | **CONFIRMED** | present on all 21 versions |
| modern | `Attribute.MAX_HEALTH` exists on every version the modern sheet serves | **CONFIRMED** | present on all 8 modern versions (1.21.7+) |
| modern | `Attribute.MOVEMENT_SPEED` exists on every version the modern sheet serves | **CONFIRMED** | present on all 8 modern versions (1.21.7+) |
| modern | `Attribute.ATTACK_DAMAGE` exists on every version the modern sheet serves | **CONFIRMED** | present on all 8 modern versions (1.21.7+) |
| modern | `Attribute.SCALE` exists on every version the modern sheet serves | **CONFIRMED** | present on all 8 modern versions (1.21.7+) |
| modern | the `GENERIC_`/`PLAYER_`/`ZOMBIE_` prefixes "were removed" | **CONFIRMED** | zero prefixed constants on all 8 modern versions |
| modern | `ItemMeta#setEnchantmentGlintOverride(Boolean)` is available | **CONFIRMED** | present with the exact `(Boolean)` parameter on all 8 modern versions |
| legacy | "use only enum constants that exist in Paper 1.20.6" is a safe instruction for the whole legacy range | **REFUTED** | the profile also serves 6 versions BELOW 1.20.6 (1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4, 1.20.5). 1.20.6 declares constants that 1.20 does not: 10 `Attribute`, 22 `Enchantment`, 15 `PotionEffectType` constants exist on 1.20.6 and not on 1.20. So a model obeying the instruction to the letter still writes code that fails on the bottom of the range the profile serves |
| legacy | `Attribute.GENERIC_MAX_HEALTH` exists on every version the legacy sheet serves | **REFUTED** | absent on 4/13: 1.21.3, 1.21.4, 1.21.5, 1.21.6 |
| legacy | `Attribute.GENERIC_MOVEMENT_SPEED` exists on every version the legacy sheet serves | **REFUTED** | absent on 4/13: 1.21.3, 1.21.4, 1.21.5, 1.21.6 |
| legacy | `Attribute.GENERIC_ATTACK_DAMAGE` exists on every version the legacy sheet serves | **REFUTED** | absent on 4/13: 1.21.3, 1.21.4, 1.21.5, 1.21.6 |
| legacy | `Attribute.GENERIC_ARMOR` exists on every version the legacy sheet serves | **REFUTED** | absent on 4/13: 1.21.3, 1.21.4, 1.21.5, 1.21.6 |
| legacy | `Attribute.GENERIC_SCALE` exists on every version the legacy sheet serves | **REFUTED** | absent on 9/13: 1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4, 1.21.3, 1.21.4, 1.21.5, 1.21.6 |
| legacy | "`AttributeInstance`/`AttributeModifier` still take a `NamespacedKey` + `AttributeModifier.Operation`" | **REFUTED** | no `AttributeModifier` constructor takes a `NamespacedKey` on 7/13: 1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4, 1.20.5, 1.20.6 (those take a `UUID`); it appears from 1.21 on |
| legacy | `PotionEffectType.SPEED` is a safe constant | **CONFIRMED** | present on all 21 versions |
| legacy | "`Enchantment` constants are still fields on `Enchantment` (e.g. `Enchantment.DURABILITY`)" | **REFUTED (half true)** | `Enchantment` does still expose constants as fields, but `DURABILITY` specifically exists on only 5/21 versions (1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4) and is absent on 8 of the 13 the sheet serves: 1.20.5, 1.20.6, 1.21, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.6. The sheet's own hedge ("era naming may differ") is doing a lot of work; the model is being shown a name that mostly does not compile |
| legacy | "Do NOT call `ItemMeta#setItemModel`" - i.e. the method is absent | **REFUTED** | present on 4/13: 1.21.3, 1.21.4, 1.21.5, 1.21.6 |
| legacy | "Do NOT call `ItemMeta#setTooltipStyle`" - i.e. the method is absent | **REFUTED** | present on 4/13: 1.21.3, 1.21.4, 1.21.5, 1.21.6 |
| legacy | "Do NOT use `Registry`-based lookups ... use the plain constants" | **UNVERIFIABLE (style rule, but its premise holds)** | `org.bukkit.Registry` exists on all 21 versions, so this forbids something real rather than something absent; whether plain constants are preferable is a taste call a jar cannot settle. The premise that plain constants remain available does hold: Sound/Particle/Enchantment expose fields on every version |
| modern | the role line "expert Paper 1.21.8 ... real Paper 1.21 enum constants" is accurate for the servers it is shown to | **REFUTED as a description** | the modern profile is selected for 8 versions up to 26.2. On 26.2 `Sound` has 1968 constants against 1728 on 1.21.8, and `Material` 2155 against 2032. Calling a 26.2 server "Paper 1.21.8" is a factual misdescription; whether it costs compile rate is a generation question this jar-level measurement cannot answer |

## Found, and not anticipated by the brief

1. **`Attribute` changes kind, not just spelling.** It is an `enum` up to
   1.21.1 and an `interface` from 1.21.3. Any generated mod using `switch`,
   `EnumMap<Attribute,…>`, `EnumSet` or `Attribute.valueOf` in an enum-typed
   context breaks across that line independently of the rename, and a repair
   pass that only rewrites names will not fix it.
2. **A `GENERIC_`-only repair rule is incomplete.** 8 of 1.21.1's 31 constants carry `PLAYER_` or `ZOMBIE_` instead. Both directions of the mapping need all four prefixes.
3. **At the 1.21.3 boundary the rename IS a clean prefix strip — but only
   there.** Stripping the prefix from 1.21.1's 31 constants yields 31 names; 1.21.3 has 32. Names 1.21.3 adds outright: `TEMPT_RANGE`. Names that vanish rather than being renamed: none.
   So at THIS boundary a prefix-stripping regex would in fact be correct. It is
   not correct in general: the set keeps drifting afterwards (1.21.6 and 26.2
   each add more), and the 1.20.5 boundary below is not a prefix rule at all.
   A repair pass must be a lookup against the measured set.
4. **There is a SECOND vocabulary boundary, at 1.20.5, and nothing in the
   prompt knows about it.** `Enchantment` loses 19 of 39 constants and gains 22; `Attribute` loses 1 of 14 constants and gains 9; `PotionEffectType` loses 9 of 33 constants and gains 15; 
   Bukkit's legacy `Enchantment` spellings are replaced wholesale by the
   vanilla ones (`DURABILITY`->`UNBREAKING`, `DIG_SPEED`->`EFFICIENCY`,
   `PROTECTION_ENVIRONMENTAL`->`PROTECTION`, `LOOT_BONUS_MOBS`->`LOOTING`), and
   `Attribute.HORSE_JUMP_STRENGTH` becomes `GENERIC_JUMP_STRENGTH`. That is 19
   enchantment names that stop compiling in one step, INSIDE the single
   `paper-legacy` profile - and the legacy sheet's one worked enchantment example,
   `Enchantment.DURABILITY`, is on the losing side of it.

5. **Paper never published a `paper-api` for 1.21.2**, and **26.1.1 exists
   only as `-alpha` builds**. Any "every supported version" loop must derive
   its list from the metadata rather than counting patch numbers.
6. **`ItemMeta` is an interface with 0 constants and ~100 methods** on every
   version, so `constants("ItemMeta")` is legitimately empty — exactly the
   case where `ApiVocabulary.constants()` returning an empty set must not be
   read as "unknown type". That is why the interface has `declaresConstant`.
