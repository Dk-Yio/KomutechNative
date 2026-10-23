package tech.komutech.native_scripts.support;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public final class ChatInputService implements Listener {

    private static ChatInputService instance;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public static void register(Plugin plugin) {
        if (instance == null) {
            instance = new ChatInputService();
            plugin.getServer().getPluginManager().registerEvents(instance, plugin);
        }
    }

    public static boolean request(Player player, String prompt, Consumer<String> callback) {
        if (player == null || callback == null) {
            return false;
        }
        register(KomutechSupport.plugin());
        instance.pending.put(player.getUniqueId(), callback);
        player.sendMessage(prompt);
        return true;
    }

    public static void cancel(Player player) {
        if (instance != null && player != null) {
            instance.pending.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Consumer<String> callback = pending.remove(event.getPlayer().getUniqueId());
        if (callback == null) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage();
        Plugin plugin = KomutechSupport.plugin();
        if (plugin == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(message));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
