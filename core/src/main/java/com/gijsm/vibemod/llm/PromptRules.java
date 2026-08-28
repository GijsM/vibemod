package com.gijsm.vibemod.llm;

import java.util.List;

/**
 * The rule tables: era- and capability-specific guidance as
 * {@code (predicate, text)} pairs evaluated against the running server, in place
 * of one hand-written cheat sheet per era.
 *
 * <h2>Why the Paper table is ONE list, not two</h2>
 *
 * <p>{@link #PAPER} is shared by {@code paper-modern} and {@code paper-legacy}.
 * That is the point of the rework rather than an oversight: the vocabulary
 * boundaries are at 1.20.5, 1.21 and 1.21.3, and the profile split is at 1.21.7
 * (where the dialog UI arrives, which is a host concern and not an API one).
 * Two eras cannot express three boundaries, so nothing is gained by keeping two
 * lists and a great deal is lost — every measured defect in
 * docs/API-VOCABULARY.md is an instance of a two-era table being asked a
 * question with more than two answers. The profiles still differ in the text
 * that genuinely has no probe behind it: the display range in the role line.
 *
 * <h2>Probe-predicated, not version-predicated</h2>
 *
 * <p>Every rule below keys off {@link PromptFacts}, i.e. off what the server's
 * own classpath declares. Not one reads a version number. Where a rule pair
 * splits an era — enchantments, potion effects, particles, the
 * {@code AttributeModifier} constructor, the {@code ItemMeta} data-component
 * setters — the split is a single measured symbol whose flip point was verified
 * against all 21 cached {@code paper-api} jars, and the two halves are mutually
 * exclusive by construction because each one's {@code requiresSymbols} is the
 * other's {@code forbidsSymbols}.
 */
public final class PromptRules {

    private PromptRules() {
    }

    // ------------------------------------------------------------------
    // Paper
    // ------------------------------------------------------------------

    /**
     * The four fallback constants the model is told to retreat to when unsure.
     * Measured present on all 21 supported versions, so the predicate is
     * "not known absent" rather than "known present": on a host that measured
     * nothing, having the fallback advice is better than having none.
     */
    private static final PromptRule PAPER_BIG_ENUMS = new PromptRule(
            "paper.constants.big-enums",
            facts -> facts.notAbsent("Material.DIAMOND_SWORD")
                    && facts.notAbsent("EntityType.ZOMBIE")
                    && facts.notAbsent("Sound.ENTITY_PLAYER_LEVELUP")
                    && facts.notAbsent("Particle.CLOUD"),
            """
            - `Material`, `EntityType`, `Sound` and `Particle` declare hundreds to thousands
              of constants each and the names differ between Minecraft versions, so they are
              NOT listed below. Use only names you are certain exist on THIS server and never
              invent one. When unsure, retreat to a very common, obviously-real constant
              (`Material.DIAMOND_SWORD`, `EntityType.ZOMBIE`, `Sound.ENTITY_PLAYER_LEVELUP`,
              `Particle.CLOUD`) rather than guessing.""",
            List.of("Material.DIAMOND_SWORD", "EntityType.ZOMBIE",
                    "Sound.ENTITY_PLAYER_LEVELUP", "Particle.CLOUD"),
            List.of());

    /**
     * {@code Attribute} is an {@code enum} up to 1.21.1 and an {@code interface}
     * from 1.21.3 — a break independent of the renaming, and one no line of the
     * old prompt mentioned. Stated unconditionally rather than probed, because
     * the advice it gives (an if/else chain and a {@code HashMap}) is correct on
     * BOTH shapes; a probe would only let us offer {@code switch} on the older
     * half, which is not worth a rule that can be wrong.
     */
    private static final PromptRule PAPER_ATTRIBUTE_SHAPE = PromptRule.always(
            "paper.attribute.shape",
            """
            - Never `switch` over an `Attribute`, and never put one in an `EnumMap` or an
              `EnumSet`: `Attribute` is an enum on some supported Paper versions and an
              interface on others. An `if`/`else` chain and a `HashMap` compile on every
              version.""");

