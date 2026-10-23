package tech.komutech.native_scripts.support;

import io.github.thebusybiscuit.slimefun4.libraries.dough.chat.ChatInput;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class KomutechChatInput {

    private KomutechChatInput() {
    }

    public static void waitFor(Player player, Consumer<String> callback) {
        Plugin plugin = KomutechSupport.plugin();
        if (plugin == null) {
            return;
        }
        ChatInput.waitForPlayer(plugin, player, callback::accept);
    }
}
