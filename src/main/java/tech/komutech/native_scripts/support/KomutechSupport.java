package tech.komutech.native_scripts.support;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import tech.origintech.NativeAddonConfig;
import tech.origintech.native_scripts.NativePluginHelper;
import tech.origintech.native_scripts.support.ChatColors;
import tech.origintech.native_scripts.support.InventoryHelper;

public final class KomutechSupport {

    private static final String FULL_INVENTORY_MESSAGE = "&e背包已满，物品已掉落在地面上";

    private KomutechSupport() {
    }

    public static void send(Player player, String message) {
        ChatColors.send(player, message);
    }

    public static void actionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }

    public static void giveOrDrop(Player player, ItemStack stack) {
        InventoryHelper.giveOrDrop(player, stack, FULL_INVENTORY_MESSAGE);
    }

    public static void giveOrDrop(Player player, String itemId) {
        InventoryHelper.giveOrDrop(player, itemId, FULL_INVENTORY_MESSAGE);
    }

    public static void playBell(Player player) {
        Location loc = player.getLocation();
        player.getWorld().playSound(loc, Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
    }

    public static Plugin plugin() {
        Plugin plugin = NativePluginHelper.getPlugin();
        if (plugin != null) {
            return plugin;
        }
        return Bukkit.getPluginManager().getPlugin(NativeAddonConfig.PLUGIN_NAME);
    }

    public static boolean isRightClick(Object clickAction) {
        if (clickAction == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(clickAction.getClass().getMethod("isRightClicked").invoke(clickAction));
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    public static boolean isShiftClick(Object clickAction) {
        if (clickAction == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(clickAction.getClass().getMethod("isShiftClicked").invoke(clickAction));
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }
}