    /**
     * The {@code NamespacedKey} constructor era. Probed via
     * {@code AttributeModifier#getKey}, which is declared from 1.21 on and
     * absent below it — the same boundary as the constructor, and a name-only
     * question {@link com.gijsm.vibemod.platform.ApiVocabulary} can answer,
     * which a constructor signature is not.
     */
    private static final PromptRule PAPER_ATTRIBUTE_MODIFIER_KEY = new PromptRule(
            "paper.attribute.modifier.key",
            facts -> facts.declares("AttributeModifier#getKey"),
            """
            - An `AttributeModifier` is built here from a `NamespacedKey`, an amount and an
              `Operation`. Prefer `AttributeInstance#setBaseValue(...)` where it will do: it is
              spelled the same on every supported version.""",
            List.of("AttributeModifier#getKey", "AttributeInstance#setBaseValue"),
            List.of());

    /** The pre-1.21 {@code UUID} constructor era; mirror of the rule above. */
    private static final PromptRule PAPER_ATTRIBUTE_MODIFIER_UUID = new PromptRule(
            "paper.attribute.modifier.uuid",
            facts -> facts.lacks("AttributeModifier#getKey"),
            """
            - An `AttributeModifier` is built here from a `UUID`, a name, an amount and an
              `Operation`. The `NamespacedKey` constructor does NOT exist on this server. Prefer
              `AttributeInstance#setBaseValue(...)` where it will do: it is spelled the same on
              every supported version.""",
            List.of("AttributeInstance#setBaseValue"),
            List.of("AttributeModifier#getKey"));

    /**
     * The glint override, present from 1.20.5. This is the rule the old sheet
     * got exactly backwards: it forbade the method on all 13 versions
     * {@code paper-legacy} serves, 8 of which have it.
     */
    private static final PromptRule PAPER_GLINT_YES = new PromptRule(
            "paper.itemmeta.glint.yes",
            facts -> facts.declares("ItemMeta#setEnchantmentGlintOverride"),
            """
            - `ItemMeta#setEnchantmentGlintOverride(Boolean)` IS available on this server: use
              it to make an item shine without giving it a real enchantment.""",
            List.of("ItemMeta#setEnchantmentGlintOverride"),
            List.of());

    private static final PromptRule PAPER_GLINT_NO = new PromptRule(
            "paper.itemmeta.glint.no",
            facts -> facts.lacks("ItemMeta#setEnchantmentGlintOverride"),
            """
            - `ItemMeta#setEnchantmentGlintOverride(...)` does NOT exist on this server. For a
              "shiny" item, apply a real enchantment with `ItemMeta#addEnchant` and hide it
              with `ItemFlag.HIDE_ENCHANTS`, or skip the effect.""",
            List.of("ItemMeta#addEnchant", "ItemFlag.HIDE_ENCHANTS"),
            List.of("ItemMeta#setEnchantmentGlintOverride"));

    /** The 1.21.3 data-component setters. Measured to arrive together. */
    private static final PromptRule PAPER_ITEM_MODEL_YES = new PromptRule(
            "paper.itemmeta.model.yes",
            facts -> facts.declares("ItemMeta#setItemModel")
                    && facts.declares("ItemMeta#setTooltipStyle"),
            """
            - `ItemMeta#setItemModel(...)` and `ItemMeta#setTooltipStyle(...)` are available on
              this server.""",
            List.of("ItemMeta#setItemModel", "ItemMeta#setTooltipStyle"),
            List.of());

    private static final PromptRule PAPER_ITEM_MODEL_NO = new PromptRule(
            "paper.itemmeta.model.no",
            facts -> facts.lacks("ItemMeta#setItemModel")
                    && facts.lacks("ItemMeta#setTooltipStyle"),
            """
            - Do NOT call `ItemMeta#setItemModel(...)` or `ItemMeta#setTooltipStyle(...)`:
              neither exists on this server.""",
            List.of(),
            List.of("ItemMeta#setItemModel", "ItemMeta#setTooltipStyle"));

