package tech.komutech.native_scripts.tool;

import tech.komutech.native_scripts.support.EntityQueries;
import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import tech.origintech.NativeAddonConfig;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.AdminToolsSupport;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;

public final class DisplayCleanerScript implements NativeScript {

    private static final String MODE_MARKER = "§6§l当前模式:";
    private static final String SINGLE_LORE = "§6§l当前模式: §7单个清除";
    private static final String RANGE_LORE = "§6§l当前模式: §b范围清除";
    private static final double SINGLE_MAX_RADIUS = 32.0;
    private static final int SINGLE_CHUNK_RADIUS = 2;
    private static final NamespacedKey MEDITATION_DISPLAY_KEY = new NamespacedKey(
        NativeAddonConfig.PLUGIN_NAME.toLowerCase(), "pu_tuan_display");

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
        if (!AdminToolsSupport.checkCooldown(player, 1000)) {
            return null;
        }
        if (!AdminToolsSupport.hasPermission(player, player.getLocation())) {
            KomutechSupport.send(player, "§c你没有权限在此区域清除展示物品！");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
            return null;
        }
        if (player.isSneaking()) {
            AdminToolsSupport.switchLoreMode(item, MODE_MARKER, SINGLE_LORE, RANGE_LORE);
            ItemMeta meta = item.getItemMeta();
            boolean range = meta != null && meta.hasLore()
                && meta.getLore().stream().anyMatch(line -> line != null && line.contains("范围"));
            KomutechSupport.actionBar(player, "§a已切换至: " + (range ? "范围清除" : "单个清除"));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : List.of();
        if ("range".equals(AdminToolsSupport.loreMode(lore, MODE_MARKER, "single", "范围"))) {
            clearRange(player);
        } else {
            clearSingle(player);
        }
        return null;
    }

    private void clearSingle(Player player) {
        Display nearest = findNearestDisplay(player);
        if (nearest == null) {
            KomutechSupport.send(player, "§c附近没有全息物品");
            return;
        }
        if (!AdminToolsSupport.hasPermission(player, nearest.getLocation())) {
            KomutechSupport.send(player, "§c你没有权限移除这个全息物品！");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
            return;
        }
        nearest.remove();
        KomutechSupport.actionBar(player, "§a已清除最近的全息物品及文字");
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
    }

    /**
     * 按区块扫描附近 Display，避免 64³ getNearbyEntities 全量遍历。
     */
    private static Display findNearestDisplay(Player player) {
        Location origin = player.getLocation();
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }

        double maxRadiusSq = SINGLE_MAX_RADIUS * SINGLE_MAX_RADIUS;
        Display nearest = null;
        double minSq = Double.MAX_VALUE;

        Chunk center = origin.getChunk();
        int baseX = center.getX();
        int baseZ = center.getZ();
        for (int dx = -SINGLE_CHUNK_RADIUS; dx <= SINGLE_CHUNK_RADIUS; dx++) {
            for (int dz = -SINGLE_CHUNK_RADIUS; dz <= SINGLE_CHUNK_RADIUS; dz++) {
                if (!world.isChunkLoaded(baseX + dx, baseZ + dz)) {
                    continue;
                }
                for (Entity entity : world.getChunkAt(baseX + dx, baseZ + dz).getEntities()) {
                    if (!(entity instanceof ItemDisplay || entity instanceof TextDisplay) || entity.isDead()) {
                        continue;
                    }
                    if (isProtectedDisplay(entity)) {
                        continue;
                    }
                    double distanceSq = entity.getLocation().distanceSquared(origin);
                    if (distanceSq > maxRadiusSq || distanceSq >= minSq) {
                        continue;
                    }
                    minSq = distanceSq;
                    nearest = (Display) entity;
                }
            }
        }
        return nearest;
    }

    private void clearRange(Player player) {
        int count = 0;
        int skipped = 0;
        for (Entity entity : EntityQueries.entities(player.getLocation(), 10)) {
            if (!(entity instanceof ItemDisplay || entity instanceof TextDisplay) || entity.isDead()) {
                continue;
            }
            if (isProtectedDisplay(entity)) {
                continue;
            }
            if (AdminToolsSupport.hasPermission(player, entity.getLocation())) {
                entity.remove();
                count++;
            } else {
                skipped++;
            }
        }
        if (count > 0) {
            KomutechSupport.actionBar(player, "§a已清除 " + count + " 个全息物品及文字"
                + (skipped > 0 ? "，跳过 " + skipped + " 个无权限的" : ""));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
        } else if (skipped > 0) {
            KomutechSupport.send(player, "§c没有可清除的全息物品（无权限）");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
        } else {
            KomutechSupport.send(player, "§c范围内没有全息物品");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
        }
    }

    private static boolean isProtectedDisplay(Entity entity) {
        if (!(entity instanceof org.bukkit.entity.Display display)) {
            return false;
        }
        return display.getPersistentDataContainer().has(MEDITATION_DISPLAY_KEY, PersistentDataType.STRING);
    }
}
