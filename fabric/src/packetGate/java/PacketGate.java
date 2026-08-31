import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.gijsm.vibemod.fabric.project.ProjectedPackets;

/**
 * The V4 Phase 4 packet-completeness gate: every game packet that can carry
 * VibeMod content must have a row in {@link ProjectedPackets}, and every row
 * must still have a packet.
 *
 * <p>Lane B is a projection of a live protocol onto a client that does not know
 * about half of it. The failure mode of such a projection is not a crash, it is
 * one player holding a piece of paper — and the way that arrives is a game
 * update adding a packet nobody thought to look at. This turns "did we miss
 * one" from a bug report into a build failure, in both directions: an
 * undeclared packet that reaches content fails, and a declared row whose packet
 * no longer reaches anything fails too, so the table cannot rot into fiction.
 *
 * <h2>Why the data shape and not the codec</h2>
 *
 * <p>The plan says "walk each {@code StreamCodec}/record component
 * transitively". Only half of that is reachable: a {@code StreamCodec} on a
 * 26.2 packet is a {@code static final} built from
 * {@code StreamCodec.composite(...)} with method references, so at runtime it
 * is an {@code invokedynamic}-minted lambda whose captured components are
 * {@code Object}-typed synthetic fields. Reflection over it yields nothing —
 * it does not even know it encodes an {@code ItemStack}. The record components
 * and instance fields, on the other hand, carry full generic signatures in the
 * classfile, and they are exactly what the codec composes over. Walking the
 * data shape IS the transitive walk; it is the version of this that actually
 * works, and it is strictly more conservative, because a field with no codec
 * still gets declared rather than silently skipped.
 *
 * <h2>What counts as content</h2>
 *
 * <p>Seven sentinels, six of them class-assignability and one of them a shape:
 * {@code Holder<Item>} is content, {@code Holder<SoundEvent>} is not, so it is
 * matched on the parameterisation rather than on the raw type.
 * {@code ItemStackTemplate} is folded into {@code ItemStack} because it is the
 * same thing wearing the {@code HoverEvent.ShowItem} hat.
 *
 * <h2>Where the walk stops, and where it admits it stopped</h2>
 *
 * <p>Machinery ({@code StreamCodec}, {@code PacketType},
 * {@code Codec}/{@code MapCodec}) and foreign packages ({@code java.*},
 * fastutil, netty, …) are not walked, but their generic ARGUMENTS still are —
 * {@code List<ItemStack>}, {@code Optional<Component>},
 * {@code Int2ObjectMap<HashedStack>} all get followed, which is where the
 * content in this protocol actually lives. A sealed {@code net.minecraft}
 * interface is walked through every permitted subclass, transitively. A
 * NON-sealed one cannot be enumerated at all, and that is reported as an
 * {@code OPAQUE BOUNDARY} rather than dropped: those are precisely the holes
 * where the next game version can hide a new {@code ItemStack} without this
 * gate noticing, so they are printed by name.
 *
 * <h2>The registry cut, which is what makes this a walk and not a heap dump</h2>
 *
 * <p>A field walk over a game object graph does not terminate anywhere useful
 * on its own. Written without this cut it reports, in full seriousness, that
 * {@code ClientboundAddEntityPacket} reaches {@code ParticleOptions} — via
 * {@code EntityType.lootTable -> LootTable -> LootContext -> ServerLevel ->
 * MinecraftServer -> DedicatedServer -> ManagementServer}, sixteen hops into
 * the JSON-RPC server. Every one of those hops is a real Java reference and not
 * one of them is on the wire.
 *
 * <p>What is on the wire is the cut. <b>A built-in registry entry is sent as an
 * id.</b> Its own fields are never serialised, so the walk stops at it, and
 * whether the id itself is content is the sentinel list's question, not the
 * walk's — which is why {@code EntityType} is a stop that scores and
 * {@code BlockEntityType} is a stop that does not. The set is not a
 * hand-written list that could rot: it is read out of
 * {@code net.minecraft.core.registries.BuiltInRegistries} itself, whose every
 * field is a {@code Registry<X>} and whose {@code X} is exactly that set, on
 * whatever version is on the classpath. See
 * {@link #registryElementTypes()} for why it is that class and not
 * {@code Registries} — the difference is a real bug this gate had.
 *
 * <p>The same cut applies to the wrappers that hold a registry entry BY id —
 * {@code Holder}, {@code HolderSet}, {@code ResourceKey}, {@code TagKey},
 * {@code Registry}. Their type arguments are not followed either, because
 * {@code ResourceKey<Level>} is two {@code Identifier}s on the wire and
 * following its argument is how a walk ends up inside {@code MinecraftServer}.
 * {@code Holder<Item>} is checked before the stop, because that parameterisation
 * IS one of the seven sentinels.
 *
 * <p>And the same cut applies to live world state — {@link #LIVE}, four roots
 * that are never serialised by anything at any depth.
 *
 * <p>There is a second kind, and it is the one that bites hardest. An
 * <b>encoding boundary</b> is a type that is perfectly well known and says
 * nothing: an NBT {@code Tag} and a raw {@code byte[]}.
 * {@code ClientboundLevelChunkWithLightPacket} carries every blockstate in the
 * chunk inside a {@code byte[]}, and a lectern's book is a {@code CompoundTag}.
 * Reporting "reaches nothing" for those is true of the type and false of the
 * content. {@code String} is deliberately not one — a {@code String} is a
 * {@code String}.
 *
 * <p><b>Crossing either kind REQUIRES a rule, exactly as reaching a sentinel
 * does.</b> That is not bookkeeping, it is the correction of a real inversion:
 * with the requirement keyed on sentinels alone, this gate demanded the
 * deletion of the rules covering the only two packets in the protocol that hide
 * an {@code ItemStack} behind erasure — {@code ClientboundSetEntityDataPacket},
 * where the stack sits in {@code DataValue<T>.value}, and
 * {@code ServerboundContainerClickPacket}, where it sits behind
 * {@code HashedStack}. A boundary is by definition the place where content
 * could be and the walk would not know, so it is where a human's declaration is
 * worth the most, not the least. The reverse check follows the same rule: a row
 * is stale only when its packet reaches <em>neither</em>.
 *
 * <p>Plain {@code main()}, no test framework, {@code System.exit(1)} on
 * failure — the same shape as {@code SurgeonSelfTest}, and its own source set
 * for the same reason: it needs {@code main}'s output (for
 * {@link ProjectedPackets}) and {@code main}'s compile classpath (for the game
 * jar) at once, and nothing else.
 */
