package tech.komutech.native_scripts.tool;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.AdminToolsSupport;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;

public final class PositionRecorderScript implements NativeScript {

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
        Block block = AdminToolsSupport.rayTraceBlock(player, 5);
        if (block != null) {
            AdminToolsSupport.BlockPos pos = new AdminToolsSupport.BlockPos(
                block.getX(), block.getY(), block.getZ());
            String key = player.isSneaking() ? AdminToolsSupport.POS_KEY_SECOND : AdminToolsSupport.POS_KEY_FIRST;
            String label = player.isSneaking() ? "位置二" : "位置一";
            AdminToolsSupport.writePos(item, key, label, pos);
            KomutechSupport.send(player, "§a已记录" + label + "：X:" + pos.x() + " Y:" + pos.y() + " Z:" + pos.z());
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : null;
        AdminToolsSupport.BlockPos first = AdminToolsSupport.parsePos(lore, AdminToolsSupport.POS_KEY_FIRST);
        AdminToolsSupport.BlockPos second = AdminToolsSupport.parsePos(lore, AdminToolsSupport.POS_KEY_SECOND);
        if (first == null && second == null) {
            KomutechSupport.send(player, "§e暂无记录的位置");
            return null;
        }
        KomutechSupport.send(player, "§6=== 当前记录的位置 ===");
        if (first != null) {
            KomutechSupport.send(player, "§a位置一：X:" + first.x() + " Y:" + first.y() + " Z:" + first.z());
        }
        if (second != null) {
            KomutechSupport.send(player, "§a位置二：X:" + second.x() + " Y:" + second.y() + " Z:" + second.z());
        }
        return null;
    }
}