    /**
     * The 1.20.5 boundary, the larger of the two and the one no prose ever
     * mentioned: 1.20.4 -> 1.20.5 replaces Bukkit's legacy {@code Enchantment}
     * spellings with the vanilla ones wholesale, deleting 19 names in one step.
     * The old sheet's single worked enchantment example, {@code DURABILITY}, is
     * on the losing side of it and exists on only 5 of 21 versions.
     */
    private static final PromptRule PAPER_ENCHANTMENT_LEGACY = new PromptRule(
            "paper.enchantment.legacy",
            facts -> facts.declares("Enchantment.DURABILITY"),
            """
            - This server still uses Bukkit's OLD enchantment spellings, not the vanilla ones:
              `Enchantment.DURABILITY` (not `UNBREAKING`), `Enchantment.DIG_SPEED` (not
              `EFFICIENCY`), `Enchantment.PROTECTION_ENVIRONMENTAL` (not `PROTECTION`),
              `Enchantment.LOOT_BONUS_MOBS` (not `LOOTING`). The exhaustive list is below.""",
            List.of("Enchantment.DURABILITY", "Enchantment.DIG_SPEED",
                    "Enchantment.PROTECTION_ENVIRONMENTAL", "Enchantment.LOOT_BONUS_MOBS"),
            List.of("Enchantment.UNBREAKING", "Enchantment.EFFICIENCY",
                    "Enchantment.PROTECTION", "Enchantment.LOOTING"));

    private static final PromptRule PAPER_ENCHANTMENT_VANILLA = new PromptRule(
            "paper.enchantment.vanilla",
            facts -> facts.lacks("Enchantment.DURABILITY"),
            """
            - This server uses the VANILLA enchantment spellings: `Enchantment.UNBREAKING`,
              `Enchantment.EFFICIENCY`, `Enchantment.PROTECTION`, `Enchantment.LOOTING`.
              Bukkit's old names (`DURABILITY`, `DIG_SPEED`, `PROTECTION_ENVIRONMENTAL`,
              `LOOT_BONUS_MOBS`) are gone. The exhaustive list is below.""",
            List.of("Enchantment.UNBREAKING", "Enchantment.EFFICIENCY",
                    "Enchantment.PROTECTION", "Enchantment.LOOTING"),
            List.of("Enchantment.DURABILITY", "Enchantment.DIG_SPEED",
                    "Enchantment.PROTECTION_ENVIRONMENTAL", "Enchantment.LOOT_BONUS_MOBS"));

    /** Same 1.20.5 boundary, {@code PotionEffectType}: 9 names deleted in one step. */
    private static final PromptRule PAPER_POTION_LEGACY = new PromptRule(
            "paper.potion.legacy",
            facts -> facts.declares("PotionEffectType.CONFUSION"),
            """
            - Potion effects use the OLD Bukkit spellings here: `PotionEffectType.CONFUSION`
              (not `NAUSEA`), `DAMAGE_RESISTANCE` (not `RESISTANCE`), `FAST_DIGGING` (not
              `HASTE`), `INCREASE_DAMAGE` (not `STRENGTH`), `JUMP` (not `JUMP_BOOST`), `SLOW`
              (not `SLOWNESS`). The exhaustive list is below.""",
            List.of("PotionEffectType.CONFUSION", "PotionEffectType.DAMAGE_RESISTANCE",
                    "PotionEffectType.FAST_DIGGING", "PotionEffectType.INCREASE_DAMAGE",
                    "PotionEffectType.JUMP", "PotionEffectType.SLOW"),
            List.of("PotionEffectType.NAUSEA", "PotionEffectType.RESISTANCE",
                    "PotionEffectType.HASTE", "PotionEffectType.STRENGTH",
                    "PotionEffectType.JUMP_BOOST", "PotionEffectType.SLOWNESS"));