public final class PacketGate {

    /** The package the game protocol lives in. Nothing outside it is scanned. */
    private static final String PACKET_PACKAGE = "net/minecraft/network/protocol/game/";

    /** Reported names for the class-assignability sentinels, in report order. */
    private static final String[][] SENTINELS = {
        {"net.minecraft.world.item.ItemStack", "ItemStack"},
        // The ShowItem hover form. Same content, same projection, so it is
        // reported as ItemStack rather than as a category of its own.
        {"net.minecraft.world.item.ItemStackTemplate", "ItemStack"},
        {"net.minecraft.world.level.block.state.BlockState", "BlockState"},
        {"net.minecraft.world.entity.EntityType", "EntityType"},
        {"net.minecraft.core.particles.ParticleOptions", "ParticleOptions"},
        {"net.minecraft.network.chat.Component", "Component"},
    };

    /**
     * Packages whose members are never walked.
     *
     * <p>Their generic arguments still are — see the class javadoc. The list is
     * "everything that is not the game", stated positively so a new third-party
     * dependency on a packet cannot quietly turn into a thousand-node walk
     * through somebody's buffer implementation.
     */
    private static final String[] FOREIGN = {
        "java.", "javax.", "sun.", "jdk.", "org.slf4j.",
        "com.mojang.serialization.", "com.mojang.datafixers.",
        "com.mojang.brigadier.", "com.mojang.authlib.", "com.mojang.blaze3d.",
        "io.netty.", "it.unimi.dsi.", "org.joml.", "com.google.",
        "org.apache.", "org.jetbrains.", "org.lwjgl.", "oshi.",
    };

    /**
     * Field types that are machinery rather than content.
     *
     * <p>A {@code StreamCodec} field is how the packet is written, not what it
     * says; a {@code PacketType} is its name. Neither can carry an id.
     */
    private static final String[] MACHINERY = {
        "net.minecraft.network.codec.StreamCodec",
        "net.minecraft.network.protocol.PacketType",
        "com.mojang.serialization.Codec",
        "com.mojang.serialization.MapCodec",
        // Every packet implements Packet<SomeListener>, so both turn up as
        // supertypes of all 185 of them. Neither is content, and Packet is not
        // a hole either: the only thing that holds packets is
        // ClientboundBundlePacket, and each packet inside a bundle is a packet
        // this gate already scans on its own.
        "net.minecraft.network.protocol.Packet",
        "net.minecraft.network.PacketListener",
    };

    /**
     * Live world state. Never serialised, at any depth, by anything.
     *
     * <p>Four roots, and between them they close every route out of the wire
     * form that this walk has found. A packet refers to a world by its
     * dimension key and to an entity by its network id; it does not carry
     * either. Without these, one {@code Optional<CacheableFunction>} on an
     * advancement reward — a field the advancement's own stream codec does not
     * even write — walks {@code CommandFunction<CommandSourceStack>} into
     * {@code MinecraftServer}, and out the far side into the JSON-RPC
     * management server's ban list.
     *
     * <p>Matched by assignability, so {@code ServerLevel}, {@code ServerPlayer}
     * and every other live subtype stop here too.
     */
    private static final String[] LIVE = {
        "net.minecraft.server.MinecraftServer",
        "net.minecraft.world.level.Level",
        "net.minecraft.world.entity.Entity",
        "net.minecraft.commands.CommandSourceStack",
        "net.minecraft.world.level.block.entity.BlockEntity",
    };

