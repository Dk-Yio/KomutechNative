package tech.komutech.native_scripts.support;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;

public final class DisplayReflectionHelper {

    private DisplayReflectionHelper() {
    }

    public static BlockDisplay spawnBlockDisplay(Location location, BlockData blockData) {
        return spawnBlockDisplay(location, blockData, 2f);
    }

    public static BlockDisplay spawnBlockDisplay(Location location, BlockData blockData, float scale) {
        return location.getWorld().spawn(location, BlockDisplay.class, entity -> {
            entity.setBlock(blockData);
            entity.setGlowing(true);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setViewRange(100);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            applyScale(entity, scale, scale, scale);
        });
    }

    public static ItemDisplay spawnItemDisplay(Location location, ItemStack item, float scale) {
        return location.getWorld().spawn(location, ItemDisplay.class, entity -> {
            entity.setItemStack(item);
            entity.setGlowing(true);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setViewRange(100);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            applyScale(entity, scale, scale, scale);
        });
    }

    public static TextDisplay spawnTextDisplay(Location location, String text, float scale) {
        return location.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.setText(text);
            entity.setSeeThrough(true);
            entity.setDefaultBackground(false);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setViewRange(50);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            applyScale(entity, scale, scale, scale);
        });
    }

    public static void applyScale(Display display, float x, float y, float z) {
        try {
            Class<?> vector3f = Class.forName("org.joml.Vector3f");
            Class<?> axisAngle4f = Class.forName("org.joml.AxisAngle4f");
            Object translation = vector3f.getConstructor(float.class, float.class, float.class).newInstance(0f, 0f, 0f);
            Object leftRotation = axisAngle4f.getConstructor(float.class, float.class, float.class, float.class)
                .newInstance(0f, 0f, 1f, 0f);
            Object scale = vector3f.getConstructor(float.class, float.class, float.class).newInstance(x, y, z);
            Object rightRotation = axisAngle4f.getConstructor(float.class, float.class, float.class, float.class)
                .newInstance(0f, 0f, 1f, 0f);
            Transformation transformation = (Transformation) Transformation.class
                .getConstructor(vector3f, axisAngle4f, vector3f, axisAngle4f)
                .newInstance(translation, leftRotation, scale, rightRotation);
            display.setTransformation(transformation);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    public static BlockData parseBlockData(String materialKey) {
        if (materialKey == null || materialKey.isBlank()) {
            return Material.HORN_CORAL_FAN.createBlockData();
        }
        String normalized = materialKey.contains(":") ? materialKey.substring(materialKey.indexOf(':') + 1) : materialKey;
        Material material = Material.matchMaterial(normalized.toUpperCase());
        if (material == null) {
            material = Material.HORN_CORAL_FAN;
        }
        return material.createBlockData();
    }
}
