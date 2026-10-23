package tech.komutech.native_scripts.item;

import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.combat.ScrollCombatEngine;
import tech.komutech.native_scripts.support.PlayerAttributeStore;

public final class ScrollScript implements NativeScript {

    private final String skillId;

    public ScrollScript(String skillId) {
        this.skillId = skillId;
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("castScroll".equals(functionName) && args.length >= 3 && args[0] instanceof Player player) {
            ItemStack staff = args[1] instanceof ItemStack stack ? stack : player.getInventory().getItemInMainHand();
            if (args[2] instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> attr = (Map<String, Object>) map;
                ScrollCombatEngine.castScroll(player, staff, attr, skillId);
            }
        }
        return null;
    }
}
