package tech.komutech.native_scripts.tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.AdminToolsSupport;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;

public final class MaterialReplacerScript implements NativeScript {

    private static final Map<String, Material> stored = new ConcurrentHashMap<>();

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        Block target = player.getTargetBlockExact(3);
        if (target == null || target.getType().isAir()) {
            KomutechSupport.actionBar(player, "§c❌ 未瞄准有效方块！");
            return null;
        }
        if (!AdminToolsSupport.hasPermission(player, target.getLocation())) {
            KomutechSupport.send(player, "§c你没有权限在此区域操作方块！");
            player.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
            return null;
        }
        if (player.isSneaking()) {
            stored.put(player.getName(), target.getType());
            KomutechSupport.actionBar(player, "§a✅ 存储：§e" + target.getType().name().replace('_', ' '));
            player.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            return null;
        }
        Material material = stored.get(player.getName());
        if (material == null) {
            KomutechSupport.actionBar(player, "§c❌ 请先用Shift+右键存储材质！");
            return null;
        }
        if (target.getType() == material) {
            KomutechSupport.actionBar(player, "§c❌ 目标方块已经是该材质，无需替换");
            return null;
        }
        target.setType(material);
        KomutechSupport.actionBar(player, "§a✅ 替换为：§e" + material.name().replace('_', ' '));
        player.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
        return null;
    }
}
