package vibemod.chatcraft;

import java.util.Optional;
import java.util.logging.Logger;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Say "craft me a diamond" in chat and get one.
 *
 * <p>The chat listener and the {@code /chatcraft} command both go through
 * {@link #resolve}, so the parse and the lookup can be exercised without a
 * player in the world - which matters, because chat cannot be sent from a
 * console.
 */
public final class ChatCraft implements ModInitializer {

    private static final Logger LOG = Logger.getLogger("VibeMod.ChatCraft");
    private static final String NS = "vibemod_chatcraft";
    private static final String TRIGGER = "craft me a ";

    private int served;

    @Override
    public void onInitialize() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String said = message.signedContent().toLowerCase();
            int at = said.indexOf(TRIGGER);
            if (at < 0) {
                return true;
            }
            String wanted = said.substring(at + TRIGGER.length()).trim();
            Optional<Holder.Reference<Item>> item = resolve(wanted);
            if (item.isEmpty()) {
                sender.sendSystemMessage(Component.literal("Never heard of a " + wanted + "."));
                // Still swallowed: the line was addressed to the mod, not the room.
                return false;
            }
            give(sender, item.get());
            served++;
            LOG.info("chatcraft-served " + wanted + " total=" + served);
            return false;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("chatcraft")
                        .then(Commands.argument("what", StringArgumentType.greedyString())
                                .executes(ctx -> lookup(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "what"))))
                        .executes(ctx -> {
                            ctx.getSource().sendSystemMessage(Component.literal(
                                    "chatcraft-status served=" + served
                                            + " trigger=\"" + TRIGGER + "<item>\""));
                            return 1;
                        })));
    }

    /**
     * The command half: runs exactly the parse and the registry lookup the chat
     * listener runs, and says what it found. Nothing is given out, because a
     * console has no inventory.
     */
    private int lookup(CommandSourceStack source, String phrase) {
        String said = phrase.toLowerCase();
        int at = said.indexOf(TRIGGER);
        String wanted = at < 0 ? said.trim() : said.substring(at + TRIGGER.length()).trim();
        Optional<Holder.Reference<Item>> item = resolve(wanted);
        source.sendSystemMessage(Component.literal("chatcraft-lookup want=" + wanted
                + " found=" + item.isPresent()
                + " id=" + item.map(holder -> holder.key().identifier().toString()).orElse("none")));
        return item.isPresent() ? 1 : 0;
    }

    /**
     * "diamond sword" or "minecraft:diamond_sword" -&gt; the item, if it exists.
     *
     * <p>{@code Identifier} throws on anything that is not a legal path, and a
     * player will type punctuation, so the parse failure is a miss rather than a
     * stack trace.
     */
    private Optional<Holder.Reference<Item>> resolve(String wanted) {
        String path = wanted.replace(' ', '_').replace("minecraft:", "");
        Identifier id = Identifier.tryBuild("minecraft", path);
        return id == null ? Optional.empty() : BuiltInRegistries.ITEM.get(id);
    }

    private void give(ServerPlayer player, Holder.Reference<Item> item) {
        ItemStack stack = new ItemStack(item.value());
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.sendSystemMessage(Component.literal("One "
                + item.key().identifier().getPath().replace('_', ' ') + ", coming up."));
    }
}
