package tech.komutech.native_scripts.item;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;

public final class WuGouPiScript implements NativeScript {

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        double maxHealth = player.getMaxHealth();
        double healthToRemove = maxHealth * 0.99;
        double newHealth = player.getHealth() - healthToRemove;
        player.setHealth(Math.max(newHealth, 0));

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() > 0) {
            hand.setAmount(hand.getAmount() - 1);
        }

        SlimefunItem bound = SlimefunItem.getById("KOMUTECH_L_DJ_蕴灵身");
        if (bound == null) {
            KomutechSupport.send(player, "§c错误：未找到蕴灵身物品！");
            return null;
        }
        ItemStack stack = bound.getItem().clone();
        ItemMeta meta = stack.getItemMeta();
        var lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<String>();
        lore.add("§b已绑定：§a" + player.getName());
        meta.setLore(lore);
        stack.setItemMeta(meta);
        KomutechSupport.giveOrDrop(player, stack);
        KomutechSupport.send(player, "§a你已绑定蕴灵身！");
        KomutechSupport.send(player, "§c已扣除最大生命值的99%！");
        if (newHealth <= 0) {
            KomutechSupport.send(player, "§4警告：生命值耗尽！");
        }
        return null;
    }
}