    /**
     * The types that hold a registry entry BY ID rather than by value.
     *
     * <p>Neither they nor their type arguments are walked — see the class
     * javadoc. {@code Holder<Item>} is tested before the stop because that one
     * parameterisation is a sentinel.
     */
    private static final String[] BY_ID = {
        "net.minecraft.core.Holder",
        "net.minecraft.core.HolderSet",
        "net.minecraft.core.HolderGetter",
        "net.minecraft.core.HolderLookup",
        "net.minecraft.core.Registry",
        "net.minecraft.core.IdMap",
        "net.minecraft.resources.ResourceKey",
        "net.minecraft.resources.Identifier",
        "net.minecraft.tags.TagKey",
    };

    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        File jar = minecraftJar();
        System.out.println("game jar: " + jar);

        List<Class<?>> sentinelTypes = new ArrayList<>();
        List<String> sentinelLabels = new ArrayList<>();
        for (String[] pair : SENTINELS) {
            sentinelTypes.add(Class.forName(pair[0], false, PacketGate.class.getClassLoader()));
            sentinelLabels.add(pair[1]);
        }
        Class<?> holder = Class.forName("net.minecraft.core.Holder", false,
                PacketGate.class.getClassLoader());
        Class<?> item = Class.forName("net.minecraft.world.item.Item", false,
                PacketGate.class.getClassLoader());

        List<Class<?>> byId = new ArrayList<>();
        for (String name : BY_ID) {
            byId.add(Class.forName(name, false, PacketGate.class.getClassLoader()));
        }
        List<Class<?>> live = new ArrayList<>();
        for (String name : LIVE) {
            live.add(Class.forName(name, false, PacketGate.class.getClassLoader()));
        }
        List<Class<?>> stops = registryElementTypes();
        stops.addAll(live);
        System.out.println("walk stops (built-in registry element types + live world roots): "
                + stops.size());

        List<Class<?>> packets = packetClasses(jar);
        System.out.println("packets scanned: " + packets.size());
        System.out.println();

        // Content-reaching packets, in jar order, with the sentinels they reach.
        Map<String, Map<String, String>> reaching = new LinkedHashMap<>();
        // Boundary-reaching packets: the type and encoding boundaries each crosses.
        Map<String, Map<String, String>> crossing = new LinkedHashMap<>();
        Set<String> present = new LinkedHashSet<>();
        // Boundary -> the first packet+path that reached it, plus a count.
        Map<String, String> opaqueWhere = new TreeMap<>();
        Map<String, Integer> opaqueCount = new TreeMap<>();
        Map<String, String> encodingWhere = new TreeMap<>();
        Map<String, Integer> encodingCount = new TreeMap<>();
        List<String> unreadable = new ArrayList<>();

        for (Class<?> packet : packets) {
            present.add(packet.getSimpleName());
            Walk walk = new Walk(packet, sentinelTypes, sentinelLabels, holder, item,
                    byId, stops);
            try {
                walk.run();
            } catch (Throwable t) {
                unreadable.add(packet.getSimpleName() + ": " + t);
                continue;
            }
            unreadable.addAll(walk.unreadable);
            for (Map.Entry<String, String> boundary : walk.opaque.entrySet()) {
                opaqueWhere.putIfAbsent(boundary.getKey(), boundary.getValue());
                opaqueCount.merge(boundary.getKey(), 1, Integer::sum);
            }
            for (Map.Entry<String, String> boundary : walk.encodings.entrySet()) {
                encodingWhere.putIfAbsent(boundary.getKey(), boundary.getValue());
                encodingCount.merge(boundary.getKey(), 1, Integer::sum);
            }
            if (!walk.hits.isEmpty()) {
                reaching.put(packet.getSimpleName(), walk.hits);
            }
            Map<String, String> boundaries = new LinkedHashMap<>(walk.opaque);
            boundaries.putAll(walk.encodings);
            if (!boundaries.isEmpty()) {
                crossing.put(packet.getSimpleName(), boundaries);
            }
        }

        // A packet needs a rule when it reaches content OR when it crosses a
        // boundary the walk cannot see past. The second half is not a
        // consolation prize: a boundary is by definition the place where
        // content could be and this gate would not know, so it is where a
        // human declaration is worth the most. Without it the gate asked for
        // the deletion of the two rules covering the ONLY two packets that
        // hide an ItemStack behind erasure — ClientboundSetEntityDataPacket
        // (behind DataValue<T>.value) and ServerboundContainerClickPacket
        // (behind HashedStack) — which is the exact inverse of its job.
        Set<String> needRule = new LinkedHashSet<>();
        needRule.addAll(reaching.keySet());
        needRule.addAll(crossing.keySet());

        // ------------------------------------------------------- (1) undeclared
        List<String> undeclared = new ArrayList<>();
        for (String name : needRule) {
            if (ProjectedPackets.ruleFor(name) == null) {
                undeclared.add(name);
            }
        }
        if (!undeclared.isEmpty()) {
            System.out.println("UNDECLARED PACKETS THAT REACH CONTENT OR CROSS A BOUNDARY ("
                    + undeclared.size() + ")");
            System.out.println("  Each needs a rule(\"<packet>\", Coverage.<...>, \"<why>\") in "
                    + "ProjectedPackets.");
            System.out.println("  A packet listed only under 'crosses' reaches no sentinel by type, "
                    + "but carries something");
            System.out.println("  this walk cannot see into. That is a declaration worth having, not "
                    + "a clean bill of health.");
            System.out.println();
            for (String name : undeclared) {
                System.out.println("  " + name);
                Map<String, String> hits = reaching.getOrDefault(name, Map.of());
                Map<String, String> boundaries = crossing.getOrDefault(name, Map.of());
                for (Map.Entry<String, String> hit : hits.entrySet()) {
                    System.out.println("      reaches  " + hit.getValue());
                }
                for (Map.Entry<String, String> boundary : boundaries.entrySet()) {
                    System.out.println("      crosses  " + boundary.getValue());
                }
                String what = hits.isEmpty()
                        ? "crosses " + String.join(", ", boundaries.keySet())
                                + " — a boundary this walk cannot see past —"
                        : "reaches " + String.join(", ", hits.keySet());
                fail(name + " " + what + " and has no rule in ProjectedPackets");
            }
            System.out.println();
        }

