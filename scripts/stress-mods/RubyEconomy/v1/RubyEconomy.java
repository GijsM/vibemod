package vibemod.rubyeconomy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A ruby currency. Rubies are smelted from redstone, a ruby blade is crafted
 * from them, and every player has a balance that survives a restart.
 *
 * <p>The balances live in a JSON file inside the world folder, written with
 * plain {@code java.nio} and a hand-rolled encoder - the host's policy allows
 * {@code java.*} but not Gson, and a mod that cannot be unloaded cleanly is not
 * allowed a thread to write on, so every write is synchronous and small.
 */
public final class RubyEconomy implements ModInitializer {

    private static final Logger LOG = Logger.getLogger("VibeMod.RubyEconomy");
    private static final String NS = "vibemod_rubyeconomy";
    /** What a fresh account starts with. */
    private static final int STARTING_BALANCE = 10;

    private final Map<UUID, Integer> balances = new ConcurrentHashMap<>();
    private Path store;

    @Override
    public void onInitialize() {
        // Replayed by the host if the server is already up when this mod loads.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            store = server.getWorldPath(LevelResource.ROOT).resolve(NS + "-balances.json");
            load();
            LOG.info("ruby-economy loaded " + balances.size() + " balance(s) from " + store);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("balance")
                    .executes(ctx -> report(ctx.getSource(), ctx.getSource().getTextName()))
                    .then(Commands.argument("who", StringArgumentType.word())
                            .executes(ctx -> report(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "who")))));

            dispatcher.register(Commands.literal("pay")
                    .then(Commands.argument("from", StringArgumentType.word())
                            .then(Commands.argument("to", StringArgumentType.word())
                                    .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                            .executes(ctx -> pay(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "from"),
                                                    StringArgumentType.getString(ctx, "to"),
                                                    IntegerArgumentType.getInteger(ctx, "amount")))))));

            dispatcher.register(Commands.literal("econ")
                    .then(Commands.literal("grant")
                            .then(Commands.argument("who", StringArgumentType.word())
                                    .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                            .executes(ctx -> grant(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "who"),
                                                    IntegerArgumentType.getInteger(ctx, "amount"))))))
                    .then(Commands.literal("verify")
                            .executes(ctx -> verify(ctx.getSource())))
                    .then(Commands.literal("save").executes(ctx -> {
                        save();
                        ctx.getSource().sendSystemMessage(Component.literal(
                                "econ-saved accounts=" + balances.size() + " file=" + store));
                        return 1;
                    })));
        });
    }

    // ---------------------------------------------------------------- accounts

    /**
     * Whoever this name refers to. An online player keeps their real UUID; a
     * name nobody is using gets the stable name-derived one, which is what makes
     * this testable from a console with nobody logged in.
     */
    private UUID idOf(MinecraftServer server, String name) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player != null) {
            return player.getUUID();
        }
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private int balanceOf(UUID id) {
        return balances.computeIfAbsent(id, key -> STARTING_BALANCE);
    }

    private int report(CommandSourceStack source, String name) {
        UUID id = idOf(source.getServer(), name);
        source.sendSystemMessage(Component.literal(
                "balance " + name + " " + balanceOf(id) + " rubies (" + id + ")"));
        return 1;
    }

    private int grant(CommandSourceStack source, String name, int amount) {
        UUID id = idOf(source.getServer(), name);
        int now = balanceOf(id) + amount;
        balances.put(id, now);
        save();
        source.sendSystemMessage(Component.literal("econ-granted " + name + " " + amount
                + " -> " + now));
        return now;
    }

    private int pay(CommandSourceStack source, String from, String to, int amount) {
        UUID payer = idOf(source.getServer(), from);
        UUID payee = idOf(source.getServer(), to);
        int have = balanceOf(payer);
        if (have < amount) {
            source.sendSystemMessage(Component.literal(
                    "econ-refused " + from + " has " + have + ", needs " + amount));
            return 0;
        }
        balances.put(payer, have - amount);
        balances.put(payee, balanceOf(payee) + amount);
        save();
        source.sendSystemMessage(Component.literal("econ-paid " + from + " -> " + to
                + " " + amount + " (" + balances.get(payer) + "/" + balances.get(payee) + ")"));
        return 1;
    }

    // ----------------------------------------------------------------- recipes

    /**
     * Asks the live recipe manager whether this mod's two recipes arrived, and
     * then makes the game craft the blade for real - match plus assemble, the
     * same two calls a crafting table makes - so the components on the result
     * are read out of the stack the game would hand a player rather than out of
     * the JSON. A recipe with a shape 26.x rejects is dropped silently as the
     * pack loads, so "the file is on disk" is not the same as "the recipe works".
     */
    private int verify(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        boolean ruby = false;
        boolean blade = false;
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            String id = holder.id().identifier().toString();
            ruby |= id.equals(NS + ":ruby");
            blade |= id.equals(NS + ":ruby_blade");
        }

        ItemStack shard = new ItemStack(Items.AMETHYST_SHARD);
        ItemStack stick = new ItemStack(Items.STICK);
        ItemStack none = ItemStack.EMPTY;
        CraftingInput input = CraftingInput.of(3, 3, List.of(
                none, shard, none,
                none, shard, none,
                none, stick, none));
        ItemStack crafted = server.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, source.getLevel())
                .map(holder -> holder.value().assemble(input))
                .orElse(ItemStack.EMPTY);
        ItemAttributeModifiers modifiers = crafted.get(DataComponents.ATTRIBUTE_MODIFIERS);

        source.sendSystemMessage(Component.literal("econ-verify ruby=" + ruby
                + " blade=" + blade
                + " crafted=" + crafted.getItem().toString()
                + " craftedName=" + (crafted.get(DataComponents.CUSTOM_NAME) == null
                        ? "none" : crafted.get(DataComponents.CUSTOM_NAME).getString())
                + " bladeModifiers=" + (modifiers == null ? -1 : modifiers.modifiers().size())));
        return 1;
    }

    // -------------------------------------------------------------- persistence

    private void save() {
        if (store == null) {
            return;
        }
        StringBuilder json = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<UUID, Integer> entry : balances.entrySet()) {
            json.append(first ? "  \"" : ",\n  \"")
                    .append(entry.getKey())
                    .append("\": ")
                    .append(entry.getValue());
            first = false;
        }
        json.append("\n}\n");
        try {
            Files.createDirectories(store.getParent());
            Path temp = store.resolveSibling(store.getFileName() + ".tmp");
            Files.writeString(temp, json.toString(), StandardCharsets.UTF_8);
            Files.move(temp, store, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failed) {
            LOG.warning("ruby-economy could not save balances: " + failed);
        }
    }

    /** The other half of {@link #save}: flat "uuid": int pairs, nothing nested. */
    private void load() {
        if (store == null || !Files.exists(store)) {
            return;
        }
        try {
            for (String line : lines(Files.readString(store, StandardCharsets.UTF_8))) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = line.substring(0, colon).replace("\"", "").trim();
                String value = line.substring(colon + 1).replace(",", "").trim();
                if (key.isEmpty() || value.isEmpty()) {
                    continue;
                }
                balances.put(UUID.fromString(key), Integer.parseInt(value));
            }
        } catch (IOException | IllegalArgumentException failed) {
            LOG.warning("ruby-economy could not read balances: " + failed);
        }
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.equals("{") && !trimmed.equals("}")) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
