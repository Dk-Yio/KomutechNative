package tech.komutech.native_scripts.support;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class SlimefunBlockPlacer {

    private SlimefunBlockPlacer() {
    }

    public static boolean place(Location location, String sfId) {
        if (location == null || sfId == null || sfId.isEmpty()) {
            return false;
        }
        SlimefunItem sfItem = SlimefunItem.getById(sfId);
        if (sfItem == null) {
            return false;
        }
        ItemStack template = sfItem.getItem();
        if (template == null || template.getType().isAir()) {
            return false;
        }
        Block block = location.getBlock();
        block.setType(template.getType());
        if (!registerSlimefunBlock(location, sfId)) {
            block.setType(Material.AIR);
            return false;
        }
        return true;
    }

    public static boolean placeVanilla(Location location, String materialName) {
        if (location == null || materialName == null || materialName.isEmpty()) {
            return false;
        }
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            try {
                material = Material.valueOf(materialName.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        location.getBlock().setType(material);
        return true;
    }

    public static boolean remove(Location location) {
        if (location == null) {
            return false;
        }
        if (MachineScriptHelper.getSfItem(location) != null) {
            try {
                Class<?> slimefunClass = Class.forName("io.github.thebusybiscuit.slimefun4.implementation.Slimefun");
                Object databaseManager = slimefunClass.getMethod("getDatabaseManager").invoke(null);
                Object controller = databaseManager.getClass().getMethod("getBlockDataController").invoke(databaseManager);
                controller.getClass().getMethod("removeBlock", Location.class).invoke(controller, location);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        Block block = location.getBlock();
        if (!block.getType().isAir()) {
            block.setType(Material.AIR);
        }
        return true;
    }

    private static boolean registerSlimefunBlock(Location location, String sfId) {
        try {
            Class<?> slimefunClass = Class.forName("io.github.thebusybiscuit.slimefun4.implementation.Slimefun");
            Object databaseManager = slimefunClass.getMethod("getDatabaseManager").invoke(null);
            Object controller = databaseManager.getClass().getMethod("getBlockDataController").invoke(databaseManager);
            controller.getClass().getMethod("createBlock", Location.class, String.class).invoke(controller, location, sfId);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
