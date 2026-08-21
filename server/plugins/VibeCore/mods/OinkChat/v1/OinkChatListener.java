package vibemod.oinkchat;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Random;

public final class OinkChatListener implements Listener {

    private static final double REPLACE_CHANCE = 0.35;
    private final Random random = new Random();

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (event == null) {
            return;
        }
        String message = event.getMessage();
        if (message == null || message.isEmpty()) {
            return;
        }
        String[] words = message.split(" ");
        if (words.length == 0) {
            return;
        }
        StringBuilder rebuilt = new StringBuilder();
        boolean changedAny = false;
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (!word.isEmpty() && random.nextDouble() < REPLACE_CHANCE) {
                rebuilt.append("oink oink");
                changedAny = true;
            } else {
                rebuilt.append(word);
            }
            if (i < words.length - 1) {
                rebuilt.append(' ');
            }
        }
        if (changedAny) {
            event.setMessage(rebuilt.toString());
        }
    }
}
