package com.gijsm.vibemod.fabric.project;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;

import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import com.gijsm.vibemod.fabric.net.ContentManifest;
import com.gijsm.vibemod.store.ModResources;

/**
 * Keeping VibeMod's entries out of fabric-api's sync map, and proving at boot
 * that we still can (V4 Phase 4, step zero).
 *
 * <p>The mechanism is {@code RegistrySyncManagerMixin}, which redirects
 * {@code configureClient}'s call to {@code createAndPopulateRegistryMap()} into
 * {@link #filterForConnection}. This class is the half of it that is ordinary
 * Java: the filter itself, the counters, and — because the redirect targets
 * another mod's implementation class — a self-check that refuses to let the
 * projection claim to work when it does not.
 *
 * <h2>What the filter does, and what it deliberately does not</h2>
 *
 * <p>It strips entries whose namespace starts with {@code vibemod_} —
 * {@code ModResources.NAMESPACE_PREFIX}, which is every namespace VibeMod ever
 * mints — and drops a registry left holding nothing, <b>for connections that
 * cannot receive our manifest</b>. A Lane A connection gets the map back untouched and by identity:
 * Fabric's {@code SyncConfigurationTask} is what makes Lane A's numeric ids
 * agree, and interfering with it would break the lane that works.
 *
 * <p>It does not touch any other mod's entries. If a second content mod is
 * installed and its registries are not {@code OPTIONAL}, fabric-api still
 * disconnects the vanilla client — correctly, and with its own message. That is
 * not ours to suppress.
 *
 * <h2>Why the self-check exists</h2>
 *
 * <p>A {@code @Redirect} against somebody else's implementation class is a
 * standing bet on a method name in a jar we do not control. {@code "required":
 * true} in {@code vibemod.mixins.json} turns a failed <em>apply</em> into a
 * startup crash, which covers a rename or a signature change. It does not cover
 * the subtler failure — the mixin applies, the filter runs, and the entries are
 * still there — so {@link #selfCheck()} proves both halves: that the handler is
 * genuinely merged onto {@code RegistrySyncManager}, and that the filter
 * genuinely removes a synthetic VibeMod entry and genuinely keeps a vanilla one.
 *
 * <p>When it fails, {@link #available()} goes false and
 * {@code VanillaLane} refuses Lane B connections by name rather than letting
 * fabric-api kick them with a message that mentions neither VibeMod nor the
 * item. A loud refusal at the door beats a mystery ten seconds later.
 */
public final class RegistryHiding {

    private static final Logger LOG = Logger.getLogger("VibeMod.Lane");

    /** The namespace of a synthetic entry that exists only inside the self-check. */
    private static final String PROBE_NAMESPACE = ModResources.NAMESPACE_PREFIX + "selfcheck";

    /** Registry maps filtered for a Lane B connection since boot. */
    private static final AtomicInteger FILTERED = new AtomicInteger();

    /** Entries stripped across all of those, for the gates. */
    private static final AtomicInteger STRIPPED = new AtomicInteger();

    /** Maps handed back untouched because the connection is Lane A. */
    private static final AtomicInteger PASSED = new AtomicInteger();

    private static volatile boolean available;
    private static volatile String unavailableReason = "the boot self-check has not run yet";

    private RegistryHiding() {
    }

    /**
     * Whether the redirect is proven to be in place.
     *
     * <p>False until {@link #selfCheck()} has run and passed. Callers treat it
     * as "a vanilla client will be kicked by fabric-api if any VibeMod content
     * exists", which is exactly the V3 situation.
     */
    public static boolean available() {
        return available;
    }

    /** Why {@link #available()} is false, as a sentence. Null when it is true. */
    public static String unavailableReason() {
        return available ? null : unavailableReason;
    }

    // ------------------------------------------------------------------ the filter