        // ------------------------------------------------- (2) and (3) dead rules
        Map<String, ProjectedPackets.Coverage> outlivedRules = new LinkedHashMap<>();
        List<String> outlived = new ArrayList<>();
        List<String> vanished = new ArrayList<>();
        for (ProjectedPackets.Rule rule : ProjectedPackets.rules()) {
            if (!present.contains(rule.packet())) {
                vanished.add(rule.packet());
                continue;
            }
            // LOSSLESS is exempt by construction. Those rows exist to record a
            // MECHANISM, not to claim coverage of a sentinel: the sound packets
            // carry Holder<SoundEvent>, which is not one of the seven and never
            // will be, and the row's whole content is the finding that a vanilla
            // client resolves an inline holder against its own atlas. A check
            // that deletes the note recording the phase's one free win has
            // misunderstood what the note is.
            if (rule.coverage() == ProjectedPackets.Coverage.LOSSLESS) {
                continue;
            }
            if (!needRule.contains(rule.packet())) {
                outlived.add(rule.packet());
                outlivedRules.put(rule.packet(), rule.coverage());
            }
        }
        if (!vanished.isEmpty()) {
            System.out.println("RULES NAMING A PACKET THAT NO LONGER EXISTS (" + vanished.size() + ")");
            System.out.println("  The game renamed or removed the class. Find its replacement and "
                    + "re-point the rule; do not just delete it.");
            System.out.println();
            for (String name : vanished) {
                System.out.println("  " + name);
                fail("ProjectedPackets declares " + name + ", which is not a class in "
                        + "net.minecraft.network.protocol.game at all — renamed or removed by a game update");
            }
            System.out.println();
        }
        if (!outlived.isEmpty()) {
            System.out.println("RULES WHOSE PACKET REACHES NEITHER CONTENT NOR A BOUNDARY ("
                    + outlived.size() + ")");
            System.out.println("  The packet still exists, carries none of the seven sentinels, and "
                    + "hides nothing behind");
            System.out.println("  an opaque type or encoding. There is nothing left for the rule to "
                    + "be about. Delete it.");
            System.out.println("  The coverage is printed with it because it is the thing to argue "
                    + "with: an EXEMPT row");
            System.out.println("  claims the packet DOES reach a sentinel and cannot carry content, "
                    + "and this walk says the");
            System.out.println("  premise is false — so the row is not wrong, it is unnecessary.");
            System.out.println();
            Map<ProjectedPackets.Coverage, Integer> byCoverage = new LinkedHashMap<>();
            for (String name : outlived) {
                ProjectedPackets.Coverage coverage = outlivedRules.get(name);
                byCoverage.merge(coverage, 1, Integer::sum);
                System.out.println("  " + coverage + "  " + name);
                fail("ProjectedPackets declares " + name + " (" + coverage + "), whose packet reaches "
                        + "no sentinel type and crosses no opaque boundary — the rule outlived its "
                        + "packet's content; delete it");
            }
            System.out.println();
            System.out.println("  by coverage: " + byCoverage);
            System.out.println();
        }

        // ------------------------------------------------------- (4) not a failure
        if (!opaqueWhere.isEmpty() || !encodingWhere.isEmpty()) {
            System.out.println("OPAQUE BOUNDARIES (not walked) ("
                    + (opaqueWhere.size() + encodingWhere.size()) + ")");
            System.out.println("  Where content can be without this gate seeing it. Crossing one is "
                    + "not a failure — it is");
            System.out.println("  what makes a rule REQUIRED above, so these are already accounted "
                    + "for, not merely noted.");
            System.out.println();
        }
        if (!opaqueWhere.isEmpty()) {
            System.out.println("  TYPE BOUNDARIES (" + opaqueWhere.size() + ") — non-sealed "
                    + "net.minecraft interfaces and abstract");
            System.out.println("  classes. The walk cannot enumerate their implementors, so it cannot "
                    + "know what a value");
            System.out.println("  actually is. This is where the next game version hides a new "
                    + "ItemStack.");
            System.out.println();
            for (Map.Entry<String, String> boundary : opaqueWhere.entrySet()) {
                System.out.println("    " + boundary.getKey()
                        + "  (reached from " + opaqueCount.get(boundary.getKey()) + " packet(s))");
                System.out.println("        " + boundary.getValue());
            }
            System.out.println();
        }
        if (!encodingWhere.isEmpty()) {
            System.out.println("  ENCODING BOUNDARIES (" + encodingWhere.size() + ") — the type is "
                    + "known and says nothing. An NBT");
            System.out.println("  Tag and a raw byte[] are both fully opaque to a type walk: "
                    + "ClientboundLevelChunkWith-");
            System.out.println("  LightPacket carries every blockstate in the chunk inside a byte[], "
                    + "and a lectern's book");
            System.out.println("  is a CompoundTag. Reporting 'reaches nothing' for those is true of "
                    + "the type and false");
            System.out.println("  of the content. String is deliberately NOT one of these — a String "
                    + "is a String.");
            System.out.println();
            for (Map.Entry<String, String> boundary : encodingWhere.entrySet()) {
                System.out.println("    " + boundary.getKey()
                        + "  (reached from " + encodingCount.get(boundary.getKey()) + " packet(s))");
                System.out.println("        " + boundary.getValue());
            }
            System.out.println();
        }

