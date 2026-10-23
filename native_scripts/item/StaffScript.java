package tech.komutech.native_scripts.item;

import java.util.Map;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.combat.ScrollCombatEngine;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;
import tech.komutech.native_scripts.support.PlayerAttributeStore;

public final class StaffScript implements NativeScript {

    private final String staffKey;

    public StaffScript(String staffKey) {
        this.staffKey = staffKey;
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onUse".equals(functionName)) {
            return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
        }
        if ("castScroll".equals(functionName) && args.length >= 3 && args[0] instanceof org.bukkit.entity.Player player) {
            Map<String, Object> attr = args[2] instanceof Map<?, ?> map ? PlayerAttributeStore.deepCopy(KomutechJsonMap(map)) : null;
            if (attr != null) {
                ItemStack staff = args[1] instanceof ItemStack stack ? stack : player.getInventory().getItemInMainHand();
                String skill = ScrollCombatEngine.getBoundSkill(staff);
                if (skill != null) {
                    ScrollCombatEngine.castScroll(player, staff, attr, skill);
                    PlayerAttributeStore.save(player.getName(), attr);
                }
            }
            return null;
        }
        return null;
    }

    private Object handleUse(UseEvents.Context ctx) {
        if (ctx.hand() != EquipmentSlot.HAND) {
            return null;
        }
        var player = ctx.player();
        ItemStack item = ctx.item();
        Map<String, Object> attr = PlayerAttributeStore.load(player.getName());
        if (attr == null) {
            KomutechSupport.send(player, "§c无法读取属性数据");
            return null;
        }
        if (player.isSneaking()) {
            String skill = ScrollCombatEngine.getBoundSkill(item);
            if (skill == null) {
                KomutechSupport.send(player, "§c未绑定卷轴");
                return null;
            }
            ScrollCombatEngine.castScroll(player, item, attr, skill);
            PlayerAttributeStore.save(player.getName(), attr);
            return null;
        }
        if (ScrollCombatEngine.handleStaffRightClick(player, () -> {
            ScrollCombatEngine.staffAttack(player, item, attr, staffKey);
            PlayerAttributeStore.save(player.getName(), attr);
        })) {
            ScrollCombatEngine.switchScroll(player, item);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> KomutechJsonMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
