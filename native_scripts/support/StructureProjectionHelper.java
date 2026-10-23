package tech.komutech.native_scripts.support;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * LogiTech-style structure projection: ItemDisplay only for all blocks,
 * TextDisplay labels only for addon (Slimefun) items that have a display name.
 */
public final class StructureProjectionHelper {

    private static final float PROJECTION_SCALE = 0.5f;

    public record SpawnedProjection(Display body, Display label) {
    }

    private StructureProjectionHelper() {
    }

    public static SpawnedProjection spawn(Location point, String materialKey, String slimefunId, ItemStack dynamicItem) {
        if (dynamicItem != null) {
            return spawnAddonItem(point, dynamicItem);
        }
        if (slimefunId != null) {
            SlimefunItem sf = resolveSlimefunItem(slimefunId);
            if (sf != null) {
                return spawnAddonItem(point, sf.getItem().clone());
            }
        }
        if (materialKey == null || materialKey.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(materialKey.toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            return null;
        }
        return new SpawnedProjection(
            DisplayReflectionHelper.spawnItemDisplay(point, new ItemStack(material), PROJECTION_SCALE),
            null
        );
    }

    private static SpawnedProjection spawnAddonItem(Location point, ItemStack item) {
        Display label = null;
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            label = DisplayReflectionHelper.spawnTextDisplay(
                point.clone().add(0, 0.8, 0), meta.getDisplayName(), 1f
            );
        }
        return new SpawnedProjection(
            DisplayReflectionHelper.spawnItemDisplay(point, item, PROJECTION_SCALE),
            label
        );
    }

    private static SlimefunItem resolveSlimefunItem(String id) {
        SlimefunItem direct = SlimefunItem.getById(id);
        if (direct != null) {
            return direct;
        }
        for (SlimefunItem candidate : Slimefun.getRegistry().getAllSlimefunItems()) {
            if (candidate != null && candidate.getId() != null && candidate.getId().equalsIgnoreCase(id)) {
                return candidate;
            }
        }
        return null;
    }
}