    private static final PromptRule PAPER_POTION_VANILLA = new PromptRule(
            "paper.potion.vanilla",
            facts -> facts.lacks("PotionEffectType.CONFUSION"),
            """
            - Potion effects use the VANILLA spellings here: `PotionEffectType.NAUSEA`,
              `RESISTANCE`, `HASTE`, `STRENGTH`, `JUMP_BOOST`, `SLOWNESS`. The old Bukkit
              names (`CONFUSION`, `DAMAGE_RESISTANCE`, `FAST_DIGGING`, `INCREASE_DAMAGE`,
              `JUMP`, `SLOW`) are gone. The exhaustive list is below.""",
            List.of("PotionEffectType.NAUSEA", "PotionEffectType.RESISTANCE",
                    "PotionEffectType.HASTE", "PotionEffectType.STRENGTH",
                    "PotionEffectType.JUMP_BOOST", "PotionEffectType.SLOWNESS"),
            List.of("PotionEffectType.CONFUSION", "PotionEffectType.DAMAGE_RESISTANCE",
                    "PotionEffectType.FAST_DIGGING", "PotionEffectType.INCREASE_DAMAGE",
                    "PotionEffectType.JUMP", "PotionEffectType.SLOW"));

    /**
     * Same 1.20.5 boundary, {@code Particle}: 38 names deleted in one step, the
     * single largest removal anywhere in the supported range. Particle is too
     * large to dump, so this rule carries the renames the model is most likely
     * to reach for, plus three spellings that never changed.
     */
    private static final PromptRule PAPER_PARTICLE_LEGACY = new PromptRule(
            "paper.particle.legacy",
            facts -> facts.declares("Particle.BLOCK_CRACK"),
            """
            - Particles use the OLD Bukkit spellings here: `Particle.BLOCK_CRACK` (not
              `BLOCK`), `CRIT_MAGIC` (not `ENCHANTED_HIT`), `EXPLOSION_NORMAL` (not `POOF`),
              `SMOKE_NORMAL` (not `SMOKE`), `VILLAGER_HAPPY` (not `HAPPY_VILLAGER`).
              `Particle.CLOUD`, `Particle.FLAME` and `Particle.HEART` are spelled the same on
              every version and are the safest choices.""",
            List.of("Particle.BLOCK_CRACK", "Particle.CRIT_MAGIC", "Particle.EXPLOSION_NORMAL",
                    "Particle.SMOKE_NORMAL", "Particle.VILLAGER_HAPPY",
                    "Particle.CLOUD", "Particle.FLAME", "Particle.HEART"),
            List.of("Particle.BLOCK", "Particle.ENCHANTED_HIT", "Particle.POOF",
                    "Particle.SMOKE", "Particle.HAPPY_VILLAGER"));

    private static final PromptRule PAPER_PARTICLE_VANILLA = new PromptRule(
            "paper.particle.vanilla",
            facts -> facts.lacks("Particle.BLOCK_CRACK"),
            """
            - Particles use the VANILLA spellings here: `Particle.BLOCK`, `ENCHANTED_HIT`,
              `POOF`, `SMOKE`, `HAPPY_VILLAGER`. The old Bukkit names (`BLOCK_CRACK`,
              `CRIT_MAGIC`, `EXPLOSION_NORMAL`, `SMOKE_NORMAL`, `VILLAGER_HAPPY`) are gone.
              `Particle.CLOUD`, `Particle.FLAME` and `Particle.HEART` are spelled the same on
              every version and are the safest choices.""",
            List.of("Particle.BLOCK", "Particle.ENCHANTED_HIT", "Particle.POOF",
                    "Particle.SMOKE", "Particle.HAPPY_VILLAGER",
                    "Particle.CLOUD", "Particle.FLAME", "Particle.HEART"),
            List.of("Particle.BLOCK_CRACK", "Particle.CRIT_MAGIC", "Particle.EXPLOSION_NORMAL",
                    "Particle.SMOKE_NORMAL", "Particle.VILLAGER_HAPPY"));

