package tech.komutech.native_scripts.item;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Map;
import org.bukkit.inventory.EquipmentSlot;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;
import tech.komutech.native_scripts.support.PlayerAttributeStore;
import tech.komutech.native_scripts.support.PlayerAttributeStore.Lingli;

public final class LingStoneScript implements NativeScript {

    private static final Map<String, StoneConfig> STONES = Map.of(
        "KOMUTECH_L_DJ_XPLS", new StoneConfig(1, "下品灵石"),
        "KOMUTECH_L_DJ_ZPLS", new StoneConfig(100, "中品灵石"),
        "KOMUTECH_L_DJ_SPLS", new StoneConfig(10_000, "上品灵石"),
        "KOMUTECH_L_DJ_JPLS", new StoneConfig(1_000_000, "极品灵石")
    );

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        if (ctx.hand() != EquipmentSlot.HAND) {
            return null;
        }
        var player = ctx.player();
        var main = player.getInventory().getItemInMainHand();
        if (main == null || main.getType().isAir() || main.getAmount() < 1) {
            KomutechSupport.send(player, "&c主手物品不足！");
            return null;
        }
        StoneConfig config = getStoneConfig(main);
        if (config == null) {
            KomutechSupport.send(player, "&c主手物品不是有效的灵石！");
            return null;
        }
        Map<String, Object> data = PlayerAttributeStore.load(player.getName());
        if (data == null) {
            KomutechSupport.send(player, "&c你还没有玩家属性数据，无法充能！");
            return null;
        }
        if (!data.containsKey("灵力")) {
            data.put("灵力", "100/100+0");
        }
        Lingli lingli = PlayerAttributeStore.parseLingli(data.get("灵力"));
        double totalMax = lingli.totalMax();
        if (lingli.current() >= totalMax) {
            KomutechSupport.send(player, "&e灵力已满，无需充能！");
            return null;
        }
        double remain = totalMax - lingli.current();
        int useCount = 1;
        if (player.isSneaking()) {
            useCount = Math.min(main.getAmount(), Math.max(1, (int) Math.floor(remain / config.energy)));
        }
        double add = Math.min(useCount * (double) config.energy, remain);
        int realUse = (int) Math.floor(add / config.energy);
        if (realUse < 1) {
            realUse = 1;
        }
        double newCurrent = Math.min(lingli.current() + add, totalMax);
        data.put("灵力", PlayerAttributeStore.formatLingli(new Lingli(newCurrent, lingli.max(), lingli.bonus())));
        if (!PlayerAttributeStore.save(player.getName(), data)) {
            KomutechSupport.send(player, "&c保存数据失败！");
            return null;
        }
        main.setAmount(main.getAmount() - realUse);
        KomutechSupport.send(player, "&a[" + config.name + "] 充能成功！");
        KomutechSupport.send(player, "&b当前灵力：&6" + String.format("%.2f", newCurrent)
            + " &7/ &6" + totalMax + " &7（消耗&6" + realUse + "&7颗）");
        KomutechSupport.playBell(player);
        return null;
    }

    private static StoneConfig getStoneConfig(org.bukkit.inventory.ItemStack item) {
        SlimefunItem sf = SlimefunItem.getByItem(item);
        if (sf == null) {
            return null;
        }
        return STONES.get(sf.getId());
    }

    private record StoneConfig(int energy, String name) {
    }
}