        if (!unreadable.isEmpty()) {
            System.out.println("TYPES THE WALK COULD NOT READ (" + unreadable.size() + ")");
            System.out.println("  A signature that would not resolve on this classpath. Reported "
                    + "because a walk that");
            System.out.println("  swallows these is a walk that silently checks less than it says.");
            System.out.println();
            for (String line : unreadable) {
                System.out.println("  " + line);
            }
            System.out.println();
        }

        // ------------------------------------------------------------- summary
        System.out.println("SUMMARY");
        System.out.println("  packets scanned:        " + packets.size());
        System.out.println("  reach content:          " + reaching.size());
        System.out.println("  cross a boundary:       " + crossing.size());
        System.out.println("  therefore need a rule:  " + needRule.size());
        System.out.println("  declared rules:         " + ProjectedPackets.rules().size()
                + "  (" + ProjectedPackets.describeCoverage() + ")");
        System.out.println("  undeclared:             " + undeclared.size());
        System.out.println("  rules with no packet:   " + vanished.size());
        System.out.println("  rules with no content:  " + outlived.size());
        System.out.println("  type boundaries:        " + opaqueWhere.size());
        System.out.println("  encoding boundaries:    " + encodingWhere.size());
        System.out.println();

        if (FAILURES.isEmpty()) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(FAILURES.size() + " CHECK(S) FAILED");
            for (String failure : FAILURES) {
                System.out.println("  FAIL: " + failure);
            }
            System.exit(1);
        }
    }

    private static void fail(String what) {
        FAILURES.add(what);
    }

    // --------------------------------------------------------------- the jar

    /**
     * The game jar, off {@code java.class.path}.
     *
     * <p>Enumerating the package by listing jar entries rather than by asking
     * for a list of classes, because there is no such list: the JVM has no
     * package-contents API, and a gate that scanned a hand-written array of
     * names would be blind to exactly the thing it exists to catch — a packet
     * the next game version adds.
     *
     * <p>Throws rather than returning empty when the jar is missing. A gate
     * that silently checks zero packets is worse than no gate, because it
     * prints {@code ALL CHECKS PASSED}.
     */
    private static File minecraftJar() {
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(File.pathSeparator)) {
            if (entry.contains("minecraft-merged") && entry.endsWith(".jar")) {
                File file = new File(entry);
                if (file.isFile()) {
                    return file;
                }
            }
        }
        throw new IllegalStateException(
                "no minecraft-merged jar on java.class.path, so there is nothing to enumerate. "
                + "The gate refuses to run rather than report zero packets and pass: this task's "
                + "classpath must be the fabric main runtime classpath, which is where Loom puts "
                + ".gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/*/*.jar. "
                + "Classpath was: " + classpath);
    }

    /**
     * Every BUILT-IN registry element type, read out of the game's own table.
     *
     * <p>{@code BuiltInRegistries} is a wall of
     * {@code public static final Registry<X> NAME}, and each {@code X} is a
     * type both ends of the connection already have: it is minted from code at
     * boot, it is never sent as a value, and every reference to it on the wire
     * is a numeric id or an {@code Identifier}. That is what makes it a sound
     * place to stop walking. Reading the set out of the class rather than
     * writing it down means a game update that adds a registry adds a stop on
     * the same day, with no edit here. Read with {@code initialize=false} and
     * {@code getGenericType()}: the signature is in the classfile, so nothing
     * bootstraps.
     *
     * <p><b>{@code BuiltInRegistries} and deliberately not {@code Registries}.</b>
     * {@code Registries} also names the DATAPACK registries, and those are a
     * different animal: {@code Registries.ADVANCEMENT} is a registry of
     * {@code Advancement}, and {@code ClientboundUpdateAdvancementsPacket} sends
     * whole {@code Advancement} records BY VALUE, icon and title and all.
     * Cutting there hid a genuinely projected packet's entire content from this
     * gate — it reported "reaches nothing" for the packet whose display icon is
     * a plain {@code ItemStack}. Built-in registries have no such case, because
     * a built-in entry that was sent by value would have nowhere to be
     * registered on arrival.
     */
    private static List<Class<?>> registryElementTypes() throws Exception {
        Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false,
                PacketGate.class.getClassLoader());
        Class<?> registry = Class.forName("net.minecraft.core.Registry", false,
                PacketGate.class.getClassLoader());
        Set<Class<?>> elements = new LinkedHashSet<>();
        for (Field field : builtIn.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            // Registry<X> / DefaultedRegistry<X> / WritableRegistry<X> -> X
            if (!(field.getGenericType() instanceof ParameterizedType declared)
                    || declared.getActualTypeArguments().length != 1) {
                continue;
            }
            Class<?> raw = erase(declared.getRawType());
            if (raw == null || !registry.isAssignableFrom(raw)) {
                continue;
            }
            Class<?> element = erase(declared.getActualTypeArguments()[0]);
            if (element != null && element.getName().startsWith("net.minecraft.")) {
                elements.add(element);
            }
        }
        if (elements.isEmpty()) {
            throw new IllegalStateException(
                    "read zero registry element types out of net.minecraft.core.registries."
                    + "BuiltInRegistries. Its fields are no longer Registry<X>, so the walk has lost the "
                    + "cut that keeps it inside the wire form and would report the whole server object "
                    + "graph as content. Fix registryElementTypes() rather than the table it feeds");
        }
        return new ArrayList<>(elements);
    }

    /** Every top-level {@code Clientbound*}/{@code Serverbound*} in the game protocol package. */
    private static List<Class<?>> packetClasses(File jar) throws Exception {
        List<Class<?>> found = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (JarFile file = new JarFile(jar)) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                String path = entry.getName();
                if (!path.startsWith(PACKET_PACKAGE) || !path.endsWith(".class")) {
                    continue;
                }
                String simple = path.substring(PACKET_PACKAGE.length(), path.length() - ".class".length());
                // Nested classes are reached through their owner's fields, so
                // enumerating them here would double-count and would attribute
                // a hit to a type that is not a packet.
                if (simple.indexOf('$') >= 0) {
                    continue;
                }
                if (!simple.startsWith("Clientbound") && !simple.startsWith("Serverbound")) {
                    continue;
                }
                names.add(path.substring(0, path.length() - ".class".length()).replace('/', '.'));
            }
        }
        names.sort(String::compareTo);
        for (String name : names) {
            // initialize=false: a packet's <clinit> builds its StreamCodec, which
            // on some packets touches registries that only exist after the game
            // has bootstrapped. Nothing here needs the class initialised — field
            // and record-component signatures are read from the classfile.
            found.add(Class.forName(name, false, PacketGate.class.getClassLoader()));
        }
        return found;
    }

    // -------------------------------------------------------------- the walk

    /**
     * One packet's content graph, walked breadth-first.
     *
     * <p>Breadth-first rather than depth-first for one reason: the message is
     * the whole value of this gate, and BFS reports the SHORTEST field path to
     * each sentinel. A depth-first walk finds the same sentinels and describes
     * them via whatever eight-hop detour it happened to take first, which is
     * the difference between a diagnostic somebody acts on and one they skim.
     */
    private static final class Walk {

        private final Class<?> packet;
        private final List<Class<?>> sentinels;
        private final List<String> labels;
        private final Class<?> holder;
        private final Class<?> item;
        private final List<Class<?>> byId;
        private final List<Class<?>> stops;
        private final Class<?> packetInterface;
        private final Class<?> packetListener;
        private final Class<?> tagInterface;

        /** Sentinel label -> the shortest field path that reached it. */
        final Map<String, String> hits = new LinkedHashMap<>();
        /** Non-sealed net.minecraft interface/abstract class -> the path that reached it. */
        final Map<String, String> opaque = new LinkedHashMap<>();
        /** {@code Tag} / {@code byte[]} — a known type carrying unknown content. */
        final Map<String, String> encodings = new LinkedHashMap<>();
        final List<String> unreadable = new ArrayList<>();

        private final Set<String> seen = new HashSet<>();
        private final Deque<Node> queue = new ArrayDeque<>();

        Walk(Class<?> packet, List<Class<?>> sentinels, List<String> labels,
                Class<?> holder, Class<?> item,
                List<Class<?>> byId, List<Class<?>> stops) {
            this.packet = packet;
            this.sentinels = sentinels;
            this.labels = labels;
            this.holder = holder;
            this.item = item;
            this.byId = byId;
            this.stops = stops;
            try {
                ClassLoader loader = PacketGate.class.getClassLoader();
                this.packetInterface = Class.forName(
                        "net.minecraft.network.protocol.Packet", false, loader);
                this.packetListener = Class.forName(
                        "net.minecraft.network.PacketListener", false, loader);
                this.tagInterface = Class.forName("net.minecraft.nbt.Tag", false, loader);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("no Packet/PacketListener on the classpath", e);
            }
        }

        void run() {
            enqueue(packet, List.of());
            while (!queue.isEmpty()) {
                Node node = queue.removeFirst();
                try {
                    visit(node);
                } catch (Throwable t) {
                    unreadable.add(packet.getSimpleName() + " at " + path(node.path, render(node.type))
                            + ": " + t);
                }
            }
        }

        private void enqueue(Type type, List<String> path) {
            // Keyed on the FULL generic name, so Holder<Item> and
            // Holder<SoundEvent> are two nodes (only one of them is content)
            // while the raw Holder underneath both is walked once.
            String key = type.getTypeName();
            if (seen.add(key)) {
                queue.addLast(new Node(type, path));
            }
        }

        private void visit(Node node) {
            Type type = node.type;

            // The non-class forms carry no members of their own; unwrap and go.
            if (type instanceof GenericArrayType array) {
                enqueue(array.getGenericComponentType(), node.path);
                return;
            }
            if (type instanceof WildcardType wildcard) {
                for (Type bound : wildcard.getUpperBounds()) {
                    if (bound != Object.class) {
                        enqueue(bound, node.path);
                    }
                }
                return;
            }
            if (type instanceof TypeVariable<?> variable) {
                for (Type bound : variable.getBounds()) {
                    if (bound != Object.class) {
                        enqueue(bound, node.path);
                    }
                }
                return;
            }

            Type[] arguments = null;
            if (type instanceof ParameterizedType parameterized) {
                arguments = parameterized.getActualTypeArguments();
            }
            Class<?> clazz = erase(type);
            if (clazz == null || clazz.isPrimitive()) {
                return;
            }
            if (clazz.isArray()) {
                // A raw byte[] is an encoding boundary: the chunk packet's
                // entire blockstate payload is one of these, and no walk over
                // types will ever say so.
                if (clazz.getComponentType() == byte.class) {
                    encodings.putIfAbsent("byte[]", path(node.path, null));
                    return;
                }
                enqueue(clazz.getComponentType(), node.path);
                return;
            }

            // (1) Held by id. Neither the wrapper nor its argument is on the
            //     wire as a value, so both stop here — but Holder<Item> IS a
            //     sentinel, and that is a shape test, not a walk.
            if (assignableToAny(byId, clazz)) {
                if (holder.isAssignableFrom(clazz) && arguments != null) {
                    for (Type argument : arguments) {
                        Class<?> erased = erase(argument);
                        if (erased != null && item.isAssignableFrom(erased)) {
                            hit("Holder<Item>", node.path, type);
                        }
                    }
                }
                return;
            }

            // (2) Content. Terminal: an ItemStack's own fields are not a second
            //     place to find an ItemStack, and a Component's are not a place
            //     to find a MinecraftServer.
            boolean scored = false;
            for (int i = 0; i < sentinels.size(); i++) {
                if (sentinels.get(i).isAssignableFrom(clazz)) {
                    hit(labels.get(i), node.path, type);
                    scored = true;
                }
            }
            if (scored) {
                return;
            }

            // (3) NBT. The type is known and tells you nothing: a lectern's
            //     book, a jukebox's disc and a decorated pot's contents are all
            //     ItemStacks living inside a CompoundTag. Recorded and not
            //     walked — walking Map<String, Tag> would only rediscover Tag.
            if (tagInterface.isAssignableFrom(clazz)) {
                encodings.putIfAbsent(clazz.getName(), path(node.path, render(clazz)));
                return;
            }

            // (4) A registry entry that is not content. Sent as an id; its
            //     fields never leave the server. This is the cut that keeps the
            //     walk inside the wire form.
            if (assignableToAny(stops, clazz)) {
                return;
            }

            // Generic arguments are always followed, even when the raw type is
            // not walked: List<ItemStack> and Optional<Component> are exactly
            // how this protocol carries content.
            if (arguments != null) {
                for (Type argument : arguments) {
                    enqueue(argument, node.path);
                }
            }

            // Enums are closed sets of vanilla constants; a slot index or a hand
            // is not content, and walking their synthetic $VALUES adds nothing.
            if (clazz.isEnum() || foreign(clazz) || machinery(clazz)
                    || !clazz.getName().startsWith("net.minecraft.")) {
                return;
            }

            if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
                Class<?>[] permitted = clazz.isSealed() ? clazz.getPermittedSubclasses() : null;
                if (permitted == null || permitted.length == 0) {
                    // No way to enumerate implementors. Say so rather than
                    // pretending the subtree is empty — EXCEPT when it is the
                    // packet itself. ClientboundMoveEntityPacket is an abstract
                    // base whose concrete forms are its nested $Pos/$Rot/$PosRot,
                    // and a packet counting as a boundary against itself would
                    // demand a rule of every abstract packet in the protocol
                    // while saying nothing about content.
                    if (clazz != packet) {
                        opaque.putIfAbsent(clazz.getName(), path(node.path, render(clazz)));
                    }
                } else {
                    for (Class<?> subclass : permitted) {
                        enqueue(subclass, node.path);
                    }
                }
                // An abstract class still has fields of its own; fall through.
            }

            members(clazz, node.path);
        }

        /**
         * Record components, or instance fields, plus whatever a superclass adds.
         *
         * <p>The generic SUPERTYPES are enqueued too, and that is not
         * housekeeping: {@code MerchantOffers extends ArrayList<MerchantOffer>}
         * and declares not one field of its own. Without following the
         * parameterised superclass, this gate said
         * {@code ClientboundMerchantOffersPacket} reaches no content — a packet
         * that is nothing but stacks. Every trade in the game went past it.
         */
        private void members(Class<?> clazz, List<String> path) {
            for (Type supertype : supertypes(clazz)) {
                if (supertype instanceof ParameterizedType) {
                    enqueue(supertype, path);
                }
            }
            if (clazz.isRecord()) {
                for (RecordComponent component : clazz.getRecordComponents()) {
                    Type type = component.getGenericType();
                    if (skipDeclared(type)) {
                        continue;
                    }
                    enqueue(type, extend(path, clazz, component.getName(), type));
                }
                return;
            }
            for (Class<?> level = clazz; level != null && level.getName().startsWith("net.minecraft.");
                    level = level.getSuperclass()) {
                for (Field field : level.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    Type type = field.getGenericType();
                    if (skipDeclared(type)) {
                        continue;
                    }
                    enqueue(type, extend(path, level, field.getName(), type));
                }
            }
        }

        /**
         * The parameterised supertypes worth following.
         *
         * <p>{@code Packet<SomeListener>} and its listener are dropped by
         * assignability rather than by name: every packet implements one, so
         * left in they put {@code ClientGamePacketListener} on the opaque list
         * with a count of 125 and buried the boundaries that mean something.
         * Name matching would not do it — the listener a packet names is
         * {@code ClientGamePacketListener}, a subinterface, never
         * {@code PacketListener} itself.
         */
        private List<Type> supertypes(Class<?> clazz) {
            List<Type> types = new ArrayList<>();
            Type superclass = clazz.getGenericSuperclass();
            if (superclass != null) {
                types.add(superclass);
            }
            types.addAll(List.of(clazz.getGenericInterfaces()));
            types.removeIf(type -> {
                Class<?> erased = erase(type);
                return erased == null || machinery(erased) || protocolPlumbing(erased);
            });
            return types;
        }

        private boolean protocolPlumbing(Class<?> clazz) {
            return packetInterface.isAssignableFrom(clazz)
                    || packetListener.isAssignableFrom(clazz);
        }

        private boolean skipDeclared(Type type) {
            Class<?> erased = erase(type);
            return erased != null && machinery(erased);
        }

        private void hit(String label, List<String> path, Type reached) {
            hits.putIfAbsent(label, path(path, null) + " -> " + label
                    + (label.equals(render(reached)) ? "" : " (" + render(reached) + ")"));
        }

        private List<String> extend(List<String> path, Class<?> owner, String name, Type type) {
            List<String> extended = new ArrayList<>(path);
            String where = path.isEmpty() ? packet.getSimpleName() : simple(owner);
            extended.add(where + "." + name + " : " + render(type));
            return extended;
        }

        /** {@code Packet.field : Type -> Owner.field : Type} — what to go and look at. */
        private String path(List<String> path, String tail) {
            String head = path.isEmpty() ? packet.getSimpleName() : String.join(" -> ", path);
            return tail == null ? head : head + " -> " + tail;
        }

        private record Node(Type type, List<String> path) {
        }
    }

    // ----------------------------------------------------------- type helpers

    private static boolean assignableToAny(List<Class<?>> candidates, Class<?> clazz) {
        for (Class<?> candidate : candidates) {
            if (candidate.isAssignableFrom(clazz)) {
                return true;
            }
        }
        return false;
    }

    private static boolean foreign(Class<?> clazz) {
        String name = clazz.getName();
        for (String prefix : FOREIGN) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean machinery(Class<?> clazz) {
        String name = clazz.getName();
        for (String machine : MACHINERY) {
            if (name.equals(machine) || name.startsWith(machine + "$")) {
                return true;
            }
        }
        return false;
    }

    /** The raw class behind a generic type, or null when there is not one. */
    private static Class<?> erase(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized) {
            return erase(parameterized.getRawType());
        }
        if (type instanceof GenericArrayType array) {
            Class<?> component = erase(array.getGenericComponentType());
            return component == null ? null : component.arrayType();
        }
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                if (bound != Object.class) {
                    return erase(bound);
                }
            }
        }
        return null;
    }

    /** {@code List<Pair<EquipmentSlot, ItemStack>>} — simple names, so the path stays readable. */
    private static String render(Type type) {
        if (type instanceof Class<?> clazz) {
            return simple(clazz);
        }
        if (type instanceof ParameterizedType parameterized) {
            StringBuilder text = new StringBuilder(render(parameterized.getRawType()));
            text.append('<');
            Type[] arguments = parameterized.getActualTypeArguments();
            for (int i = 0; i < arguments.length; i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(render(arguments[i]));
            }
            return text.append('>').toString();
        }
        if (type instanceof GenericArrayType array) {
            return render(array.getGenericComponentType()) + "[]";
        }
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                if (bound != Object.class) {
                    return "? extends " + render(bound);
                }
            }
            return "?";
        }
        if (type instanceof TypeVariable<?> variable) {
            return variable.getName();
        }
        return type.getTypeName();
    }

    /** {@code Item.Properties} rather than {@code Item$Properties}, and never a package. */
    private static String simple(Class<?> clazz) {
        if (clazz.isArray()) {
            return simple(clazz.getComponentType()) + "[]";
        }
        String name = clazz.getName();
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(dot + 1)).replace('$', '.');
    }

    private PacketGate() {
    }
}