    /**
     * A style rule, kept from the old sheet. Its premise was checked and holds:
     * {@code Registry} exists on all 21 versions, so this forbids something real
     * rather than something absent. Predicated on the type being present so it
     * cannot tell a future platform not to use a class it does not have.
     */
    private static final PromptRule PAPER_NO_REGISTRY_LOOKUPS = new PromptRule(
            "paper.registry.no-lookups",
            facts -> facts.notAbsent("Registry"),
            """
            - Do NOT look `Sound`, `Particle`, `Enchantment` or `PotionEffectType` up through
              `Registry` or by building a `NamespacedKey`; use the plain constants.""",
            List.of(),
            List.of());

    // ------------------------------------------------------------------
    // Threading
    // ------------------------------------------------------------------
    //
    // This pair used to be one fixed string on PlatformProfile
    // (PlatformProfiles.PAPER_THREADING), shared by both Paper profiles. That
    // was safe only while every Paper-shaped server was single-threaded, and it
    // stopped being safe the moment Folia was supported: Folia is selected by
    // BOTH Paper profiles (it exists at 1.20.6 and at 26.2), so no profile split
    // can express the difference — exactly the situation this rule table is for.
    //
    // The stakes are higher than the other pairs here. A wrong glint rule costs
    // a self-heal round. A wrong threading rule produces a mod that compiles,
    // loads, passes its first test and then corrupts state under load, because
    // the model was told it had a guarantee it does not have. The old sentence
    // is FALSE on Folia, and false in the silent direction.

    private static final PromptRule PAPER_THREADING_MAIN = new PromptRule(
            "paper.threading.main",
            facts -> !facts.regionised(),
            """
            - Event handler methods and Runnables passed to ctx.repeat/ctx.later already run
              on the main server thread — do not spawn your own threads and do not attempt to
              hop threads yourself.""",
            List.of(),
            List.of());

    /**
     * Folia and any fork that adopts its regionised threading.
     *
     * <p>What this text says is deliberately narrower than "Folia is supported".
     * The host pins every scheduled task and every command handler to the global
     * region thread, so those two are genuinely single-threaded. Event handlers
     * are not and cannot be: Folia delivers them on the region thread that owns
     * the subject, and hopping them to the global region would arrive a tick late
     * and break {@code setCancelled}. So the honest contract is "two domains, and
     * here is which is which" — not a promise of one thread that the host cannot
     * keep.
     */
    private static final PromptRule PAPER_THREADING_REGIONISED = new PromptRule(
            "paper.threading.regionised",
            PromptFacts::regionised,
            """
            - THIS SERVER IS NOT SINGLE-THREADED. It ticks the world as several regions in
              parallel, so your callbacks do NOT all run on one thread:
              - Runnables passed to `ctx.repeat(...)` / `ctx.later(...)` run on the global
                region thread.
              - Event handler methods run on whichever region thread owns the entity or chunk
                the event is about. That is a DIFFERENT thread from the one above, and your
                handler can be running in several regions at the same moment.
            - Therefore: any state shared between an event handler and a scheduled task MUST be
              thread-safe. Use `ConcurrentHashMap`, never a plain `HashMap`, for per-player
              maps; use `AtomicInteger` / `AtomicLong` instead of a plain counter field. This
              overrides the usual advice about plain `HashMap` fields.
            - Do the work where the object lives. An event handler may freely touch the player,
              entity or block its event is about. A `ctx.repeat` / `ctx.later` task runs on the
              global region and must NOT assume it can touch an arbitrary player, entity or
              block — this server rejects touching something another region owns. Prefer doing
              world changes in the event handler that handed you the object, and keep scheduled
              tasks to bookkeeping and to work on things you have re-checked are still valid.
            - Still never spawn your own threads, and never hop threads yourself.""",
            List.of(),
            List.of());