    /**
     * The redirect's target: fabric's map, minus our entries, for a connection
     * that was never told about them.
     *
     * <p>Null-safe because {@code configureClient} is: the disassembly shows
     * {@code ifnonnull} on the result at offset 30, so the method can and does
     * return null, and a filter that NPE'd there would break every join on a
     * server with no modded registries at all.
     */
    public static Map<Identifier, Object2IntMap<Identifier>> filterForConnection(
            Map<Identifier, Object2IntMap<Identifier>> map,
            ServerConfigurationPacketListenerImpl handler) {
        if (map == null) {
            return null;
        }
        if (ServerConfigurationNetworking.canSend(handler, ContentManifest.TYPE)) {
            // Lane A. Fabric's own remap is the mechanism that makes this lane
            // work; the map goes back by identity, unread.
            PASSED.incrementAndGet();
            return map;
        }

        Stripped stripped = strip(map);
        if (stripped.removed() == 0) {
            return map;
        }
        FILTERED.incrementAndGet();
        STRIPPED.addAndGet(stripped.removed());
        LOG.info("Lane B: hid " + stripped.removed() + " VibeMod registry entr(ies) in "
                + stripped.registriesTouched() + " registr(ies) from fabric-api's sync map for "
                + handler.getOwner().name() + ", so this vanilla client is not disconnected at "
                + "configuration over content it was never going to be sent");
        return stripped.map();
    }

    /**
     * The connection-independent half, so the self-check can exercise exactly
     * the code the redirect runs rather than an imitation of it.
     */
    static Stripped strip(Map<Identifier, Object2IntMap<Identifier>> map) {
        Map<Identifier, Object2IntMap<Identifier>> out = new LinkedHashMap<>(map.size());
        int removed = 0;
        int touched = 0;

        for (Map.Entry<Identifier, Object2IntMap<Identifier>> registry : map.entrySet()) {
            Object2IntMap<Identifier> entries = registry.getValue();
            List<Identifier> ours = new ArrayList<>();
            for (Identifier id : entries.keySet()) {
                if (isVibeModNamespace(id.getNamespace())) {
                    ours.add(id);
                }
            }
            if (ours.isEmpty()) {
                out.put(registry.getKey(), entries);
                continue;
            }
            touched++;
            removed += ours.size();
            if (ours.size() == entries.size()) {
                // Every entry in this registry was ours, so the registry itself
                // must go: leaving an empty Object2IntMap behind would still
                // count as a modded registry in areAllRegistriesOptional and
                // still name a namespace in the kick message.
                continue;
            }
            Object2IntMap<Identifier> kept = new Object2IntOpenHashMap<>(entries.size() - ours.size());
            for (Identifier id : entries.keySet()) {
                if (!isVibeModNamespace(id.getNamespace())) {
                    kept.put(id, entries.getInt(id));
                }
            }
            out.put(registry.getKey(), kept);
        }
        return new Stripped(out, removed, touched);
    }

    /** The filtered map and what came out of it. */
    record Stripped(Map<Identifier, Object2IntMap<Identifier>> map, int removed, int registriesTouched) {
    }

    /** True for a namespace VibeMod mints, which is every one it ever registers under. */
    public static boolean isVibeModNamespace(String namespace) {
        return namespace != null && namespace.startsWith(ModResources.NAMESPACE_PREFIX);
    }

    // ------------------------------------------------------------------ the self-check

