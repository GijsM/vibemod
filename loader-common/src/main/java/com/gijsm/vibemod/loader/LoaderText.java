package com.gijsm.vibemod.loader;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/**
 * Adventure text into the game and back.
 *
 * <p>VibeMod's whole UI is built from Adventure {@code Component}s — the screen
 * model, the chat renderer, every message core sends. The game speaks
 * {@code net.minecraft.network.chat.Component}. Something has to translate, and
 * ARCHITECTURE-V2 §1 expected adventure-platform-mod to be that something.
 *
 * <p>It is not (§10.3): it has no dialog support, which was the reason §8.5
 * named it, and its per-MC builds are pinned to different Adventure majors, both
 * above the 4.24.0 {@code core} compiles against. So the bridge is here, and it
 * is the boring one: serialize to JSON with Adventure's own Gson serializer,
 * parse with Minecraft's {@code ComponentSerialization} codec. Both sides
 * implement the same wire format — it is what the server sends players — so a
 * round trip is lossless for everything VibeMod builds, including click and
 * hover events.
 *
 * <p>The parse needs a {@link net.minecraft.resources.RegistryOps} because
 * components can reference registry data (item hovers, for one). That is the
 * only reason a {@link MinecraftServer} has to be in scope to render text.
 */
public final class LoaderText {

    private static final Logger LOG = Logger.getLogger(LoaderText.class.getName());

    private LoaderText() {
    }

    /**
     * Adventure {@code Component} to a vanilla one. Never throws: a component
     * that will not convert degrades to its plain text, because a UI that
     * renders in the wrong font beats a UI that throws inside a packet write.
     */
    public static net.minecraft.network.chat.Component toVanilla(
            net.kyori.adventure.text.Component adventure, HolderLookup.Provider registries) {
        if (adventure == null) {
            return net.minecraft.network.chat.Component.empty();
        }
        try {
            JsonElement json = GsonComponentSerializer.gson().serializer().toJsonTree(adventure);
            var ops = registries.createSerializationContext(JsonOps.INSTANCE);
            return ComponentSerialization.CODEC.parse(ops, json)
                    .getOrThrow(reason -> new IllegalStateException("component decode failed: " + reason));
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Falling back to plain text for a component that would not convert", t);
            return net.minecraft.network.chat.Component.literal(plain(adventure));
        }
    }

    /** Same, taking the server for its registries — the common call shape. */
    public static net.minecraft.network.chat.Component toVanilla(
            net.kyori.adventure.text.Component adventure, MinecraftServer server) {
        return toVanilla(adventure, server.registryAccess());
    }

    /** The plain-text rendering of an Adventure component (chat capture, logs, fallbacks). */
    public static String plain(net.kyori.adventure.text.Component adventure) {
        if (adventure == null) {
            return "";
        }
        try {
            return PlainTextComponentSerializer.plainText().serialize(adventure);
        } catch (Throwable t) {
            return "";
        }
    }

    /** The plain-text rendering of a vanilla component. */
    public static String plain(net.minecraft.network.chat.Component vanilla) {
        return vanilla == null ? "" : vanilla.getString();
    }

    /**
     * {@code "namespace:path"} to an {@link Identifier}, or null when it is not
     * a legal id. Generated code supplies these (sound ids, item ids), so
     * "not a legal id" is a normal input, not an error.
     */
    public static Identifier idOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return raw.indexOf(':') >= 0
                    ? Identifier.parse(raw.trim())
                    : Identifier.withDefaultNamespace(raw.trim());
        } catch (Throwable notAnId) {
            return null;
        }
    }
}
