package tech.komutech.native_scripts.cultivation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import tech.komutech.native_scripts.support.DisplayReflectionHelper;
import tech.komutech.native_scripts.support.EntityQueries;
import tech.origintech.NativeAddonConfig;
import tech.origintech.native_scripts.support.MachineScriptHelper;

final class PuTuanDisplayHelper {

    private static final NamespacedKey MEDITATION_TAG = new NamespacedKey(
        NativeAddonConfig.PLUGIN_NAME.toLowerCase(), "pu_tuan_display");

    private PuTuanDisplayHelper() {
    }

    static NamespacedKey tagKey() {
        return MEDITATION_TAG;
    }

    static boolean isProtected(Entity entity) {
        if (!(entity instanceof Display display)) {
            return false;
        }
        return display.getPersistentDataContainer().has(MEDITATION_TAG, PersistentDataType.STRING);
    }

    static void tag(Display display, Location matLoc) {
        display.getPersistentDataContainer().set(
            MEDITATION_TAG, PersistentDataType.STRING, locationKey(matLoc));
    }

    static TextDisplay spawnStatusHologram(Location matLoc, String text) {
        // 只清同坐标残留全息文字，保留珊瑚扇等 BlockDisplay 投影
        removeTaggedTextAt(matLoc, null);
        Location holoLoc = matLoc.clone().add(0.5, 1.55, 0.5);
        TextDisplay display = DisplayReflectionHelper.spawnTextDisplay(holoLoc, text, 0.7f);
        tag(display, matLoc);
        return display;
    }

    static void updateHologram(Location matLoc, String text) {
        String holoId = MachineScriptHelper.getData(matLoc, PuTuanScript.HOLO_UUID_KEY);
        Entity entity = holoId == null ? null : findEntity(matLoc, holoId);
        if (entity instanceof TextDisplay textDisplay && !textDisplay.isDead()) {
            textDisplay.setText(text);
            // 只清多余全息，绝不删投影（珊瑚扇）
            removeTaggedTextAt(matLoc, textDisplay.getUniqueId());
            return;
        }
        TextDisplay created = spawnStatusHologram(matLoc, text);
        MachineScriptHelper.setData(matLoc, PuTuanScript.HOLO_UUID_KEY, created.getUniqueId().toString());
    }

    /**
     * 按 UUID 移除；找不到时仍扫附近 PDC 标签，避免崩溃后 UUID 丢失导致永久残留。
     */
    static void removeStoredOrNearby(Location matLoc, String uuidKey) {
        String uuid = MachineScriptHelper.getData(matLoc, uuidKey);
        UUID keepNone = null;
        if (uuid != null) {
            Entity entity = findEntity(matLoc, uuid);
            if (entity != null && !entity.isDead()) {
                entity.remove();
            }
        }
        removeTaggedAt(matLoc, keepNone);
    }

    /** 移除该蒲团坐标上所有带 pu_tuan_display 标签的 Display（全息+投影）。 */
    static int removeTaggedAt(Location matLoc) {
        return removeTaggedAt(matLoc, null);
    }

    /** 仅移除同坐标残留 TextDisplay 全息，保留 BlockDisplay 珊瑚扇投影。 */
    static int removeTaggedTextAt(Location matLoc, UUID keep) {
        return removeTaggedOfType(matLoc, keep, TextDisplay.class);
    }

    /** 仅移除同坐标投影（BlockDisplay），保留全息文字。 */
    static int removeTaggedProjectionsAt(Location matLoc, UUID keep) {
        return removeTaggedOfType(matLoc, keep, org.bukkit.entity.BlockDisplay.class);
    }

    private static int removeTaggedOfType(Location matLoc, UUID keep, Class<? extends Display> type) {
        if (matLoc == null || matLoc.getWorld() == null) {
            return 0;
        }
        String key = locationKey(matLoc);
        int removed = 0;
        for (Entity entity : EntityQueries.entities(matLoc.clone().add(0.5, 1.0, 0.5), 3.5)) {
            if (!type.isInstance(entity) || !(entity instanceof Display display) || display.isDead()) {
                continue;
            }
            if (keep != null && keep.equals(display.getUniqueId())) {
                continue;
            }
            String tagged = display.getPersistentDataContainer().get(MEDITATION_TAG, PersistentDataType.STRING);
            if (!key.equals(tagged)) {
                continue;
            }
            display.remove();
            removed++;
        }
        return removed;
    }

    static int removeTaggedAt(Location matLoc, UUID keep) {
        if (matLoc == null || matLoc.getWorld() == null) {
            return 0;
        }
        String key = locationKey(matLoc);
        int removed = 0;
        for (Entity entity : EntityQueries.entities(matLoc.clone().add(0.5, 1.0, 0.5), 3.5)) {
            if (!(entity instanceof Display display) || display.isDead()) {
                continue;
            }
            if (keep != null && keep.equals(display.getUniqueId())) {
                continue;
            }
            String tagged = display.getPersistentDataContainer().get(MEDITATION_TAG, PersistentDataType.STRING);
            if (!key.equals(tagged)) {
                continue;
            }
            display.remove();
            removed++;
        }
        return removed;
    }

    /**
     * 扫描已加载区块：清理无主/状态为空闲却仍显示修炼的残留 Display。
     * @return 移除的实体数
     */
    static int sweepChunk(Chunk chunk, OrphanDecision decision) {
        if (chunk == null || !chunk.isLoaded()) {
            return 0;
        }
        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Display display) || display.isDead()) {
                continue;
            }
            String tagged = display.getPersistentDataContainer().get(MEDITATION_TAG, PersistentDataType.STRING);
            if (tagged == null || tagged.isEmpty()) {
                continue;
            }
            if (decision.shouldRemove(display, tagged)) {
                display.remove();
                removed++;
            }
        }
        return removed;
    }

    static int sweepLoadedWorlds(OrphanDecision decision) {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                removed += sweepChunk(chunk, decision);
            }
        }
        return removed;
    }

    static Entity findEntity(Location matLoc, String uuid) {
        if (matLoc == null || matLoc.getWorld() == null || uuid == null) {
            return null;
        }
        try {
            return matLoc.getWorld().getEntity(UUID.fromString(uuid));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static Location parseLocationKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String[] parts = key.split(",");
        if (parts.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String locationKey(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    @FunctionalInterface
    interface OrphanDecision {
        boolean shouldRemove(Display display, String locationKey);
    }

    /** 收集某点附近残留，供指令反馈。 */
    static List<Display> findTaggedNear(Location center, double radius) {
        List<Display> list = new ArrayList<>();
        if (center == null || center.getWorld() == null) {
            return list;
        }
        for (Entity entity : EntityQueries.entities(center, radius)) {
            if (entity instanceof Display display && isProtected(display) && !display.isDead()) {
                list.add(display);
            }
        }
        return list;
    }
}