    /**
     * Proves at boot that the redirect is real, or says loudly that it is not.
     *
     * <p>Two assertions, and they fail differently on purpose:
     *
     * <ol>
     *   <li><b>The handler is merged onto {@code RegistrySyncManager}.</b> Mixin
     *       merges an injector handler into the target class, and may prefix the
     *       merged name, so the check is a suffix match rather than an exact one
     *       — a rename by Mixin is expected, an absence is not. This is what
     *       catches "the mixin silently did not apply to the class we think it
     *       did".</li>
     *   <li><b>The filter removes what it claims to.</b> A synthetic map holding
     *       one {@code vibemod_selfcheck:probe} entry beside one
     *       {@code minecraft:stone} entry, through the same {@link #strip} the
     *       redirect calls: the probe must be gone, stone must survive, and a
     *       registry holding only the probe must be dropped rather than left
     *       empty. This is what catches "the filter applied and did nothing" —
     *       which {@code required: true} cannot see.</li>
     * </ol>
     *
     * @return true when the projection may be offered; false when Lane B must
     *         fall back to the dedicated-server refusal
     */
    public static boolean selfCheck() {
        List<String> problems = new ArrayList<>();

        String handler = findMergedHandler();
        if (handler == null) {
            problems.add("the @Redirect handler vibemod$hideContentFromVanillaClients is not on "
                    + "net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager. The mixin did not "
                    + "apply to the class it names, so createAndPopulateRegistryMap() is still "
                    + "returning VibeMod's entries to every vanilla client");
        }

        Identifier probe = Identifier.fromNamespaceAndPath(PROBE_NAMESPACE, "probe");
        Identifier vanilla = Identifier.parse("minecraft:stone");
        Identifier itemRegistry = Identifier.parse("minecraft:item");
        Identifier blockRegistry = Identifier.parse("minecraft:block");

        Object2IntMap<Identifier> mixed = new Object2IntOpenHashMap<>();
        mixed.put(vanilla, 1);
        mixed.put(probe, 2);
        Object2IntMap<Identifier> oursOnly = new Object2IntOpenHashMap<>();
        oursOnly.put(probe, 3);

        Map<Identifier, Object2IntMap<Identifier>> synthetic = new LinkedHashMap<>();
        synthetic.put(itemRegistry, mixed);
        synthetic.put(blockRegistry, oursOnly);

        Stripped result = strip(synthetic);
        if (result.removed() != 2) {
            problems.add("the filter stripped " + result.removed() + " synthetic entr(ies), expected 2");
        }
        Object2IntMap<Identifier> filteredItems = result.map().get(itemRegistry);
        if (filteredItems == null || filteredItems.containsKey(probe)) {
            problems.add("the filter left " + probe + " in " + itemRegistry
                    + "; a vanilla client would still be disconnected over it");
        }
        if (filteredItems == null || !filteredItems.containsKey(vanilla)) {
            problems.add("the filter removed " + vanilla + " from " + itemRegistry
                    + "; it must only ever touch VibeMod namespaces");
        }
        if (result.map().containsKey(blockRegistry)) {
            problems.add("the filter left " + blockRegistry + " in the map with no entries; an empty "
                    + "modded registry still counts in areAllRegistriesOptional and still names a "
                    + "namespace in fabric-api's kick message");
        }

        if (problems.isEmpty()) {
            available = true;
            unavailableReason = null;
            LOG.info("Lane B step zero verified: " + handler + " is merged onto RegistrySyncManager and "
                    + "the filter strips VibeMod namespaces while leaving minecraft: alone. A vanilla "
                    + "client will not be disconnected at configuration over VibeMod content");
            return true;
        }

        available = false;
        unavailableReason = String.join("; ", problems);
        LOG.severe("Lane B step zero FAILED, so vanilla-client projection is off: " + unavailableReason
                + ". VibeMod falls back to refusing registry content while a non-VibeMod client is "
                + "connected — the V3 behaviour — because the alternative is fabric-api disconnecting "
                + "that client during configuration with a message naming neither VibeMod nor the item. "
                + "This almost always means fabric-registry-sync-v0 changed shape; the redirect targets "
                + "RegistrySyncManager.createAndPopulateRegistryMap()");
        return false;
    }

    /**
     * The merged handler's name on the target class, or null.
     *
     * <p>Suffix match rather than equality: Mixin is entitled to rename a merged
     * injector handler (it does so to keep handlers from colliding when two
     * mixins target the same class), and the original name survives as the tail
     * of whatever it picks. Demanding the exact name would turn a Mixin version
     * bump into a false alarm, and a false alarm here disables a working
     * projection.
     */
    private static String findMergedHandler() {
        Class<?> target;
        try {
            // initialize = false: Mixin transforms at class LOAD, so the merged
            // handler is visible without running fabric-api's static
            // initialiser. Running somebody else's <clinit> early, from mod
            // init, to answer a question that does not need it is how a
            // self-check becomes the bug it was written to catch.
            target = Class.forName("net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager",
                    false, RegistryHiding.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            return null;
        }
        for (Method method : target.getDeclaredMethods()) {
            if (method.getName().endsWith("vibemod$hideContentFromVanillaClients")) {
                return method.getName();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ state, for the gates

    /**
     * {@code "registryHiding=on filteredMaps=1 hiddenEntries=3 laneAPassthroughs=2"}.
     */
    public static String describeState() {
        return "registryHiding=" + (available ? "on" : "off")
                + " filteredMaps=" + FILTERED.get()
                + " hiddenEntries=" + STRIPPED.get()
                + " laneAPassthroughs=" + PASSED.get();
    }
}
