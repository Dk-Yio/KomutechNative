package tech.komutech.native_scripts.machine;

import tech.komutech.native_scripts.support.EntityQueries;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import tech.origintech.NativeAddonConfig;
import tech.komutech.native_scripts.support.StructureProjectionHelper;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class UltimateSynthProjection {

    private static final NamespacedKey PROJECTION_KEY = new NamespacedKey(
        NativeAddonConfig.PLUGIN_NAME.toLowerCase(), "zjs_projection");
    private static final String PROJECTION_STATE_KEY = UltimateSynthSupport.DATA_PROJECTION;

    private static final Map<String, List<Display>> PROJECTIONS = new ConcurrentHashMap<>();

    private UltimateSynthProjection() {
    }

    public static boolean hasProjection(Location core) {
        if (isProjectionEnabled(core) && !hasLiveDisplays(core)) {
            setProjectionState(core, false);
            return false;
        }
        return hasLiveDisplays(core);
    }

    public static void toggleProjection(Location core, int dir) {
        if (hasProjection(core)) {
            removeProjection(core);
        } else {
            spawnProjection(core, dir);
        }
    }

    public static void spawnProjection(Location core, int dir) {
        removeProjection(core);
        List<Display> spawned = new ArrayList<>();
        String coreKey = UltimateSynthSupport.locationKey(core);
        for (UltimateSynthSupport.StructureBlock block : UltimateSynthSupport.coreStructure()) {
            UltimateSynthSupport.Rotated rotated = UltimateSynthSupport.rot(block.x(), block.z(), dir);
            Location point = core.clone().add(rotated.x(), block.y(), rotated.z()).add(0.5, 0.5, 0.5);
            StructureProjectionHelper.SpawnedProjection projection = StructureProjectionHelper.spawn(
                point, block.material(), block.sfId(), null
            );
            if (projection == null) {
                continue;
            }
            tagDisplay(projection.body(), coreKey);
            spawned.add(projection.body());
            if (projection.label() != null) {
                tagDisplay(projection.label(), coreKey);
                spawned.add(projection.label());
            }
        }
        if (!spawned.isEmpty()) {
            PROJECTIONS.put(coreKey, spawned);
            setProjectionState(core, true);
        }
    }

    public static void removeProjection(Location core) {
        if (core == null) {
            return;
        }
        String key = UltimateSynthSupport.locationKey(core);
        List<Display> displays = PROJECTIONS.remove(key);
        if (displays != null) {
            for (Display display : displays) {
                if (!display.isDead()) {
                    display.remove();
                }
            }
        }
        removeTaggedEntities(core);
        setProjectionState(core, false);
    }

    public static void clearForCoreBreak(Location core) {
        removeProjection(core);
    }

    private static boolean hasLiveDisplays(Location core) {
        String key = UltimateSynthSupport.locationKey(core);
        List<Display> displays = PROJECTIONS.get(key);
        if (displays == null || displays.isEmpty()) {
            return scanTaggedEntities(core);
        }
        displays.removeIf(Display::isDead);
        if (displays.isEmpty()) {
            PROJECTIONS.remove(key);
            return scanTaggedEntities(core);
        }
        return true;
    }

    private static boolean scanTaggedEntities(Location core) {
        String coreKey = UltimateSynthSupport.locationKey(core);
        for (Entity entity : EntityQueries.entities(core.getWorld(), core, 16)) {
            if (entity instanceof Display && !entity.isDead()) {
                String tagged = entity.getPersistentDataContainer().get(PROJECTION_KEY, PersistentDataType.STRING);
                if (coreKey.equals(tagged)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isProjectionEnabled(Location core) {
        return "1".equals(MachineScriptHelper.getData(core, PROJECTION_STATE_KEY));
    }

    private static void removeTaggedEntities(Location core) {
        String coreKey = UltimateSynthSupport.locationKey(core);
        for (Entity entity : EntityQueries.entities(core.getWorld(), core, 16)) {
            if (!(entity instanceof Display)) {
                continue;
            }
            String tagged = entity.getPersistentDataContainer().get(PROJECTION_KEY, PersistentDataType.STRING);
            if (coreKey.equals(tagged) && !entity.isDead()) {
                entity.remove();
            }
        }
    }

    private static void tagDisplay(Display display, String coreKey) {
        display.getPersistentDataContainer().set(PROJECTION_KEY, PersistentDataType.STRING, coreKey);
    }

    private static void setProjectionState(Location core, boolean active) {
        MachineScriptHelper.setData(core, PROJECTION_STATE_KEY, active ? "1" : "0");
    }
}
