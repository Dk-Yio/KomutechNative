package tech.komutech.native_scripts.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import tech.komutech.native_scripts.support.KomutechJson;
import tech.komutech.native_scripts.support.KomutechPaths;

public final class WanYanYiMachineStorage {

    private WanYanYiMachineStorage() {
    }

    public static Path bindingDir() {
        Path dir = KomutechPaths.configRoot().toPath().resolve("萬衍儀绑定");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }

    public static Path bindingPath(Location location) {
        return bindingDir().resolve(locationKey(location) + ".json");
    }

    public static String locationKey(Location location) {
        return location.getWorld().getUID() + "_"
            + location.getBlockX() + "_"
            + location.getBlockY() + "_"
            + location.getBlockZ();
    }

    public static void save(Location location, ItemStack item) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        Path path = bindingPath(location);
        if (item == null || item.getType().isAir() || !WanXiangGuiStorage.isStorageItem(item)) {
            clear(location);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("world", location.getWorld().getUID().toString());
        payload.put("x", location.getBlockX());
        payload.put("y", location.getBlockY());
        payload.put("z", location.getBlockZ());
        payload.put("item", Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        try {
            Files.writeString(path, KomutechJson.stringify(payload), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static ItemStack load(Location location) {
        if (location == null) {
            return null;
        }
        Path path = bindingPath(location);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            Map<String, Object> payload = KomutechJson.asMap(
                KomutechJson.parse(Files.readString(path, StandardCharsets.UTF_8))
            );
            String encoded = KomutechJson.getString(payload, "item", "");
            if (encoded.isEmpty()) {
                return null;
            }
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    public static void clear(Location location) {
        if (location == null) {
            return;
        }
        try {
            Files.deleteIfExists(bindingPath(location));
        } catch (IOException ignored) {
        }
    }
}