    /**
     * Paper's table. Order is the emission order, so the pairs sit where their
     * subject does and a diff between two versions' prompts stays readable.
     */
    public static final List<PromptRule> PAPER = List.of(
            PAPER_BIG_ENUMS,
            PAPER_ATTRIBUTE_SHAPE,
            PAPER_ATTRIBUTE_MODIFIER_KEY,
            PAPER_ATTRIBUTE_MODIFIER_UUID,
            PAPER_ENCHANTMENT_LEGACY,
            PAPER_ENCHANTMENT_VANILLA,
            PAPER_POTION_LEGACY,
            PAPER_POTION_VANILLA,
            PAPER_PARTICLE_LEGACY,
            PAPER_PARTICLE_VANILLA,
            PAPER_GLINT_YES,
            PAPER_GLINT_NO,
            PAPER_ITEM_MODEL_YES,
            PAPER_ITEM_MODEL_NO,
            PAPER_NO_REGISTRY_LOOKUPS);

    /**
     * Paper rules whose firing is a property of the RUNNING HOST, not of any
     * {@code paper-api} jar — and which therefore must not sit in {@link #PAPER}.
     *
     * <p>{@code PromptSymbolGate} measures the prompt against every supported
     * version's jar, and one of its checks ({@code checkNoDeadRules}) fails any
     * rule in {@link #PAPER} that fires on none of them. That check rests on an
     * assumption which holds for all fifteen jar-decidable rules and fails for
     * these two: that whether a rule fires can be decided by reading a jar.
     * "Is this server regionised?" cannot. Folia ships the same
     * {@code paper-api} surface as Paper — the entire
     * {@code io.papermc.paper.threadedregions.scheduler} package is in ordinary
     * {@code paper-api}, verified with {@code javap} — so no jar the gate can
     * open distinguishes the two. {@link PromptFacts#regionised()} reads a boot
     * probe on the live host, and the gate's stub host is not one.
     *
     * <p>These rules are NOT unchecked. The gate's other two checks iterate
     * {@code facts.profile().rules()}, which is {@link #PAPER_PROFILE} and
     * includes them, so their symbol claims are still measured on all 21
     * versions. Only the dead-rule check — the one that cannot decide them — does
     * not see them.
     *
     * <p><strong>Follow-up owed:</strong> the right long-term fix is for the gate
     * to run each version twice, once with a regionised stub host, at which point
     * these belong back in {@link #PAPER}. That is a change to
     * {@code core/src/test/java/symbols/}, which this work was explicitly scoped
     * out of.
     */
    public static final List<PromptRule> PAPER_HOST_PREDICATED = List.of(
            PAPER_THREADING_MAIN,
            PAPER_THREADING_REGIONISED);

    /**
     * What a Paper profile actually carries: the jar-decidable table followed by
     * the host-predicated one. Threading lands last, next to the defensive-coding
     * guidance it qualifies.
     */
    public static final List<PromptRule> PAPER_PROFILE =
            java.util.stream.Stream.concat(PAPER.stream(), PAPER_HOST_PREDICATED.stream())
                    .toList();

    // ------------------------------------------------------------------
    // Fabric / NeoForge
    // ------------------------------------------------------------------
    //
    // Every loader rule is unconditional, and that is a property of the design
    // rather than laziness: the sdk mod flavor is loader-NEUTRAL, so there is no
    // loader-dependent sentence to predicate. The symbol lists are populated
    // honestly all the same. They name net.minecraft types, which no paper-api
    // jar contains, so the offline gate measures them as UNKNOWN and — per the
    // tri-state rule — leaves them alone rather than reporting them absent.
    // ------------------------------------------------------------------

