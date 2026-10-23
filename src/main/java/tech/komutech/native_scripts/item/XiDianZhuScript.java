package tech.komutech.native_scripts.item;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.AttributeApplier;
import tech.komutech.native_scripts.support.KomutechChatInput;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;
import tech.komutech.native_scripts.support.PlayerAttributeStore;

/**
 * 洗点珠：将已分配到各项属性的点数退回「属性点」，属性恢复为鉴灵基底（无基底则按 0）。
 * 不删除灵根/修为（与归元珠不同）。
 */
public final class XiDianZhuScript implements NativeScript {

    public static final String ITEM_ID = "KOMUTECH_L_DJ_洗点珠";
    public static final String BASE_KEY = "属性基底";
    public static final List<String> ALLOCATABLE_ATTRS = List.of(
        "血量", "攻击力", "防御力", "速度", "灵识", "灵气获取"
    );

    private static final Set<String> PENDING = new HashSet<>();

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        String name = player.getName();
        if (PENDING.contains(name)) {
            return null;
        }
        if (!PlayerAttributeStore.exists(name)) {
            KomutechSupport.send(player, "§c你没有玩家属性数据，无法洗点。");
            return null;
        }
        Map<String, Object> preview = PlayerAttributeStore.load(name);
        if (preview == null) {
            KomutechSupport.send(player, "§c无法读取属性数据。");
            return null;
        }
        if (!hasAllocatedAboveBase(preview)) {
            KomutechSupport.send(player, "§e当前没有可重置的加点。");
            return null;
        }

        EquipmentSlot hand = ctx.hand() != null ? ctx.hand() : EquipmentSlot.HAND;
        int expected = previewRefund(preview);
        PENDING.add(name);
        KomutechSupport.send(player, "§d✦ 洗点珠：将清空已分配加点并返还属性点（修为/灵根不变）");
        KomutechSupport.send(player, "§e预计返还后属性点：§a" + expected);
        KomutechSupport.send(player, "§c输入 §f确认洗点 §c继续，输入 §fcancel §c取消：");
        KomutechChatInput.waitFor(player, input -> {
            PENDING.remove(name);
            if ("cancel".equalsIgnoreCase(input.trim())) {
                KomutechSupport.send(player, "§a已取消洗点。");
                return;
            }
            if (!"确认洗点".equals(input.replaceAll("§.", "").trim())) {
                KomutechSupport.send(player, "§c输入不匹配，操作取消。");
                return;
            }
            if (!consumeOrb(player, hand)) {
                KomutechSupport.send(player, "§c未检测到洗点珠，操作取消。");
                return;
            }
            Map<String, Object> data = PlayerAttributeStore.load(name);
            if (data == null) {
                KomutechSupport.send(player, "§c属性数据已丢失，无法洗点。");
                return;
            }
            int restored = applyRespec(data);
            AttributeApplier.applyPlayerAttributes(player, data, false);
            if (!PlayerAttributeStore.save(name, data)) {
                KomutechSupport.send(player, "§c保存失败，请联系管理员。");
                return;
            }
            KomutechSupport.send(player, "§a✦ 洗点完成！当前可分配属性点：§e" + restored);
            KomutechSupport.send(player, "§7请打开玩家属性菜单重新加点。");
        });
        return null;
    }

    /** 鉴灵时写入基底，供后续洗点还原。 */
    public static void captureBase(Map<String, Object> data) {
        if (data == null) {
            return;
        }
        Map<String, Object> base = new LinkedHashMap<>();
        for (String key : ALLOCATABLE_ATTRS) {
            base.put(key, PlayerAttributeStore.getInt(data, key, 0));
        }
        data.put(BASE_KEY, base);
    }

    static int applyRespec(Map<String, Object> data) {
        Map<String, Integer> base = readBase(data);
        int refund = PlayerAttributeStore.getInt(data, "属性点", 0);
        for (String key : ALLOCATABLE_ATTRS) {
            int cur = PlayerAttributeStore.getInt(data, key, 0);
            int floor = base.getOrDefault(key, 0);
            refund += Math.max(0, cur - floor);
            data.put(key, floor);
        }
        data.put("属性点", refund);
        captureBaseFromMap(data, base);
        return refund;
    }

    private static void captureBaseFromMap(Map<String, Object> data, Map<String, Integer> base) {
        Map<String, Object> store = new LinkedHashMap<>();
        for (String key : ALLOCATABLE_ATTRS) {
            store.put(key, base.getOrDefault(key, 0));
        }
        data.put(BASE_KEY, store);
    }

    private static boolean hasAllocatedAboveBase(Map<String, Object> data) {
        Map<String, Integer> base = readBase(data);
        for (String key : ALLOCATABLE_ATTRS) {
            if (PlayerAttributeStore.getInt(data, key, 0) > base.getOrDefault(key, 0)) {
                return true;
            }
        }
        return false;
    }

    private static int previewRefund(Map<String, Object> data) {
        Map<String, Integer> base = readBase(data);
        int refund = PlayerAttributeStore.getInt(data, "属性点", 0);
        for (String key : ALLOCATABLE_ATTRS) {
            int cur = PlayerAttributeStore.getInt(data, key, 0);
            refund += Math.max(0, cur - base.getOrDefault(key, 0));
        }
        return refund;
    }

    private static Map<String, Integer> readBase(Map<String, Object> data) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Object raw = data.get(BASE_KEY);
        if (raw instanceof Map<?, ?> map) {
            for (String key : ALLOCATABLE_ATTRS) {
                Object v = map.get(key);
                out.put(key, v instanceof Number n ? n.intValue() : 0);
            }
            return out;
        }
        // 旧存档无基底：按 0 处理，首次洗点可将当前加点全额退回
        for (String key : ALLOCATABLE_ATTRS) {
            out.put(key, 0);
        }
        return out;
    }

    private static boolean consumeOrb(Player player, EquipmentSlot hand) {
        PlayerInventory inv = player.getInventory();
        ItemStack stack = hand == EquipmentSlot.OFF_HAND ? inv.getItemInOffHand() : inv.getItemInMainHand();
        if (isOrb(stack)) {
            UseEvents.consumeOne(new UseEvents.Context(player, stack, hand));
            return true;
        }
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (!isOrb(item)) {
                continue;
            }
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
            } else {
                inv.setItem(i, null);
            }
            return true;
        }
        return false;
    }

    private static boolean isOrb(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        SlimefunItem sf = SlimefunItem.getByItem(stack);
        return sf != null && ITEM_ID.equals(sf.getId());
    }
}
