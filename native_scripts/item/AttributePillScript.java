package tech.komutech.native_scripts.item;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.AttributeApplier;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;
import tech.komutech.native_scripts.support.PlayerAttributeStore;

public final class AttributePillScript implements NativeScript {

    private static final Map<String, PillConfig> PILLS = new LinkedHashMap<>();

    static {
        register("KOMUTECH_L_DY_低品悟性丹", "悟性", 0.1, 3);
        register("KOMUTECH_L_DY_中品悟性丹", "悟性", 0.25, 5);
        register("KOMUTECH_L_DY_高品悟性丹", "悟性", 0.5, 8);
        register("KOMUTECH_L_DY_极品悟性丹", "悟性", 1.0, 10);
        register("KOMUTECH_L_DY_低品洗髓丹", "根骨", 0.1, 3);
        register("KOMUTECH_L_DY_中品洗髓丹", "根骨", 0.25, 5);
        register("KOMUTECH_L_DY_高品洗髓丹", "根骨", 0.5, 8);
        register("KOMUTECH_L_DY_极品洗髓丹", "根骨", 1.0, 10);
    }

    private static void register(String itemId, String attribute, double bonus, double cap) {
        PILLS.put(itemId, new PillConfig(attribute, bonus, cap));
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        ItemStack item = ctx.item();
        SlimefunItem sf = SlimefunItem.getByItem(item);
        if (sf == null) {
            KomutechSupport.actionBar(player, "§c请手持丹药使用！");
            return null;
        }
        PillConfig pill = PILLS.get(sf.getId());
        if (pill == null) {
            KomutechSupport.actionBar(player, "§c无效的丹药！");
            return null;
        }

        Map<String, Object> data = PlayerAttributeStore.load(player.getName());
        if (data == null) {
            KomutechSupport.actionBar(player, "§c尚未鉴灵，无法服用丹药！");
            return null;
        }

        double current = PlayerAttributeStore.getDouble(data, pill.attribute(), 1);
        if (current >= pill.cap()) {
            KomutechSupport.actionBar(player, "§c当前" + pill.attribute() + "已达该品级上限(" + format(pill.cap()) + ")！");
            return null;
        }

        double next = round(Math.min(current + pill.bonus(), pill.cap()));
        if (next <= current) {
            KomutechSupport.actionBar(player, "§c当前" + pill.attribute() + "无法通过该丹药继续提升！");
            return null;
        }

        data.put(pill.attribute(), next);
        if ("根骨".equals(pill.attribute())) {
            // 根骨提升会放大所有加点实际值，强制按上限截断
            AttributeApplier.applyPlayerAttributes(player, data, false);
        }
        if (!PlayerAttributeStore.save(player.getName(), data)) {
            KomutechSupport.actionBar(player, "§c保存属性失败，请联系管理员！");
            return null;
        }

        UseEvents.consumeOne(ctx);
        double gained = round(next - current);
        KomutechSupport.actionBar(player, "§a" + pill.attribute() + " +" + format(gained)
            + " §7(当前 " + format(next) + " / 该品级上限 " + format(pill.cap()) + ")");
        KomutechSupport.playBell(player);
        return null;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String format(double value) {
        if (Math.rint(value) == value) {
            return String.valueOf((long) value);
        }
        return String.format("%.2f", value);
    }

    private record PillConfig(String attribute, double bonus, double cap) {
    }
}
