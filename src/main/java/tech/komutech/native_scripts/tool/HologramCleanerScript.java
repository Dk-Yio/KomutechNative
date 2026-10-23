package tech.komutech.native_scripts.tool;

import tech.komutech.native_scripts.support.EntityQueries;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.AdminToolsSupport;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;

public final class HologramCleanerScript implements NativeScript {

    private static final Map<UUID, Entity> targets = new ConcurrentHashMap<>();

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        if (!AdminToolsSupport.checkCooldown(player, 500)) {
            return null;
        }
        if (!AdminToolsSupport.hasPermission(player, player.getLocation())) {
            KomutechSupport.send(player, "§c你没有权限在此区域移除全息文字！");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
            return null;
        }
        Entity selected = targets.get(player.getUniqueId());
        if (selected != null && selected.isValid()) {
            if (!AdminToolsSupport.hasPermission(player, selected.getLocation())) {
                KomutechSupport.send(player, "§c你没有权限移除这个全息文字！");
                targets.remove(player.getUniqueId());
                return null;
            }
            selected.remove();
            targets.remove(player.getUniqueId());
            KomutechSupport.actionBar(player, "§a成功清除已选中的全息文字！");
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
            return null;
        }
        targets.remove(player.getUniqueId());
        ArmorStand nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : EntityQueries.entities(player.getLocation(), 3)) {
            if (!(entity instanceof ArmorStand stand) || !stand.isInvisible() || stand.hasGravity() || !stand.isMarker()) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(stand.getLocation());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = stand;
            }
        }
        if (nearest == null) {
            KomutechSupport.send(player, "§e3格范围内未找到全息文字！");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
            return null;
        }
        String content = nearest.getCustomName();
        KomutechSupport.send(player, "§e检测到最近的全息文字: " + (content == null ? "§7(无文字)" : content));
        KomutechSupport.send(player, "§7再次右键可清除该文字。");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1f);
        targets.put(player.getUniqueId(), nearest);
        return null;
    }
}