    private static final PromptRule LOADER_HOOKS = PromptRule.always(
            "loader.hooks.surface",
            """
            - THE ctx.on* HOOKS ARE THE ENTIRE EVENT SURFACE. There is no listener object
              and no event bus. They are: onPlayerJoin, onPlayerQuit, onServerTick, onChat,
              onBlockBreak, onUseBlock, onUseItem, onEntityDeath, onPlayerDeath, onRespawn.
              If a request needs an event that is not on that list, build the closest
              tasteful approximation out of the ones that are (onServerTick can poll for a
              great deal) rather than inventing a hook.
            - The cancelling hooks (onChat, onBlockBreak, onUseBlock, onUseItem) return
              `boolean`: return `true` to let it happen, `false` to cancel. There is no
              `setCancelled`.""");

    private static final PromptRule LOADER_RENAMES = PromptRule.always(
            "loader.mc26.renames",
            """
            - MC 26.x renamed things you may remember differently:
              * `ResourceLocation` is now `net.minecraft.resources.Identifier`
                (`Identifier.withDefaultNamespace("stone")`,
                `Identifier.fromNamespaceAndPath("minecraft", "stone")`).
              * Text is `net.minecraft.network.chat.Component`: `Component.literal("hi")`,
                `Component.translatable("key")`. Send it with
                `player.sendSystemMessage(component)` or, in a command,
                `src.sendSuccess(() -> component, false)`.
              * A command's source is `CommandSourceStack`: `src.getPlayer()` (null for the
                console), `src.getServer()`, `src.sendSystemMessage(...)`.
              * Permission levels are gone from CommandSourceStack; do not check permissions
                yourself, the host already did.""",
            List.of("Identifier#withDefaultNamespace", "Identifier#fromNamespaceAndPath",
                    "Component#literal", "Component#translatable",
                    "CommandSourceStack#getPlayer", "CommandSourceStack#getServer"),
            List.of());

    private static final PromptRule LOADER_CLIENT = PromptRule.always(
            "loader.client.surface",
            """
            - Client features go inside `ctx.client(c -> { ... })` and NOWHERE else. That
              block does not run at all on a dedicated server, so a mod with client
              features still works there - it just has no HUD. Never import
              `net.minecraft.client.*` yourself: use the ClientContext getters shown below,
              which are the same on every version.
            - `c.hud(id, (canvas, tickDelta) -> ...)` draws with the HudCanvas shown below.
              Keep it cheap: it runs every frame and the host times it.
            - `c.key(label, "G", () -> ...)` leases one of 8 shared key slots. There are 8
              in total across ALL loaded mods, so lease one only if the mod is really about
              a key.
            - If your mod uses `ctx.client(...)`, say so in the manual: "This is a client
              feature: it draws on your own screen and does nothing on a dedicated server.\"""");

    /** The loader table, shared verbatim by Fabric and NeoForge (ARCHITECTURE-V2 §6.2). */
    public static final List<PromptRule> LOADER = List.of(
            LOADER_HOOKS,
            LOADER_RENAMES,
            LOADER_CLIENT);

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * The applicable rules' text, one per line, in table order.
     *
     * <p>This is where the invariant is enforced: see
     * {@link PromptRule#appliesTo}. Returns {@code ""} when nothing applies, so
     * a caller can decide whether to emit a heading.
     */
    public static String render(List<PromptRule> rules, PromptFacts facts) {
        StringBuilder sb = new StringBuilder();
        for (PromptRule rule : rules) {
            if (!rule.appliesTo(facts)) {
                continue;
            }
            sb.append(rule.text());
            if (!rule.text().endsWith("\n")) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** The ids of the rules that apply — for self-tests, the gate and the boot log. */
    public static List<String> applicableIds(List<PromptRule> rules, PromptFacts facts) {
        List<String> ids = new java.util.ArrayList<>();
        for (PromptRule rule : rules) {
            if (rule.appliesTo(facts)) {
                ids.add(rule.id());
            }
        }
        return List.copyOf(ids);
    }
}
